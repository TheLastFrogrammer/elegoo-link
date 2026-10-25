package io.github.thelastfrogrammer.elink;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;

/** Pushes to the person's relay: signed the same way the reference relay checks, HTTPS only, paced. */
public class IntegrationRelayTest {
    /** The same vector the relay worker's own test checks (android/integrations/relay-worker/test.mjs). */
    @Test public void signatureMatchesTheRelay() {
        assertEquals("9baea68011788923add0e7f273f0dd7cdb6c56805f107c4eb2fd6a52d5a2a076",
            IntegrationRelay.sign(IntegrationRelay.secretBytes("test-secret-0123456789abcdef"), 1700000000, "POST", "/v1/status", "{\"a\":1}".getBytes(StandardCharsets.UTF_8)));
        assertTrue(IntegrationRelay.newSecret().length() >= 40);
    }

    @Test public void onlyPlainHttpsAddresses() {
        assertEquals("", IntegrationRelay.problem("https://printer-relay.example.workers.dev"));
        assertFalse(IntegrationRelay.problem("http://printer-relay.example.workers.dev").isEmpty());
        assertFalse(IntegrationRelay.problem("https://user:pass@relay.example").isEmpty());
        assertFalse(IntegrationRelay.problem("https://relay.example/?key=1").isEmpty());
        assertFalse(IntegrationRelay.problem("not a url").isEmpty());
    }

    private static final class Sent { String path, type, timestamp, signature; byte[] body; }
    private static final class Fake extends HttpURLConnection {
        final Sent sent = new Sent(); final ByteArrayOutputStream body = new ByteArrayOutputStream(); final List<Sent> log;
        Fake(URL url, List<Sent> log) { super(url); this.log = log; }
        @Override public void connect() { }
        @Override public void disconnect() { }
        @Override public boolean usingProxy() { return false; }
        @Override public OutputStream getOutputStream() { return body; }
        @Override public int getResponseCode() {
            if (sent.path == null) { sent.path = url.getPath(); sent.type = getRequestProperty("Content-Type"); sent.timestamp = getRequestProperty("X-LW-Timestamp"); sent.signature = getRequestProperty("X-LW-Signature"); sent.body = body.toByteArray(); synchronized (log) { log.add(sent); } }
            return 200;
        }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream("{\"ok\":true}".getBytes(StandardCharsets.UTF_8)); }
    }

    @Test public void pushesAreSignedAndPaced() throws Exception {
        List<Sent> log = new ArrayList<>();
        IntegrationRelay relay = new IntegrationRelay(url -> new Fake(url, log));
        byte[] secret = IntegrationRelay.secretBytes("a-relay-secret-of-enough-length");
        relay.configure("https://relay.example/", secret, 60_000);
        assertTrue(relay.ready());
        JSONObject status = new JSONObject().put("schema", IntegrationStatus.SCHEMA).put("state", new JSONObject().put("phase", "printing"));
        relay.status(status, 1_000_000);
        relay.status(new JSONObject(status.toString()).put("updated_at", "later"), 1_010_000);              // same status: not again
        relay.status(new JSONObject(status.toString()).put("state", new JSONObject().put("phase", "paused")), 1_020_000);   // changed, but too soon
        relay.event(new JSONObject().put("type", "print_finished"));
        relay.picture(new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xd9});
        for (int i = 0; i < 100; i++) { synchronized (log) { if (log.size() >= 3) break; } Thread.sleep(20); }
        Thread.sleep(100);
        assertEquals(3, log.size());
        assertEquals("/v1/status", log.get(0).path); assertEquals("/v1/event", log.get(1).path); assertEquals("/v1/snapshot", log.get(2).path);
        for (Sent s : log) assertEquals("v1=" + IntegrationRelay.sign(secret, Long.parseLong(s.timestamp), "POST", s.path, s.body), s.signature);
        assertEquals("image/jpeg", log.get(2).type);
        assertTrue(relay.lastResult, relay.lastResult.startsWith("Sent the snapshot"));
        relay.configure("", secret, 60_000); assertFalse(relay.ready());
        relay.close();
    }
}
