package io.github.thelastfrogrammer.elink;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.view.View;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 34)
public class ZoomImageViewTest {
    @Test public void zoomStaysWithinLimitsAndANewFrameOfTheSameSizeKeepsIt() {
        ZoomImageView view = new ZoomImageView(RuntimeEnvironment.getApplication());
        view.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY)); view.layout(0, 0, 1000, 500);
        view.setImageBitmap(Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888));
        float[] values = new float[9]; view.getImageMatrix().getValues(values);
        assertEquals("fits the height", 500f / 480, values[Matrix.MSCALE_X], 1e-4);
        view.zoomBy(2); view.zoomBy(2); view.zoomBy(2); assertEquals(ZoomImageView.MAX_ZOOM, view.zoom(), 1e-4);
        view.setImageBitmap(Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)); assertEquals("the next frame keeps the zoom", ZoomImageView.MAX_ZOOM, view.zoom(), 1e-4);
        view.zoomBy(0.01f); assertEquals(1, view.zoom(), 1e-4);
        view.zoomBy(3); view.setImageBitmap(Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)); assertEquals("a new size starts fitted", 1, view.zoom(), 1e-4);
    }
}
