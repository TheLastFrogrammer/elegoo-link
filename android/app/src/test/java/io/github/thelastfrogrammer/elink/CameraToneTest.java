package io.github.thelastfrogrammer.elink;

import org.junit.Test;
import static org.junit.Assert.*;

public class CameraToneTest {
    @Test public void darkFramesAreLiftedAndBrightOnesLeftAlone() {
        int[] dark = new int[100], bright = new int[100];
        java.util.Arrays.fill(dark, 0xff141414); java.util.Arrays.fill(bright, 0xffb4b4b4);
        assertTrue(CameraTone.luminance(dark) < CameraTone.DARK);
        assertEquals(3, CameraTone.gain(CameraTone.luminance(dark)), 1e-6);
        assertEquals(1, CameraTone.gain(CameraTone.luminance(bright)), 1e-6);
        assertEquals(1, CameraTone.gain(0.6), 1e-6); assertEquals(2.1, CameraTone.gain(0.2), 1e-6);
        float[] m = CameraTone.matrix(2); assertEquals(20, m.length); assertEquals(2, m[0], 0); assertEquals(1, m[18], 0); assertEquals(6, m[4], 0);
        assertEquals(0, CameraTone.luminance(new int[0]), 0);
    }
}
