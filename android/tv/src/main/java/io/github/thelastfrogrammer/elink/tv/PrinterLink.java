package io.github.thelastfrogrammer.elink.tv;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The TV following the phone while the watch screen is open: the event stream (the status whenever it changes, print
 * events) and one camera stream, each on its own thread. When the phone cannot be reached or ends a stream, each waits
 * longer before trying again (2 s doubling to a minute), so a TV left on never hammers the phone; once the events come
 * back the camera tries at once. A refused token (401) ends the link: the TV pairs again. Listener calls come from the
 * link's threads. Pure Java.
 */
final class PrinterLink {
    static final int FPS = 5, EVENTS_READ_MS = 40_000, CAMERA_READ_MS = 30_000;
    /** A connection that stayed up this long starts the waits from the beginning again. */
    static final long STEADY_MS = 30_000, BUSY_MS = 10_000, NUDGE_GAP_MS = 5_000;

    enum State { CONNECTING, LIVE, WAITING }
    /** Why the link waits: the phone did not answer (or ended the stream), was busy, or refused with its own words. */
    static final String UNREACHABLE = "unreachable", BUSY = "busy", REFUSED = "refused";

    interface Listener {
        void link(State state, long retryAtMs, String why, String words);
        void status(JSONObject status);
        void event(JSONObject event);
        void cameras(List<PhoneApi.Camera> cameras);
        void frame(String camera, byte[] jpeg);
        /** The camera is being opened (retryAtMs 0), or waits until retryAtMs; words are the phone's, if it said why. */
        void camera(String camera, long retryAtMs, String words);
        void unpaired();
    }

    private final PhoneApi api;
    private final String token;
    private final Listener listener;
    private final Backoff eventsWait, cameraWait;
    private final Semaphore eventsWake = new Semaphore(0), cameraWake = new Semaphore(0);
    private final AtomicBoolean unpaired = new AtomicBoolean();
    private volatile boolean running;
    private boolean started;
    private volatile String wanted;
    private volatile HttpURLConnection eventsStream, cameraStream;
    private long nudgedAt;

    PrinterLink(PhoneApi api, String token, Listener listener) { this(api, token, listener, 2_000, 60_000); }
    PrinterLink(PhoneApi api, String token, Listener listener, long firstWaitMs, long maxWaitMs) {
        this.api = api; this.token = token; this.listener = listener;
        Random random = new Random();
        eventsWait = new Backoff(firstWaitMs, maxWaitMs, random); cameraWait = new Backoff(firstWaitMs * 3 / 2, maxWaitMs, random);
    }

    /** Once: a stopped link stays stopped (the screen makes a new one), so a stop and start can never leave two running. */
    synchronized void start() {
        if (started) return;
        started = true; running = true;
        thread(this::events, "tv-events"); thread(this::camera, "tv-camera");
    }
    synchronized void stop() {
        running = false;
        disconnect(eventsStream); disconnect(cameraStream);
        eventsWake.release(); cameraWake.release();
    }
    /** Shows this camera now (switching streams), or none (null). */
    void camera(String id) {
        if (id == null ? wanted == null : id.equals(wanted)) return;
        wanted = id; cameraWait.reset();
        disconnect(cameraStream); wake(cameraWake);
    }
    /** The network came back: try at once instead of waiting out the pause (at most every 5 s). */
    synchronized void retryNow() {
        long now = System.currentTimeMillis();
        if (now - nudgedAt < NUDGE_GAP_MS) return;
        nudgedAt = now; eventsWait.reset(); cameraWait.reset(); wake(eventsWake); wake(cameraWake);
    }
    boolean running() { return running; }

    // ------------------------------------------------------------------ the event stream

    private void events() {
        while (running) {
            long openedAt = 0; String why = UNREACHABLE, words = "";
            try {
                listener.link(State.CONNECTING, 0, "", "");
                List<PhoneApi.Camera> cameras = api.cameras(token);
                if (!running) return;
                listener.cameras(cameras);
                HttpURLConnection c = api.stream("/api/v1/events", token, EVENTS_READ_MS);
                eventsStream = c;
                try {
                    if (!running) return;
                    openedAt = System.currentTimeMillis();
                    SseParser sse = new SseParser(new BufferedInputStream(c.getInputStream(), 16 * 1024));
                    boolean[] live = {false};
                    while (running) sse.next((event, data) -> {
                        JSONObject json;
                        try { json = new JSONObject(data); } catch (JSONException unreadable) { return; }   // skip one bad message, keep the stream
                        if ("status".equals(event)) {
                            listener.status(json);
                            if (!live[0]) { live[0] = true; listener.link(State.LIVE, 0, "", ""); cameraWait.reset(); wake(cameraWake); }
                        } else if ("event".equals(event)) listener.event(json);
                    });
                } finally { disconnect(c); eventsStream = null; }
            } catch (PhoneApi.Unpaired refused) { unpaired(); return; }
            catch (PhoneApi.Refused r) {
                why = r.code == 503 || r.code == 429 ? BUSY : REFUSED; words = r.getMessage();
                if (why.equals(BUSY)) eventsWait.atLeast(BUSY_MS);   // too many screens already: give the phone time
            }
            catch (IOException | RuntimeException lost) { why = UNREACHABLE; }
            if (!running) return;
            if (openedAt > 0 && System.currentTimeMillis() - openedAt > STEADY_MS) eventsWait.reset();
            long wait = eventsWait.next();
            listener.link(State.WAITING, System.currentTimeMillis() + wait, why, words);
            pause(eventsWake, wait);
        }
    }

    // ------------------------------------------------------------------ the camera stream

    private void camera() {
        while (running) {
            String id = wanted;
            if (id == null) { pause(cameraWake, 60_000); continue; }
            long openedAt = 0; String words = "";
            try {
                listener.camera(id, 0, "");
                HttpURLConnection c = api.stream(PhoneApi.cameraPath(id, FPS), token, CAMERA_READ_MS);
                cameraStream = c;
                try {
                    if (!running || !id.equals(wanted)) continue;
                    openedAt = System.currentTimeMillis();
                    MjpegReader pictures = new MjpegReader(c.getInputStream());
                    while (running && id.equals(wanted)) { byte[] jpeg = pictures.next(); if (running && id.equals(wanted)) listener.frame(id, jpeg); }
                    continue;   // switched to another camera, or stopped
                } finally { disconnect(c); cameraStream = null; }
            } catch (PhoneApi.Unpaired refused) { unpaired(); return; }
            catch (PhoneApi.Refused r) {
                words = r.code == 404 ? "The phone no longer shares this camera." : r.getMessage();
                cameraWait.atLeast(BUSY_MS);   // no picture yet, or too many streams: give the phone time
            } catch (IOException | RuntimeException lost) { words = ""; }
            if (!running) return;
            if (!id.equals(wanted)) continue;   // switched while it failed: open the new one now
            if (openedAt > 0 && System.currentTimeMillis() - openedAt > STEADY_MS) cameraWait.reset();
            long wait = cameraWait.next();
            listener.camera(id, System.currentTimeMillis() + wait, words);
            pause(cameraWake, wait);
        }
    }

    // ------------------------------------------------------------------ plumbing

    private void unpaired() {
        running = false;
        disconnect(eventsStream); disconnect(cameraStream); wake(eventsWake); wake(cameraWake);
        if (unpaired.compareAndSet(false, true)) listener.unpaired();
    }
    private void pause(Semaphore wake, long ms) {
        try { wake.tryAcquire(ms, TimeUnit.MILLISECONDS); } catch (InterruptedException stop) { Thread.currentThread().interrupt(); running = false; }
        wake.drainPermits();
    }
    private static void wake(Semaphore s) { if (s.availablePermits() == 0) s.release(); }
    private static void disconnect(HttpURLConnection c) { if (c != null) try { c.disconnect(); } catch (RuntimeException ignored) { } }
    private static void thread(Runnable r, String name) { Thread t = new Thread(r, name); t.setDaemon(true); t.start(); }
}
