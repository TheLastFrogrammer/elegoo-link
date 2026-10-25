package io.github.thelastfrogrammer.elink.tv;

import static org.junit.Assert.*;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** The TV against a phone over real sockets: info, pairing in all its endings, the event and camera streams, reconnects and 401. */
public class PhoneLinkTest {
    private static final byte[] JPEG = {(byte) 0xff, (byte) 0xd8, 1, 2, 3, (byte) 0xff, (byte) 0xd9};
    private static final String TOKEN = "lw1_secret";
    private FakePhone phone;

    @Before public void start() throws Exception {
        phone = new FakePhone();
        phone.on("GET /api/v1/info", (r, out) -> FakePhone.json(out, 200, "{\"app\":\"Link Workshop\",\"api\":1,\"read_only\":true}"));
    }
    @After public void stop() throws Exception { phone.close(); }

    @Test public void infoTellsLinkWorkshopFromAnythingElse() throws Exception {
        assertEquals(1, new PhoneApi(phone.base()).info().getInt("api"));
        phone.on("GET /api/v1/info", (r, out) -> FakePhone.json(out, 200, "{\"app\":\"Something else\"}"));
        try { new PhoneApi(phone.base()).info(); fail(); } catch (PhoneApi.Refused r) { assertEquals(200, r.code); }
        try (java.net.ServerSocket closedPort = new java.net.ServerSocket(0)) {
            String nobody = "http://127.0.0.1:" + closedPort.getLocalPort(); closedPort.close();
            try { new PhoneApi(nobody).info(); fail(); } catch (java.io.IOException unreachable) { assertFalse(unreachable instanceof PhoneApi.Refused); }
        }
    }

    /** Records a pairing's calls in order. */
    private static final class Pairing implements PairingSession.Listener {
        final BlockingQueue<String> calls = new LinkedBlockingQueue<>();
        public void asking() { calls.add("asking"); }
        public void code(String code, long expiresAtMs) { calls.add("code " + code + (expiresAtMs > System.currentTimeMillis() + 250_000 ? " 5min" : " short")); }
        public void waiting(boolean reachable) { calls.add("waiting " + reachable); }
        public void approved(String token) { calls.add("approved " + token); }
        public void refused() { calls.add("refused"); }
        public void expired() { calls.add("expired"); }
        public void failed(boolean busy, String words) { calls.add("failed " + (busy ? "busy" : "unreachable")); }
        String next() throws InterruptedException { String c = calls.poll(10, TimeUnit.SECONDS); assertNotNull("a call", c); return c; }
    }
    private void pairAnswers(String... states) {
        phone.on("POST /api/v1/pair", (r, out) -> FakePhone.json(out, 202, "{\"id\":\"p1\",\"code\":\"4821\",\"poll\":\"/api/v1/pair/p1\",\"expires_in\":300}"));
        AtomicInteger polls = new AtomicInteger();
        phone.on("GET /api/v1/pair/p1", (r, out) -> {
            String s = states[Math.min(polls.getAndIncrement(), states.length - 1)];
            if (s.equals("gone")) FakePhone.json(out, 404, "{\"error\":\"No such pairing\"}");
            else if (s.equals("cut")) out.close();
            else FakePhone.json(out, 200, s.equals("approved") ? "{\"state\":\"approved\",\"token\":\"" + TOKEN + "\"}" : "{\"state\":\"" + s + "\"}");
        });
    }

    @Test public void pairingApproved() throws Exception {
        pairAnswers("pending", "pending", "approved");
        Pairing p = new Pairing();
        new PairingSession(new PhoneApi(phone.base()), "Living room TV", 50, p).start();
        assertEquals("asking", p.next()); assertEquals("code 4821 5min", p.next()); assertEquals("approved " + TOKEN, p.next());
        assertEquals("{\"name\":\"Living room TV\"}", phone.seen.get(0).body);
        assertEquals("pairing sends no token", "", phone.seen.get(0).auth);
    }
    @Test public void pairingRefusedAndRunOut() throws Exception {
        pairAnswers("pending", "denied");
        Pairing p = new Pairing();
        new PairingSession(new PhoneApi(phone.base()), "TV", 50, p).start();
        p.next(); p.next(); assertEquals("refused", p.next());
        pairAnswers("pending", "gone");
        Pairing q = new Pairing();
        new PairingSession(new PhoneApi(phone.base()), "TV", 50, q).start();
        q.next(); q.next(); assertEquals("expired", q.next());
    }
    @Test public void pairingWaitsOutAPhoneThatStopsAnswering() throws Exception {
        pairAnswers("pending", "cut", "cut", "pending", "approved");
        Pairing p = new Pairing();
        new PairingSession(new PhoneApi(phone.base()), "TV", 50, p).start();
        p.next(); p.next();
        assertEquals("waiting false", p.next()); assertEquals("waiting true", p.next()); assertEquals("approved " + TOKEN, p.next());
    }
    @Test public void pairingBusyRetriesOnceThenSays() throws Exception {
        AtomicInteger asks = new AtomicInteger();
        phone.on("POST /api/v1/pair", (r, out) -> { asks.incrementAndGet(); FakePhone.json(out, 429, "{\"error\":\"Too many devices are waiting to be approved on the phone\"}"); });
        Pairing p = new Pairing();
        new PairingSession(new PhoneApi(phone.base()), "TV", 50, p).start();
        assertEquals("asking", p.next()); assertEquals("failed busy", p.next());
        assertEquals("one quiet retry, no more", 2, asks.get());
        phone.on("POST /api/v1/pair", (r, out) -> out.close());
        Pairing q = new Pairing();
        new PairingSession(new PhoneApi(phone.base()), "TV", 50, q).start();
        q.next(); assertEquals("failed unreachable", q.next());
    }

    /** Records a link's calls. */
    private static final class Watch implements PrinterLink.Listener {
        final BlockingQueue<String> calls = new LinkedBlockingQueue<>();
        final List<String> frames = new CopyOnWriteArrayList<>();
        volatile List<PhoneApi.Camera> cameras;
        public void link(PrinterLink.State state, long retryAtMs, String why, String words) { calls.add("link " + state + (why.isEmpty() ? "" : " " + why)); }
        public void status(JSONObject status) { calls.add("status " + status.optJSONObject("state").optString("phase")); }
        public void event(JSONObject event) { calls.add("event " + event.optString("type")); }
        public void cameras(List<PhoneApi.Camera> all) { cameras = all; calls.add("cameras " + all.size()); }
        public void frame(String camera, byte[] jpeg) { frames.add(camera); }
        public void camera(String camera, long retryAtMs, String words) { }
        public void unpaired() { calls.add("unpaired"); }
        String until(String wanted) throws InterruptedException {
            long end = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < end) { String c = calls.poll(200, TimeUnit.MILLISECONDS); if (c != null && c.startsWith(wanted)) return c; }
            fail("no " + wanted); return null;
        }
    }
    private void camerasAndStreams(AtomicInteger eventStreams, boolean endAfterOne) {
        phone.on("GET /api/v1/cameras", (r, out) -> {
            if (!FakePhone.token(r, TOKEN)) { FakePhone.json(out, 401, "{\"error\":\"Pair this device first\"}"); return; }
            FakePhone.json(out, 200, "{\"cameras\":[{\"id\":\"printer\",\"name\":\"Printer camera\",\"live\":true,\"rotation\":0,\"mirror\":false},"
                + "{\"id\":\"added-ab12\",\"name\":\"Bench\",\"live\":true,\"rotation\":270,\"mirror\":true},{\"id\":\"bad id/..\",\"name\":\"x\"}]}");
        });
        phone.on("GET /api/v1/events", (r, out) -> {
            if (!FakePhone.token(r, TOKEN)) { FakePhone.json(out, 401, "{\"error\":\"Pair this device first\"}"); return; }
            eventStreams.incrementAndGet();
            FakePhone.streamHead(out, "text/event-stream; charset=utf-8");
            out.write("retry: 3000\n\n".getBytes()); FakePhone.sse(out, "status", "{\"state\":{\"phase\":\"printing\"}}");
            out.write(": ping\n\n".getBytes()); FakePhone.sse(out, "event", "{\"type\":\"first_layer\"}");
            FakePhone.sse(out, "status", "not json");   // skipped, the stream goes on
            FakePhone.sse(out, "status", "{\"state\":{\"phase\":\"paused\"}}");
            if (endAfterOne) return;
            Thread.sleep(30_000);
        });
        phone.on("GET /api/v1/cameras/printer/stream.mjpeg", (r, out) -> { FakePhone.streamHead(out, "multipart/x-mixed-replace; boundary=lwframe"); for (int i = 0; i < 100; i++) { FakePhone.jpeg(out, JPEG); Thread.sleep(40); } });
        phone.on("GET /api/v1/cameras/added-ab12/stream.mjpeg", (r, out) -> { FakePhone.streamHead(out, "multipart/x-mixed-replace; boundary=lwframe"); for (int i = 0; i < 100; i++) { FakePhone.jpeg(out, JPEG); Thread.sleep(40); } });
    }

    @Test public void followsStatusEventsAndCameras() throws Exception {
        AtomicInteger streams = new AtomicInteger();
        camerasAndStreams(streams, false);
        Watch w = new Watch();
        PrinterLink link = new PrinterLink(new PhoneApi(phone.base()), TOKEN, w, 100, 1_000);
        link.start();
        assertEquals("cameras 2", w.until("cameras"));
        assertEquals("the camera hints come through", 270, w.cameras.get(1).rotation); assertTrue(w.cameras.get(1).mirror);
        assertEquals("status printing", w.until("status")); assertEquals("link LIVE", w.until("link LIVE"));
        assertEquals("event first_layer", w.until("event")); assertEquals("status paused", w.until("status"));
        link.camera("printer");
        long end = System.currentTimeMillis() + 5_000; while (w.frames.size() < 3 && System.currentTimeMillis() < end) Thread.sleep(20);
        assertTrue("pictures arrive: " + w.frames.size(), w.frames.size() >= 3);
        link.camera("added-ab12");
        end = System.currentTimeMillis() + 5_000; while (!w.frames.contains("added-ab12") && System.currentTimeMillis() < end) Thread.sleep(20);
        assertTrue("Right switches the stream", w.frames.contains("added-ab12"));
        assertEquals("fps asked", 1, phone.count("/api/v1/cameras/added-ab12/stream.mjpeg"));
        for (FakePhone.Request r : phone.seen) {
            if (r.path.startsWith("/api/v1/pair")) continue;
            assertTrue(r.path + " carries the token", FakePhone.token(r, TOKEN) || r.path.equals("/api/v1/info"));
            assertEquals("read only: " + r.path, "GET", r.method);
        }
        link.stop();
        int after = phone.seen.size(); Thread.sleep(400);
        assertEquals("a stopped link asks nothing more", after, phone.seen.size());
    }

    @Test public void reconnectsCalmlyNeverHammering() throws Exception {
        AtomicInteger streams = new AtomicInteger();
        camerasAndStreams(streams, true);   // each stream ends at once, as when the phone loses the printer
        Watch w = new Watch();
        PrinterLink link = new PrinterLink(new PhoneApi(phone.base()), TOKEN, w, 100, 400);
        link.start();
        Thread.sleep(2_000);
        link.stop();
        int n = streams.get();
        // Waits 100, 200, 400, 400… ms (±20%) plus the requests: about 6 in 2 s. Without the back-off it would be hundreds.
        assertTrue("reconnected, calmly: " + n, n >= 3 && n <= 9);
        assertEquals("link WAITING unreachable", w.until("link WAITING"));
    }
    @Test public void aBusyPhoneIsGivenTime() throws Exception {
        phone.on("GET /api/v1/cameras", (r, out) -> FakePhone.json(out, 200, "{\"cameras\":[]}"));
        phone.on("GET /api/v1/events", (r, out) -> FakePhone.json(out, 503, "{\"error\":\"Too many event streams open\"}"));
        Watch w = new Watch();
        PrinterLink link = new PrinterLink(new PhoneApi(phone.base()), TOKEN, w, 100, 400);
        link.start();
        assertEquals("link WAITING busy", w.until("link WAITING"));
        link.stop();
    }
    @Test public void aRefusedTokenEndsTheLinkOnce() throws Exception {
        camerasAndStreams(new AtomicInteger(), false);
        Watch w = new Watch();
        PrinterLink link = new PrinterLink(new PhoneApi(phone.base()), "lw1_removed", w, 100, 400);
        link.start();
        assertEquals("unpaired", w.until("unpaired"));
        Thread.sleep(500);
        assertFalse(link.running());
        assertFalse("said once", w.calls.contains("unpaired"));
        assertEquals("no retries with a refused token", 1, phone.count("/api/v1/cameras"));
    }
    @Test public void comesBackWhenThePhoneDoes() throws Exception {
        AtomicInteger streams = new AtomicInteger();
        camerasAndStreams(streams, false);
        Watch w = new Watch();
        PrinterLink link = new PrinterLink(new PhoneApi(phone.base()), TOKEN, w, 100, 400);
        link.start();
        w.until("link LIVE");
        phone.cut();   // the phone app closes
        assertEquals("link WAITING unreachable", w.until("link WAITING"));
        assertEquals("link LIVE", w.until("link LIVE"));
        assertTrue(streams.get() >= 2);
        link.stop();
    }
}
