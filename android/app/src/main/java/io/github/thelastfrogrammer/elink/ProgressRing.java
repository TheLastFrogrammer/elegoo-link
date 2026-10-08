package io.github.thelastfrogrammer.elink;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

/** Circular print progress with the percentage (or a short word) in the middle. */
final class ProgressRing extends View {
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), arc = new Paint(Paint.ANTI_ALIAS_FLAG), label = new Paint(Paint.ANTI_ALIAS_FLAG), caption = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private int percent = -1;
    private String center = "—", below = "";

    ProgressRing(Context context, int trackColor, int arcColor, int textColor, int mutedColor) {
        super(context);
        float density = context.getResources().getDisplayMetrics().density;
        track.setStyle(Paint.Style.STROKE); track.setStrokeWidth(10 * density); track.setColor(trackColor);
        arc.setStyle(Paint.Style.STROKE); arc.setStrokeWidth(10 * density); arc.setStrokeCap(Paint.Cap.ROUND); arc.setColor(arcColor);
        label.setColor(textColor); label.setTextAlign(Paint.Align.CENTER); label.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        caption.setColor(mutedColor); caption.setTextAlign(Paint.Align.CENTER);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    /** percent < 0 hides the arc; center replaces the percentage text when not printing. */
    void set(int percent, String center, String below) {
        this.percent = percent; this.center = center; this.below = below;
        setContentDescription(percent >= 0 ? "Printing, " + percent + " percent" + (below.isEmpty() ? "" : ", " + below.replace("/", " of ")) : center);
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        float inset = track.getStrokeWidth() / 2 + 1, size = Math.min(getWidth(), getHeight());
        float left = (getWidth() - size) / 2, top = (getHeight() - size) / 2;
        bounds.set(left + inset, top + inset, left + size - inset, top + size - inset);
        canvas.drawArc(bounds, 0, 360, false, track);
        if (percent > 0) canvas.drawArc(bounds, -90, 360f * Math.min(100, percent) / 100f, false, arc);
        label.setTextSize(size * (center.length() > 4 ? 0.15f : 0.24f));
        caption.setTextSize(size * 0.1f);
        float middle = getHeight() / 2f;
        canvas.drawText(center, getWidth() / 2f, middle + label.getTextSize() * (below.isEmpty() ? 0.35f : 0.15f), label);
        if (!below.isEmpty()) canvas.drawText(below, getWidth() / 2f, middle + label.getTextSize() * 0.15f + caption.getTextSize() * 1.4f, caption);
    }
}
