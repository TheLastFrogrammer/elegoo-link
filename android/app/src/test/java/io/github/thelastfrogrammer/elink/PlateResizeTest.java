package io.github.thelastfrogrammer.elink;

import org.junit.Test;
import static org.junit.Assert.*;

/** Typed sizes in millimetres become the even scale (in proportion) or each axis's stretch. */
public class PlateResizeTest {
    private static final double[] SIZE = {40, 20, 10}, PLAIN = {1, 1, 1};

    @Test public void inProportionOneSizeSetsTheEvenScale() {
        assertArrayEquals(new double[] {1.5, 1, 1, 1}, PlateActivity.resize(1, PLAIN, SIZE, new double[] {60, 30, 15}, true), 1e-9);
        // Only the height typed (the others still show the old sizes): the height decides.
        assertArrayEquals(new double[] {0.5, 1, 1, 1}, PlateActivity.resize(1, PLAIN, SIZE, new double[] {40, 20, 5}, true), 1e-9);
        assertArrayEquals("on top of an earlier scale", new double[] {1, 1, 1, 1}, PlateActivity.resize(2, PLAIN, SIZE, new double[] {20, 10, 5}, true), 1e-9);
    }

    @Test public void eachAxisOnItsOwnStretchesThatAxis() {
        assertArrayEquals(new double[] {1, 2, 1, 1.5}, PlateActivity.resize(1, PLAIN, SIZE, new double[] {80, 20, 15}, false), 1e-9);
        assertArrayEquals("an existing stretch is kept and adjusted", new double[] {1, 1, 1, 3}, PlateActivity.resize(1, new double[] {2, 1, 1.5}, new double[] {80, 20, 15}, new double[] {40, 20, 30}, false), 1e-9);
    }

    @Test public void outOfRangeSizesAreRefused() {
        assertNull(PlateActivity.resize(1, PLAIN, SIZE, new double[] {0, 20, 10}, false));
        assertNull(PlateActivity.resize(1, PLAIN, SIZE, new double[] {20000, 20, 10}, false));
        assertNull("1000 times the original or more: the slicer refuses it", PlateActivity.resize(1, PLAIN, new double[] {0.2, 20, 10}, new double[] {200, 20, 10}, false));
    }

    @Test public void aFileMadeInOtherUnitsCanBeSizedBack() {
        // Made in inches: a 2-inch cube reads as 2 mm; typed back as 50.8 mm.
        assertArrayEquals(new double[] {25.4, 1, 1, 1}, PlateActivity.resize(1, PLAIN, new double[] {2, 2, 2}, new double[] {50.8, 50.8, 50.8}, true), 1e-9);
        // Made in micrometres-as-mm: 40000 "mm" brought down to 40 mm.
        assertArrayEquals(new double[] {0.004, 1, 1, 1}, PlateActivity.resize(1, PLAIN, new double[] {10000, 5000, 2500}, new double[] {40, 20, 10}, true), 1e-9);
        assertTrue(PlateActivity.inRange(25.4) && PlateActivity.inRange(0.1) && PlateActivity.inRange(1 / 25.4));
        assertFalse(PlateActivity.inRange(0.001)); assertFalse(PlateActivity.inRange(1000));
    }
}
