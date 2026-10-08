package io.github.thelastfrogrammer.elink;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;

/**
 * Sends one G-code file to a printer through Elegoo's cloud, as the SDK's CloudService::uploadFile does: put the file in
 * Elegoo's storage (first half of the progress), cancel any earlier transfer to the printer, then ask the printer to fetch
 * the file (method 1057) and follow its reports (method 6006, second half). The transfer task is the printer's serial, as
 * in the SDK. Nothing is repeated: a stalled or failed transfer ends the upload with a message. Independent of Android.
 */
final class CloudUpload {
    static final long STALL_MS = 60_000, SETTLE_MS = 1_000;

    interface Commands {
        void send(JSONObject request, CloudControl.Reply reply);
        /** Starts (listener) or stops (null) receiving the printer's transfer reports. */
        void watch(CloudControl.Transfers listener);
        /** The cloud control session was ended from outside (another login); sending would log in again and take the identity back. */
        default boolean linkEnded() { return false; }
    }
    interface Listener {
        void progress(int percent);
        /** The printer reported the file fetched: where it lives in Elegoo's storage (object name only, never the link). Scheduler thread, before finished(true). */
        default void stored(String objectName) { }
        /** Called once, on the scheduler thread. */
        void finished(boolean done, String message);
    }

    private final CloudApi api;
    private final CloudApi.Uploader uploader;
    private final Commands commands;
    /** Kept for the callers; the storage PUT runs on its own thread (see start()), never on a shared executor. */
    private final Executor blocking;
    private final ScheduledExecutorService scheduler;
    private final String serial, name;
    private final File file;
    private final Listener listener;
    private final long stallMs, settleMs;
    private volatile boolean cancelled, ended, fetching, putting;
    private volatile long lastBytesAt;
    private volatile String storedObject = "";
    private ScheduledFuture<?> putWatchdog;
    private ScheduledFuture<?> stall;
    private int lastPercent = -1;

    CloudUpload(CloudApi api, CloudApi.Uploader uploader, Commands commands, Executor blocking, ScheduledExecutorService scheduler,
                String serial, File file, String name, Listener listener) {
        this(api, uploader, commands, blocking, scheduler, serial, file, name, listener, STALL_MS, SETTLE_MS);
    }
    CloudUpload(CloudApi api, CloudApi.Uploader uploader, Commands commands, Executor blocking, ScheduledExecutorService scheduler,
                String serial, File file, String name, Listener listener, long stallMs, long settleMs) {
        this.api = api; this.uploader = uploader; this.commands = commands; this.blocking = blocking; this.scheduler = scheduler;
        this.serial = serial; this.file = file; this.name = name; this.listener = listener; this.stallMs = stallMs; this.settleMs = settleMs;
    }

    void start() {
        try {
            Cc2Codec.filename(name);
            if (!Cc2Discovery.validSerial(serial)) throw new IllegalArgumentException("Unknown printer serial.");
        } catch (IllegalArgumentException invalid) { scheduler.execute(() -> finish(false, invalid.getMessage())); return; }
        if (!file.isFile() || file.length() == 0) { scheduler.execute(() -> finish(false, "The file to upload is missing or empty.")); return; }
        if (file.length() > CloudApi.UPLOAD_LIMIT) { scheduler.execute(() -> finish(false, "Files of 500 MB or more cannot be sent through the cloud yet. Use the local connection.")); return; }
        // The PUT can block for minutes in a socket write; it gets its own thread so the shared file executor is never held by it.
        Thread thread = new Thread(this::store, "cloud-upload"); thread.setDaemon(true); thread.start();
    }

    /** Stops the upload; once the printer is fetching, its transfer is cancelled too. */
    void cancel() {
        cancelled = true;
        uploader.abort(); // ends a write that is stuck; the PUT thread then unwinds on its own
        scheduler.execute(() -> {
            if (ended) return;
            if (fetching) cancelFetch(() -> { });
            finish(false, "Upload cancelled.");
        });
    }

    private void store() {
        String failure = null, md5 = "", access = "";
        try {
            String[] sums = CloudApi.md5(file); md5 = sums[0];
            CloudApi.UploadTarget target = api.uploadTarget(CloudApi.storageName(api.account().userId, serial, md5, name), sums[1]);
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Content-Type", "application/octet-stream");
            headers.put("Content-MD5", sums[1]);
            lastBytesAt = System.nanoTime(); putting = true; startPutWatchdog();
            int status = uploader.put(target.uploadUrl, headers, file, percent -> { lastBytesAt = System.nanoTime(); report(percent / 2); return !cancelled; });
            Diagnostics.note(Diagnostics.FILES, "cloud upload: storage answered HTTP " + status);
            if (status < 200 || status >= 300) failure = "Elegoo's storage refused the file (HTTP " + status + ").";
            access = target.accessUrl; storedObject = target.objectName;
        } catch (InterruptedIOException stopped) { failure = cancelled || !(stopped instanceof java.net.SocketTimeoutException) ? "Upload cancelled." : FriendlyErrors.message(stopped, "Sending the file to Elegoo", "Upload timed out.");
        } catch (IOException error) { failure = FriendlyErrors.message(error, "Sending the file to Elegoo", "Could not upload the file to Elegoo."); }
        putting = false;
        String problem = failure, checksum = md5, url = access;
        scheduler.execute(() -> {
            if (ended) return;
            if (problem != null) { finish(false, problem); return; }
            if (cancelled) { finish(false, "Upload cancelled."); return; }
            // As the SDK: clear any earlier transfer (a refusal only means there was none), wait a moment, then fetch.
            cancelFetch(() -> scheduler.schedule(() -> fetch(url, checksum), settleMs, TimeUnit.MILLISECONDS));
        });
    }

    private void cancelFetch(Runnable then) {
        try { commands.send(Cc2Codec.fetchCancelRequest(0, serial), (acknowledged, message) -> { Diagnostics.note(Diagnostics.FILES, "cloud upload: clear earlier transfer (1058) " + (acknowledged ? "acknowledged" : "not acknowledged: " + message)); scheduler.execute(then); }); }
        catch (Exception error) { scheduler.execute(then); }
    }

    private void fetch(String url, String md5) {
        if (ended) return;
        if (cancelled) { finish(false, "Upload cancelled."); return; }
        JSONObject request;
        try { request = Cc2Codec.fetchRequest(0, name, url, md5, serial); }
        catch (Exception error) { finish(false, error.getMessage() == null ? "Could not prepare the transfer." : error.getMessage()); return; }
        fetching = true;
        commands.watch(new CloudControl.Transfers() {
            public void status(String who, String task, int progress, int status) { scheduler.execute(() -> transfer(task, progress, status)); }
            public void ended(String reason) { scheduler.execute(() -> finish(false, reason + " The transfer of " + name + " was not cancelled; refresh Files in a minute to see whether it arrived.")); }
        });
        restartStall();
        commands.send(request, (acknowledged, message) -> scheduler.execute(() -> {
            Diagnostics.note(Diagnostics.FILES, "cloud upload: fetch request (1057) " + (acknowledged ? "acknowledged" : "refused: " + message));
            if (!acknowledged) finish(false, "The printer did not accept the transfer: " + message);
        }));
    }

    private void transfer(String task, int progress, int status) {
        if (ended || !task.isEmpty() && !task.equals(serial)) return;
        if (status != 0 || progress == 0 || progress == 100) Diagnostics.note(Diagnostics.FILES, "cloud upload: printer reports " + progress + "% status " + status);
        restartStall();
        if (status == 1) { report(100); if (!ended && !storedObject.isEmpty()) listener.stored(storedObject); finish(true, "Uploaded " + name + " through the Elegoo cloud."); }
        else if (status == 2) finish(false, "The printer cancelled the transfer of " + name + ".");
        else if (status == 3) finish(false, "The printer could not fetch " + name + " from Elegoo. Check its storage space and try again.");
        else report(50 + progress / 2);
    }

    /** No bytes written for the stall time: the connection is closed and the upload ends with a plain message. */
    private void startPutWatchdog() {
        long interval = Math.max(50, Math.min(1_000, stallMs / 4));
        synchronized (this) {
            putWatchdog = scheduler.scheduleWithFixedDelay(() -> {
                if (ended || !putting) return;
                if ((System.nanoTime() - lastBytesAt) / 1_000_000 < stallMs) return;
                putting = false; uploader.abort();
                finish(false, "Elegoo's storage stopped accepting " + name + ". Check the Wi-Fi and try again.");
            }, interval, interval, TimeUnit.MILLISECONDS);
        }
    }

    private void restartStall() {
        if (stall != null) stall.cancel(false);
        stall = scheduler.schedule(() -> {
            if (ended) return;
            boolean cancelling = !commands.linkEnded(); // never log in again just to cancel: that takes the identity back from the other app
            if (cancelling) cancelFetch(() -> { });
            finish(false, "The printer stopped reporting the transfer of " + name + " for a minute" + (cancelling ? ", so it was cancelled. Refresh Files before trying again; the file is unlikely to be there." : ". It was not cancelled; refresh Files to see whether it arrived."));
        }, stallMs, TimeUnit.MILLISECONDS);
    }

    private synchronized void report(int percent) {
        if (percent == lastPercent || ended) return;
        lastPercent = percent; listener.progress(percent);
    }

    private void finish(boolean done, String message) {
        if (ended) return;
        ended = true; putting = false;
        if (stall != null) stall.cancel(false);
        synchronized (this) { if (putWatchdog != null) putWatchdog.cancel(false); }
        if (fetching) commands.watch(null);
        listener.finished(done, message);
    }
}
