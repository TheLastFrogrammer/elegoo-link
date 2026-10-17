package io.github.thelastfrogrammer.elink;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.graphics.Bitmap;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 34)
public class ExtraCamerasTest {
    private java.net.ServerSocket server;
    @After public void stop() throws Exception { if (server != null) server.close(); }

    private static byte[] jpeg() {
        Bitmap b = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888); b.eraseColor(0xff336699);
        ByteArrayOutputStream out = new ByteArrayOutputStream(); b.compress(Bitmap.CompressFormat.JPEG, 80, out); return out.toByteArray();
    }
    /** A tiny camera: /stream (MJPEG), /snap.jpg, /page (HTML), /locked (Basic admin:secret), /moved (302). */
    private String serve() throws Exception {
        byte[] picture = jpeg();
        server = new java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"));
        Thread loop = new Thread(() -> {
            while (!server.isClosed()) {
                try (java.net.Socket client = server.accept()) {
                    java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(client.getInputStream(), "ISO-8859-1"));
                    String first = in.readLine(); if (first == null) continue;
                    String path = first.split(" ")[1], auth = "", line;
                    while ((line = in.readLine()) != null && !line.isEmpty()) if (line.toLowerCase().startsWith("authorization:")) auth = line.substring(14).trim();
                    OutputStream o = client.getOutputStream();
                    String okAuth = "Basic " + java.util.Base64.getEncoder().encodeToString("admin:secret".getBytes());
                    if (path.equals("/stream")) {
                        o.write("HTTP/1.0 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=frame\r\n\r\n".getBytes());
                        for (int i = 0; i < 2; i++) { o.write("--frame\r\nContent-Type: image/jpeg\r\n\r\n".getBytes()); o.write(picture); o.write("\r\n".getBytes()); }
                    } else if (path.equals("/snap.jpg") || path.equals("/locked") && auth.equals(okAuth)) {
                        o.write(("HTTP/1.0 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: " + picture.length + "\r\n\r\n").getBytes()); o.write(picture);
                    } else if (path.equals("/locked")) o.write("HTTP/1.0 401 Unauthorized\r\nContent-Length: 0\r\n\r\n".getBytes());
                    else if (path.equals("/page")) o.write("HTTP/1.0 200 OK\r\nContent-Type: text/html\r\nContent-Length: 6\r\n\r\n<html>".getBytes());
                    else if (path.equals("/moved")) o.write("HTTP/1.0 302 Found\r\nLocation: http://elsewhere.example/\r\nContent-Length: 0\r\n\r\n".getBytes());
                    else o.write("HTTP/1.0 404 Not Found\r\nContent-Length: 0\r\n\r\n".getBytes());
                    o.flush();
                } catch (Exception closed) { }
            }
        });
        loop.setDaemon(true); loop.start();
        return "http://127.0.0.1:" + server.getLocalPort();
    }

    @Test public void probeTellsStreamsFromSnapshotsAndSaysWhatIsWrong() throws Exception {
        String base = serve();
        Bitmap[] picture = new Bitmap[1];
        assertEquals(ExtraCameras.STREAM, ExtraCameras.probe(base + "/stream", ExtraCameras.connections("", ""), picture));
        assertNotNull(picture[0]); assertEquals(64, picture[0].getWidth());
        assertEquals(ExtraCameras.SNAPSHOT, ExtraCameras.probe(base + "/snap.jpg", ExtraCameras.connections(null, null), picture));
        assertEquals(ExtraCameras.SNAPSHOT, ExtraCameras.probe(base + "/locked", ExtraCameras.connections("admin", "secret"), picture));
        for (String[] bad : new String[][] {{"/page", "web page"}, {"/locked", "user name or password"}, {"/moved", "elsewhere"}}) {
            try { ExtraCameras.probe(base + bad[0], ExtraCameras.connections("admin", "wrong"), picture); fail(bad[0]); }
            catch (java.io.IOException expected) { assertTrue(expected.getMessage(), expected.getMessage().contains(bad[1])); }
        }
        assertEquals(64, ExtraCameras.decode(ExtraCameras.fetch(base + "/snap.jpg", ExtraCameras.connections("", ""))).getWidth());
    }

    @Test public void addressesAreCheckedAndCredentialsKeptOutOfThem() {
        assertEquals("", ExtraCameras.problem("http://192.168.1.50:8080/?action=stream"));
        assertEquals("", ExtraCameras.problem("HTTPS://cam.local/snapshot.jpg"));
        assertTrue(ExtraCameras.problem("rtsp://192.168.1.50/live").contains("RTSP"));
        assertTrue(ExtraCameras.problem("http://admin:pw@192.168.1.50/").contains("own fields"));
        assertTrue(ExtraCameras.problem("http:///x").contains("host"));
        assertFalse(ExtraCameras.problem("not an address").isEmpty());
    }

    @Test public void camerasAreSavedWithOnlyOneTakingThePicturesAndNoAddressInPrefs() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        ExtraCameras.Camera a = new ExtraCameras.Camera(), b = new ExtraCameras.Camera();
        a.name = "Side"; a.pictures = true; a.rotation = 90; b.name = "Top"; b.pictures = true; b.kind = ExtraCameras.SNAPSHOT;
        List<ExtraCameras.Camera> both = new ArrayList<>(List.of(a, b));
        ExtraCameras.save(context, both);
        List<ExtraCameras.Camera> read = ExtraCameras.list(context);
        assertEquals(2, read.size());
        assertTrue(read.get(0).pictures); assertFalse("one camera takes the pictures", read.get(1).pictures);
        assertEquals(90, read.get(0).rotation); assertEquals(ExtraCameras.SNAPSHOT, read.get(1).kind);
        assertEquals("Side", ExtraCameras.forPictures(context).name);
        String stored = context.getSharedPreferences("workshop-settings", Context.MODE_PRIVATE).getString(ExtraCameras.PREF, "");
        assertFalse("no address in the settings", stored.contains("http"));
        ExtraCameras.Camera odd = ExtraCameras.Camera.fromJson(new JSONObject().put("name", "").put("rotation", 45).put("kind", "rtsp"));
        assertEquals("Camera", odd.name); assertEquals(0, odd.rotation); assertEquals(ExtraCameras.STREAM, odd.kind);
        assertNull(ExtraCameras.find(context, "nobody"));
    }

    @Test public void turningMakesAPortraitPictureLandscapeAndBack() {
        Bitmap wide = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888);
        assertEquals(20, ExtraCameras.turn(wide, 90, false).getWidth());
        assertEquals(40, ExtraCameras.turn(wide, 180, true).getWidth());
        assertTrue(ExtraCameras.turn(wide, 0, false) == wide);
    }
}
