package io.github.thelastfrogrammer.elink;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import java.util.Locale;

/**
 * Where the head was at each picture, in 3D over the bed: a dot per picture, joined in order and shaded light (early) to
 * dark (late), with the status readings' positions as faint grey dots behind. Drag to turn it; tap a dot to read it (its
 * picture in the readout), tap it again to open the picture. Drawn with a plain projection, nothing loaded.
 */
final class HeadPositions3DView extends View {
    private final float density;
    private final int ink, muted, grid, surface, early, late;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG), small = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** {s into the print, x, y, z} per picture; and the readings' {x, y, z}. */
    private double[][] points = new double[0][], readings = new double[0][];
    private double bedX = 256, bedY = 256, top = 10;
    /** Heights drawn this many times taller, so a low print is not a flat smear over a wide bed (said on the Z axis). */
    private double stretch = 1;
    /** Turn about the bed's middle (around Z), and tilt down from level; degrees. */
    private double yaw = -35, pitch = 30;
    private int chosen = -1; private float downX, downY, lastX, lastY; private boolean dragged;
    private java.util.function.IntFunction<Bitmap> thumb; private java.util.function.IntConsumer open;

    HeadPositions3DView(Context context, int ink, int muted, int grid, int surface, int early, int late) {
        super(context);
        this.ink = ink; this.muted = muted; this.grid = grid; this.surface = surface; this.early = early; this.late = late;
        density = context.getResources().getDisplayMetrics().density;
        line.setStyle(Paint.Style.STROKE); line.setStrokeJoin(Paint.Join.ROUND); line.setStrokeCap(Paint.Cap.ROUND);
        small.setColor(muted); small.setTextSize(11 * density);
        setFocusable(true);
    }

    /** The pictures' positions {s, x, y, z}, the readings' {x, y, z} (may be empty), and the bed's size (mm). */
    HeadPositions3DView set(double[][] pictures, double[][] readings, double bedX, double bedY) {
        points = pictures; this.readings = readings; this.bedX = bedX; this.bedY = bedY;
        double z = 10; for (double[] p : pictures) z = Math.max(z, p[3]); for (double[] r : readings) if (!Double.isNaN(r[2])) z = Math.max(z, r[2]);
        top = z; chosen = -1; stretch = zStretch(bedX, z);
        setContentDescription(describe(pictures));
        invalidate(); return this;
    }
    HeadPositions3DView pictures(java.util.function.IntFunction<Bitmap> thumb, java.util.function.IntConsumer open) { this.thumb = thumb; this.open = open; return this; }

    /** How much to stretch heights: the tallest point at least a third of the bed's width, whole steps, at most ×10. Pure. */
    static double zStretch(double bedWidth, double top) { return Math.max(1, Math.min(10, Math.floor(bedWidth / 3 / Math.max(1, top)))); }
    /** The spread of the positions in words, for screen readers and the card. Pure. */
    static String describe(double[][] pictures) {
        if (pictures.length == 0) return "No head positions noted for the pictures.";
        double[] lo = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE}, hi = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (double[] p : pictures) for (int k = 0; k < 3; k++) { lo[k] = Math.min(lo[k], p[k + 1]); hi[k] = Math.max(hi[k], p[k + 1]); }
        return String.format(Locale.getDefault(), "Head at %d pictures: X %.1f–%.1f, Y %.1f–%.1f, Z %.2f–%.2f mm.", pictures.length, lo[0], hi[0], lo[1], hi[1], lo[2], hi[2]);
    }

    /** Screen position of a bed point: turned about the bed's middle, tilted, Z up; orthographic, fitted to the view. */
    private float[] project(double x, double y, double z, float[] fit) {
        double cx = x - bedX / 2, cy = y - bedY / 2, a = Math.toRadians(yaw), b = Math.toRadians(pitch); z *= stretch;
        double rx = cx * Math.cos(a) - cy * Math.sin(a), ry = cx * Math.sin(a) + cy * Math.cos(a);
        // Seen from the front, tilted down by pitch: farther back (larger ry) and higher (z) are both further up the screen.
        double sx = rx, sy = -(z * Math.cos(b) + ry * Math.sin(b));
        return new float[] {(float) (fit[0] + sx * fit[2]), (float) (fit[1] + sy * fit[2])};
    }
    /** {centre x, centre y, scale} so the bed and the tallest point fit. */
    private float[] fit() {
        double reach = Math.hypot(bedX, bedY) / 2, b = Math.toRadians(pitch);
        double wide = reach * 2, tall = reach * 2 * Math.sin(b) + top * stretch * Math.cos(b);
        float pad = 28 * density, w = getWidth() - 2 * pad, h = getHeight() - 2 * pad - 14 * density;
        float scale = (float) Math.min(w / wide, h / Math.max(1, tall));
        float cy = (float) (pad + 14 * density + h / 2 + (top * stretch * Math.cos(b)) * scale / 2);
        return new float[] {getWidth() / 2f, cy, scale};
    }
    private int shade(double f) {
        f = Math.max(0, Math.min(1, f));
        int r = (int) Math.round(((early >> 16) & 255) + (((late >> 16) & 255) - ((early >> 16) & 255)) * f);
        int g = (int) Math.round(((early >> 8) & 255) + (((late >> 8) & 255) - ((early >> 8) & 255)) * f);
        int b = (int) Math.round((early & 255) + ((late & 255) - (early & 255)) * f);
        return 0xff000000 | (r << 16) | (g << 8) | b;
    }

    @Override protected void onDraw(Canvas canvas) {
        float[] fit = fit();
        // The bed: its outline and a 50 mm grid, then a Z axis at the bed's back left corner.
        line.setStrokeWidth(Math.max(1, density * 0.75f)); line.setColor(grid);
        for (double gx = 0; gx <= bedX + 0.1; gx += 50) { float[] a = project(gx, 0, 0, fit), b = project(gx, bedY, 0, fit); canvas.drawLine(a[0], a[1], b[0], b[1], line); }
        for (double gy = 0; gy <= bedY + 0.1; gy += 50) { float[] a = project(0, gy, 0, fit), b = project(bedX, gy, 0, fit); canvas.drawLine(a[0], a[1], b[0], b[1], line); }
        line.setColor(muted); line.setStrokeWidth(1.25f * density);
        Path bed = new Path(); float[] c0 = project(0, 0, 0, fit), c1 = project(bedX, 0, 0, fit), c2 = project(bedX, bedY, 0, fit), c3 = project(0, bedY, 0, fit);
        bed.moveTo(c0[0], c0[1]); bed.lineTo(c1[0], c1[1]); bed.lineTo(c2[0], c2[1]); bed.lineTo(c3[0], c3[1]); bed.close(); canvas.drawPath(bed, line);
        float[] zTop = project(0, bedY, top, fit); canvas.drawLine(c3[0], c3[1], zTop[0], zTop[1], line);
        small.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("X", (c0[0] + c1[0]) / 2, (c0[1] + c1[1]) / 2 + 14 * density, small);
        canvas.drawText("Y", (c0[0] + c3[0]) / 2 - 10 * density, (c0[1] + c3[1]) / 2, small);
        canvas.drawText(String.format(Locale.getDefault(), "Z %.0f mm%s", top, stretch > 1 ? String.format(Locale.getDefault(), " (drawn ×%.0f)", stretch) : ""), zTop[0], zTop[1] - 6 * density, small);
        canvas.drawText("0,0", c0[0], c0[1] + 14 * density, small);
        // The readings, faint.
        fill.setColor((muted & 0x00ffffff) | 0x55000000);
        for (double[] r : readings) { if (Double.isNaN(r[0]) || Double.isNaN(r[1])) continue; float[] p = project(r[0], r[1], Double.isNaN(r[2]) ? 0 : r[2], fit); canvas.drawCircle(p[0], p[1], 1.5f * density, fill); }
        if (points.length == 0) { small.setTextAlign(Paint.Align.LEFT); canvas.drawText("No head positions noted for the pictures.", 12 * density, 20 * density, small); return; }
        // The pictures: joined in order, then the dots on top with a ring of the background.
        double t0 = points[0][0], t1 = Math.max(t0 + 1, points[points.length - 1][0]);
        line.setStrokeWidth(1.5f * density); Path path = new Path();
        for (int i = 0; i < points.length; i++) { float[] p = project(points[i][1], points[i][2], points[i][3], fit); if (i == 0) path.moveTo(p[0], p[1]); else path.lineTo(p[0], p[1]); }
        line.setColor((late & 0x00ffffff) | 0x66000000); canvas.drawPath(path, line);
        for (int i = 0; i < points.length; i++) {
            float[] p = project(points[i][1], points[i][2], points[i][3], fit);
            fill.setColor(surface); canvas.drawCircle(p[0], p[1], (i == chosen ? 8 : 6) * density, fill);
            fill.setColor(i == chosen ? ink : shade((points[i][0] - t0) / (t1 - t0))); canvas.drawCircle(p[0], p[1], (i == chosen ? 6 : 4) * density, fill);
        }
        small.setTextAlign(Paint.Align.LEFT);
        canvas.drawText("Light: early · dark: late · drag to turn, tap a dot", 4 * density, getHeight() - 6 * density, small);
        if (chosen >= 0) readout(canvas, chosen);
    }
    private void readout(Canvas canvas, int i) {
        double[] p = points[i];
        String[] rows = {ChartView.elapsed(p[0]) + " into the print", String.format(Locale.getDefault(), "X %.1f  Y %.1f  Z %.2f mm", p[1], p[2], p[3]), open != null ? "Tap again to open the picture" : ""};
        Bitmap image = thumb == null ? null : thumb.apply(i);
        float width = 0; for (String r : rows) width = Math.max(width, small.measureText(r));
        float tw = image == null ? 0 : Math.min(140 * density, getWidth() * 0.4f), th = image == null ? 0 : tw * image.getHeight() / Math.max(1, image.getWidth());
        float boxW = Math.max(width, tw) + 16 * density, boxH = rows.length * 15 * density + 10 * density + (image == null ? 0 : th + 6 * density);
        float x = 6 * density, y = 6 * density;
        fill.setColor(surface); canvas.drawRoundRect(x, y, x + boxW, y + boxH, 8 * density, 8 * density, fill);
        line.setColor(grid); line.setStrokeWidth(Math.max(1, density * 0.75f)); canvas.drawRoundRect(x, y, x + boxW, y + boxH, 8 * density, 8 * density, line);
        for (int k = 0; k < rows.length; k++) { small.setColor(k == 0 ? muted : ink); canvas.drawText(rows[k], x + 8 * density, y + (16 + k * 15) * density, small); }
        small.setColor(muted);
        if (image != null) canvas.drawBitmap(image, null, new RectF(x + 8 * density, y + rows.length * 15 * density + 10 * density, x + 8 * density + tw, y + rows.length * 15 * density + 10 * density + th), new Paint(Paint.FILTER_BITMAP_FLAG));
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: downX = lastX = e.getX(); downY = lastY = e.getY(); dragged = false; getParent().requestDisallowInterceptTouchEvent(true); return true;
            case MotionEvent.ACTION_MOVE:
                if (Math.hypot(e.getX() - downX, e.getY() - downY) > 8 * density) dragged = true;
                if (dragged) {
                    yaw += (e.getX() - lastX) / density * 0.5; pitch = Math.max(5, Math.min(90, pitch + (e.getY() - lastY) / density * 0.4));
                    lastX = e.getX(); lastY = e.getY(); invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (!dragged) {
                    int hit = nearest(e.getX(), e.getY());
                    if (hit >= 0 && hit == chosen && open != null) open.accept(hit);
                    else { chosen = hit; invalidate(); }
                }
                return true;
            default: return true;
        }
    }
    private int nearest(float x, float y) {
        float[] fit = fit(); int best = -1; float bestGap = 24 * density;
        for (int i = 0; i < points.length; i++) {
            float[] p = project(points[i][1], points[i][2], points[i][3], fit); float gap = (float) Math.hypot(p[0] - x, p[1] - y);
            if (gap < bestGap) { bestGap = gap; best = i; }
        }
        return best;
    }
}
