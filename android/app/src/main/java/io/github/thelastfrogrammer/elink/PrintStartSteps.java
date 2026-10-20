package io.github.thelastfrogrammer.elink;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import java.util.function.Supplier;
import org.json.JSONObject;

/**
 * After Start print: a full page that follows the print starting, step by step, from the printer's own status. The command
 * is sent once by the caller before this opens; nothing here sends anything. A step is done only when a status that arrived
 * after the tap shows it (an acknowledgement is not the printer printing). After three minutes without that, it says so and
 * points to the Print tab instead of claiming success.
 */
final class PrintStartSteps {
    static final long GIVE_UP_MS = 3 * 60_000L;
    private static final String[] STEPS = {"Checking the printer is ready", "Sending the print job", "Printer starting", "Printing"};

    /** Where the start is, from facts at hand (pure, for tests): 0–3 the step being waited for, 4 printing, -1 refused, -2 not confirmed. */
    static int stage(boolean refused, boolean freshAfterTap, int machineStatus, String printingFile, String file, long waitedMs) {
        if (refused) return -1;
        if (freshAfterTap && machineStatus == 2 && same(printingFile, file)) return 4;
        if (waitedMs > GIVE_UP_MS) return -2;
        if (freshAfterTap && machineStatus != 1 && machineStatus >= 0) return 3;
        return 2;
    }
    private static boolean same(String a, String b) {
        String x = a == null ? "" : a.replaceFirst("^.*/", "").trim(), y = b == null ? "" : b.replaceFirst("^.*/", "").trim();
        return !x.isEmpty() && x.equalsIgnoreCase(y);
    }

    private final Activity activity; private final Supplier<PrinterService> printer; private final String file, where;
    private final int ink, muted, accent, background, surface; private final boolean dark;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final long tappedAt = System.currentTimeMillis();
    private Dialog page; private TextView title, note; private FrameLayout badge; private ProgressBar spinner; private TextView check;
    private final View[] marks = new View[STEPS.length]; private final TextView[] states = new TextView[STEPS.length];
    private Button done; private int shown = Integer.MIN_VALUE;
    private final boolean refused;

    PrintStartSteps(Activity activity, Supplier<PrinterService> printer, String file, String where, boolean refused,
                    int ink, int muted, int accent, int background, int surface, boolean dark) {
        this.activity = activity; this.printer = printer; this.file = file; this.where = where; this.refused = refused;
        this.ink = ink; this.muted = muted; this.accent = accent; this.background = background; this.surface = surface; this.dark = dark;
    }

    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }

    void show() {
        page = new Dialog(activity, dark ? android.R.style.Theme_Material_NoActionBar : android.R.style.Theme_Material_Light_NoActionBar);
        LinearLayout root = new LinearLayout(activity); root.setOrientation(LinearLayout.VERTICAL); root.setFitsSystemWindows(true); root.setPadding(dp(20), dp(12), dp(20), dp(20));
        // A soft wash of the accent from the top into the page colour.
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {(accent & 0x00ffffff) | (dark ? 0x40000000 : 0x30000000), background, background}));
        TextView bar = new TextView(activity); bar.setText("Starting the print"); bar.setTextSize(20); bar.setTextColor(ink); bar.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        bar.setMinHeight(dp(56)); bar.setGravity(Gravity.CENTER_VERTICAL); A11y.heading(bar); root.addView(bar);
        // The big circle: a spinner while waiting, a check when the printer shows the print, "!" otherwise.
        badge = new FrameLayout(activity);
        GradientDrawable halo = new GradientDrawable(); halo.setShape(GradientDrawable.OVAL); halo.setColor((accent & 0x00ffffff) | 0x26000000); badge.setBackground(halo);
        LinearLayout.LayoutParams badgeAt = new LinearLayout.LayoutParams(dp(176), dp(176)); badgeAt.gravity = Gravity.CENTER_HORIZONTAL; badgeAt.topMargin = dp(24); root.addView(badge, badgeAt);
        spinner = new ProgressBar(activity); spinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(accent));
        badge.addView(spinner, new FrameLayout.LayoutParams(dp(120), dp(120), Gravity.CENTER));
        check = new TextView(activity); check.setTextSize(56); check.setTypeface(Typeface.DEFAULT, Typeface.BOLD); check.setGravity(Gravity.CENTER); check.setVisibility(View.GONE);
        GradientDrawable disc = new GradientDrawable(); disc.setShape(GradientDrawable.OVAL); disc.setColor(accent); check.setBackground(disc); check.setTextColor(dark ? 0xff00201c : Color.WHITE);
        badge.addView(check, new FrameLayout.LayoutParams(dp(112), dp(112), Gravity.CENTER));
        title = text(root, "", 24, ink, true); ((LinearLayout.LayoutParams) title.getLayoutParams()).topMargin = dp(28); A11y.polite(title);
        TextView names = text(root, StatusPresentation.clean(file) + (where.isEmpty() ? "" : "\n" + where), 14, muted, false); names.setGravity(Gravity.CENTER); title.setGravity(Gravity.CENTER);
        // The steps, each with its mark and state.
        LinearLayout list = new LinearLayout(activity); list.setOrientation(LinearLayout.VERTICAL); list.setPadding(dp(20), dp(12), dp(20), dp(12));
        GradientDrawable card = new GradientDrawable(); card.setColor(surface); card.setCornerRadius(dp(24)); list.setBackground(card);
        LinearLayout.LayoutParams listAt = new LinearLayout.LayoutParams(-1, -2); listAt.topMargin = dp(24); root.addView(list, listAt);
        for (int i = 0; i < STEPS.length; i++) {
            LinearLayout row = new LinearLayout(activity); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL); row.setMinimumHeight(dp(48));
            TextView mark = new TextView(activity); mark.setGravity(Gravity.CENTER); mark.setTextSize(14); mark.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            row.addView(mark, new LinearLayout.LayoutParams(dp(28), dp(28))); marks[i] = mark;
            TextView name = new TextView(activity); name.setText(STEPS[i]); name.setTextSize(16); name.setTextColor(ink); name.setPadding(dp(14), 0, dp(8), 0);
            row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            TextView state = new TextView(activity); state.setTextSize(13); state.setTextColor(muted); row.addView(state); states[i] = state;
            list.addView(row);
        }
        note = text(root, "", 13, muted, false); ((LinearLayout.LayoutParams) note.getLayoutParams()).topMargin = dp(12);
        View space = new View(activity); root.addView(space, new LinearLayout.LayoutParams(-1, 0, 1));
        done = new Button(activity); done.setAllCaps(false); done.setTextSize(17); done.setTypeface(Typeface.DEFAULT, Typeface.BOLD); done.setMinHeight(dp(56));
        GradientDrawable pill = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[] {accent, (accent & 0x00ffffff) | 0xcc000000}); pill.setCornerRadius(dp(28));
        done.setBackground(pill); done.setTextColor(dark ? 0xff00201c : Color.WHITE); done.setOnClickListener(v -> page.dismiss());
        root.addView(done, new LinearLayout.LayoutParams(-1, -2));
        android.widget.ScrollView scroll = new android.widget.ScrollView(activity); scroll.setFillViewport(true); scroll.addView(root);
        page.setContentView(scroll);
        page.setOnDismissListener(d -> main.removeCallbacksAndMessages(null));
        page.show();
        tick.run();
    }

    private TextView text(LinearLayout parent, String value, int size, int color, boolean bold) {
        TextView view = new TextView(activity); view.setText(value); view.setTextSize(size); view.setTextColor(color); if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        parent.addView(view, new LinearLayout.LayoutParams(-1, -2)); return view;
    }

    private final Runnable tick = new Runnable() { public void run() {
        PrinterService p = printer.get();
        JSONObject live = p == null ? new JSONObject() : p.liveStatus();
        JSONObject machine = live.optJSONObject("machine_status"), print = live.optJSONObject("print_status");
        long statusTime = p == null ? 0 : p.ready() ? p.statusAt : p.cloudCheckedAt;
        // The local session can still refuse at the moment of sending (its checks run again then): its message says so.
        boolean notSent = refused || p != null && p.feedback != null && p.feedback.startsWith("Start print not sent");
        int now = stage(notSent, p != null && p.liveFresh() && statusTime > tappedAt, machine == null ? -1 : machine.optInt("status", -1),
            print == null ? "" : print.optString("filename", ""), file, System.currentTimeMillis() - tappedAt);
        if (now != shown) { shown = now; draw(now, p == null ? "" : p.feedback); }
        if (now >= 0 && now < 4) main.postDelayed(this, 1000);
    } };

    private void draw(int stage, String feedback) {
        int reached = stage == 4 ? STEPS.length : stage == -1 ? 0 : stage == -2 ? 2 : stage;
        for (int i = 0; i < STEPS.length; i++) {
            boolean ok = i < reached, waiting = i == reached && stage >= 0, failed = stage == -1 && i == 0 || stage == -2 && i == 2;
            TextView mark = (TextView) marks[i];
            GradientDrawable dot = new GradientDrawable(); dot.setShape(GradientDrawable.OVAL);
            if (ok) { dot.setColor(accent); mark.setText("✓"); mark.setTextColor(dark ? 0xff00201c : Color.WHITE); }
            else if (failed) { dot.setColor(Color.TRANSPARENT); dot.setStroke(dp(2), muted); mark.setText("!"); mark.setTextColor(ink); }
            else { dot.setColor(Color.TRANSPARENT); dot.setStroke(dp(2), waiting ? accent : muted); mark.setText(""); }
            mark.setBackground(dot);
            states[i].setText(ok ? "Done" : waiting ? "Waiting…" : failed ? (stage == -1 ? "Not sent" : "Not seen yet") : "");
            states[i].setTextColor(ok || waiting ? accent : muted);
        }
        boolean finished = stage == 4;
        spinner.setVisibility(stage >= 0 && !finished ? View.VISIBLE : View.GONE);
        check.setVisibility(stage < 0 || finished ? View.VISIBLE : View.GONE); check.setText(finished ? "✓" : "!");
        title.setText(finished ? "The printer is printing" : stage == -1 ? "The print was not started" : stage == -2 ? "Not confirmed yet" : "Waiting for the printer…");
        note.setText(stage == -1 ? StatusPresentation.clean(feedback)
            : stage == -2 ? "The printer has not shown this print in its status for three minutes. Nothing was sent again. Check the Print tab or the printer's screen" + (feedback == null || feedback.isEmpty() ? "." : ". Last message: " + StatusPresentation.clean(feedback))
            : finished ? "Its status shows this file printing." : "Each step is checked against the printer's own status, not just its reply.");
        done.setText(finished ? "Done" : stage < 0 ? "Close" : "Hide (it carries on)");
    }
}
