package io.github.thelastfrogrammer.elink;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Each layer of a G-code file as charts (LayerProfile): the cross section by line type, stacked in the viewer's colours,
 * and the speed, flow and acceleration its lines run at, one at a time behind chips. Used by a recording and by the
 * Layer charts screen (a file before it is printed). Only reads the file.
 */
final class LayerCharts {
    /** Adds a chip-style button to a row, as the screen showing the charts draws them. */
    interface Chip { Button add(LinearLayout row, String text, Runnable action); }

    /** The print guardian's checks and the answers to its alarms, laid over the charts (a recording's; null before printing). */
    static final class Guardian {
        /** As Recording.guardianChecks: {layer, measure, value, limit, over 0/1, alarm 0/1}. */
        final List<double[]> checks;
        /** {layer, 1 "looks fine" or 0 "real problem"}. */
        final List<double[]> answers;
        Guardian(List<double[]> checks, List<double[]> answers) { this.checks = checks; this.answers = answers; }
        boolean any() { return !checks.isEmpty() || !answers.isEmpty(); }
        int lastLayer() { int last = 0; for (double[] c : checks) last = Math.max(last, (int) c[0]); for (double[] a : answers) last = Math.max(last, (int) a[0]); return last; }
    }
    static final int AMBER = 0xffe0a030, RED = 0xffd64545;

    /**
     * The marks to lay over every chart: one per layer and measure that went over its limit (the worst picture of that
     * layer; red when it raised the alarm), and each answer. {layer, colour} with its words. Pure, for tests.
     */
    static List<Object[]> marks(Guardian g, int teal) {
        java.util.Map<String, double[]> worst = new java.util.TreeMap<>();
        for (double[] c : g.checks) {
            if (c[4] < 0.5) continue;
            String key = String.format(Locale.ROOT, "%08d-%d", (int) c[0], (int) c[1]);
            double[] was = worst.get(key);
            boolean coverage = (int) c[1] == PrintRecorder.GUARDIAN_COVERAGE;
            if (was == null || c[5] > was[5] || c[5] == was[5] && (coverage ? c[2] < was[2] : c[2] > was[2])) worst.put(key, c);
        }
        List<Object[]> out = new ArrayList<>();
        for (double[] c : worst.values()) {
            int measure = (int) c[1];
            String name = measure >= 0 && measure < PrintRecorder.GUARDIAN_NAMES.length ? PrintRecorder.GUARDIAN_NAMES[measure] : "Check";
            out.add(new Object[] {c[0], c[5] > 0.5 ? RED : AMBER, String.format(Locale.getDefault(), "%s %s %.1f %% (limit %.1f %%)",
                c[5] > 0.5 ? "Guardian alarm:" : "Guardian:", name.toLowerCase(Locale.ROOT), c[2] * 100, c[3] * 100)});
        }
        for (double[] a : g.answers) out.add(new Object[] {a[0], a[1] > 0.5 ? teal : RED, a[1] > 0.5 ? "You said it looked fine" : "You said it was a real problem"});
        return out;
    }

    /** The chart colours for the app's theme: blue, orange, aqua (the validated palette's first slots). */
    static int[] colours(boolean dark) { return dark ? new int[] {0xff3987e5, 0xffd95926, 0xff199e70} : new int[] {0xff2a78d6, 0xffeb6834, 0xff149a69}; }

    /**
     * What to say above the charts: what they are, and the layers that ask for more flow than the filament's limit
     * (a common reason for thin, rough walls). Pure, for tests.
     */
    static String note(LayerProfile p) {
        StringBuilder words = new StringBuilder("What the file asks for at each layer; the printer may run slower (speed modes, its own limits). Touch a chart to read a layer.");
        if (p.maxFlow > 0) {
            List<String> over = new ArrayList<>(); int startRun = -1;
            for (int i = 0; i <= p.layers; i++) {
                boolean high = i < p.layers && p.flowMax[i] > p.maxFlow;
                if (high && startRun < 0) startRun = i;
                if (!high && startRun >= 0) { over.add(startRun + 1 == i ? String.valueOf(i) : (startRun + 1) + "–" + i); startRun = -1; }
            }
            if (!over.isEmpty()) words.append(String.format(Locale.getDefault(), "\nLayers %s ask for more flow than the filament's limit (%.0f mm³/s): those can come out thin or rough.",
                String.join(", ", over.subList(0, Math.min(5, over.size()))) + (over.size() > 5 ? "…" : ""), p.maxFlow));
        }
        return words.toString();
    }

    /** Adds the chips and charts for `p` to `card`; the chosen chart is remembered in `prefs`. */
    static void fill(Context context, LinearLayout card, LayerProfile p, int ink, int muted, int grid, int surface, boolean dark, SharedPreferences prefs, Chip chip) {
        fill(context, card, p, null, ink, muted, grid, surface, dark, prefs, chip);
    }
    /** As above, with the guardian's checks and answers laid over every chart and charted on their own (`p` may be null: then only that). */
    static void fill(Context context, LinearLayout card, LayerProfile p, Guardian guardian, int ink, int muted, int grid, int surface, boolean dark, SharedPreferences prefs, Chip chip) {
        int[] c = colours(dark); int blue = c[0], orange = c[1], aqua = c[2];
        float density = context.getResources().getDisplayMetrics().density;
        List<View> views = new ArrayList<>(); List<String> names = new ArrayList<>();
        if (p != null) profileCharts(context, p, ink, muted, grid, surface, blue, orange, aqua, views, names);
        if (guardian != null && guardian.any()) {
            int layers = Math.max(p == null ? 0 : p.layers, guardian.lastLayer());
            ChartView g = guardianChart(context, guardian, layers, ink, muted, grid, surface, new int[] {orange, blue, aqua, RED});
            if (g != null) { views.add(g); names.add("Guardian"); }
            int teal = dark ? 0xff5fd4c4 : 0xff00796b;
            for (Object[] m : marks(guardian, teal)) for (View v : views) ((ChartView) v).mark((Double) m[0], (Integer) m[1], (String) m[2]);
        }
        if (views.isEmpty()) return;
        chips(context, card, views, names, dark, prefs, chip, density);
    }

    /** Each measure the guardian kept, per layer (the highest of the layer's pictures; the lowest for coverage), against its limit (dashed). */
    private static ChartView guardianChart(Context context, Guardian g, int layers, int ink, int muted, int grid, int surface, int[] colours) {
        if (layers <= 0 || g.checks.isEmpty()) return null;
        double[] layer = new double[layers]; for (int i = 0; i < layers; i++) layer[i] = i + 1;
        List<ChartView.Series> lines = new ArrayList<>();
        for (int measure = 0; measure < PrintRecorder.GUARDIAN_NAMES.length; measure++) {
            double[] value = new double[layers], limit = new double[layers]; Arrays.fill(value, Double.NaN); Arrays.fill(limit, Double.NaN);
            boolean any = false, coverage = measure == PrintRecorder.GUARDIAN_COVERAGE;
            for (double[] c : g.checks) {
                int i = (int) c[0] - 1; if ((int) c[1] != measure || i < 0 || i >= layers) continue;
                any = true;
                value[i] = Double.isNaN(value[i]) ? c[2] * 100 : coverage ? Math.min(value[i], c[2] * 100) : Math.max(value[i], c[2] * 100);
                if (!Double.isNaN(c[3])) limit[i] = c[3] * 100;
            }
            if (!any) continue;
            lines.add(new ChartView.Series(PrintRecorder.GUARDIAN_NAMES[measure], colours[measure % colours.length], value, false));
            lines.add(new ChartView.Series(PrintRecorder.GUARDIAN_NAMES[measure] + " limit", colours[measure % colours.length], limit, true));
        }
        ChartView chart = new ChartView(context, ink, muted, grid, surface).layers();
        chart.set("Print guardian's measures (dashed: their limits)", " %", layer, lines, 0, Double.NaN);
        return chart;
    }

    private static void profileCharts(Context context, LayerProfile p, int ink, int muted, int grid, int surface, int blue, int orange, int aqua, List<View> views, List<String> names) {
        double[] layer = new double[p.layers]; for (int i = 0; i < layer.length; i++) layer[i] = i + 1;
        List<ChartView.Series> parts = new ArrayList<>();
        for (int type : p.typesUsed()) parts.add(new ChartView.Series(GcodeToolpath.FEATURES[type], Color.parseColor(GcodeViewerActivity.PALETTE[type]), p.series(type), false));
        ChartView cross = new ChartView(context, ink, muted, grid, surface).layers().stacked();
        cross.set("Cross section by line type", " mm²", layer, parts, 0, Double.NaN);
        views.add(cross); names.add("Section");
        List<ChartView.Series> soFar = new ArrayList<>();
        for (int type : p.typesUsed()) soFar.add(new ChartView.Series(GcodeToolpath.FEATURES[type], Color.parseColor(GcodeViewerActivity.PALETTE[type]), p.cumulative(type), false));
        ChartView volume = new ChartView(context, ink, muted, grid, surface).layers().stacked();
        volume.set("Volume printed by each layer, by line type", " cm³", layer, soFar, 0, Double.NaN);
        views.add(volume); names.add("Volume");
        ChartView speed = new ChartView(context, ink, muted, grid, surface).layers();
        speed.set("Print speed (mean of the lines)", " mm/s", layer, Collections.singletonList(new ChartView.Series("Speed", blue, p.column(p.speed), false)), 0, Double.NaN);
        views.add(speed); names.add("Speed");
        List<ChartView.Series> flowLines = new ArrayList<>(); flowLines.add(new ChartView.Series("Highest flow", orange, p.column(p.flowMax), false));
        if (p.maxFlow > 0) { double[] limit = new double[p.layers]; Arrays.fill(limit, p.maxFlow); flowLines.add(new ChartView.Series("Filament's limit", orange, limit, true)); }
        ChartView flow = new ChartView(context, ink, muted, grid, surface).layers();
        flow.set("Flow (filament melted per second)", " mm³/s", layer, flowLines, 0, Double.NaN);
        views.add(flow); names.add("Flow");
        ChartView accel = new ChartView(context, ink, muted, grid, surface).layers();
        accel.set("Acceleration", " mm/s²", layer, Arrays.asList(new ChartView.Series("Mean", blue, p.column(p.accel), false), new ChartView.Series("Highest", aqua, p.column(p.accelMax), false)), 0, Double.NaN);
        views.add(accel); names.add("Accel");
    }

    private static void chips(Context context, LinearLayout card, List<View> views, List<String> names, boolean dark, SharedPreferences prefs, Chip chip, float density) {
        LinearLayout chips = new LinearLayout(context); chips.setOrientation(LinearLayout.HORIZONTAL);
        android.widget.HorizontalScrollView slide = new android.widget.HorizontalScrollView(context); slide.setHorizontalScrollBarEnabled(false); slide.addView(chips); card.addView(slide);
        List<Button> buttons = new ArrayList<>(); int accent = dark ? 0xff5fd4c4 : 0xff00796b;
        Runnable[] pick = new Runnable[1];
        for (int i = 0; i < views.size(); i++) {
            int index = i;
            Button b = chip.add(chips, names.get(i), () -> { prefs.edit().putInt("layerChart", index).apply(); pick[0].run(); });
            b.setMaxLines(1); b.setTextSize(14); buttons.add(b);
            card.addView(views.get(i), new LinearLayout.LayoutParams(-1, Math.round(260 * density)));
        }
        pick[0] = () -> {
            int chosen = Math.min(prefs.getInt("layerChart", 0), views.size() - 1);
            for (int i = 0; i < views.size(); i++) {
                boolean on = i == chosen; views.get(i).setVisibility(on ? View.VISIBLE : View.GONE);
                buttons.get(i).setTextColor(on ? (dark ? 0xff0e1417 : Color.WHITE) : accent);
                if (buttons.get(i).getBackground() instanceof android.graphics.drawable.RippleDrawable)
                    ((android.graphics.drawable.RippleDrawable) buttons.get(i).getBackground()).getDrawable(0).setTint(on ? accent : (dark ? 0xff21343a : 0xffe2efed));
                A11y.state(buttons.get(i), on ? "Chosen" : "");
            }
        };
        pick[0].run();
    }

    private LayerCharts() { }
}
