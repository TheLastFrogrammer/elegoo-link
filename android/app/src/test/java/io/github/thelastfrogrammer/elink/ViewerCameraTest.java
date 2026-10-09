package io.github.thelastfrogrammer.elink;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.net.*;
import java.util.concurrent.*;

public class ViewerCameraTest {
    private static byte[] jpeg(int marker) { return new byte[] {(byte) 0xff, (byte) 0xd8, (byte) marker, (byte) 0xff, (byte) 0xd9}; }

    private static HttpURLConnection stream(URL url, String type, byte[] body) {
        return new HttpURLConnection(url) {
            public void connect() { }
            public void disconnect() { }
            public boolean usingProxy() { return false; }
            public int getResponseCode() { return 200; }
            public String getContentType() { return type; }
            public InputStream getInputStream() { return new ByteArrayInputStream(body); }
        };
    }

    @Test public void keepsOnlyTheNewestFrameAndAnnouncesOnceUntilTaken() throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (int i = 1; i <= 3; i++) { body.write("--b\r\nContent-Type: image/jpeg\r\n\r\n".getBytes()); body.write(jpeg(i)); body.write("\r\n".getBytes()); }
        BlockingQueue<String> events = new LinkedBlockingQueue<>();
        CameraFrames frames = new CameraFrames("http://192.168.1.50:8080/?action=stream", url -> stream(url, "multipart/x-mixed-replace;boundary=b", body.toByteArray()),
            new CameraFrames.Listener() {
                public void frame() { events.add("frame"); }
                public void error(String message) { events.add("error " + message); }
            });
        assertEquals("frame", events.poll(5, TimeUnit.SECONDS));
        String end = events.poll(5, TimeUnit.SECONDS);
        assertTrue("The stream ended after three frames: " + end, end != null && end.startsWith("error"));
        assertNull("Only one announcement until the page takes a frame", events.poll(200, TimeUnit.MILLISECONDS));
        assertArrayEquals("The newest frame", jpeg(3), frames.take());
        frames.close();
    }

    @Test public void refusesWhatIsNotAJpegStream() throws Exception {
        BlockingQueue<String> events = new LinkedBlockingQueue<>();
        new CameraFrames("http://192.168.1.50:8080/", url -> stream(url, "text/html", "<html>".getBytes()), new CameraFrames.Listener() {
            public void frame() { events.add("frame"); }
            public void error(String message) { events.add(message); }
        });
        String message = events.poll(5, TimeUnit.SECONDS);
        assertNotNull(message); assertTrue(message, message.contains("Not a JPEG stream"));
    }

    @Test public void cameraSpotsLookAtTheBedFromAbove() throws Exception {
        for (int spot = -1; spot <= GcodeViewerActivity.CAMERA_SPOTS.length; spot++) {
            JSONObject pose = GcodeViewerActivity.cameraPose(spot);
            JSONArray position = pose.getJSONArray("position"), target = pose.getJSONArray("target");
            assertTrue(position.getDouble(2) > 0);
            assertTrue("Aims downwards", target.getDouble(2) < position.getDouble(2));
            assertTrue(pose.getDouble("fov") > 20 && pose.getDouble("fov") < 120);
        }
    }

    @Test public void presetSpotsBecomeParametersThatAimAtTheBedCentre() throws Exception {
        assertArrayEquals(GcodeViewerActivity.CC2_CAMERA, GcodeViewerActivity.spotParams(0), 0);
        assertNotSame("A copy, so lining up never changes the default", GcodeViewerActivity.CC2_CAMERA, GcodeViewerActivity.spotParams(0));
        assertEquals(0.23, GcodeViewerActivity.cameraPose(0).getDouble("lens"), 1e-9);
        for (int spot = 1; spot < GcodeViewerActivity.LINED_UP; spot++) {
            JSONObject preset = GcodeViewerActivity.cameraPose(spot);
            double[] c = GcodeViewerActivity.spotParams(spot);
            JSONArray position = preset.getJSONArray("position"), target = preset.getJSONArray("target");
            // The aim passes through the bed centre: the target lies on the line from the camera to (128, 128, 0).
            double[] toCentre = {128 - c[0], 128 - c[1], -c[2]}, toTarget = {target.getDouble(0) - c[0], target.getDouble(1) - c[1], target.getDouble(2) - c[2]};
            double cross = Math.hypot(Math.hypot(toCentre[1] * toTarget[2] - toCentre[2] * toTarget[1], toCentre[2] * toTarget[0] - toCentre[0] * toTarget[2]), toCentre[0] * toTarget[1] - toCentre[1] * toTarget[0]);
            assertEquals("spot " + spot, 0, cross / Math.hypot(Math.hypot(toCentre[0], toCentre[1]), toCentre[2]) / 200, 1e-6);
            assertEquals(c[0], position.getDouble(0), 1e-9);
        }
    }

    @Test public void handLinedUpCameraIsReadBackOnlyWhenWhole() {
        assertArrayEquals("saved before roll existed: no roll", new double[] {1, 2, 3, 4, 5, 6, 0.25, 0}, GcodeViewerActivity.parseParams("1,2,3,4,5,6,0.25"), 1e-12);
        assertArrayEquals(new double[] {1, 2, 3, 4, 5, 6, 0.25, -2}, GcodeViewerActivity.parseParams("1,2,3,4,5,6,0.25,-2"), 1e-12);
        assertNull(GcodeViewerActivity.parseParams("1,2,3"));
        assertNull(GcodeViewerActivity.parseParams("1,2,3,4,5,6,x"));
        assertNull(GcodeViewerActivity.parseParams(null));
    }

    @Test public void oneLineUpMovesWithTheBedAndSeveralAreFitted() {
        java.util.List<double[]> points = new java.util.ArrayList<>();
        points = GcodeViewerActivity.addPoint(points, 4, new double[] {308, -9, 32, 134, 8, 37, 0.23});
        double[] at80 = GcodeViewerActivity.modelAt(points, 80);
        assertEquals("One line-up: the camera sits as much higher as the bed went down", 108, at80[2], 1e-9);
        assertEquals(134, at80[3], 1e-9);

        // A second height that says the camera rises 0.9 mm per mm and tilts a little more: the fit follows the measurements.
        points = GcodeViewerActivity.addPoint(points, 104, new double[] {308, -9, 122, 134, 10, 37, 0.23});
        double[] at54 = GcodeViewerActivity.modelAt(points, 54);
        assertEquals(77, at54[2], 1e-9);
        assertEquals(9, at54[4], 1e-9);
        assertEquals(308, at54[0], 1e-9);

        // Saving again near a saved height replaces it.
        points = GcodeViewerActivity.addPoint(points, 105, new double[] {308, -9, 123, 134, 10, 37, 0.23});
        assertEquals(2, points.size());
        assertEquals(points.size(), GcodeViewerActivity.parsePoints(GcodeViewerActivity.pointsJson(points)).size());
        assertArrayEquals(points.get(1), GcodeViewerActivity.parsePoints(GcodeViewerActivity.pointsJson(points)).get(1), 1e-12);
        assertTrue(GcodeViewerActivity.parsePoints("not json").isEmpty());
    }

    @Test public void tapsAreKeptPerBedHeight() {
        java.util.List<CameraFit.Mark> a = java.util.Arrays.asList(new CameraFit.Mark(1, 0.2, 0.3), new CameraFit.Mark(3, 0.7, 0.6));
        java.util.List<CameraFit.TapSet> sets = GcodeViewerActivity.withTaps(new java.util.ArrayList<>(), new CameraFit.TapSet(5, 16.0 / 9, a));
        sets = GcodeViewerActivity.withTaps(sets, new CameraFit.TapSet(100, 16.0 / 9, a));
        sets = GcodeViewerActivity.withTaps(sets, new CameraFit.TapSet(101, 16.0 / 9, a.subList(0, 1)));   // replaces the set at 100
        assertEquals(2, sets.size());
        assertEquals(101, sets.get(1).z, 0); assertEquals(1, sets.get(1).marks.size());
        assertEquals("No taps now: the saved ones stay", 2, GcodeViewerActivity.withTaps(sets, new CameraFit.TapSet(50, 1.7, new java.util.ArrayList<>())).size());
        java.util.List<CameraFit.TapSet> back = GcodeViewerActivity.parseTaps(GcodeViewerActivity.tapsJson(sets));
        assertEquals(2, back.size());
        assertEquals(3, back.get(0).marks.get(1).edge);
        assertEquals(0.7, back.get(0).marks.get(1).u, 1e-12);
        assertEquals(16.0 / 9, back.get(0).aspect, 1e-12);
        assertTrue(GcodeViewerActivity.parseTaps("[{]").isEmpty());
    }
}
