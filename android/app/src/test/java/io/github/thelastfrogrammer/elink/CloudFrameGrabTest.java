package io.github.thelastfrogrammer.elink;

import static org.junit.Assert.assertArrayEquals;

import java.nio.ByteBuffer;
import org.junit.Test;

/** A decoded frame's I420 planes, with padded rows, become one NV21 picture for the JPEG encoder. */
public class CloudFrameGrabTest {
    @Test public void planesWithStridesBecomeNv21() {
        // 2x2 picture: Y rows padded to 4 bytes, one chroma sample each padded to 2.
        ByteBuffer y = ByteBuffer.wrap(new byte[] {1, 2, 9, 9, 3, 4, 9, 9}), u = ByteBuffer.wrap(new byte[] {5, 9}), v = ByteBuffer.wrap(new byte[] {6, 9});
        assertArrayEquals(new byte[] {1, 2, 3, 4, 6, 5}, CloudFrameGrab.nv21(2, 2, y, 4, u, 2, v, 2));
    }
    @Test public void oddSizesKeepTheLastChromaColumn() {
        ByteBuffer y = ByteBuffer.wrap(new byte[] {1, 2, 3}), u = ByteBuffer.wrap(new byte[] {7, 8}), v = ByteBuffer.wrap(new byte[] {4, 5});
        assertArrayEquals(new byte[] {1, 2, 3, 4, 7, 5, 8}, CloudFrameGrab.nv21(3, 1, y, 3, u, 2, v, 2));
    }
}
