package io.github.thelastfrogrammer.elink;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A small chart of one value over the weeks: a dot per print or levelling, joined in time order, the lowest and highest
 * values on the left and the first and last dates below. Its summary is what TalkBack hears.
 */
final class TrendView extends View {
    private List<PrinterHealth.Point> points = new ArrayList<>();
    private String unit = ""; private int decimals = 1;
    private final int ink, muted, line, colour;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG);

    TrendView(Context context, int ink, int muted, int line, int colour) { super(context); this.ink = ink; this.muted = muted; this.line = line; this.colour = colour; }

    void set(List<PrinterHealth.Point> points, String unit, int decimals, String summary) {
        this.points = new ArrayList<>(points); this.points.sort((a, b) -> Long.compare(a.at, b.at));
        this.unit = unit; this.decimals = decimals; setContentDescription(summary); invalidate();
    }

    @Override protected void onMeasure(int w, int h) { setMeasuredDimension(MeasureSpec.getSize(w), Math.round(150 * getResources().getDisplayMetrics().density)); }

    @Override protected void onDraw(Canvas canvas) {
        if (points.isEmpty()) return;
        float d = getResources().getDisplayMetrics().density, left = 56 * d, right = getWidth() - 8 * d, top = 8 * d, bottom = getHeight() - 22 * d;
        double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
        for (PrinterHealth.Point p : points) { lo = Math.min(lo, p.value); hi = Math.max(hi, p.value); }
        boolean level = hi - lo < 1e-9;   // every value the same: one label, at the line
        double pad = Math.max((hi - lo) * 0.15, Math.max(Math.abs(hi) * 0.05, 1e-3)); lo -= pad; hi += pad;
        long t0 = points.get(0).at, t1 = Math.max(points.get(points.size() - 1).at, t0 + 1);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(d); paint.setColor(line);
        canvas.drawLine(left, bottom, right, bottom, paint); canvas.drawLine(left, top, left, bottom, paint);
        Path path = new Path(); boolean first = true;
        for (PrinterHealth.Point p : points) {
            float x = points.size() == 1 ? (left + right) / 2 : (float) (left + (right - left) * (p.at - t0) / (double) (t1 - t0)), y = (float) (bottom - (bottom - top) * (p.value - lo) / (hi - lo));
            if (first) path.moveTo(x, y); else path.lineTo(x, y); first = false;
        }
        paint.setColor(colour); paint.setStrokeWidth(2 * d); canvas.drawPath(path, paint);
        paint.setStyle(Paint.Style.FILL);
        for (PrinterHealth.Point p : points) {
            float x = points.size() == 1 ? (left + right) / 2 : (float) (left + (right - left) * (p.at - t0) / (double) (t1 - t0)), y = (float) (bottom - (bottom - top) * (p.value - lo) / (hi - lo));
            canvas.drawCircle(x, y, 4 * d, paint);
        }
        text.setTextSize(11 * getResources().getDisplayMetrics().scaledDensity); text.setColor(muted);
        String f = "%." + decimals + "f";
        text.setTextAlign(Paint.Align.RIGHT);
        if (level) canvas.drawText(String.format(Locale.getDefault(), f, lo + pad) + unit, left - 4 * d, (top + bottom) / 2 + 4 * d, text);
        else {
            canvas.drawText(String.format(Locale.getDefault(), f, hi - pad) + unit, left - 4 * d, top + 10 * d, text);
            canvas.drawText(String.format(Locale.getDefault(), f, lo + pad) + unit, left - 4 * d, bottom, text);
        }
        text.setTextAlign(Paint.Align.LEFT); canvas.drawText(PrinterHealth.day(t0), left, bottom + 16 * d, text);
        text.setTextAlign(Paint.Align.RIGHT); canvas.drawText(PrinterHealth.day(points.get(points.size() - 1).at), right, bottom + 16 * d, text);
        text.setColor(ink);
    }
}
