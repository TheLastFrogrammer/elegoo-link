package io.github.thelastfrogrammer.elink;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;

public class CameraFitTest {
    private static final double ASPECT = 16.0 / 9;
    private static final double[] TRUTH = {308, -9, 33, 134, 8, 37, 0.34, 2.0};

    /** Taps along each edge where it shows in the picture, as a user would place them (with a little hand wobble). */
    private static List<CameraFit.Mark> taps(double[] camera, int[] edges, double wobble) {
        Random random = new Random(7);
        List<CameraFit.Mark> marks = new ArrayList<>();
        for (int edge : edges)
            for (int i = 2; i <= 46; i += 4) {
                double t = CameraFit.BED * i / 48;
                double x = edge == 2 ? 0 : edge == 3 ? CameraFit.BED : t, y = edge == 0 ? 0 : edge == 1 ? CameraFit.BED : t;
                double[] p = CameraFit.project(camera, ASPECT, x, y, 0);
                if (p == null) continue;
                double u = (p[0] / ASPECT + 1) / 2 + random.nextGaussian() * wobble, v = (1 - p[1]) / 2 + random.nextGaussian() * wobble;
                if (u < 0.02 || u > 0.98 || v < 0.02 || v > 0.98) continue;
                marks.add(new CameraFit.Mark(edge, u, v));
            }
        return marks;
    }

    @Test public void recoversTheCameraFromTapsAlongTheEdges() {
        List<CameraFit.Mark> marks = taps(TRUTH, new int[] {0, 1, 2, 3}, 0);
        assertTrue("enough taps in view: " + marks.size(), marks.size() >= 12);
        double[] start = {300, -3, 40, 130, 10, 40, 0.2, 0};
        assertTrue(CameraFit.rms(start, ASPECT, marks) > 0.02);
        double[] fitted = CameraFit.fit(start, ASPECT, marks);
        assertEquals("the fitted outline passes through the taps", 0, CameraFit.rms(fitted, ASPECT, marks), 1e-3);
        // Where the camera puts the bed's centre and corners agrees with the truth, which is what the overlay needs.
        for (double[] point : new double[][] {{128, 128}, {0, 256}, {256, 256}, {0, 0}}) {
            double[] want = CameraFit.project(TRUTH, ASPECT, point[0], point[1], 0), got = CameraFit.project(fitted, ASPECT, point[0], point[1], 0);
            if (want == null || Math.abs(want[0]) > ASPECT || Math.abs(want[1]) > 1) continue;   // only points in the picture
            assertNotNull(got);
            assertEquals(want[0], got[0], 0.005); assertEquals(want[1], got[1], 0.005);
        }
    }

    @Test public void handTapsStillGiveACloseFit() {
        List<CameraFit.Mark> marks = taps(TRUTH, new int[] {0, 1, 2, 3}, 0.004);
        double[] fitted = CameraFit.fit(new double[] {300, -3, 40, 130, 10, 40, 0.2, 0}, ASPECT, marks);
        // The fit follows the taps at least as closely as the true camera does (it cannot know which wobble is real) ...
        assertTrue(CameraFit.rms(fitted, ASPECT, marks) <= CameraFit.rms(TRUTH, ASPECT, marks) + 1e-4);
        // ... and lands within about a degree of it, so the bed's centre sits within a few percent of the picture's height.
        assertEquals(TRUTH[CameraFit.TURN], fitted[CameraFit.TURN], 1.5);
        assertEquals(TRUTH[CameraFit.TILT], fitted[CameraFit.TILT], 1.5);
        double[] want = CameraFit.project(TRUTH, ASPECT, 128, 128, 0), got = CameraFit.project(fitted, ASPECT, 128, 128, 0);
        assertEquals(0, Math.hypot(want[0] - got[0], want[1] - got[1]), 0.06);
    }

    @Test public void fewTapsOnlyMoveWhatTheyCanPinDown() {
        assertEquals(0, CameraFit.free(new ArrayList<>()).length);
        List<CameraFit.Mark> three = taps(TRUTH, new int[] {1}, 0).subList(0, 3);
        assertArrayEquals(new int[] {CameraFit.TURN, CameraFit.TILT, CameraFit.ROLL}, CameraFit.free(three));
        double[] start = {300, -3, 40, 130, 10, 40, 0.2, 0};
        double[] fitted = CameraFit.fit(start, ASPECT, three);
        assertEquals(300, fitted[CameraFit.X], 0); assertEquals(40, fitted[CameraFit.VIEW], 0); assertEquals(0.2, fitted[CameraFit.LENS], 0);
    }

    @Test public void valuesStayInsideTheSliderRanges() {
        List<CameraFit.Mark> marks = taps(TRUTH, new int[] {0, 1, 2, 3}, 0);
        double[] fitted = CameraFit.fit(new double[] {300, -3, 40, 130, 10, 40, 0.2, 0}, ASPECT, marks);
        for (int i = 0; i < CameraFit.COUNT; i++) assertTrue(fitted[i] >= CameraFit.RANGE[i][0] && fitted[i] <= CameraFit.RANGE[i][1]);
    }

    @Test public void tapsFromSeveralBedHeightsDescribeOneCamera() {
        double[] base = TRUTH.clone(); base[CameraFit.Z] = 28;   // the camera with the bed at 0
        List<CameraFit.TapSet> sets = new ArrayList<>();
        for (double z : new double[] {5, 100, 200}) sets.add(new CameraFit.TapSet(z, ASPECT, taps(CameraFit.atBed(base, z), new int[] {0, 1, 2, 3}, 0.004)));
        double[] start = {300, -3, 40, 130, 10, 40, 0.2, 0};
        double[] fitted = CameraFit.fit(start, sets);
        // One shared camera from three heights lands much closer than the hand-tap wobble allows from one height.
        assertEquals(base[CameraFit.X], fitted[CameraFit.X], 3);
        assertEquals(base[CameraFit.Z], fitted[CameraFit.Z], 2);
        assertEquals(base[CameraFit.TURN], fitted[CameraFit.TURN], 0.5);
        assertEquals(base[CameraFit.VIEW], fitted[CameraFit.VIEW], 1);
        assertTrue(CameraFit.rms(fitted, sets) <= CameraFit.rms(base, sets) + 1e-4);
    }
}
