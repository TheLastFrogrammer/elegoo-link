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

    private final Credentials credentials;
    private final LinkFactory factory;
    private final ScheduledExecutorService worker;
    private final long replyTimeoutMs, idleCloseMs;
    private Link link;
    private String userId = "";
    private String ended = "";
    private Pending pending;
    private ScheduledFuture<?> idle;
    private int nextId = 1;

    private static final class Pending { int id; String publisher; Reply reply; ScheduledFuture<?> timeout; }

    public CloudControl(Credentials credentials, LinkFactory factory, ScheduledExecutorService worker) {
        this(credentials, factory, worker, REPLY_TIMEOUT_MS, IDLE_CLOSE_MS);
    }
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
            case Cc2Codec.FILES: case Cc2Codec.DELETE: case Cc2Codec.DISK: case Cc2Codec.CANVAS: case Cc2Codec.AUTO_REFILL:
                return true;
            default: return false;
        }
    }

    /** Last reason the session ended from outside, or "" if it did not. */
    public synchronized String endedReason() { return ended; }
    public synchronized boolean connected() { return link != null; }

    /** Sends one request built by the caller (Cc2Codec envelope); its id is replaced. Reply arrives on the worker thread. */
    public void send(String serial, JSONObject request, Reply reply) {
        worker.execute(() -> {
            int method = request.optInt("method", -1);
            if (!allowed(method)) { reply.done(false, "This command is not available through the cloud."); return; }
            if (!Cc2Discovery.validSerial(serial)) { reply.done(false, "Unknown printer serial."); return; }
            synchronized (this) {
                if (pending != null) { reply.done(false, "Waiting for the previous cloud command to finish."); return; }
            }
            try {
                ensureConnected();
                JSONObject message = new JSONObject(request.toString());
                Pending wait = new Pending();
                synchronized (this) {
                    wait.id = nextId++; wait.publisher = userId + serial; wait.reply = reply;
                    message.put("id", wait.id);
                    pending = wait;
                    wait.timeout = worker.schedule(() -> finish(wait, false, "No reply through the cloud. Check the printer's status before trying again; the command was not repeated."), replyTimeoutMs, TimeUnit.MILLISECONDS);
                }
                link.publish(wait.publisher, message.toString());
            } catch (Exception error) {
                Pending failed; synchronized (this) { failed = pending; }
                String text = error instanceof IOException && error.getMessage() != null ? error.getMessage() : "Could not reach the printer through the cloud.";
                if (failed != null) finish(failed, false, text); else reply.done(false, text);
                closeLink();
            }
            scheduleIdleClose();
        });
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

    private void received(String publisher, String text) {
        Pending wait;
        synchronized (this) { wait = pending; }
        if (wait == null || !wait.publisher.equals(publisher)) return;
        JSONObject message;
        try { message = new JSONObject(text); } catch (Exception error) { return; }
        if (message.optInt("id", -1) != wait.id) return;
        JSONObject result = message.optJSONObject("result");
        int code = result == null ? -1 : result.optInt("error_code", 0);
        if (code == 0) finish(wait, true, "Printer acknowledged through the cloud. Waiting for its status to update.", result);
        else finish(wait, false, PrinterErrors.code(code));
    }

    private void finish(Pending wait, boolean acknowledged, String text) { finish(wait, acknowledged, text, new JSONObject()); }
    private void finish(Pending wait, boolean acknowledged, String text, JSONObject result) {
        synchronized (this) { if (pending != wait) return; pending = null; }
        if (wait.timeout != null) wait.timeout.cancel(false);
        wait.reply.done(acknowledged, text, result);
    }

    private void endedOutside(Link which, String reason) {
        synchronized (this) { if (link != which) return; ended = reason; }
        Pending wait; synchronized (this) { wait = pending; }
        if (wait != null) finish(wait, false, reason);
        closeLink();
    }

    private void scheduleIdleClose() {
        synchronized (this) {
            if (idle != null) idle.cancel(false);
            idle = worker.schedule(() -> { synchronized (CloudControl.this) { if (pending != null) { scheduleIdleClose(); return; } } closeLink(); }, idleCloseMs, TimeUnit.MILLISECONDS);
        }
    }

    private void closeLink() {
        Link old;
        synchronized (this) { old = link; link = null; }
        if (old != null) old.close();
    }

    @Override public void close() {
        worker.execute(() -> {
            Pending wait; synchronized (this) { wait = pending; if (idle != null) idle.cancel(false); }
            if (wait != null) finish(wait, false, "Cloud controls closed.");
            closeLink();
        });
    }
}
