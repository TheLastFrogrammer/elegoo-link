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

    int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }

    LinearLayout card(LinearLayout parent, String title) {
        LinearLayout card = new LinearLayout(activity); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable shape = new GradientDrawable(); shape.setColor(surface); shape.setCornerRadius(dp(20)); card.setBackground(shape);
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2); layout.topMargin = dp(12); parent.addView(card, layout);
        if (title != null) label(card, title, 17, ink, true);
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
        Button view = new Button(activity); view.setText(text); view.setAllCaps(false); view.setMinHeight(dp(48)); view.setPadding(dp(10), dp(8), dp(10), dp(8));
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
                TextView view = (TextView) super.getView(position, convert, group); view.setTextColor(ink); view.setSingleLine(false); view.setMaxLines(2); return view;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter); if (selected >= 0 && selected < values.size()) spinner.setSelection(selected);
        parent.addView(spinner, new LinearLayout.LayoutParams(-1, -2)); return spinner;
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
        label(content, title, 22, ink, true);
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
            view.setTextColor(ink); view.setSingleLine(false); view.setMaxLines(lines);
            if (colour == null) { view.setCompoundDrawables(null, null, null, null); view.setCompoundDrawablePadding(0); return view; }
            GradientDrawable dot = new GradientDrawable(); dot.setShape(GradientDrawable.OVAL); dot.setColor(Color.parseColor(colour)); dot.setStroke(Math.round(density), muted); dot.setSize(Math.round(14 * density), Math.round(14 * density));
            view.setCompoundDrawablesWithIntrinsicBounds(dot, null, null, null); view.setCompoundDrawablePadding(Math.round(8 * density));
            return view;
        }
    }
}
