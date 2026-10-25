package io.github.thelastfrogrammer.elink.tv;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The TV's look, built in code like the phone app: a calm dark palette (the phone's TV page), text sized for a sofa three
 * metres away, and a focus that is plain to see (a bright fill or ring and a slight lift), because the remote's D-pad
 * is the only way around. Sizes are in dp/sp on the TV's 960×540 dp screen, inside the 48/27 dp overscan margins.
 */
final class Ui {
    static final int BG = 0xFF0E1417, CARD = 0xFF182227, CARD_FOCUSED = 0xFF233840, BUTTON = 0xFF21343A, INK = 0xFFE6EEF1, MUTED = 0xFF9FB3BB,
        ACCENT = 0xFF5FD4C4, ON_ACCENT = 0xFF06201D, WARN = 0xFFFFB4AB, CALM = 0xFFF2C46D, GOOD = 0xFF8ED6A0, TRACK = 0xFF2A3A40;
    /** Text sizes (sp). */
    static final float HUGE = 96, DISPLAY = 44, TITLE = 30, BODY = 22, SMALL = 18;
    /** Overscan-safe margins (dp). */
    static final int SIDE = 48, TOP = 27;

    static int dp(Context c, float v) { return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics())); }

    static TextView text(Context c, String words, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(words); t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); t.setTextColor(color);
        t.setLineSpacing(0, 1.15f); t.setIncludeFontPadding(true);
        return t;
    }
    static TextView heading(Context c, String words, float sp) {
        TextView t = text(c, words, sp, INK); t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); t.setLineSpacing(0, 1.05f);
        return t;
    }
    /** The small capitals over a screen: the app's name. */
    static TextView label(Context c, String words) {
        TextView t = text(c, words.toUpperCase(java.util.Locale.ROOT), SMALL, ACCENT);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); t.setLetterSpacing(0.12f);
        return t;
    }

    static GradientDrawable round(int color, float radiusPx) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(radiusPx); return d; }

    /** A pill button: filled with the accent when focused, so the focus reads from across the room. */
    static Button button(Context c, String words, View.OnClickListener click) {
        Button b = new Button(c);
        b.setText(words); b.setAllCaps(false); b.setTextSize(TypedValue.COMPLEX_UNIT_SP, BODY);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setStateListAnimator(null); b.setElevation(0);
        float r = dp(c, 30);
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[] {android.R.attr.state_pressed}, round(0xFF4CBFB0, r));
        bg.addState(new int[] {android.R.attr.state_focused}, round(ACCENT, r));
        bg.addState(new int[] {}, round(BUTTON, r));
        b.setBackground(bg);
        b.setTextColor(new ColorStateList(new int[][] {{android.R.attr.state_focused}, {android.R.attr.state_pressed}, {}}, new int[] {ON_ACCENT, ON_ACCENT, ACCENT}));
        b.setMinHeight(dp(c, 60)); b.setMinimumHeight(dp(c, 60)); b.setMinWidth(dp(c, 120)); b.setMinimumWidth(dp(c, 120));
        b.setPadding(dp(c, 32), dp(c, 8), dp(c, 32), dp(c, 8));
        b.setFocusable(true); b.setOnClickListener(click);
        lift(b, 1.06f);
        return b;
    }

    /** A focusable list row (two lines): a ring and a lighter card when focused. Screen readers hear it as a button. */
    static final class Row extends LinearLayout {
        final TextView title, detail;
        Row(Context c) { this(c, "", ""); }
        Row(Context c, String titleWords, String detailWords) {
            super(c);
            setOrientation(VERTICAL); setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(c, 26), dp(c, 12), dp(c, 26), dp(c, 12)); setMinimumHeight(dp(c, 74));
            title = heading(c, titleWords, 25); title.setSingleLine(); title.setEllipsize(TextUtils.TruncateAt.END);
            detail = text(c, detailWords, SMALL + 1, MUTED); detail.setLineSpacing(0, 1.05f);
            addView(title); addView(detail);
            float r = dp(c, 18);
            GradientDrawable on = round(CARD_FOCUSED, r); on.setStroke(dp(c, 3), ACCENT);
            StateListDrawable bg = new StateListDrawable();
            bg.addState(new int[] {android.R.attr.state_focused}, on);
            bg.addState(new int[] {}, round(CARD, r));
            setBackground(bg);
            setFocusable(true); setClickable(true);
            lift(this, 1.02f);
        }
        void detail(String words, int color) { detail.setText(words); detail.setTextColor(color); }
        @Override public CharSequence getAccessibilityClassName() { return Button.class.getName(); }
    }

    /** Grows a little while focused, the way TV launchers show focus. */
    static void lift(View v, float scale) {
        v.setOnFocusChangeListener((view, focused) -> view.animate().scaleX(focused ? scale : 1f).scaleY(focused ? scale : 1f).setDuration(140).start());
    }

    /** A text field for the remote's on-screen keyboard: a ring when focused. */
    static Drawable field(Context c) {
        float r = dp(c, 14);
        GradientDrawable on = round(CARD_FOCUSED, r); on.setStroke(dp(c, 3), ACCENT);
        GradientDrawable off = round(CARD, r); off.setStroke(dp(c, 2), TRACK);
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[] {android.R.attr.state_focused}, on);
        bg.addState(new int[] {}, off);
        return bg;
    }

    static LinearLayout.LayoutParams wrap() { return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT); }
    static LinearLayout.LayoutParams wide() { return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); }
    static LinearLayout.LayoutParams gap(LinearLayout.LayoutParams p, Context c, int topDp) { p.topMargin = dp(c, topDp); return p; }

    private Ui() { }
}
