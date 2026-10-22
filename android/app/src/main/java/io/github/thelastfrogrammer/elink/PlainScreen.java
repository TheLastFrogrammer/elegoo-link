package io.github.thelastfrogrammer.elink;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** A scrolling page of cards in the app's light or dark look, for the simpler screens. */
abstract class PlainScreen extends Activity {
    protected int ink, muted, surface, grid, background, accent, warning;
    protected boolean dark;
    protected LinearLayout content;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        int appearance = getSharedPreferences("workshop-settings", MODE_PRIVATE).getInt("theme", 0);
        dark = appearance == 2 || appearance == 0 && (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        setTheme(dark ? R.style.WorkshopDark : R.style.WorkshopLight);
        ink = dark ? 0xffe6eef1 : 0xff17252c; muted = dark ? 0xff9fb3bb : 0xff5a6d76; background = dark ? 0xff0e1417 : 0xfff2f5f6;
        surface = dark ? 0xff182227 : Color.WHITE; grid = dark ? 0xff2a3a40 : 0xffe1e6e6; accent = dark ? 0xff5fd4c4 : 0xff00796b; warning = dark ? 0xffd95926 : 0xffc2410c;
        ScrollView scroll = new ScrollView(this); scroll.setBackgroundColor(background); scroll.setFitsSystemWindows(true);
        content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(dp(16), dp(16), dp(16), dp(24)); scroll.addView(content);
        setContentView(scroll);
    }
    protected TextView label(LinearLayout parent, String text, int size, int color, boolean bold) {
        TextView view = new TextView(this); view.setText(text); view.setTextSize(size); view.setTextColor(color); if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setPadding(0, dp(3), 0, dp(3)); parent.addView(view); return view;
    }
    protected LinearLayout card() {
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(dp(16), dp(12), dp(16), dp(12));
        GradientDrawable shape = new GradientDrawable(); shape.setColor(surface); shape.setCornerRadius(dp(20)); card.setBackground(shape);
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2); layout.topMargin = dp(12); content.addView(card, layout); return card;
    }
    protected Button button(LinearLayout parent, String text, Runnable action) {
        Button b = new A11y.DimButton(this); b.setText(text); b.setAllCaps(false); b.setMinHeight(dp(48)); b.setTextColor(accent);
        GradientDrawable shape = new GradientDrawable(); shape.setCornerRadius(dp(24)); shape.setColor(dark ? 0xff21343a : 0xffe2efed); b.setBackground(shape);
        b.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams at = parent.getOrientation() == LinearLayout.HORIZONTAL ? new LinearLayout.LayoutParams(0, -2, 1) : new LinearLayout.LayoutParams(-1, -2);
        at.topMargin = dp(6); if (parent.getOrientation() == LinearLayout.HORIZONTAL && parent.getChildCount() > 0) at.leftMargin = dp(8);
        parent.addView(b, at); return b;
    }
    protected LinearLayout row(LinearLayout parent) { LinearLayout r = new LinearLayout(this); r.setOrientation(LinearLayout.HORIZONTAL); parent.addView(r); return r; }
    protected int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
