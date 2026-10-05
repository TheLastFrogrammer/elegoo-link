/* CC2 MQTT behavior derived from ELEGOO's Apache-2.0 adapter; Android port, 2026. */
package io.github.thelastfrogrammer.elink;

import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** One foreground connection. Never retries a printer-changing command. */
public final class Cc2Session implements AutoCloseable {
    public interface Listener {
        void connection(String message, boolean registered);
        void status(JSONObject snapshot);
        void result(String message);
        void uploadProgress(int percent);
    }
    private volatile Listener listener;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService transfer = Executors.newSingleThreadExecutor();
    private final String clientId = "1_PC_" + (1000 + new java.security.SecureRandom().nextInt(9000));
    private final String requestId = clientId + "_req";
    private final PrinterHttp http;
    private final Cc2Codec codec = new Cc2Codec();
    private final AtomicInteger ids = new AtomicInteger(1);
    private final Map<Integer, Pending> pending = new HashMap<>(); // worker thread only
    private final CompletableFuture<JSONObject> registration = new CompletableFuture<>();
    private volatile MqttClient mqtt;
    private volatile PrinterHttp uploadHttp;
    private volatile boolean closed, ready, uploading;
    private volatile long statusAt;
    private String base;
    private static final class Pending {
        final int method; final long deadline;
        Pending(int method) { this.method = method; deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8); }
    }
    public Cc2Session(String host, String accessCode, Listener listener) {
        http = new PrinterHttp(host, accessCode); this.listener = listener;
    }
    public boolean ready() { return ready && !closed; }
    public boolean fresh() { return ready() && statusAt != 0 && System.nanoTime() - statusAt < TimeUnit.SECONDS.toNanos(20); }
    public boolean uploading() { return uploading; }
    public void connect() { execute(this::connectOnWorker); }
    private void connectOnWorker() {
        try {
            emitConnection("Reading printer information…", false);
            JSONObject info = http.systemInfo();
            if (closed) return;
            base = "elegoo/" + info.getString("sn") + "/";
            MqttClient client = new MqttClient("tcp://" + http.host() + ":1883", clientId, new MemoryPersistence());
            mqtt = client;
            client.setTimeToWait(8000);
            client.setCallback(new MqttCallback() {
                public void connectionLost(Throwable cause) {
                    registration.completeExceptionally(new IllegalStateException("MQTT connection lost"));
                    execute(() -> fail("Connection lost. Reconnect to the printer."));
                }
                public void deliveryComplete(IMqttDeliveryToken token) { }
                public void messageArrived(String topic, MqttMessage message) {
                    if (closed) return;
                    if (message.getPayload().length > 2 * 1024 * 1024) { execute(() -> fail("Printer message too large")); return; }
                    try {
                        JSONObject payload = new JSONObject(new String(message.getPayload(), StandardCharsets.UTF_8));
                        if (topic.equals(base + requestId + "/register_response")) registration.complete(payload);
                        else execute(() -> handle(topic, payload));
                    } catch (Exception ignored) { /* Ignore non-JSON events; do not log credentials or payloads. */ }
                }
            });
            MqttConnectOptions options = new MqttConnectOptions();
            options.setUserName("elegoo"); options.setPassword(http.token().toCharArray());
            options.setCleanSession(true); options.setAutomaticReconnect(false);
            options.setConnectionTimeout(5); options.setKeepAliveInterval(20);
            emitConnection("Connecting…", false);
            client.connect(options);
            if (closed) return;
            client.subscribe(new String[] {base + clientId + "/api_response", base + "api_status", base + requestId + "/register_response"}, new int[] {0, 0, 0});
            publish(base + "api_register", new JSONObject().put("client_id", clientId).put("request_id", requestId));
            JSONObject reply = registration.get(3, TimeUnit.SECONDS);
            if (!Cc2Codec.validRegistration(reply, clientId)) throw new IllegalStateException("Printer registration rejected: " + reply.optString("error", "unknown reason"));
            if (closed) return;
            ready = true;
            emitConnection("Connected on local Wi-Fi", true);
            send(Cc2Codec.ATTRIBUTES); send(Cc2Codec.STATUS);
            worker.scheduleWithFixedDelay(() -> {
                if (!ready()) return;
                try { publish(base + clientId + "/api_request", new JSONObject().put("type", "PING")); }
                catch (Exception exception) { fail("Heartbeat failed. Reconnect to the printer."); }
            }, 10, 10, TimeUnit.SECONDS);
            worker.scheduleWithFixedDelay(() -> {
                if (!ready()) return;
                if (pending.values().stream().noneMatch(p -> p.method == Cc2Codec.STATUS)) send(Cc2Codec.STATUS);
            }, 15, 15, TimeUnit.SECONDS);
            worker.scheduleWithFixedDelay(this::expireRequests, 1, 1, TimeUnit.SECONDS);
        } catch (Exception exception) {
            if (!closed) fail(error(exception));
        } finally { if (closed) disposeMqtt(); }
    }
    public void refresh() { execute(() -> { if (ready()) send(Cc2Codec.STATUS); }); }
    public void command(int method) {
        execute(() -> {
            try {
                JSONObject snapshot = codec.snapshot();
                if (!fresh() || (method == Cc2Codec.PAUSE ? !Cc2Codec.canPause(snapshot) : method != Cc2Codec.STOP || !Cc2Codec.canStop(snapshot))) {
                    emitResult("Control unavailable. Refresh the printer status first."); return;
                }
                if (pending.values().stream().anyMatch(p -> p.method == Cc2Codec.PAUSE || p.method == Cc2Codec.STOP)) {
                    emitResult("Waiting for the previous command response."); return;
                }
                send(method);
            } catch (Exception exception) { emitResult(error(exception)); }
        });
    }
    private void send(int method) {
        if (!ready()) return;
        int id = ids.getAndIncrement();
        try {
            pending.put(id, new Pending(method));
            publish(base + clientId + "/api_request", Cc2Codec.request(id, method));
            if (method == Cc2Codec.PAUSE || method == Cc2Codec.STOP) emitResult("Command sent; waiting for printer acknowledgement…");
        } catch (Exception exception) {
            pending.remove(id);
            emitResult("Request failed; outcome unknown. Refresh before trying again.");
        }
    }
    private void publish(String topic, JSONObject payload) throws Exception {
        if (closed) throw new IllegalStateException("Session closed");
        mqtt.publish(topic, payload.toString().getBytes(StandardCharsets.UTF_8), 0, false);
    }
    private void handle(String topic, JSONObject message) {
        if (!ready()) return;
        try {
            boolean response = topic.equals(base + clientId + "/api_response");
            int method = message.optInt("method", -1);
            if (response) {
                Pending request = pending.get(message.optInt("id", -1));
                if (request == null || method != request.method) return;
                pending.remove(message.getInt("id"));
                JSONObject result = message.optJSONObject("result");
                if (result == null || result.optInt("error_code", -1) != 0) {
                    emitResult("Printer rejected request (code " + (result == null ? "missing" : result.optInt("error_code", -1)) + ")"); return;
                }
                if (method == Cc2Codec.PAUSE || method == Cc2Codec.STOP) {
                    emitResult("Printer acknowledged " + (method == Cc2Codec.PAUSE ? "pause" : "stop") + ". Waiting for status update.");
                    send(Cc2Codec.STATUS); return;
                }
            } else if (!topic.equals(base + "api_status") || method != 6000) return;
            if (method == Cc2Codec.STATUS || method == 6000) {
                if (codec.accept(message)) {
                    statusAt = System.nanoTime();
                    Listener current = listener; if (current != null) current.status(codec.snapshot());
                } else if (pending.values().stream().noneMatch(p -> p.method == Cc2Codec.STATUS)) {
                    statusAt = 0; send(Cc2Codec.STATUS);
                }
            }
        } catch (Exception exception) { emitResult("Could not parse printer response. Refresh status."); }
    }
    private void expireRequests() {
        Iterator<Pending> iterator = pending.values().iterator();
        while (iterator.hasNext()) {
            Pending request = iterator.next();
            if (System.nanoTime() <= request.deadline) continue;
            iterator.remove();
            if (request.method == Cc2Codec.PAUSE || request.method == Cc2Codec.STOP)
                emitResult("No command acknowledgement. Outcome unknown; refresh before trying again.");
            else if (request.method == Cc2Codec.STATUS) { statusAt = 0; emitResult("Status request timed out. Controls disabled until fresh status arrives."); }
        }
    }
    public synchronized void upload(File file, String name) {
        if (!ready() || uploading) return;
        uploading = true;
        PrinterHttp uploader = new PrinterHttp(http.host(), http.token()); uploadHttp = uploader;
        transfer.execute(() -> {
            try {
                uploader.upload(file, name, percent -> { Listener current = listener; if (current != null && !closed) current.uploadProgress(percent); });
                emitResult("Upload acknowledged by printer. Start the job from the printer screen.");
            } catch (Exception exception) { if (!closed) emitResult("Upload failed: " + error(exception)); }
            finally { uploading = false; uploadHttp = null; }
        });
    }
    private void execute(Runnable task) { if (!closed) try { worker.execute(() -> { if (!closed) task.run(); }); } catch (RejectedExecutionException ignored) { } }
    private void emitConnection(String message, boolean connected) { Listener current = listener; if (current != null && !closed) current.connection(message, connected); }
    private void emitResult(String message) { Listener current = listener; if (current != null && !closed) current.result(message); }
    private void fail(String message) { ready = false; statusAt = 0; emitConnection(message, false); close(); }
    private static String error(Exception exception) {
        if (exception instanceof TimeoutException) return "Printer registration timed out";
        if (exception instanceof MqttException) return "MQTT connection failed (code " + ((MqttException) exception).getReasonCode() + "). Check LAN mode and access code.";
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }
    private void disposeMqtt() {
        MqttClient current = mqtt;
        if (current == null) return;
        try { if (current.isConnected()) current.disconnectForcibly(0, 500, false); current.close(true); } catch (Exception ignored) { }
    }
    public void close() {
        if (closed) return;
        closed = true; ready = false; listener = null;
        registration.completeExceptionally(new IllegalStateException("Session closed"));
        http.cancel(); PrinterHttp uploader = uploadHttp; if (uploader != null) uploader.cancel();
        worker.shutdownNow(); transfer.shutdownNow();
        Thread cleanup = new Thread(this::disposeMqtt, "mqtt-cleanup"); cleanup.setDaemon(true); cleanup.start();
    }
}
