package io.github.thelastfrogrammer.elink;

import static org.junit.Assert.*;

import android.graphics.Bitmap;
import java.io.File;
import java.io.FileOutputStream;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/** The thermal picture as every view shows it; with -Dscreenshots=DIR, DIR/thermal-picture.png. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 34)
public class ThermalPictureRenderTest {
    /** A 640 × 512 scene in 10 mK steps: a 60 °C bed, a cooler room above, a 215 °C nozzle. */
    static ThermalImage scene() {
        int w = 640, h = 512; int[] v = new int[w * h];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            double c = y > 300 ? 58 + 4 * Math.sin(x / 90.0) : 24 + y / 60.0;
            double d = Math.hypot(x - 430, y - 250); if (d < 18) c = 215 - d * 3; else if (d < 60) c = Math.max(c, 80 - d);
            v[y * w + x] = (int) Math.round((c + 273.15) * 100);
        }
        return new ThermalImage(w, h, v, 0.01);
    }

    @Test public void thePictureCarriesItsTemperatures() throws Exception {
        ThermalImage image = scene();
        ThermalImage.Stats s = image.stats();
        assertTrue(s.temperatures);
        assertEquals(215, image.celsius(s.hottest), 0.05);
        assertEquals(430, s.hotX); assertEquals(250, s.hotY);
        Bitmap b = ThermalPicture.bitmap(image, 0, false);
        assertEquals(1280, b.getWidth()); assertTrue(b.getHeight() > 1024);
        Bitmap turned = ThermalPicture.bitmap(image, 90, false);
        assertEquals(1024, turned.getWidth());
        assertTrue(ThermalPicture.jpeg(b).length > 1000);
        Bitmap none = ThermalPicture.bitmap(new ThermalImage(640, 512, new int[640 * 512], Double.NaN), 0, false);
        assertNotNull(none);
        String dir = System.getProperty("screenshots", "");
        if (!dir.isEmpty()) {
            File f = new File(dir, "thermal-picture.png"); f.getParentFile().mkdirs();
            try (FileOutputStream out = new FileOutputStream(f)) { b.compress(Bitmap.CompressFormat.PNG, 100, out); }
        }
    }

    @Test public void addressesForAThermalCamera() {
        assertEquals("", ExtraCameras.thermalProblem("192.168.1.80"));
        assertEquals("", ExtraCameras.thermalProblem("flir-a65.local"));
        assertTrue(ExtraCameras.thermalProblem("http://192.168.1.80").contains("without http"));
        assertTrue(ExtraCameras.thermalProblem("192.168.1").contains("not complete"));
        assertTrue(ExtraCameras.thermalProblem("192.168.1.300").contains("not complete"));
        assertFalse(ExtraCameras.thermalProblem("").isEmpty());
    }
}
