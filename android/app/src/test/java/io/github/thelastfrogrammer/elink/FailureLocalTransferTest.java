package io.github.thelastfrogrammer.elink;

import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.json.JSONObject;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Failure audit, local connection: the Wi-Fi drops while a file is going up or coming down, and the printer refuses a write.
 * Uses the production Cc2Session with fake MQTT and HTTP transports (same approach as Cc2SessionTest).
 */
public class FailureLocalTransferTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    private static final class Listener implements Cc2Session.Listener {
        final BlockingQueue<JSONObject> statuses = new LinkedBlockingQueue<>();
        final BlockingQueue<String> results = new LinkedBlockingQueue<>(), failures = new LinkedBlockingQueue<>(), uploadFailures = new LinkedBlockingQueue<>();
        final BlockingQueue<File> downloads = new LinkedBlockingQueue<>();
        public void connection(String message, boolean registered) { }
        public void status(JSONObject snapshot) { statuses.add(snapshot); }
        public void result(String message) { results.add(message); }
        public void uploadProgress(int percent) { }
        public void failure(String message, boolean retryable) { failures.add(message); }
        public void uploadFailed(String filename) { uploadFailures.add(filename); }
        public void downloaded(File file, String filename) { downloads.add(file); }
    }

    /** MQTT that registers and answers status, so the session is ready; the test can fire connectionLost. */
    private static final class FakeMqtt extends MqttClient {
        MqttCallback callback; String responseTopic; volatile boolean connected; final List<String> subscriptions = new CopyOnWriteArrayList<>();
        FakeMqtt(String uri, String id) throws MqttException { super(uri, id, new MemoryPersistence()); }
        @Override public void setCallback(MqttCallback callback) { this.callback = callback; }
        @Override public void connect(MqttConnectOptions options) { connected = true; }
        @Override public IMqttToken subscribeWithResponse(String[] topics, int[] qos) {
            subscriptions.addAll(Arrays.asList(topics)); responseTopic = topics[0];
            return new MqttToken() { @Override public int[] getGrantedQos() { return new int[] {1, 1, 1}; } };
        }
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
                int method = request.getInt("method");
                JSONObject result = new JSONObject().put("error_code", 0);
                if (method == Cc2Codec.STATUS) result.put("machine_status", new JSONObject().put("status", 1).put("sub_status", 0));
                if (method == Cc2Codec.CANVAS) result.put("canvas_info", new JSONObject().put("auto_refill", false));
                if (method == Cc2Codec.ATTRIBUTES) result.put("hostname", "Test CC2");
                JSONObject reply = new JSONObject().put("id", request.getInt("id")).put("method", method).put("result", result);
                callback.messageArrived(responseTopic, new MqttMessage(reply.toString().getBytes(StandardCharsets.UTF_8)));
            } catch (Exception error) { throw new MqttException(error); }
        }
    }

    /** An HTTP connection whose body write (upload) or read (download) blocks until the test "drops the Wi-Fi" or disconnect() is called. */
    private static final class Blocking extends HttpURLConnection {
        final CountDownLatch started = new CountDownLatch(1), wake = new CountDownLatch(1);
        final boolean download; volatile boolean disconnected; final String uploadReply;
        Blocking(URL url, boolean download, String uploadReply) { super(url); this.download = download; this.uploadReply = uploadReply; }
        public void connect() { }
        public void disconnect() { disconnected = true; wake.countDown(); }
        public boolean usingProxy() { return false; }
        public int getResponseCode() { return 200; }
        public long getContentLengthLong() { return download ? 1_000_000 : -1; }
        public String getContentType() { return "application/octet-stream"; }
        public OutputStream getOutputStream() {
            return new OutputStream() {
                public void write(int b) throws IOException { write(new byte[] {(byte) b}, 0, 1); }
                public void write(byte[] b, int off, int len) throws IOException { stall(); }
            };
        }
        public InputStream getInputStream() {
            if (!download) return new ByteArrayInputStream(uploadReply.getBytes(StandardCharsets.UTF_8));
            return new InputStream() {
                int sent;
                public int read() throws IOException { byte[] one = new byte[1]; return read(one, 0, 1) < 0 ? -1 : one[0] & 255; }
                public int read(byte[] b, int off, int len) throws IOException {
                    if (sent < 4096) { int n = Math.min(len, 4096 - sent); sent += n; return n; }
                    stall(); return -1;
                }
            };
        }
        void stall() throws IOException {
            started.countDown();
            try { wake.await(20, TimeUnit.SECONDS); } catch (InterruptedException stop) { throw new InterruptedIOException("interrupted"); }
            throw new SocketException(disconnected ? "Socket closed" : "Software caused connection abort");
        }
    }
    private static HttpURLConnection info(URL url, String response) {
        return new HttpURLConnection(url) {
            public void connect() { }
            public void disconnect() { }
            public boolean usingProxy() { return false; }
            public int getResponseCode() { return 200; }
            public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
            public InputStream getInputStream() { return new ByteArrayInputStream(response.getBytes(StandardCharsets.UTF_8)); }
        };
    }
    private static <T> T take(BlockingQueue<T> queue, long seconds) throws Exception { return queue.poll(seconds, TimeUnit.SECONDS); }

    private Cc2Session session(Listener listener, List<FakeMqtt> clients, PrinterHttp.ConnectionFactory http) {
        return new Cc2Session("192.168.1.50", "code", listener, http, null,
            (uri, id) -> { FakeMqtt fake = new FakeMqtt(uri, id); clients.add(fake); return fake; });
    }
    private static boolean eventually(java.util.function.BooleanSupplier condition) throws Exception {
        for (int i = 0; i < 100; i++) { if (condition.getAsBoolean()) return true; Thread.sleep(50); }
        return condition.getAsBoolean();
    }

    /**
     * The Wi-Fi drops while a file is going up. Cc2Session.fail() closes the session (listener = null) before the transfer thread
     * unwinds, so uploadFailed() is never delivered. PrinterService relies on uploadFailed() to clear its upload queue
     * (queuedName/queuedFile/uploadQueue), so after this the queue is never cleared and later uploads silently do nothing.
     */
    @Test public void connectionLossMidUploadStillReportsTheUploadAsFailed() throws Exception {
        File file = folder.newFile("part.gcode"); Files.write(file.toPath(), "G28\nG1 X10\n".getBytes(StandardCharsets.UTF_8));
        Listener listener = new Listener(); List<FakeMqtt> clients = new CopyOnWriteArrayList<>();
        Blocking[] upload = new Blocking[1];
        Cc2Session session = session(listener, clients, url -> {
            if (url.getPath().equals("/upload")) return upload[0] = new Blocking(url, false, "{\"error_code\":0}");
            return info(url, "{\"system_info\":{\"sn\":\"TEST\"}}");
        });
        try {
            session.connect(); assertNotNull(take(listener.statuses, 12));
            session.upload(file, "part.gcode");
            assertTrue(eventually(() -> upload[0] != null && upload[0].started.getCount() == 0));
            clients.get(0).callback.connectionLost(new IOException("wifi lost"));
            assertNotNull("session reports the lost connection", take(listener.failures, 12));
            assertTrue("the transfer thread is gone", eventually(() -> !session.uploading()));
            assertEquals("the listener must hear that the upload failed", "part.gcode", take(listener.uploadFailures, 2));
            String shown = null; for (String result; (result = take(listener.results, 1)) != null; ) shown = result;
            assertEquals("Upload stopped: connection lost. A partial file may remain on the printer.", shown);
        } finally { session.close(); }
    }

    /** Works well: an upload that fails while the session is still open IS reported, with no automatic second attempt. */
    @Test public void printerWriteFailureIsReportedOnceAndNeverRetried() throws Exception {
        File file = folder.newFile("full.gcode"); Files.write(file.toPath(), "G28\n".getBytes(StandardCharsets.UTF_8));
        Listener listener = new Listener(); List<FakeMqtt> clients = new CopyOnWriteArrayList<>();
        AtomicInteger puts = new AtomicInteger();
        Cc2Session session = session(listener, clients, url -> {
            if (url.getPath().equals("/upload")) { puts.incrementAndGet(); return info(url, "{\"error_code\":9002}"); }
            return info(url, "{\"system_info\":{\"sn\":\"TEST\"}}");
        });
        try {
            session.connect(); assertNotNull(take(listener.statuses, 12));
            session.upload(file, "full.gcode");
            assertEquals("full.gcode", take(listener.uploadFailures, 5));
            String shown = null; for (String result; (result = take(listener.results, 1)) != null; ) shown = result;
            // What the user reads for a printer whose storage is full (the SDK table lists 1004/9002 only as "File write failed").
            assertEquals("Printer could not write the file; the printer's storage may be full. Delete files on the printer and try again (code 9002). A partial file may remain on the printer.", shown);
            assertEquals("no automatic retry", 1, puts.get());
            assertTrue(eventually(() -> !session.uploading()));
        } finally { session.close(); }
    }

    /** Works well: the Wi-Fi drops mid-download; the partial phone copy is deleted and nothing is delivered. */
    @Test public void connectionLossMidDownloadDeletesThePartialCopy() throws Exception {
        Listener listener = new Listener(); List<FakeMqtt> clients = new CopyOnWriteArrayList<>();
        Blocking[] download = new Blocking[1];
        Cc2Session session = session(listener, clients, url -> {
            if (url.getPath().startsWith("/download")) return download[0] = new Blocking(url, true, "");
            return info(url, "{\"system_info\":{\"sn\":\"TEST\"}}");
        });
        File target = folder.newFile("download-1.gcode");
        try {
            session.connect(); assertNotNull(take(listener.statuses, 12));
            assertTrue(session.download(target, "local", "big.gcode"));
            assertTrue(eventually(() -> download[0] != null && download[0].started.getCount() == 0));
            clients.get(0).callback.connectionLost(new IOException("wifi lost"));
            assertNotNull(take(listener.failures, 12));
            assertTrue(eventually(() -> !session.downloading()));
            assertTrue(eventually(() -> !target.exists()));
            assertTrue(listener.downloads.isEmpty());
        } finally { session.close(); }
    }

    /** An acknowledgement is not the new state: until a status newer than the ack arrives, Start/Delete/temperature are not allowed. */
    @Test public void aStatusOlderThanTheAcknowledgementDoesNotKeepControlsFresh() throws Exception {
        Listener listener = new Listener(); List<FakeMqtt> clients = new CopyOnWriteArrayList<>();
        Cc2Session session = session(listener, clients, url -> info(url, "{\"system_info\":{\"sn\":\"TEST\"}}"));
        try {
            session.connect(); assertNotNull(take(listener.statuses, 12));
            assertTrue(session.fresh());
            session.start("local", "a.gcode", false, false, false, "A", new org.json.JSONArray());
            assertTrue("freshness is dropped when the printer acknowledges the start", eventually(() -> !session.fresh()));
            assertTrue("and returns with the next status", eventually(session::fresh));
        } finally { session.close(); }
    }
}
