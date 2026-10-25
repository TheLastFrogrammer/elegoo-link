package example.printerwatch;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/**
 * A reference client for Link Workshop's local API, for an Android or Fire TV app (Fire OS is Android): finds the phone
 * on the network, pairs once (the person approves on the phone), then follows the status and shows the camera.
 * Plain Android, no libraries. Copy it into your app and call from an Activity:
 *
 *   PrinterWatch watch = new PrinterWatch(this, new PrinterWatch.Listener() { ... });
 *   watch.start();          // discovery, pairing if needed, status and camera
 *   watch.stop();           // in onStop
 *
 * Needs <uses-permission android:name="android.permission.INTERNET"/> and, because the phone answers plain http on the
 * home network, android:usesCleartextTraffic="true" (or a network security config allowing it).
 */
public final class PrinterWatch {
    public interface Listener {
        /** The phone wants approval: show this code big on the TV so the person can match it on the phone. */
        void pairingCode(String code);
        void status(JSONObject status);
        void event(JSONObject event);
        void frame(Bitmap picture);
        void problem(String words);
    }

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;
    private volatile boolean stopped;
    private volatile String base;
    private NsdManager.DiscoveryListener discovery;
    private Thread statusThread, cameraThread;

    public PrinterWatch(Context context, Listener listener) {
        this.context = context.getApplicationContext(); this.listener = listener;
        this.prefs = context.getSharedPreferences("printer-watch", Context.MODE_PRIVATE);
    }

    public void start() {
        stopped = false;
        String known = prefs.getString("base", null);
        if (known != null) connect(known); else discover();
    }
    public void stop() {
        stopped = true;
        NsdManager nsd = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        if (discovery != null) try { nsd.stopServiceDiscovery(discovery); } catch (RuntimeException ignored) { }
        if (statusThread != null) statusThread.interrupt();
        if (cameraThread != null) cameraThread.interrupt();
    }

    /** Finds the phone: it announces itself as _linkworkshop._tcp. */
    private void discover() {
        NsdManager nsd = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        discovery = new NsdManager.DiscoveryListener() {
            @Override public void onServiceFound(NsdServiceInfo found) {
                nsd.resolveService(found, new NsdManager.ResolveListener() {
                    @Override public void onServiceResolved(NsdServiceInfo info) {
                        String host = info.getHost().getHostAddress();
                        String url = "http://" + (host.contains(":") ? "[" + host + "]" : host) + ":" + info.getPort();
                        prefs.edit().putString("base", url).apply();
                        try { nsd.stopServiceDiscovery(discovery); } catch (RuntimeException ignored) { }
                        connect(url);
                    }
                    @Override public void onResolveFailed(NsdServiceInfo info, int error) { }
                });
            }
            @Override public void onDiscoveryStarted(String type) { }
            @Override public void onServiceLost(NsdServiceInfo info) { }
            @Override public void onDiscoveryStopped(String type) { }
            @Override public void onStartDiscoveryFailed(String type, int error) { post(() -> listener.problem("Could not look for the phone on the network.")); }
            @Override public void onStopDiscoveryFailed(String type, int error) { }
        };
        nsd.discoverServices("_linkworkshop._tcp", NsdManager.PROTOCOL_DNS_SD, discovery);
    }

    private void connect(String url) {
        base = url;
        statusThread = new Thread(() -> {
            while (!stopped) {
                try {
                    String token = prefs.getString("token", null);
                    if (token == null) { token = pair(); prefs.edit().putString("token", token).apply(); }
                    if (cameraThread == null || !cameraThread.isAlive()) { String t = token; cameraThread = new Thread(() -> camera(t), "printer-camera"); cameraThread.start(); }
                    events(token);
                } catch (Unpaired e) {
                    prefs.edit().remove("token").apply();
                } catch (Exception e) {
                    post(() -> listener.problem("Lost the phone (" + e.getClass().getSimpleName() + "). Trying again…"));
                    sleep(5000);
                }
            }
        }, "printer-status");
        statusThread.start();
    }

    private String pair() throws Exception {
        JSONObject asked = new JSONObject(read(request("POST", "/api/v1/pair", null, "{\"name\":\"" + android.os.Build.MODEL.replace("\"", "") + "\"}")));
        post(() -> listener.pairingCode(asked.optString("code")));
        long until = System.currentTimeMillis() + asked.optLong("expires_in", 300) * 1000;
        while (!stopped && System.currentTimeMillis() < until) {
            JSONObject state = new JSONObject(read(request("GET", asked.getString("poll"), null, null)));
            if ("approved".equals(state.optString("state"))) return state.getString("token");
            if ("denied".equals(state.optString("state"))) throw new IOException("refused on the phone");
            sleep(2000);
        }
        throw new IOException("not approved in time");
    }

    /** Server-Sent Events: "status" whenever it changes, "event" for print events, a comment every 15 s. */
    private void events(String token) throws Exception {
        HttpURLConnection c = request("GET", "/api/v1/events", token, null);
        c.setReadTimeout(40_000);
        if (c.getResponseCode() == 401) throw new Unpaired();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
            String line, event = null; StringBuilder data = new StringBuilder();
            while (!stopped && (line = in.readLine()) != null) {
                if (line.startsWith("event: ")) event = line.substring(7);
                else if (line.startsWith("data: ")) data.append(line.substring(6));
                else if (line.isEmpty() && event != null) {
                    JSONObject json = new JSONObject(data.toString()); String kind = event;
                    post(() -> { if ("status".equals(kind)) listener.status(json); else listener.event(json); });
                    event = null; data.setLength(0);
                }
            }
        } finally { c.disconnect(); }
    }

    /** The camera as MJPEG: one JPEG after another; each is decoded and handed over (a TV shows them in an ImageView). */
    private void camera(String token) {
        while (!stopped) {
            HttpURLConnection c = null;
            try {
                c = request("GET", "/api/v1/cameras/printer/stream.mjpeg?fps=5", token, null);
                c.setReadTimeout(30_000);
                if (c.getResponseCode() != 200) { post(() -> listener.problem("The camera is not available now.")); sleep(10_000); continue; }
                InputStream in = new BufferedInputStream(c.getInputStream(), 65536);
                while (!stopped) {
                    byte[] jpeg = nextJpeg(in);
                    Bitmap picture = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
                    if (picture != null) post(() -> listener.frame(picture));
                }
            } catch (Exception e) { sleep(5000); }
            finally { if (c != null) c.disconnect(); }
        }
    }
    /** The next JPEG in a stream: from FF D8 to FF D9, whatever the multipart headers between. */
    static byte[] nextJpeg(InputStream in) throws IOException {
        ByteArrayOutputStream frame = null; int previous = -1, b;
        while ((b = in.read()) != -1) {
            if (frame == null) { if (previous == 0xff && b == 0xd8) { frame = new ByteArrayOutputStream(); frame.write(0xff); frame.write(0xd8); } }
            else { frame.write(b); if (previous == 0xff && b == 0xd9) return frame.toByteArray(); if (frame.size() > 4 * 1024 * 1024) throw new IOException("frame too large"); }
            previous = b;
        }
        throw new EOFException();
    }

    private HttpURLConnection request(String method, String path, String token, String body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        c.setRequestMethod(method); c.setConnectTimeout(5000); c.setReadTimeout(15000);
        if (token != null) c.setRequestProperty("Authorization", "Bearer " + token);
        if (body != null) { c.setDoOutput(true); c.setRequestProperty("Content-Type", "application/json"); try (OutputStream o = c.getOutputStream()) { o.write(body.getBytes(StandardCharsets.UTF_8)); } }
        return c;
    }
    private static String read(HttpURLConnection c) throws IOException {
        if (c.getResponseCode() == 401) throw new Unpaired();
        try (InputStream in = c.getResponseCode() < 400 ? c.getInputStream() : c.getErrorStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] b = new byte[4096]; int n; while ((n = in.read(b)) > 0) out.write(b, 0, n);
            return out.toString("UTF-8");
        } finally { c.disconnect(); }
    }
    private static final class Unpaired extends IOException { }
    private void post(Runnable r) { if (!stopped) main.post(r); }
    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
}
