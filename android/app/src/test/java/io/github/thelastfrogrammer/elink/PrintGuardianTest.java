package io.github.thelastfrogrammer.elink;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.junit.Test;

public class PrintGuardianTest {
    /** A camera in front of the bed looking at it from above: x, y, z, turn, tilt, view angle, lens, roll. */
    private static final double[] CAMERA = {128, -120, 260, 90, 50, 60, 0, 0};
    private static final double ASPECT = 16 / 9.0;
    private static final int RED = 0xd02020, GREY = 0x606060, BED = 0x303030;

    /** A 40 mm square at the bed's centre, `layers` layers of 0.2 mm. */
    private static GcodeToolpath square(int layers) throws IOException {
        StringBuilder g = new StringBuilder("; filament_colour = #D02020\nG90\nM83\n");
        for (int l = 0; l < layers; l++) {
            double z = 0.2 * (l + 1);
            g.append(String.format(Locale.ROOT, ";LAYER_CHANGE\nG1 Z%.2f\nG1 X108 Y108\n", z));
            for (double y = 108; y <= 148; y += 2) g.append(String.format(Locale.ROOT, "G1 X108 Y%.0f\nG1 X148 Y%.0f E2\n", y, y));
        }
        return GcodeToolpath.read(new ByteArrayInputStream(g.toString().getBytes(StandardCharsets.UTF_8)));
    }

    /** A picture: filament where the print is, the bed elsewhere, grey off the bed; `blob` adds filament-coloured cells off the print. */
    private static int[] picture(boolean[] print, boolean[] bed, boolean blob) {
        int[] rgb = new int[PrintGuardian.W * PrintGuardian.H];
        for (int i = 0; i < rgb.length; i++) rgb[i] = print[i] ? RED : bed[i] ? BED : GREY;
        if (blob) { int n = 0; for (int i = 0; i < rgb.length && n < 600; i++) if (bed[i] && !print[i] && (i % PrintGuardian.W) < 50) { rgb[i] = RED; n++; } }
        return rgb;
    }

    @Test public void projectsThePrintAndTheBedIntoView() throws IOException {
        GcodeToolpath path = square(10);
        boolean[] print = PrintGuardian.expectedMask(path, 9, CAMERA, ASPECT, 0), bed = PrintGuardian.bedMask(CAMERA, ASPECT);
        int printCells = 0, bedCells = 0;
        for (int i = 0; i < print.length; i++) { if (print[i]) printCells++; if (bed[i]) bedCells++; }
        assertTrue("print cells " + printCells, printCells > 50);
        assertTrue("bed cells " + bedCells, bedCells > printCells * 4);
        // The print's centre lands in the middle third of the picture.
        int c = PrintGuardian.cell(CameraFit.project(CAMERA, ASPECT, 128, 128, 1), ASPECT);
        assertTrue(c >= 0 && c % PrintGuardian.W > PrintGuardian.W / 3 && c % PrintGuardian.W < PrintGuardian.W * 2 / 3);
    }

    @Test public void measuresCoverageAndFilamentOffThePrint() throws IOException {
        GcodeToolpath path = square(10);
        boolean[] print = PrintGuardian.expectedMask(path, 9, CAMERA, ASPECT, 0), expected = PrintGuardian.grow(print, 2), bed = PrintGuardian.bedMask(CAMERA, ASPECT);
        boolean[] noHead = new boolean[print.length];
        PrintGuardian.Result fine = PrintGuardian.analyse(picture(print, bed, false), RED, expected, bed, noHead);
        assertTrue("coverage " + fine.coverage, fine.coverage > 0.3);
        assertEquals(0, fine.outside, 1e-9);
        PrintGuardian.Result blob = PrintGuardian.analyse(picture(print, bed, true), RED, expected, bed, noHead);
        assertTrue("outside " + blob.outside, blob.outside > 0.03);
        // The head hides what it covers: filament under it is not counted.
        boolean[] all = new boolean[print.length]; java.util.Arrays.fill(all, true);
        assertEquals(0, PrintGuardian.analyse(picture(print, bed, true), RED, expected, bed, all).bedPixels);
    }

    @Test public void filamentColourByHueOrBrightness() {
        boolean[] red = PrintGuardian.material(new int[] {0xc03030, 0x802020, 0x3030c0, 0x808080}, 0xd02020);
        assertTrue(red[0]); assertTrue(red[1]); assertFalse(red[2]); assertFalse(red[3]);
        boolean[] white = PrintGuardian.material(new int[] {0xf0f0f0, 0x202020, 0xf0e0e0}, 0xffffff);
        assertTrue(white[0]); assertFalse(white[1]); assertTrue(white[2]);
    }

    @Test public void alarmsOnlyAfterThreeBadChecksInARow() throws IOException {
        GcodeToolpath path = square(10);
        boolean[] print = PrintGuardian.expectedMask(path, 9, CAMERA, ASPECT, 0), expected = PrintGuardian.grow(print, 2), bed = PrintGuardian.bedMask(CAMERA, ASPECT), noHead = new boolean[print.length];
        PrintGuardian.Result good = PrintGuardian.analyse(picture(print, bed, false), RED, expected, bed, noHead);
        PrintGuardian.Result bad = PrintGuardian.analyse(picture(print, bed, true), RED, expected, bed, noHead);
        PrintGuardian.Watch watch = new PrintGuardian.Watch();
        for (int i = 0; i < 3; i++) assertFalse(watch.add(good, 6 + i));   // learning
        assertFalse(watch.add(bad, 9)); assertFalse(watch.add(good, 10));   // one bad check is not enough
        assertFalse(watch.add(bad, 11)); assertFalse(watch.add(bad, 12));
        assertTrue(watch.add(bad, 13));
        assertTrue(watch.reason, watch.reason.startsWith("filament-coloured area off the print"));
        assertFalse("one alarm per run", watch.add(bad, 14));
        watch.looksFine();
        assertFalse(watch.alarmed);
    }

    @Test public void alarmsWhenThePrintDisappears() throws IOException {
        GcodeToolpath path = square(10);
        boolean[] print = PrintGuardian.expectedMask(path, 9, CAMERA, ASPECT, 0), expected = PrintGuardian.grow(print, 2), bed = PrintGuardian.bedMask(CAMERA, ASPECT), noHead = new boolean[print.length];
        PrintGuardian.Result good = PrintGuardian.analyse(picture(print, bed, false), RED, expected, bed, noHead);
        PrintGuardian.Result gone = PrintGuardian.analyse(picture(new boolean[print.length], bed, false), RED, expected, bed, noHead);
        PrintGuardian.Watch watch = new PrintGuardian.Watch();
        for (int i = 0; i < 4; i++) watch.add(good, 6 + i);
        assertFalse(watch.add(gone, 10)); assertFalse(watch.add(gone, 11));
        assertTrue(watch.add(gone, 12));
        assertTrue(watch.reason, watch.reason.startsWith("the print's area shows"));
    }

    @Test public void tooLittleInViewIsNotJudged() {
        PrintGuardian.Result r = new PrintGuardian.Result(); r.expectedPixels = 5; r.bedPixels = 2000; r.outside = 0.9;
        PrintGuardian.Watch watch = new PrintGuardian.Watch();
        for (int i = 0; i < 10; i++) assertFalse(watch.add(r, i));
        assertTrue(watch.reason.startsWith("too little"));
    }
}
