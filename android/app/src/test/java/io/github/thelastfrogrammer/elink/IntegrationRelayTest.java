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
        final Sent sent = new Sent(); final ByteArrayOutputStream body = new ByteArrayOutputStream(); final List<Sent> log; final int code;
        Fake(URL url, List<Sent> log) { this(url, log, 200); }
        Fake(URL url, List<Sent> log, int code) { super(url); this.log = log; this.code = code; }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public void connect() { }
        @Override public void disconnect() { }
        @Override public boolean usingProxy() { return false; }
        @Override public OutputStream getOutputStream() { return body; }
        @Override public int getResponseCode() {
            if (sent.path == null) { sent.path = url.getPath(); sent.type = getRequestProperty("Content-Type"); sent.timestamp = getRequestProperty("X-LW-Timestamp"); sent.signature = getRequestProperty("X-LW-Signature"); sent.body = body.toByteArray(); synchronized (log) { log.add(sent); } }
            return code;
        }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream("{\"ok\":true}".getBytes(StandardCharsets.UTF_8)); }
    }

    private static void waitFor(List<Sent> log, int n) throws InterruptedException { for (int i = 0; i < 100; i++) { synchronized (log) { if (log.size() >= n) return; } Thread.sleep(20); } }

    /** An address with a path of its own: the signature covers the path as sent, which is what a relay can check. */
    @Test public void theSignatureCoversThePathAsSent() throws Exception {
        List<Sent> log = new ArrayList<>();
        IntegrationRelay relay = new IntegrationRelay(url -> new Fake(url, log));
        byte[] secret = IntegrationRelay.secretBytes("a-relay-secret-of-enough-length");
        relay.configure("https://example.org/printer-relay/", secret, 60_000);
        relay.test(new JSONObject().put("a", 1)); waitFor(log, 1);
        Sent s = log.get(0);
        assertEquals("/printer-relay/v1/status", s.path);
        assertEquals("v1=" + IntegrationRelay.sign(secret, Long.parseLong(s.timestamp), "POST", "/printer-relay/v1/status", s.body), s.signature);
        relay.close();
    }

    /** Even the test push (which skips the pacing) goes nowhere but a ready https relay. */
    @Test public void nothingGoesToAPlainHttpAddress() throws Exception {
        List<Sent> log = new ArrayList<>(); List<URL> opened = new ArrayList<>();
        IntegrationRelay relay = new IntegrationRelay(url -> { synchronized (opened) { opened.add(url); } return new Fake(url, log); });
        relay.configure("http://relay.example", IntegrationRelay.secretBytes("a-relay-secret-of-enough-length"), 60_000);
        relay.test(new JSONObject().put("a", 1));
        relay.configure("https://relay.example", IntegrationRelay.secretBytes("short"), 60_000);
        relay.test(new JSONObject().put("a", 1));
        Thread.sleep(300);
        assertTrue(opened.toString(), opened.isEmpty());
        relay.close();
    }

    /** After a failed push, the status goes again once the wait is over, even if it has not changed since. */
    @Test public void aFailedStatusIsSentAgainAfterTheWait() throws Exception {
        List<Sent> log = new ArrayList<>(); int[] calls = {0};
        IntegrationRelay relay = new IntegrationRelay(url -> new Fake(url, log, calls[0]++ == 0 ? 503 : 200));
        relay.backoffMs = 200;
        relay.configure("https://relay.example", IntegrationRelay.secretBytes("a-relay-secret-of-enough-length"), 60_000);
        JSONObject done = new JSONObject().put("schema", IntegrationStatus.SCHEMA).put("state", new JSONObject().put("phase", "complete"));
        relay.status(done, System.currentTimeMillis()); waitFor(log, 1);
        Thread.sleep(400);
        assertTrue(relay.lastResult, relay.lastResult.contains("503"));
        relay.status(new JSONObject(done.toString()), System.currentTimeMillis()); waitFor(log, 2); Thread.sleep(100);
        assertEquals("sent again", 2, log.size());
        assertTrue(relay.lastResult, relay.lastResult.startsWith("Sent the status"));
        relay.close();
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
