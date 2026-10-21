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
        int[] c = colours(dark); int blue = c[0], orange = c[1], aqua = c[2];
        float density = context.getResources().getDisplayMetrics().density;
        double[] layer = new double[p.layers]; for (int i = 0; i < layer.length; i++) layer[i] = i + 1;
        List<View> views = new ArrayList<>(); List<String> names = new ArrayList<>();
        List<ChartView.Series> parts = new ArrayList<>();
        for (int type : p.typesUsed()) parts.add(new ChartView.Series(GcodeToolpath.FEATURES[type], Color.parseColor(GcodeViewerActivity.PALETTE[type]), p.series(type), false));
        ChartView cross = new ChartView(context, ink, muted, grid, surface).layers().stacked();
        cross.set("Cross section by line type", " mm²", layer, parts, 0, Double.NaN);
        views.add(cross); names.add("Section");
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

        LinearLayout chips = new LinearLayout(context); chips.setOrientation(LinearLayout.HORIZONTAL); card.addView(chips);
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
