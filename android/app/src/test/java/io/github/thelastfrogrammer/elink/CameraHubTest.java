package io.github.thelastfrogrammer.elink;

import static org.junit.Assert.*;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

/** One camera connection however many watch, given up to the app's own view, closed after the last viewer. */
public class CameraHubTest {
    private static final byte[] JPEG = {(byte) 0xff, (byte) 0xd8, 9, (byte) 0xff, (byte) 0xd9};

    @Test public void opensForViewersAndYieldsToTheAppsOwnView() throws Exception {
        AtomicLong clock = new AtomicLong(1_000_000); AtomicInteger opened = new AtomicInteger(), closed = new AtomicInteger();
        CameraHub.Sink[] sink = new CameraHub.Sink[1];
        CameraHub hub = new CameraHub((id, s) -> { opened.incrementAndGet(); sink[0] = s; return closed::incrementAndGet; }, clock::get);
        try {
            hub.acquire("printer"); hub.acquire("printer");
            assertEquals(1, opened.get()); assertTrue(hub.pulling("printer"));
            sink[0].frame(JPEG);
            assertEquals(1, hub.latest("printer").seq);
            sink[0].frame(new byte[] {1, 2, 3});
            assertEquals("not a JPEG: dropped", 1, hub.latest("printer").seq);

            // The app's own camera view opens: the hub lets go and takes its frames instead.
            hub.yield("printer"); assertEquals(1, closed.get()); assertFalse(hub.pulling("printer"));
            hub.appFrame("printer", JPEG); assertEquals(2, hub.latest("printer").seq);
            clock.addAndGet(CameraHub.APP_HOLD_MS - 1); hub.tick();
            assertEquals("not while the app's view holds the camera", 1, opened.get());
            clock.addAndGet(CameraHub.RETRY_MS + 2); hub.tick();
            assertEquals("back once the app's view has closed", 2, opened.get());

            // A failure: asked again after RETRY_MS, not before.
            sink[0].failed("refused"); assertFalse(hub.pulling("printer")); assertEquals("refused", hub.problem("printer"));
            hub.tick(); assertEquals(2, opened.get());
            clock.addAndGet(CameraHub.RETRY_MS + 1); hub.tick(); assertEquals(3, opened.get());

            hub.release("printer"); hub.release("printer"); hub.tick();
            assertTrue("kept a moment for a viewer coming back", hub.pulling("printer"));
            clock.addAndGet(CameraHub.KEEP_MS + 1); hub.tick();
            assertFalse(hub.pulling("printer")); assertEquals(2, closed.get());
        } finally { hub.close(); }
    }

    /** A viewer arriving as the hub closes (the service stopping) must not open a camera no one would close again. */
    @Test public void aClosedHubOpensNothing() {
        AtomicInteger opened = new AtomicInteger();
        CameraHub hub = new CameraHub((id, s) -> { opened.incrementAndGet(); return () -> { }; }, System::currentTimeMillis);
        hub.close();
        hub.acquire("printer"); hub.tick();
        assertEquals(0, opened.get()); assertFalse(hub.pulling("printer"));
    }

    /** While the app's own view is still connecting (no frame yet), the hub does not open a second stream to the camera. */
    @Test public void theHubWaitsForTheAppsViewToConnect() {
        AtomicLong clock = new AtomicLong(1_000_000); AtomicInteger opened = new AtomicInteger();
        CameraHub hub = new CameraHub((id, s) -> { opened.incrementAndGet(); return () -> { }; }, clock::get);
        try {
            hub.acquire("printer"); assertEquals(1, opened.get());
            hub.yield("printer");
            clock.addAndGet(CameraHub.APP_HOLD_MS + 2_000); hub.tick();
            assertEquals("its first frame can take several seconds", 1, opened.get());
            clock.addAndGet(CameraHub.YIELD_MS); hub.tick();
            assertEquals("it never came: the hub takes the camera again", 2, opened.get());
        } finally { hub.close(); }
    }

    /** The newest kept picture failing to read once does not end the source (a dead one would be held while watched). */
    @Test public void picturesKeepGoingAfterAFailedRead() throws Exception {
        AtomicInteger asked = new AtomicInteger(); java.util.concurrent.CountDownLatch got = new java.util.concurrent.CountDownLatch(1);
        CameraHub.Source s = CameraHub.pictures(() -> { if (asked.incrementAndGet() == 1) throw new IllegalStateException("being written"); return JPEG; }, 20, new CameraHub.Sink() {
            public void frame(byte[] jpeg) { got.countDown(); }
            public void failed(String why) { }
        });
        try { assertTrue(got.await(3, java.util.concurrent.TimeUnit.SECONDS)); } finally { s.close(); }
    }

    /** A camera's failure as other apps (and the log) see it: no address, host name, user or password. */
    @Test public void failuresSayNoAddresses() {
        String[] said = {
            CameraHub.words(new java.net.UnknownHostException("garage-cam.lan")),
            CameraHub.words(new java.net.ConnectException("failed to connect to garage-cam.lan/192.168.1.5 (port 80) from /fe80::1c2:3%wlan0 (port 5555)")),
            CameraHub.words(new java.io.IOException("Server returned 401 for URL: http://admin:hunter2@garage-cam.lan/snap.jpg")),
            CameraHub.words(new java.io.IOException("no route to fd00:1:2:3:4:5:6:7 or 2001:db8::8")),
            CameraHub.words(new javax.net.ssl.SSLPeerUnverifiedException("Hostname garage-cam.lan not verified")),
        };
        for (String words : said) for (String secret : new String[] {"garage", "192.168", "fe80", "admin", "hunter2", "fd00", "2001:db8"}) assertFalse(words, words.contains(secret));
        assertTrue(said[0], said[0].startsWith("UnknownHostException"));
    }

    @Test public void awaitHandsOutTheNextFrame() throws Exception {
        CameraHub hub = new CameraHub((id, s) -> null, System::currentTimeMillis);
        try {
            assertNull(hub.await("printer", 0, 50));
            new Thread(() -> { try { Thread.sleep(100); } catch (InterruptedException e) { } hub.offer("printer", JPEG); }).start();
            CameraHub.Frame f = hub.await("printer", 0, 3000);
            assertNotNull(f); assertEquals(1, f.seq);
            assertNull("nothing newer", hub.await("printer", 1, 50));
        } finally { hub.close(); }
    }
}
