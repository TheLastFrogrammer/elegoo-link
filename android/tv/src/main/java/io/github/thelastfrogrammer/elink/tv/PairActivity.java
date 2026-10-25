package io.github.thelastfrogrammer.elink.tv;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import java.util.Locale;

/**
 * Pairing: the TV asks the phone, shows the 4-digit code big, and waits while the person approves it on the phone, with
 * how long the code still works. Refused, run out or not reached each say so and offer to ask again.
 */
public final class PairActivity extends Activity {
    /** Why pairing is needed again (the phone refused the old token), shown under the heading. */
    static final String EXTRA_WHY = "why";

    private final Handler main = new Handler(Looper.getMainLooper());
    private PhoneStore store;
    private PairingSession session;
    private LinearLayout card, digits, buttons;
    private TextView why, cardTitle, cardBody, countdown;
    private ProgressBar asking, timeLeft;
    private long expiresAt, shownFor;
    private boolean destroyed;
    private final Runnable tick = new Runnable() { @Override public void run() { showTimeLeft(); if (expiresAt > 0) main.postDelayed(this, 1000); } };

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        store = new PhoneStore(this);
        // Five minutes to walk to the phone: the screen saver must not hide the code meanwhile.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL); root.setBackgroundColor(Ui.BG);
        root.setPadding(Ui.dp(this, Ui.SIDE + 16), Ui.dp(this, Ui.TOP + 16), Ui.dp(this, Ui.SIDE + 16), Ui.dp(this, Ui.TOP + 16));

        LinearLayout steps = new LinearLayout(this);
        steps.setOrientation(LinearLayout.VERTICAL); steps.setGravity(Gravity.CENTER_VERTICAL);
        steps.addView(Ui.label(this, "Printer on TV"));
        steps.addView(Ui.heading(this, "Approve this TV on your phone", Ui.DISPLAY), Ui.gap(Ui.wide(), this, 10));
        why = Ui.text(this, "", Ui.BODY, Ui.CALM);
        String reason = getIntent().getStringExtra(EXTRA_WHY);
        why.setText(reason == null ? "" : reason); why.setVisibility(reason == null ? View.GONE : View.VISIBLE);
        steps.addView(why, Ui.gap(Ui.wide(), this, 14));
        step(steps, "1", "On the phone, open Link Workshop.");
        step(steps, "2", "Go to Settings › Automations & integrations › Other apps.");
        step(steps, "3", "Check that the phone shows the same code, then tap Approve.");
        steps.addView(Ui.text(this, "This TV will only watch the printer. It cannot send it anything.", Ui.SMALL, Ui.MUTED), Ui.gap(Ui.wide(), this, 26));
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        left.rightMargin = Ui.dp(this, 56);
        root.addView(steps, left);

        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL); side.setGravity(Gravity.CENTER);
        card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL); card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setBackground(Ui.round(Ui.CARD, Ui.dp(this, 26)));
        card.setPadding(Ui.dp(this, 36), Ui.dp(this, 30), Ui.dp(this, 36), Ui.dp(this, 32));
        card.setMinimumHeight(Ui.dp(this, 260));
        cardTitle = Ui.text(this, "", Ui.SMALL + 1, Ui.MUTED); cardTitle.setGravity(Gravity.CENTER);
        card.addView(cardTitle, Ui.wide());
        asking = new ProgressBar(this); asking.setIndeterminate(true); asking.setIndeterminateTintList(ColorStateList.valueOf(Ui.ACCENT));
        LinearLayout.LayoutParams askingParams = new LinearLayout.LayoutParams(Ui.dp(this, 56), Ui.dp(this, 56)); askingParams.topMargin = Ui.dp(this, 40);
        card.addView(asking, askingParams);
        digits = new LinearLayout(this); digits.setOrientation(LinearLayout.HORIZONTAL); digits.setGravity(Gravity.CENTER);
        digits.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        card.addView(digits, Ui.gap(Ui.wrap(), this, 22));
        cardBody = Ui.text(this, "", Ui.BODY, Ui.INK); cardBody.setGravity(Gravity.CENTER);
        cardBody.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        card.addView(cardBody, Ui.gap(Ui.wide(), this, 20));
        timeLeft = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        timeLeft.setIndeterminate(false); timeLeft.setMax(1000);
        timeLeft.setProgressTintList(ColorStateList.valueOf(Ui.ACCENT)); timeLeft.setProgressBackgroundTintList(ColorStateList.valueOf(Ui.TRACK));
        timeLeft.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);   // the words below say the same
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(Ui.dp(this, 300), Ui.dp(this, 8)); barParams.topMargin = Ui.dp(this, 18);
        card.addView(timeLeft, barParams);
        countdown = Ui.text(this, "", Ui.SMALL, Ui.MUTED); countdown.setGravity(Gravity.CENTER);
        card.addView(countdown, Ui.gap(Ui.wide(), this, 8));
        side.addView(card, Ui.wide());
        // Stacked: Up and Down move between them, and "Choose another phone" never wraps beside "Ask again".
        buttons = new LinearLayout(this); buttons.setOrientation(LinearLayout.VERTICAL); buttons.setGravity(Gravity.CENTER_HORIZONTAL);
        side.addView(buttons, Ui.gap(Ui.wide(), this, 26));
        root.addView(side, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.1f));
        setContentView(root);
        asking();
    }

    @Override protected void onStart() { super.onStart(); if (FindActivity.networking) ask(); }
    @Override protected void onStop() { super.onStop(); if (session != null) session.cancel(); session = null; main.removeCallbacks(tick); }
    @Override protected void onDestroy() { destroyed = true; main.removeCallbacksAndMessages(null); super.onDestroy(); }

    private void step(LinearLayout into, String number, String words) {
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.TOP);
        TextView n = Ui.heading(this, number, Ui.BODY); n.setTextColor(Ui.ON_ACCENT); n.setGravity(Gravity.CENTER);
        n.setBackground(Ui.round(Ui.ACCENT, Ui.dp(this, 18)));
        n.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(n, new LinearLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 36)));
        TextView t = Ui.text(this, words, Ui.BODY, Ui.INK);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); tp.leftMargin = Ui.dp(this, 18);
        row.addView(t, tp);
        into.addView(row, Ui.gap(Ui.wide(), this, 16));
    }

    /** Starts a new request (each has its own code). */
    void ask() {
        if (session != null) session.cancel();
        String base = store.base();
        if (base == null) { finish(); return; }
        PairingSession[] mine = new PairingSession[1];
        PairingSession s = new PairingSession(new PhoneApi(base), PhoneStore.deviceName(this), PairingSession.POLL_MS, new PairingSession.Listener() {
            @Override public void asking() { on(() -> PairActivity.this.asking()); }
            @Override public void code(String code, long expiresAtMs) { on(() -> showCode(code, expiresAtMs)); }
            @Override public void waiting(boolean reachable) { on(() -> waiting(reachable)); }
            @Override public void approved(String token) { on(() -> approved(token)); }
            @Override public void refused() { on(PairActivity.this::refused); }
            @Override public void expired() { on(PairActivity.this::expired); }
            @Override public void failed(boolean busy, String words) { on(() -> failed(busy, words)); }
            // Only the current request may change the screen (an old one, cancelled, can still be finishing a call).
            private void on(Runnable r) { main.post(() -> { if (!destroyed && session != null && session == mine[0]) r.run(); }); }
        });
        mine[0] = s; session = s; s.start();
    }

    // ------------------------------------------------------------------ the states (package-private: the tests show each)

    void asking() {
        expiresAt = 0; main.removeCallbacks(tick);
        cardTitle.setText("Asking the phone…");
        asking.setVisibility(View.VISIBLE); digits.setVisibility(View.GONE); timeLeft.setVisibility(View.GONE); countdown.setVisibility(View.GONE);
        cardBody.setText(""); cardBody.setVisibility(View.GONE);
        buttons(new String[] {"Choose another phone"}, new Runnable[] {this::finish});
    }

    void showCode(String code, long expiresAtMs) {
        expiresAt = expiresAtMs; shownFor = Math.max(1, expiresAtMs - System.currentTimeMillis());
        cardTitle.setText("The code on the phone");
        asking.setVisibility(View.GONE);
        digits.removeAllViews();
        for (int i = 0; i < code.length(); i++) {
            TextView d = Ui.heading(this, String.valueOf(code.charAt(i)), Ui.HUGE);
            d.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); d.setGravity(Gravity.CENTER); d.setIncludeFontPadding(false);
            d.setBackground(Ui.round(Ui.BG, Ui.dp(this, 18)));
            d.setTextSize(76);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(Ui.dp(this, 70), Ui.dp(this, 104));
            if (i > 0) p.leftMargin = Ui.dp(this, 10);
            digits.addView(d, p);
            d.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        // Read one digit at a time ("4 8 2 1"), not as a number.
        StringBuilder spoken = new StringBuilder("Code");
        for (char c : code.toCharArray()) spoken.append(' ').append(c);
        digits.setContentDescription(spoken);
        digits.setVisibility(View.VISIBLE);
        cardBody.setText("Waiting for you to approve it on the phone"); cardBody.setTextColor(Ui.INK); cardBody.setVisibility(View.VISIBLE);
        timeLeft.setVisibility(View.VISIBLE); countdown.setVisibility(View.VISIBLE);
        buttons(new String[] {"Choose another phone"}, new Runnable[] {this::finish});
        main.removeCallbacks(tick); tick.run();
    }

    void waiting(boolean reachable) {
        cardBody.setText(reachable ? "Waiting for you to approve it on the phone" : "The phone is not answering just now. Still waiting…");
        cardBody.setTextColor(reachable ? Ui.INK : Ui.CALM);
    }

    void approved(String token) {
        store.token(token);
        expiresAt = 0; main.removeCallbacks(tick);
        cardBody.setText("Approved. Opening the camera…"); cardBody.setTextColor(Ui.GOOD);
        startActivity(new Intent(this, WatchActivity.class));
        finish();
    }

    void refused() {
        ended("The phone said no", "Someone tapped Refuse on the phone. If that was a mistake, ask again.", true);
    }
    void expired() {
        ended("The code ran out", "Codes work for five minutes. Ask again for a new one.", true);
    }
    void failed(boolean busy, String words) {
        if (busy) ended("The phone is busy", "Other devices are waiting to be approved on the phone. Approve or refuse them there, then ask again.", true);
        else ended("Could not reach the phone", words.isEmpty() ? "Check that Link Workshop is open on the phone, that Other apps is turned on, and that both are on the same Wi-Fi." : words, true);
    }

    private void ended(String title, String body, boolean again) {
        if (session != null) session.cancel();
        session = null; expiresAt = 0; main.removeCallbacks(tick);
        cardTitle.setText("");
        asking.setVisibility(View.GONE); digits.setVisibility(View.GONE); timeLeft.setVisibility(View.GONE); countdown.setVisibility(View.GONE);
        cardTitle.setText(title); cardTitle.setTextColor(Ui.INK); cardTitle.setTextSize(Ui.TITLE);
        cardBody.setText(body); cardBody.setTextColor(Ui.MUTED); cardBody.setVisibility(View.VISIBLE);
        if (again) buttons(new String[] {"Ask again", "Choose another phone"}, new Runnable[] {this::askAgain, this::finish});
    }
    private void askAgain() { cardTitle.setTextColor(Ui.MUTED); cardTitle.setTextSize(Ui.SMALL + 1); asking(); if (FindActivity.networking) ask(); }

    private void showTimeLeft() {
        long left = Math.max(0, expiresAt - System.currentTimeMillis());
        long s = (left + 999) / 1000;
        countdown.setText(String.format(Locale.ROOT, "The code works for another %d:%02d", s / 60, s % 60));
        timeLeft.setProgress((int) (1000 * left / Math.max(1, shownFor)));
    }

    private void buttons(String[] words, Runnable[] actions) {
        View focused = getWindow().getDecorView().findFocus();
        boolean hadFocus = focused == null || focused.getParent() == buttons;
        buttons.removeAllViews();
        for (int i = 0; i < words.length; i++) {
            Runnable action = actions[i];
            Button b = Ui.button(this, words[i], v -> action.run());
            LinearLayout.LayoutParams p = Ui.wrap(); if (i > 0) p.topMargin = Ui.dp(this, 14);
            buttons.addView(b, p);
        }
        if (buttons.getChildCount() > 0 && hadFocus) buttons.getChildAt(0).requestFocus();   // the focus moves to the new first button
    }
}
