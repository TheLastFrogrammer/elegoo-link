package io.github.thelastfrogrammer.elink;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A bed mesh as a shaded surface in 3D: each cell between four probe points a tile coloured as on the map (BedMesh.colour)
 * and lit from above, heights × an exaggeration so tenths of a millimetre can be seen, over the bed's outline. Drag to turn
 * it, pinch to zoom, double tap to go back to the first view. Drawn on the canvas, far tiles first.
 */
final class BedMesh3DView extends View {
    private BedMesh.Mesh mesh;
    private final boolean dark;
    private final int ink, muted;
    private int exaggeration = 50;
    private double yaw = -35, elevation = 32, zoom = 1;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ScaleGestureDetector pinch;
    private final GestureDetector taps;
    private float lastX, lastY; private boolean dragging;

    BedMesh3DView(Context context, boolean dark, int ink, int muted) {
        super(context); this.dark = dark; this.ink = ink; this.muted = muted;
        edge.setStyle(Paint.Style.STROKE); edge.setStrokeWidth(Math.max(1, context.getResources().getDisplayMetrics().density * 0.6f));
        pinch = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) { zoom = Math.max(0.6, Math.min(3, zoom * d.getScaleFactor())); invalidate(); return true; }
        });
        taps = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDoubleTap(MotionEvent e) { reset(); return true; }
        });
        setFocusable(true);
    }

    void set(BedMesh.Mesh mesh, int exaggeration) { this.mesh = mesh; this.exaggeration = exaggeration; describe(); invalidate(); }
    void exaggeration(int value) { exaggeration = value; describe(); invalidate(); }
    void reset() { yaw = -35; elevation = 32; zoom = 1; invalidate(); }
    private void describe() {
        if (mesh == null) return;
        setContentDescription(String.format(Locale.getDefault(), "The bed's shape in 3D, heights times %d. Range %.2f millimetres. Drag to turn, pinch to zoom.", exaggeration, mesh.range()));
    }

    @Override protected void onMeasure(int w, int h) { int width = MeasureSpec.getSize(w); setMeasuredDimension(width, Math.round(width * 0.75f)); }

    /** Projects a point (mm, heights already exaggerated, about the bed's centre): {screen x, screen y, depth (far is larger)}. */
    private double[] project(double x, double y, double z, double cx, double cy, double scale) {
        double a = Math.toRadians(yaw), e = Math.toRadians(elevation);
        double xr = x * Math.cos(a) - y * Math.sin(a), yr = x * Math.sin(a) + y * Math.cos(a);
        double v = yr * Math.sin(e) + z * Math.cos(e), depth = yr * Math.cos(e) - z * Math.sin(e);
        return new double[] {cx + xr * scale, cy - v * scale, depth};
    }

    @Override protected void onDraw(Canvas canvas) {
        if (mesh == null || mesh.xs.length < 2 || mesh.ys.length < 2) return;
        float d = getResources().getDisplayMetrics().density;
        double cx = getWidth() / 2.0, cy = getHeight() * 0.55, scale = Math.min(getWidth(), getHeight() * 1.3) / 380.0 * zoom;   // a turned 256 mm bed spans up to 362 mm across
        double mean = mesh.mean(), mx = (mesh.xs[0] + mesh.xs[mesh.xs.length - 1]) / 2, my = (mesh.ys[0] + mesh.ys[mesh.ys.length - 1]) / 2;
        double[] s = BedMesh.scale(mesh, false);
        // The bed's outline (256 mm) at the mean height, for scale.
        double half = BedMesh.PLATE_MM / 2;
        double[][] corners = {project(-half, -half, 0, cx, cy, scale), project(half, -half, 0, cx, cy, scale), project(half, half, 0, cx, cy, scale), project(-half, half, 0, cx, cy, scale)};
        Path outline = new Path(); outline.moveTo((float) corners[0][0], (float) corners[0][1]);
        for (int k = 1; k <= 4; k++) outline.lineTo((float) corners[k % 4][0], (float) corners[k % 4][1]);
        edge.setColor(muted); canvas.drawPath(outline, edge);
        // The tiles, far ones first.
        List<double[]> tiles = new ArrayList<>();   // {depth, i, j}
        int nx = mesh.xs.length, ny = mesh.ys.length;
        double[][][] p = new double[ny][nx][];
        for (int j = 0; j < ny; j++) for (int i = 0; i < nx; i++) {
            double v = Double.isNaN(mesh.z[j][i]) ? mean : mesh.z[j][i];
            p[j][i] = project(mesh.xs[i] - mx, mesh.ys[j] - my, (v - mean) * exaggeration, cx, cy, scale);
        }
        for (int j = 0; j + 1 < ny; j++) for (int i = 0; i + 1 < nx; i++)
            tiles.add(new double[] {(p[j][i][2] + p[j][i + 1][2] + p[j + 1][i][2] + p[j + 1][i + 1][2]) / 4, i, j});
        tiles.sort((a, b) -> Double.compare(b[0], a[0]));
        edge.setColor(dark ? 0x40ffffff : 0x30000000);
        for (double[] t : tiles) {
            int i = (int) t[1], j = (int) t[2];
            double z00 = val(j, i, mean), z10 = val(j, i + 1, mean), z01 = val(j + 1, i, mean), z11 = val(j + 1, i + 1, mean), avg = (z00 + z10 + z01 + z11) / 4;
            // Lit from above and a little to the front: the tile's slope (with the exaggeration) darkens it a little.
            double dx = mesh.xs[i + 1] - mesh.xs[i], dy = mesh.ys[j + 1] - mesh.ys[j];
            double gx = ((z10 - z00) + (z11 - z01)) / 2 * exaggeration / dx, gy = ((z01 - z00) + (z11 - z10)) / 2 * exaggeration / dy;
            double nlen = Math.sqrt(gx * gx + gy * gy + 1), light = (0.25 * -gx + -0.45 * -gy + 0.86) / nlen;
            int colour = shade(BedMesh.colour((avg - s[0]) / s[1], dark), 0.72 + 0.28 * Math.max(0, Math.min(1, light)));
            Path tile = new Path();
            tile.moveTo((float) p[j][i][0], (float) p[j][i][1]); tile.lineTo((float) p[j][i + 1][0], (float) p[j][i + 1][1]);
            tile.lineTo((float) p[j + 1][i + 1][0], (float) p[j + 1][i + 1][1]); tile.lineTo((float) p[j + 1][i][0], (float) p[j + 1][i][1]); tile.close();
            fill.setColor(colour); canvas.drawPath(tile, fill); canvas.drawPath(tile, edge);
        }
        // Which way is which: the first X and Y probe corner, and the exaggeration.
        double[] origin = project(mesh.xs[0] - mx, mesh.ys[0] - my, (val(0, 0, mean) - mean) * exaggeration, cx, cy, scale);
        text.setColor(muted); text.setTextSize(11 * getResources().getDisplayMetrics().scaledDensity);
        canvas.drawText(String.format(Locale.getDefault(), "X %.0f, Y %.0f", mesh.xs[0], mesh.ys[0]), (float) origin[0] + 4 * d, (float) origin[1] + 14 * d, text);
        text.setColor(ink);
        canvas.drawText(String.format(Locale.getDefault(), "Heights × %d", exaggeration), 8 * d, 16 * d, text);
    }
    private double val(int j, int i, double mean) { double v = mesh.z[j][i]; return Double.isNaN(v) ? mean : v; }
    private static int shade(int colour, double k) {
        int r = (int) Math.min(255, ((colour >> 16) & 255) * k), g = (int) Math.min(255, ((colour >> 8) & 255) * k), b = (int) Math.min(255, (colour & 255) * k);
        return 0xff000000 | (r << 16) | (g << 8) | b;
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        pinch.onTouchEvent(e); taps.onTouchEvent(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: lastX = e.getX(); lastY = e.getY(); dragging = true; getParent().requestDisallowInterceptTouchEvent(true); return true;
            case MotionEvent.ACTION_POINTER_DOWN: dragging = false; return true;
            case MotionEvent.ACTION_MOVE:
                if (dragging && e.getPointerCount() == 1 && !pinch.isInProgress()) {
                    yaw -= (e.getX() - lastX) * 0.4; elevation = Math.max(8, Math.min(88, elevation + (e.getY() - lastY) * 0.3)); invalidate();
                }
                lastX = e.getX(); lastY = e.getY(); return true;
            case MotionEvent.ACTION_UP: case MotionEvent.ACTION_CANCEL:
                dragging = false; getParent().requestDisallowInterceptTouchEvent(false); performClick(); return true;
            default: return true;
        }
    }
    @Override public boolean performClick() { return super.performClick(); }
}
