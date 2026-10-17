package io.github.thelastfrogrammer.elink;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Cameras people add beside the printer's own: any camera that serves an MJPEG stream or a JPEG snapshot over http(s)
 * (a webcam with mjpg-streamer or go2rtc, an ESP32-CAM, an IP camera's snapshot address, a phone running an IP-webcam app,
 * a Home Assistant camera proxy). The address and password are kept encrypted (CredentialStore); the log names a camera,
 * never its address. One camera can take the print pictures instead of the printer's own.
 */
final class ExtraCameras {
    static final String STREAM = "stream", SNAPSHOT = "snapshot";
    static final String PREF = "extraCameras";

    static final class Camera {
        String id = UUID.randomUUID().toString(), name = "", kind = STREAM, user = "";
        /** Degrees clockwise, 0, 90, 180 or 270; and a mirror image (left to right). */
        int rotation; boolean mirror;
        /** Takes the running print's pictures (for recordings, timelapses, notifications and automations). */
        boolean pictures;
        JSONObject toJson() throws JSONException {
            return new JSONObject().put("id", id).put("name", name).put("kind", kind).put("user", user).put("rotation", rotation).put("mirror", mirror).put("pictures", pictures);
        }
        static Camera fromJson(JSONObject o) {
            Camera c = new Camera();
            c.id = o.optString("id", c.id); c.name = Automation.cap(o.optString("name", "Camera").trim(), 40); c.kind = SNAPSHOT.equals(o.optString("kind")) ? SNAPSHOT : STREAM;
            c.user = o.optString("user", ""); int r = o.optInt("rotation", 0); c.rotation = r == 90 || r == 180 || r == 270 ? r : 0; c.mirror = o.optBoolean("mirror"); c.pictures = o.optBoolean("pictures");
            if (c.name.isEmpty()) c.name = "Camera";
            return c;
        }
    }

    static List<Camera> list(Context context) {
        List<Camera> out = new ArrayList<>();
        try { JSONArray a = new JSONArray(context.getSharedPreferences("workshop-settings", Context.MODE_PRIVATE).getString(PREF, "[]"));
            for (int i = 0; i < a.length() && i < 8; i++) { JSONObject o = a.optJSONObject(i); if (o != null) out.add(Camera.fromJson(o)); } }
        catch (JSONException unreadable) { }
        return out;
    }
    static void save(Context context, List<Camera> cameras) {
        JSONArray a = new JSONArray();
        boolean picturesTaken = false;
        for (Camera c : cameras) { if (c.pictures && picturesTaken) c.pictures = false; picturesTaken |= c.pictures; try { a.put(c.toJson()); } catch (JSONException impossible) { } }
        context.getSharedPreferences("workshop-settings", Context.MODE_PRIVATE).edit().putString(PREF, a.toString()).apply();
    }
    static Camera find(Context context, String id) { for (Camera c : list(context)) if (c.id.equals(id)) return c; return null; }
    /** The camera chosen for print pictures, or null (the printer's own camera). */
    static Camera forPictures(Context context) { for (Camera c : list(context)) if (c.pictures) return c; return null; }

    // Secrets: the address (it can hold a token) and the password, encrypted on the phone.
    static void setSecrets(Context context, Camera c, String url, String password) throws Exception {
        CredentialStore store = new CredentialStore(context);
        if (url != null) store.saveSecret("camera-url:" + c.id, url);
        if (password != null) { if (password.isEmpty()) store.forget("camera-password:" + c.id); else store.saveSecret("camera-password:" + c.id, password); }
    }
    static String url(Context context, Camera c) {
        try { CredentialStore store = new CredentialStore(context); return store.remembers("camera-url:" + c.id) ? store.load("camera-url:" + c.id) : ""; } catch (Exception unreadable) { return ""; }
    }
    static String password(Context context, Camera c) {
        try { CredentialStore store = new CredentialStore(context); return store.remembers("camera-password:" + c.id) ? store.load("camera-password:" + c.id) : ""; } catch (Exception unreadable) { return ""; }
    }
    static void forget(Context context, Camera c) { CredentialStore store = new CredentialStore(context); store.forget("camera-url:" + c.id); store.forget("camera-password:" + c.id); }

    /** Why an address cannot be used, or "": http or https, a host, no name:password@ in it (use the fields). */
    static String problem(String url) {
        try {
            URI u = new URI(url.trim());
            if (!"http".equalsIgnoreCase(u.getScheme()) && !"https".equalsIgnoreCase(u.getScheme())) return "The address must start with http:// or https://. For an RTSP camera, use its snapshot address, or a bridge such as go2rtc that serves MJPEG.";
            if (u.getHost() == null || u.getHost().isEmpty()) return "The address has no camera host.";
            if (u.getRawUserInfo() != null) return "Put the user name and password in their own fields, not in the address.";
            if (u.getPort() > 65535) return "The port is not valid.";
            return "";
        } catch (Exception bad) { return "That is not a web address."; }
    }

    /** Connections with the camera's user name and password (HTTP Basic), never following redirects. */
    static PrinterHttp.ConnectionFactory connections(String user, String password) {
        String auth = user == null || user.isEmpty() ? null : "Basic " + android.util.Base64.encodeToString((user + ":" + (password == null ? "" : password)).getBytes(StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
        return target -> {
            HttpURLConnection c = (HttpURLConnection) target.openConnection();
            c.setInstanceFollowRedirects(false);
            if (auth != null) c.setRequestProperty("Authorization", auth);
            return c;
        };
    }

    interface Feed extends AutoCloseable { @Override void close(); }

    /** Opens a camera: frames arrive on the main thread, turned as set. */
    static Feed open(Context context, Camera camera, MjpegPlayer.Listener listener) {
        String url = url(context, camera), password = password(context, camera);
        MjpegPlayer.Listener turned = new MjpegPlayer.Listener() {
            public void frame(Bitmap image) { listener.frame(turn(image, camera.rotation, camera.mirror)); }
            public void error(String message) { listener.error(message); }
        };
        if (url.isEmpty() || !problem(url).isEmpty()) {
            new Handler(Looper.getMainLooper()).post(() -> listener.error("The camera “" + camera.name + "” has no usable address. Edit it in Settings, Cameras."));
            return () -> { };
        }
        PrinterHttp.ConnectionFactory factory = connections(camera.user, password);
        if (SNAPSHOT.equals(camera.kind)) return new SnapshotPoller(url, factory, turned, 1000);
        MjpegPlayer player = new MjpegPlayer(url, factory, turned);
        return player::close;
    }

    static Bitmap turn(Bitmap image, int rotation, boolean mirror) {
        if (image == null || rotation == 0 && !mirror) return image;
        android.graphics.Matrix m = new android.graphics.Matrix();
        if (mirror) m.postScale(-1, 1);
        if (rotation != 0) m.postRotate(rotation);
        return Bitmap.createBitmap(image, 0, 0, image.getWidth(), image.getHeight(), m, true);
    }

    /** A camera that serves one JPEG per request: asked again every `everyMs`. Three failures in a row end it. */
    static final class SnapshotPoller implements Feed {
        private final Handler main = new Handler(Looper.getMainLooper());
        private volatile boolean closed;
        private final Thread thread;
        SnapshotPoller(String url, PrinterHttp.ConnectionFactory factory, MjpegPlayer.Listener listener, long everyMs) {
            thread = new Thread(() -> {
                int failures = 0;
                while (!closed) {
                    try {
                        Bitmap frame = decode(fetch(url, factory));
                        failures = 0;
                        main.post(() -> { if (!closed) listener.frame(frame); });
                    } catch (Exception error) {
                        if (++failures >= 3) { String why = describe(error); main.post(() -> { if (!closed) listener.error("Camera unavailable (" + why + "). Check its address, user name and password, and that the phone can reach it."); }); return; }
                    }
                    try { Thread.sleep(everyMs); } catch (InterruptedException stop) { return; }
                }
            }, "camera-snapshots");
            thread.setDaemon(true); thread.start();
        }
        @Override public void close() { closed = true; thread.interrupt(); main.removeCallbacksAndMessages(null); }
    }

    /** One JPEG from a snapshot address (at most 8 MB). */
    static byte[] fetch(String url, PrinterHttp.ConnectionFactory factory) throws IOException {
        HttpURLConnection c = factory.open(new URL(url));
        try {
            c.setConnectTimeout(5000); c.setReadTimeout(8000);
            int code = c.getResponseCode();
            if (code == 401 || code == 403) throw new IOException("the camera refused the user name or password (HTTP " + code + ")");
            if (code != 200) throw new IOException("HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[16384]; int n, total = 0;
                while ((n = in.read(buffer)) > 0) { total += n; if (total > 8 * 1024 * 1024) throw new IOException("picture too large"); out.write(buffer, 0, n); }
                return out.toByteArray();
            }
        } finally { c.disconnect(); }
    }
    static Bitmap decode(byte[] jpeg) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth > 8192 || bounds.outHeight > 8192) throw new IOException("not a picture");
        BitmapFactory.Options options = new BitmapFactory.Options(); options.inSampleSize = 1;
        while (bounds.outWidth / options.inSampleSize > 1920 || bounds.outHeight / options.inSampleSize > 1080) options.inSampleSize *= 2;
        Bitmap frame = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, options);
        if (frame == null) throw new IOException("not a picture");
        return frame;
    }

    /** What an address serves: STREAM (MJPEG), SNAPSHOT (a JPEG), or throws with the reason. Reads one picture to prove it. Off the main thread. */
    static String probe(String url, PrinterHttp.ConnectionFactory factory, Bitmap[] picture) throws IOException {
        HttpURLConnection c = factory.open(new URL(url));
        try {
            c.setConnectTimeout(5000); c.setReadTimeout(8000);
            int code = c.getResponseCode();
            if (code == 401 || code == 403) throw new IOException("the camera refused the user name or password (HTTP " + code + ")");
            if (code >= 300 && code < 400) throw new IOException("the camera sends the app elsewhere (HTTP " + code + "); use the final address");
            if (code != 200) throw new IOException("HTTP " + code);
            String type = c.getContentType() == null ? "" : c.getContentType().toLowerCase(Locale.ROOT);
            try (InputStream in = new java.io.BufferedInputStream(c.getInputStream(), 32768)) {
                if (type.contains("multipart/x-mixed-replace")) { picture[0] = decode(JpegFrames.next(in)); return STREAM; }
                if (type.startsWith("image/jpeg") || type.startsWith("image/jpg")) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[16384]; int n, total = 0;
                    while ((n = in.read(buffer)) > 0) { total += n; if (total > 8 * 1024 * 1024) throw new IOException("picture too large"); out.write(buffer, 0, n); }
                    picture[0] = decode(out.toByteArray()); return SNAPSHOT;
                }
                if (type.startsWith("text/html")) throw new IOException("this address is a web page, not the camera's picture; look for its MJPEG or snapshot address");
                throw new IOException("it answers with " + (type.isEmpty() ? "no content type" : type.split(";")[0]) + ", not MJPEG or JPEG");
            }
        } finally { c.disconnect(); }
    }
    static String describe(Exception error) {
        String text = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        if (error instanceof java.net.UnknownHostException) return "the camera's name was not found";
        if (error instanceof java.net.SocketTimeoutException) return "no answer in time";
        if (error instanceof java.net.ConnectException) return "the connection was refused or the camera is off";
        if (error instanceof javax.net.ssl.SSLException) return "its secure (https) connection was not trusted; try its http address";
        return Diagnostics.scrub(text);
    }

    /** One picture, for print pictures: from the stream's first frame or a snapshot, then closed. Calls back on the main thread. */
    static void grab(Context context, Camera camera, MjpegPlayer.Listener listener) {
        Feed[] feed = new Feed[1]; boolean[] done = {false};
        Handler main = new Handler(Looper.getMainLooper());
        MjpegPlayer.Listener once = new MjpegPlayer.Listener() {
            public void frame(Bitmap image) { if (done[0]) return; done[0] = true; if (feed[0] != null) feed[0].close(); listener.frame(image); }
            public void error(String message) { if (done[0]) return; done[0] = true; if (feed[0] != null) feed[0].close(); listener.error(message); }
        };
        feed[0] = open(context, camera, once);
        main.postDelayed(() -> once.error("no picture from “" + camera.name + "” within 15 s"), 15_000);
    }
}
