package io.github.thelastfrogrammer.elink;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Two print recordings side by side, layer by layer: how long each layer took, the temperatures, fan and speed on it, and
 * the camera pictures at the same layer. For seeing what a change of settings (or filament, or room) did. Only reads.
 */
public final class CompareActivity extends Activity {
    static final String EXTRA_A = "compareA", EXTRA_B = "compareB";
    private int ink, muted, surface, grid, background, blue, orange;
    private LinearLayout content;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        int appearance = getSharedPreferences("workshop-settings", MODE_PRIVATE).getInt("theme", 0);
        boolean dark = appearance == 2 || appearance == 0 && (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        setTheme(dark ? R.style.WorkshopDark : R.style.WorkshopLight);
        ink = dark ? 0xffe6eef1 : 0xff17252c; muted = dark ? 0xff9fb3bb : 0xff5a6d76; background = dark ? 0xff0e1417 : 0xfff2f5f6;
        surface = dark ? 0xff182227 : Color.WHITE; grid = dark ? 0xff2a3a40 : 0xffe1e6e6;
        blue = dark ? 0xff3987e5 : 0xff2a78d6; orange = dark ? 0xffd95926 : 0xffeb6834;
        ScrollView scroll = new ScrollView(this); scroll.setBackgroundColor(background); scroll.setFitsSystemWindows(true);
        content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(dp(16), dp(16), dp(16), dp(24)); scroll.addView(content);
        setContentView(scroll);
        String a = getIntent().getStringExtra(EXTRA_A), b = getIntent().getStringExtra(EXTRA_B);
        PrintRecorder.Recording first, second; List<double[]> rowsA, rowsB;
        try {
            first = PrintRecorder.Recording.read(new File(a)); second = PrintRecorder.Recording.read(new File(b));
            rowsA = PrintRecorder.samples(first); rowsB = PrintRecorder.samples(second);
        } catch (Exception unreadable) { label(content, "One of the two recordings could not be read.", 15, ink, false); return; }
        show(first, rowsA, second, rowsB);
    }

    private void show(PrintRecorder.Recording a, List<double[]> rowsA, PrintRecorder.Recording b, List<double[]> rowsB) {
        label(content, "Compare two prints", 22, ink, true);
        LinearLayout who = card();
        swatchRow(who, blue, "Print 1: " + name(a)); swatchRow(who, orange, "Print 2: " + name(b));
        label(who, "Layer by layer: the same layer number lines up, whatever the time it was printed at.", 12, muted, false);
        // The numbers side by side.
        LinearLayout facts = card(); label(facts, "In short", 15, ink, true);
        String[][] rows = {
            {"Time", RecordingAnalysis.clock(a.duration() / 1000.0), RecordingAnalysis.clock(b.duration() / 1000.0)},
            {"Result", RecordingsActivity.outcome(a.outcome), RecordingsActivity.outcome(b.outcome)},
            {"Layers", String.valueOf(Math.max(a.layers, RecordingAnalysis.lastLayer(RecordingsActivity.column(rowsA, "layer")))), String.valueOf(Math.max(b.layers, RecordingAnalysis.lastLayer(RecordingsActivity.column(rowsB, "layer"))))},
            {"Filament fed", String.format(Locale.getDefault(), "%.2f m", a.extrudedMm / 1000), String.format(Locale.getDefault(), "%.2f m", b.extrudedMm / 1000)},
            {"Warm-up", warmUp(rowsA), warmUp(rowsB)},
            {"Pictures", String.valueOf(a.pictures), String.valueOf(b.pictures)}};
        for (String[] r : rows) {
            LinearLayout line = new LinearLayout(this); line.setOrientation(LinearLayout.HORIZONTAL); facts.addView(line);
            cell(line, r[0], muted, 1.1f); cell(line, r[1], blue, 1); cell(line, r[2], orange, 1);
        }
        // Charts by layer.
        int layers = Math.max(RecordingAnalysis.lastLayer(RecordingsActivity.column(rowsA, "layer")), RecordingAnalysis.lastLayer(RecordingsActivity.column(rowsB, "layer")));
        if (layers < 2) { label(card(), "Too few layers recorded to compare layer by layer.", 14, muted, false); }
        else {
            double[] x = new double[layers]; for (int i = 0; i < layers; i++) x[i] = i + 1;
            LinearLayout charts = card(); label(charts, "Layer by layer", 15, ink, true);
            double[] ta = minutes(RecordingAnalysis.layerSeconds(RecordingsActivity.column(rowsA, "elapsed_s"), RecordingsActivity.column(rowsA, "layer"), layers, !"printing".equals(a.outcome)));
            double[] tb = minutes(RecordingAnalysis.layerSeconds(RecordingsActivity.column(rowsB, "elapsed_s"), RecordingsActivity.column(rowsB, "layer"), layers, !"printing".equals(b.outcome)));
            chart(charts, "Time per layer", " min", x, ta, tb, "Print 1", "Print 2");
            for (String[] c : new String[][] {{"nozzle_c", "Nozzle temperature", " °C"}, {"bed_c", "Bed temperature", " °C"}, {"chamber_c", "Chamber temperature", " °C"}, {"part_fan_pct", "Part fan", " %"}, {"speed", "Speed (as the printer reports it)", ""}})
                chart(charts, c[1] + " (mean on the layer)", c[2], x, RecordingAnalysis.perLayerMean(RecordingsActivity.column(rowsA, "layer"), RecordingsActivity.column(rowsA, c[0]), layers),
                    RecordingAnalysis.perLayerMean(RecordingsActivity.column(rowsB, "layer"), RecordingsActivity.column(rowsB, c[0]), layers), "Print 1", "Print 2");
            pictures(a, rowsA, b, rowsB, layers);
        }
    }

    /** The two prints' pictures at the same layer, with a slider through the layers. */
    private void pictures(PrintRecorder.Recording a, List<double[]> rowsA, PrintRecorder.Recording b, List<double[]> rowsB, int layers) {
        List<File> pa = PrintRecorder.pictures(a), pb = PrintRecorder.pictures(b);
        if (pa.isEmpty() || pb.isEmpty()) return;
        double[] la = RecordingsActivity.pictureLayers(RecordingsActivity.pictureTimes(pa), RecordingsActivity.column(rowsA, "elapsed_s"), RecordingsActivity.column(rowsA, "layer"));
        double[] lb = RecordingsActivity.pictureLayers(RecordingsActivity.pictureTimes(pb), RecordingsActivity.column(rowsB, "elapsed_s"), RecordingsActivity.column(rowsB, "layer"));
        LinearLayout card = card(); label(card, "Pictures at the same layer", 15, ink, true);
        TextView at = label(card, "", 13, muted, false);
        LinearLayout pair = new LinearLayout(this); pair.setOrientation(LinearLayout.HORIZONTAL); card.addView(pair);
        ImageView left = new ImageView(this), right = new ImageView(this);
        for (ImageView v : new ImageView[] {left, right}) { v.setAdjustViewBounds(true); v.setScaleType(ImageView.ScaleType.FIT_CENTER); }
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, -2, 1); half.rightMargin = dp(4); pair.addView(left, half);
        LinearLayout.LayoutParams other = new LinearLayout.LayoutParams(0, -2, 1); other.leftMargin = dp(4); pair.addView(right, other);
        TextView under = label(card, "", 12, muted, false);
        SeekBar slider = new SeekBar(this); slider.setMax(Math.max(1, layers - 1)); card.addView(slider, new LinearLayout.LayoutParams(-1, dp(48)));
        slider.setContentDescription("Layer");
        int width = getResources().getDisplayMetrics().widthPixels / 2;
        Runnable[] showLayer = new Runnable[1];
        showLayer[0] = () -> {
            int layer = slider.getProgress() + 1, i = RecordingAnalysis.pictureAtLayer(la, layer), k = RecordingAnalysis.pictureAtLayer(lb, layer);
            at.setText("Layer " + layer + " of " + layers);
            left.setImageBitmap(i < 0 ? null : RecordingsActivity.decode(pa.get(i), width)); right.setImageBitmap(k < 0 ? null : RecordingsActivity.decode(pb.get(k), width));
            left.setContentDescription(i < 0 ? "Print 1: no picture" : "Print 1 at layer " + (int) la[i]); right.setContentDescription(k < 0 ? "Print 2: no picture" : "Print 2 at layer " + (int) lb[k]);
            under.setText("Print 1: " + (i < 0 ? "—" : "layer " + (int) la[i]) + "   ·   Print 2: " + (k < 0 ? "—" : "layer " + (int) lb[k]));
        };
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int value, boolean user) { showLayer[0].run(); }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        slider.setProgress(layers / 2); showLayer[0].run();
    }

    private void chart(LinearLayout parent, String title, String unit, double[] x, double[] a, double[] b, String nameA, String nameB) {
        boolean any = false; for (double v : a) any |= !Double.isNaN(v); for (double v : b) any |= !Double.isNaN(v);
        if (!any) return;
        ChartView view = new ChartView(this, ink, muted, grid, surface).layers();
        view.set(title, unit, x, Arrays.asList(new ChartView.Series(nameA, blue, a, false), new ChartView.Series(nameB, orange, b, false)), Double.NaN, Double.NaN);
        LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(-1, dp(240)); at.topMargin = dp(8); parent.addView(view, at);
    }
    private static double[] minutes(double[] seconds) { double[] out = new double[seconds.length]; for (int i = 0; i < out.length; i++) out[i] = seconds[i] / 60; return out; }
    private static String warmUp(List<double[]> rows) {
        List<Object[]> phases = RecordingsActivity.warmUpPhases(RecordingsActivity.column(rows, "sub_status"), RecordingsActivity.column(rows, "layer"), RecordingsActivity.column(rows, "elapsed_s"));
        double total = 0; for (Object[] p : phases) total += (Double) p[1] - (Double) p[0];
        return phases.isEmpty() ? "—" : ChartView.spanWords(total);
    }
    static String name(PrintRecorder.Recording r) {
        String file = StatusPresentation.clean(r.file.isEmpty() ? "Print" : r.file.replaceFirst("(?i)\\.gcode$", ""));
        return file + " · " + new java.text.SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(new java.util.Date(r.start));
    }

    private void swatchRow(LinearLayout parent, int colour, String text) {
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(android.view.Gravity.CENTER_VERTICAL); parent.addView(row);
        android.view.View dot = new android.view.View(this); GradientDrawable shape = new GradientDrawable(); shape.setColor(colour); shape.setCornerRadius(dp(3)); dot.setBackground(shape);
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(dp(14), dp(14)); size.rightMargin = dp(8); row.addView(dot, size);
        label(row, text, 14, ink, false);
    }
    private void cell(LinearLayout row, String text, int colour, float weight) {
        TextView v = new TextView(this); v.setText(text); v.setTextSize(13); v.setTextColor(colour == muted ? muted : ink); v.setPadding(0, dp(3), dp(6), dp(3));
        if (colour != muted) { v.setCompoundDrawablePadding(dp(6)); GradientDrawable mark = new GradientDrawable(); mark.setColor(colour); mark.setSize(dp(6), dp(14)); mark.setCornerRadius(dp(2)); v.setCompoundDrawablesRelativeWithIntrinsicBounds(mark, null, null, null); }
        row.addView(v, new LinearLayout.LayoutParams(0, -2, weight));
    }
    private TextView label(LinearLayout parent, String text, int size, int color, boolean bold) {
        TextView view = new TextView(this); view.setText(text); view.setTextSize(size); view.setTextColor(color); if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setPadding(0, dp(3), 0, dp(3)); parent.addView(view); return view;
    }
    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(dp(16), dp(12), dp(16), dp(12));
        GradientDrawable shape = new GradientDrawable(); shape.setColor(surface); shape.setCornerRadius(dp(20)); card.setBackground(shape);
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2); layout.topMargin = dp(12); content.addView(card, layout); return card;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
