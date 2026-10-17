package io.github.thelastfrogrammer.elink;

import java.io.*;
import java.net.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reads a camera's MJPEG stream (or asks a snapshot camera for a JPEG every so often) and keeps only the newest JPEG, for the
 * toolpath viewer's in-scene camera. The page asks for a frame only after it has shown the previous one, so frames never
 * queue. Independent of Android.
 */
final class CameraFrames implements AutoCloseable {
    interface Listener {
        /** A new frame is ready (worker thread); called again only after take() has handed out the previous one. */
        void frame();
        void error(String message);
    }
    private final Thread thread;
    private final AtomicBoolean announced = new AtomicBoolean();
    private volatile byte[] latest;
    private volatile boolean closed;
    private volatile HttpURLConnection connection;

    CameraFrames(String url, PrinterHttp.ConnectionFactory factory, Listener listener) { this(url, factory, listener, 0); }
    /** A camera that serves one JPEG per request: asked every `everyMs`; three failures in a row end it. */
    static CameraFrames snapshots(String url, PrinterHttp.ConnectionFactory factory, long everyMs, Listener listener) { return new CameraFrames(url, factory, listener, Math.max(200, everyMs)); }
    private CameraFrames(String url, PrinterHttp.ConnectionFactory factory, Listener listener, long everyMs) {
        if (everyMs > 0) { thread = new Thread(() -> poll(url, factory, listener, everyMs), "viewer-camera"); thread.setDaemon(true); thread.start(); return; }
        thread = new Thread(() -> {
            try {
                HttpURLConnection stream = factory.open(new URL(url)); connection = stream;
                if (closed) return;
                stream.setInstanceFollowRedirects(false); stream.setConnectTimeout(5000); stream.setReadTimeout(6000);
                if (stream.getResponseCode() != 200) throw new IOException("Camera HTTP " + stream.getResponseCode());
                String type = String.valueOf(stream.getContentType()).toLowerCase(java.util.Locale.ROOT);
                if (!type.contains("multipart/x-mixed-replace") && !type.contains("image/jpeg")) throw new IOException("Not a JPEG stream");
                try (InputStream input = new BufferedInputStream(stream.getInputStream(), 32768)) {
                    while (!closed) {
                        latest = JpegFrames.next(input);
                        if (announced.compareAndSet(false, true)) listener.frame();
                    }
                }
            } catch (Exception error) {
                if (!closed) listener.error("The camera stream stopped (" + (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()) + ").");
            } finally { HttpURLConnection stream = connection; if (stream != null) stream.disconnect(); connection = null; }
        }, "viewer-camera");
        thread.setDaemon(true);
        thread.start();
    }

    private void poll(String url, PrinterHttp.ConnectionFactory factory, Listener listener, long everyMs) {
        int failures = 0;
        while (!closed) {
            HttpURLConnection c = null;
            try {
                c = factory.open(new URL(url)); connection = c;
                c.setInstanceFollowRedirects(false); c.setConnectTimeout(5000); c.setReadTimeout(8000);
                if (c.getResponseCode() != 200) throw new IOException("Camera HTTP " + c.getResponseCode());
                try (InputStream in = c.getInputStream()) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[16384]; int n, total = 0;
                    while ((n = in.read(buffer)) > 0) { total += n; if (total > 8 * 1024 * 1024) throw new IOException("picture too large"); out.write(buffer, 0, n); }
                    byte[] jpeg = out.toByteArray();
                    if (jpeg.length < 4 || (jpeg[0] & 0xff) != 0xff || (jpeg[1] & 0xff) != 0xd8) throw new IOException("not a JPEG picture");
                    latest = jpeg; failures = 0;
                    if (announced.compareAndSet(false, true)) listener.frame();
                }
            } catch (Exception error) {
                if (closed) return;
                if (++failures >= 3) { listener.error("The camera stopped answering (" + (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()) + ")."); return; }
            } finally { if (c != null) c.disconnect(); connection = null; }
            try { Thread.sleep(everyMs); } catch (InterruptedException stop) { return; }
        }
    }

    /** The newest frame (or null), and permission to announce the next one. */
    /** The newest frame without taking it (null before the first). */
    byte[] latest() { return latest; }
    byte[] take() { byte[] frame = latest; announced.set(false); return frame; }

    @Override public void close() { closed = true; HttpURLConnection stream = connection; if (stream != null) stream.disconnect(); thread.interrupt(); }
}
