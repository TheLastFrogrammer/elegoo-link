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

/** Recorded prints: a list, and per print a summary, charts, a data table and CSV export. */
public final class RecordingsActivity extends Activity {
    public static final String EXTRA_META = "meta";
    private static final int EXPORT = 1, VIDEO = 2;
    /** The timelapse just made, waiting for a place to save it. */
    private File madeVideo;
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
            .then(4600, () -> { View used = chart(5); if (used != null) { Showcase.glideTo(scroll, used, 1800); Showcase.caption(this, "And how much was used, filament by filament"); } })
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
            label("Prints are recorded automatically while the app is connected to the printer. Connect first, then start a print and it will appear here.", 14, muted, false);
            LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); content.addView(row);
            action(row, "Open Settings to connect", () -> startActivity(new Intent(this, MainActivity.class).putExtra(MainActivity.EXTRA_PAGE, FeatureIndex.SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)));
            return;
        }
        label("Each print is recorded automatically, newest first. Unfinished means the app stopped watching before the print ended.", 13, muted, false);
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
        // Opened from the Print tab during a print: the other recordings are one tap away.
        LinearLayout back = new LinearLayout(this); back.setOrientation(LinearLayout.HORIZONTAL); content.addView(back);
        action(back, "All recordings", () -> startActivity(new Intent(this, RecordingsActivity.class)));
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
        String last = lastReading(rows);
        if (!last.isEmpty()) { LinearLayout card = card(); label(card, "At the end", 15, ink, true); label(card, last, 13, muted, false); }
        double[] t = column(rows, "elapsed_s");
        pictures(recording);   // the quickest look at how the print went, before the charts
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
        trays(rows, t);
        material(recording, rows, t);
        LinearLayout actions = new LinearLayout(this); actions.setOrientation(LinearLayout.HORIZONTAL); content.addView(actions);
        action(actions, "All readings…", () -> table(recording, rows));
        action(actions, "Export CSV…", () -> startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/csv").addCategory(Intent.CATEGORY_OPENABLE)
            .putExtra(Intent.EXTRA_TITLE, recording.csv.getName()), EXPORT));
        action(actions, "Delete…", () -> new AlertDialog.Builder(this).setTitle("Delete this recording?").setNegativeButton("Cancel", null)
            .setPositiveButton("Delete", (d, w) -> { new PrintRecorder(directory(this)).delete(recording); finish(); }).show());
    }

    // ---- Pictures, trays and material ----

    /** The print's camera pictures in a strip, each with its time into the print; tap for the full picture. */
    private void pictures(PrintRecorder.Recording recording) {
        List<File> files = PrintRecorder.pictures(recording);
        if (files.isEmpty() && recording.pictureMinutes == 0) return;
        LinearLayout card = card(); label(card, "Camera pictures", 15, ink, true);
        if (files.isEmpty()) { label(card, "No pictures yet. One is taken every " + recording.pictureMinutes + " min from the printer's camera on this network, or through the cloud while the camera is open in Live toolpath.", 13, muted, false); return; }
        label(card, files.size() + (files.size() == 1 ? " picture" : " pictures") + (recording.pictureMinutes > 0 ? " · every " + recording.pictureMinutes + " min" : "") + ". Tap one to see it larger.", 13, muted, false);
        if (files.size() >= 2) { LinearLayout videoRow = new LinearLayout(this); videoRow.setOrientation(LinearLayout.HORIZONTAL); card.addView(videoRow); action(videoRow, "Make a timelapse video…", () -> makeVideo(recording, files)); }
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
    /** Turns the pictures into a short MP4 on the phone (about ten seconds), then asks where to save it. */
    private void makeVideo(PrintRecorder.Recording recording, List<File> files) {
        java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        TextView state = new TextView(this); state.setTextSize(15); state.setTextColor(ink); state.setPadding(dp(24), dp(16), dp(24), 0);
        state.setText("Making the video: 0 of " + files.size() + " pictures"); A11y.polite(state);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Timelapse video").setView(state).setCancelable(false)
            .setNegativeButton("Cancel", (d, w) -> cancelled.set(true)).show();
        File folder = new File(getCacheDir(), "timelapse"); folder.mkdirs();
        File[] old = folder.listFiles(); if (old != null) for (File f : old) f.delete();
        File out = new File(folder, recording.csv.getName().replaceFirst("\\.csv$", "") + "-timelapse.mp4");
        new Thread(() -> {
            String failure = null;
            try { TimelapseEncoder.encode(files, out, cancelled, (done, total) -> runOnUiThread(() -> state.setText("Making the video: " + done + " of " + total + " pictures"))); }
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

    /** Which CANVAS tray fed the print over time: a colour band, and the changes in words. */
    private void trays(List<double[]> rows, double[] t) {
        double[] tray = column(rows, "tray"), rgb = column(rows, "tray_rgb");
        List<String> changes = trayChanges(tray, rgb, t);
        if (changes.isEmpty()) return;
        LinearLayout card = card(); label(card, "Filament over time", 15, ink, true);
        label(card, "Which CANVAS tray fed the print, and when it switched.", 13, muted, false);
        View band = new View(this) {
            @Override protected void onDraw(android.graphics.Canvas canvas) {
                android.graphics.Paint paint = new android.graphics.Paint(); double end = t.length == 0 ? 1 : Math.max(1, t[t.length - 1]);
                for (int i = 0; i < t.length; i++) {
                    if (Double.isNaN(tray[i])) continue;
                    double next = i + 1 < t.length ? t[i + 1] : end;
                    paint.setColor(Double.isNaN(rgb[i]) ? muted : 0xff000000 | (int) rgb[i]);
                    canvas.drawRect((float) (t[i] / end * getWidth()), 0, (float) (next / end * getWidth()), getHeight(), paint);
                }
            }
        };
        band.setContentDescription("Filament over time: " + String.join("; ", changes));
        LinearLayout.LayoutParams bandLayout = new LinearLayout.LayoutParams(-1, dp(28)); bandLayout.topMargin = dp(4); card.addView(band, bandLayout);
        label(card, String.join("\n", changes), 13, muted, false);
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
    private void material(PrintRecorder.Recording recording, List<double[]> rows, double[] t) {
        LinearLayout card = card(); label(card, "Material used", 15, ink, true);
        TextView note = label(card, "Reading the print's file…", 13, muted, false);
        LinearLayout chartBox = new LinearLayout(this); chartBox.setOrientation(LinearLayout.VERTICAL); card.addView(chartBox);
        LinearLayout byColour = new LinearLayout(this); byColour.setOrientation(LinearLayout.VERTICAL); card.addView(byColour);
        File file = new GcodeLibrary(new File(getFilesDir(), "gcode-library")).find(recording.file);
        if (file == null) { note.setText("The print's file is not on this phone, so material is not shown. Download it from the Files tab, or slice it here, to see material per layer and per colour."); return; }
        new Thread(() -> {
            double[] volumes = null; double[][] laidDown = null; double[] perTool = null; SlicedMaterials materials = null;
            double[] progress = column(rows, "progress"), fractions = new double[progress.length];
            for (int i = 0; i < progress.length; i++) fractions[i] = Double.isNaN(progress[i]) ? Double.NaN : progress[i] / 100.0;
            try {
                GcodeToolpath path = GcodeToolpath.read(file); volumes = path.layerVolumes();
                int tools = path.toolCount(); laidDown = path.toolVolumesAt(fractions, tools); perTool = path.toolVolumes(tools);
            } catch (Exception ignored) { }
            try { materials = GcodeInspector.inspect(file).materials; } catch (Exception ignored) { }
            double[] perLayer = volumes; double[][] cumulative = laidDown; double[] toolTotals = perTool; SlicedMaterials used = materials;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                note.setText(perLayer == null || cumulative == null ? "The print's file could not be read."
                    : "How fast each filament was laid down (grams per hour) and how much was used in all, from the print's progress and the sliced file.");
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
                    view.set("Laying down", " g/h", t, lines, 0, Double.NaN);
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
                    usedView.set("Used so far", " g", t, totals, 0, Double.NaN);
                    if (shown != null && shown.start > 0) usedView.clock(shown.start);
                    chartBox.addView(usedView, new LinearLayout.LayoutParams(-1, dp(220)));
                }
                if (used != null) for (SlicedMaterials.Entry entry : used.entries) {
                    if (entry.lengthMm == null && entry.massG == null) continue;
                    LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(android.view.Gravity.CENTER_VERTICAL); byColour.addView(row);
                    String hex = TrayPlan.colour(entry.color);
                    if (hex != null) { View dot = new View(this); GradientDrawable shape = new GradientDrawable(); shape.setShape(GradientDrawable.OVAL); shape.setColor(Color.parseColor(hex)); shape.setStroke(dp(1), muted); dot.setBackground(shape);
                        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(dp(18), dp(18)); size.rightMargin = dp(10); row.addView(dot, size); }
                    String colour = hex == null ? null : WorkshopUi.colourName(hex);
                    label(row, "Filament " + (entry.index + 1) + (entry.type == null ? "" : " · " + entry.type) + (colour == null ? "" : " · " + colour)
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

    /**
     * Grams per hour of each filament at each reading: the material laid down per tool (`cumulative`, mm³, from the print's
     * progress) over a window of a few minutes around the reading (wider for long prints), so a progress that moves in whole
     * percents still gives a smooth line. NaN where there is no progress to go on.
     */
    static double[][] depositionRates(double[] t, double[][] cumulative, double[] gramsPerMm3) {
        int n = t.length, tools = gramsPerMm3.length; double[][] out = new double[n][tools];
        double window = Math.max(300, (n == 0 ? 0 : t[n - 1] - t[0]) / 30);
        int lo = 0, hi = 0;
        for (int i = 0; i < n; i++) {
            while (lo < i && t[lo] < t[i] - window / 2) lo++;
            while (hi + 1 < n && t[hi + 1] <= t[i] + window / 2) hi++;
            int a = lo, b = Math.max(hi, i);
            while (a < b && Double.isNaN(cumulative[a][0])) a++;
            while (b > a && Double.isNaN(cumulative[b][0])) b--;
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
            } catch (IOException error) { Toast.makeText(this, "Saving failed. Check that you chose a place you can write to.", Toast.LENGTH_SHORT).show(); }
            video.delete(); return;
        }
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
