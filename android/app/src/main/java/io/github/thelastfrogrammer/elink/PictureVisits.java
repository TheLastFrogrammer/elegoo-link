package io.github.thelastfrogrammer.elink;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.ViewGroup;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Cloud picture visits: every 30 s, in cloud mode, when the running print's picture is due and no camera view is open, joins
 * the printer's video channel unseen, keeps one picture and leaves (at most 35 s). Nothing is sent to the printer; it joins
 * its channel on its own when a viewer does. After two empty channels in a row it waits 30 minutes. The fallback for when
 * the native library (CloudFrameGrab, from the service) cannot take them: this video needs a window to decode in, so it uses
 * whichever screen of this app is showing (Host). Settings › Recording & alerts turns cloud pictures off ("cloudPictureVisits").
 */
final class PictureVisits {
    static final String PREF = "cloudPictureVisits";
    /** Where a visit's video goes: a container while it runs (null: nowhere to show it now, see why()), given back afterwards. */
    interface Host {
        ViewGroup attach();
        void detach(ViewGroup box);
        /** Why there is no window now, for the camera log ("" when there is one). */
        String why();
    }
    private final Supplier<PrinterService> printer; private final SharedPreferences settings; private final Host host;
    /** True while another view holds the channel (the Print tab's video): a visit then waits. */
    private final BooleanSupplier videoOpen;
    private final Handler main = new Handler(Looper.getMainLooper());
    private CloudVideo visit; private ViewGroup box;
    /** Why visits are waiting, noted in the camera log each time it changes. */
    private String waiting = "";
    private void waitingFor(String why) { if (!why.equals(waiting)) { waiting = why; if (!why.isEmpty()) Diagnostics.note(Diagnostics.CAMERA, "cloud picture visits waiting: " + why); } }

    PictureVisits(Supplier<PrinterService> printer, SharedPreferences settings, BooleanSupplier videoOpen, Host host) {
        this.printer = printer; this.settings = settings; this.videoOpen = videoOpen; this.host = host;
    }

    /** Starts checking (soon, then every 30 s): when the service starts, and again when a screen shows. */
    void resume() { main.removeCallbacks(tick); main.postDelayed(tick, 5_000); }
    /** Stops checking and ends a running visit. */
    void pause() { main.removeCallbacks(tick); end(); }
    boolean running() { return visit != null; }
    /** Ends a running visit (it leaves the channel within about half a second) and gives its window back. */
    void end() {
        CloudVideo v = visit; visit = null; if (v != null) v.stop();
        ViewGroup old = box; box = null;
        if (old != null) main.postDelayed(() -> host.detach(old), 700);   // after the page has left the channel (CloudVideo.stop)
    }

    /** Whether a visit should start now, from the facts at hand (pure, for tests). */
    static boolean due(boolean enabled, boolean otherVideo, boolean local, boolean cloudFresh, boolean localCamera, boolean online,
                       boolean agreed, boolean channelHeld, int emptyStreak, long sinceEmptyMs, boolean pictureDue) {
        if (!enabled || otherVideo || local || !cloudFresh || localCamera || !online || !agreed || channelHeld) return false;
        if (emptyStreak >= 2 && sinceEmptyMs < 30 * 60_000L) return false;
        return pictureDue;
    }

    private final Runnable tick = new Runnable() { public void run() {
        main.postDelayed(this, 30_000);
        PrinterService p = printer.get();
        if (p == null || visit != null || p.cloudSerial.isEmpty() || !CloudVideo.supported()) return;
        // The native library takes them from the service, window or not; visits are the fallback when it cannot.
        if (p.nativePictures()) { waitingFor(""); return; }
        long now = SystemClock.elapsedRealtime();
        boolean pictureDue = p.recorder != null && p.recorder.pictureDue(now);
        boolean enabled = settings.getBoolean(PREF, true), open = videoOpen.getAsBoolean(), local = p.ready() || !p.usingCloud(), fresh = p.cloudFresh(),
            alongside = p.localCamera() || ExtraCameras.forPictures(p) != null, online = p.cloudOnline == 1, agreed = settings.getBoolean("cloudControlUnderstood", false), held = CloudCameraActivity.holdsChannel();
        if (due(enabled, open, local, fresh, alongside, online, agreed, held, CloudVideo.emptyStreak(), now - CloudVideo.emptyAt(), pictureDue)) {
            ViewGroup into = host.attach();
            if (into == null) { waitingFor(host.why()); return; }
            waitingFor(""); start(p, into); return;
        }
        boolean recording = p.recorder != null && p.recorder.current() != null;
        // The first reason that applies, in the order due() checks them; "no picture due" is the normal state and not noted.
        waitingFor(!enabled ? "turned off in Settings" : open ? "" : local ? "" : !fresh ? "no fresh status from the cloud" : alongside ? "" : !online ? "the cloud says the printer is offline"
            : !agreed ? "cloud control not agreed yet" : held ? "" : CloudVideo.emptyStreak() >= 2 && now - CloudVideo.emptyAt() < 30 * 60_000L ? "the printer's camera did not answer twice; trying again in 30 minutes"
            : !recording ? "no print is being recorded" : "");
    } };

    private void start(PrinterService p, ViewGroup into) {
        box = into;
        CloudVideo[] self = new CloudVideo[1];
        self[0] = new CloudVideo(into.getContext(), p.cloudSerial, printer, new CloudVideo.Listener() {
            public void status(String text, boolean problem) { if (problem && visit == self[0]) end(); }
            public void playing() { }
            public void snapshot(byte[] jpeg) { }
        }).oneShot("cloud picture visit", () -> { if (visit == self[0]) end(); });
        visit = self[0];
        box.addView(visit.view(), new ViewGroup.LayoutParams(-1, -1));
        visit.start();
        main.postDelayed(() -> { if (visit == self[0]) { Diagnostics.note(Diagnostics.CAMERA, "cloud picture visit: no picture within 35 s; left"); end(); } }, 35_000);
    }
}
