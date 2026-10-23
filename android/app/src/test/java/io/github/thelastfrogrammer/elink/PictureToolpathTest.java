package io.github.thelastfrogrammer.elink;

import static org.junit.Assert.*;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.RectF;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.GraphicsMode;

/** Where a picture falls in the print's G-code (from the reported layer and head position), and the toolpath drawn over it. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@org.robolectric.annotation.Config(sdk = 34)
public class PictureToolpathTest {
    private File dir;
    @Before public void folder() throws Exception { dir = Files.createTempDirectory("picture-toolpath").toFile(); }
    @After public void clean() { delete(dir); }
    private static void delete(File f) { File[] all = f.listFiles(); if (all != null) for (File c : all) delete(c); f.delete(); }

    private static final String GCODE = String.join("\n", "M83",
        ";LAYER_CHANGE", ";Z:0.2", ";HEIGHT:0.2", "G1 X100 Y100 Z0.2 F6000", ";TYPE:Outer wall",
        "G1 X150 Y100 E2 F3000", "G1 X150 Y150 E2", "G1 X100 Y150 E2", "G1 X100 Y100 E2",
        ";LAYER_CHANGE", ";Z:0.4", ";HEIGHT:0.2", "G1 Z0.4",
        "G1 X150 Y100 E2", "G1 X150 Y150 E2", "G1 X100 Y150 E2", "G1 X100 Y100 E2", "");

    private PictureToolpath toolpath() throws Exception {
        File file = new File(dir, "part.gcode"); Files.write(file.toPath(), GCODE.getBytes(StandardCharsets.UTF_8));
        return new PictureToolpath(GcodeToolpath.read(file), file);
    }
    private File pictures(String... rows) throws Exception {
        File folder = new File(dir, "pictures"); folder.mkdirs();
        Files.write(new File(folder, PrintRecorder.POSITIONS).toPath(), String.join("\n", rows).getBytes(StandardCharsets.UTF_8));
        return folder;
    }

    @Test public void eachPictureFindsItsLineOrSaysTheHeadWasAway() throws Exception {
        PictureToolpath t = toolpath();
        File folder = pictures("000060.jpg,60,150.00,130.00,0.400,2,0.8", "000090.jpg,90,250.00,250.00,10.000,2,0.5", "000030.jpg,30,125.00,100.00,0.200,1,1.0");
        Map<String, String> lines = t.lines(Arrays.asList(new File(folder, "000060.jpg"), new File(folder, "000090.jpg"), new File(folder, "000030.jpg"), new File(folder, "000120.jpg")));
        assertEquals("G-code line 16: G1 X150 Y150 E2", lines.get("000060.jpg"));   // layer 2's second wall, where the nozzle was
        assertEquals("G-code line 7: G1 X150 Y100 E2 F3000", lines.get("000030.jpg"));
        assertTrue(lines.get("000090.jpg"), lines.get("000090.jpg").startsWith("Head away from the print"));
        assertFalse("no position, no line", lines.containsKey("000120.jpg"));
        // Located: on the print, the segment the nozzle is on; away, the layer's start (the layers before are done).
        int[] on = t.locate(new double[] {60, 150, 130, 0.4, 2, 0}), away = t.locate(new double[] {90, 250, 250, 10, 2, 0});
        assertEquals(5, on[1]); assertEquals(1, on[2]);
        assertEquals(-1, away[1]); assertEquals(t.path.layerStart(1), away[0]);
    }

    @Test public void theToolpathIsDrawnThroughTheCamera() throws Exception {
        PictureToolpath t = toolpath();
        // A camera above the bed's middle looking straight down: the square's walls fall inside the picture.
        double[] camera = {128, 128, 400, 0, 89.9, 60, 0, 0};
        Bitmap frame = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888); frame.eraseColor(0xff000000);
        t.draw(new Canvas(frame), new RectF(0, 0, 640, 480), camera, t.locate(new double[] {60, 150, 130, 0.4, 2, 0}));
        int lit = 0; for (int y = 0; y < 480; y += 2) for (int x = 0; x < 640; x += 2) if ((frame.getPixel(x, y) & 0x00ffffff) != 0) lit++;
        assertTrue("lines drawn: " + lit, lit > 50);
        assertNotNull(CameraFit.project(camera, 640 / 480.0, 125, 125, 0));
    }
}
