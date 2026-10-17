package io.github.thelastfrogrammer.elink;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Print guardian (beta): compares a camera picture with what the print should look like at this layer. The toolpath printed
 * so far is projected through the lined-up camera (CameraFit, the same model as the 3D view) onto a small grid; pixels that
 * look like the filament (by colour) are found in the picture. Filament where nothing was printed (spaghetti, a blob, a part
 * dragged off) and printed areas that have lost their filament (a part knocked loose) are measured; Watch turns a run of bad
 * checks into one alarm. It only looks: what to do about an alarm is up to the person. Independent of Android.
 */
final class PrintGuardian {
    /** The grid pictures are scaled to before comparing (16:9). */
    static final int W = 160, H = 90;

    static final class Result {
        /** Share of the expected print area that looks like filament, and share of the visible bed (outside the print and the head) that does. */
        double coverage, outside;
        int expectedPixels, bedPixels;
        boolean[] expected, bed, material, head;
        String note = "";
        /** No usable colour: every filament colour looks like the bed, so filament cannot be told from it. */
        boolean undecided;
    }

    /** Grid cell of a projected point, or -1 when it falls outside the picture. */
    static int cell(double[] projected, double aspect) {
        if (projected == null) return -1;
        int px = (int) Math.floor((projected[0] / aspect + 1) / 2 * W), py = (int) Math.floor((1 - projected[1]) / 2 * H);
        return px < 0 || py < 0 || px >= W || py >= H ? -1 : py * W + px;
    }

    /** Where the print should show: every extrusion up to `layer` (inclusive), projected, then widened by `grow` cells. */
    static boolean[] expectedMask(GcodeToolpath path, int layer, double[] camera, double aspect, int grow) {
        boolean[] mask = new boolean[W * H];
        int end = path.layerEnd(Math.max(0, Math.min(layer, path.layerCount - 1)));
        int stride = Math.max(1, end / 150_000);   // a picture's grid needs far fewer points than a big print has segments
        for (int i = 0; i < end; i += stride) {
            double dx = path.x1[i] - path.x0[i], dy = path.y1[i] - path.y0[i], dz = path.z1[i] - path.z0[i];
            int steps = Math.max(1, Math.min(20, (int) (Math.sqrt(dx * dx + dy * dy + dz * dz) / 3)));
            for (int s = 0; s <= steps; s++) {
                double t = s / (double) steps;
                int c = cell(CameraFit.project(camera, aspect, path.x0[i] + dx * t, path.y0[i] + dy * t, path.z0[i] + dz * t), aspect);
                if (c >= 0) mask[c] = true;
            }
        }
        return grow(mask, grow);
    }

    /** The bed's surface (0..256 mm square at height 0) as seen by the camera. */
    static boolean[] bedMask(double[] camera, double aspect) {
        boolean[] mask = new boolean[W * H];
        for (double x = 0; x <= 256; x += 2) for (double y = 0; y <= 256; y += 2) {
            int c = cell(CameraFit.project(camera, aspect, x, y, 0), aspect);
            if (c >= 0) mask[c] = true;
        }
        return grow(mask, 1);
    }

    /** The print head around the nozzle (x, y, z in bed mm): a box about 70 mm wide and 70 mm tall, widened. */
    static boolean[] headMask(double[] camera, double aspect, double x, double y, double z) {
        boolean[] mask = new boolean[W * H];
        if (Double.isNaN(x) || Double.isNaN(y) || Double.isNaN(z)) return mask;
        for (double dx = -35; dx <= 35; dx += 5) for (double dy = -35; dy <= 35; dy += 5) for (double dz = 0; dz <= 70; dz += 5) {
            int c = cell(CameraFit.project(camera, aspect, x + dx, y + dy, z + dz), aspect);
            if (c >= 0) mask[c] = true;
        }
        return grow(mask, 3);
    }

    /** Pixels whose colour looks like the filament's (hue for coloured filament, brightness for white, grey and black). */
    static boolean[] material(int[] rgb, int filament) {
        float[] f = hsv(filament); boolean coloured = f[1] > 0.25f && f[2] > 0.15f;
        boolean[] out = new boolean[rgb.length];
        for (int i = 0; i < rgb.length; i++) {
            float[] p = hsv(rgb[i]);
            if (coloured) {
                float dh = Math.abs(p[0] - f[0]); dh = Math.min(dh, 360 - dh);
                out[i] = dh < 22 && p[1] > Math.max(0.2f, f[1] * 0.45f) && p[2] > 0.12f;
            } else out[i] = p[1] < 0.3f && Math.abs(p[2] - f[2]) < 0.18f;
        }
        return out;
    }

    static Result analyse(int[] rgb, int filament, boolean[] expected, boolean[] bed, boolean[] head) { return analyse(rgb, new int[] {filament}, expected, bed, head); }
    /**
     * As above with every filament colour printed so far: filament is any pixel that looks like one of them, so the parts in
     * an earlier colour still count after a colour change. A colour that matches most of the bare bed cannot be told from it
     * and is left out; with none left the result is undecided.
     */
    static Result analyse(int[] rgb, int[] filaments, boolean[] expected, boolean[] bed, boolean[] head) {
        Result r = new Result(); r.expected = expected; r.bed = bed; r.head = head;
        r.material = new boolean[rgb.length]; int usable = 0;
        for (int filament : filaments) {
            boolean[] m = material(rgb, filament); int bare = 0, matched = 0;
            for (int i = 0; i < rgb.length; i++) if (bed[i] && !expected[i] && !head[i]) { bare++; if (m[i]) matched++; }
            if (bare > 0 && matched > bare / 2) continue;
            usable++; for (int i = 0; i < rgb.length; i++) r.material[i] |= m[i];
        }
        r.undecided = usable == 0;
        int covered = 0, outsideMaterial = 0;
        for (int i = 0; i < rgb.length; i++) {
            if (head[i]) continue;
            if (expected[i]) { r.expectedPixels++; if (r.material[i]) covered++; }
            else if (bed[i]) { r.bedPixels++; if (r.material[i]) outsideMaterial++; }
        }
        r.coverage = r.expectedPixels == 0 ? 0 : covered / (double) r.expectedPixels;
        r.outside = r.bedPixels == 0 ? 0 : outsideMaterial / (double) r.bedPixels;
        return r;
    }

    /**
     * One print's run of checks. The first three set what is normal for this camera, light and bed; an alarm needs three bad
     * checks in a row: much more filament-coloured area off the print than normal, or most of the print's area gone after it
     * was clearly there. "Looks fine" from the person raises the bar for the rest of the print.
     */
    static final class Watch {
        private final List<Double> start = new ArrayList<>();
        private double baseline = -1, bestCoverage, tolerance = 1;
        private int badOutside, badCoverage; boolean alarmed;
        String reason = "";
        /** Feeds one check; true the moment it becomes an alarm (once per run of bad checks). */
        boolean add(Result r, int layer) {
            if (r.expectedPixels < 30 || r.bedPixels < 100) { reason = "too little of the print or bed in view to judge"; return false; }
            if (baseline < 0) { start.add(r.outside); if (start.size() < 3) { reason = "learning what is normal (" + start.size() + " of 3)"; return false; }
                List<Double> sorted = new ArrayList<>(start); Collections.sort(sorted); baseline = Math.max(0.002, sorted.get(1)); }
            bestCoverage = Math.max(bestCoverage, r.coverage);
            boolean outsideBad = r.outside > Math.max(0.03, 3 * baseline) * tolerance;
            boolean coverageBad = layer >= 5 && bestCoverage > 0.25 && r.coverage < bestCoverage * 0.4 / tolerance;
            badOutside = outsideBad ? badOutside + 1 : 0; badCoverage = coverageBad ? badCoverage + 1 : 0;
            reason = outsideBad ? "filament-coloured area off the print: " + percent(r.outside) + " of the bed (normal " + percent(baseline) + ")"
                : coverageBad ? "the print's area shows " + percent(r.coverage) + " filament (was " + percent(bestCoverage) + ")" : "looks as expected";
            boolean bad = badOutside >= 3 || badCoverage >= 3;
            if (bad && !alarmed) { alarmed = true; return true; }
            if (!bad) alarmed = false;
            return false;
        }
        /** The person said the last alarm was not a problem: allow 50 % more before the next. */
        void looksFine() { tolerance *= 1.5; badOutside = 0; badCoverage = 0; alarmed = false; }
        private static String percent(double v) { return Math.round(v * 1000) / 10.0 + " %"; }
    }

    static boolean[] grow(boolean[] mask, int by) {
        boolean[] out = mask.clone();
        for (int k = 0; k < by; k++) {
            boolean[] in = out.clone();
            for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
                if (in[y * W + x]) continue;
                if (x > 0 && in[y * W + x - 1] || x < W - 1 && in[y * W + x + 1] || y > 0 && in[(y - 1) * W + x] || y < H - 1 && in[(y + 1) * W + x]) out[y * W + x] = true;
            }
        }
        return out;
    }
    static float[] hsv(int rgb) {
        float r = ((rgb >> 16) & 255) / 255f, g = ((rgb >> 8) & 255) / 255f, b = (rgb & 255) / 255f;
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b)), d = max - min, h;
        if (d < 1e-6f) h = 0; else if (max == r) h = 60 * (((g - b) / d) % 6); else if (max == g) h = 60 * ((b - r) / d + 2); else h = 60 * ((r - g) / d + 4);
        if (h < 0) h += 360;
        return new float[] {h, max == 0 ? 0 : d / max, max};
    }
}
