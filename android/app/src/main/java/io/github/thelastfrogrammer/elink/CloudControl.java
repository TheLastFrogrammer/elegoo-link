package io.github.thelastfrogrammer.elink;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;

/**
 * Printer commands through Elegoo's cloud, as the SDK's RtmService does: log in to Agora RTM with the identity Elegoo issues,
 * then send the printer's ordinary JSON request to the user channel "<userId><serial>" and wait for the reply with the same id.
 * One command at a time, never retried or replayed. The session closes after a short idle period because the identity is
 * shared with ElegooSlicer: logging in can sign another app's cloud control session out, and the reverse.
 */
public final class CloudControl implements AutoCloseable {
    /** Agora app ID from the SDK (src/cloud/services/rtm_service.cpp). */
    public static final String AGORA_APP_ID = "d035320941e34cd5bc4ec106eff05580";
    static final String OUTCOME_UNKNOWN = "The command may or may not have reached the printer; it was not repeated. Check the printer's status.";
    static final long REPLY_TIMEOUT_MS = 10_000, IDLE_CLOSE_MS = 120_000;

    public interface Link {
        interface Events {
            void message(String publisher, String text);
            /** The session ended from outside, for example the same identity logged in elsewhere. */
            void ended(String reason);
        }
        void login(String appId, String rtmUserId, String token, Events events) throws IOException;
        void subscribe(String channel) throws IOException;
        void publish(String channel, String text) throws IOException;
        void close();
    }
    public interface LinkFactory { Link create(); }
    public interface Credentials { CloudApi.AgoraCredential get() throws IOException; }
    public interface Reply {
        void done(boolean acknowledged, String message);
        /** Full reply; result is the printer's "result" object (empty on failure). */
        default void done(boolean acknowledged, String message, JSONObject result) { done(acknowledged, message); }
    }

    /** Progress the printer reports unasked while it fetches a cloud upload (method 6006). */
    public interface Transfers {
        void status(String serial, String task, int progress, int status);
        /** The cloud control session ended from outside while transfers were being watched; reports will not arrive any more. */
        default void ended(String reason) { }
    }

    private final Credentials credentials;
    private final LinkFactory factory;
    private final ScheduledExecutorService worker;
    private final long replyTimeoutMs, idleCloseMs;
    private Link link;
    private String userId = "";
    private String ended = "";
    private Pending pending, urgent;   // the command awaiting its reply; an emergency stop sent alongside it
    static final int MAX_WAITING = 3;
    static final long WAIT_MS = 15_000;
    private final java.util.concurrent.atomic.AtomicInteger inFlight = new java.util.concurrent.atomic.AtomicInteger();
    private final List<Waiting> waiting = new ArrayList<>();
    private static final class Waiting { final String serial; final JSONObject request; final Reply reply; ScheduledFuture<?> expiry; Waiting(String serial, JSONObject request, Reply reply) { this.serial = serial; this.request = request; this.reply = reply; } }
    private ScheduledFuture<?> idle;
    /** Methods arriving on the channel that answer nothing this app asked (for the read-only probe's report). */
    final PrinterProbe.Tally unasked = new PrinterProbe.Tally();
    private int nextId = 1;
    private Transfers transfers;
    /** Serial whose transfer is being watched; keeps the session open until cleared. */
    private String watching;

    private static final class Pending { int id, method; String publisher; Reply reply; ScheduledFuture<?> timeout; }

    /**
     * Least time between two commands put on the channel. The printer drops replies when three or more requests arrive in
     * quick succession and stays deaf for about five seconds (pycentauri's PROTOCOL.md, "rate limit"); the local connection
     * already keeps 2 s apart.
     */
    static final long SPACING_MS = 2_000;
    private long spacingMs, lastPublishAt;
    public CloudControl(Credentials credentials, LinkFactory factory, ScheduledExecutorService worker) {
        this(credentials, factory, worker, REPLY_TIMEOUT_MS, IDLE_CLOSE_MS);
        spacingMs = SPACING_MS;
    }
    /** Test hook: spacing between commands (0 in most tests). */
    CloudControl spacing(long ms) { spacingMs = ms; return this; }
    CloudControl(Credentials credentials, LinkFactory factory, ScheduledExecutorService worker, long replyTimeoutMs, long idleCloseMs) {
        this.credentials = credentials; this.factory = factory; this.worker = worker; this.replyTimeoutMs = replyTimeoutMs; this.idleCloseMs = idleCloseMs;
    }

    /**
     * Commands this app already sends on the local network. Elegoo's own printer page sends any printer command through the
     * same cloud channel (its "sendRtmMessage" bridge), so the cloud carries the same set; uploads and the camera address
     * stay local. Anything else is refused before it leaves the phone.
     */
    static boolean allowed(int method) {
        switch (method) {
            case Cc2Codec.ATTRIBUTES: case Cc2Codec.STATUS: case Cc2Codec.START: case Cc2Codec.PAUSE: case Cc2Codec.STOP: case Cc2Codec.RESUME:
            case Cc2Codec.TEMPERATURE: case Cc2Codec.LIGHT: case Cc2Codec.FAN: case Cc2Codec.SPEED: case Cc2Codec.HISTORY:
            case Cc2Codec.HISTORY_DETAIL: case Cc2Codec.FILES: case Cc2Codec.DELETE: case Cc2Codec.DISK: case Cc2Codec.CANVAS: case Cc2Codec.AUTO_REFILL: case Cc2Codec.THUMBNAIL:
            case Cc2Codec.FETCH: case Cc2Codec.FETCH_CANCEL:
                return true;
            default: return Cc2Codec.maintenance(method) || PrinterProbe.readOnly(method) && method != Cc2Codec.CAMERA;
        }
    }

    /**
     * Passes the printer's transfer reports for this serial to the listener and keeps the session open meanwhile; null
     * stops watching. Elegoo's SDK uploads the same way: progress arrives on the user channel, not as a reply.
     */
    public synchronized void watchTransfers(String serial, Transfers listener) { watching = listener == null ? null : serial; transfers = listener; }

    /** Last reason the session ended from outside, or "" if it did not. */
    public synchronized String endedReason() { return ended; }
    public synchronized boolean connected() { return link != null; }

    /** Sends one request built by the caller (Cc2Codec envelope); its id is replaced. Reply arrives on the worker thread. */
    public void send(String serial, JSONObject request, Reply reply) { dispatch(serial, request, reply, false); }
    /**
     * Elegoo's read-only "GetLivingVideoUrl" question (1042), asked once through the cloud when the user taps it: in cloud mode
     * the printer may need it before it joins its video channel. The only way 1042 leaves the phone through the cloud.
     */
    /** "answered: fields error_code, url; url is http on a private address, port 8080" (no address), or why it failed. */
    static String describeCameraAnswer(boolean acknowledged, String text, JSONObject result) {
        if (!acknowledged) return "no answer: " + Diagnostics.scrub(StatusPresentation.clean(text));
        StringBuilder out = new StringBuilder("answered" + (result.has("error_code") ? " (error_code " + result.optInt("error_code") + ")" : "") + ": fields " + CloudApi.shape(result));
        String url = result.optString("url", "");
        if (!url.isEmpty()) {
            try {
                java.net.URI uri = new java.net.URI(url);
                String host = uri.getHost() == null ? "" : uri.getHost();
                boolean privateIp = host.matches("^(10|127)\\..*|^192\\.168\\..*|^172\\.(1[6-9]|2\\d|3[01])\\..*");
                out.append("; url is ").append(uri.getScheme() == null ? "without a scheme" : uri.getScheme()).append(" on ")
                    .append(host.isEmpty() ? "no host" : privateIp ? "a private address" : host.matches("[\\d.]+") ? "a public address" : "a named host")
                    .append(uri.getPort() > 0 ? ", port " + uri.getPort() : "").append(uri.getPath() == null || uri.getPath().isEmpty() ? "" : ", path " + Diagnostics.scrub(uri.getPath()));
            } catch (Exception unreadable) { out.append("; url not readable (").append(url.length()).append(" characters)"); }
        }
        return out.toString();
    }
    public void askCamera(String serial, Reply reply) {
        try { dispatch(serial, Cc2Codec.request(0, Cc2Codec.CAMERA), reply, true); }
        catch (org.json.JSONException impossible) { reply.done(false, "Could not prepare the question."); }
    }
    private void dispatch(String serial, JSONObject request, Reply reply, boolean cameraQuestion) {
        inFlight.incrementAndGet();
        Reply counted = new Reply() {
            public void done(boolean acknowledged, String message) { inFlight.decrementAndGet(); reply.done(acknowledged, message); }
            public void done(boolean acknowledged, String message, JSONObject result) { inFlight.decrementAndGet(); reply.done(acknowledged, message, result); }
        };
        worker.execute(() -> {
            int method = request.optInt("method", -1);
            if (!allowed(method) && !(cameraQuestion && method == Cc2Codec.CAMERA)) { counted.done(false, "This command is not available through the cloud."); return; }
            if (!Cc2Discovery.validSerial(serial)) { counted.done(false, "Unknown printer serial."); return; }
            if (method == Cc2Codec.URGENT_STOP) { urgentStop(serial, request, counted); return; }
            synchronized (this) {
                if (pending != null || !waiting.isEmpty()) {
                    // Only a command that has NOT been sent may wait. Nothing already published is ever sent again.
                    if (waiting.size() >= MAX_WAITING) { counted.done(false, "Waiting for the previous cloud command to finish. This command was not sent."); return; }
                    Waiting entry = new Waiting(serial, request, counted);
                    entry.expiry = worker.schedule(() -> expire(entry), WAIT_MS, TimeUnit.MILLISECONDS);
                    waiting.add(entry);
                    return;
                }
            }
            dispatch(serial, request, counted, method, false);
        });
    }

    /** Commands accepted but not yet sent plus the one awaiting its reply: what the screens call "busy". */
    public int outstanding() { return inFlight.get(); }

    /**
     * Emergency stop is never held behind another command: changing commands still waiting (never published) are dropped, and
     * the stop goes out at the next moment the channel spacing allows, even while an earlier command awaits its reply. That
     * earlier command is not repeated or cancelled; its reply is still matched to it.
     */
    private void urgentStop(String serial, JSONObject request, Reply reply) {
        List<Waiting> dropped = new ArrayList<>();
        synchronized (this) {
            if (urgent != null || pending != null && pending.method == Cc2Codec.URGENT_STOP) { reply.done(false, "Emergency stop already sent. Waiting for the printer's reply."); return; }
            for (java.util.Iterator<Waiting> it = waiting.iterator(); it.hasNext(); ) {
                Waiting entry = it.next();
                if (Cc2Codec.changing(entry.request.optInt("method", -1))) { it.remove(); dropped.add(entry); }
            }
        }
        for (Waiting entry : dropped) { entry.expiry.cancel(false); entry.reply.done(false, "Cancelled for the emergency stop. This command was not sent."); }
        boolean alongside; synchronized (this) { alongside = pending != null; }
        dispatch(serial, request, reply, Cc2Codec.URGENT_STOP, alongside);
    }

    private void expire(Waiting entry) {
        synchronized (this) { if (!waiting.remove(entry)) return; }
        entry.reply.done(false, "The previous cloud command did not finish in time, so this command was not sent. Check the printer's status.");
    }

    /** Sends the next waiting command once nothing is pending. Worker thread. */
    private void drain() {
        Waiting next;
        synchronized (this) {
            if (pending != null || waiting.isEmpty()) return;
            next = waiting.remove(0);
        }
        next.expiry.cancel(false);
        dispatch(next.serial, next.request, next.reply, next.request.optInt("method", -1), false);
    }

    /** Fails every command that was waiting (never sent). Worker thread. */
    private void failWaiting(String why) {
        List<Waiting> dropped;
        synchronized (this) { dropped = new ArrayList<>(waiting); waiting.clear(); }
        for (Waiting entry : dropped) { entry.expiry.cancel(false); entry.reply.done(false, why + " This command was not sent."); }
    }

    /** Publishes one command; `asUrgent` tracks it beside a command still awaiting its reply (emergency stop only). */
    private void dispatch(String serial, JSONObject request, Reply reply, int method, boolean asUrgent) {
        {
            try {
                ensureConnected();
                JSONObject message = new JSONObject(request.toString());
                Pending wait = new Pending();
                long delay;
                synchronized (this) {
                    wait.id = nextId++; wait.method = method; wait.publisher = userId + serial; wait.reply = reply;
                    message.put("id", wait.id);
                    if (asUrgent) urgent = wait; else pending = wait;
                    // Kept apart from the previous command (see SPACING_MS); the reply wait starts when it actually goes out.
                    long now = System.currentTimeMillis();
                    delay = Math.max(0, lastPublishAt + spacingMs - now); lastPublishAt = now + delay;
                    // Slow commands (filament changes, homing) reply only when finished.
                    long timeout = Cc2Codec.timeoutSeconds(method) > 15 ? Math.max(replyTimeoutMs, Cc2Codec.timeoutSeconds(method) * 1000L) : replyTimeoutMs;
                    wait.timeout = worker.schedule(() -> finish(wait, false, "No reply through the cloud. Check the printer's status before trying again; the command was not repeated."), timeout + delay, TimeUnit.MILLISECONDS);
                }
                if (delay > 0) {
                    String text = message.toString();
                    worker.schedule(() -> {
                        synchronized (this) { if (pending != wait && urgent != wait) return; }   // ended meanwhile (link closed): never sent
                        try { link.publish(wait.publisher, text); }
                        catch (Exception error) {
                            String why = error instanceof IOException && error.getMessage() != null ? error.getMessage() : "Could not reach the printer through the cloud.";
                            finish(wait, false, Cc2Codec.changing(method) ? why + " " + OUTCOME_UNKNOWN : why); closeLink();
                        }
                    }, delay, TimeUnit.MILLISECONDS);
                } else link.publish(wait.publisher, message.toString());
            } catch (Exception error) {
                Pending failed; synchronized (this) { failed = asUrgent ? urgent : pending; }
                String text = error instanceof IOException && error.getMessage() != null ? error.getMessage() : "Could not reach the printer through the cloud.";
                if (failed != null && Cc2Codec.changing(method)) text += " " + OUTCOME_UNKNOWN;
                if (failed != null) finish(failed, false, text); else { reply.done(false, text); failWaiting(text); }
                closeLink();
            }
            scheduleIdleClose();
        }
    }

    private void ensureConnected() throws IOException {
        synchronized (this) { if (link != null) return; }
        CloudApi.AgoraCredential credential = credentials.get();
        Link created = factory.create();
        synchronized (this) { ended = ""; userId = credential.userId; }
        try {
            created.login(AGORA_APP_ID, credential.rtmUserId, credential.rtmToken, new Link.Events() {
                public void message(String publisher, String text) { worker.execute(() -> received(publisher, text)); }
                public void ended(String reason) { worker.execute(() -> endedOutside(created, reason)); }
            });
            created.subscribe(credential.userId);
        } catch (IOException error) { created.close(); throw error; }
        synchronized (this) { link = created; }
    }

    private volatile long listenUntil;
    /** Connects and stays connected for `ms` only listening (nothing is sent), to hear what the printer reports unasked; `result` gets "" or why not. */
    public void listen(long ms, java.util.function.Consumer<String> result) {
        worker.execute(() -> {
            try { ensureConnected(); listenUntil = System.currentTimeMillis() + ms; scheduleIdleClose(); Diagnostics.note(Diagnostics.HYBRID, "listening on the cloud channel for " + ms / 60_000 + " min (nothing sent)"); result.accept(""); }
            catch (IOException error) { result.accept(String.valueOf(error.getMessage())); }
        });
    }
    private final java.util.Set<Integer> shapesLogged = new java.util.HashSet<>();
    /**
     * A message the printer sent without being asked (for example while it fetches a file another app, such as Elegoo's,
     * sent through the cloud). Its structure is logged once per method, values masked; a field that plainly names a G-code in
     * Elegoo's storage is kept for fetching that file later (CloudFileRoute). Nothing is sent and nothing is guessed.
     */
    private void heard(String serial, int method, JSONObject message) {
        List<JSONObject> parts = new ArrayList<>(); collect(message, parts, 0);
        if (shapesLogged.add(method)) {
            StringBuilder shape = new StringBuilder();
            for (JSONObject part : parts) { String p = CloudApi.patterns(part); if (!p.equals("none")) shape.append(shape.length() > 0 ? " | " : "").append(p); }
            Diagnostics.note(Diagnostics.HYBRID, "the printer sent method " + method + " unasked through the cloud: " + (shape.length() == 0 ? "no text fields" : shape));
        }
        for (JSONObject part : parts) {
            CloudApi.Reference reference = CloudApi.gcodeReference(part);
            if (reference != null && CloudFileRoute.heard(serial, reference)) Diagnostics.note(Diagnostics.FILES, "the printer named a G-code in Elegoo's storage (method " + method + "); kept for the live toolpath");
        }
    }
    private static void collect(Object value, List<JSONObject> out, int depth) {
        if (depth > 4 || out.size() > 20) return;
        if (value instanceof JSONObject) { JSONObject o = (JSONObject) value; out.add(o); for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) collect(o.opt(it.next()), out, depth + 1); }
        else if (value instanceof org.json.JSONArray) { org.json.JSONArray a = (org.json.JSONArray) value; for (int i = 0; i < a.length() && i < 10; i++) collect(a.opt(i), out, depth + 1); }
        else if (value instanceof String && ((String) value).startsWith("{")) { try { collect(new JSONObject((String) value), out, depth + 1); } catch (Exception notJson) { } }
    }
    private void received(String publisher, String text) {
        JSONObject message;
        try { message = new JSONObject(text); } catch (Exception error) { return; }
        Transfers listener; String serial;
        synchronized (this) { listener = transfers; serial = watching; }
        if (listener != null && serial != null && publisher.equals(userId + serial) && message.optInt("method", -1) == Cc2Codec.FETCH_STATUS) {
            JSONObject result = message.optJSONObject("result");
            if (result != null) listener.status(serial, result.optString("taskID", ""), Math.max(0, Math.min(100, result.optInt("progress", 0))), result.optInt("status", 0));
            return;
        }
        Pending wait;
        synchronized (this) { wait = answers(pending, publisher, message) ? pending : answers(urgent, publisher, message) ? urgent : null; }
        if (wait == null) {
            int method = message.optInt("method", -1);
            unasked.saw("cloud", method);
            String uid; synchronized (this) { uid = userId; }
            if (uid != null && publisher.startsWith(uid) && publisher.length() > uid.length()) heard(publisher.substring(uid.length()), method, message);
            return;
        }
        JSONObject result = message.optJSONObject("result");
        if (result == null) { finish(wait, false, "The printer's reply through the cloud carried no result." + (Cc2Codec.changing(wait.method) ? " " + OUTCOME_UNKNOWN : "")); return; }
        // A changing command needs the printer to say it succeeded (error_code 0), as on the local network; a read may answer
        // with its data alone.
        boolean changing = Cc2Codec.changing(wait.method);
        int code = result.has("error_code") ? result.optInt("error_code", -1) : changing ? -1 : 0;
        if (code == 0) finish(wait, true, "Printer acknowledged through the cloud. Waiting for its status to update.", result);
        else if (!result.has("error_code")) finish(wait, false, "The printer's reply through the cloud did not say whether it worked." + (changing ? " " + OUTCOME_UNKNOWN : ""));
        else finish(wait, false, PrinterErrors.code(code));
    }
    /** Whether a message is the reply to this command: same printer, same id and same method. */
    private static boolean answers(Pending wait, String publisher, JSONObject message) {
        return wait != null && wait.publisher.equals(publisher) && message.optInt("id", -1) == wait.id && message.optInt("method", -1) == wait.method;
    }

    private void finish(Pending wait, boolean acknowledged, String text) { finish(wait, acknowledged, text, new JSONObject()); }
    private void finish(Pending wait, boolean acknowledged, String text, JSONObject result) {
        synchronized (this) { if (pending == wait) pending = null; else if (urgent == wait) urgent = null; else return; }
        if (wait.timeout != null) wait.timeout.cancel(false);
        wait.reply.done(acknowledged, text, result);
        worker.execute(this::drain);
    }

    private void endedOutside(Link which, String reason) {
        synchronized (this) { if (link != which) return; ended = reason; }
        Pending wait, stop; Transfers watcher; synchronized (this) { wait = pending; stop = urgent; watcher = watching == null ? null : transfers; }
        if (wait != null) finish(wait, false, Cc2Codec.changing(wait.method) ? reason + " " + OUTCOME_UNKNOWN : reason);
        if (stop != null) finish(stop, false, reason + " " + OUTCOME_UNKNOWN);
        failWaiting(reason);
        closeLink();
        if (watcher != null) watcher.ended(reason);
    }

    private void scheduleIdleClose() {
        synchronized (this) {
            if (idle != null) idle.cancel(false);
            idle = worker.schedule(() -> { synchronized (CloudControl.this) { if (pending != null || urgent != null || watching != null || System.currentTimeMillis() < listenUntil) { scheduleIdleClose(); return; } } closeLink(); }, idleCloseMs, TimeUnit.MILLISECONDS);
        }
    }

    private void closeLink() {
        Link old;
        synchronized (this) { old = link; link = null; }
        if (old != null) old.close();
    }

    @Override public void close() {
        worker.execute(() -> {
            Pending wait, stop; synchronized (this) { wait = pending; stop = urgent; if (idle != null) idle.cancel(false); }
            if (wait != null) finish(wait, false, "Cloud controls closed.");
            if (stop != null) finish(stop, false, "Cloud controls closed. " + OUTCOME_UNKNOWN);
            failWaiting("Cloud controls closed.");
            closeLink();
        });
    }
}
