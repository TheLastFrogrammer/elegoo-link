package io.github.thelastfrogrammer.elink;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import android.widget.*;
import java.util.List;

/** The app's colours and the card, label, button and spinner styles, for screens built in code. */
final class WorkshopUi {
    final Activity activity;
    final boolean dark;
    final int ink, muted, teal, background, surface, button, error;

    /** Applies the app's theme (call before super.onCreate) and picks the matching colours. */
    WorkshopUi(Activity activity) {
        this.activity = activity;
        SharedPreferences settings = activity.getSharedPreferences("workshop-settings", Activity.MODE_PRIVATE);
        int appearance = settings.getInt("theme", 0);
        dark = appearance == 2 || appearance == 0 && (activity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        activity.setTheme(dark ? R.style.WorkshopDark : R.style.WorkshopLight);
        ink = dark ? 0xffe6eef1 : 0xff17252c; muted = dark ? 0xff9fb3bb : 0xff5a6d76; teal = dark ? 0xff5fd4c4 : 0xff00796b;
        background = dark ? 0xff0e1417 : 0xfff2f5f6; surface = dark ? 0xff182227 : Color.WHITE; button = dark ? 0xff21343a : 0xffe2efed;
        error = dark ? 0xffffb4ab : 0xffba1a1a;
    }

    /** Plain name of a filament colour ("#D02828" is "red"), for speech and for text beside a colour dot; null for no or an unreadable colour. */
    static String colourName(String hex) {
        if (hex == null) return null;
        String h = hex.trim(); if (h.startsWith("#")) h = h.substring(1);
        if (h.length() == 8) h = h.substring(2); // #AARRGGBB: ignore the alpha
        if (h.length() != 6) return null;
        int rgb; try { rgb = Integer.parseInt(h, 16); } catch (NumberFormatException e) { return null; }
        float r = ((rgb >> 16) & 255) / 255f, g = ((rgb >> 8) & 255) / 255f, b = (rgb & 255) / 255f;
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b)), d = max - min, hue = 0;
        if (d > 0) hue = max == r ? 60 * (((g - b) / d + 6) % 6) : max == g ? 60 * ((b - r) / d + 2) : 60 * ((r - g) / d + 4);
        return nameOf(hue, max == 0 ? 0 : d / max, max);
    }
    /** hue 0-360, saturation and value 0-1 to a name; pure arithmetic so it needs no Android classes beyond the caller's conversion. */
    static String nameOf(float hue, float sat, float val) {
        if (val < 0.2f) return "black";
        if (sat < 0.12f) return val > 0.88f ? "white" : val > 0.62f ? "light grey" : val > 0.35f ? "grey" : "dark grey";
        if (sat < 0.3f && val > 0.8f) return hue >= 15 && hue < 70 ? "cream" : "white";
        String shade = val < 0.55f ? "dark " : sat < 0.45f && val > 0.7f ? "light " : "";
        String base;
        if (hue < 15 || hue >= 345) base = "red";
        else if (hue < 40) base = val < 0.6f ? "brown" : "orange";
        else if (hue < 70) base = "yellow";
        else if (hue < 160) base = "green";
        else if (hue < 200) base = val > 0.8f ? "cyan" : "teal";
        else if (hue < 255) base = "blue";
        else if (hue < 305) base = "purple";
        else base = "pink";
        if (base.equals("brown") || base.equals("pink") && val > 0.75f && sat < 0.5f) shade = "";
        return shade + base;
    }

    int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }

    LinearLayout card(LinearLayout parent, String title) {
        LinearLayout card = new LinearLayout(activity); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable shape = new GradientDrawable(); shape.setColor(surface); shape.setCornerRadius(dp(20)); card.setBackground(shape);
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2); layout.topMargin = dp(12); parent.addView(card, layout);
        if (title != null) A11y.heading(label(card, title, 17, ink, true));
        return card;
    }

    TextView label(LinearLayout parent, String text, int size, int color, boolean bold) {
        TextView view = new TextView(activity); view.setText(text); view.setTextSize(size); view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setPadding(0, dp(5), 0, dp(7)); parent.addView(view, new LinearLayout.LayoutParams(-1, -2)); return view;
    }

    LinearLayout row(LinearLayout parent) {
        LinearLayout row = new LinearLayout(activity); row.setOrientation(LinearLayout.HORIZONTAL); parent.addView(row, new LinearLayout.LayoutParams(-1, -2)); return row;
    }

    Button button(LinearLayout parent, String text, Runnable action, boolean primary) {
        Button view = styled(text, action, primary); LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2); layout.topMargin = dp(6); parent.addView(view, layout); return view;
    }

    Button rowButton(LinearLayout row, String text, Runnable action, boolean primary) {
        Button view = styled(text, action, primary); LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(0, -2, 1); layout.topMargin = dp(6);
        if (row.getChildCount() > 0) layout.leftMargin = dp(8); row.addView(view, layout); return view;
    }

    Button styled(String text, Runnable action, boolean primary) {
        Button view = new A11y.DimButton(activity); view.setText(text); view.setAllCaps(false); view.setMinHeight(dp(48)); view.setPadding(dp(10), dp(8), dp(10), dp(8));
        ColorStateList textColor = new ColorStateList(new int[][] {new int[] {-android.R.attr.state_enabled}, new int[] {}}, new int[] {muted, primary ? (dark ? 0xff00201c : Color.WHITE) : teal});
        GradientDrawable shape = new GradientDrawable(); shape.setCornerRadius(dp(12));
        shape.setColor(primary ? new ColorStateList(new int[][] {new int[] {-android.R.attr.state_enabled}, new int[] {}}, new int[] {button, teal}) : ColorStateList.valueOf(button));
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(dark ? 0x4463d5c7 : 0x33006b65), shape, null));
        view.setTextColor(textColor); view.setOnClickListener(v -> action.run()); return view;
    }

    Spinner spinner(LinearLayout parent, List<String> values, int selected) {
        Spinner spinner = new Spinner(activity); spinner.setBackgroundTintList(ColorStateList.valueOf(teal)); spinner.setMinimumHeight(dp(48));
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(activity, android.R.layout.simple_spinner_item, values) {
            @Override public View getView(int position, View convert, android.view.ViewGroup group) {
                TextView view = (TextView) super.getView(position, convert, group); view.setTextColor(ink); view.setSingleLine(false); view.setMaxLines(Integer.MAX_VALUE); return view;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter); if (selected >= 0 && selected < values.size()) spinner.setSelection(selected);
        parent.addView(spinner, new LinearLayout.LayoutParams(-1, -2)); A11y.nameFromCaption(spinner, parent); return spinner;
    }

    EditText input(LinearLayout parent, String hint) {
        EditText input = new EditText(activity); input.setHint(hint); input.setHintTextColor(muted); input.setTextColor(ink); input.setSingleLine(true);
        input.setBackgroundTintList(ColorStateList.valueOf(teal)); parent.addView(input, new LinearLayout.LayoutParams(-1, dp(52))); return input;
    }

    /** A vertical page in a scroll view with the app's background, set as the activity's content. */
    LinearLayout page(String title, String subtitle) {
        ScrollView scroll = new ScrollView(activity); scroll.setBackgroundColor(background); scroll.setFitsSystemWindows(true);
        LinearLayout content = new LinearLayout(activity); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(dp(16), dp(16), dp(16), dp(24));
        scroll.addView(content); activity.setContentView(scroll);
        A11y.heading(label(content, title, 22, ink, true));
        if (subtitle != null) label(content, subtitle, 13, muted, false);
        return content;
    }

    /** A spinner adapter whose rows can start with a filament colour dot; the text never shows the hex. Shared by the Slice screen and Print setup. */
    static final class DottedAdapter extends ArrayAdapter<String> {
        private final List<String> colours; private final int ink, muted; private final float density;
        DottedAdapter(android.content.Context context, int ink, int muted, List<String> values, List<String> colours) {
            super(context, android.R.layout.simple_spinner_item, values); this.colours = colours; this.ink = ink; this.muted = muted; density = context.getResources().getDisplayMetrics().density;
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        }
        /** The row's colour as #RRGGBB, or null for a row without a colour. */
        String colourAt(int position) { return colours.get(position); }
        @Override public View getView(int position, View convert, android.view.ViewGroup parent) { return dotted((TextView) super.getView(position, convert, parent), colours.get(position), 2); }
        @Override public View getDropDownView(int position, View convert, android.view.ViewGroup parent) { return dotted((TextView) super.getDropDownView(position, convert, parent), colours.get(position), 3); }
        private TextView dotted(TextView view, String colour, int lines) {
            view.setTextColor(ink); view.setSingleLine(false); view.setMaxLines(Integer.MAX_VALUE);
            if (colour == null) { view.setCompoundDrawables(null, null, null, null); view.setCompoundDrawablePadding(0); return view; }
            GradientDrawable dot = new GradientDrawable(); dot.setShape(GradientDrawable.OVAL); dot.setColor(Color.parseColor(colour)); dot.setStroke(Math.round(density), muted); dot.setSize(Math.round(14 * density), Math.round(14 * density));
            view.setCompoundDrawablesWithIntrinsicBounds(dot, null, null, null); view.setCompoundDrawablePadding(Math.round(8 * density));
            return view;
        }
    }
}
