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
            assertTrue(position.getDouble(2) > 100);
            assertTrue("Aims downwards", target.getDouble(2) < position.getDouble(2));
            assertTrue(pose.getDouble("fov") > 20 && pose.getDouble("fov") < 120);
        }
    }

    @Test public void presetSpotsBecomeParametersThatAimAtTheBedCentre() throws Exception {
        for (int spot = 0; spot < GcodeViewerActivity.LINED_UP; spot++) {
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
        assertArrayEquals(new double[] {1, 2, 3, 4, 5, 6, 0.25}, GcodeViewerActivity.parseParams("1,2,3,4,5,6,0.25"), 1e-12);
        assertNull(GcodeViewerActivity.parseParams("1,2,3"));
        assertNull(GcodeViewerActivity.parseParams("1,2,3,4,5,6,x"));
        assertNull(GcodeViewerActivity.parseParams(null));
    }
}
