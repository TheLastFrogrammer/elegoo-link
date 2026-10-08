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
import android.widget.*;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

/** Recorded prints: a list, and per print a summary, charts, a data table and CSV export. */
public final class RecordingsActivity extends Activity {
    public static final String EXTRA_META = "meta";
    private static final int EXPORT = 1;
    private boolean dark;
    private int ink, muted, background, surface, grid;
    /** Validated categorical slots 1-3 (blue, orange, aqua), stepped per mode; see the dataviz reference palette. */
    private int blue, orange, aqua;
    private LinearLayout content;
    private PrintRecorder.Recording shown;

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
    }

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

    // ---- Screens ----

    private void showList() {
        content.removeAllViews();
        heading("Print recordings");
        List<PrintRecorder.Recording> recordings = new PrintRecorder(directory(this)).list();
        if (recordings.isEmpty()) {
            label("No recordings yet.", 16, ink, true);
            label("Each print is recorded while the app watches the printer, newest first. Connect to the printer, then start a print.", 14, muted, false);
            LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); content.addView(row);
            action(row, "Open Settings to connect", () -> startActivity(new Intent(this, MainActivity.class).putExtra(MainActivity.EXTRA_PAGE, 3)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)));
            return;
        }
        label("Each print the app watches is recorded automatically, newest first. Unfinished means the app stopped watching before the print ended.", 13, muted, false);
        for (PrintRecorder.Recording recording : recordings) {
            LinearLayout card = card();
            TextView name = label(card, StatusPresentation.clean(recording.file.replaceFirst("(?i)\\.gcode$", "")), 16, ink, true);
            name.setSingleLine(true); name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            label(card, rowSummary(recording, Locale.getDefault(), TimeZone.getDefault()), 13, muted, false);
            card.setOnClickListener(v -> startActivity(new Intent(this, RecordingsActivity.class).putExtra(EXTRA_META, recording.meta.getAbsolutePath())));
        }
    }

    private void showRecording(File meta) {
        content.removeAllViews();
        PrintRecorder.Recording recording; List<double[]> rows;
        try { recording = PrintRecorder.Recording.read(meta); rows = PrintRecorder.samples(recording); }
        catch (Exception error) { heading("Recording"); label("This recording could not be opened. It may have been deleted or damaged. Go back and choose another one.", 15, ink, false); return; }
        shown = recording;
        heading(StatusPresentation.clean(recording.file.replaceFirst("(?i)\\.gcode$", "")));
        String details = when(recording.start, Locale.getDefault(), TimeZone.getDefault()) + " · " + StatusPresentation.clean(recording.printer);
        if (recording.layers > 0) details += " · " + recording.layers + " layers";
        label(details, 13, muted, false);
        // Summary tiles: how long, how it ended, and when it ended on the clock.
        long ended = recording.end > 0 ? recording.end : recording.lastSample;
        LinearLayout tiles = new LinearLayout(this); tiles.setOrientation(LinearLayout.HORIZONTAL); content.addView(tiles);
        tile(tiles, "Duration", duration(recording.duration()), 0);
        tile(tiles, "Result", outcome(recording.outcome), dp(8));
        tile(tiles, "Ended", ended > 0 ? when(ended, Locale.getDefault(), TimeZone.getDefault()) : "—", dp(8));
        String last = lastReading(rows);
        if (!last.isEmpty()) { LinearLayout card = card(); label(card, "At the end", 15, ink, true); label(card, last, 13, muted, false); }
        double[] t = column(rows, "elapsed_s");
        chart("Progress", " %", t, Collections.singletonList(new ChartView.Series("Progress", blue, column(rows, "progress"), false)), 0, 100);
        chart("Layer number", "", t, Collections.singletonList(new ChartView.Series("Layer", blue, column(rows, "layer"), false)), Double.NaN, Double.NaN);
        chart("Temperatures", " °C", t, Arrays.asList(
            new ChartView.Series("Nozzle", blue, column(rows, "nozzle_c"), false), new ChartView.Series("Nozzle target", blue, column(rows, "nozzle_target_c"), true),
            new ChartView.Series("Bed", orange, column(rows, "bed_c"), false), new ChartView.Series("Bed target", orange, column(rows, "bed_target_c"), true),
            new ChartView.Series("Chamber", aqua, column(rows, "chamber_c"), false)), Double.NaN, Double.NaN);
        chart("Fan speed", " %", t, Arrays.asList(
            new ChartView.Series("Part fan", blue, column(rows, "part_fan_pct"), false), new ChartView.Series("Aux fan", orange, column(rows, "aux_fan_pct"), false),
            new ChartView.Series("Chamber fan", aqua, column(rows, "chamber_fan_pct"), false)), 0, 100);
        label("Touch and drag on a chart to read values. Times along the bottom are clock times on this phone. Dashed lines are targets.", 12, muted, false);
        LinearLayout actions = new LinearLayout(this); actions.setOrientation(LinearLayout.HORIZONTAL); content.addView(actions);
        action(actions, "All readings", () -> table(recording, rows));
        action(actions, "Export CSV…", () -> startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/csv").addCategory(Intent.CATEGORY_OPENABLE)
            .putExtra(Intent.EXTRA_TITLE, recording.csv.getName()), EXPORT));
        action(actions, "Delete…", () -> new AlertDialog.Builder(this).setTitle("Delete this recording?").setNegativeButton("Cancel", null)
            .setPositiveButton("Delete", (d, w) -> { new PrintRecorder(directory(this)).delete(recording); finish(); }).show());
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
        if (request != EXPORT || result != RESULT_OK || data == null || data.getData() == null || shown == null) return;
        Uri uri = data.getData();
        try (InputStream input = new FileInputStream(shown.csv); OutputStream output = getContentResolver().openOutputStream(uri)) {
            if (output == null) throw new IOException();
            byte[] buffer = new byte[8192]; int count; while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            Toast.makeText(this, "CSV exported.", Toast.LENGTH_SHORT).show();
        } catch (IOException error) { Toast.makeText(this, "Export failed. Check that you chose a place you can write to.", Toast.LENGTH_SHORT).show(); }
    }

    @Override protected void onResume() { super.onResume(); if (getIntent().getStringExtra(EXTRA_META) == null) showList(); }

    private void chart(String title, String unit, double[] t, List<ChartView.Series> series, double min, double max) {
        // Skip series that never reported (for example, fans the firmware does not report).
        List<ChartView.Series> present = new ArrayList<>();
        for (ChartView.Series s : series) for (double v : s.values) if (!Double.isNaN(v)) { present.add(s); break; }
        if (present.isEmpty()) return;
        LinearLayout card = card();
        ChartView view = new ChartView(this, ink, muted, grid, surface);
        view.set(title, unit, t, present, min, max);
        if (shown != null && shown.start > 0) view.clock(shown.start);
        card.addView(view, new LinearLayout.LayoutParams(-1, dp(240)));
    }

    private static double[] column(List<double[]> rows, String name) {
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
    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(dp(16), dp(12), dp(16), dp(12));
        GradientDrawable shape = new GradientDrawable(); shape.setColor(surface); shape.setCornerRadius(dp(20)); card.setBackground(shape);
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2); layout.topMargin = dp(12); content.addView(card, layout); return card;
    }
    private void tile(LinearLayout row, String caption, String value, int gap) {
        LinearLayout tile = new LinearLayout(this); tile.setOrientation(LinearLayout.VERTICAL); tile.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable shape = new GradientDrawable(); shape.setColor(surface); shape.setCornerRadius(dp(16)); tile.setBackground(shape);
        label(tile, caption, 12, muted, false); TextView text = label(tile, value, 16, ink, true); text.setMaxLines(2);
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(0, -1, 1); layout.leftMargin = gap; layout.topMargin = dp(8); row.addView(tile, layout);
    }
    private void action(LinearLayout row, String text, Runnable run) {
        Button button = new A11y.DimButton(this); button.setText(text); button.setAllCaps(false); button.setMinHeight(dp(48)); button.setOnClickListener(v -> run.run());
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(0, -2, 1); layout.topMargin = dp(8); if (row.getChildCount() > 0) layout.leftMargin = dp(8); row.addView(button, layout);
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
