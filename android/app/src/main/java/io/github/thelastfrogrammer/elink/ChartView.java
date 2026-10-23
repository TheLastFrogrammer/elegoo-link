package io.github.thelastfrogrammer.elink;

import android.content.Context;
import android.graphics.*;
import android.view.MotionEvent;
import android.view.View;
import java.util.*;

/**
 * Time-series line chart for print recordings. One y-axis per chart (never dual-axis); 2dp solid lines for measured values,
 * dashed lines for targets of the same series; legend above for 2+ series and direct end labels; a touch crosshair with a
 * readout. Colors come from the validated categorical palette (first three slots), text stays in ink colors.
 */
final class ChartView extends View {
    static final class Series {
        final String name; final int color; final double[] values; final boolean dashed;
        /** Optional colour per point: the line from a point to the next takes that point's colour (for example the filament printing then). */
        int[] colours;
        Series(String name, int color, double[] values, boolean dashed) { this.name = name; this.color = color; this.values = values; this.dashed = dashed; }
        Series coloured(int[] perPoint) { colours = perPoint; return this; }
    }

    private final float density;
    /** Text and its spacing follow the system font size (up to 1.6x, so the plot keeps room). */
    private final float f;
    private final int ink, muted, grid, surface;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG), small = new Paint(Paint.ANTI_ALIAS_FLAG),
        rule = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private String title = "", unit = "";
    private double[] seconds = new double[0];
    private final List<Series> series = new ArrayList<>();
    private double fixedMin = Double.NaN, fixedMax = Double.NaN;
    private int touched = -1;
    /** Start of the print in epoch milliseconds, or 0 when the axis should show elapsed time only. */
    private long clockStart;
    /** x is a layer number rather than seconds; series are stacked areas (each a share of the whole, its total on top). */
    private boolean layerAxis, stacked;
    ChartView layers() { layerAxis = true; invalidate(); return this; }
    ChartView stacked() { stacked = true; invalidate(); return this; }
    /** Marks at x values (layers): a coloured line across the plot, and their text in the readout at that point. */
    private final List<double[]> markAt = new ArrayList<>(); private final List<String> markText = new ArrayList<>();
    /**
     * When the print's camera pictures were taken (x, as the chart's x): a short tick each along the bottom of the plot. While
     * the chart is touched, `shown` hears the picture of that moment (the last one taken at or before it), for a preview
     * beside the chart: the readout itself stays small, so the data stays in view.
     */
    private double[] pictureAt = new double[0];
    private java.util.function.IntConsumer pictureShown;
    private int currentPicture = -1;
    /** The readout sits in the plot's top corner away from the finger; it changes sides only past 60 % across, so it does not flicker. */
    private boolean boxRight = true;
    ChartView pictures(double[] at, java.util.function.IntConsumer shown) { pictureAt = at == null ? new double[0] : at; pictureShown = shown; invalidate(); return this; }
    /** The picture of a moment: the last taken at or before x, else the first after it; -1 with none. Pure. */
    static int pictureAtOrBefore(double[] at, double x) {
        int below = -1, above = -1;
        for (int i = 0; i < at.length; i++) {
            if (Double.isNaN(at[i])) continue;
            if (at[i] <= x) { if (below < 0 || at[i] >= at[below]) below = i; }
            else if (above < 0 || at[i] < at[above]) above = i;
        }
        return below >= 0 ? below : above;
    }
    /** Shaded stretches of x behind the data (a print's warm-up phases), each named in the readout when touched inside it. */
    private final List<double[]> spanAt = new ArrayList<>(); private final List<String> spanText = new ArrayList<>();
    ChartView span(double from, double to, String text) { spanAt.add(new double[] {from, to}); spanText.add(text); invalidate(); return this; }
    ChartView mark(double x, int colour, String text) { markAt.add(new double[] {x, colour}); markText.add(text); invalidate(); return this; }
    /** The stacked total at point i (NaN where every series is). */
    private double total(int i) { double sum = 0; boolean any = false; for (Series s : series) if (i < s.values.length && !Double.isNaN(s.values[i])) { sum += s.values[i]; any = true; } return any ? sum : Double.NaN; }

    ChartView(Context context, int ink, int muted, int grid, int surface) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        f = Math.min(1.6f, Math.max(1f, context.getResources().getConfiguration().fontScale));
        this.ink = ink; this.muted = muted; this.grid = grid; this.surface = surface;
        line.setStyle(Paint.Style.STROKE); line.setStrokeWidth(2 * density); line.setStrokeJoin(Paint.Join.ROUND); line.setStrokeCap(Paint.Cap.ROUND);
        text.setColor(ink); text.setTextSize(14 * density * f); text.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        small.setColor(muted); small.setTextSize(11 * density * f);
        rule.setStrokeWidth(Math.max(1, density * 0.75f));
        setMinimumHeight(Math.round(220 * density * f));
        pinch = new android.view.ScaleGestureDetector(context, new android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(android.view.ScaleGestureDetector d) { focusX = d.getFocusX(); return true; }
            @Override public boolean onScale(android.view.ScaleGestureDetector d) {
                double[] r = range(); float left = plotLeft(), right = getWidth() - plotRight(), w = Math.max(1, right - left);
                double at = r[0] + (focusX - left) / w * (r[1] - r[0]);   // the time under the fingers stays under them
                double span = (r[1] - r[0]) / Math.max(0.2f, d.getCurrentSpanX() > 0 && d.getPreviousSpanX() > 0 ? d.getCurrentSpanX() / d.getPreviousSpanX() : d.getScaleFactor());
                double shift = -(d.getFocusX() - focusX) / w * span;   // two fingers moving together move the view
                double from = at - (focusX - left) / w * span + shift;
                zoomTo(from, from + span); focusX = d.getFocusX(); return true;
            }
        });
        taps = new android.view.GestureDetector(context, new android.view.GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDoubleTap(MotionEvent e) { if (zoomed()) { viewX0 = Double.NaN; viewX1 = Double.NaN; touched = -1; invalidate(); } return zoomed(); }
        });
    }

    // ---- Zoom: two fingers pinch the time (or layer) axis and move along it; double tap shows it all again ----
    private final android.view.ScaleGestureDetector pinch;
    private final android.view.GestureDetector taps;
    private double viewX0 = Double.NaN, viewX1 = Double.NaN;
    private float focusX; private boolean twoFingers;
    /** The x range shown: all of the data, or the zoomed part of it. */
    double[] range() {
        double x0 = min(seconds), x1 = Math.max(x0 + 1, max(seconds));
        if (Double.isNaN(viewX0)) return new double[] {x0, x1};
        return new double[] {Math.max(x0, viewX0), Math.min(x1, viewX1)};
    }
    boolean zoomed() { return !Double.isNaN(viewX0); }
    /** Shows x from..to, kept inside the data and no narrower than a few readings (or two layers). */
    void zoomTo(double from, double to) {
        double x0 = min(seconds), x1 = Math.max(x0 + 1, max(seconds)), full = x1 - x0;
        double least = layerAxis ? Math.min(full, 4) : Math.min(full, Math.max(60, full / 200));
        double span = Math.max(least, Math.min(full, to - from));
        if (span >= full - 1e-9) { viewX0 = Double.NaN; viewX1 = Double.NaN; invalidate(); return; }
        from = Math.max(x0, Math.min(x1 - span, from));
        viewX0 = from; viewX1 = from + span; invalidate();
    }
    private boolean visible(int i, double[] r) { return i < seconds.length && seconds[i] >= r[0] && seconds[i] <= r[1]; }

    /** x values in seconds since the print started; NaN values leave gaps. A fixed range (e.g. 0-100 %) may be given. */
    void set(String title, String unit, double[] seconds, List<Series> series, double min, double max) {
        this.title = title; this.unit = unit; this.seconds = seconds; this.series.clear(); this.series.addAll(series);
        fixedMin = min; fixedMax = max; touched = -1;
        StringBuilder description = new StringBuilder(title).append(" chart");
        for (Series s : series) {
            double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
            for (double v : s.values) if (!Double.isNaN(v)) { lo = Math.min(lo, v); hi = Math.max(hi, v); }
            description.append(", ").append(s.name).append(" latest ").append(format(last(s.values))).append(unit);
            if (!Double.isInfinite(lo)) description.append(", lowest ").append(format(lo)).append(unit).append(", highest ").append(format(hi)).append(unit);
        }
        setContentDescription(description.toString());
        invalidate();
    }

    /** Shows the time axis and readout as clock times on this phone, counted from the print's start. */
    void clock(long startMillis) { clockStart = startMillis; invalidate(); }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), resolveSize(Math.round(240 * density * f), heightSpec));
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (seconds.length == 0) return false;
        float left = plotLeft(), right = getWidth() - plotRight();
        pinch.onTouchEvent(event); taps.onTouchEvent(event);
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) twoFingers = false;
        if (event.getPointerCount() > 1 || pinch.isInProgress()) { twoFingers = true; touched = -1; getParent().requestDisallowInterceptTouchEvent(true); invalidate(); return true; }
        if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) { touched = -1; invalidate(); return true; }
        if (twoFingers) return true;   // the rest of a pinch, after one finger lifted: no readout jump
        getParent().requestDisallowInterceptTouchEvent(true);
        float across = (event.getX() - left) / Math.max(1, right - left);
        if (across > 0.6f) boxRight = false; else if (across < 0.4f) boxRight = true;
        double[] shown = range();
        double t = shown[0] + (event.getX() - left) / Math.max(1, right - left) * Math.max(1e-9, shown[1] - shown[0]);
        int best = 0; for (int i = 1; i < seconds.length; i++) if (Math.abs(seconds[i] - t) < Math.abs(seconds[best] - t)) best = i;
        touched = best;
        int picture = pictureAtOrBefore(pictureAt, seconds[best]);
        if (picture != currentPicture) { currentPicture = picture; if (picture >= 0 && pictureShown != null) pictureShown.accept(picture); }
        invalidate(); return true;
    }

    private float plotLeft() { return 44 * density * f; }
    /** Room on the right for direct end labels when there are several series. */
    private float plotRight() { return (series.size() > 1 && !stacked ? 64 : 12) * density; }
    private float plotTop() { return series.size() > 1 ? (30 + 20 * legendRows(getWidth())) * density * f : 30 * density * f; }
    private float plotBottom() { return 24 * density * f; }

    @Override protected void onDraw(Canvas canvas) {
        canvas.drawText(title + (unit.isEmpty() ? "" : " (" + unit.trim() + ")"), 0, 16 * density * f, text);
        float left = plotLeft(), top = plotTop(), right = getWidth() - plotRight(), bottom = getHeight() - plotBottom();
        if (series.size() > 1) legend(canvas, 40 * density * f, getWidth());
        if (seconds.length < 2) { small.setTextAlign(Paint.Align.LEFT); canvas.drawText("Not enough samples yet.", left, (top + bottom) / 2, small); return; }
        double[] view = range();
        if (zoomed()) { small.setTextAlign(Paint.Align.RIGHT); canvas.drawText("Zoomed · double-tap for all", getWidth(), 16 * density * f, small); }
        double lo = fixedMin, hi = fixedMax;
        if (Double.isNaN(lo) || Double.isNaN(hi)) {
            lo = Double.POSITIVE_INFINITY; hi = Double.NEGATIVE_INFINITY;
            if (stacked) { lo = 0; hi = 0; for (int i = 0; i < seconds.length; i++) { double v = visible(i, view) ? total(i) : Double.NaN; if (!Double.isNaN(v)) hi = Math.max(hi, v); } }
            else for (Series s : series) for (int i = 0; i < s.values.length; i++) { double v = s.values[i]; if (!Double.isNaN(v) && visible(i, view)) { lo = Math.min(lo, v); hi = Math.max(hi, v); } }
            if (Double.isInfinite(lo)) { lo = 0; hi = 1; }
            if (hi - lo < 1e-9) { lo -= 1; hi += 1; }
            double pad = (hi - lo) * 0.08; lo = lo >= 0 && lo - pad < 0 ? 0 : lo - pad; hi += pad;
        }
        double step = niceStep((hi - lo) / 4); lo = Math.floor(lo / step) * step; hi = Math.ceil(hi / step) * step;
        double x0 = view[0], x1 = view[1];
        // Recessive grid and y ticks.
        rule.setColor(grid); small.setTextAlign(Paint.Align.RIGHT);
        for (double v = lo; v <= hi + step / 2; v += step) {
            float y = (float) (bottom - (v - lo) / (hi - lo) * (bottom - top));
            canvas.drawLine(left, y, right, y, rule);
            canvas.drawText(format(v), left - 6 * density, y + 4 * density, small);
        }
        // Time ticks: clock times on round hours and minutes when the print's start is known, otherwise elapsed time.
        small.setTextAlign(Paint.Align.CENTER);
        double xStep = layerAxis ? Math.max(1, niceStep((x1 - x0) / 4)) : niceTime((x1 - x0) / 4);
        if (layerAxis) for (double t = Math.ceil(x0 / xStep) * xStep; t <= x1; t += xStep) {
            float x = (float) (left + (t - x0) / (x1 - x0) * (right - left));
            canvas.drawText(format(t), x, bottom + 16 * density * f, small);
        } else if (clockStart > 0) {
            double local0 = localSeconds(clockStart);
            for (double local = Math.ceil((local0 + x0) / xStep) * xStep; local <= local0 + x1; local += xStep) {
                float x = (float) (left + (local - local0 - x0) / (x1 - x0) * (right - left));
                canvas.drawText(clockOfDay((long) local), x, bottom + 16 * density * f, small);
            }
        } else for (double t = Math.ceil(x0 / xStep) * xStep; t <= x1; t += xStep) {
            float x = (float) (left + (t - x0) / (x1 - x0) * (right - left));
            canvas.drawText(elapsed(t), x, bottom + 16 * density * f, small);
        }
        // The data stays inside the plot when zoomed.
        canvas.save(); canvas.clipRect(left - 2 * density, top - 2 * density, right + 2 * density, bottom + 2 * density);
        // Spans: a faint band each, alternating shades so neighbours stay apart, the first one's name on top when it fits.
        for (int k = 0; k < spanAt.size(); k++) {
            double a = Math.max(x0, spanAt.get(k)[0]), b = Math.min(x1, spanAt.get(k)[1]); if (b <= a) continue;
            float xa = (float) (left + (a - x0) / (x1 - x0) * (right - left)), xb = (float) (left + (b - x0) / (x1 - x0) * (right - left));
            fill.setColor((muted & 0x00ffffff) | (k % 2 == 0 ? 0x22000000 : 0x14000000)); canvas.drawRect(xa, top, xb, bottom, fill);
        }
        if (!spanAt.isEmpty()) {
            double a = Math.max(x0, spanAt.get(0)[0]), b = Math.min(x1, spanAt.get(spanAt.size() - 1)[1]);
            float xa = (float) (left + (a - x0) / (x1 - x0) * (right - left)), xb = (float) (left + (b - x0) / (x1 - x0) * (right - left));
            String name = "Warm-up"; small.setTextAlign(Paint.Align.LEFT);
            if (xb - xa > small.measureText(name) + 8 * density) canvas.drawText(name, xa + 4 * density, top + 12 * density * f, small);
        }
        // Pictures: a short tick each, rising from the bottom of the plot (the touched one taller, in ink).
        if (pictureAt.length > 0) {
            Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG); tick.setStrokeWidth(Math.max(1, 1.5f * density));
            for (int i = 0; i < pictureAt.length; i++) {
                if (Double.isNaN(pictureAt[i]) || pictureAt[i] < x0 || pictureAt[i] > x1) continue;
                float x = (float) (left + (pictureAt[i] - x0) / (x1 - x0) * (right - left));
                boolean on = touched >= 0 && i == currentPicture; tick.setColor(on ? ink : (muted & 0x00ffffff) | 0xb0000000);
                canvas.drawLine(x, bottom, x, bottom - (on ? 14 : 7) * density, tick);
            }
        }
        // Stacked: each series an area on top of the ones before, a thin gap of the background between them, the total on top.
        if (stacked) {
            double[] base = new double[seconds.length];
            for (Series s : series) {
                Path area = new Path(); boolean open = false; int first = -1, lastPoint = -1;
                for (int i = 0; i < seconds.length && i < s.values.length; i++) {
                    double v = Double.isNaN(s.values[i]) ? 0 : s.values[i];
                    float x = (float) (left + (seconds[i] - x0) / (x1 - x0) * (right - left)), y = (float) (bottom - (base[i] + v - lo) / (hi - lo) * (bottom - top));
                    if (open) area.lineTo(x, y); else { area.moveTo(x, y); open = true; first = i; }
                    lastPoint = i;
                }
                for (int i = lastPoint; i >= first && i >= 0; i--) {
                    float x = (float) (left + (seconds[i] - x0) / (x1 - x0) * (right - left)), y = (float) (bottom - (base[i] - lo) / (hi - lo) * (bottom - top));
                    area.lineTo(x, y);
                }
                area.close(); fill.setColor(s.color); canvas.drawPath(area, fill);
                for (int i = 0; i < seconds.length && i < s.values.length; i++) base[i] += Double.isNaN(s.values[i]) ? 0 : s.values[i];
                path.reset(); boolean on = false;
                for (int i = 0; i < seconds.length; i++) {
                    float x = (float) (left + (seconds[i] - x0) / (x1 - x0) * (right - left)), y = (float) (bottom - (base[i] - lo) / (hi - lo) * (bottom - top));
                    if (on) path.lineTo(x, y); else { path.moveTo(x, y); on = true; }
                }
                line.setColor(surface); line.setStrokeWidth(1f * density); canvas.drawPath(path, line);
            }
            line.setColor(ink); line.setStrokeWidth(2f * density); canvas.drawPath(path, line);
        }
        // Lines; targets dashed.
        if (!stacked) for (Series s : series) {
            line.setColor(s.color); line.setStrokeWidth((s.dashed ? 1.5f : 2f) * density);
            line.setPathEffect(s.dashed ? new DashPathEffect(new float[] {6 * density, 4 * density}, 0) : null);
            path.reset(); boolean open = false;
            for (int i = 0; i < seconds.length && i < s.values.length; i++) {
                double v = s.values[i];
                if (Double.isNaN(v)) { open = false; continue; }
                float x = (float) (left + (seconds[i] - x0) / (x1 - x0) * (right - left)), y = (float) (bottom - (v - lo) / (hi - lo) * (bottom - top));
                if (open) path.lineTo(x, y); else { path.moveTo(x, y); open = true; }
            }
            if (s.colours == null) { canvas.drawPath(path, line); continue; }
            // Coloured per point: a faint outline first, so a colour close to the background (black on dark) still shows.
            line.setColor((muted & 0x00ffffff) | 0x99000000); line.setStrokeWidth(4.5f * density); canvas.drawPath(path, line);
            line.setStrokeWidth(2.5f * density);
            float px = 0, py = 0; boolean have = false;
            for (int i = 0; i < seconds.length && i < s.values.length; i++) {
                double v = s.values[i];
                if (Double.isNaN(v)) { have = false; continue; }
                float x = (float) (left + (seconds[i] - x0) / (x1 - x0) * (right - left)), y = (float) (bottom - (v - lo) / (hi - lo) * (bottom - top));
                if (have) { line.setColor(i - 1 < s.colours.length ? s.colours[i - 1] : s.color); canvas.drawLine(px, py, x, y, line); }
                px = x; py = y; have = true;
            }
        }
        // Marks: a line across the plot at each one (on top of the data, so it shows across stacked areas too).
        for (double[] m : markAt) {
            if (m[0] < x0 || m[0] > x1) continue;
            float x = (float) (left + (m[0] - x0) / (x1 - x0) * (right - left));
            Paint mark = new Paint(Paint.ANTI_ALIAS_FLAG); mark.setColor((((int) m[1]) & 0x00ffffff) | 0xc0000000); mark.setStrokeWidth(2.5f * density);
            canvas.drawLine(x, top, x, bottom, mark);
        }
        line.setPathEffect(null);
        canvas.restore();
        // Direct end labels for measured series, nudged apart so they never overlap; text stays in ink.
        if (series.size() > 1 && !stacked) {
            List<float[]> ends = new ArrayList<>(); List<String> names = new ArrayList<>();
            for (Series s : series) {
                if (s.dashed) continue; double v = Double.NaN; for (int i = Math.min(s.values.length, seconds.length) - 1; i >= 0 && Double.isNaN(v); i--) if (visible(i, view)) v = s.values[i]; if (Double.isNaN(v)) continue;
                ends.add(new float[] {(float) (bottom - (v - lo) / (hi - lo) * (bottom - top))}); names.add(s.name);
            }
            Integer[] order = new Integer[ends.size()]; for (int i = 0; i < order.length; i++) order[i] = i;
            Arrays.sort(order, (a, b) -> Float.compare(ends.get(a)[0], ends.get(b)[0]));
            float previous = Float.NEGATIVE_INFINITY, gap = 13 * density * f;
            small.setTextAlign(Paint.Align.LEFT); small.setColor(ink);
            for (int i : order) { float y = Math.max(ends.get(i)[0] + 4 * density, previous + gap); previous = y; canvas.drawText(names.get(i), right + 6 * density, y, small); }
            small.setColor(muted);
        }
        // Crosshair and readout.
        if (touched >= 0) {
            float x = (float) (left + (seconds[touched] - x0) / (x1 - x0) * (right - left));
            rule.setColor(muted); canvas.drawLine(x, top, x, bottom, rule);
            List<String> rows = new ArrayList<>(); List<Integer> colors = new ArrayList<>();
            if (layerAxis) rows.add("Layer " + format(seconds[touched]));
            else rows.add(clockStart > 0 ? clockOfDay((long) (localSeconds(clockStart) + seconds[touched])) + " · " + spanWords(seconds[touched]) + " into the print" : elapsed(seconds[touched]));
            colors.add(0);
            double whole = stacked ? total(touched) : Double.NaN;
            if (stacked && !Double.isNaN(whole)) { rows.add("All  " + format(whole) + unit); colors.add(0); }
            for (Series s : series) if (touched < s.values.length && !Double.isNaN(s.values[touched]) && !(stacked && s.values[touched] <= 0)) {
                rows.add(s.name + "  " + format(s.values[touched]) + unit + (stacked && whole > 0 ? " · " + Math.round(100 * s.values[touched] / whole) + " %" : ""));
                colors.add(s.color);
            }
            for (int k = 0; k < spanAt.size(); k++)
                if (seconds[touched] >= spanAt.get(k)[0] && seconds[touched] < spanAt.get(k)[1]) { rows.add("Warm-up: " + spanText.get(k)); colors.add(0); }
            for (int m = 0; m < markAt.size(); m++)
                if (Math.abs(markAt.get(m)[0] - seconds[touched]) < (layerAxis ? 0.5 : 30)) { rows.add(markText.get(m)); colors.add((int) markAt.get(m)[1]); }
            if (!stacked) for (Series s : series) if (!s.dashed && touched < s.values.length && !Double.isNaN(s.values[touched])) {
                float y = (float) (bottom - (s.values[touched] - lo) / (hi - lo) * (bottom - top));
                fill.setColor(surface); canvas.drawCircle(x, y, 6 * density, fill); fill.setColor(s.color); canvas.drawCircle(x, y, 4 * density, fill);
            }
            float width = 0; small.setTextAlign(Paint.Align.LEFT);
            for (String row : rows) width = Math.max(width, small.measureText(row));
            float boxW = width + 30 * density, boxH = rows.size() * 16 * density * f + 10 * density;
            float boxX = boxRight ? right - boxW - 4 * density : left + 4 * density, boxY = Math.max(0, Math.min(top, getHeight() - boxH));
            boxX = Math.max(0, Math.min(boxX, getWidth() - boxW));
            fill.setColor(surface); canvas.drawRoundRect(boxX, boxY, boxX + boxW, boxY + boxH, 8 * density, 8 * density, fill);
            rule.setColor(grid); rule.setStyle(Paint.Style.STROKE); canvas.drawRoundRect(boxX, boxY, boxX + boxW, boxY + boxH, 8 * density, 8 * density, rule); rule.setStyle(Paint.Style.FILL);
            for (int i = 0; i < rows.size(); i++) {
                float rowY = boxY + 18 * density * f + i * 16 * density * f;
                if (colors.get(i) != 0) { fill.setColor(colors.get(i)); canvas.drawRoundRect(boxX + 8 * density, rowY - 8 * density, boxX + 18 * density, rowY - 1 * density, 2 * density, 2 * density, fill); }
                small.setColor(i == 0 ? muted : ink); canvas.drawText(rows.get(i), boxX + (colors.get(i) == 0 ? 8 : 22) * density, rowY, small);
            }

            small.setColor(muted);
        }
    }

    /** Legend entries: each measured series, plus one shared "Target" entry when any series is a dashed target. */
    private List<String> legendNames() {
        List<String> names = new ArrayList<>(); boolean target = false;
        for (Series s : series) if (s.dashed) target = true; else names.add(s.name);
        if (target) names.add("Target");
        return names;
    }
    private float entryWidth(String name) { return 18 * density + small.measureText(name) + 14 * density; }
    private int legendRows(int width) {
        if (width <= 0) return 1;
        int rows = 1; float x = 0;
        for (String name : legendNames()) { float w = entryWidth(name); if (x > 0 && x + w > width) { rows++; x = 0; } x += w; }
        return rows;
    }
    private void legend(Canvas canvas, float y, int width) {
        float x = 0; small.setTextAlign(Paint.Align.LEFT); small.setColor(ink);
        for (String name : legendNames()) {
            float w = entryWidth(name);
            if (x > 0 && x + w > width) { x = 0; y += 20 * density * f; }
            Series match = null; for (Series s : series) if (!s.dashed && s.name.equals(name)) match = s;
            if (match == null) { // shared dashed "Target" key, drawn in muted ink so it never claims a series color
                line.setColor(muted); line.setStrokeWidth(1.5f * density); line.setPathEffect(new DashPathEffect(new float[] {4 * density, 3 * density}, 0));
                canvas.drawLine(x, y - 4 * density, x + 14 * density, y - 4 * density, line); line.setPathEffect(null);
            } else { fill.setColor(match.color); canvas.drawRoundRect(x, y - 9 * density, x + 14 * density, y + 1 * density, 2 * density, 2 * density, fill); }
            canvas.drawText(name, x + 18 * density, y, small);
            x += w;
        }
        small.setColor(muted);
    }

    private static double last(double[] values) { for (int i = values.length - 1; i >= 0; i--) if (!Double.isNaN(values[i])) return values[i]; return Double.NaN; }
    private static double min(double[] values) { double m = Double.POSITIVE_INFINITY; for (double v : values) m = Math.min(m, v); return values.length == 0 ? 0 : m; }
    private static double max(double[] values) { double m = Double.NEGATIVE_INFINITY; for (double v : values) m = Math.max(m, v); return values.length == 0 ? 0 : m; }
    static double niceStep(double raw) {
        if (raw <= 0 || Double.isNaN(raw)) return 1;
        double magnitude = Math.pow(10, Math.floor(Math.log10(raw))), normalized = raw / magnitude;
        return (normalized <= 1 ? 1 : normalized <= 2 ? 2 : normalized <= 5 ? 5 : 10) * magnitude;
    }
    static double niceTime(double raw) {
        for (double step : new double[] {60, 300, 600, 900, 1800, 3600, 7200, 10800, 21600, 43200}) if (raw <= step) return step;
        return 86400;
    }
    /** Epoch seconds shifted to this phone's time zone, so that seconds modulo a day give the local time of day. */
    static double localSeconds(long millis) { return (millis + TimeZone.getDefault().getOffset(millis)) / 1000.0; }
    /** Local time of day from local epoch seconds, as HH:mm. */
    static String clockOfDay(long localSeconds) {
        long day = ((localSeconds % 86400) + 86400) % 86400;
        return String.format(Locale.ROOT, "%02d:%02d", day / 3600, (day % 3600) / 60);
    }
    /** A span in plain words, for example "45 min", "3 h 20 min" or "2 h". */
    static String spanWords(double seconds) {
        long minutes = Math.round(seconds / 60);
        if (minutes < 1) return "under 1 min";
        if (minutes < 60) return minutes + " min";
        long hours = minutes / 60, rest = minutes % 60;
        return rest == 0 ? hours + " h" : hours + " h " + rest + " min";
    }
    static String elapsed(double seconds) { long s = Math.round(seconds); return s / 3600 + ":" + String.format(Locale.ROOT, "%02d", (s % 3600) / 60); }
    static String format(double value) {
        if (Double.isNaN(value)) return "—";
        return Math.abs(value - Math.rint(value)) < 1e-6 ? String.valueOf((long) Math.rint(value)) : String.format(Locale.ROOT, "%.1f", value);
    }
}
