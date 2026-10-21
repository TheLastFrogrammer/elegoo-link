package io.github.thelastfrogrammer.elink;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The guardian's second look, needing no G-code, no line-up and no filament colour: each picture against the ones before
 * it. A print that goes well changes in one place that grows slowly (its top). A mess (spaghetti, a blob dragged about)
 * shows as changes spreading across the bed, away from that place; a part knocked loose or moved shows as a large part of
 * the picture changing at once and staying changed (a flicker of the light changes back). Pictures must show the bed the
 * same way each time with the head out of the way: parked pictures (smooth timelapse), or any picture with the head masked
 * out by a lined-up camera. It only looks and tells. Independent of Android.
 */
final class PictureChange {
    static final int W = PrintGuardian.W, H = PrintGuardian.H, N = W * H;
    /** A cell counts as changed past this difference in brightness (0-255), after evening out the whole picture's brightness. */
    static final int THRESHOLD = 24;
    /** Pictures that set what is normal before anything is judged. */
    static final int LEARN = 4;

    /** Brightness (0-255) of each cell of an RGB grid. */
    static int[] luminance(int[] rgb) {
        int[] out = new int[rgb.length];
        for (int i = 0; i < rgb.length; i++) { int p = rgb[i]; out[i] = (299 * ((p >> 16) & 255) + 587 * ((p >> 8) & 255) + 114 * (p & 255)) / 1000; }
        return out;
    }

    /** A 3 × 3 average: camera noise and JPEG blocks are not changes. */
    static int[] blur(int[] g) {
        int[] out = new int[N];
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            int sum = 0, n = 0;
            for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                int xx = x + dx, yy = y + dy; if (xx < 0 || yy < 0 || xx >= W || yy >= H) continue;
                sum += g[yy * W + xx]; n++;
            }
            out[y * W + x] = sum / n;
        }
        return out;
    }

    /** Cells that changed from `before` to `after`, with `after`'s brightness first matched to `before`'s; `ignore` (or null) is left out. */
    static boolean[] changed(int[] before, int[] after, boolean[] ignore) {
        double a = 0, b = 0; int n = 0;
        for (int i = 0; i < N; i++) if (ignore == null || !ignore[i]) { a += after[i]; b += before[i]; n++; }
        double gain = n == 0 || a <= 0 ? 1 : Math.max(0.5, Math.min(2, b / a));
        boolean[] out = new boolean[N];
        for (int i = 0; i < N; i++) out[i] = (ignore == null || !ignore[i]) && Math.abs(after[i] * gain - before[i]) > THRESHOLD;
        return out;
    }
    static int count(boolean[] mask) { int n = 0; for (boolean b : mask) if (b) n++; return n; }

    /** One print's run of pictures from one camera. */
    static final class Watch {
        private int[] previous, beforeJump, reference;
        private final boolean[] region = new boolean[N];
        private final List<Double> learning = new ArrayList<>();
        private double noise = -1, tolerance = 1, jumpShare;
        private int spreadRun, jumpRun;
        boolean alarmed;
        String reason = "";
        /** The last picture's changed cells, for the overlay. */
        boolean[] last = new boolean[N];
        /** Where the print has been changing so far (the overlay shows it). */
        boolean[] region() { return region; }

        /** One picture's brightness grid; `ignore`: cells to leave out (the head), or null. True the moment it becomes an alarm. */
        boolean add(int[] gray, boolean[] ignore) {
            int[] now = blur(gray);
            if (previous == null) { previous = now; reason = "first picture"; return false; }
            boolean[] d = changed(previous, now, ignore); last = d;
            double share = count(d) / (double) N;
            if (noise < 0) {
                learning.add(share); or(region, d); previous = now;
                if (learning.size() < LEARN) { reason = "learning what is normal (" + learning.size() + " of " + LEARN + ")"; return false; }
                List<Double> sorted = new ArrayList<>(learning); Collections.sort(sorted);
                noise = Math.max(0.002, sorted.get(sorted.size() / 2));
                reference = now;
                reason = "looks as expected"; return false;
            }
            double big = Math.max(0.12, 6 * noise) * tolerance;
            // A large change waits for the next picture: if the picture goes back, it was the light or a passing shadow.
            if (beforeJump != null) {
                double kept = count(changed(beforeJump, now, ignore)) / (double) N;
                if (kept > jumpShare * 0.6 && share < jumpShare * 0.5) jumpRun++;
                else { beforeJump = null; jumpRun = 0; }
            } else if (share > big) {
                beforeJump = previous; jumpShare = share; jumpRun = 0; previous = now;
                reason = "a large change; waiting for the next picture to see if it stays"; return false;
            }
            boolean jumped = jumpRun >= 2;
            // Spreading: cells well away from where the print has been changing that now differ from the reference picture
            // (a mess stays; camera noise does not). The print's own place grows only by changes right beside it.
            boolean[] near = PrintGuardian.grow(region, 3);
            boolean[] since = changed(reference, now, ignore);
            int away = 0; for (int i = 0; i < N; i++) if (since[i] && !near[i]) away++;
            double spread = away / (double) N;
            boolean spreadBad = beforeJump == null && spread > Math.max(0.015, 4 * noise) * tolerance;
            spreadRun = spreadBad ? spreadRun + 1 : 0;
            boolean spreading = spreadRun >= 3;
            boolean[] beside = PrintGuardian.grow(region, 1);
            for (int i = 0; i < N; i++) if (d[i] && beside[i]) region[i] = true;
            reason = jumped ? "a large part of the picture changed at once and stayed changed (" + percent(jumpShare) + "): the print may have come loose or been moved"
                : spreading ? "changes spreading across the bed away from the print (" + percent(spread) + " of the picture, normal " + percent(noise) + ")"
                : spreadBad ? "some change away from the print (" + spreadRun + " of 3)" : "looks as expected";
            previous = now;
            boolean bad = jumped || spreading;
            if (jumped) { beforeJump = null; jumpRun = 0; reference = now; }   // after a move, what is there now is the new normal to compare with
            if (bad && !alarmed) { alarmed = true; return true; }
            if (!bad) alarmed = false;
            return false;
        }
        /** The person said the last alarm was not a problem: allow 50 % more before the next, and learn what is there now. */
        void looksFine() { tolerance *= 1.5; spreadRun = 0; jumpRun = 0; beforeJump = null; alarmed = false; if (previous != null) reference = previous; }
        private static void or(boolean[] into, boolean[] add) { for (int i = 0; i < N; i++) into[i] |= add[i]; }
        private static String percent(double v) { return Math.round(v * 1000) / 10.0 + " %"; }
    }

    private PictureChange() { }
}
