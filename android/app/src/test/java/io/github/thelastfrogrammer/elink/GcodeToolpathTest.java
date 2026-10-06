package io.github.thelastfrogrammer.elink;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

public class GcodeToolpathTest {
    private static GcodeToolpath fixture() throws IOException {
        try (InputStream in = GcodeToolpathTest.class.getResourceAsStream("/gcode/tolerance-cc2.gcode")) { return GcodeToolpath.read(in); }
    }
    private static GcodeToolpath of(String gcode) throws IOException { return GcodeToolpath.read(new ByteArrayInputStream(gcode.getBytes(StandardCharsets.UTF_8))); }

    @Test public void readsAnElegooSlicerFile() throws Exception {
        GcodeToolpath path = fixture();
        assertEquals("matches \"; total layer number: 32\"", 32, path.layerCount);
        assertEquals(0.2f, path.layerZ(0), 1e-4);
        assertEquals(6.4f, path.layerZ(31), 1e-3);
        assertArrayEquals(new float[] {0, 0, 256, 0, 256, 256, 0, 256}, path.bed, 0);
        assertTrue(path.count > 10_000);
        assertTrue(path.travelCount > 1_000);
        // On the CC2 bed; the start G-code's purge line runs just in front of the printable area, at Y = -1.2.
        assertTrue(path.minX > 0 && path.maxX < 256 && path.maxY < 256);
        assertEquals(-1.2f, path.minY, 1e-4);
        double[] lengths = path.featureLengths();
        assertTrue(lengths[0] > 0 && lengths[1] > 0 && lengths[3] > 0 && lengths[7] > 0);
        assertEquals("every ;TYPE: is a known feature except Custom", 0, lengths[GcodeToolpath.OTHER], 1e-9);
        // Layers are in order and cover every segment.
        for (int layer = 1; layer < path.layerCount; layer++) assertTrue(path.layerStart(layer) > path.layerStart(layer - 1));
        assertEquals(path.count, path.layerEnd(path.layerCount - 1));
    }

    @Test public void purgeLineBeforeTheFirstLayerChangeBelongsToLayerOne() throws Exception {
        GcodeToolpath path = of("G90\nM83\nG1 X0 Y0 Z0.3 F600\nG1 X50 E5\n;LAYER_CHANGE\n;Z:0.2\nG1 Z0.2\nG1 X10 Y10\nG1 X20 E1\n;LAYER_CHANGE\n;Z:0.4\nG1 Z0.4\nG1 X10 E1\n");
        assertEquals(2, path.layerCount);
        assertEquals(0, path.layerStart(0)); assertEquals(2, path.layerStart(1));
        assertEquals(0.2f, path.layerZ(0), 1e-6); assertEquals(0.4f, path.layerZ(1), 1e-6);
    }

    @Test public void layersFromZWithoutSlicerComments() throws Exception {
        GcodeToolpath path = of("G21\nG90\nM82\nG92 E0\nG1 Z0.3\nG1 X10 Y0 E1\nG1 X10 Y10 E2\nG1 Z0.6\nG1 X0 Y10 E3\nG92 E0\nG1 X0 Y0 E1\n");
        assertEquals(2, path.layerCount);
        assertEquals(4, path.count);
        assertEquals(2, path.layerStart(1));
    }

    @Test public void relativeMovesAndRetractionsAreNotExtrusions() throws Exception {
        GcodeToolpath path = of("G91\nG1 Z0.2\nG1 X10 E1\nG1 E-0.8\nG1 X5 Y5\nG1 E0.8\nG1 Y10 E0.5\n");
        assertEquals(2, path.count);
        assertEquals(10f, path.x1[0], 1e-6);
        assertEquals(15f, path.x0[1], 1e-6); assertEquals(15f, path.y1[1], 1e-6);
        assertEquals("the Z move and the X/Y move", 2, path.travelCount);
    }

    @Test public void arcsAreSplitIntoShortSegmentsAlongTheCircle() throws Exception {
        // Half circle of radius 10 around (10,0), counter-clockwise from (0,0) to (20,0) through (10,-10).
        GcodeToolpath path = of("G90\nM83\nG1 Z0.2\nG1 X0 Y0\nG3 X20 Y0 I10 J0 E1\n");
        assertTrue(path.count >= 18);
        for (int i = 0; i < path.count; i++) {
            assertEquals(10, Math.hypot(path.x1[i] - 10, path.y1[i]), 1e-4);
            assertTrue(path.y1[i] <= 1e-4);
        }
        assertEquals(20f, path.x1[path.count - 1], 1e-6);
    }

    @Test public void locatesTheNozzleWithinTheReportedLayer() throws Exception {
        GcodeToolpath path = fixture();
        int layer = 10, start = path.layerStart(layer - 1), end = path.layerEnd(layer - 1);
        int target = start + (end - start) / 2;
        double mx = (path.x0[target] + path.x1[target]) / 2, my = (path.y0[target] + path.y1[target]) / 2;
        int located = path.locate(layer, mx, my, true, start);
        assertTrue("located " + located + " near " + target, Math.abs(located - target) <= 1 || distance(path, located, mx, my) < 0.05);
        // At the end point of a segment, that segment counts as printed.
        assertEquals(target + 1, path.locate(layer, path.x1[target], path.y1[target], true, target));
        // Without a position: the start of the layer. Layer numbers are clamped.
        assertEquals(start, path.locate(layer, 0, 0, false, -1));
        assertEquals(path.layerStart(path.layerCount - 1), path.locate(999, 0, 0, false, -1));
        assertEquals(0, path.locate(0, 0, 0, false, -1));
        // Walking the layer's segments in order gives monotonic progress.
        int previous = start;
        for (int i = start; i < end; i += 7) {
            int found = path.locate(layer, path.x1[i], path.y1[i], true, previous);
            assertTrue("segment " + i + " -> " + found + " after " + previous, found >= previous || found >= i);
            previous = found;
        }
    }
    private static double distance(GcodeToolpath path, int i, double x, double y) {
        double best = Double.MAX_VALUE;
        for (int k = Math.max(0, i - 1); k <= Math.min(path.count - 1, i); k++) best = Math.min(best, Math.hypot((path.x0[k] + path.x1[k]) / 2 - x, (path.y0[k] + path.y1[k]) / 2 - y));
        return best;
    }

    @Test public void binaryExportForTheViewer() throws Exception {
        GcodeToolpath path = of("G90\nM83\nG1 Z0.2\n;TYPE:Outer wall\n;WIDTH:0.42\n;HEIGHT:0.2\nG1 X10 E1\n");
        ByteBuffer data = ByteBuffer.wrap(path.segmentsBinary()).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(GcodeToolpath.FLOATS_PER_SEGMENT * 4, data.capacity());
        float[] values = new float[GcodeToolpath.FLOATS_PER_SEGMENT];
        for (int i = 0; i < values.length; i++) values[i] = data.getFloat();
        assertArrayEquals(new float[] {0, 0, 0.2f, 10, 0, 0.2f, 0.42f, 0.2f, 0}, values, 1e-6f);
        assertTrue(path.layersJson().startsWith("{\"segments\":1,\"travels\":1,\"starts\":[0],\"travelStarts\":[0],\"z\":[0.200],\"bed\":[0.0,0.0,256.0"));
    }
}
