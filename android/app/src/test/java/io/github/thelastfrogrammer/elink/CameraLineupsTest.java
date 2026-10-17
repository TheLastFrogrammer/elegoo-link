package io.github.thelastfrogrammer.elink;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class CameraLineupsTest {
    @Test public void eachCameraKeepsItsOwnLineUp() throws Exception {
        SharedPreferences prefs = RuntimeEnvironment.getApplication().getSharedPreferences("viewer-hints-test", Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
        // The printer's camera lined up by hand at bed height 0; the added camera not yet.
        double[] printer = {0, 300, -10, 30, 135, 10, 40, 0.2, 0};
        prefs.edit().putString("cameraPoints", "[" + new org.json.JSONArray(java.util.Arrays.stream(printer).boxed().toArray()).toString() + "]")
            .putInt("cameraSpot", GcodeViewerActivity.LINED_UP).commit();
        assertTrue(GcodeViewerActivity.linedUp(prefs, ""));
        assertFalse("an added camera has no line-up of its own yet", GcodeViewerActivity.linedUp(prefs, "side"));
        assertEquals(300, GcodeViewerActivity.cameraFor(prefs, 0)[0], 1e-6);
        assertEquals(300, GcodeViewerActivity.cameraFor(prefs, 0, "")[0], 1e-6);
        double[] side = GcodeViewerActivity.cameraFor(prefs, 0, "side");
        assertArrayEquals("before its line-up an added camera starts above the front", GcodeViewerActivity.spotParams(4), side, 1e-9);
        prefs.edit().putString(GcodeViewerActivity.camKey("cameraPoints", "side"), "[[0, 128, -150, 200, 90, 45, 60, 0, 0]]").putInt(GcodeViewerActivity.camKey("cameraSpot", "side"), GcodeViewerActivity.LINED_UP).commit();
        assertTrue(GcodeViewerActivity.linedUp(prefs, "side"));
        assertEquals(128, GcodeViewerActivity.cameraFor(prefs, 0, "side")[0], 1e-6);
        assertEquals("the printer's own line-up is untouched", 300, GcodeViewerActivity.cameraFor(prefs, 0)[0], 1e-6);
        assertEquals("cameraSpot", GcodeViewerActivity.camKey("cameraSpot", "")); assertEquals("cameraSpot@side", GcodeViewerActivity.camKey("cameraSpot", "side"));
    }

    @Test public void snapshotCamerasArePolledAndStopAfterThreeFailures() throws Exception {
        byte[] jpeg = {(byte) 0xff, (byte) 0xd8, 1, 2, 3, (byte) 0xff, (byte) 0xd9};
        AtomicInteger asked = new AtomicInteger(); AtomicInteger failFrom = new AtomicInteger(Integer.MAX_VALUE);
        try (java.net.ServerSocket server = new java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))) {
            Thread loop = new Thread(() -> {
                while (!server.isClosed()) try (java.net.Socket client = server.accept()) {
                    java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(client.getInputStream(), "ISO-8859-1"));
                    String line; while ((line = in.readLine()) != null && !line.isEmpty()) { }
                    OutputStream o = client.getOutputStream();
                    if (asked.incrementAndGet() >= failFrom.get()) o.write("HTTP/1.0 500 Oops\r\nContent-Length: 0\r\n\r\n".getBytes());
                    else { o.write(("HTTP/1.0 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: " + jpeg.length + "\r\n\r\n").getBytes()); o.write(jpeg); }
                    o.flush();
                } catch (Exception closed) { }
            });
            loop.setDaemon(true); loop.start();
            CountDownLatch frames = new CountDownLatch(2), stopped = new CountDownLatch(1); AtomicReference<String> error = new AtomicReference<>();
            CameraFrames[] holder = new CameraFrames[1];
            holder[0] = CameraFrames.snapshots("http://127.0.0.1:" + server.getLocalPort() + "/snap.jpg", url -> (java.net.HttpURLConnection) url.openConnection(), 200, new CameraFrames.Listener() {
                public void frame() { CameraFrames f = holder[0]; if (f != null) f.take(); frames.countDown(); }
                public void error(String message) { error.set(message); stopped.countDown(); }
            });
            assertTrue("two pictures arrive", frames.await(10, TimeUnit.SECONDS));
            assertArrayEquals(jpeg, holder[0].latest());
            failFrom.set(asked.get() + 1);
            assertTrue("three failures end it", stopped.await(10, TimeUnit.SECONDS));
            assertNotNull(error.get()); assertTrue(error.get(), error.get().contains("stopped answering"));
            holder[0].close();
        }
    }
}
