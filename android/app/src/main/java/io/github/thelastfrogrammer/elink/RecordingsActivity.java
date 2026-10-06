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
        blue = dark ? 0xff3987e5 : 0xff2a78d6; orange = dark ? 0xffd95926 : 0xffeb6834; aqua = dark ? 0xff199e70 : 0xff1baf7a;
        ScrollView scroll = new ScrollView(this); scroll.setBackgroundColor(background); scroll.setFitsSystemWindows(true);
        content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(dp(16), dp(16), dp(16), dp(24)); scroll.addView(content);
        setContentView(scroll);
        String meta = getIntent().getStringExtra(EXTRA_META);
        if (meta == null) showList(); else showRecording(new File(meta));
    }

    static File directory(android.content.Context context) { return new File(context.getFilesDir(), "recordings"); }

    private void showList() {
        content.removeAllViews();
        heading("Print recordings");
        label("Every print is recorded while the app is monitoring it, locally or through the Elegoo cloud. With the app closed, recording continues only during a local connection or with cloud background watching on.", 13, muted, false);
        List<PrintRecorder.Recording> recordings = new PrintRecorder(directory(this)).list();
        if (recordings.isEmpty()) { label("No recordings yet. Start or watch a print.", 15, ink, false); return; }
        SimpleDateFormat date = new SimpleDateFormat("d MMM HH:mm", Locale.getDefault());
        for (PrintRecorder.Recording recording : recordings) {
            LinearLayout card = card();
            TextView name = label(card, StatusPresentation.clean(recording.file.replaceFirst("(?i)\\.gcode$", "")), 16, ink, true);
            name.setSingleLine(true); name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            label(card, date.format(new Date(recording.start)) + " · " + duration(recording.duration()) + " · " + outcome(recording.outcome) + " · " + recording.samples + " samples", 13, muted, false);
            card.setOnClickListener(v -> startActivity(new Intent(this, RecordingsActivity.class).putExtra(EXTRA_META, recording.meta.getAbsolutePath())));
        }
    }

    private void showRecording(File meta) {
        content.removeAllViews();
        PrintRecorder.Recording recording; List<double[]> rows;
        try { recording = PrintRecorder.Recording.read(meta); rows = PrintRecorder.samples(recording); }
        catch (Exception error) { heading("Recording"); label("This recording could not be read.", 15, ink, false); return; }
        shown = recording;
        heading(StatusPresentation.clean(recording.file.replaceFirst("(?i)\\.gcode$", "")));
        label(new SimpleDateFormat("d MMM yyyy HH:mm", Locale.getDefault()).format(new Date(recording.start)) + " · " + StatusPresentation.clean(recording.printer), 13, muted, false);
        // Summary tiles.
        LinearLayout tiles = new LinearLayout(this); tiles.setOrientation(LinearLayout.HORIZONTAL); content.addView(tiles);
        tile(tiles, "Duration", duration(recording.duration()), 0);
        tile(tiles, "Outcome", outcome(recording.outcome), dp(8));
        tile(tiles, "Layers", recording.layers > 0 ? String.valueOf(recording.layers) : "—", dp(8));
        double[] t = column(rows, "elapsed_s");
        chart("Progress", " %", t, Collections.singletonList(new ChartView.Series("Progress", blue, column(rows, "progress"), false)), 0, 100);
        chart("Layer", "", t, Collections.singletonList(new ChartView.Series("Layer", blue, column(rows, "layer"), false)), Double.NaN, Double.NaN);
        chart("Temperatures", " °C", t, Arrays.asList(
            new ChartView.Series("Nozzle", blue, column(rows, "nozzle_c"), false), new ChartView.Series("Nozzle target", blue, column(rows, "nozzle_target_c"), true),
            new ChartView.Series("Bed", orange, column(rows, "bed_c"), false), new ChartView.Series("Bed target", orange, column(rows, "bed_target_c"), true),
            new ChartView.Series("Chamber", aqua, column(rows, "chamber_c"), false)), Double.NaN, Double.NaN);
        chart("Fans", " %", t, Arrays.asList(
            new ChartView.Series("Part", blue, column(rows, "part_fan_pct"), false), new ChartView.Series("Auxiliary", orange, column(rows, "aux_fan_pct"), false),
            new ChartView.Series("Chamber", aqua, column(rows, "chamber_fan_pct"), false)), 0, 100);
        label("Touch and drag on a chart to read values. Dashed lines are targets.", 12, muted, false);
        LinearLayout actions = new LinearLayout(this); actions.setOrientation(LinearLayout.HORIZONTAL); content.addView(actions);
        action(actions, "Data table", () -> table(recording, rows));
        action(actions, "Export CSV…", () -> startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/csv").addCategory(Intent.CATEGORY_OPENABLE)
            .putExtra(Intent.EXTRA_TITLE, recording.csv.getName()), EXPORT));
        action(actions, "Delete…", () -> new AlertDialog.Builder(this).setTitle("Delete this recording?").setNegativeButton("Cancel", null)
            .setPositiveButton("Delete", (d, w) -> { new PrintRecorder(directory(this)).delete(recording); finish(); }).show());
    }

    /** The table view: every sample's key values, for reading exact numbers and as the non-visual alternative to the charts. */
    private void table(PrintRecorder.Recording recording, List<double[]> rows) {
        String[] columns = {"elapsed_s", "progress", "layer", "nozzle_c", "bed_c", "chamber_c", "part_fan_pct"};
        String[] titles = {"Time", "%", "Layer", "Nozzle", "Bed", "Chamber", "Fan %"};
        StringBuilder text = new StringBuilder();
        for (String title : titles) text.append(String.format(Locale.ROOT, "%-8s", title));
        for (double[] row : rows) {
            text.append('\n');
            for (int i = 0; i < columns.length; i++) {
                double value = row[PrintRecorder.column(columns[i])];
                text.append(String.format(Locale.ROOT, "%-8s", i == 0 ? ChartView.elapsed(value) : ChartView.format(value)));
            }
        }
        TextView view = new TextView(this); view.setTypeface(Typeface.MONOSPACE); view.setTextSize(12); view.setTextColor(ink); view.setText(text); view.setTextIsSelectable(true);
        view.setPadding(dp(16), dp(8), dp(16), dp(8));
        HorizontalScrollView wide = new HorizontalScrollView(this); wide.addView(view); ScrollView tall = new ScrollView(this); tall.addView(wide);
        new AlertDialog.Builder(this).setTitle("Samples (" + rows.size() + ")").setView(tall).setPositiveButton("Close", null).show();
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != EXPORT || result != RESULT_OK || data == null || data.getData() == null || shown == null) return;
        Uri uri = data.getData();
        try (InputStream input = new FileInputStream(shown.csv); OutputStream output = getContentResolver().openOutputStream(uri)) {
            if (output == null) throw new IOException();
            byte[] buffer = new byte[8192]; int count; while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            Toast.makeText(this, "CSV exported.", Toast.LENGTH_SHORT).show();
        } catch (IOException error) { Toast.makeText(this, "Export failed.", Toast.LENGTH_SHORT).show(); }
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
        card.addView(view, new LinearLayout.LayoutParams(-1, dp(240)));
    }

    private static double[] column(List<double[]> rows, String name) {
        int index = PrintRecorder.column(name); double[] values = new double[rows.size()];
        for (int i = 0; i < values.length; i++) values[i] = rows.get(i)[index];
        return values;
    }
    static String duration(long millis) { long minutes = millis / 60000; return minutes >= 60 ? minutes / 60 + "h " + minutes % 60 + "m" : minutes + "m"; }
    static String outcome(String outcome) {
        switch (outcome) {
            case "complete": return "Completed"; case "stopped": return "Stopped"; case "printing": return "In progress or interrupted";
            case "interrupted": return "Replaced by another print"; case "ended": return "Ended"; default: return "Unknown";
        }
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
        Button button = new Button(this); button.setText(text); button.setAllCaps(false); button.setOnClickListener(v -> run.run());
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(0, -2, 1); layout.topMargin = dp(8); if (row.getChildCount() > 0) layout.leftMargin = dp(8); row.addView(button, layout);
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
