package io.github.thelastfrogrammer.elink;

import org.junit.Test;
import static org.junit.Assert.*;

public class TimelapseEncoderTest {
    @Test public void aboutTenSecondsAndEncoderFriendlySizes() {
        assertEquals(2, TimelapseEncoder.framesPerSecond(3)); assertEquals(10, TimelapseEncoder.framesPerSecond(100)); assertEquals(15, TimelapseEncoder.framesPerSecond(1000));
        assertArrayEquals(new int[] {640, 480}, TimelapseEncoder.size(640, 480));
        assertArrayEquals(new int[] {1280, 720}, TimelapseEncoder.size(1920, 1080));
        int[] odd = TimelapseEncoder.size(1000, 750); assertEquals(0, odd[0] % 16); assertEquals(0, odd[1] % 16);
    }

    /** Side by side: each parked picture beside the far one of its layer (taken after it, before the next park). */
    @Test public void pairsEachLayersParkedAndFarPictures() {
        // Parked at 10, 70, 130; far at 40 (layer 1), none in layer 2, 150 (layer 3).
        int[][] pairs = TimelapseEncoder.pair(new double[] {10, 70, 130}, new double[] {40, 150});
        assertArrayEquals(new int[] {0, 0}, pairs[0]);
        assertArrayEquals("a layer without its far picture keeps the last one", new int[] {1, 0}, pairs[1]);
        assertArrayEquals(new int[] {2, 1}, pairs[2]);
        assertArrayEquals("none yet: that side stays dark", new int[] {0, -1}, TimelapseEncoder.pair(new double[] {10, 70}, new double[] {80})[0]);
        int[] wide = TimelapseEncoder.size(1280 * 2, 720, 1920); assertEquals(1920, wide[0]); assertEquals(0, wide[1] % 16);
    }

    /** Flicker evened out toward the neighbours, within limits; the end held for a moment. */
    @Test public void brightnessIsEvenedOutAndTheEndHeld() {
        double[] gains = TimelapseEncoder.gains(new double[] {100, 100, 100, 70, 100, 100, 100});
        assertEquals(1.0, gains[0], 1e-9);
        assertEquals("a dark picture is brightened, by at most a quarter", 1.25, gains[3], 1e-9);
        assertEquals("its neighbours stay as they are", 1.0, gains[2], 1e-9);
        assertEquals("never more than a fifth darker", 0.8, TimelapseEncoder.gains(new double[] {100, 250, 100})[1], 1e-9);
        assertEquals("unknown pictures stay as they are", 1.0, TimelapseEncoder.gains(new double[] {-1, 100})[0], 1e-9);
        assertEquals(15, TimelapseEncoder.holdFrames(10)); assertEquals(3, TimelapseEncoder.holdFrames(2));
    }
}
