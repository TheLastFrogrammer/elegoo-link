package io.github.thelastfrogrammer.elink.tv;

import java.io.IOException;
import java.util.Random;
import org.json.JSONObject;

/**
 * One request to pair: asks the phone, hands over the 4-digit code to show, then polls every 2 s (the phone's limit) until
 * the person approves or refuses on the phone, or the request runs out (5 minutes). When the phone stops answering, the
 * polls slow down instead of piling up. Runs on its own thread; the listener is called there. Pure Java.
 */
final class PairingSession {
    static final long POLL_MS = 2_000, BUSY_RETRY_MS = 2_500, GRACE_MS = 5_000;

    interface Listener {
        void asking();
        /** Show this code; it is good until expiresAtMs (the TV's clock). */
        void code(String code, long expiresAtMs);
        /** Still waiting; reachable is false while the phone does not answer. */
        void waiting(boolean reachable);
        void approved(String token);
        void refused();
        void expired();
        /** Could not ask: busy (too many waiting on the phone), or not reached (words say why). */
        void failed(boolean busy, String words);
    }

    private final PhoneApi api;
    private final String name;
    private final long pollMs;
    private final Listener listener;
    private volatile boolean cancelled;
    private Thread thread;

    PairingSession(PhoneApi api, String name, long pollMs, Listener listener) { this.api = api; this.name = name; this.pollMs = pollMs; this.listener = listener; }

    void start() { thread = new Thread(this::run, "tv-pairing"); thread.setDaemon(true); thread.start(); }
    void cancel() { cancelled = true; if (thread != null) thread.interrupt(); }

    private void run() {
        listener.asking();
        JSONObject asked = null;
        for (int attempt = 0; asked == null; attempt++) {
            try { asked = api.pair(name); }
            catch (PhoneApi.Refused r) {
                // 429: asked within 2 s of the last request (Ask again pressed quickly), or too many waiting on the phone.
                // One quiet retry covers the first; for the second the person decides.
                if (r.code == 429 && attempt == 0) { if (!pause(BUSY_RETRY_MS)) return; continue; }
                if (!cancelled) listener.failed(r.code == 429, r.code == 429 ? r.getMessage() : "The phone answered, but could not pair: " + r.getMessage());
                return;
            } catch (IOException unreachable) { if (!cancelled) listener.failed(false, ""); return; }
        }
        if (cancelled) return;
        long until = System.currentTimeMillis() + Math.max(10, asked.optLong("expires_in", 300)) * 1000;
        String poll = asked.optString("poll");
        listener.code(asked.optString("code"), until);
        Backoff trouble = new Backoff(pollMs * 2, 15_000, new Random());
        boolean reachable = true;
        while (!cancelled) {
            if (!pause(reachable ? pollMs : trouble.next())) return;
            JSONObject state;
            try { state = api.poll(poll); }
            catch (IOException e) {
                if (reachable) { reachable = false; listener.waiting(false); }
                // The phone forgets the request when it runs out; without an answer the TV can only go by its clock.
                if (System.currentTimeMillis() > until + GRACE_MS) { listener.expired(); return; }
                continue;
            }
            if (cancelled) return;
            if (state == null) { listener.expired(); return; }
            String s = state.optString("state");
            if (s.equals("approved") && !state.optString("token").isEmpty()) { listener.approved(state.optString("token")); return; }
            if (s.equals("denied")) { listener.refused(); return; }
            if (!reachable) { reachable = true; trouble.reset(); listener.waiting(true); }
        }
    }

    /** Waits; false when cancelled meanwhile. */
    private boolean pause(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException stop) { Thread.currentThread().interrupt(); return false; }
        return !cancelled;
    }
}
