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
        assertNull(PlateActivity.resize(1, PLAIN, SIZE, new double[] {2000, 20, 10}, false));
        assertNull("more than 2000% of the original", PlateActivity.resize(1, PLAIN, new double[] {0.2, 20, 10}, new double[] {900, 20, 10}, false));
    }
}
