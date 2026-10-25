package io.github.thelastfrogrammer.elink.tv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/**
 * The camera's picture, as large as fits the screen without cropping, turned and mirrored as the camera is set up on the
 * phone (mirrored first, then turned clockwise: the phone's own order). Shows the bitmap it is given and hands the one it
 * replaces back, so the decoder can reuse its memory instead of making a new one five times a second.
 */
final class FrameView extends View {
    private final Matrix matrix = new Matrix();
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private Bitmap frame;
    private int rotation;
    private boolean mirror;

    FrameView(Context c) { super(c); }
    FrameView(Context c, AttributeSet attrs) { super(c, attrs); }

    /** Shows this picture; returns the one it replaces (or null). */
    Bitmap show(Bitmap picture, int rotation, boolean mirror) {
        Bitmap old = frame == picture ? null : frame;
        frame = picture; this.rotation = rotation; this.mirror = mirror;
        fit(); invalidate();
        return old;
    }
    Bitmap clear() { Bitmap old = frame; frame = null; invalidate(); return old; }
    boolean showing() { return frame != null; }

    @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) { fit(); }

    private void fit() {
        matrix.reset();
        if (frame == null || getWidth() == 0 || getHeight() == 0) return;
        float w = frame.getWidth(), h = frame.getHeight();
        boolean sideways = rotation % 180 != 0;
        float scale = Math.min(getWidth() / (sideways ? h : w), getHeight() / (sideways ? w : h));
        matrix.postTranslate(-w / 2f, -h / 2f);
        if (mirror) matrix.postScale(-1, 1);
        if (rotation != 0) matrix.postRotate(rotation);
        matrix.postScale(scale, scale);
        matrix.postTranslate(getWidth() / 2f, getHeight() / 2f);
    }

    @Override protected void onDraw(Canvas canvas) {
        canvas.drawColor(Color.BLACK);
        if (frame != null && !frame.isRecycled()) canvas.drawBitmap(frame, matrix, paint);
    }
}
