package io.github.thelastfrogrammer.elink;

import static org.junit.Assert.*;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** The local API over real sockets: pairing, tokens and tickets, read only, status, events and the camera. */
public class IntegrationServerTest {
    private static final byte[] JPEG = {(byte) 0xff, (byte) 0xd8, 1, 2, 3, (byte) 0xff, (byte) 0xd9};
    private IntegrationAuth auth;
    private CameraHub hub;
    private IntegrationServer server;
    private final AtomicInteger opened = new AtomicInteger(), closed = new AtomicInteger();
    private volatile JSONObject status;

    @Before public void start() throws Exception {
        status = new JSONObject().put("schema", IntegrationStatus.SCHEMA).put("state", new JSONObject().put("phase", "printing"));
        auth = new IntegrationAuth(new IntegrationAuth.Storage() { String j = "[]"; public String load() { return j; } public void save(String s) { j = s; } });
        // A camera that sends a frame every 50 ms while open.
        hub = new CameraHub((id, sink) -> {
            opened.incrementAndGet(); boolean[] stop = {false};
            Thread t = new Thread(() -> { while (!stop[0]) { sink.frame(JPEG.clone()); try { Thread.sleep(50); } catch (InterruptedException e) { return; } } });
            t.setDaemon(true); t.start();
            return () -> { stop[0] = true; closed.incrementAndGet(); };
        }, System::currentTimeMillis);
        server = new IntegrationServer(new IntegrationServer.Host() {
            public JSONObject status() { return status; }
            public JSONArray cameras() { try { return new JSONArray().put(new JSONObject().put("id", "printer").put("live", true)); } catch (Exception e) { throw new IllegalStateException(e); } }
            public byte[] asset(String name) { return "<html>page</html>".getBytes(StandardCharsets.UTF_8); }
            public String appVersion() { return "test"; }
        }, auth, hub, 0, false);
        server.start();
    }
    @After public void stop() { server.close(); hub.close(); }

    private HttpURLConnection call(String method, String path, String token, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + server.port() + path).openConnection();
        c.setRequestMethod(method); c.setConnectTimeout(3000); c.setReadTimeout(10000);
        if (token != null) c.setRequestProperty("Authorization", "Bearer " + token);
        if (body != null) { c.setDoOutput(true); c.setRequestProperty("Content-Type", "application/json"); try (OutputStream o = c.getOutputStream()) { o.write(body.getBytes(StandardCharsets.UTF_8)); } }
        return c;
    }
    private static String text(HttpURLConnection c) throws Exception {
        InputStream in = c.getResponseCode() < 400 ? c.getInputStream() : c.getErrorStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] b = new byte[4096]; int n; while ((n = in.read(b)) > 0) out.write(b, 0, n); return out.toString("UTF-8");
    }

    @Test public void pairThenReadOnly() throws Exception {
        HttpURLConnection info = call("GET", "/api/v1/info", null, null);
        assertEquals(200, info.getResponseCode()); JSONObject i = new JSONObject(text(info));
        assertTrue(i.getBoolean("read_only")); assertEquals(1, i.getInt("api"));
        assertEquals("reads need a paired device", 401, call("GET", "/api/v1/status", null, null).getResponseCode());

        HttpURLConnection ask = call("POST", "/api/v1/pair", null, "{\"name\":\"Fire TV\"}");
        assertEquals(202, ask.getResponseCode()); JSONObject pairing = new JSONObject(text(ask));
        assertEquals("pending", new JSONObject(text(call("GET", pairing.getString("poll"), null, null))).getString("state"));
        assertEquals(1, auth.pending(System.currentTimeMillis()).size());
        auth.approve(pairing.getString("id"), System.currentTimeMillis());   // the person taps Approve on the phone
        String token = new JSONObject(text(call("GET", pairing.getString("poll"), null, null))).getString("token");

        HttpURLConnection read = call("GET", "/api/v1/status", token, null);
        assertEquals(200, read.getResponseCode()); assertEquals("*", read.getHeaderField("Access-Control-Allow-Origin"));
        assertEquals(IntegrationStatus.SCHEMA, new JSONObject(text(read)).getString("schema"));
        assertEquals("printer", new JSONObject(text(call("GET", "/api/v1/cameras", token, null))).getJSONArray("cameras").getJSONObject(0).getString("id"));
        assertEquals("nothing can be sent", 405, call("PUT", "/api/v1/status", token, "{}").getResponseCode());
        assertEquals(405, call("POST", "/api/v1/status", token, "{}").getResponseCode());
        assertEquals(404, call("GET", "/api/v1/print/start", token, null).getResponseCode());
        assertEquals(404, call("GET", "/api/v1/cameras/elsewhere/snapshot.jpg", token, null).getResponseCode());
        assertEquals(200, call("GET", "/", null, null).getResponseCode());
        assertEquals(204, call("OPTIONS", "/api/v1/status", null, null).getResponseCode());

        // Tickets: made with the token, good in a query for a minute, never good for making another ticket.
        String ticket = new JSONObject(text(call("POST", "/api/v1/tickets", token, ""))).getString("ticket");
        assertEquals(200, call("GET", "/api/v1/status?ticket=" + ticket, null, null).getResponseCode());
        assertEquals(401, call("POST", "/api/v1/tickets?ticket=" + ticket, null, "").getResponseCode());
    }

    @Test public void onlyTheHomeNetworkIsAnswered() throws Exception {
        assertTrue(IntegrationServer.allowed(InetAddress.getByName("192.168.1.20"), false));
        assertTrue(IntegrationServer.allowed(InetAddress.getByName("10.0.0.5"), false));
        assertTrue(IntegrationServer.allowed(InetAddress.getByName("172.20.1.1"), false));
        assertTrue(IntegrationServer.allowed(InetAddress.getByName("127.0.0.1"), false));
        assertTrue(IntegrationServer.allowed(InetAddress.getByName("fd12:3456::1"), false));
        assertTrue(IntegrationServer.allowed(InetAddress.getByName("fe80::1"), false));
        assertFalse(IntegrationServer.allowed(InetAddress.getByName("8.8.8.8"), false));
        assertFalse(IntegrationServer.allowed(InetAddress.getByName("2001:4860::8888"), false));
        assertFalse(IntegrationServer.allowed(InetAddress.getByName("100.101.102.103"), false));
        assertTrue("the home VPN, when allowed", IntegrationServer.allowed(InetAddress.getByName("100.101.102.103"), true));
        assertEquals(5, IntegrationServer.fps(null)); assertEquals(10, IntegrationServer.fps("99")); assertEquals(1, IntegrationServer.fps("0"));
    }

    @Test public void cameraSnapshotAndStreamShareOneConnection() throws Exception {
        String token = auth.add("Test", System.currentTimeMillis());
        HttpURLConnection shot = call("GET", "/api/v1/cameras/printer/snapshot.jpg", token, null);
        assertEquals(200, shot.getResponseCode()); assertEquals("image/jpeg", shot.getContentType());
        byte[] got = text(shot).getBytes(StandardCharsets.ISO_8859_1);
        assertEquals(JPEG.length, shot.getContentLength()); assertEquals(1, opened.get());

        // Two streams at once: still one connection to the camera.
        Socket a = stream(token), b = stream(token);
        assertTrue(readUntil(a.getInputStream(), "Content-Type: image/jpeg").contains("multipart/x-mixed-replace; boundary=lwframe"));
        readUntil(b.getInputStream(), "Content-Type: image/jpeg");
        assertEquals(1, opened.get()); assertEquals(2, hub.viewers("printer"));
        a.close(); b.close();
        for (int i = 0; i < 100 && hub.viewers("printer") > 0; i++) Thread.sleep(50);
        assertEquals("the viewers left", 0, hub.viewers("printer"));
        assertNotNull(got);
    }
    private Socket stream(String token) throws Exception {
        Socket s = new Socket("127.0.0.1", server.port()); s.setSoTimeout(10000);
        s.getOutputStream().write(("GET /api/v1/cameras/printer/stream.mjpeg?fps=10 HTTP/1.1\r\nHost: x\r\nAuthorization: Bearer " + token + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        return s;
    }
    private static String readUntil(InputStream in, String marker) throws Exception {
        StringBuilder seen = new StringBuilder(); int b;
        while ((b = in.read()) != -1) { seen.append((char) b); if (seen.indexOf(marker) >= 0) return seen.toString(); if (seen.length() > 100_000) break; }
        fail("never saw " + marker + " in " + seen); return null;
    }

    @Test public void eventsStreamStatusAndPrintEvents() throws Exception {
        String token = auth.add("Dashboard", System.currentTimeMillis());
        Socket s = new Socket("127.0.0.1", server.port()); s.setSoTimeout(10000);
        s.getOutputStream().write(("GET /api/v1/events HTTP/1.1\r\nHost: x\r\nAuthorization: Bearer " + token + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        InputStream in = s.getInputStream();
        assertTrue(readUntil(in, "event: status").contains("text/event-stream"));
        readUntil(in, "\n\n");
        for (int i = 0; i < 100 && server.listeners() == 0; i++) Thread.sleep(20);
        status = new JSONObject().put("schema", IntegrationStatus.SCHEMA).put("state", new JSONObject().put("phase", "paused"));
        server.statusChanged();
        assertTrue(readUntil(in, "\n\n").contains("\"paused\""));
        server.event(IntegrationStatus.event("print_finished", "A print finishes", "part.gcode", 10, 10, 100, false, 0));
        String event = readUntil(in, "print_finished");
        assertTrue(event, event.contains("event: event"));
        s.close();
    }
}
