package io.github.thelastfrogrammer.elink;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.ImageView;

/**
 * A picture that fits the view and can be zoomed (pinch, double tap, or zoomBy for buttons) and moved when zoomed. New frames
 * of the same size keep the zoom, so a live camera can be followed closely.
 */
final class ZoomImageView extends ImageView {
    static final float MAX_ZOOM = 6;
    private float zoom = 1, panX, panY;
    private int imageW, imageH;
    private final ScaleGestureDetector pinch;
    private final GestureDetector gestures;

    ZoomImageView(Context context) {
        super(context);
        setScaleType(ScaleType.MATRIX);
        pinch = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector detector) { zoomAt(detector.getScaleFactor(), detector.getFocusX(), detector.getFocusY()); return true; }
        });
        gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onScroll(MotionEvent a, MotionEvent b, float dx, float dy) { panX -= dx; panY -= dy; apply(); return true; }
            @Override public boolean onDoubleTap(MotionEvent e) { if (zoom > 1.01f) reset(); else zoomAt(2.5f, e.getX(), e.getY()); return true; }
        });
    }

    @Override public void setImageBitmap(Bitmap image) {
        boolean resized = image != null && (image.getWidth() != imageW || image.getHeight() != imageH);
        super.setImageBitmap(image);
        if (image != null) { imageW = image.getWidth(); imageH = image.getHeight(); }
        if (resized) reset(); else apply();
    }
    @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) { super.onSizeChanged(w, h, oldW, oldH); apply(); }

    @Override public boolean onTouchEvent(MotionEvent event) {
        pinch.onTouchEvent(event); gestures.onTouchEvent(event);
        if (event.getAction() == MotionEvent.ACTION_UP) performClick();
        return true;
    }
    @Override public boolean performClick() { return super.performClick(); }

    float zoom() { return zoom; }
    void reset() { zoom = 1; panX = 0; panY = 0; apply(); }
    /** Zoom about the centre (for buttons and accessibility). */
    void zoomBy(float factor) { zoomAt(factor, getWidth() / 2f, getHeight() / 2f); }
    private void zoomAt(float factor, float x, float y) {
        float next = Math.max(1, Math.min(MAX_ZOOM, zoom * factor)), applied = next / zoom;
        // Keep the point under the fingers in place: the offset from the view centre scales with the zoom.
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        panX = (panX - (x - cx)) * applied + (x - cx); panY = (panY - (y - cy)) * applied + (y - cy);
        zoom = next; apply();
    }
    private void apply() {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0 || imageW == 0 || imageH == 0) return;
        float fit = Math.min((float) w / imageW, (float) h / imageH), scale = fit * zoom;
        float shownW = imageW * scale, shownH = imageH * scale;
        // Never past the picture's edges: centred when smaller than the view, otherwise kept inside.
        float maxX = Math.max(0, (shownW - w) / 2), maxY = Math.max(0, (shownH - h) / 2);
        panX = Math.max(-maxX, Math.min(maxX, panX)); panY = Math.max(-maxY, Math.min(maxY, panY));
        Matrix m = new Matrix(); m.postScale(scale, scale); m.postTranslate((w - shownW) / 2 + panX, (h - shownH) / 2 + panY);
        setImageMatrix(m);
    }
}
