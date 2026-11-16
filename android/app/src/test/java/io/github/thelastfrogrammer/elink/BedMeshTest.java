package io.github.thelastfrogrammer.elink;

import static org.junit.Assert.*;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** The bed mesh rebuilt from the positions the printer reports while probing, its summary, comparison and storage. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class BedMeshTest {
    /** A bed tilted 0.10 mm across X and 0.05 mm across Y, with a 0.08 mm bump near the middle. */
    static double surface(double x, double y) { return 0.10 * x / 236 + 0.05 * y / 236 - 0.06 + (Math.abs(x - 128) < 30 && Math.abs(y - 128) < 30 ? 0.08 : 0); }
    static JSONObject status(int code, int sub, double x, double y, double z) throws Exception {
        return new JSONObject().put("machine_status", new JSONObject().put("status", code).put("sub_status", sub))
            .put("gcode_move", new JSONObject().put("x", x).put("y", y).put("z", z)).put("heater_bed", new JSONObject().put("temperature", 60.2).put("target", 60));
    }
    /** What the status stream shows while an 11 × 11 grid is probed: travel high, a fall, the touch, the lift. */
    static List<JSONObject> levelling(int code, int pointsLimit) throws Exception {
        List<JSONObject> out = new ArrayList<>(); int n = 0;
        for (int j = 0; j < 11; j++) for (int i = 0; i < 11; i++) {
            if (n++ >= pointsLimit) return out;
            double x = 10 + i * 23.6, y = 10 + j * 23.6;
            out.add(status(code, 2901, x, y, 5));
            out.add(status(code, 2901, x, y, 2.0));
            out.add(status(code, 2901, x, y, 1.2 + surface(x, y)));        // still falling, already under the touch threshold
            out.add(status(code, 2901, x, y, surface(x, y)));              // the touch: the last near-zero reading counts
            out.add(status(code, 2901, x, y, 3));
        }
        return out;
    }

    @Test public void theProbeGridIsRebuiltFromTheStatusStream() throws Exception {
        BedMesh.Capture c = new BedMesh.Capture();
        assertNull(c.feed(status(1, 0, 0, 0, 0), 0, "local network", 0));
        List<JSONObject> stream = levelling(2, 121);
        stream.get(0).put("print_status", new JSONObject().put("filename", "Desk_mount.gcode"));
        boolean first = true;
        for (JSONObject s : stream) { assertNull(c.feed(s, 1000, "local network", 0)); if (first) { assertTrue(c.began); first = false; } }
        assertTrue(c.active());
        BedMesh.Mesh m = c.feed(status(2, 2075, 100, 100, 0.2), 2000, "local network", 0);   // printing: the levelling has ended
        assertNotNull(c.problem, m);
        assertEquals(121, m.count()); assertEquals(11, m.xs.length); assertEquals(11, m.ys.length);
        assertEquals(surface(10, 10), m.z[0][0], 1e-3); assertEquals(surface(246, 246), m.z[10][10], 1e-3);
        assertEquals("Desk_mount.gcode", m.file); assertEquals(60.2, m.bed, 0); assertEquals(2901, m.phase);
        double[] p = BedMesh.plane(m);
        assertEquals(0.10 / 236, p[1], 0.0002); assertEquals(0.05 / 236, p[2], 0.0002);
        assertTrue("the bump remains once the tilt is gone", BedMesh.residualRange(m, p) > 0.07 && BedMesh.residualRange(m, p) < 0.10);
        List<String> words = BedMesh.describe(m);
        assertTrue(words.get(0), words.get(0).startsWith("121 points"));
        assertTrue(words.get(2), words.get(2).startsWith("Tilt: 0.10 mm across X, 0.05 mm across Y"));
    }

    @Test public void aLevellingCutShortIsNotKept() throws Exception {
        BedMesh.Capture c = new BedMesh.Capture();
        for (JSONObject s : levelling(5, 60)) c.feed(s, 0, "local network", 0);
        assertNull("rows 1-5 and part of row 6 do not make a full grid", c.feed(status(1, 0, 0, 0, 0), 1, "local network", 121));
        assertTrue(c.problem, c.problem.contains("points came through"));
        // A complete 3-row grid is still smaller than the last kept one: not kept either.
        for (JSONObject s : levelling(5, 33)) c.feed(s, 0, "local network", 0);
        assertNull(c.feed(status(1, 0, 0, 0, 0), 1, "local network", 121));
        // Nothing reported but the state: says so.
        c.feed(new JSONObject().put("machine_status", new JSONObject().put("status", 5).put("sub_status", 2901)), 0, "Elegoo cloud", 0);
        assertNull(c.feed(status(1, 0, 0, 0, 0), 1, "Elegoo cloud", 0));
        assertTrue(c.problem, c.problem.contains("no probe positions"));
    }

    @Test public void comparedKeptAndReadBack() throws Exception {
        BedMesh.Capture c = new BedMesh.Capture();
        for (JSONObject s : levelling(5, 121)) c.feed(s, 0, "local network", 0);
        BedMesh.Mesh a = c.feed(status(1, 0, 0, 0, 0), 1_000_000, "local network", 0);
        for (JSONObject s : levelling(5, 121)) { JSONObject m = s.getJSONObject("gcode_move"); if (m.getDouble("z") < 1) m.put("z", m.getDouble("z") + 0.03); c.feed(s, 0, "local network", 0); }
        BedMesh.Mesh b = c.feed(status(1, 0, 0, 0, 0), 2_000_000, "local network", 121);
        BedMesh.Mesh d = BedMesh.difference(a, b);
        assertEquals(0.03, d.mean(), 1e-4);
        assertTrue(BedMesh.changeWords(a, d), BedMesh.changeWords(a, d).startsWith("Changed by up to 0.03 mm since"));

        File dir = Files.createTempDirectory("bed-mesh").toFile();
        BedMesh.save(dir, a); BedMesh.save(dir, b);
        List<BedMesh.Mesh> kept = BedMesh.list(dir);
        assertEquals(2, kept.size()); assertEquals(2_000_000, kept.get(0).at);
        assertEquals(b.z[5][5], kept.get(0).z[5][5], 1e-9); assertTrue(kept.get(0).sameGrid(kept.get(1)));
        JSONObject file = BedMesh.json(b);
        assertEquals("the same shape as cc2-bed-mesh's files", 11, file.getJSONArray("z_mm").getJSONArray(0).length());
        assertEquals(121, file.getJSONObject("capture").getInt("point_count"));
        assertFalse("no serial or address in the file", file.toString().matches(".*(\\d{1,3}\\.){3}\\d{1,3}.*"));
    }

    @Test public void theModelIsAClosedSolidFacingOut() throws Exception {
        BedMesh.Capture c = new BedMesh.Capture();
        for (JSONObject s : levelling(5, 121)) c.feed(s, 0, "local network", 0);
        BedMesh.Mesh m = c.feed(status(1, 0, 0, 0, 0), 1, "local network", 0);
        int ex = BedMesh.autoExaggeration(m);
        assertEquals("a 0.18 mm range shown about 40 mm tall", 200, ex);
        byte[] stl = BedMesh.stl(m, ex, 2);
        java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(stl).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int n = b.getInt(80);
        assertEquals("top 2×100, walls 4×10×2, bottom 2×100", 200 + 80 + 200, n);
        assertEquals(84 + n * 50, stl.length);
        // Closed: every edge is shared by exactly two triangles, once in each direction (so all faces point the same way).
        java.util.Map<String, Integer> edges = new java.util.HashMap<>(); double volume = 0, top = 0;
        for (int t = 0; t < n; t++) {
            float[][] p = new float[3][3];
            for (int k = 0; k < 3; k++) for (int a = 0; a < 3; a++) p[k][a] = b.getFloat(84 + t * 50 + 12 + k * 12 + a * 4);
            for (int k = 0; k < 3; k++) { float[] u = p[k], v = p[(k + 1) % 3]; edges.merge(key(u) + ">" + key(v), 1, Integer::sum); }
            // Signed volume of the tetrahedron with the origin: positive in total only when the faces point out.
            volume += (p[0][0] * (p[1][1] * p[2][2] - p[1][2] * p[2][1]) - p[0][1] * (p[1][0] * p[2][2] - p[1][2] * p[2][0]) + p[0][2] * (p[1][0] * p[2][1] - p[1][1] * p[2][0])) / 6;
            for (float[] q : p) top = Math.max(top, q[2]);
        }
        for (java.util.Map.Entry<String, Integer> e : edges.entrySet()) {
            String[] ends = e.getKey().split(">");
            assertEquals("edge " + e.getKey() + " once each way", 1, (int) e.getValue());
            assertEquals("its twin", Integer.valueOf(1), edges.get(ends[1] + ">" + ends[0]));
        }
        assertTrue("faces point out: " + volume, volume > 236 * 236 * 2);
        assertEquals("the highest point is the base plus the range × the exaggeration", 2 + m.range() * ex, top, 0.01);
        assertEquals(500, BedMeshActivity.step(200, 1)); assertEquals(100, BedMeshActivity.step(200, -1)); assertEquals(10, BedMeshActivity.step(10, -1)); assertEquals(1000, BedMeshActivity.step(1000, 1));
    }
    private static String key(float[] p) { return Math.round(p[0] * 100) + "," + Math.round(p[1] * 100) + "," + Math.round(p[2] * 100); }

    static BedMesh.Mesh probedOnce() throws Exception { return probed(); }
    private static BedMesh.Mesh probed() throws Exception {
        BedMesh.Capture c = new BedMesh.Capture();
        for (JSONObject s : levelling(5, 121)) c.feed(s, 0, "local network", 0);
        return c.feed(status(1, 0, 0, 0, 0), 1, "local network", 0);
    }

    @Test public void fillingInKeepsTheProbePointsAndEachWayKeepsItsPromise() throws Exception {
        BedMesh.Mesh m = probed();
        assertSame("as probed is the mesh itself", m, BedMesh.interpolate(m, BedMesh.PROBED, 4));
        for (String how : new String[] {BedMesh.LINEAR, BedMesh.SMOOTH}) for (int detail : BedMesh.DETAILS) {
            BedMesh.Mesh f = BedMesh.interpolate(m, how, detail);
            assertEquals(10 * detail + 1, f.xs.length); assertEquals(10 * detail + 1, f.ys.length);
            assertSame(m, f.probed); assertEquals(detail, f.detail);
            for (int j = 0; j < 11; j++) for (int i = 0; i < 11; i++) {
                assertEquals(how + " keeps the measured value at each probe point", m.z[j][i], f.z[j * detail][i * detail], 1e-9);
                assertTrue(BedMesh.probePoint(f, i * detail, j * detail));
            }
            assertEquals(m.xs[3] + (m.xs[4] - m.xs[3]) / 2, f.xs[3 * detail + detail / 2], 1e-9);
        }
        // Linear: halfway between two points is their mean, and nothing goes past the measured heights.
        BedMesh.Mesh linear = BedMesh.interpolate(m, BedMesh.LINEAR, 4);
        assertEquals((m.z[0][0] + m.z[0][1]) / 2, linear.z[0][2], 1e-9);
        assertTrue(linear.min() >= m.min() - 1e-9 && linear.max() <= m.max() + 1e-9);
        assertFalse(BedMesh.probePoint(linear, 1, 0));
        // Smooth: a tilted plane stays exactly that plane (edges included), and it may go a little past the measured heights.
        BedMesh.Mesh plane = new BedMesh.Mesh(); plane.xs = m.xs.clone(); plane.ys = m.ys.clone(); plane.z = new double[11][11];
        for (int j = 0; j < 11; j++) for (int i = 0; i < 11; i++) plane.z[j][i] = 0.001 * m.xs[i] - 0.0005 * m.ys[j] + 0.02;
        BedMesh.Mesh smooth = BedMesh.interpolate(plane, BedMesh.SMOOTH, 8);
        for (int j = 0; j < smooth.ys.length; j++) for (int i = 0; i < smooth.xs.length; i++)
            // Exact although the probe positions are 23 or 24 mm apart: the curve's slopes use the real positions.
            assertEquals(0.001 * smooth.xs[i] - 0.0005 * smooth.ys[j] + 0.02, smooth.z[j][i], 1e-12);
        BedMesh.Mesh bumpy = BedMesh.interpolate(m, BedMesh.SMOOTH, 8);
        assertTrue("the bump's edge overshoots a little", bumpy.max() > m.max() && bumpy.max() < m.max() + 0.03);
        // Evenly spaced points: the curve is the classic Catmull-Rom one.
        assertEquals(0.5 * (2 * 2 + (-1 + 3) * 0.3 + (2 * 1 - 5 * 2 + 4 * 3 - 5) * 0.09 + (-1 + 3 * 2 - 3 * 3 + 5) * 0.027), BedMesh.curve(1, 2, 3, 5, 0, 1, 2, 3, 0.3), 1e-12);
        // A mesh with a point missing is not filled in (there is nothing honest to fill it with).
        BedMesh.Mesh holed = probed(); holed.z[5][5] = Double.NaN;
        assertSame(holed, BedMesh.interpolate(holed, BedMesh.SMOOTH, 4));
        // The model from a filled-in mesh is still a closed solid.
        byte[] stl = BedMesh.stl(BedMesh.interpolate(m, BedMesh.SMOOTH, 4), 100, 2);
        int n = java.nio.ByteBuffer.wrap(stl).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(80);
        assertEquals(2 * 40 * 40 * 2 + 4 * 40 * 2, n);
    }

    @Test public void aPartIsMovedToWhereTheBedIsFlattest() throws Exception {
        BedMesh.Mesh m = probed();   // tilted, with a 0.08 mm bump around the middle (X and Y 98 to 158)
        assertEquals(m.z[0][0], BedMesh.heightAt(m, 10, 10), 1e-9);
        assertEquals((m.z[0][0] + m.z[0][1]) / 2, BedMesh.heightAt(m, (m.xs[0] + m.xs[1]) / 2, 10), 1e-9);
        assertEquals("past the probed area, the edge's height", m.z[0][0], BedMesh.heightAt(m, 0, 0), 1e-9);
        double[] bed = {0, 0, 256, 256};
        // A 40 mm part on the bump's edge: moved off it, to a spot that varies far less.
        BedMesh.Spot s = BedMesh.flattest(m, 40, 40, 128, 100, bed, null);
        assertTrue(s.moved); assertTrue(s.rangeNow > 0.08); assertTrue("" + s.range, s.range < 0.03);
        assertTrue("inside the bed", s.x >= 20 && s.x <= 236 && s.y >= 20 && s.y <= 236);
        double[] there = BedMesh.under(m, new double[] {s.x - 20, s.y - 20, s.x + 20, s.y + 20});
        assertEquals(s.range, there[1] - there[0], 1e-9);
        // Another part already on that spot: the next flattest free spot, clear of it.
        java.util.List<double[]> taken = java.util.Collections.singletonList(new double[] {s.x - 20, s.y - 20, s.x + 20, s.y + 20});
        BedMesh.Spot second = BedMesh.flattest(m, 40, 40, 128, 100, bed, taken);
        assertTrue(second.moved && (Math.abs(second.x - s.x) >= 42 || Math.abs(second.y - s.y) >= 42));
        // Already as flat as anywhere (within 0.01 mm): it stays.
        BedMesh.Spot stay = BedMesh.flattest(m, 40, 40, s.x, s.y, bed, null);
        assertFalse(stay.moved); assertEquals(s.x, stay.x, 0); assertEquals(s.y, stay.y, 0);
        assertNull("a part larger than the bed", BedMesh.flattest(m, 300, 40, 128, 128, bed, null));
    }

    @Test public void theFirstLayerIsSetAgainstTheBed() throws Exception {
        BedMesh.Mesh m = probed();
        File dir = Files.createTempDirectory("first-layer").toFile();
        // A purge line at the front, then a 30 mm square: over the bump's edge, or on a flat corner.
        String gcode = String.join("\n", "M83", ";LAYER_CHANGE", ";Z:0.2", "G1 Z0.2", "G1 X10 Y2 F6000", "G1 X200 Y2 E10",
            "G1 X%1$d Y%2$d", "G1 X%3$d Y%2$d E2", "G1 X%3$d Y%4$d E2", "G1 X%1$d Y%4$d E2", "G1 X%1$d Y%2$d E2",
            ";LAYER_CHANGE", ";Z:0.4", "G1 Z0.4", "G1 X%3$d Y%2$d E2", "G1 X%3$d Y%4$d E2", "G1 X%1$d Y%4$d E2", "G1 X%1$d Y%2$d E2", "");
        File bump = new File(dir, "bump.gcode"), corner = new File(dir, "corner.gcode");
        Files.write(bump.toPath(), String.format(gcode, 80, 80, 110, 110).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Files.write(corner.toPath(), String.format(gcode, 20, 200, 50, 230).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        BedMesh.FirstLayer onBump = BedMesh.firstLayer(m, GcodeToolpath.read(bump));
        assertEquals("the purge line at Y 2 is left out", 80, onBump.box[1], 0.01); assertEquals(110, onBump.box[2], 0.01);
        assertEquals(0.2, onBump.layerHeight, 1e-6);
        assertTrue(onBump.max - onBump.min > 0.08);
        String words = BedMesh.firstLayerWords(m, onBump);
        assertTrue(words, words.startsWith("Under the first layer the bed varies 0.1") && words.contains("% of the 0.20 mm first layer") && words.contains("Move to the flattest spot"));
        String flat = BedMesh.firstLayerWords(m, BedMesh.firstLayer(m, GcodeToolpath.read(corner)));
        assertTrue(flat, flat.endsWith("flat enough that the first layer should go down evenly."));
    }
}
