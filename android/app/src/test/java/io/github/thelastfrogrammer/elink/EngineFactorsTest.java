package io.github.thelastfrogrammer.elink;

import org.junit.Test;
import static org.junit.Assert.*;

/** A scale past what the engine takes in one factor is shared with the stretch, keeping each axis's size. */
public class EngineFactorsTest {
    private static void sameSize(double scale, double[] stretch, double[] k) {
        for (int i = 0; i < 3; i++) assertEquals(scale * stretch[i], k[0] * k[i + 1], 1e-9 * scale * stretch[i]);
        for (double f : k) assertTrue("each factor in the engine's range: " + f, f > 0.001 && f < 1000);
    }

    @Test public void inRangeIsUnchanged() {
        assertArrayEquals(new double[] {25.4, 1, 2, 1}, NativeSlicer.engineFactors(25.4, new double[] {1, 2, 1}), 0);
    }

    @Test public void aFileInMetresIsShared() {
        double[] plain = {1, 1, 1};
        sameSize(1000, plain, NativeSlicer.engineFactors(1000, plain));
        sameSize(25400, plain, NativeSlicer.engineFactors(25400, plain));
        sameSize(0.0005, plain, NativeSlicer.engineFactors(0.0005, plain));
        double[] stretched = {2, 1, 0.5};
        sameSize(5000, stretched, NativeSlicer.engineFactors(5000, stretched));
    }
}
