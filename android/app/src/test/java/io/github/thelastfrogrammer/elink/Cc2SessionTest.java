package io.github.thelastfrogrammer.elink;

import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Runs the production session through fake HTTP/MQTT transports, not a duplicated session model. */
public class Cc2SessionTest {
    private static final class Listener implements Cc2Session.Listener {
        final BlockingQueue<JSONObject> statuses = new LinkedBlockingQueue<>(), canvases = new LinkedBlockingQueue<>(), attrs = new LinkedBlockingQueue<>();
        final BlockingQueue<String> results = new LinkedBlockingQueue<>(), failures = new LinkedBlockingQueue<>();
        volatile boolean retryable;
        public void connection(String message, boolean registered) { }
        public void status(JSONObject snapshot) { statuses.add(snapshot); }
        public void result(String message) { results.add(message); }
        public void uploadProgress(int percent) { }
        public void attributes(JSONObject data) { attrs.add(data); }
        public void canvas(JSONObject data) { canvases.add(data); }
        public void failure(String message, boolean canRetry) { retryable = canRetry; failures.add(message); }
    }
    private static HttpURLConnection info(URL url, String response) {
        return new HttpURLConnection(url) {
            public void connect() { }
            public void disconnect() { }
            public boolean usingProxy() { return false; }
            public int getResponseCode() { return 200; }
            public InputStream getInputStream() { return new ByteArrayInputStream(response.getBytes(StandardCharsets.UTF_8)); }
        };
    }
    private static class FakeMqtt extends MqttClient {
        MqttCallback callback;
        String responseTopic;
        volatile boolean connected;
        String password;
        final List<String> subscriptions = new CopyOnWriteArrayList<>();
        final BlockingQueue<JSONObject> writes = new LinkedBlockingQueue<>();
        final List<JSONObject> requests = new CopyOnWriteArrayList<>();
        FakeMqtt(String uri, String id) throws MqttException { super(uri, id, new MemoryPersistence()); }
        @Override public void setCallback(MqttCallback callback) { this.callback = callback; }
        @Override public void connect(MqttConnectOptions options) throws MqttException {
            assertEquals("elegoo", options.getUserName()); assertFalse(options.isAutomaticReconnect()); password = new String(options.getPassword()); connected = true;
            assertEquals(MqttConnectOptions.MQTT_VERSION_3_1_1, options.getMqttVersion());
        }
        @Override public void subscribe(String[] topics, int[] qos) { subscriptions.addAll(Arrays.asList(topics)); responseTopic = topics[0]; }
        @Override public boolean isConnected() { return connected; }
        @Override public void disconnectForcibly(long quiesce, long timeout, boolean packet) { connected = false; }
        @Override public void close(boolean force) { connected = false; }
        @Override public void publish(String topic, byte[] bytes, int qos, boolean retained) throws MqttException {
            try {
                JSONObject request = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
                if (topic.endsWith("api_register")) {
                    callback.messageArrived(subscriptions.get(2), new MqttMessage(new JSONObject().put("client_id", request.getString("client_id")).put("error", "ok").toString().getBytes(StandardCharsets.UTF_8)));
                    return;
                }
                if (!request.has("method")) return;
                requests.add(request); int method = request.getInt("method");
                if (method == Cc2Codec.PAUSE || method == Cc2Codec.AUTO_REFILL || method == Cc2Codec.STOP) { writes.add(request); return; }
                JSONObject result = new JSONObject().put("error_code", 0);
                if (method == Cc2Codec.STATUS) result.put("machine_status", new JSONObject().put("status", 2).put("sub_status", 2075));
                if (method == Cc2Codec.CANVAS) result.put("canvas_info", new JSONObject().put("auto_refill", false));
                if (method == Cc2Codec.ATTRIBUTES) result.put("hostname", "Test CC2");
                response(request.getInt("id"), method, result);
            } catch (Exception error) { throw new MqttException(error); }
        }
        void response(int id, int method, JSONObject result) throws Exception {
            JSONObject reply = new JSONObject().put("id", id).put("method", method).put("result", result);
            callback.messageArrived(responseTopic, new MqttMessage(reply.toString().getBytes(StandardCharsets.UTF_8)));
        }
    }
    private static <T> T take(BlockingQueue<T> queue) throws Exception {
        T value = queue.poll(4, TimeUnit.SECONDS); assertNotNull("Expected callback", value); return value;
    }
    @Test public void connectsRegistersAndDeliversAttributesStatusAndCanvas() throws Exception {
        Listener listener = new Listener(); List<FakeMqtt> clients = new CopyOnWriteArrayList<>();
        Cc2Session session = new Cc2Session("192.168.1.50", "code", listener,
            url -> info(url, "{\"system_info\":{\"sn\":\"TEST-SERIAL\"}}"), null,
            (uri, id) -> { FakeMqtt fake = new FakeMqtt(uri, id); clients.add(fake); return fake; });
        try {
            session.connect(); assertEquals("Test CC2", take(listener.attrs).getString("hostname"));
            assertTrue(Cc2Codec.canPause(take(listener.statuses))); assertFalse(take(listener.canvases).getBoolean("auto_refill"));
            assertTrue(session.ready()); assertTrue(session.fresh());
            assertEquals(3, clients.get(0).subscriptions.size()); assertTrue(clients.get(0).subscriptions.get(0).startsWith("elegoo/TEST-SERIAL/"));
        } finally { session.close(); }
    }
    @Test public void wrongAcknowledgementCannotCompletePauseAndPendingWriteIsNotReplayed() throws Exception {
        Listener listener = new Listener(); List<FakeMqtt> clients = new CopyOnWriteArrayList<>();
        Cc2Session session = new Cc2Session("192.168.1.50", "code", listener,
            url -> info(url, "{\"system_info\":{\"sn\":\"TEST\"}}"), null,
            (uri, id) -> { FakeMqtt fake = new FakeMqtt(uri, id); clients.add(fake); return fake; });
        try {
            session.connect(); take(listener.statuses); FakeMqtt client = clients.get(0);
            session.command(Cc2Codec.PAUSE); JSONObject pause = take(client.writes); take(listener.results);
            client.response(pause.getInt("id") + 100, Cc2Codec.PAUSE, new JSONObject().put("error_code", 0));
            session.refresh(); take(listener.statuses); assertTrue(listener.results.isEmpty());
            client.callback.connectionLost(new IOException("wifi lost"));
            assertTrue(take(listener.results).contains("will not be replayed")); assertTrue(take(listener.failures).contains("connection lost"));
            assertFalse(session.ready()); assertTrue(listener.retryable);
            assertEquals(1, client.requests.stream().filter(r -> r.optInt("method") == Cc2Codec.PAUSE).count());
            assertTrue(client.writes.isEmpty());
        } finally { session.close(); }
    }
    @Test public void rejectedHttpCredentialNeverOpensMqttAndIsNotRetryable() throws Exception {
        Listener listener = new Listener();
        Cc2Session session = new Cc2Session("192.168.1.50", "secret", listener,
            url -> info(url, "{\"error_code\":1000}"), null,
            (uri, id) -> { fail("MQTT must not start after rejected HTTP authentication"); return null; });
        try {
            session.connect(); assertTrue(take(listener.failures).contains("Access code rejected")); assertFalse(listener.retryable); assertFalse(session.ready());
        } finally { session.close(); }
    }
    @Test public void refillUsesExplicitPayloadAndMatchingAcknowledgement() throws Exception {
        Listener listener = new Listener(); List<FakeMqtt> clients = new CopyOnWriteArrayList<>();
        Cc2Session session = new Cc2Session("192.168.1.50", "code", listener,
            url -> info(url, "{\"system_info\":{\"sn\":\"TEST\"}}"), null,
            (uri, id) -> { FakeMqtt fake = new FakeMqtt(uri, id); clients.add(fake); return fake; });
        try {
            session.connect(); take(listener.statuses); take(listener.canvases);
            session.autoRefill(true); JSONObject refill = take(clients.get(0).writes);
            assertEquals(2004, refill.getInt("method")); assertTrue(refill.getJSONObject("params").getBoolean("auto_refill")); take(listener.results);
            clients.get(0).response(refill.getInt("id"), 2004, new JSONObject().put("error_code", 0));
            assertTrue(take(listener.results).contains("acknowledged")); take(listener.canvases);
        } finally { session.close(); }
    }
    @Test public void discoveredIdentityRegistersAndReceivesStatusWithoutOpeningHttp() throws Exception {
        Listener listener = new Listener(); List<FakeMqtt> clients = new CopyOnWriteArrayList<>();
        Cc2Session session = new Cc2Session("192.168.1.84", "actual-code", listener,
            url -> { fail("Monitoring must not require HTTP when identity is available"); return null; }, null,
            (uri, id) -> { FakeMqtt fake = new FakeMqtt(uri, id); clients.add(fake); return fake; }, http -> "DISCOVERED-CC2");
        try {
            session.connect(); take(listener.statuses); assertTrue(session.ready());
            assertEquals("actual-code", clients.get(0).password);
            assertTrue(clients.get(0).subscriptions.stream().allMatch(topic -> topic.startsWith("elegoo/DISCOVERED-CC2/")));
            session.command(Cc2Codec.PAUSE); assertEquals(Cc2Codec.PAUSE, take(clients.get(0).writes).getInt("method"));
        } finally { session.close(); }
    }
    @Test public void manualIdentityStillRequiresMqttAuthentication() throws Exception {
        Listener listener = new Listener();
        Cc2Session session = new Cc2Session("192.168.1.84", "wrong-code", listener,
            url -> { fail("HTTP must not open"); return null; }, null,
            (uri, id) -> new FakeMqtt(uri, id) {
                @Override public void connect(MqttConnectOptions options) throws MqttSecurityException { throw new MqttSecurityException(5); }
            }, new PrinterIdentity("MANUAL-CC2", new Cc2Discovery(() -> { throw new IOException("UDP blocked"); }, Collections.emptyList())));
        try { session.connect(); assertTrue(take(listener.failures).contains("not authorized")); assertFalse(listener.retryable); assertFalse(session.ready()); }
        finally { session.close(); }
    }
    @Test public void invalidIdentityCannotBecomeAMqttTopic() throws Exception {
        Listener listener = new Listener();
        Cc2Session session = new Cc2Session("192.168.1.84", "code", listener,
            url -> { fail("HTTP must not open"); return null; }, null,
            (uri, id) -> { fail("Invalid identity must not open MQTT"); return null; }, http -> "#/wrong");
        try { session.connect(); assertTrue(take(listener.failures).contains("Serial Number")); assertFalse(listener.retryable); }
        finally { session.close(); }
    }
    @Test public void disabledCodeProtectionUsesDefaultForMqttDespiteEnteredCode() throws Exception {
        Listener listener = new Listener(); List<FakeMqtt> clients = new CopyOnWriteArrayList<>();
        Cc2Session session = new Cc2Session("192.168.1.84", "stale-code", listener,
            url -> { fail("HTTP must not open"); return null; }, null,
            (uri, id) -> { FakeMqtt fake = new FakeMqtt(uri, id); clients.add(fake); return fake; },
            new PrinterIdentity("MANUAL", PrinterIdentityTest.reply(true, false)));
        try {
            session.connect(); take(listener.statuses); assertTrue(session.ready());
            assertEquals("123456", clients.get(0).password);
            assertTrue(clients.get(0).subscriptions.get(0).startsWith("elegoo/DISCOVERED/"));
            assertTrue(take(listener.results).contains("disabled"));
        } finally { session.close(); }
    }
    @Test public void reportedCloudModeDoesNotSendLanCredentialsToMqtt() throws Exception {
        Listener listener = new Listener();
        Cc2Session session = new Cc2Session("192.168.1.84", "code", listener,
            url -> { fail("HTTP must not open"); return null; }, null,
            (uri, id) -> { fail("Cloud mode must stop before MQTT LAN authentication"); return null; },
            new PrinterIdentity("", PrinterIdentityTest.reply(false, true)));
        try { session.connect(); assertTrue(take(listener.failures).contains("cloud / WAN mode")); assertFalse(listener.retryable); assertFalse(session.ready()); }
        finally { session.close(); }
    }
}
