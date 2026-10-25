package io.github.thelastfrogrammer.elink;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Camera pictures shared by every outside viewer (the local API's snapshots and streams): one connection to a camera
 * however many watch, opened only while someone watches and closed a few seconds after the last one leaves. The printer's
 * camera may serve only one stream at a time, so while the app's own camera view is open its frames feed the hub and the
 * hub does not open a second stream; it takes over again a few seconds after the app's view closes. Frames are passed on
 * as the camera sent them (JPEG), never decoded or re-encoded. Independent of Android.
 */
final class CameraHub implements AutoCloseable {
    static final long KEEP_MS = 5_000, APP_HOLD_MS = 3_000, RETRY_MS = 10_000;
    /** After the app's view says it opens the camera, how long the hub waits for its first frame (its connect and first read time out within 11 s). */
    static final long YIELD_MS = 12_000;

    static final class Frame {
        final byte[] jpeg; final long seq, at;
        Frame(byte[] jpeg, long seq, long at) { this.jpeg = jpeg; this.seq = seq; this.at = at; }
    }
    interface Sink { void frame(byte[] jpeg); void failed(String why); }
    interface Source extends AutoCloseable { @Override void close(); }
    /** Opens a camera's source when the hub needs it; null when there is nothing to open now (it asks again later). */
    interface Sources { Source open(String cameraId, Sink sink); }

    private static final class Feed {
        Frame frame; long seq; int viewers; Source source; long lastViewer, retryAt; String problem = "";
        /** Until when the app's own view holds the camera (the hub opens no source of its own before then). */
        long appUntil;
    }
    private final Sources sources;
    private final LongSupplier clock;
    private final Map<String, Feed> feeds = new HashMap<>();
    /** Opens and closes sources twice a second; started by the first viewer, so an unused hub runs nothing. */
    private Thread watchdog;
    private volatile boolean closed;

    CameraHub(Sources sources, LongSupplier clock) { this.sources = sources; this.clock = clock; }
    private synchronized void watch() {
        if (watchdog != null || closed) return;
        watchdog = new Thread(() -> { while (!closed) { tick(); try { Thread.sleep(500); } catch (InterruptedException stop) { return; } } }, "camera-hub");
        watchdog.setDaemon(true); watchdog.start();
    }

    private Feed feed(String id) { return feeds.computeIfAbsent(id, k -> new Feed()); }

    /** A viewer starts watching a camera; the source opens on the next tick if needed. */
    void acquire(String id) { synchronized (this) { Feed f = feed(id); f.viewers++; f.lastViewer = clock.getAsLong(); } watch(); tick(); }
    synchronized void release(String id) { Feed f = feed(id); f.viewers = Math.max(0, f.viewers - 1); f.lastViewer = clock.getAsLong(); }

    /** A frame from a source. */
    void offer(String id, byte[] jpeg) {
        if (jpeg == null || jpeg.length < 4 || (jpeg[0] & 0xff) != 0xff || (jpeg[1] & 0xff) != 0xd8) return;
        synchronized (this) { Feed f = feed(id); f.seq++; f.frame = new Frame(jpeg, f.seq, clock.getAsLong()); f.problem = ""; notifyAll(); }
    }
    /** A frame from the app's own camera view: it holds the camera, so the hub's own source closes. */
    void appFrame(String id, byte[] jpeg) {
        Source drop;
        synchronized (this) { Feed f = feed(id); f.appUntil = Math.max(f.appUntil, clock.getAsLong() + APP_HOLD_MS); drop = f.source; f.source = null; }
        if (drop != null) drop.close();
        offer(id, jpeg);
    }
    /**
     * The app's own camera view is about to open this camera: the hub lets go of it first, and waits for that view's
     * first frame (YIELD_MS at most) before opening its own again, so the camera never has two streams while it connects.
     */
    void yield(String id) {
        Source drop;
        synchronized (this) { Feed f = feed(id); f.appUntil = clock.getAsLong() + YIELD_MS; drop = f.source; f.source = null; }
        if (drop != null) drop.close();
    }

    synchronized Frame latest(String id) { Feed f = feeds.get(id); return f == null ? null : f.frame; }
    /** The first frame newer than `afterSeq`, waiting up to `timeoutMs`; null when none came. */
    synchronized Frame await(String id, long afterSeq, long timeoutMs) throws InterruptedException {
        long until = System.currentTimeMillis() + timeoutMs;
        while (!closed) {
            Feed f = feed(id);
            if (f.frame != null && f.frame.seq > afterSeq) return f.frame;
            long left = until - System.currentTimeMillis(); if (left <= 0) return null;
            wait(left);
        }
        return null;
    }
    /** Whether the hub itself holds a connection to the camera now. */
    synchronized boolean pulling(String id) { Feed f = feeds.get(id); return f != null && f.source != null; }
    synchronized int viewers() { int n = 0; for (Feed f : feeds.values()) n += f.viewers; return n; }
    synchronized int viewers(String id) { Feed f = feeds.get(id); return f == null ? 0 : f.viewers; }
    /** Why the camera's source last failed ("" when it did not). */
    synchronized String problem(String id) { Feed f = feeds.get(id); return f == null ? "" : f.problem; }

    /** Opens sources that are wanted and closes those no one watches. */
    void tick() {
        if (closed) return;   // a closed hub opens nothing: no one would close it again
        long now = clock.getAsLong();
        Map<String, Source> close = new HashMap<>(); Map<String, Feed> open = new HashMap<>();
        synchronized (this) {
            for (Map.Entry<String, Feed> e : feeds.entrySet()) {
                Feed f = e.getValue();
                if (f.source != null && f.viewers == 0 && now - f.lastViewer > KEEP_MS) { close.put(e.getKey(), f.source); f.source = null; }
                else if (f.source == null && f.viewers > 0 && now >= f.appUntil && now >= f.retryAt) { f.retryAt = now + RETRY_MS; open.put(e.getKey(), f); }
            }
        }
        for (Source s : close.values()) s.close();
        for (Map.Entry<String, Feed> e : open.entrySet()) {
            String id = e.getKey(); Feed f = e.getValue();
            Source[] mine = new Source[1]; boolean[] dead = new boolean[1];
            Source s;
            try {
                s = sources.open(id, new Sink() {
                    public void frame(byte[] jpeg) { if (!dead[0]) offer(id, jpeg); }
                    public void failed(String why) {
                        synchronized (CameraHub.this) {
                            dead[0] = true; f.problem = why; f.retryAt = clock.getAsLong() + RETRY_MS;
                            if (mine[0] != null && f.source == mine[0]) f.source = null;
                            CameraHub.this.notifyAll();
                        }
                    }
                });
            } catch (RuntimeException failed) { s = null; synchronized (this) { f.problem = "could not open: " + failed.getClass().getSimpleName(); } }
            if (s == null) continue;
            boolean keep;
            synchronized (this) { mine[0] = s; keep = !closed && !dead[0] && f.viewers > 0 && f.source == null && clock.getAsLong() >= f.appUntil; if (keep) f.source = s; }
            if (!keep) s.close();
        }
    }

    @Override public void close() {
        closed = true; synchronized (this) { if (watchdog != null) watchdog.interrupt(); }
        Map<String, Source> all = new HashMap<>();
        synchronized (this) { for (Map.Entry<String, Feed> e : feeds.entrySet()) if (e.getValue().source != null) { all.put(e.getKey(), e.getValue().source); e.getValue().source = null; } notifyAll(); }
        for (Source s : all.values()) s.close();
    }

    // ------------------------------------------------------------------ sources

    /** An MJPEG stream (or a camera answering one JPEG per request) read on its own thread. */
    static Source mjpeg(String url, PrinterHttp.ConnectionFactory factory, Sink sink) {
        HttpURLConnection[] open = new HttpURLConnection[1]; AtomicBoolean stopped = new AtomicBoolean();   // seen by the reading thread
        Thread t = new Thread(() -> {
            try {
                HttpURLConnection c = factory.open(new URL(url)); open[0] = c;
                c.setInstanceFollowRedirects(false); c.setConnectTimeout(5000); c.setReadTimeout(8000);
                if (c.getResponseCode() != 200) throw new IOException("camera answered HTTP " + c.getResponseCode());
                String type = String.valueOf(c.getContentType()).toLowerCase(java.util.Locale.ROOT);
                if (!type.contains("multipart/x-mixed-replace") && !type.contains("image/jpeg")) throw new IOException("not a JPEG stream");
                try (InputStream in = new BufferedInputStream(c.getInputStream(), 32768)) { while (!stopped.get()) sink.frame(JpegFrames.next(in)); }
            } catch (Exception failure) { if (!stopped.get()) sink.failed(words(failure)); }
            finally { HttpURLConnection c = open[0]; if (c != null) c.disconnect(); }
        }, "camera-hub-stream");
        t.setDaemon(true); t.start();
        return () -> { stopped.set(true); HttpURLConnection c = open[0]; if (c != null) c.disconnect(); t.interrupt(); };
    }
    /** A camera that serves one JPEG per request, asked every `everyMs`; three failures in a row end it. */
    static Source snapshots(String url, PrinterHttp.ConnectionFactory factory, long everyMs, Sink sink) {
        AtomicBoolean stopped = new AtomicBoolean();
        Thread t = new Thread(() -> {
            int failures = 0;
            while (!stopped.get()) {
                HttpURLConnection c = null;
                try {
                    c = factory.open(new URL(url)); c.setInstanceFollowRedirects(false); c.setConnectTimeout(5000); c.setReadTimeout(8000);
                    if (c.getResponseCode() != 200) throw new IOException("camera answered HTTP " + c.getResponseCode());
                    try (InputStream in = c.getInputStream()) { sink.frame(read(in, 8 * 1024 * 1024)); failures = 0; }
                } catch (Exception failure) { if (stopped.get()) return; if (++failures >= 3) { sink.failed(words(failure)); return; } }
                finally { if (c != null) c.disconnect(); }
                try { Thread.sleep(Math.max(200, everyMs)); } catch (InterruptedException stop) { return; }
            }
        }, "camera-hub-snapshots");
        t.setDaemon(true); t.start();
        return () -> { stopped.set(true); t.interrupt(); };
    }
    /** No live camera: the newest kept picture, looked at every `everyMs` (nothing is sent anywhere). */
    static Source pictures(Supplier<byte[]> newest, long everyMs, Sink sink) {
        AtomicBoolean stopped = new AtomicBoolean();
        Thread t = new Thread(() -> {
            byte[] last = null;
            while (!stopped.get()) {
                // A picture that cannot be read now is tried again next time: a thread that died here would leave the hub
                // holding a source that never sends anything.
                byte[] now; try { now = newest.get(); } catch (RuntimeException unreadable) { now = null; }
                if (now != null && now != last && (last == null || !java.util.Arrays.equals(now, last))) { last = now; sink.frame(now); }
                try { Thread.sleep(everyMs); } catch (InterruptedException stop) { return; }
            }
        }, "camera-hub-pictures");
        t.setDaemon(true); t.start();
        return () -> { stopped.set(true); t.interrupt(); };
    }
    private static byte[] read(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[16384]; int n, total = 0;
        while ((n = in.read(buffer)) > 0) { total += n; if (total > limit) throw new IOException("picture too large"); out.write(buffer, 0, n); }
        return out.toByteArray();
    }
    /** A failure in words without addresses, host names, user names or ids (other apps and the log see it). */
    static String words(Exception e) {
        // A host that is not found is all its message says.
        String message = e instanceof java.net.UnknownHostException ? "the camera's address was not found" : e.getMessage();
        String m = e.getClass().getSimpleName() + (message == null ? "" : ": " + message);
        m = m.replaceAll("(?i)[a-z][a-z0-9+.-]*://\\S+", "<camera>")                                       // addresses, with any user and password
            .replaceAll("\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?", "<camera>")                                    // IPv4
            .replaceAll("(?i)\\[?\\b(?:[0-9a-f]{1,4}:){7}[0-9a-f]{1,4}\\b\\]?(?:%[\\w.]+)?", "<camera>")        // IPv6, in full
            .replaceAll("(?i)\\[?(?:[0-9a-f]{1,4}(?::[0-9a-f]{1,4})*)?::(?:[0-9a-f]{1,4}(?::[0-9a-f]{1,4})*)?\\]?(?:%[\\w.]+)?", "<camera>")   // IPv6, shortened
            .replaceAll("[\\w.-]*/<camera>", "<camera>")                                                   // "name/address", as Java writes a host
            .replaceAll("(?i)(host(?:name)?\\s+)\"?[\\w.-]+\"?", "$1<camera>");
        return Diagnostics.scrub(m);
    }
}
