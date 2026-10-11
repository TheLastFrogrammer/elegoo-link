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
}
