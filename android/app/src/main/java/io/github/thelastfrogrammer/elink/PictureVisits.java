package io.github.thelastfrogrammer.elink;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Cloud picture visits: every 30 s while the screen is open, in cloud mode, when the running print's picture is due and no
 * camera view is open, joins the printer's video channel unseen, keeps one picture and leaves (at most 35 s). Nothing is sent
 * to the printer; it joins its channel on its own when a viewer does. After two empty channels in a row it waits 30 minutes.
 * Settings › App preferences turns it off ("cloudPictureVisits").
 */
final class PictureVisits {
    static final String PREF = "cloudPictureVisits";
    private final Activity activity; private final Supplier<PrinterService> printer; private final SharedPreferences settings;
    /** True while another view holds the channel (the Print tab's video): a visit then waits. */
    private final BooleanSupplier videoOpen;
    private final Handler main = new Handler(Looper.getMainLooper());
    private CloudVideo visit; private FrameLayout box;
    /** Why visits are waiting, noted in the camera log each time it changes. */
    private String waiting = "";
    private void waitingFor(String why) { if (!why.equals(waiting)) { waiting = why; if (!why.isEmpty()) Diagnostics.note(Diagnostics.CAMERA, "cloud picture visits waiting: " + why); } }

    PictureVisits(Activity activity, Supplier<PrinterService> printer, SharedPreferences settings, BooleanSupplier videoOpen) {
        this.activity = activity; this.printer = printer; this.settings = settings; this.videoOpen = videoOpen;
    }

    /** Starts checking (first check after 10 s); call when the screen shows. */
    void resume() { main.removeCallbacks(tick); main.postDelayed(tick, 10_000); }
    /** Stops checking and ends a running visit; call when the screen goes. */
    void pause() { main.removeCallbacks(tick); end(); }
    boolean running() { return visit != null; }
    /** Ends a running visit (it leaves the channel within about half a second). */
    void end() { CloudVideo v = visit; visit = null; if (v != null) v.stop(); }

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
        long now = SystemClock.elapsedRealtime();
        boolean pictureDue = p.recorder != null && p.recorder.pictureDue(now);
        boolean enabled = settings.getBoolean(PREF, true), open = videoOpen.getAsBoolean(), local = p.ready() || !p.usingCloud(), fresh = p.cloudFresh(),
            alongside = p.localCamera() || ExtraCameras.forPictures(p) != null, online = p.cloudOnline == 1, agreed = settings.getBoolean("cloudControlUnderstood", false), held = CloudCameraActivity.holdsChannel();
        if (due(enabled, open, local, fresh, alongside, online, agreed, held, CloudVideo.emptyStreak(), now - CloudVideo.emptyAt(), pictureDue)) { waitingFor(""); start(p); return; }
        boolean recording = p.recorder != null && p.recorder.current() != null;
        // The first reason that applies, in the order due() checks them; "no picture due" is the normal state and not noted.
        waitingFor(!enabled ? "turned off in Settings" : open ? "" : local ? "" : !fresh ? "no fresh status from the cloud" : alongside ? "" : !online ? "the cloud says the printer is offline"
            : !agreed ? "cloud control not agreed yet" : held ? "" : CloudVideo.emptyStreak() >= 2 && now - CloudVideo.emptyAt() < 30 * 60_000L ? "the printer's camera did not answer twice; trying again in 30 minutes"
            : !recording ? "no print is being recorded" : "");
    } };

    private void start(PrinterService p) {
        if (box == null) {
            // A 2 dp spot in the corner: the video must be in the window to decode, but nobody needs to see it.
            int size = Math.round(2 * activity.getResources().getDisplayMetrics().density);
            box = new FrameLayout(activity); box.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            ((FrameLayout) activity.findViewById(android.R.id.content)).addView(box, new FrameLayout.LayoutParams(size, size, Gravity.BOTTOM | Gravity.START));
        }
        CloudVideo[] self = new CloudVideo[1];
        self[0] = new CloudVideo(activity, p.cloudSerial, printer, new CloudVideo.Listener() {
            public void status(String text, boolean problem) { if (problem && visit == self[0]) end(); }
            public void playing() { }
            public void snapshot(byte[] jpeg) { }
        }).oneShot("cloud picture visit", () -> { if (visit == self[0]) end(); });
        visit = self[0];
        box.addView(visit.view(), new FrameLayout.LayoutParams(-1, -1));
        visit.start();
        main.postDelayed(() -> { if (visit == self[0]) { Diagnostics.note(Diagnostics.CAMERA, "cloud picture visit: no picture within 35 s; left"); end(); } }, 35_000);
    }
}
