package io.github.thelastfrogrammer.elink;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/**
 * Showcase: a scripted tour of the app, recorded to a video while it runs. Each screen plays its own part of the tour (see
 * MainActivity, GcodeViewerActivity and RecordingsActivity) with captions faded in over it; this class keeps where the tour
 * is and draws the captions, title cards and fades. The tour only moves between screens and changes how things are shown:
 * it never presses anything that sends a command to the printer. While it runs, screens leave out the printer's address,
 * serial and name, so the video can be shared.
 */
final class Showcase {
    /** Parts of the tour, in order: the main tabs, the live toolpath, a print's recording, then the closing card. */
    static final int OFF = 0, MAIN = 1, VIEWER = 2, RECORDING = 3, END = 4;
    static int stage = OFF;
    /** The recording made by ShowcaseService, once it has stopped. */
    static java.io.File video;

    static boolean running() { return stage != OFF; }
    /** The recording to show: the running print's, else the newest one; null when there is none. */
    static String recordingMeta(android.content.Context context, PrinterService printer) {
        PrintRecorder.Recording current = printer == null || printer.recorder == null ? null : printer.recorder.current();
        if (current != null) return current.meta.getAbsolutePath();
        List<PrintRecorder.Recording> all = new PrintRecorder(RecordingsActivity.directory(context)).list();
        return all.isEmpty() ? null : all.get(0).meta.getAbsolutePath();
    }
    /** Goes on to a print's recording (or straight to the end when there is none) and closes `from`. */
    static void toRecording(Activity from, PrinterService printer) {
        String meta = recordingMeta(from, printer);
        if (meta != null) { stage = RECORDING; from.startActivity(new android.content.Intent(from, RecordingsActivity.class).putExtra(RecordingsActivity.EXTRA_META, meta).putExtra(EXTRA, true)); }
        else stage = END;
        from.finish(); from.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }
    /** Intent extra: this screen was opened by the tour. */
    static final String EXTRA = "showcase";
    static void stop() { stage = OFF; }

    /** Steps run one after another on the main thread, each after its pause; stops early when the tour ends or the screen goes. */
    static final class Script {
        private final Activity activity; private final Handler main = new Handler(Looper.getMainLooper());
        private final List<Object[]> steps = new ArrayList<>(); private boolean cancelled;
        Script(Activity activity) { this.activity = activity; }
        /** Runs `action` after waiting `waitMs` from the step before. */
        Script then(long waitMs, Runnable action) { steps.add(new Object[] {waitMs, action}); return this; }
        /** Waits until `ready` says so (checked every quarter second, at most `maxMs`), then goes on. */
        Script until(long maxMs, java.util.function.BooleanSupplier ready) { steps.add(new Object[] {maxMs, ready}); return this; }
        void cancel() { cancelled = true; main.removeCallbacksAndMessages(null); }
        void run(Runnable done) { next(0, done); }
        private void next(int index, Runnable done) {
            if (cancelled || activity.isFinishing() || activity.isDestroyed() || !running()) return;
            if (index >= steps.size()) { if (done != null) done.run(); return; }
            Object[] step = steps.get(index);
            if (step[1] instanceof Runnable) main.postDelayed(() -> {
                if (cancelled || activity.isDestroyed() || !running()) return;
                try { ((Runnable) step[1]).run(); } catch (RuntimeException ignored) { }
                next(index + 1, done);
            }, (Long) step[0]);
            else waitFor(index, done, (java.util.function.BooleanSupplier) step[1], android.os.SystemClock.elapsedRealtime() + (Long) step[0]);
        }
        private void waitFor(int index, Runnable done, java.util.function.BooleanSupplier ready, long deadline) {
            boolean ok; try { ok = ready.getAsBoolean(); } catch (RuntimeException e) { ok = false; }
            if (ok || android.os.SystemClock.elapsedRealtime() > deadline) { next(index + 1, done); return; }
            main.postDelayed(() -> { if (!cancelled && running()) waitFor(index, done, ready, deadline); }, 250);
        }
    }

    // ------------------------------------------------------------------ captions, cards and fades

    private static final String CAPTION_TAG = "showcase-caption", CARD_TAG = "showcase-card";
    private static float dp(Activity a, float v) { return v * a.getResources().getDisplayMetrics().density; }
    private static FrameLayout root(Activity a) { return a.findViewById(android.R.id.content); }

    /** Shows a caption near the bottom of the screen, fading the old one out and the new one in; null or "" hides it. */
    static void caption(Activity a, String text) {
        FrameLayout root = root(a); if (root == null) return;
        TextView old = root.findViewWithTag(CAPTION_TAG);
        if (old != null) { old.setTag(null); old.animate().alpha(0).setDuration(250).withEndAction(() -> root.removeView(old)).start(); }
        if (text == null || text.isEmpty()) return;
        TextView label = new TextView(a); label.setTag(CAPTION_TAG); label.setText(text); label.setTextSize(17); label.setTextColor(Color.WHITE);
        label.setTypeface(Typeface.DEFAULT_BOLD); label.setGravity(Gravity.CENTER); label.setShadowLayer(dp(a, 3), 0, dp(a, 1), 0xff000000);
        int padH = (int) dp(a, 18), padV = (int) dp(a, 10); label.setPadding(padH, padV, padH, padV);
        GradientDrawable plate = new GradientDrawable(); plate.setColor(0xcc0f1a20); plate.setCornerRadius(dp(a, 16)); plate.setStroke((int) dp(a, 1), 0x5534d1b6); label.setBackground(plate);
        FrameLayout.LayoutParams at = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        int side = (int) dp(a, 20); at.setMargins(side, 0, side, (int) dp(a, 96)); label.setMaxWidth(a.getResources().getDisplayMetrics().widthPixels - 2 * side);
        label.setAlpha(0); label.setTranslationY(dp(a, 12)); label.setElevation(dp(a, 12)); label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        root.addView(label, at); label.animate().alpha(1).translationY(0).setDuration(350).start();
    }

    /** A full-screen card (the app's name and a line under it), faded in; `holdMs` later it fades away by itself (0 keeps it). */
    static void card(Activity a, String title, String line, long holdMs) {
        FrameLayout root = root(a); if (root == null) return;
        View old = root.findViewWithTag(CARD_TAG); if (old != null) root.removeView(old);
        LinearLayout card = new LinearLayout(a); card.setTag(CARD_TAG); card.setOrientation(LinearLayout.VERTICAL); card.setGravity(Gravity.CENTER);
        card.setBackgroundColor(0xff0f1a20); card.setClickable(true); card.setElevation(dp(a, 16));
        TextView name = new TextView(a); name.setText(title); name.setTextSize(34); name.setTextColor(Color.WHITE); name.setTypeface(Typeface.DEFAULT_BOLD); name.setGravity(Gravity.CENTER);
        TextView under = new TextView(a); under.setText(line); under.setTextSize(16); under.setTextColor(0xff9fe6d8); under.setGravity(Gravity.CENTER); under.setPadding(0, (int) dp(a, 10), 0, 0);
        View bar = new View(a); bar.setBackgroundColor(0xff34d1b6); LinearLayout.LayoutParams barAt = new LinearLayout.LayoutParams((int) dp(a, 56), (int) dp(a, 3)); barAt.topMargin = (int) dp(a, 18);
        card.addView(name); card.addView(under); card.addView(bar, barAt);
        card.setAlpha(0); root.addView(card, new FrameLayout.LayoutParams(-1, -1)); card.animate().alpha(1).setDuration(500).start();
        name.setScaleX(0.92f); name.setScaleY(0.92f); name.animate().scaleX(1).scaleY(1).setDuration(1200).start();
        if (holdMs > 0) card.postDelayed(() -> card.animate().alpha(0).setDuration(600).withEndAction(() -> root.removeView(card)).start(), holdMs);
    }
    static void clear(Activity a) {
        FrameLayout root = root(a); if (root == null) return;
        for (String tag : new String[] {CAPTION_TAG, CARD_TAG}) { View v = root.findViewWithTag(tag); if (v != null) root.removeView(v); }
    }

    /** Dims `view`, makes the change while it is dim, and brings it back: the change shows as a fade instead of a jump. */
    static void fade(View view, Runnable change) {
        if (view == null) { change.run(); return; }
        view.animate().alpha(0.15f).setDuration(260).withEndAction(() -> { change.run(); view.animate().alpha(1).setDuration(420).start(); }).start();
    }

    /** Scrolls slowly (over `ms`) so the motion reads well on video. */
    static void glide(ScrollView scroll, int toY, long ms) {
        if (scroll == null) return;
        android.animation.ObjectAnimator.ofInt(scroll, "scrollY", scroll.getScrollY(), Math.max(0, toY)).setDuration(ms).start();
    }
    /** Glides `scroll` so `target` sits a little below the top. */
    static void glideTo(ScrollView scroll, View target, long ms) {
        if (scroll == null || target == null) return;
        int[] at = new int[2], top = new int[2]; target.getLocationOnScreen(at); scroll.getLocationOnScreen(top);
        glide(scroll, scroll.getScrollY() + at[1] - top[1] - (int) (scroll.getHeight() * 0.12f), ms);
    }
    /** The first view under `root` whose text starts with `text`, or null. */
    static View find(View root, String text) {
        if (root == null || root.getVisibility() != View.VISIBLE) return null;
        if (root instanceof TextView && ((TextView) root).getText().toString().startsWith(text)) return root;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) { View found = find(((ViewGroup) root).getChildAt(i), text); if (found != null) return found; }
        return null;
    }
}
