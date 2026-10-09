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
            assertEquals(128, target.getDouble(0), 0.01); assertEquals(128, target.getDouble(1), 0.01); assertEquals(0, target.getDouble(2), 0.01);
            assertTrue(pose.getDouble("fov") > 20 && pose.getDouble("fov") < 120);
        }
    }
}
