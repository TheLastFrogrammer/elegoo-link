package io.github.thelastfrogrammer.elink;

import java.io.*;
import java.net.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reads the printer's MJPEG stream and keeps only the newest JPEG, for the toolpath viewer's in-scene camera. The page asks
 * for a frame only after it has shown the previous one, so frames never queue. Independent of Android.
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

    CameraFrames(String url, PrinterHttp.ConnectionFactory factory, Listener listener) {
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
                if (!closed) listener.error("The printer's camera stream stopped (" + (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()) + ").");
            } finally { HttpURLConnection stream = connection; if (stream != null) stream.disconnect(); connection = null; }
        }, "viewer-camera");
        thread.setDaemon(true);
        thread.start();
    }

    /** The newest frame (or null), and permission to announce the next one. */
    byte[] take() { byte[] frame = latest; announced.set(false); return frame; }

    @Override public void close() { closed = true; HttpURLConnection stream = connection; if (stream != null) stream.disconnect(); thread.interrupt(); }
}
