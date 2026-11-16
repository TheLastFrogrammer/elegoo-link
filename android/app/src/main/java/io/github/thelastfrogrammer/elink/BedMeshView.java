package io.github.thelastfrogrammer.elink;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * A bed mesh as a square map of its probe points: each point's cell coloured from blue (low) through a quiet middle to
 * orange (high), with its value written in when there is room, X along the bottom and Y up the side as the printer numbers
 * them, and a legend. A difference map is centred on no change. Tapping a cell reads it out; TalkBack hears the summary.
 */
final class BedMeshView extends View {
    private BedMesh.Mesh mesh;
    private boolean difference;
    private final boolean dark;
    private final int ink, muted;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF plot = new RectF();
    private int chosenI = -1, chosenJ = -1;
    private Consumer<String> readout;

    BedMeshView(Context context, boolean dark, int ink, int muted) {
        super(context); this.dark = dark; this.ink = ink; this.muted = muted;
        line.setStyle(Paint.Style.STROKE); setFocusable(true); setClickable(true);
    }

    void set(BedMesh.Mesh mesh, boolean difference, String summary) {
        this.mesh = mesh; this.difference = difference; chosenI = chosenJ = -1;
        setContentDescription(summary); invalidate();
    }
    void onReadout(Consumer<String> readout) { this.readout = readout; }

    @Override protected void onMeasure(int w, int h) {
        int width = MeasureSpec.getSize(w);
        float d = getResources().getDisplayMetrics().density;
        setMeasuredDimension(width, Math.round(width - 40 * d + 70 * d));   // a square plot, its labels and the legend
    }

    double[] scale() { return BedMesh.scale(mesh, difference); }
    int colour(double t) { return BedMesh.colour(t, dark); }

    @Override protected void onDraw(Canvas canvas) {
        if (mesh == null || mesh.xs.length == 0) return;
        float d = getResources().getDisplayMetrics().density;
        float left = 36 * d, size = Math.min(getWidth() - left - 4 * d, getHeight() - 70 * d);
        plot.set(left, 4 * d, left + size, 4 * d + size);
        int nx = mesh.xs.length, ny = mesh.ys.length; float cw = size / nx, ch = size / ny;
        double[] s = scale();
        text.setTextAlign(Paint.Align.CENTER); text.setTextSize(Math.min(cw, ch) * 0.26f);
        boolean labels = cw > 26 * d && mesh.probed == null, dense = mesh.probed != null;
        for (int j = 0; j < ny; j++) for (int i = 0; i < nx; i++) {
            double v = mesh.z[j][i];
            float x0 = plot.left + i * cw, y0 = plot.bottom - (j + 1) * ch;   // Y grows upwards, as on the printer's axes
            fill.setColor(Double.isNaN(v) ? (dark ? 0xff182227 : 0xffffffff) : colour((v - s[0]) / s[1]));
            // As probed, each point is a tile with a gap around it; filled in, the samples join into one surface.
            if (dense) canvas.drawRect(x0, y0, x0 + cw + 0.5f, y0 + ch + 0.5f, fill); else canvas.drawRect(x0 + 1, y0 + 1, x0 + cw - 1, y0 + ch - 1, fill);
            if (labels && !Double.isNaN(v)) {
                double t = Math.abs((v - s[0]) / s[1]);
                text.setColor(t > 0.6 ? 0xffffffff : ink);
                canvas.drawText(String.format(Locale.getDefault(), "%.2f", v).replace("-0.00", "0.00"), x0 + cw / 2, y0 + ch / 2 + text.getTextSize() / 3, text);
            }
        }
        // Filled in: the probe points themselves as small dots, so it stays clear what was measured.
        if (dense) {
            fill.setColor(dark ? 0xccffffff : 0xcc17252c); float r = Math.max(1.5f * d, Math.min(cw, ch) * 0.35f);
            for (int j = 0; j < ny; j += mesh.detail) for (int i = 0; i < nx; i += mesh.detail) canvas.drawCircle(plot.left + (i + 0.5f) * cw, plot.bottom - (j + 0.5f) * ch, r, fill);
        }
        if (chosenI >= 0) { line.setColor(ink); line.setStrokeWidth(2.5f * d); canvas.drawRect(plot.left + chosenI * cw + 1, plot.bottom - (chosenJ + 1) * ch + 1, plot.left + (chosenI + 1) * cw - 1, plot.bottom - chosenJ * ch - 1, line); }
        // Axes: the first and last X and Y, in mm.
        text.setColor(muted); text.setTextSize(11 * getResources().getDisplayMetrics().scaledDensity);
        canvas.drawText(String.format(Locale.getDefault(), "X %.0f", mesh.xs[0]), plot.left + cw / 2, plot.bottom + 14 * d, text);
        text.setTextAlign(Paint.Align.RIGHT);   // the last label ends at the plot's edge, so its unit is never cut off
        canvas.drawText(String.format(Locale.getDefault(), "X %.0f mm", mesh.xs[nx - 1]), plot.right, plot.bottom + 14 * d, text);
        canvas.drawText(String.format(Locale.getDefault(), "Y %.0f", mesh.ys[0]), plot.left - 4 * d, plot.bottom - ch / 2 + 4 * d, text);
        canvas.drawText(String.format(Locale.getDefault(), "Y %.0f", mesh.ys[ny - 1]), plot.left - 4 * d, plot.top + ch / 2 + 4 * d, text);
        // The legend: the colour ramp with its ends and middle.
        float ly = plot.bottom + 26 * d, lh = 12 * d;
        for (int k = 0; k < 100; k++) { fill.setColor(colour(k / 49.5 - 1)); canvas.drawRect(plot.left + plot.width() * k / 100f, ly, plot.left + plot.width() * (k + 1) / 100f + 1, ly + lh, fill); }
        text.setTextAlign(Paint.Align.LEFT); canvas.drawText(BedMesh.signed(s[0] - s[1]) + " mm" + (difference ? " lower" : ""), plot.left, ly + lh + 16 * d, text);
        text.setTextAlign(Paint.Align.CENTER); canvas.drawText(difference ? "no change" : BedMesh.signed(s[0]) + " (mean)", plot.centerX(), ly + lh + 16 * d, text);
        text.setTextAlign(Paint.Align.RIGHT); canvas.drawText(BedMesh.signed(s[0] + s[1]) + " mm" + (difference ? " higher" : ""), plot.right, ly + lh + 16 * d, text);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (mesh == null || e.getAction() != MotionEvent.ACTION_UP || !plot.contains(e.getX(), e.getY())) return super.onTouchEvent(e);
        int i = (int) ((e.getX() - plot.left) / (plot.width() / mesh.xs.length)), j = (int) ((plot.bottom - e.getY()) / (plot.height() / mesh.ys.length));
        if (i < 0 || j < 0 || i >= mesh.xs.length || j >= mesh.ys.length) return true;
        chosenI = i; chosenJ = j; invalidate();
        double v = mesh.z[j][i];
        if (readout != null) readout.accept(String.format(Locale.getDefault(), "X %.0f, Y %.0f mm: %s", mesh.xs[i], mesh.ys[j],
            Double.isNaN(v) ? "not probed" : BedMesh.signed(v) + " mm" + (difference ? " change" : "") + (BedMesh.probePoint(mesh, i, j) ? "" : ", estimated between probe points")));
        performClick();
        return true;
    }
    @Override public boolean performClick() { return super.performClick(); }
}
