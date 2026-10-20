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
import android.view.ViewGroup;
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

    /**
     * An on/off switch in the app's colours, drawn large: a 52×32 dp pill filled with the accent and a big light knob when on,
     * an outlined pill with a small grey knob when off.
     */
    static void switchColours(CompoundButton view, int accent, int track, boolean dark) {
        int[][] states = {new int[] {-android.R.attr.state_enabled}, new int[] {android.R.attr.state_checked}, new int[] {}};
        if (!(view instanceof Switch)) { view.setButtonTintList(new ColorStateList(new int[][] {states[0], states[2]}, new int[] {track, accent})); return; }
        Switch toggle = (Switch) view; float d = view.getResources().getDisplayMetrics().density;
        int outline = dark ? 0xff8fa3ab : 0xff74868e, knobOn = dark ? 0xff00201c : Color.WHITE;
        android.graphics.drawable.StateListDrawable tracks = new android.graphics.drawable.StateListDrawable();
        tracks.addState(states[0], pill(track, track, d)); tracks.addState(states[1], pill(accent, accent, d)); tracks.addState(states[2], pill(track, outline, d));
        android.graphics.drawable.StateListDrawable knobs = new android.graphics.drawable.StateListDrawable();
        knobs.addState(states[0], knob(outline, 16, d)); knobs.addState(states[1], knob(knobOn, 22, d)); knobs.addState(states[2], knob(outline, 16, d));
        toggle.setTrackDrawable(tracks); toggle.setThumbDrawable(knobs); toggle.setThumbTintList(null); toggle.setTrackTintList(null);
        toggle.setSwitchMinWidth(Math.round(52 * d)); toggle.setThumbTextPadding(0);
    }
    private static android.graphics.drawable.Drawable pill(int fill, int stroke, float d) {
        GradientDrawable shape = new GradientDrawable(); shape.setColor(fill); shape.setStroke(Math.round(2 * d), stroke); shape.setCornerRadius(16 * d);
        shape.setSize(Math.round(52 * d), Math.round(32 * d)); return shape;
    }
    /** A round knob in a 26×32 dp box (the switch is two boxes wide, the track's 52 dp): 24 dp across when on, 16 dp when off. */
    private static android.graphics.drawable.Drawable knob(int colour, int size, float d) {
        GradientDrawable dot = new GradientDrawable(); dot.setShape(GradientDrawable.OVAL); dot.setColor(colour);
        int across = Math.round((26 - size) / 2f * d), down = Math.round((32 - size) / 2f * d);
        GradientDrawable box = new GradientDrawable(); box.setColor(Color.TRANSPARENT); box.setSize(Math.round(26 * d), Math.round(32 * d));
        // Layer insets, not an InsetDrawable: those would count as the thumb's padding and change how far it travels.
        android.graphics.drawable.LayerDrawable layers = new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[] {box, dot});
        layers.setLayerInset(1, across, down, across, down); return layers;
    }
    /** A rounded search field with a magnifier at its start, as search boxes look elsewhere on the phone. */
    static void searchField(EditText field, int fill, int outline) {
        float d = field.getResources().getDisplayMetrics().density;
        GradientDrawable shape = new GradientDrawable(); shape.setCornerRadius(28 * d); shape.setColor(fill); shape.setStroke(Math.round(d), outline);
        field.setBackground(shape); field.setPadding(Math.round(16 * d), 0, Math.round(16 * d), 0); field.setMinHeight(Math.round(56 * d));
        android.graphics.drawable.Drawable lens = field.getContext().getDrawable(R.drawable.ic_search);
        if (lens == null) return;
        lens = lens.mutate(); lens.setTint(outline); int size = Math.round(24 * d); lens.setBounds(0, 0, size, size);
        field.setCompoundDrawablesRelative(lens, null, null, null); field.setCompoundDrawablePadding(Math.round(12 * d));
    }
    private static final String GRID_ROW = "grid-row";
    /** Cards two to a row in `grid`: the next row starts when the last one has two; an odd last card keeps half the width. */
    static void addToGrid(LinearLayout grid, View card, int gap) {
        // Only rows this made count: the grid may also hold a heading or a note before its cards.
        View last = grid.getChildCount() == 0 ? null : grid.getChildAt(grid.getChildCount() - 1);
        LinearLayout row = last instanceof LinearLayout && GRID_ROW.equals(last.getTag()) ? (LinearLayout) last : null;
        if (row == null || row.getChildCount() >= 2) {
            row = new LinearLayout(grid.getContext()); row.setOrientation(LinearLayout.HORIZONTAL); row.setWeightSum(2); row.setBaselineAligned(false); row.setTag(GRID_ROW);
            LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(-1, -2); at.topMargin = gap; grid.addView(row, at);
        }
        LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(0, -1, 1); if (row.getChildCount() > 0) cell.leftMargin = gap;
        row.addView(card, cell);
    }
    /**
     * A short choice (two to four short options) shown as a row of chips instead of a drop-down. The spinner stays as the
     * value (invisible, no size, still laid out so its own listeners fire as before); a chip only selects in it. Returns
     * false and leaves the spinner as it is when the options are too many or too long for one row.
     */
    static boolean chipsFor(Spinner spinner, int accent, int chipOff, int ink, boolean dark) {
        android.widget.SpinnerAdapter adapter = spinner.getAdapter(); ViewGroup parent = (ViewGroup) spinner.getParent();
        if (adapter == null || parent == null || adapter.getCount() < 2 || adapter.getCount() > 4) return false;
        int letters = 0; String[] names = new String[adapter.getCount()];
        for (int i = 0; i < names.length; i++) { names[i] = String.valueOf(adapter.getItem(i)); letters += names[i].length(); }
        if (letters > (names.length == 2 ? 44 : 36)) return false;
        float d = spinner.getResources().getDisplayMetrics().density;
        Runnable[] sync = new Runnable[1];
        // Also caught up while drawing: a pre-draw pass is not run by every renderer (screenshots), and a stale chip must never show.
        LinearLayout row = new LinearLayout(spinner.getContext()) {
            @Override protected void dispatchDraw(android.graphics.Canvas canvas) { if (sync[0] != null) sync[0].run(); super.dispatchDraw(canvas); }
        };
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button[] chips = new Button[names.length];
        CharSequence label = spinner.getContentDescription();
        for (int i = 0; i < names.length; i++) {
            Button chip = new A11y.DimButton(spinner.getContext()); chip.setText(names[i]); chip.setAllCaps(false); chip.setTextSize(14); chip.setMinHeight(Math.round(44 * d)); chip.setMinimumHeight(Math.round(44 * d));
            chip.setPadding(Math.round(8 * d), 0, Math.round(8 * d), 0); chip.setStateListAnimator(null);
            int index = i; chip.setOnClickListener(v -> spinner.setSelection(index));
            LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(0, -2, names[i].length() + 6); at.topMargin = Math.round(6 * d); if (i > 0) at.leftMargin = Math.round(8 * d);
            row.addView(chip, at); chips[i] = chip;
        }
        Runnable paint = () -> {
            int chosen = spinner.getSelectedItemPosition();
            for (int i = 0; i < chips.length; i++) {
                boolean on = i == chosen;
                GradientDrawable shape = new GradientDrawable(); shape.setCornerRadius(22 * d);
                if (on) shape.setColor(accent); else { shape.setColor(Color.TRANSPARENT); shape.setStroke(Math.round(d), dark ? 0xff5a6d76 : 0xff9fb3bb); }
                chips[i].setBackground(shape); chips[i].setTextColor(on ? (dark ? 0xff00201c : Color.WHITE) : ink);
                chips[i].setContentDescription((label == null ? "" : label + ": ") + names[i]); A11y.state(chips[i], on ? "Selected" : "Not selected");
            }
        };
        // Anything may change the selection (a saved value restored, a dialog): the chips follow it before each frame.
        int[] last = {-2};
        sync[0] = () -> { if (spinner.getSelectedItemPosition() != last[0]) { last[0] = spinner.getSelectedItemPosition(); paint.run(); } };
        row.getViewTreeObserver().addOnPreDrawListener(() -> { sync[0].run(); return true; });
        paint.run(); last[0] = spinner.getSelectedItemPosition();
        int at = parent.indexOfChild(spinner); parent.addView(row, at, new LinearLayout.LayoutParams(-1, -2));
        spinner.setVisibility(View.INVISIBLE); ViewGroup.LayoutParams none = spinner.getLayoutParams(); none.width = 0; none.height = 0; spinner.setLayoutParams(none);
        spinner.setMinimumHeight(0); spinner.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return true;
    }
    /** A slider with its caption on the left and its value on the right above it (value[0] receives the value's view), drawn with a thick rounded track. */
    static SeekBar slider(LinearLayout parent, String caption, TextView[] value, int accent, int track, int ink, int muted) {
        android.content.Context context = parent.getContext(); float d = context.getResources().getDisplayMetrics().density;
        LinearLayout head = new LinearLayout(context); head.setOrientation(LinearLayout.HORIZONTAL); head.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView name = new TextView(context); name.setText(caption); name.setTextSize(15); name.setTextColor(muted); head.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        TextView shown = new TextView(context); shown.setTextSize(16); shown.setTextColor(ink); shown.setTypeface(Typeface.DEFAULT, Typeface.BOLD); head.addView(shown);
        LinearLayout.LayoutParams headAt = new LinearLayout.LayoutParams(-1, -2); headAt.topMargin = Math.round(12 * d); parent.addView(head, headAt);
        SeekBar bar = new SeekBar(context); bar.setMinimumHeight(Math.round(48 * d)); bar.setPadding(Math.round(14 * d), 0, Math.round(14 * d), 0);
        styleSlider(bar, accent, track);
        parent.addView(bar, new LinearLayout.LayoutParams(-1, Math.round(48 * d)));
        A11y.labelFor(name, bar); value[0] = shown; return bar;
    }
    /** A slider's thick rounded track (accent up to the value) and round knob. */
    static void styleSlider(SeekBar bar, int accent, int track) {
        float d = bar.getResources().getDisplayMetrics().density;
        GradientDrawable back = new GradientDrawable(); back.setColor(track); back.setCornerRadius(4 * d); back.setSize(-1, Math.round(8 * d));
        GradientDrawable fill = new GradientDrawable(); fill.setColor(accent); fill.setCornerRadius(4 * d); fill.setSize(-1, Math.round(8 * d));
        android.graphics.drawable.LayerDrawable lines = new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[] {back,
            new android.graphics.drawable.ClipDrawable(fill, android.view.Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL)});
        lines.setId(0, android.R.id.background); lines.setId(1, android.R.id.progress);
        bar.setProgressDrawable(lines);
        GradientDrawable knob = new GradientDrawable(); knob.setShape(GradientDrawable.OVAL); knob.setColor(accent); knob.setSize(Math.round(24 * d), Math.round(24 * d));
        bar.setThumb(knob); bar.setSplitTrack(false);
    }
    static void switchColours(CompoundButton view, int accent, boolean dark) { switchColours(view, accent, dark ? 0xff2a3a40 : 0xffdbe6e6, dark); }
    /** A back control as a title bar: an arrow before the words (the place it goes back to, or the page's own title). */
    static void backArrow(TextView view, int color) {
        android.graphics.drawable.Drawable arrow = view.getContext().getDrawable(R.drawable.ic_arrow_back);
        if (arrow == null) return;
        arrow = arrow.mutate(); arrow.setTint(color);
        int size = Math.round(24 * view.getResources().getDisplayMetrics().density); arrow.setBounds(0, 0, size, size);
        view.setCompoundDrawablesRelative(arrow, null, null, null); view.setCompoundDrawablePadding(Math.round(16 * view.getResources().getDisplayMetrics().density));
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
        GradientDrawable shape = new GradientDrawable(); shape.setCornerRadius(dp(24));
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
