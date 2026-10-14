package io.github.thelastfrogrammer.elink;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Print progress as a row of small rounded segments, filled from the left; the last one partly filled. Purely visual (the percentage beside it speaks). */
final class SegmentBar extends View {
    static final int SEGMENTS = 24;
    private final Paint on = new Paint(Paint.ANTI_ALIAS_FLAG), off = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final float density;
    private int percent;

    SegmentBar(Context context, int fill, int track) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        on.setColor(fill); off.setColor(track);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void setProgress(int value) { int clamped = Math.max(0, Math.min(100, value)); if (clamped != percent) { percent = clamped; invalidate(); } }

    /** How much of segment `index` (0-based, of `segments`) is filled at `percent`: 0 to 1. */
    static float filled(int percent, int index, int segments) {
        float at = Math.max(0, Math.min(100, percent)) / 100f * segments - index;
        return Math.max(0, Math.min(1, at));
    }

    @Override protected void onDraw(Canvas canvas) {
        float gap = 2 * density, width = getWidth(), height = getHeight(), radius = Math.min(height / 2, 3 * density);
        float each = (width - gap * (SEGMENTS - 1)) / SEGMENTS;
        for (int i = 0; i < SEGMENTS; i++) {
            float left = i * (each + gap), part = filled(percent, i, SEGMENTS);
            box.set(left, 0, left + each, height); canvas.drawRoundRect(box, radius, radius, off);
            if (part > 0) { box.set(left, 0, left + Math.max(radius * 2, each * part), height); canvas.drawRoundRect(box, radius, radius, on); }
        }
    }
}
