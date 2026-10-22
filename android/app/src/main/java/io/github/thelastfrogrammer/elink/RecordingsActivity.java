package io.github.thelastfrogrammer.elink;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import org.json.JSONObject;

/** Recorded prints: a list, and per print a summary, charts, a data table and CSV export. */
public final class RecordingsActivity extends Activity {
    public static final String EXTRA_META = "meta", EXTRA_JOURNAL = "journal";
    private static final int EXPORT = 1, VIDEO = 2, ADD_GCODE = 3;
    /** The timelapse just made, waiting for a place to save it. */
    private File madeVideo;
    private boolean dark;
    private int ink, muted, background, surface, grid;
    /** Validated categorical slots 1-3 (blue, orange, aqua), stepped per mode; see the dataviz reference palette. */
    private int blue, orange, aqua;
    private LinearLayout content;
    private PrintRecorder.Recording shown;
    /** The shown print's warm-up phases {from s, to s, name}, shaded on its time charts. */
    private List<Object[]> warmUp = new ArrayList<>();
    /** The shown print's camera pictures (main set), their times into the print (s), and small copies decoded as touched. */
    private List<File> chartPictures = new ArrayList<>(); private double[] pictureTimes = new double[0];
    private final Map<Integer, android.graphics.Bitmap> pictureThumbs = new HashMap<>();

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        int appearance = getSharedPreferences("workshop-settings", MODE_PRIVATE).getInt("theme", 0);
        dark = appearance == 2 || appearance == 0 && (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        setTheme(dark ? R.style.WorkshopDark : R.style.WorkshopLight);
        ink = dark ? 0xffe6eef1 : 0xff17252c; muted = dark ? 0xff9fb3bb : 0xff5a6d76; background = dark ? 0xff0e1417 : 0xfff2f5f6;
        surface = dark ? 0xff182227 : Color.WHITE; grid = dark ? 0xff2a3a40 : 0xffe1e6e6;
        blue = dark ? 0xff3987e5 : 0xff2a78d6; orange = dark ? 0xffd95926 : 0xffeb6834; aqua = dark ? 0xff199e70 : 0xff149a69;
        ScrollView scroll = new ScrollView(this); scroll.setBackgroundColor(background); scroll.setFitsSystemWindows(true);
        content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(dp(16), dp(16), dp(16), dp(24)); scroll.addView(content);
        setContentView(scroll);
        String meta = getIntent().getStringExtra(EXTRA_META);
        if (meta == null) showList(); else showRecording(new File(meta));
        if (meta != null && getIntent().getBooleanExtra(Showcase.EXTRA, false) && Showcase.stage == Showcase.RECORDING) showcase(scroll);
    }

    // ---- Showcase tour: glides through this print's graphs with captions, then the tour ends ----
    private Showcase.Script showcaseScript;
    private void showcase(ScrollView scroll) {
        Showcase.Script script = new Showcase.Script(this); showcaseScript = script;
        script.then(700, () -> Showcase.caption(this, "Every print the app watches is recorded"))
            .until(8000, () -> Showcase.find(content, "Filament used by") != null || Showcase.find(content, "The print's file") != null || charts().size() >= 6)
            .then(2600, () -> { View pictures = Showcase.find(content, "Camera pictures"); if (pictures != null) { Showcase.glideTo(scroll, pictures, 1800); Showcase.caption(this, "Camera pictures through the print, made into a timelapse on the phone"); } })
            .then(4600, () -> { View chart = chart(2); if (chart != null) { Showcase.glideTo(scroll, chart, 2000); Showcase.caption(this, "Temperatures, fans, progress and layers over time"); } })
            .then(4800, () -> { View trays = Showcase.find(content, "Filament over time"); if (trays != null) { Showcase.glideTo(scroll, trays, 1800); Showcase.caption(this, "Which filament was loaded, and when"); } })
            .then(4400, () -> { View rate = chart(4); if (rate != null) { Showcase.glideTo(scroll, rate, 1800); Showcase.caption(this, "How fast each filament was laid down"); } })
            .then(4600, () -> { View used = chart(5); if (used != null) { Showcase.glideTo(scroll, used, 1800); Showcase.caption(this, "How much of each filament was used"); } })
            .then(5000, () -> Showcase.caption(this, null))
            .then(500, () -> { Showcase.stage = Showcase.END; finish(); overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out); });
        script.run(null);
    }
    private List<View> charts() { List<View> out = new ArrayList<>(); collectCharts(content, out); return out; }
    private static void collectCharts(View view, List<View> out) {
        if (view instanceof ChartView && view.getVisibility() == View.VISIBLE) out.add(view);
        else if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) collectCharts(((ViewGroup) view).getChildAt(i), out);
    }
    /** The nth graph on the page (progress, layer, temperatures, fans, laying down, used so far), or null. */
    private View chart(int n) { List<View> all = charts(); return n < all.size() ? all.get(n) : null; }
    @Override protected void onDestroy() { if (showcaseScript != null) showcaseScript.cancel(); super.onDestroy(); }

    static File directory(android.content.Context context) { return new File(context.getFilesDir(), "recordings"); }

    // ---- Plain-language text (pure, tested on the JVM) ----

    /** A span such as "45 min", "3 h 20 min" or "under 1 min". */
    static String duration(long millis) { return ChartView.spanWords(millis / 1000.0); }

    /** The result of a print, in words a hobbyist can act on. */
    static String outcome(String outcome) {
        switch (outcome) {
            case "complete": return "Completed";
            case "stopped": return "Stopped";
            case "printing": return "Unfinished";
            case "interrupted": return "Replaced by another print";
            case "ended": return "Ended (printer went idle)";
            default: return "Unknown";
        }
    }

    /** Date, weekday and clock time, for example "Tue 6 Oct, 14:05". */
    static String when(long millis, Locale locale, TimeZone zone) {
        SimpleDateFormat format = new SimpleDateFormat("EEE d MMM, HH:mm", locale); format.setTimeZone(zone);
        return format.format(new Date(millis));
    }

    /** A stretch without readings longer than this means the app was not watching (closed, or the phone asleep). */
    static final long GAP_MS = 3 * 60_000L;
    /**
     * How fully the app watched the print: stretches with no readings, and pictures against what the interval asked for.
     * "" when it watched throughout and the pictures kept up.
     */
    static String coverage(List<double[]> rows, PrintRecorder.Recording r) {
        int time = PrintRecorder.column("time_ms");
        int gaps = 0; long missed = 0;
        for (int i = 1; i < rows.size(); i++) {
            double a = rows.get(i - 1)[time], b = rows.get(i)[time];
            if (Double.isNaN(a) || Double.isNaN(b)) continue;
            long gap = (long) (b - a);
            if (gap > GAP_MS) { gaps++; missed += gap; }
        }
        List<String> parts = new ArrayList<>();
        if (gaps > 0) parts.add("The app was not watching " + (gaps == 1 ? "once" : gaps + " times") + ", " + duration(missed) + " in all (the app was closed or the phone asleep), so readings there are missing.");
        if (r.pictureLayers == 0 && r.pictureMinutes > 0 && r.duration() > 0) {
            long expected = r.duration() / (r.pictureMinutes * 60_000L);
            if (expected >= 2 && r.pictures < expected * 0.6)
                parts.add("Pictures: " + r.pictures + " of about " + expected + " (every " + r.pictureMinutes + " min). Through the cloud they also come with the app closed, while it watches the printer through the cloud in the background (Settings › Printer connection); the camera log says why any were missed.");
        }
        return String.join("\n", parts);
    }

    /** One list row's second line: when, how long and the result. */
    static String rowSummary(PrintRecorder.Recording recording, Locale locale, TimeZone zone) {
        return when(recording.start, locale, zone) + " · " + duration(recording.duration()) + " · " + outcome(recording.outcome);
    }

    /** The last reading of a print in words: when, progress and the temperatures with their targets. Empty when there are no readings. */
    static String lastReading(List<double[]> rows) {
        if (rows.isEmpty()) return "";
        double[] row = rows.get(rows.size() - 1);
        List<String> parts = new ArrayList<>();
        parts.add("Last reading " + ChartView.clockOfDay((long) ChartView.localSeconds((long) row[PrintRecorder.column("time_ms")])));
        addValue(parts, "progress", row[PrintRecorder.column("progress")], " %", Double.NaN);
        addValue(parts, "nozzle", row[PrintRecorder.column("nozzle_c")], " °C", row[PrintRecorder.column("nozzle_target_c")]);
        addValue(parts, "bed", row[PrintRecorder.column("bed_c")], " °C", row[PrintRecorder.column("bed_target_c")]);
        addValue(parts, "chamber", row[PrintRecorder.column("chamber_c")], " °C", Double.NaN);
        return String.join(" · ", parts);
    }
    private static void addValue(List<String> parts, String name, double value, String unit, double target) {
        if (Double.isNaN(value)) return;
        String text = name + " " + ChartView.format(value) + unit;
        if (!Double.isNaN(target)) text += " (target " + ChartView.format(target) + unit + ")";
        parts.add(text);
    }

    // ---- The journal: stars, how it came out, what went wrong, notes and tags; saved as it changes ----
    private PrintJournal editing; private File editingMeta;
    private android.widget.EditText notesField, tagsField;
    private void journalCard(PrintRecorder.Recording recording) {
        editing = recording.journal; editingMeta = recording.meta;
        LinearLayout outer = card();
        // Folded to one line (stars, result, problems) until tapped; open from the start when the journal was asked for.
        LinearLayout head = new LinearLayout(this); head.setOrientation(LinearLayout.HORIZONTAL); head.setGravity(android.view.Gravity.CENTER_VERTICAL); head.setMinimumHeight(dp(48)); outer.addView(head);
        LinearLayout headWords = new LinearLayout(this); headWords.setOrientation(LinearLayout.VERTICAL); head.addView(headWords, new LinearLayout.LayoutParams(0, -2, 1));
        A11y.heading(label(headWords, "Journal", 17, ink, true));
        TextView summary = label(headWords, "", 13, muted, false);
        TextView chevron = label(head, "", 20, muted, false);
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); outer.addView(card);
        boolean[] open = {getIntent().getBooleanExtra(EXTRA_JOURNAL, false) || Showcase.running()};
        Runnable fold = () -> {
            card.setVisibility(open[0] ? View.VISIBLE : View.GONE); summary.setVisibility(open[0] ? View.GONE : View.VISIBLE); chevron.setText(open[0] ? "▴" : "▾");
            summary.setText(journalSummary(editing)); A11y.state(head, open[0] ? "Expanded" : "Collapsed");
        };
        head.setClickable(true); head.setFocusable(true); head.setOnClickListener(v -> { open[0] = !open[0]; if (!open[0]) saveJournal(); fold.run(); });
        A11y.clickLabel(head, "Show or hide the journal");
        fold.run();
        int accent = dark ? 0xff5fd4c4 : 0xff00796b;
        LinearLayout stars = new LinearLayout(this); stars.setOrientation(LinearLayout.HORIZONTAL); card.addView(stars);
        TextView[] star = new TextView[5];
        Runnable showStars = () -> { for (int i = 0; i < 5; i++) { star[i].setText(i < editing.stars ? "★" : "☆"); A11y.state(star[i], i < editing.stars ? "On" : "Off"); } };
        for (int i = 0; i < 5; i++) {
            int value = i + 1; star[i] = new TextView(this); star[i].setTextSize(30); star[i].setTextColor(accent); star[i].setGravity(android.view.Gravity.CENTER);
            star[i].setMinWidth(dp(48)); star[i].setMinHeight(dp(48)); star[i].setContentDescription(value + (value == 1 ? " star" : " stars"));
            star[i].setOnClickListener(v -> { editing.stars = editing.stars == value ? 0 : value; showStars.run(); saveJournal(); });
            stars.addView(star[i]);
        }
        showStars.run();
        LinearLayout results = new LinearLayout(this); results.setOrientation(LinearLayout.HORIZONTAL); card.addView(results);
        List<Button> resultButtons = new ArrayList<>();
        Runnable showResult = () -> { for (int i = 0; i < resultButtons.size(); i++) { boolean on = PrintJournal.RESULTS[i][0].equals(editing.result);
            resultButtons.get(i).setTextColor(on ? (dark ? 0xff0e1417 : Color.WHITE) : accent);
            ((android.graphics.drawable.RippleDrawable) resultButtons.get(i).getBackground()).getDrawable(0).setTint(on ? accent : (dark ? 0xff21343a : 0xffe2efed)); A11y.state(resultButtons.get(i), on ? "Chosen" : ""); } };
        for (String[] r : PrintJournal.RESULTS) {
            action(results, r[1], () -> { editing.result = editing.result.equals(r[0]) ? "" : r[0]; showResult.run(); saveJournal(); });
            resultButtons.add((Button) results.getChildAt(results.getChildCount() - 1));
        }
        showResult.run();
        LinearLayout problemRow = new LinearLayout(this); problemRow.setOrientation(LinearLayout.HORIZONTAL); card.addView(problemRow);
        TextView problems = label(card, "", 13, ink, false);
        Runnable showProblems = () -> { List<String> names = new ArrayList<>(); for (String id : editing.reasons) names.add(PrintJournal.label(PrintJournal.REASONS, id));
            problems.setText(names.isEmpty() ? "No problems noted." : "Problems: " + String.join(", ", names)); };
        action(problemRow, "What went wrong…", () -> {
            String[] labels = new String[PrintJournal.REASONS.length]; boolean[] checked = new boolean[labels.length];
            for (int i = 0; i < labels.length; i++) { labels[i] = PrintJournal.REASONS[i][1]; checked[i] = editing.reasons.contains(PrintJournal.REASONS[i][0]); }
            new android.app.AlertDialog.Builder(this).setTitle("What went wrong?").setMultiChoiceItems(labels, checked, (d, which, on) -> checked[which] = on)
                .setNegativeButton("Cancel", null).setPositiveButton("Done", (d, w) -> {
                    editing.reasons.clear(); for (int i = 0; i < labels.length; i++) if (checked[i]) editing.reasons.add(PrintJournal.REASONS[i][0]);
                    showProblems.run(); saveJournal(); }).show();
        });
        showProblems.run();
        notesField = new android.widget.EditText(this); notesField.setHint("Notes: settings tried, what to change next time…"); notesField.setText(editing.notes);
        notesField.setTextColor(ink); notesField.setHintTextColor(muted); notesField.setMinHeight(dp(48)); notesField.setMinLines(2); card.addView(notesField, new LinearLayout.LayoutParams(-1, -2));
        tagsField = new android.widget.EditText(this); tagsField.setHint("Tags, separated by commas: petg, functional, gift");
        tagsField.setText(String.join(", ", editing.tags)); tagsField.setSingleLine(true); tagsField.setTextColor(ink); tagsField.setHintTextColor(muted); tagsField.setMinHeight(dp(48));
        card.addView(tagsField, new LinearLayout.LayoutParams(-1, -2));
        android.view.View.OnFocusChangeListener saveOnLeave = (v, has) -> { if (!has) saveJournal(); };
        notesField.setOnFocusChangeListener(saveOnLeave); tagsField.setOnFocusChangeListener(saveOnLeave);
        for (JSONObject g : editing.guardian)
            label(card, "Print guardian, " + new java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(new java.util.Date(g.optLong("at"))) + ": "
                + (g.optBoolean("fine") ? "you said it looked fine" : "you said it was a real problem") + (g.optString("said").isEmpty() ? "" : " (" + StatusPresentation.clean(g.optString("said")) + ")"), 12, muted, false);
        if (getIntent().getBooleanExtra(EXTRA_JOURNAL, false)) outer.post(() -> { android.view.View scroll = (android.view.View) content.getParent(); if (scroll instanceof ScrollView) ((ScrollView) scroll).smoothScrollTo(0, outer.getTop() - dp(12)); });
    }
    /** The folded journal's line: "★★★★☆ · Success · 2 problems · notes", or a prompt when nothing is noted. */
    static String journalSummary(PrintJournal j) {
        List<String> parts = new ArrayList<>();
        if (j.stars > 0) { StringBuilder stars = new StringBuilder(); for (int i = 0; i < 5; i++) stars.append(i < j.stars ? '★' : '☆'); parts.add(stars.toString()); }
        if (!j.result.isEmpty()) parts.add(PrintJournal.label(PrintJournal.RESULTS, j.result));
        if (!j.reasons.isEmpty()) parts.add(j.reasons.size() == 1 ? PrintJournal.label(PrintJournal.REASONS, j.reasons.iterator().next()) : j.reasons.size() + " problems");
        if (!j.notes.isEmpty()) parts.add("notes");
        if (!j.tags.isEmpty()) parts.add(String.join(", ", j.tags));
        return parts.isEmpty() ? "Rate it and note how it came out. Tap to open." : String.join(" · ", parts);
    }
    private void saveJournal() {
        if (editing == null || editingMeta == null) return;
        if (notesField != null) editing.notes = Automation.cap(notesField.getText().toString().trim(), 2000);
        if (tagsField != null) { editing.tags.clear(); editing.tags.addAll(PrintJournal.tags(tagsField.getText().toString())); }
        if (editing.rated() && editing.ratedAt == 0) editing.ratedAt = System.currentTimeMillis();
        PrintJournal saving = editing; File meta = editingMeta;
        SAVES.execute(() -> { try { PrintRecorder.saveJournal(meta, saving); } catch (Exception failed) { runOnUiThread(() -> android.widget.Toast.makeText(this, "Could not save the journal.", android.widget.Toast.LENGTH_SHORT).show()); } });
    }
    /** Journal saves in order, off the main thread. */
    private static final java.util.concurrent.ExecutorService SAVES = java.util.concurrent.Executors.newSingleThreadExecutor();
    @Override protected void onPause() { super.onPause(); if (editing != null) saveJournal(); }

    // ---- Screens ----

    private void showList() {
        content.removeAllViews();
        heading("Print recordings");
        List<PrintRecorder.Recording> recordings = new PrintRecorder(directory(this)).list();
        if (recordings.isEmpty()) {
            label("No recordings yet.", 16, ink, true);
            label("Prints are recorded automatically while the app is connected to the printer. Connect first, then start a print and it will appear here.", 14, muted, false);
            LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); content.addView(row);
            action(row, "Open Settings to connect", () -> startActivity(new Intent(this, MainActivity.class).putExtra(MainActivity.EXTRA_PAGE, FeatureIndex.SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)));
            return;
        }
        label("Each print is recorded automatically, newest first. Unfinished means the app stopped watching before the print ended. Rate a print and note what went wrong on its page.", 13, muted, false);
        // What went wrong, over every rated print: the journal's problems, most often first.
        List<PrintJournal> journals = new ArrayList<>(); int rated = 0;
        for (PrintRecorder.Recording r : recordings) { journals.add(r.journal); if (r.journal.rated()) rated++; }
        java.util.Map<String, Integer> problems = PrintJournal.problemCounts(journals);
        if (rated > 0 || !problems.isEmpty()) {
            StringBuilder text = new StringBuilder(rated + " of " + recordings.size() + " prints rated.");
            if (!problems.isEmpty()) { text.append(" Most common problems: "); int n = 0;
                for (java.util.Map.Entry<String, Integer> e : problems.entrySet()) { if (n++ == 3) break; text.append(n > 1 ? ", " : "").append(PrintJournal.label(PrintJournal.REASONS, e.getKey()).toLowerCase(Locale.getDefault())).append(" (").append(e.getValue()).append(")"); }
                text.append('.'); }
            label(text.toString(), 13, ink, false);
        }
        android.widget.EditText search = new android.widget.EditText(this); search.setHint("Search: name, notes, #tag, failed, warping…"); search.setSingleLine(true);
        search.setTextColor(ink); search.setHintTextColor(muted); search.setText(listQuery); WorkshopUi.searchField(search, surface, muted);
        LinearLayout.LayoutParams searchAt = new LinearLayout.LayoutParams(-1, -2); searchAt.topMargin = dp(8); content.addView(search, searchAt);
        LinearLayout results = new LinearLayout(this); results.setOrientation(LinearLayout.VERTICAL); content.addView(results);
        Runnable fill = () -> {
            results.removeAllViews(); int shownCount = 0;
            for (PrintRecorder.Recording recording : recordings) {
                if (!PrintJournal.matches(recording.file, recording.journal, listQuery)) continue;
                shownCount++;
                WorkshopUi.addToGrid(results, recordingCard(recording), dp(12));
            }
            if (shownCount == 0) label(results, "No recording matches “" + listQuery.trim() + "”.", 14, muted, false);
        };
        search.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { } public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(android.text.Editable s) { listQuery = s.toString(); fill.run(); }
        });
        fill.run();
    }
    private String listQuery = "";
    /** Pictures for the list's cards, decoded off the main thread. */
    private static final java.util.concurrent.ExecutorService PICTURES = java.util.concurrent.Executors.newSingleThreadExecutor();
    /** A recording as a card: its last camera picture on top (or its result, without pictures), the name, when and how long, and the journal. */
    private View recordingCard(PrintRecorder.Recording recording) {
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable shape = new GradientDrawable(); shape.setColor(surface); shape.setCornerRadius(dp(20));
        card.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(dark ? 0x4463d5c7 : 0x33006b65), shape, null));
        card.setClipToOutline(true);
        android.widget.FrameLayout top = new android.widget.FrameLayout(this); top.setBackgroundColor(dark ? 0xff21343a : 0xffe2efed);
        card.addView(top, new LinearLayout.LayoutParams(-1, dp(112)));
        ImageView picture = new ImageView(this); picture.setScaleType(ImageView.ScaleType.CENTER_CROP); picture.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        top.addView(picture, new android.widget.FrameLayout.LayoutParams(-1, -1));
        TextView badge = new TextView(this); badge.setText(outcome(recording.outcome)); badge.setTextSize(12); badge.setTextColor(Color.WHITE); badge.setPadding(dp(8), dp(3), dp(8), dp(3));
        GradientDrawable pill = new GradientDrawable(); pill.setCornerRadius(dp(12)); pill.setColor(0x99000000); badge.setBackground(pill);
        android.widget.FrameLayout.LayoutParams badgeAt = new android.widget.FrameLayout.LayoutParams(-2, -2, android.view.Gravity.TOP | android.view.Gravity.START); badgeAt.setMargins(dp(8), dp(8), dp(8), dp(8));
        top.addView(badge, badgeAt);
        List<File> files = PrintRecorder.pictures(recording);
        if (!files.isEmpty()) {
            File last = files.get(files.size() - 1); int width = dp(200);
            PICTURES.execute(() -> { android.graphics.Bitmap bitmap = decode(last, width); if (bitmap != null) runOnUiThread(() -> picture.setImageBitmap(bitmap)); });
        }
        LinearLayout text = new LinearLayout(this); text.setOrientation(LinearLayout.VERTICAL); text.setPadding(dp(14), dp(8), dp(14), dp(12)); card.addView(text);
        TextView name = label(text, StatusPresentation.clean(recording.file.replaceFirst("(?i)\\.gcode$", "")).replace('_', ' '), 15, ink, true);
        name.setMaxLines(2); name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        label(text, when(recording.start, Locale.getDefault(), TimeZone.getDefault()) + " · " + duration(recording.duration()), 12, muted, false);
        String journal = journalSummary(recording.journal);
        if (recording.journal.rated() || !recording.journal.notes.isEmpty()) { TextView j = label(text, journal, 12, ink, false); j.setMaxLines(2); j.setEllipsize(android.text.TextUtils.TruncateAt.END); }
        card.setClickable(true); card.setFocusable(true);
        card.setContentDescription(name.getText() + ". " + rowSummary(recording, Locale.getDefault(), TimeZone.getDefault()) + (recording.journal.rated() ? ". " + journal : ""));
        card.setOnClickListener(v -> startActivity(new Intent(this, RecordingsActivity.class).putExtra(EXTRA_META, recording.meta.getAbsolutePath())));
        return card;
    }

    private void showRecording(File meta) {
        content.removeAllViews();
        PrintRecorder.Recording recording; List<double[]> rows;
        try { recording = PrintRecorder.Recording.read(meta); rows = PrintRecorder.samples(recording); }
        catch (Exception error) { heading("Recording"); label("This recording could not be opened. It may have been deleted or damaged. Go back and choose another one.", 15, ink, false); return; }
        shown = recording;
        // Opened from the Print tab during a print: the other recordings are one tap away.
        LinearLayout back = new LinearLayout(this); back.setOrientation(LinearLayout.HORIZONTAL); content.addView(back);
        // A back link, as on the main screen's sections, rather than a full-width button.
        TextView allLink = new TextView(this); allLink.setText("All recordings"); allLink.setTextSize(15); allLink.setTextColor(ink); WorkshopUi.backArrow(allLink, ink);
        allLink.setGravity(android.view.Gravity.CENTER_VERTICAL); allLink.setMinHeight(dp(48)); allLink.setOnClickListener(v -> startActivity(new Intent(this, RecordingsActivity.class)));
        allLink.setContentDescription("All recordings"); back.addView(allLink, new LinearLayout.LayoutParams(-2, -2));
        heading(StatusPresentation.clean(recording.file.replaceFirst("(?i)\\.gcode$", "")));
        String details = when(recording.start, Locale.getDefault(), TimeZone.getDefault()) + (Showcase.running() ? "" : " · " + StatusPresentation.clean(recording.printer));
        if (recording.layers > 0) details += " · " + recording.layers + " layers";
        label(details, 13, muted, false);
        // Summary tiles: how long, how it ended, and when it ended on the clock.
        long ended = recording.end > 0 ? recording.end : recording.lastSample;
        LinearLayout tiles = new LinearLayout(this); tiles.setOrientation(LinearLayout.HORIZONTAL); content.addView(tiles);
        tile(tiles, "Duration", duration(recording.duration()), 0);
        tile(tiles, "Result", outcome(recording.outcome), dp(8));
        tile(tiles, "Ended", ended > 0 ? when(ended, Locale.getDefault(), TimeZone.getDefault()) : "—", dp(8));
        journalCard(recording);
        // Filament fed (from the extruder position) and how the printer's estimate at 10 % compared with the real time.
        List<String> extra = new ArrayList<>();
        if (recording.extrudedMm > 1) extra.add(String.format(Locale.getDefault(), "Filament fed: %.2f m (≈ %.0f g of PLA-like filament)", recording.extrudedMm / 1000, PrintRecorder.grams(recording.extrudedMm, "PLA")));
        double q = PrintRecorder.ratio(recording);
        if (!Double.isNaN(q)) extra.add(String.format(Locale.getDefault(), "The printer estimated %s at 10 %%; it took %s (%+.0f %%).", duration(recording.estimate * 1000), duration(Math.round(recording.estimate * q) * 1000), (q - 1) * 100));
        String last = lastReading(rows);
        if (!last.isEmpty()) extra.add(last + ".");
        String runout = runouts(rows, t0(rows));
        if (!runout.isEmpty()) extra.add(runout);
        String coverage = coverage(rows, recording);
        if (!coverage.isEmpty()) extra.add(coverage);
        if (!extra.isEmpty()) { TextView notes = label(String.join("\n", extra), 13, muted, false); notes.setPadding(0, dp(10), 0, dp(3)); }
        double[] t = column(rows, "elapsed_s");
        warmUp = warmUpPhases(column(rows, "sub_status"), column(rows, "layer"), t);
        chartPictures = PrintRecorder.pictures(recording); pictureTimes = pictureTimes(chartPictures); pictureThumbs.clear();
        String warm = warmUpWords(warmUp);
        if (!warm.isEmpty()) { TextView words = label(warm, 13, muted, false); words.setPadding(0, 0, 0, dp(3)); }
        pictures(recording);   // the quickest look at how the print went, before the charts
        checks(recording);
        // One card with the print's four charts, one at a time (the tour shows them all, one after another).
        boolean all = Showcase.running();
        LinearLayout charts = all ? content : card();
        LinearLayout choices = new LinearLayout(this); choices.setOrientation(LinearLayout.HORIZONTAL); if (!all) charts.addView(choices);
        List<View> shownCharts = new ArrayList<>(); List<Button> chips = new ArrayList<>();
        String[][] names = {{"Progress", "Progress"}, {"Layer", "Layer number"}, {"Temps", "Temperatures"}, {"Fans", "Fan speed"}, {"Speed", "Speed (as the printer reports it)"}};
        List<List<ChartView.Series>> series = Arrays.asList(
            Collections.singletonList(new ChartView.Series("Progress", blue, column(rows, "progress"), false)),
            Collections.singletonList(new ChartView.Series("Layer", blue, column(rows, "layer"), false)),
            Arrays.asList(new ChartView.Series("Nozzle", blue, column(rows, "nozzle_c"), false), new ChartView.Series("Nozzle target", blue, column(rows, "nozzle_target_c"), true),
                new ChartView.Series("Bed", orange, column(rows, "bed_c"), false), new ChartView.Series("Bed target", orange, column(rows, "bed_target_c"), true),
                new ChartView.Series("Chamber", aqua, column(rows, "chamber_c"), false)),
            Arrays.asList(new ChartView.Series("Part fan", blue, column(rows, "part_fan_pct"), false), new ChartView.Series("Aux fan", orange, column(rows, "aux_fan_pct"), false),
                new ChartView.Series("Chamber fan", aqua, column(rows, "chamber_fan_pct"), false)),
            Collections.singletonList(new ChartView.Series("Speed", blue, column(rows, "speed"), false)));
        String[] units = {" %", "", " °C", " %", ""};
        double[][] ranges = {{0, 100}, {Double.NaN, Double.NaN}, {Double.NaN, Double.NaN}, {0, 100}, {Double.NaN, Double.NaN}};
        android.content.SharedPreferences prefs = getSharedPreferences("workshop-settings", MODE_PRIVATE);
        int accent = dark ? 0xff5fd4c4 : 0xff00796b;
        Runnable[] pick = new Runnable[1];
        for (int i = 0; i < names.length; i++) {
            boolean any = false; for (ChartView.Series one : series.get(i)) for (double v : one.values) if (!Double.isNaN(v)) { any = true; break; }
            if (!any) continue;
            View chart = chart(all ? card() : charts, names[i][1], units[i], t, series.get(i), ranges[i][0], ranges[i][1]);
            if (chart == null) continue;
            shownCharts.add(chart); int index = shownCharts.size() - 1;
            if (!all) { action(choices, names[i][0], () -> { prefs.edit().putInt("recordingChart", index).apply(); pick[0].run(); }); Button chip = (Button) choices.getChildAt(choices.getChildCount() - 1); chip.setMaxLines(1); chip.setTextSize(14); chip.setPadding(0, dp(8), 0, dp(8)); ((LinearLayout.LayoutParams) chip.getLayoutParams()).weight = names[i][0].length() > 6 ? 1.4f : 1; chips.add(chip); }
        }
        pick[0] = () -> {
            int chosen = Math.min(prefs.getInt("recordingChart", 0), shownCharts.size() - 1);
            for (int i = 0; i < shownCharts.size(); i++) {
                boolean on = i == chosen; shownCharts.get(i).setVisibility(on ? View.VISIBLE : View.GONE);
                chips.get(i).setTextColor(on ? (dark ? 0xff0e1417 : Color.WHITE) : accent);
                ((android.graphics.drawable.RippleDrawable) chips.get(i).getBackground()).getDrawable(0).setTint(on ? accent : (dark ? 0xff21343a : 0xffe2efed));
                A11y.state(chips.get(i), on ? "Chosen" : "");
            }
        };
        if (!all && !shownCharts.isEmpty()) {
            pick[0].run();
            label(charts, "Touch and drag to read values. Times are clock times on this phone; dashed lines are targets." + (pictureTimes.length > 0 ? " Ticks along the bottom are the camera pictures: drag onto one to see it, tap it to open it." : ""), 12, muted, false);
        } else if (!all) content.removeView(charts);
        headPositions(recording, rows);
        trays(rows, t);
        gcodeCard(recording, rows);
        material(recording, rows, t);
        layerCharts(recording, rows);
        LinearLayout actions = new LinearLayout(this); actions.setOrientation(LinearLayout.HORIZONTAL); content.addView(actions);
        action(actions, "All readings…", () -> table(recording, rows));
        action(actions, "Export CSV…", () -> startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/csv").addCategory(Intent.CATEGORY_OPENABLE)
            .putExtra(Intent.EXTRA_TITLE, recording.csv.getName()), EXPORT));
        action(actions, "Delete…", () -> new AlertDialog.Builder(this).setTitle("Delete this recording?").setNegativeButton("Cancel", null)
            .setPositiveButton("Delete", (d, w) -> { new PrintRecorder(directory(this)).delete(recording); finish(); }).show());
    }

    private static double[] t0(List<double[]> rows) { return column(rows, "elapsed_s"); }
    /** When the filament sensor reported no filament during the print, in words ("" when it never did or does not report). */
    static String runouts(List<double[]> rows, double[] t) {
        double[] sensor = column(rows, "filament_detected"); List<String> when = new ArrayList<>(); boolean was = true;
        for (int i = 0; i < sensor.length; i++) {
            if (Double.isNaN(sensor[i])) continue;
            boolean there = sensor[i] > 0.5;
            if (!there && was) when.add(ChartView.elapsed(t[i]));
            was = there;
        }
        return when.isEmpty() ? "" : "The filament sensor reported no filament at " + String.join(", ", when.subList(0, Math.min(6, when.size()))) + (when.size() > 6 ? " and more" : "") + " into the print.";
    }

    /**
     * What the print's G-code asks of each layer (LayerProfile): its cross section by line type, stacked in the viewer's
     * colours, and the speed, flow and acceleration its lines run at. Needs the file on the phone, as the material card.
     */
    /** The guardian's answers, at the layer printing when each was given (the first sample at or after it). Pure, for tests. */
    static List<double[]> answerLayers(PrintJournal journal, List<double[]> rows) {
        List<double[]> out = new ArrayList<>();
        double[] time = column(rows, "time_ms"), layer = column(rows, "layer");
        for (org.json.JSONObject g : journal.guardian) {
            long at = g.optLong("at"); double found = Double.NaN;
            for (int i = 0; i < time.length && Double.isNaN(found); i++) if (time[i] >= at && !Double.isNaN(layer[i])) found = layer[i];
            if (Double.isNaN(found)) for (int i = layer.length - 1; i >= 0 && Double.isNaN(found); i--) if (!Double.isNaN(layer[i])) found = layer[i];
            if (!Double.isNaN(found) && found > 0) out.add(new double[] {found, g.optBoolean("fine") ? 1 : 0});
        }
        return out;
    }

    private void layerCharts(PrintRecorder.Recording recording, List<double[]> rows) {
        LayerCharts.Guardian guardian = new LayerCharts.Guardian(recording.guardianChecks, answerLayers(recording.journal, rows));
        LinearLayout card = card(); label(card, guardian.any() ? "Each layer, with the print guardian's checks" : "Each layer, from the print's G-code", 15, ink, true);
        if (guardian.any()) label(card, "Marks: amber where a guardian measure went over its limit, red where it raised the alarm (or you said it was a real problem), teal where you said it looked fine. Touch a mark's layer to read it.", 12, muted, false);
        TextView note = label(card, "Reading the print's file…", 13, muted, false);
        new Thread(() -> {
            File file = gcodeFor(recording);
            LayerProfile read = null; if (file != null) try { read = LayerProfile.read(file); } catch (Exception | OutOfMemoryError failed) { read = null; }
            LayerProfile p = read;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                if (file == null && !guardian.any()) { note.setText("The print's G-code is not on this phone. Add it to the recording (above) to see each layer's line types, speed, flow and acceleration."); return; }
                if (file == null) note.setText("The print's G-code is not on this phone, so only the guardian's measures are shown.");
                if (file != null && (p == null || p.layers == 0)) { note.setText("The print's file could not be read." + (guardian.any() ? " The guardian's measures are shown alone." : "")); if (!guardian.any()) return; }
                if (p != null && p.layers > 0) note.setText(LayerCharts.note(p));
                LayerCharts.fill(this, card, p != null && p.layers > 0 ? p : null, guardian, ink, muted, grid, surface, dark, getSharedPreferences("workshop-settings", MODE_PRIVATE),
                    (row, text, action) -> { action(row, text, action); Button b = (Button) row.getChildAt(row.getChildCount() - 1); b.setPadding(0, dp(8), 0, dp(8)); return b; });
                // The pictures at the layer printing when each was taken.
                double[] atLayer = pictureLayers(pictureTimes, column(rows, "elapsed_s"), column(rows, "layer"));
                for (int i = 0; i < card.getChildCount(); i++) if (card.getChildAt(i) instanceof ChartView) ((ChartView) card.getChildAt(i)).pictures(atLayer, this::pictureThumb, this::openPicture);
            });
        }, "recording-layers").start();
    }

    // ---- Pictures, trays and material ----

    /**
     * The print's G-code for its charts, worker only: the copy kept with the recording; else the phone's one copy by its
     * name (from this printer or none) that was there before the print ended, which is then kept with the recording so a
     * newer file of the same name, or the archive's clean-up, cannot take it away.
     */
    private File gcodeFor(PrintRecorder.Recording recording) {
        File kept = RecordingGcode.open(recording, getCacheDir());
        if (kept != null) return kept;
        try {
            GcodeLibrary.Artifact match = matchFor(new GcodeLibrary(new File(getFilesDir(), "gcode-library")).copies(recording.file), recording);
            if (match == null) return null;
            try { RecordingGcode.pack(match.file, match.name, recording, RecordingGcode.MATCHED); kept = RecordingGcode.open(recording, getCacheDir()); } catch (IOException notKept) { kept = null; }
            return kept != null ? kept : match.file;
        } catch (IOException unreadable) { return null; }
    }
    /** The one copy by the recording's name from its printer (or no printer) kept before the print ended; null when none or several. Pure. */
    static GcodeLibrary.Artifact matchFor(List<GcodeLibrary.Artifact> copies, PrintRecorder.Recording recording) {
        long end = recording.end > 0 ? recording.end : recording.lastSample;
        GcodeLibrary.Artifact only = null;
        for (GcodeLibrary.Artifact a : copies) {
            boolean printer = a.printer.isEmpty() || recording.printerId.isEmpty() || PrintRecorder.digest(a.printer).equals(recording.printerId);
            if (!printer || end > 0 && a.at > end) continue;
            if (only != null) return null;
            only = a;
        }
        return only;
    }

    /** Whether the recording holds its print's G-code, and adding (or replacing, or removing) it by hand. */
    private void gcodeCard(PrintRecorder.Recording recording, List<double[]> rows) {
        LinearLayout card = card(); label(card, "The print's G-code", 15, ink, true);
        TextView state = label(card, "Looking…", 13, muted, false);
        LinearLayout buttons = new LinearLayout(this); buttons.setOrientation(LinearLayout.HORIZONTAL); card.addView(buttons);
        new Thread(() -> {
            gcodeFor(recording);   // keeps a fitting phone copy with the recording when there is one
            RecordingGcode.Info info = RecordingGcode.info(recording);
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                state.setText(info == null ? "Not kept with this recording. Add it to see material, line types and flow for this print; the charts use it once added."
                    : "Kept with this recording: " + info.words() + ".");
                action(buttons, info == null ? "Add G-code…" : "Replace…", () -> addGcode(recording, rows));
                if (info != null) action(buttons, "Remove", () -> new AlertDialog.Builder(this).setTitle("Take the G-code out of this recording?")
                    .setMessage("The charts that need it go blank until it is added again. The phone's own G-code files are not touched.")
                    .setNegativeButton("Cancel", null).setPositiveButton("Remove", (d, w) -> { RecordingGcode.remove(recording); recreate(); }).show());
            });
        }, "recording-gcode").start();
    }
    private void addGcode(PrintRecorder.Recording recording, List<double[]> rows) {
        new Thread(() -> {
            List<GcodeLibrary.Artifact> copies;
            try { copies = new GcodeLibrary(new File(getFilesDir(), "gcode-library")).copies(null); } catch (IOException unreadable) { copies = new ArrayList<>(); }
            // The print's own name first.
            String base = GcodeLibrary.baseName(recording.file);
            List<GcodeLibrary.Artifact> ordered = new ArrayList<>();
            for (GcodeLibrary.Artifact a : copies) if (a.name.equals(base)) ordered.add(a);
            for (GcodeLibrary.Artifact a : copies) if (!a.name.equals(base)) ordered.add(a);
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                String[] labels = new String[ordered.size() + 1];
                labels[0] = "Choose a file on this phone…";
                for (int i = 0; i < ordered.size(); i++) {
                    GcodeLibrary.Artifact a = ordered.get(i);
                    labels[i + 1] = a.name + "\n" + a.origin.label + " · " + new java.text.SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(new java.util.Date(a.at))
                        + String.format(Locale.getDefault(), " · %.1f MB", a.size / 1048576.0);
                }
                new AlertDialog.Builder(this).setTitle("G-code for this recording").setNegativeButton("Cancel", null).setItems(labels, (d, which) -> {
                    if (which == 0) {
                        addingTo = recording; addingRows = rows;
                        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), ADD_GCODE);
                    } else { GcodeLibrary.Artifact a = ordered.get(which - 1); checkAndKeep(recording, rows, a.file, a.name, false); }
                }).show();
            });
        }, "recording-gcode-list").start();
    }
    private PrintRecorder.Recording addingTo; private List<double[]> addingRows;
    /** Reads the file's layers, says what does not fit the print, and keeps it with the recording once confirmed. `temporary`: delete the file afterwards. */
    private void checkAndKeep(PrintRecorder.Recording recording, List<double[]> rows, File file, String name, boolean temporary) {
        Toast.makeText(this, "Checking the file…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            int layers = 0; boolean readable = true;
            try { layers = LayerProfile.read(file).layers; } catch (Exception | OutOfMemoryError unreadable) { readable = false; }
            double reached = 0; for (double v : column(rows, "layer")) if (!Double.isNaN(v)) reached = Math.max(reached, v);
            String problem = !readable || layers == 0 ? "This file has no layers the app can read: it may not be G-code." : RecordingGcode.mismatch(recording.file, recording.layers, (int) reached, name, layers);
            boolean ok = readable && layers > 0; int fileLayers = layers;
            runOnUiThread(() -> {
                if (isDestroyed()) { if (temporary) file.delete(); return; }
                Runnable keep = () -> new Thread(() -> {
                    String failed = null;
                    try { RecordingGcode.pack(file, name, recording, RecordingGcode.ADDED); } catch (IOException e) { failed = e.getMessage(); }
                    if (temporary) file.delete();
                    String why = failed;
                    runOnUiThread(() -> { if (isDestroyed()) return; if (why != null) Toast.makeText(this, "Could not keep it: " + why, Toast.LENGTH_LONG).show(); else recreate(); });
                }, "recording-gcode-pack").start();
                if (!ok) { new AlertDialog.Builder(this).setTitle("Not added").setMessage(problem).setPositiveButton("OK", null).show(); if (temporary) file.delete(); return; }
                if (problem.isEmpty()) { keep.run(); return; }
                new AlertDialog.Builder(this).setTitle("This may not be the print's file").setMessage(problem + "\n\nThe charts will follow this file's lines for this print. Keep it anyway?")
                    .setNegativeButton("Cancel", (d, w) -> { if (temporary) file.delete(); }).setPositiveButton("Keep it (" + fileLayers + " layers)", (d, w) -> keep.run()).show();
            });
        }, "recording-gcode-check").start();
    }

    private String displayName(Uri uri) {
        try (android.database.Cursor c = getContentResolver().query(uri, new String[] {android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (RuntimeException ignored) { }
        return null;
    }
    /** The print's camera pictures in a strip, each with its time into the print; tap for the full picture. */
    private void pictures(PrintRecorder.Recording recording) {
        List<File> files = PrintRecorder.pictures(recording);
        if (files.isEmpty() && recording.pictureMinutes == 0 && recording.pictureLayers == 0) return;
        boolean comparing = recording.compare && recording.smooth();
        LinearLayout card = card(); label(card, comparing ? "Camera pictures · head parked" : "Camera pictures", 15, ink, true);
        if (files.isEmpty()) label(card, "No pictures yet. " + recording.pictureWords() + ", from the printer's camera on this network, or through the cloud.", 13, muted, false);
        else {
            label(card, files.size() + (files.size() == 1 ? " picture" : " pictures") + (comparing ? "" : recording.pictureMinutes > 0 || recording.pictureLayers > 0 ? " · " + recording.pictureWords().toLowerCase(Locale.ROOT) : "") + ". Tap one to see it larger.", 13, muted, false);
            strip(card, recording, files, "");
        }
        if (!comparing) return;
        // The second set, to compare: the same layers, with the head at the far side of each instead of parked.
        List<File> far = PrintRecorder.farPictures(recording);
        LinearLayout farCard = card(); label(farCard, "Camera pictures · head far from the camera", 15, ink, true);
        label(farCard, far.isEmpty() ? "No pictures in this set yet. They are taken while each layer prints, when the head reaches the side away from the camera."
            : far.size() + (far.size() == 1 ? " picture" : " pictures") + ", taken while each layer printed. Compare with the parked set above.", 13, muted, false);
        madeAlready(farCard, recording.sideBySide(), "Side-by-side video, made when the print ended");
        if (!far.isEmpty() && files.size() >= 2 && !recording.sideBySide().isFile()) {
            LinearLayout both = new LinearLayout(this); both.setOrientation(LinearLayout.HORIZONTAL); farCard.addView(both);
            action(both, "Side by side video…", () -> makeVideo(recording, TimelapseEncoder.pairFiles(files, far), new String[] {"Parked", "Head far"}, "-side-by-side"));
        }
        if (!far.isEmpty()) strip(farCard, recording, far, "-far");
    }
    /** A video the app made by itself when the print ended: play it in the phone's player, or save a copy. */
    private void madeAlready(LinearLayout card, File video, String what) {
        if (!video.isFile()) return;
        label(card, what + " (" + Math.max(1, video.length() / 1024) + " KB).", 13, ink, false);
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); card.addView(row);
        String name = (shown == null ? "timelapse" : shown.csv.getName().replaceFirst("\\.csv$", "")) + "-" + video.getName();
        action(row, "Play", () -> {
            try {
                File copy = ShareProvider.fresh(this, name); java.nio.file.Files.copy(video.toPath(), copy.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(ShareProvider.uriFor(this, copy), "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
            } catch (IOException | android.content.ActivityNotFoundException failed) { Toast.makeText(this, "No app on this phone can play it. Save it instead.", Toast.LENGTH_LONG).show(); }
        });
        action(row, "Save…", () -> {
            try {
                File copy = new File(new File(getCacheDir(), "timelapse"), name); copy.getParentFile().mkdirs();
                java.nio.file.Files.copy(video.toPath(), copy.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                madeVideo = copy;   // saving removes this copy, never the kept video
                startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("video/mp4").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, name), VIDEO);
            } catch (IOException failed) { Toast.makeText(this, "The video could not be copied.", Toast.LENGTH_SHORT).show(); }
        });
    }
    /** A set of pictures in a strip, with its timelapse video button; tap one for the full picture. */
    private void strip(LinearLayout card, PrintRecorder.Recording recording, List<File> files, String videoSuffix) {
        if (videoSuffix.isEmpty()) madeAlready(card, recording.video(), "Timelapse video, made when the print ended");
        if (files.size() >= 2) { LinearLayout videoRow = new LinearLayout(this); videoRow.setOrientation(LinearLayout.HORIZONTAL); card.addView(videoRow); action(videoRow, recording.video().isFile() && videoSuffix.isEmpty() ? "Make it again…" : "Make a timelapse video…", () -> makeVideo(recording, files, videoSuffix)); }
        LinearLayout strip = new LinearLayout(this); strip.setOrientation(LinearLayout.HORIZONTAL);
        HorizontalScrollView scroll = new HorizontalScrollView(this); scroll.addView(strip); card.addView(scroll);
        for (File file : files) {
            String at = ChartView.elapsed(Double.parseDouble(file.getName().replace(".jpg", "")));
            LinearLayout cell = new LinearLayout(this); cell.setOrientation(LinearLayout.VERTICAL); cell.setPadding(0, 0, dp(8), 0);
            ImageView image = new ImageView(this); image.setScaleType(ImageView.ScaleType.CENTER_CROP); image.setContentDescription("Picture at " + at + " into the print");
            image.setImageBitmap(decode(file, dp(160)));
            cell.addView(image, new LinearLayout.LayoutParams(dp(160), dp(120)));
            label(cell, at, 12, muted, false);
            image.setOnClickListener(v -> {
                ImageView big = new ImageView(this); big.setAdjustViewBounds(true); big.setImageBitmap(decode(file, getResources().getDisplayMetrics().widthPixels));
                new AlertDialog.Builder(this).setTitle(at + " into the print").setView(big).setPositiveButton("Close", null).show();
            });
            strip.addView(cell);
        }
    }
    /** What the app measured during the print (PrintChecks): shown only when there is something to say. */
    private void checks(PrintRecorder.Recording recording) {
        List<PrintChecks.Finding> found = PrintChecks.of(recording);
        if (found.isEmpty()) return;
        LinearLayout card = card(); label(card, "Measured during this print", 15, ink, true);
        label(card, "From the statuses the app received; nothing was sent to the printer for these. They also fill in Settings › Field check.", 12, muted, false);
        for (PrintChecks.Finding f : found) {
            TextView title = label(card, PrintChecks.mark(f.verdict) + "  " + f.title, 14, f.verdict == PrintChecks.Verdict.PROBLEM ? orange : ink, true);
            title.setContentDescription((f.verdict == PrintChecks.Verdict.WORKS ? "Works: " : f.verdict == PrintChecks.Verdict.PROBLEM ? "Problem: " : "Unsure: ") + f.title);
            label(card, f.evidence, 13, muted, false);
        }
    }
    /** Turns the pictures into a short MP4 on the phone (about ten seconds), then asks where to save it. */
    private void makeVideo(PrintRecorder.Recording recording, List<File> files, String suffix) {
        List<File[]> frames = new java.util.ArrayList<>(); for (File file : files) frames.add(new File[] {file});
        makeVideo(recording, frames, null, suffix);
    }
    /** As above, from frames of one picture or two side by side (`labels` names the sides). */
    private void makeVideo(PrintRecorder.Recording recording, List<File[]> files, String[] labels, String suffix) {
        java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        TextView state = new TextView(this); state.setTextSize(15); state.setTextColor(ink); state.setPadding(dp(24), dp(16), dp(24), 0);
        String unit = labels == null ? " pictures" : " frames"; state.setText("Making the video: 0 of " + files.size() + unit); A11y.polite(state);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Timelapse video").setView(state).setCancelable(false)
            .setNegativeButton("Cancel", (d, w) -> cancelled.set(true)).show();
        File folder = new File(getCacheDir(), "timelapse"); folder.mkdirs();
        File[] old = folder.listFiles(); if (old != null) for (File f : old) f.delete();
        File out = new File(folder, recording.csv.getName().replaceFirst("\\.csv$", "") + suffix + "-timelapse.mp4");
        new Thread(() -> {
            String failure = null;
            boolean where = getSharedPreferences("workshop-settings", MODE_PRIVATE).getBoolean("timelapsePosition", true);
            try { TimelapseEncoder.encode(files, labels, where ? TimelapseEncoder.positions() : null, out, cancelled, (done, total) -> runOnUiThread(() -> state.setText("Making the video: " + done + " of " + total + unit))); }
            catch (Exception error) { failure = cancelled.get() ? "" : "The video could not be made on this phone (" + error.getClass().getSimpleName() + ")."; out.delete(); }
            String problem = failure;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                dialog.dismiss();
                if (problem != null) { if (!problem.isEmpty()) Toast.makeText(this, problem, Toast.LENGTH_LONG).show(); return; }
                madeVideo = out;
                startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("video/mp4").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, out.getName()), VIDEO);
            });
        }, "timelapse").start();
    }
    private static android.graphics.Bitmap decode(File file, int width) {
        android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeFile(file.getPath(), bounds);
        android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options(); options.inSampleSize = 1;
        while (bounds.outWidth / (options.inSampleSize * 2) >= width) options.inSampleSize *= 2;
        return android.graphics.BitmapFactory.decodeFile(file.getPath(), options);
    }

    /**
     * Where the head was at each picture (the printer's last report before it was kept): X, Y and Z over the pictures'
     * times, or in 3D over the bed with the readings' positions behind; one at a time behind chips.
     */
    private void headPositions(PrintRecorder.Recording recording, List<double[]> rows) {
        Map<String, double[]> noted = PrintRecorder.picturePositions(recording.pictureFolder());
        if (noted.isEmpty()) return;
        List<double[]> at = new ArrayList<>(); List<Integer> index = new ArrayList<>(); List<Double> ages = new ArrayList<>();
        for (int i = 0; i < chartPictures.size(); i++) {
            double[] p = noted.get(chartPictures.get(i).getName()); if (p == null) continue;
            at.add(new double[] {p[0], p[1], p[2], p[3]}); index.add(i); ages.add(p[5]);
        }
        if (at.isEmpty()) return;
        LinearLayout card = card(); label(card, "Head position at each picture", 15, ink, true);
        Collections.sort(ages); double age = ages.get(ages.size() / 2);
        label(card, HeadPositions3DView.describe(at.toArray(new double[0][])) + String.format(Locale.getDefault(),
            " Each is the printer's last report before the picture was kept, typically %.1f s earlier.", age), 13, muted, false);
        double[] times = new double[at.size()], x = new double[at.size()], y = new double[at.size()], z = new double[at.size()];
        for (int i = 0; i < times.length; i++) { times[i] = at.get(i)[0]; x[i] = at.get(i)[1]; y[i] = at.get(i)[2]; z[i] = at.get(i)[3]; }
        java.util.function.IntFunction<android.graphics.Bitmap> thumb = k -> pictureThumb(index.get(k));
        java.util.function.IntConsumer open = k -> openPicture(index.get(k));
        ChartView overTime = new ChartView(this, ink, muted, grid, surface);
        overTime.set("Head at each picture", " mm", times, Arrays.asList(new ChartView.Series("X", blue, x, false), new ChartView.Series("Y", orange, y, false), new ChartView.Series("Z", aqua, z, false)), 0, Double.NaN);
        if (shown != null && shown.start > 0) overTime.clock(shown.start);
        overTime.pictures(times, thumb, open);
        List<double[]> seen = new ArrayList<>(); int cx = PrintRecorder.column("x_mm"), cy = PrintRecorder.column("y_mm"), cz = PrintRecorder.column("z_mm");
        for (double[] row : rows) if (!Double.isNaN(row[cx]) && !Double.isNaN(row[cy])) seen.add(new double[] {row[cx], row[cy], row[cz]});
        double bedX = 256, bedY = 256; for (double[] p : at) { bedX = Math.max(bedX, p[1]); bedY = Math.max(bedY, p[2]); }
        HeadPositions3DView space = new HeadPositions3DView(this, ink, muted, grid, surface, dark ? 0xff9ec5f4 : 0xffb7d3f5, dark ? 0xff2a78d6 : 0xff1a4f9c)
            .set(at.toArray(new double[0][]), seen.toArray(new double[0][]), bedX, bedY).pictures(thumb, open);
        LinearLayout chips = new LinearLayout(this); chips.setOrientation(LinearLayout.HORIZONTAL); card.addView(chips);
        card.addView(overTime, new LinearLayout.LayoutParams(-1, dp(240)));
        card.addView(space, new LinearLayout.LayoutParams(-1, dp(340)));
        android.content.SharedPreferences prefs = getSharedPreferences("workshop-settings", MODE_PRIVATE);
        List<Button> buttons = new ArrayList<>(); View[] views = {overTime, space}; int accent = dark ? 0xff5fd4c4 : 0xff00796b;
        Runnable[] pick = new Runnable[1];
        String[] names = {"Over time", "3D"};
        for (int i = 0; i < names.length; i++) {
            int which = i;
            action(chips, names[i], () -> { prefs.edit().putInt("headChart", which).apply(); pick[0].run(); });
            Button b = (Button) chips.getChildAt(chips.getChildCount() - 1); b.setMaxLines(1); b.setTextSize(14); b.setPadding(0, dp(8), 0, dp(8)); buttons.add(b);
        }
        pick[0] = () -> {
            int chosen = Math.min(prefs.getInt("headChart", 0), 1);
            for (int i = 0; i < 2; i++) {
                boolean on = i == chosen; views[i].setVisibility(on ? View.VISIBLE : View.GONE);
                buttons.get(i).setTextColor(on ? (dark ? 0xff0e1417 : Color.WHITE) : accent);
                if (buttons.get(i).getBackground() instanceof android.graphics.drawable.RippleDrawable)
                    ((android.graphics.drawable.RippleDrawable) buttons.get(i).getBackground()).getDrawable(0).setTint(on ? accent : (dark ? 0xff21343a : 0xffe2efed));
                A11y.state(buttons.get(i), on ? "Chosen" : "");
            }
        };
        pick[0].run();
    }

    /**
     * Which CANVAS tray fed the print over time. One tray all along: a swatch and a sentence. Otherwise a band of solid
     * stretches (one per tray, readings merged) and the changes in words. Stretches where the printer did not report a
     * tray are shown faint and named, rather than left out.
     */
    private void trays(List<double[]> rows, double[] t) {
        double[] tray = column(rows, "tray"), rgb = column(rows, "tray_rgb");
        List<double[]> stretches = traySegments(tray, rgb, t);
        List<String> changes = trayChanges(tray, rgb, t);
        if (changes.isEmpty()) return;
        LinearLayout card = card(); label(card, "Filament over time", 15, ink, true);
        String gaps = trayGaps(stretches);
        if (changes.size() == 1) {
            double[] only = null; for (double[] s : stretches) if (!Double.isNaN(s[2])) { only = s; break; }
            LinearLayout line = new LinearLayout(this); line.setOrientation(LinearLayout.HORIZONTAL); line.setGravity(android.view.Gravity.CENTER_VERTICAL);
            View swatch = new View(this);
            android.graphics.drawable.GradientDrawable dot = new android.graphics.drawable.GradientDrawable(); dot.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            dot.setColor(Double.isNaN(only[3]) ? muted : 0xff000000 | (int) only[3]); dot.setStroke(dp(1), grid); swatch.setBackground(dot);
            LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(dp(18), dp(18)); at.rightMargin = dp(8); line.addView(swatch, at);
            card.addView(line);
            label(line, "The whole print came from " + trayName(only[2], only[3]) + ".", 14, ink, false);
            if (gaps != null) label(card, gaps, 13, muted, false);
            return;
        }
        label(card, "Which CANVAS tray fed the print, and when it switched.", 13, muted, false);
        double first = t.length == 0 ? 0 : t[0], last = t.length == 0 ? 1 : t[t.length - 1];
        View band = new View(this) {
            final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(android.graphics.Canvas canvas) {
                double span = Math.max(1, last - first); int w = getWidth(), h = getHeight();
                for (double[] s : stretches) {
                    int x0 = (int) Math.round((s[0] - first) / span * w), x1 = (int) Math.round((s[1] - first) / span * w);
                    if (x1 <= x0) x1 = x0 + 1;
                    boolean unknown = Double.isNaN(s[2]);
                    paint.setColor(unknown ? (muted & 0x00ffffff) | 0x33000000 : Double.isNaN(s[3]) ? muted : 0xff000000 | (int) s[3]);
                    canvas.drawRect(x0, unknown ? h * 0.35f : 0, x1, unknown ? h * 0.65f : h, paint);
                }
            }
        };
        band.setContentDescription("Filament over time: " + String.join("; ", changes) + (gaps == null ? "" : ". " + gaps));
        LinearLayout.LayoutParams bandLayout = new LinearLayout.LayoutParams(-1, dp(28)); bandLayout.topMargin = dp(4); card.addView(band, bandLayout);
        label(card, String.join("\n", changes) + (gaps == null ? "" : "\n" + gaps), 13, muted, false);
    }
    private static String trayName(double tray, double rgb) {
        String colour = Double.isNaN(rgb) ? null : WorkshopUi.colourName(String.format(Locale.ROOT, "#%06X", (int) rgb & 0xffffff));
        return "tray " + (int) tray + (colour == null ? "" : " (" + colour + ")");
    }
    /**
     * The readings merged into stretches {from s, to s, tray or NaN when not reported, rgb or NaN}: a new stretch only where
     * the tray (or its colour) changes. Each stretch runs to the next one's first reading; the last to the last reading. Pure.
     */
    static List<double[]> traySegments(double[] tray, double[] rgb, double[] t) {
        List<double[]> out = new ArrayList<>();
        for (int i = 0; i < t.length; i++) {
            double[] open = out.isEmpty() ? null : out.get(out.size() - 1);
            boolean same = open != null && (Double.isNaN(open[2]) ? Double.isNaN(tray[i]) : open[2] == tray[i] && (Double.isNaN(open[3]) ? Double.isNaN(rgb[i]) : open[3] == rgb[i]));
            if (same) { open[1] = t[i]; continue; }
            if (open != null) open[1] = t[i];
            out.add(new double[] {t[i], t[i], tray[i], Double.isNaN(tray[i]) ? Double.NaN : rgb[i]});
        }
        return out;
    }
    /** "The printer did not report the tray 1:30–4:00, 5:10–5:20." for the stretches with no tray, or null. Pure. */
    static String trayGaps(List<double[]> stretches) {
        List<String> parts = new ArrayList<>(); int more = 0;
        for (double[] s : stretches) {
            if (!Double.isNaN(s[2]) || s[1] - s[0] < 60) continue;
            if (parts.size() < 3) parts.add(ChartView.elapsed(s[0]) + "–" + ChartView.elapsed(s[1])); else more++;
        }
        if (parts.isEmpty()) return null;
        return "The printer did not report the tray " + String.join(", ", parts) + (more > 0 ? " and " + more + " more times" : "") + ", so those stretches are shown faint.";
    }
    /** "From 0:00: tray 1 (red)" each time the active tray changes; empty when no tray was reported. */
    static List<String> trayChanges(double[] tray, double[] rgb, double[] t) {
        List<String> out = new ArrayList<>(); double last = Double.NaN;
        for (int i = 0; i < tray.length; i++) {
            if (Double.isNaN(tray[i]) || tray[i] == last) continue;
            last = tray[i];
            String colour = Double.isNaN(rgb[i]) ? null : WorkshopUi.colourName(String.format(Locale.ROOT, "#%06X", (int) rgb[i] & 0xffffff));
            out.add("From " + ChartView.elapsed(t[i]) + ": tray " + (int) tray[i] + (colour == null ? "" : " (" + colour + ")"));
        }
        return out;
    }

    /**
     * From the print's own G-code, when it is on this phone: material per layer over the print's time, and each filament's
     * use with its colour. Read in the background.
     */
    /**
     * Per tool, the colour (0xRRGGBB) of the CANVAS tray that was active most while that tool laid filament down, from the
     * recording's tray_rgb column; -1 when the printer never reported a tray colour then.
     */
    static int[] printedColours(double[] trayRgb, double[][] rate, int tools) {
        int[] out = new int[tools]; Arrays.fill(out, -1);
        List<Map<Integer, Integer>> seen = new ArrayList<>(); for (int k = 0; k < tools; k++) seen.add(new HashMap<>());
        for (int i = 0; i < Math.min(trayRgb.length, rate.length); i++) {
            if (Double.isNaN(trayRgb[i]) || trayRgb[i] < 0) continue;
            int lead = -1; double best = 0;
            for (int k = 0; k < tools && k < rate[i].length; k++) if (!Double.isNaN(rate[i][k]) && rate[i][k] > best) { best = rate[i][k]; lead = k; }
            if (lead < 0 && tools == 1) lead = 0;   // one filament: any reading while printing counts, even before it starts laying down
            if (lead >= 0) seen.get(lead).merge((int) trayRgb[i] & 0xffffff, 1, Integer::sum);
        }
        for (int k = 0; k < tools; k++) { int most = 0; for (Map.Entry<Integer, Integer> e : seen.get(k).entrySet()) if (e.getValue() > most) { most = e.getValue(); out[k] = e.getKey(); } }
        return out;
    }
    private void material(PrintRecorder.Recording recording, List<double[]> rows, double[] t) {
        LinearLayout card = card(); label(card, "Material used", 15, ink, true);
        TextView note = label(card, "Reading the print's file…", 13, muted, false);
        LinearLayout chartBox = new LinearLayout(this); chartBox.setOrientation(LinearLayout.VERTICAL); card.addView(chartBox);
        LinearLayout byColour = new LinearLayout(this); byColour.setOrientation(LinearLayout.VERTICAL); card.addView(byColour);
        new Thread(() -> {
            File file = gcodeFor(recording);
            if (file == null) { runOnUiThread(() -> note.setText("The print's G-code is not on this phone, so material is not shown. Add it to the recording (above) to see material per layer and per colour.")); return; }
            double[] volumes = null; double[][] laidDown = null, byType = null; double[] perTool = null; SlicedMaterials materials = null;
            double[] progress = column(rows, "progress"), fractions = new double[progress.length];
            for (int i = 0; i < progress.length; i++) fractions[i] = Double.isNaN(progress[i]) ? Double.NaN : progress[i] / 100.0;
            try {
                GcodeToolpath path = GcodeToolpath.read(file); volumes = path.layerVolumes();
                int tools = path.toolCount(); laidDown = path.toolVolumesAt(fractions, tools); perTool = path.toolVolumes(tools); byType = path.typeVolumesAt(fractions);
            } catch (Exception ignored) { }
            try { materials = GcodeInspector.inspect(file).materials; } catch (Exception ignored) { }
            double[] perLayer = volumes; double[][] cumulative = laidDown, typeSoFar = byType; double[] toolTotals = perTool; SlicedMaterials used = materials;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                note.setText(perLayer == null || cumulative == null ? "The print's file could not be read."
                    : "How fast each filament was laid down (grams per hour), and how much was used in total, worked out from the print's progress and its sliced file.");
                int[] printed = null;   // per tool, the colour of the tray that actually printed it (0xRRGGBB), or -1
                if (perLayer != null && cumulative != null && toolTotals != null) {
                    int tools = toolTotals.length;
                    int[] filamentColour = new int[tools]; Arrays.fill(filamentColour, blue);
                    double[] gramsPerMm3 = new double[tools]; Arrays.fill(gramsPerMm3, 0.00124);   // PLA's density when the file does not say
                    String[] names = new String[tools];
                    for (int k = 0; k < tools; k++) names[k] = "Filament " + (k + 1);
                    if (used != null) for (SlicedMaterials.Entry entry : used.entries) {
                        if (entry.index >= tools) continue;
                        String hex = TrayPlan.colour(entry.color);
                        if (hex != null) { filamentColour[entry.index] = Color.parseColor(hex); String word = WorkshopUi.colourName(hex); if (word != null) names[entry.index] += " · " + word; }
                        if (entry.massG != null && entry.massG > 0 && toolTotals[entry.index] > 0) gramsPerMm3[entry.index] = entry.massG / toolTotals[entry.index];
                    }
                    double[][] rate = depositionRates(t, cumulative, gramsPerMm3);
                    // The colour shown is the tray's that printed it (as the printer reported during the print), not the sliced file's.
                    printed = printedColours(column(rows, "tray_rgb"), rate, tools);
                    for (int k = 0; k < tools; k++) if (printed[k] >= 0) {
                        filamentColour[k] = 0xff000000 | printed[k];
                        String word = WorkshopUi.colourName(String.format(Locale.ROOT, "#%06X", printed[k]));
                        names[k] = "Filament " + (k + 1) + (word == null ? "" : " · " + word);
                    }
                    List<ChartView.Series> lines = new ArrayList<>();
                    for (int k = 0; k < tools; k++) {
                        if (toolTotals[k] <= 0) continue;
                        double[] values = new double[t.length]; for (int i = 0; i < t.length; i++) values[i] = rate[i][k];
                        int[] same = new int[t.length]; Arrays.fill(same, filamentColour[k]);
                        lines.add(new ChartView.Series(names[k], filamentColour[k], values, false).coloured(same));
                    }
                    // A band of the filament laid down most at each moment, and the changes in words.
                    int[] lead = new int[t.length]; List<int[]> blocks = new ArrayList<>();
                    for (int i = 0; i < t.length; i++) {
                        int best = -1; double most = 1e-9; for (int k = 0; k < tools; k++) if (!Double.isNaN(rate[i][k]) && rate[i][k] > most) { most = rate[i][k]; best = k; }
                        lead[i] = best;
                        if (best >= 0 && (blocks.isEmpty() || blocks.get(blocks.size() - 1)[1] != best)) blocks.add(new int[] {i, best});
                    }
                    if (blocks.size() > 1) {
                        View band = new View(this) {
                            @Override protected void onDraw(android.graphics.Canvas canvas) {
                                android.graphics.Paint paint = new android.graphics.Paint(); double end = t.length == 0 ? 1 : Math.max(1, t[t.length - 1]);
                                for (int i = 0; i + 1 < t.length; i++) {
                                    if (lead[i] < 0) continue;
                                    paint.setColor(filamentColour[lead[i]]);
                                    canvas.drawRect((float) (t[i] / end * getWidth()), 0, (float) (t[i + 1] / end * getWidth()) + 1, getHeight(), paint);
                                }
                                paint.setStyle(android.graphics.Paint.Style.STROKE); paint.setColor((muted & 0x00ffffff) | 0x99000000); paint.setStrokeWidth(dp(1));
                                canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
                            }
                        };
                        List<String> words = new ArrayList<>();
                        for (int[] b : blocks) words.add("From " + ChartView.elapsed(t[b[0]]) + ": mostly " + names[b[1]].toLowerCase(Locale.ROOT));
                        label(chartBox, "The filament laid down most at each moment.", 13, muted, false);
                        band.setContentDescription("Filament over time: " + String.join("; ", words));
                        LinearLayout.LayoutParams bandLayout = new LinearLayout.LayoutParams(-1, dp(24)); bandLayout.bottomMargin = dp(4); chartBox.addView(band, bandLayout);
                        if (words.size() <= 12) label(chartBox, String.join("\n", words), 12, muted, false);
                    }
                    ChartView view = new ChartView(this, ink, muted, grid, surface);
                    view.set("Laying down", " g/h", t, lines, 0, Double.NaN); warmUp(view);
                    if (shown != null && shown.start > 0) view.clock(shown.start);
                    chartBox.addView(view, new LinearLayout.LayoutParams(-1, dp(220)));
                    // The total so far: each filament's grams added up over the build (all of them together in words, so no line hides a filament's own).
                    double[][] usedSoFar = usedGrams(cumulative, gramsPerMm3);
                    List<ChartView.Series> totals = new ArrayList<>(); int shownTools = 0;
                    for (int k = 0; k < tools; k++) {
                        if (toolTotals[k] <= 0) continue; shownTools++;
                        double[] values = new double[t.length]; for (int i = 0; i < t.length; i++) values[i] = usedSoFar[i][k];
                        int[] same = new int[t.length]; Arrays.fill(same, filamentColour[k]);
                        totals.add(new ChartView.Series(names[k], filamentColour[k], values, false).coloured(same));
                    }
                    // All of them together, dashed, from where a second filament comes in (before that it is one filament's line).
                    double[] together = sharedTotal(usedSoFar, tools);
                    boolean splits = false; for (double v : together) if (!Double.isNaN(v)) { splits = true; break; }
                    if (splits) totals.add(new ChartView.Series("All filaments", ink, together, true));
                    int last = -1; for (int i = t.length - 1; i >= 0 && last < 0; i--) if (!Double.isNaN(usedSoFar[i][tools])) last = i;
                    double planned = 0; for (int k = 0; k < tools; k++) planned += toolTotals[k] * gramsPerMm3[k];
                    if (last >= 0) {
                        StringBuilder each = new StringBuilder();
                        if (shownTools > 1) for (int k = 0; k < tools; k++) if (toolTotals[k] > 0) each.append(each.length() == 0 ? " (" : ", ").append(names[k]).append(String.format(Locale.getDefault(), " %.1f g", usedSoFar[last][k]));
                        if (each.length() > 0) each.append(")");
                        label(chartBox, String.format(Locale.getDefault(), "Filament used by %s: %.1f g of the %.1f g sliced%s.", ChartView.elapsed(t[last]), usedSoFar[last][tools], planned, each), 13, ink, false);
                    }
                    ChartView usedView = new ChartView(this, ink, muted, grid, surface);
                    usedView.set("Used so far", " g", t, totals, 0, Double.NaN); warmUp(usedView);
                    if (shown != null && shown.start > 0) usedView.clock(shown.start);
                    chartBox.addView(usedView, new LinearLayout.LayoutParams(-1, dp(220)));
                    printedByType(chartBox, t, typeSoFar);
                }
                if (used != null) for (SlicedMaterials.Entry entry : used.entries) {
                    if (entry.lengthMm == null && entry.massG == null) continue;
                    LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(android.view.Gravity.CENTER_VERTICAL); byColour.addView(row);
                    String hex = TrayPlan.colour(entry.color), sliced = hex == null ? null : WorkshopUi.colourName(hex);
                    if (printed != null && entry.index < printed.length && printed[entry.index] >= 0) hex = String.format(Locale.ROOT, "#%06X", printed[entry.index]);
                    if (hex != null) { View dot = new View(this); GradientDrawable shape = new GradientDrawable(); shape.setShape(GradientDrawable.OVAL); shape.setColor(Color.parseColor(hex)); shape.setStroke(dp(1), muted); dot.setBackground(shape);
                        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(dp(18), dp(18)); size.rightMargin = dp(10); row.addView(dot, size); }
                    String colour = hex == null ? null : WorkshopUi.colourName(hex);
                    boolean differs = sliced != null && colour != null && !sliced.equals(colour);
                    label(row, "Filament " + (entry.index + 1) + (entry.type == null ? "" : " · " + entry.type) + (colour == null ? "" : " · " + colour) + (differs ? " (sliced as " + sliced + ")" : "")
                        + (entry.massG == null ? "" : String.format(Locale.getDefault(), " · %.1f g", entry.massG))
                        + (entry.lengthMm == null ? "" : String.format(Locale.getDefault(), " · %.2f m", entry.lengthMm / 1000)), 13, ink, false);
                }
            });
        }, "recording-material").start();
    }

    /**
     * The all-filaments total, but only from the reading before a second filament has been used (NaN until then), so it
     * never lies over a single filament's own line.
     */
    static double[] sharedTotal(double[][] used, int tools) {
        double[] out = new double[used.length]; Arrays.fill(out, Double.NaN);
        for (int i = 0; i < used.length; i++) {
            int inUse = 0; for (int k = 0; k < tools; k++) if (used[i][k] > 0.05) inUse++;
            if (inUse < 2) continue;
            out[i] = used[i][tools];
            for (int j = i - 1; j >= 0; j--) if (!Double.isNaN(used[j][tools])) { if (Double.isNaN(out[j])) out[j] = used[j][tools]; break; }
        }
        return out;
    }

    /**
     * Grams of each filament laid down by each reading (from `cumulative`, mm³ per tool), with all of them together in the last
     * column. NaN where the reading has no progress.
     */
    static double[][] usedGrams(double[][] cumulative, double[] gramsPerMm3) {
        int tools = gramsPerMm3.length; double[][] out = new double[cumulative.length][tools + 1];
        for (int i = 0; i < cumulative.length; i++) {
            double sum = 0; boolean known = cumulative[i].length >= tools && tools > 0 && !Double.isNaN(cumulative[i][0]);
            for (int k = 0; k < tools; k++) { out[i][k] = known ? cumulative[i][k] * gramsPerMm3[k] : Double.NaN; if (known) sum += out[i][k]; }
            out[i][tools] = known ? sum : Double.NaN;
        }
        return out;
    }

    /** The volume printed so far over the print's time, stacked by line type in the viewer's colours. */
    private void printedByType(LinearLayout box, double[] t, double[][] at) {
        if (at == null) return;
        double[][] soFar = soFarByType(at);
        List<ChartView.Series> parts = new ArrayList<>();
        for (int type = 0; type < soFar.length; type++) {
            double end = soFar[type].length == 0 ? 0 : soFar[type][soFar[type].length - 1];
            if (end > 1e-4) parts.add(new ChartView.Series(GcodeToolpath.FEATURES[type], Color.parseColor(GcodeViewerActivity.PALETTE[type]), soFar[type], false));
        }
        if (parts.isEmpty()) return;
        ChartView view = new ChartView(this, ink, muted, grid, surface).stacked();
        view.set("Printed so far, by line type", " cm³", t, parts, 0, Double.NaN); warmUp(view);
        if (shown != null && shown.start > 0) view.clock(shown.start);
        LinearLayout.LayoutParams at4 = new LinearLayout.LayoutParams(-1, dp(260)); at4.topMargin = dp(8);
        box.addView(view, at4);
    }
    /**
     * [type][reading]: each line type's volume printed so far (cm³), from typeVolumesAt's [reading][type] mm³. A reading
     * with no progress keeps the one before (0 before the first), so the stack does not drop to nothing there. Pure.
     */
    static double[][] soFarByType(double[][] at) {
        int types = GcodeToolpath.FEATURES.length; double[][] out = new double[types][at.length]; double[] last = new double[types];
        for (int i = 0; i < at.length; i++) {
            boolean known = at[i].length > 0 && !Double.isNaN(at[i][0]);
            for (int k = 0; k < types; k++) { if (known && k < at[i].length) last[k] = at[i][k] / 1000; out[k][i] = last[k]; }
        }
        return out;
    }

    /**
     * Grams per hour of each filament at each reading: the material laid down per tool (`cumulative`, mm³, from the print's
     * progress) over a window around the reading (ten minutes, wider for long prints). The printer's progress moves in whole
     * percents, so the rate is measured between the readings where it stepped (first and last step in the window) rather
     * than between the window's edges, which would count three steps one time and four the next: a sawtooth. NaN where
     * there is no progress to go on.
     */
    static double[][] depositionRates(double[] t, double[][] cumulative, double[] gramsPerMm3) {
        int n = t.length, tools = gramsPerMm3.length; double[][] out = new double[n][tools];
        double window = Math.max(600, (n == 0 ? 0 : t[n - 1] - t[0]) / 20);
        boolean[] step = new boolean[n]; double before = Double.NaN;
        for (int i = 0; i < n; i++) {
            if (Double.isNaN(cumulative[i][0])) continue;
            double total = 0; for (int k = 0; k < tools; k++) total += cumulative[i][k];
            step[i] = !Double.isNaN(before) && total != before; before = total;
        }
        int lo = 0, hi = 0;
        for (int i = 0; i < n; i++) {
            while (lo < i && t[lo] < t[i] - window / 2) lo++;
            while (hi + 1 < n && t[hi + 1] <= t[i] + window / 2) hi++;
            int a = lo, b = Math.max(hi, i);
            while (a < b && Double.isNaN(cumulative[a][0])) a++;
            while (b > a && Double.isNaN(cumulative[b][0])) b--;
            int fa = -1, lb = -1; for (int j = a; j <= b; j++) if (step[j]) { if (fa < 0) fa = j; lb = j; }
            if (fa >= 0 && lb > fa) { a = fa; b = lb; }   // two steps or more: step to step
            double span = t[b] - t[a];
            for (int k = 0; k < tools; k++)
                out[i][k] = span <= 0 || Double.isNaN(cumulative[i][0]) ? Double.NaN : Math.max(0, cumulative[b][k] - cumulative[a][k]) / span * gramsPerMm3[k] * 3600;
        }
        return out;
    }

    /** Every reading of the print: exact numbers, and the non-visual alternative to the charts. */
    private void table(PrintRecorder.Recording recording, List<double[]> rows) {
        String[] columns = {"time_ms", "elapsed_s", "progress", "layer", "nozzle_c", "bed_c", "chamber_c", "part_fan_pct"};
        String[] titles = {"Clock", "Elapsed", "%", "Layer", "Nozzle", "Bed", "Chamber", "Fan %"};
        StringBuilder text = new StringBuilder();
        for (String title : titles) text.append(String.format(Locale.ROOT, "%-9s", title));
        for (double[] row : rows) {
            text.append('\n');
            for (int i = 0; i < columns.length; i++) {
                double value = row[PrintRecorder.column(columns[i])];
                String cell = i == 0 ? ChartView.clockOfDay((long) ChartView.localSeconds((long) value))
                    : i == 1 ? ChartView.elapsed(value) : ChartView.format(value);
                text.append(String.format(Locale.ROOT, "%-9s", cell));
            }
        }
        TextView view = new TextView(this); view.setTypeface(Typeface.MONOSPACE); view.setTextSize(12); view.setTextColor(ink); view.setText(text); view.setTextIsSelectable(true);
        view.setPadding(dp(16), dp(8), dp(16), dp(8));
        HorizontalScrollView wide = new HorizontalScrollView(this); wide.addView(view); ScrollView tall = new ScrollView(this); tall.addView(wide);
        new AlertDialog.Builder(this).setTitle("All readings (" + rows.size() + ")").setView(tall).setPositiveButton("Close", null).show();
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == VIDEO) {
            File video = madeVideo; madeVideo = null;
            if (video == null || result != RESULT_OK || data == null || data.getData() == null) { if (video != null) video.delete(); return; }
            try (InputStream input = new FileInputStream(video); OutputStream output = getContentResolver().openOutputStream(data.getData())) {
                if (output == null) throw new IOException();
                byte[] buffer = new byte[65536]; int count; while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                Toast.makeText(this, "Timelapse saved.", Toast.LENGTH_SHORT).show();
            } catch (IOException error) { Toast.makeText(this, "Saving failed. Try choosing a different place to save.", Toast.LENGTH_SHORT).show(); }
            video.delete(); return;
        }
        if (request == ADD_GCODE) {
            PrintRecorder.Recording recording = addingTo; List<double[]> rows = addingRows; addingTo = null; addingRows = null;
            if (recording == null || result != RESULT_OK || data == null || data.getData() == null) return;
            Uri uri = data.getData(); String name = displayName(uri);
            new Thread(() -> {
                File copy = null; String failed = null;
                try {
                    copy = File.createTempFile("recording-gcode-", ".gcode", getCacheDir());
                    try (InputStream in = getContentResolver().openInputStream(uri); OutputStream out = new FileOutputStream(copy)) {
                        if (in == null) throw new IOException("the file could not be opened");
                        byte[] buffer = new byte[65536]; int n; long total = 0;
                        while ((n = in.read(buffer)) != -1) { total += n; if (total > GcodeLibrary.MAX_FILE_BYTES) throw new IOException("the file is too large"); out.write(buffer, 0, n); }
                    }
                } catch (IOException e) { failed = e.getMessage(); if (copy != null) copy.delete(); copy = null; }
                File got = copy; String why = failed;
                runOnUiThread(() -> {
                    if (got == null) { Toast.makeText(this, "Could not read it: " + why, Toast.LENGTH_LONG).show(); return; }
                    if (isDestroyed()) { got.delete(); return; }
                    checkAndKeep(recording, rows, got, name == null ? recording.file : name, true);
                });
            }, "recording-gcode-copy").start();
            return;
        }
        if (request != EXPORT || result != RESULT_OK || data == null || data.getData() == null || shown == null) return;
        Uri uri = data.getData();
        try (InputStream input = new FileInputStream(shown.csv); OutputStream output = getContentResolver().openOutputStream(uri)) {
            if (output == null) throw new IOException();
            byte[] buffer = new byte[8192]; int count; while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            Toast.makeText(this, "CSV exported.", Toast.LENGTH_SHORT).show();
        } catch (IOException error) { Toast.makeText(this, "Export failed. Try choosing a different place to save.", Toast.LENGTH_SHORT).show(); }
    }

    @Override protected void onResume() { super.onResume(); if (getIntent().getStringExtra(EXTRA_META) == null) showList(); }

    private ChartView chart(LinearLayout parent, String title, String unit, double[] t, List<ChartView.Series> series, double min, double max) {
        // Skip series that never reported (for example, fans the firmware does not report).
        List<ChartView.Series> present = new ArrayList<>();
        for (ChartView.Series s : series) for (double v : s.values) if (!Double.isNaN(v)) { present.add(s); break; }
        if (present.isEmpty()) return null;
        ChartView view = new ChartView(this, ink, muted, grid, surface);
        view.set(title, unit, t, present, min, max);
        if (shown != null && shown.start > 0) view.clock(shown.start);
        warmUp(view);
        LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(-1, dp(240)); at.topMargin = dp(4); parent.addView(view, at);
        return view;
    }

    /** Warm-up shaded and the pictures' ticks, on a chart over the print's time. */
    private void warmUp(ChartView view) {
        for (Object[] phase : warmUp) view.span((Double) phase[0], (Double) phase[1], (String) phase[2]);
        if (pictureTimes.length > 0) view.pictures(pictureTimes, this::pictureThumb, this::openPicture);
    }
    /** The time into the print (s) of each picture, from its name. Pure. */
    static double[] pictureTimes(List<File> pictures) {
        double[] out = new double[pictures.size()];
        for (int i = 0; i < out.length; i++) try { out[i] = Double.parseDouble(pictures.get(i).getName().replace(".jpg", "")); } catch (NumberFormatException odd) { out[i] = Double.NaN; }
        return out;
    }
    /** The layer printing when each picture was taken (the last reading at or before it), for the layer charts; NaN when unknown. Pure. */
    static double[] pictureLayers(double[] times, double[] t, double[] layer) {
        double[] out = new double[times.length];
        for (int i = 0; i < times.length; i++) {
            out[i] = Double.NaN;
            for (int k = 0; k < t.length && t[k] <= times[i]; k++) if (!Double.isNaN(layer[k]) && layer[k] >= 1) out[i] = layer[k];
        }
        return out;
    }
    private android.graphics.Bitmap pictureThumb(int i) {
        if (i < 0 || i >= chartPictures.size()) return null;
        return pictureThumbs.computeIfAbsent(i, k -> decode(chartPictures.get(k), dp(150)));
    }
    private void openPicture(int i) {
        if (i < 0 || i >= chartPictures.size()) return;
        File file = chartPictures.get(i); String at = ChartView.elapsed(pictureTimes[i]);
        ImageView big = new ImageView(this); big.setAdjustViewBounds(true); big.setImageBitmap(decode(file, getResources().getDisplayMetrics().widthPixels));
        new AlertDialog.Builder(this).setTitle(at + " into the print").setView(big).setPositiveButton("Close", null).show();
    }

    /**
     * The print's warm-up, from its first reading to the first layer: each stretch named by the printer's step (as the
     * status line names it: getting the file, heating, homing, levelling) and, once it reports printing but before layer 1,
     * "the file's start" (its own start code). {from s, to s, name}; empty when the recording begins already printing. Pure.
     */
    static List<Object[]> warmUpPhases(double[] sub, double[] layer, double[] t) {
        List<Object[]> out = new ArrayList<>();
        int end = -1;
        for (int i = 0; i < t.length; i++) if (!Double.isNaN(sub[i]) && (int) sub[i] == 2075 && !Double.isNaN(layer[i]) && layer[i] >= 1) { end = i; break; }
        if (end <= 0) return out;
        for (int i = 0; i < end; i++) {
            String name = warmUpStep(Double.isNaN(sub[i]) ? -1 : (int) sub[i]);
            if (name == null) continue;
            Object[] open = out.isEmpty() ? null : out.get(out.size() - 1);
            if (open != null && open[2].equals(name) && (Double) open[1] == t[i]) { open[1] = t[i + 1]; continue; }
            out.add(new Object[] {t[i], t[i + 1], name});
        }
        return out;
    }
    /** A warm-up step's name for a print's sub status, or null for the ones that are not warm-up (paused, stopping…). */
    static String warmUpStep(int sub) {
        switch (sub) {
            case 2075: return "the file's start";
            case 2501: case 2502: case 2505: case 2401: case 2402: case 2503: case 2504: case 2077: case -1: return null;
            default: {
                String state = StatusPresentation.state(statusOf(sub));
                return state.startsWith("Preparing: ") ? state.substring(11) : "preparing";
            }
        }
    }
    private static org.json.JSONObject statusOf(int sub) {
        try { return new org.json.JSONObject().put("machine_status", new org.json.JSONObject().put("status", 2).put("sub_status", sub)); } catch (org.json.JSONException impossible) { return new org.json.JSONObject(); }
    }
    /** "Warm-up: 7 min before the first layer (heating the bed 3 min, levelling the bed 2 min, …)", or "". Pure. */
    static String warmUpWords(List<Object[]> phases) {
        if (phases.isEmpty()) return "";
        java.util.LinkedHashMap<String, Double> each = new java.util.LinkedHashMap<>(); double all = 0;
        for (Object[] p : phases) { double span = (Double) p[1] - (Double) p[0]; each.merge((String) p[2], span, Double::sum); all += span; }
        List<String> parts = new ArrayList<>();
        for (java.util.Map.Entry<String, Double> e : each.entrySet()) parts.add(e.getKey() + " " + ChartView.spanWords(e.getValue()));
        return "Warm-up: " + ChartView.spanWords(all) + " before the first layer (" + String.join(", ", parts) + "). Shaded on the charts.";
    }

    static double[] column(List<double[]> rows, String name) {
        int index = PrintRecorder.column(name); double[] values = new double[rows.size()];
        for (int i = 0; i < values.length; i++) values[i] = rows.get(i)[index];
        return values;
    }

    private void heading(String text) { TextView view = label(text, 22, ink, true); view.setPadding(0, 0, 0, dp(4)); }
    private TextView label(String text, int size, int color, boolean bold) { return label(content, text, size, color, bold); }
    private TextView label(LinearLayout parent, String text, int size, int color, boolean bold) {
        TextView view = new TextView(this); view.setText(text); view.setTextSize(size); view.setTextColor(color); if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setPadding(0, dp(3), 0, dp(3)); parent.addView(view); return view;
    }
    private LinearLayout card() { return card(content); }
    private LinearLayout card(LinearLayout parent) {
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(dp(16), dp(12), dp(16), dp(12));
        GradientDrawable shape = new GradientDrawable(); shape.setColor(surface); shape.setCornerRadius(dp(20)); card.setBackground(shape);
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2); layout.topMargin = dp(12); parent.addView(card, layout); return card;
    }
    private void tile(LinearLayout row, String caption, String value, int gap) {
        LinearLayout tile = new LinearLayout(this); tile.setOrientation(LinearLayout.VERTICAL); tile.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable shape = new GradientDrawable(); shape.setColor(surface); shape.setCornerRadius(dp(16)); tile.setBackground(shape);
        label(tile, caption, 12, muted, false); TextView text = label(tile, value, 16, ink, true); text.setMaxLines(2);
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(0, -1, 1); layout.leftMargin = gap; layout.topMargin = dp(8); row.addView(tile, layout);
    }
    /** "Last reading 10:53 · progress 100 % · …" as one capitalised reading a line, each unit kept with its number. */
    static String readingLines(String reading) {
        StringBuilder out = new StringBuilder();
        for (String part : reading.split(" · ")) {
            if (part.isEmpty()) continue;
            if (out.length() > 0) out.append('\n');
            out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1).replace(" °C", "\u00a0°C").replace(" %", "\u00a0%"));
        }
        return out.toString();
    }
    /** A button in the app's style: accent text on a soft rounded tile, equal width in its row. */
    private void action(LinearLayout row, String text, Runnable run) {
        Button button = new A11y.DimButton(this); button.setText(text); button.setAllCaps(false); button.setMinHeight(dp(48)); button.setOnClickListener(v -> run.run());
        int accent = dark ? 0xff5fd4c4 : 0xff00796b; button.setTextColor(accent); button.setPadding(dp(10), dp(8), dp(10), dp(8));
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable(); shape.setColor(dark ? 0xff21343a : 0xffe2efed); shape.setCornerRadius(dp(24));
        button.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(dark ? 0x4463d5c7 : 0x33006b65), shape, null));
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(0, -2, 1); layout.topMargin = dp(8); if (row.getChildCount() > 0) layout.leftMargin = dp(8); row.addView(button, layout);
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
