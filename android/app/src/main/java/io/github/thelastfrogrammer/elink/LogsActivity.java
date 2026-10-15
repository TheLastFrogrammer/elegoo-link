package io.github.thelastfrogrammer.elink;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The app's log, readable on the phone: one section per area (Camera, Connection, Printer commands, Messages shown, Slicer,
 * Live toolpath, …), newest first, with a search box; Copy and Share hand the shown entries (or everything) on, Clear empties
 * it. The log holds no access codes, PINs, addresses or serial numbers (see Diagnostics).
 */
public final class LogsActivity extends Activity {
    /** Intent extra: open on this area. */
    public static final String EXTRA_AREA = "area";
    private static final String ALL = "All";
    private boolean dark;
    private int ink, muted, surface, accent, chipOff;
    private String area = ALL, query = "";
    private LinearLayout chips; private TextView list, count;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        int appearance = getSharedPreferences("workshop-settings", MODE_PRIVATE).getInt("theme", 0);
        dark = appearance == 2 || appearance == 0 && (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        setTheme(dark ? R.style.WorkshopDark : R.style.WorkshopLight);
        ink = dark ? 0xffe6eef1 : 0xff17252c; muted = dark ? 0xff9fb3bb : 0xff5a6d76; surface = dark ? 0xff182227 : Color.WHITE;
        accent = dark ? 0xff5fd4c4 : 0xff00796b; chipOff = dark ? 0xff21343a : 0xffe2efed;
        Diagnostics.init(getFilesDir());
        if (getIntent().getStringExtra(EXTRA_AREA) != null) area = getIntent().getStringExtra(EXTRA_AREA);

        ScrollView scroll = new ScrollView(this); scroll.setBackgroundColor(dark ? 0xff0e1417 : 0xfff2f5f6); scroll.setFitsSystemWindows(true);
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(dp(16), dp(16), dp(16), dp(24)); scroll.addView(content);
        TextView title = text(content, "Logs", 24, ink, true); A11y.heading(title);
        text(content, "What the app noted while it worked, newest first. No access codes, PINs, addresses or serial numbers are kept. Share it when asking for help.", 13, muted, false);

        HorizontalScrollView chipScroll = new HorizontalScrollView(this); chipScroll.setHorizontalScrollBarEnabled(false);
        chips = new LinearLayout(this); chips.setOrientation(LinearLayout.HORIZONTAL); chipScroll.addView(chips);
        LinearLayout.LayoutParams chipsAt = new LinearLayout.LayoutParams(-1, -2); chipsAt.topMargin = dp(8); content.addView(chipScroll, chipsAt);

        EditText search = new EditText(this); search.setHint("Search the log"); search.setSingleLine(true); search.setTextColor(ink); search.setHintTextColor(muted);
        search.setBackgroundTintList(ColorStateList.valueOf(accent)); search.setMinHeight(dp(48)); content.addView(search, new LinearLayout.LayoutParams(-1, -2));
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { } public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(Editable s) { query = s.toString().trim().toLowerCase(Locale.ROOT); show(); }
        });

        LinearLayout actions = new LinearLayout(this); actions.setOrientation(LinearLayout.HORIZONTAL); content.addView(actions);
        action(actions, "Copy", () -> {
            ClipboardManager clipboard = getSystemService(ClipboardManager.class);
            if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("Link Workshop log", shownText()));
            android.widget.Toast.makeText(this, "Copied the shown entries.", android.widget.Toast.LENGTH_SHORT).show();
        });
        action(actions, "Share…", () -> startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Link Workshop log").putExtra(Intent.EXTRA_TEXT, header() + "\n\n" + shownText()), "Share the log")));
        action(actions, "Clear…", () -> new AlertDialog.Builder(this).setTitle("Clear the whole log?").setMessage("Every section is emptied. This cannot be undone.")
            .setNegativeButton("Cancel", null).setPositiveButton("Clear", (d, w) -> { Diagnostics.clear(); area = ALL; show(); }).show());

        count = text(content, "", 12, muted, false);
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable plate = new GradientDrawable(); plate.setColor(surface); plate.setCornerRadius(dp(16)); card.setBackground(plate);
        LinearLayout.LayoutParams cardAt = new LinearLayout.LayoutParams(-1, -2); cardAt.topMargin = dp(6); content.addView(card, cardAt);
        list = new TextView(this); list.setTextSize(12); list.setTextColor(ink); list.setTypeface(Typeface.MONOSPACE); list.setTextIsSelectable(true); list.setLineSpacing(0, 1.15f);
        card.addView(list);
        setContentView(scroll);
        show();
    }

    /** The entries to show for the chosen area and search, newest first, each as "area · time  text" when showing all. */
    static List<String> select(List<String> areas, java.util.function.Function<String, List<String>> entries, String area, String query) {
        List<String[]> rows = new ArrayList<>();
        for (String a : areas) {
            if (!ALL.equals(area) && !a.equals(area)) continue;
            for (String line : entries.apply(a)) rows.add(new String[] {a, line});
        }
        // Entries start with "yyyy-MM-dd HH:mm:ss", so a stable sort on that text puts all areas in time order.
        Collections.sort(rows, (x, y) -> y[1].compareTo(x[1]));
        List<String> out = new ArrayList<>();
        for (String[] row : rows) {
            String shown = ALL.equals(area) ? row[1].replaceFirst("^(\\S+ \\S+)  ", "$1  [" + row[0] + "] ") : row[1];
            if (query.isEmpty() || shown.toLowerCase(Locale.ROOT).contains(query)) out.add(shown);
        }
        return out;
    }

    private void show() {
        List<String> areas = Diagnostics.areas();
        if (!ALL.equals(area) && !areas.contains(area)) area = ALL;
        chips.removeAllViews(); chip(ALL, totalCount(areas));
        for (String a : areas) chip(a, Diagnostics.entries(a).size());
        List<String> rows = select(areas, Diagnostics::entries, area, query);
        count.setText(rows.size() + (rows.size() == 1 ? " entry" : " entries") + (ALL.equals(area) ? "" : " in " + area) + (query.isEmpty() ? "" : " matching “" + query + "”"));
        list.setText(rows.isEmpty() ? (areas.isEmpty() ? "Nothing logged yet." : "No entries match.") : String.join("\n", rows));
    }
    private int totalCount(List<String> areas) { int n = 0; for (String a : areas) n += Diagnostics.entries(a).size(); return n; }
    private String shownText() { List<String> rows = select(Diagnostics.areas(), Diagnostics::entries, area, query); return (ALL.equals(area) ? "All areas" : area) + "\n" + String.join("\n", rows); }
    private String header() {
        String version = ""; try { version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) { }
        return "Link Workshop " + version + " · Android " + android.os.Build.VERSION.RELEASE + " · " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL;
    }

    private void chip(String name, int entries) {
        boolean on = name.equals(area);
        TextView chip = new TextView(this); chip.setText(name + " · " + entries); chip.setTextSize(13); chip.setGravity(Gravity.CENTER);
        chip.setTextColor(on ? (dark ? 0xff0e1417 : Color.WHITE) : accent); chip.setTypeface(Typeface.DEFAULT, on ? Typeface.BOLD : Typeface.NORMAL);
        chip.setPadding(dp(14), 0, dp(14), 0); chip.setMinHeight(dp(48));
        GradientDrawable shape = new GradientDrawable(); shape.setColor(on ? accent : chipOff); shape.setCornerRadius(dp(20));
        chip.setBackground(new android.graphics.drawable.InsetDrawable(shape, 0, dp(6), 0, dp(6)));
        chip.setContentDescription(name + ", " + entries + " entries" + (on ? ", shown" : ""));
        chip.setOnClickListener(v -> { area = name; show(); });
        LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(-2, -2); at.rightMargin = dp(8); chips.addView(chip, at);
    }
    private void action(LinearLayout row, String label, Runnable run) {
        Button button = new A11y.DimButton(this); button.setText(label); button.setAllCaps(false); button.setMinHeight(dp(48)); button.setTextColor(accent);
        GradientDrawable shape = new GradientDrawable(); shape.setColor(chipOff); shape.setCornerRadius(dp(12));
        button.setBackground(new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(dark ? 0x4463d5c7 : 0x33006b65), shape, null));
        button.setOnClickListener(v -> run.run());
        LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(0, -2, 1); at.topMargin = dp(8); if (row.getChildCount() > 0) at.leftMargin = dp(8); row.addView(button, at);
    }
    private TextView text(LinearLayout parent, String value, int size, int color, boolean bold) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color); if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setPadding(0, dp(4), 0, dp(4)); parent.addView(view, new LinearLayout.LayoutParams(-1, -2)); return view;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
