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
