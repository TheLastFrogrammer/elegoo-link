package io.github.thelastfrogrammer.elink;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * The self-test print: a small square block, sliced on the phone with every measured feature on (smooth timelapse with the
 * comparison set, far pictures from the G-code), so one short print gives every check in Field check its evidence. The app
 * only sets it up on the Slice screen; slicing, and starting the print, stay the person's own taps.
 */
final class SelfTest {
    /** The model's file name: recordings of the self-test print are known by it. */
    static final String NAME = "link_selftest";
    /** The block (mm): small enough for about ten minutes, with enough layers and area for every check. */
    static final double WIDTH = 25, DEPTH = 25, HEIGHT = 4;
    /** Infill for the block (%): sparse, for a quick print. */
    static final int INFILL = 15;

    /**
     * The position probe: on one layer the head lifts as for the park, stops at two known points (well apart on both axes)
     * and returns. Where the printer then reports the head, against where the G-code sent it, shows whether its positions are
     * in the G-code's frame, shifted, or mirrored. Standard G-code only, as the park.
     */
    static final double[][] PROBES = {{30, 30}, {220, 220}};
    static final int PROBE_LAYER = 3, PROBE_WAIT_MS = 3000;
    /** How close a reported stop must be to a probe point to count as it (mm). */
    static final double PROBE_NEAR = 1.0;
    static String probeGcode() {
        StringBuilder g = new StringBuilder("{if layer_num == " + PROBE_LAYER + "}\n; Link Workshop self-test: position probe\n")
            .append("SAVE_GCODE_STATE NAME=link_probe\nG91\nG1 Z0.6 F900\nG90\n");
        for (double[] p : PROBES) g.append(String.format(Locale.ROOT, "G1 X%.1f Y%.1f F18000\nM400\nG4 P%d\n", p[0], p[1], PROBE_WAIT_MS));
        return g.append("RESTORE_GCODE_STATE NAME=link_probe MOVE=1 MOVE_SPEED=250\n{endif}").toString();
    }
    /** Whether a reported position is at one of the probe points (the picture logic leaves those moments alone). */
    static boolean atProbe(double x, double y) {
        for (double[] p : PROBES) if (Math.abs(x - p[0]) <= PROBE_NEAR && Math.abs(y - p[1]) <= PROBE_NEAR) return true;
        return false;
    }

    /** Whether a printed file name is the self-test print's. */
    static boolean is(String file) { return file != null && file.replaceFirst("^.*/", "").toLowerCase(Locale.ROOT).startsWith(NAME); }

    /** The block as an STL in `folder` (written again each time, so it is always the current one). */
    static File model(File folder) throws IOException {
        folder.mkdirs();
        File file = new File(folder, NAME + ".stl");
        java.nio.file.Files.write(file.toPath(), stl(WIDTH, DEPTH, HEIGHT).getBytes(StandardCharsets.US_ASCII));
        return file;
    }

    /** A closed box as ASCII STL, outward normals. Pure, for tests. */
    static String stl(double x, double y, double z) {
        double[][] v = {{0, 0, 0}, {x, 0, 0}, {x, y, 0}, {0, y, 0}, {0, 0, z}, {x, 0, z}, {x, y, z}, {0, y, z}};
        int[][] faces = {{0, 2, 1}, {0, 3, 2}, {4, 5, 6}, {4, 6, 7}, {0, 1, 5}, {0, 5, 4}, {1, 2, 6}, {1, 6, 5}, {2, 3, 7}, {2, 7, 6}, {3, 0, 4}, {3, 4, 7}};
        StringBuilder stl = new StringBuilder("solid " + NAME + "\n");
        for (int[] f : faces) {
            stl.append("facet normal 0 0 0\nouter loop\n");
            for (int i : f) stl.append(String.format(Locale.ROOT, "vertex %.3f %.3f %.3f\n", v[i][0], v[i][1], v[i][2]));
            stl.append("endloop\nendfacet\n");
        }
        return stl.append("endsolid ").append(NAME).append('\n').toString();
    }

    /** What it is, for the Field check page and the Slice screen. */
    static final String ABOUT = "A " + (int) WIDTH + " × " + (int) DEPTH + " × " + (int) HEIGHT + " mm block, about ten minutes and 3 g of filament, sliced on this phone "
        + "with Smooth timelapse, the comparison set and a position probe (on one layer the head stops at two known points). Choose the filament you have loaded, tap Slice, then Upload and print. "
        + "Keep the phone on your network with the app open or in the background until it finishes: every measured check below gets its answer from this one print.";

    private SelfTest() { }
}
