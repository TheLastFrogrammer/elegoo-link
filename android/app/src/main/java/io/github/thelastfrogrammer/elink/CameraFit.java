package io.github.thelastfrogrammer.elink;

import java.util.*;

/**
 * Lines the viewer's camera up from points the user tapped along the bed's edges in the camera picture: finds the camera
 * (position, turn, tilt, view angle, lens curve, roll) whose drawing of the bed's outline passes through the taps, by damped
 * least squares (Levenberg–Marquardt) from the current line-up. The camera model matches the viewer page (viewer.js): a
 * pinhole camera looking along turn/tilt, rolled about its view axis, then the division-model lens curve measured against
 * the picture's half-diagonal. Independent of Android.
 */
final class CameraFit {
    /** Camera values, in GcodeViewerActivity's order. */
    static final int X = 0, Y = 1, Z = 2, TURN = 3, TILT = 4, VIEW = 5, LENS = 6, ROLL = 7, COUNT = 8;
    static final double BED = 256;
    static final String[] EDGES = {"Front edge", "Back edge", "Left edge", "Right edge"};

    /** A tap on the picture (u, v from its top-left, 0..1) said to lie on one of the bed's edges. */
    static final class Mark {
        final int edge; final double u, v;
        Mark(int edge, double u, double v) { this.edge = edge; this.u = u; this.v = v; }
    }

    /**
     * Taps made with the bed at height z. The camera is fixed to the printer's frame while the bed moves, so taps from several
     * bed heights describe one camera: everything shared, its height above the bed larger by exactly z.
     */
    static final class TapSet {
        final double z, aspect; final List<Mark> marks;
        TapSet(double z, double aspect, List<Mark> marks) { this.z = z; this.aspect = aspect; this.marks = new ArrayList<>(marks); }
    }
    /** The camera seen with the bed at z, from the camera with the bed at 0. */
    static double[] atBed(double[] base, double z) { double[] c = Arrays.copyOf(base, COUNT); c[Z] += z; return c; }

    /** Where a point of the bed's frame (mm, bed surface at z 0) shows in the picture, as {X, Y} with Y up, X in picture heights; null if out of view. */
    static double[] project(double[] c, double aspect, double px, double py, double pz) {
        double yaw = Math.toRadians(c[TURN]), pitch = Math.toRadians(c[TILT]);
        double fx = Math.cos(pitch) * Math.cos(yaw), fy = Math.cos(pitch) * Math.sin(yaw), fz = -Math.sin(pitch);
        double rl = Math.hypot(fy, fx); if (rl < 1e-9) return null;
        double rx = fy / rl, ry = -fx / rl, rz = 0;
        double ux = ry * fz - rz * fy, uy = rz * fx - rx * fz, uz = rx * fy - ry * fx;
        double dx = px - c[X], dy = py - c[Y], dz = pz - c[Z];
        double right = dx * rx + dy * ry + dz * rz, up = dx * ux + dy * uy + dz * uz, ahead = dx * fx + dy * fy + dz * fz;
        if (ahead < 1) return null;
        double focal = 1 / Math.tan(Math.toRadians(c[VIEW]) / 2);
        double sx = focal * right / ahead, sy = focal * up / ahead;
        double roll = Math.toRadians(c.length > ROLL ? c[ROLL] : 0), cos = Math.cos(roll), sin = Math.sin(roll);
        double qx = sx * cos - sy * sin, qy = sx * sin + sy * cos;
        double r2 = (qx * qx + qy * qy) / (aspect * aspect + 1), k = c[LENS];
        if (k * r2 > 1) return null;
        double scale = 1 / (1 + k * r2);
        return new double[] {qx * scale, qy * scale};
    }

    /** The edge as a line of 48 pieces in the picture (pieces out of view are left out). */
    static List<double[]> edgeLine(double[] c, double aspect, int edge) {
        List<double[]> points = new ArrayList<>();
        for (int i = 0; i <= 48; i++) {
            double t = BED * i / 48;
            double x = edge == 2 ? 0 : edge == 3 ? BED : t, y = edge == 0 ? 0 : edge == 1 ? BED : t;
            points.add(project(c, aspect, x, y, 0));
        }
        return points;
    }

    /** How far a tap is from its edge's drawn line, in picture heights (1 if the edge is out of view). */
    static double miss(double[] c, double aspect, Mark mark) {
        double tx = (2 * mark.u - 1) * aspect, ty = 1 - 2 * mark.v, best = Double.MAX_VALUE;
        List<double[]> line = edgeLine(c, aspect, mark.edge);
        for (int i = 0; i + 1 < line.size(); i++) {
            double[] a = line.get(i), b = line.get(i + 1);
            if (a == null || b == null) continue;
            double ex = b[0] - a[0], ey = b[1] - a[1], len2 = ex * ex + ey * ey;
            double t = len2 < 1e-12 ? 0 : Math.max(0, Math.min(1, ((tx - a[0]) * ex + (ty - a[1]) * ey) / len2));
            best = Math.min(best, Math.hypot(tx - a[0] - t * ex, ty - a[1] - t * ey));
        }
        return best == Double.MAX_VALUE ? 1 : best;
    }

    /** Root-mean-square miss over the taps, in picture heights. */
    static double rms(double[] c, double aspect, List<Mark> marks) {
        double sum = 0; for (Mark mark : marks) { double m = miss(c, aspect, mark); sum += m * m; }
        return marks.isEmpty() ? 0 : Math.sqrt(sum / marks.size());
    }
    /** Root-mean-square miss over taps from every bed height, for the camera `base` (bed at 0). */
    static double rms(double[] base, List<TapSet> sets) {
        double sum = 0; int n = 0;
        for (TapSet set : sets) for (Mark mark : set.marks) { double m = miss(atBed(base, set.z), set.aspect, mark); sum += m * m; n++; }
        return n == 0 ? 0 : Math.sqrt(sum / n);
    }

    static int[] free(List<Mark> marks) { return freeSets(Collections.singletonList(new TapSet(0, 1, marks))); }
    /** Which values the taps can pin down: all of them with eight or more taps on three or more edges, fewer otherwise. */
    static int[] freeSets(List<TapSet> sets) {
        List<Mark> marks = new ArrayList<>(); for (TapSet set : sets) marks.addAll(set.marks);
        Set<Integer> edges = new HashSet<>(); for (Mark mark : marks) edges.add(mark.edge);
        if (marks.size() >= 8 && edges.size() >= 3) return new int[] {X, Y, Z, TURN, TILT, VIEW, LENS, ROLL};
        if (marks.size() >= 6 && edges.size() >= 2) return new int[] {X, Y, Z, TURN, TILT, VIEW};
        if (marks.size() >= 3) return new int[] {TURN, TILT, ROLL};
        return new int[0];
    }

    private static final double[] STEP = {0.05, 0.05, 0.05, 0.005, 0.005, 0.005, 0.0002, 0.005};
    static final double[][] RANGE = {{-250, 510}, {-250, 510}, {-20, 400}, {-180, 180}, {-10, 90}, {15, 130}, {0, 1}, {-30, 30}};

    /** The line-up that best fits the taps, starting from `start`; values the taps cannot pin down stay as they are. */
    private static final double PRIOR = 0.002;
    /** How far each value may drift from where the user left it before that costs as much as a tap missing by `prior`. */
    private static final double[] SCALE = {20, 20, 20, 5, 5, 5, 0.1, 5};
    static double[] fit(double[] start, double aspect, List<Mark> marks) {
        return fit(start, Collections.singletonList(new TapSet(0, aspect, marks)));
    }
    /** The camera (bed at 0) that best fits taps from every bed height at once, starting from `start` (bed at 0). */
    static double[] fit(double[] start, List<TapSet> sets) {
        double[] c = Arrays.copyOf(start, COUNT);
        final double[] origin = c.clone();
        int[] free = freeSets(sets);
        if (free.length == 0) return c;
        int taps = 0; for (TapSet set : sets) taps += set.marks.size();
        int n = taps + free.length, m = free.length;
        double[] r = residuals(c, sets, origin, free);
        double cost = dot(r, r), lambda = 1e-3;
        for (int iteration = 0; iteration < 200; iteration++) {
            double[][] jac = new double[n][m];
            for (int j = 0; j < m; j++) {
                double h = STEP[free[j]];
                double[] plus = c.clone(), minus = c.clone(); plus[free[j]] += h; minus[free[j]] -= h;
                double[] rp = residuals(plus, sets, origin, free), rm = residuals(minus, sets, origin, free);
                for (int i = 0; i < n; i++) jac[i][j] = (rp[i] - rm[i]) / (2 * h);
            }
            double[][] a = new double[m][m]; double[] g = new double[m];
            for (int j = 0; j < m; j++) {
                for (int i = 0; i < n; i++) g[j] -= jac[i][j] * r[i];
                for (int k = 0; k < m; k++) for (int i = 0; i < n; i++) a[j][k] += jac[i][j] * jac[i][k];
            }
            boolean improved = false;
            for (int attempt = 0; attempt < 12 && !improved; attempt++) {
                double[][] damped = new double[m][];
                for (int j = 0; j < m; j++) { damped[j] = a[j].clone(); damped[j][j] += lambda * Math.max(a[j][j], 1e-9); }
                double[] step = solve(damped, g.clone());
                if (step == null) { lambda *= 10; continue; }
                double[] next = c.clone();
                for (int j = 0; j < m; j++) next[free[j]] = clamp(free[j], next[free[j]] + step[j]);
                double[] rn = residuals(next, sets, origin, free);
                double nextCost = dot(rn, rn);
                if (nextCost < cost) {
                    boolean small = cost - nextCost < 1e-12 * Math.max(1, cost);
                    c = next; r = rn; cost = nextCost; lambda = Math.max(1e-9, lambda / 3); improved = true;
                    if (small) return c;
                } else lambda *= 4;
            }
            if (!improved) break;
        }
        return c;
    }

    private static double clamp(int index, double value) { return Math.max(RANGE[index][0], Math.min(RANGE[index][1], value)); }
    /** The taps' misses, then a small pull of each free value toward where it started (keeps what the taps cannot tell apart). */
    private static double[] residuals(double[] c, List<TapSet> sets, double[] origin, int[] free) {
        int taps = 0; for (TapSet set : sets) taps += set.marks.size();
        double[] r = new double[taps + free.length];
        int i = 0;
        for (TapSet set : sets) { double[] seen = atBed(c, set.z); for (Mark mark : set.marks) r[i++] = miss(seen, set.aspect, mark); }
        for (int j = 0; j < free.length; j++) r[taps + j] = PRIOR * (c[free[j]] - origin[free[j]]) / SCALE[free[j]];
        return r;
    }
    private static double dot(double[] a, double[] b) { double s = 0; for (int i = 0; i < a.length; i++) s += a[i] * b[i]; return s; }
    /** Gaussian elimination with partial pivoting; null if singular. */
    private static double[] solve(double[][] a, double[] b) {
        int n = b.length;
        for (int col = 0; col < n; col++) {
            int pivot = col; for (int row = col + 1; row < n; row++) if (Math.abs(a[row][col]) > Math.abs(a[pivot][col])) pivot = row;
            if (Math.abs(a[pivot][col]) < 1e-14) return null;
            double[] t = a[col]; a[col] = a[pivot]; a[pivot] = t; double tb = b[col]; b[col] = b[pivot]; b[pivot] = tb;
            for (int row = col + 1; row < n; row++) {
                double f = a[row][col] / a[col][col];
                for (int k = col; k < n; k++) a[row][k] -= f * a[col][k];
                b[row] -= f * b[col];
            }
        }
        double[] x = new double[n];
        for (int row = n - 1; row >= 0; row--) {
            double s = b[row]; for (int k = row + 1; k < n; k++) s -= a[row][k] * x[k];
            x[row] = s / a[row][row];
        }
        return x;
    }
}
