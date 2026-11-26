package io.github.thelastfrogrammer.elink;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The bed's shape, as the printer probes it while it levels the bed. The stock firmware does not show its mesh, but its
 * status stream reports the head's position as it probes; the probe points are rebuilt from that (the method of Sixth49's
 * cc2-bed-mesh-sniffer and Human Automatica's cc2-bed-mesh, both MIT: see THIRD_PARTY_NOTICES.md). Only listens: nothing
 * is sent to the printer, and the person (or a print's own start) starts the levelling. A reconstruction, not the mesh the
 * printer stores. Independent of Android.
 */
final class BedMesh {
    /** A reported Z this close to 0 is a probe touching the bed; farther is the head moving between points. */
    static final double CONTACT_MM = 1.5;
    static final double PLATE_MM = 256;
    static final int KEEP = 200, MAX_EVENTS = 20_000;
    /** The printer's steps while it probes (levelling inside a print's start, a self-check or Auto-level). */
    static final int PROBING = 2901, LEVELLED = 2902;
    static boolean probing(JSONObject status) {
        JSONObject machine = status == null ? null : status.optJSONObject("machine_status");
        return machine != null && machine.optInt("sub_status", -1) == PROBING;
    }

    /** One probed shape: the grid's X and Y (mm), Z per row (mm, rows by Y), and what was known when it was kept. */
    static final class Mesh {
        double[] xs = new double[0], ys = new double[0];
        double[][] z = new double[0][0];
        long at; String source = ""; double bed = Double.NaN, bedTarget = Double.NaN; String file = ""; String printer = "";
        int phase; int expected;
        String name = "";   // the file it is kept in
        /** For a filled-in mesh (interpolate): the mesh as probed, and how many steps lie between two probe points; null and 1 otherwise. */
        Mesh probed; int detail = 1;
        /** Probe repeatability: the largest spread (mm) between readings of a point the printer probed more than once, and how many such points; NaN and 0 when none was. */
        double repeatSpread = Double.NaN; int repeated;

        int count() { int n = 0; for (double[] row : z) for (double v : row) if (!Double.isNaN(v)) n++; return n; }
        double min() { double m = Double.POSITIVE_INFINITY; for (double[] r : z) for (double v : r) if (!Double.isNaN(v)) m = Math.min(m, v); return m; }
        double max() { double m = Double.NEGATIVE_INFINITY; for (double[] r : z) for (double v : r) if (!Double.isNaN(v)) m = Math.max(m, v); return m; }
        double range() { return max() - min(); }
        double mean() { double s = 0; int n = 0; for (double[] r : z) for (double v : r) if (!Double.isNaN(v)) { s += v; n++; } return n == 0 ? Double.NaN : s / n; }
        /** Where the lowest or highest point is: {x, y}. */
        double[] at(boolean highest) {
            double best = highest ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY; double[] where = {Double.NaN, Double.NaN};
            for (int j = 0; j < ys.length; j++) for (int i = 0; i < xs.length; i++) {
                double v = z[j][i]; if (Double.isNaN(v)) continue;
                if (highest ? v > best : v < best) { best = v; where = new double[] {xs[i], ys[j]}; }
            }
            return where;
        }
        boolean sameGrid(Mesh other) { return other != null && Arrays.equals(xs, other.xs) && Arrays.equals(ys, other.ys); }
    }

    /** Fits the plane z = a + b·x + c·y (least squares): {a, b, c}, or null with fewer than three points. Pure. */
    static double[] plane(Mesh m) {
        double n = 0, sx = 0, sy = 0, sz = 0, sxx = 0, syy = 0, sxy = 0, sxz = 0, syz = 0;
        for (int j = 0; j < m.ys.length; j++) for (int i = 0; i < m.xs.length; i++) {
            double v = m.z[j][i]; if (Double.isNaN(v)) continue;
            double x = m.xs[i], y = m.ys[j];
            n++; sx += x; sy += y; sz += v; sxx += x * x; syy += y * y; sxy += x * y; sxz += x * v; syz += y * v;
        }
        if (n < 3) return null;
        // The normal equations, solved by Cramer's rule (3×3).
        double[][] a = {{n, sx, sy}, {sx, sxx, sxy}, {sy, sxy, syy}}; double[] r = {sz, sxz, syz};
        double d = det(a); if (Math.abs(d) < 1e-12) return null;
        double[] out = new double[3];
        for (int k = 0; k < 3; k++) { double[][] c = {a[0].clone(), a[1].clone(), a[2].clone()}; for (int row = 0; row < 3; row++) c[row][k] = r[row]; out[k] = det(c) / d; }
        return out;
    }
    private static double det(double[][] m) {
        return m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1]) - m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0]) + m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0]);
    }
    /** The spread of the points once the fitted tilt is taken away (mm): how far from flat the bed itself is. */
    static double residualRange(Mesh m, double[] p) {
        double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
        for (int j = 0; j < m.ys.length; j++) for (int i = 0; i < m.xs.length; i++) {
            double v = m.z[j][i]; if (Double.isNaN(v)) continue;
            double r = v - (p[0] + p[1] * m.xs[i] + p[2] * m.ys[j]); lo = Math.min(lo, r); hi = Math.max(hi, r);
        }
        return hi - lo;
    }

    /** The mesh in a few plain lines: range, where the lowest and highest points are, the tilt and the shape without it. Pure. */
    static List<String> describe(Mesh m) {
        List<String> out = new ArrayList<>();
        if (m.count() == 0) return out;
        double[] lo = m.at(false), hi = m.at(true);
        out.add(String.format(Locale.getDefault(), "%d points · from %s to %s mm: a range of %.2f mm", m.count(), signed(m.min()), signed(m.max()), m.range()));
        out.add(String.format(Locale.getDefault(), "Lowest at X %.0f, Y %.0f; highest at X %.0f, Y %.0f (mm, as the printer numbers them).", lo[0], lo[1], hi[0], hi[1]));
        double[] p = plane(m);
        if (p != null) {
            double spanX = m.xs[m.xs.length - 1] - m.xs[0], spanY = m.ys[m.ys.length - 1] - m.ys[0];
            out.add(String.format(Locale.getDefault(), "Tilt: %.2f mm across X, %.2f mm across Y. Without the tilt, the bed's surface spans %.2f mm.",
                Math.abs(p[1] * spanX), Math.abs(p[2] * spanY), residualRange(m, p)));
        }
        return out;
    }
    static String signed(double v) { return String.format(Locale.getDefault(), "%+.2f", v); }

    /** b − a per point, on the same grid; null when the grids differ. Pure. */
    static Mesh difference(Mesh older, Mesh newer) {
        if (older == null || newer == null || !older.sameGrid(newer)) return null;
        Mesh d = new Mesh(); d.xs = newer.xs.clone(); d.ys = newer.ys.clone(); d.z = new double[newer.ys.length][newer.xs.length];
        for (int j = 0; j < d.ys.length; j++) for (int i = 0; i < d.xs.length; i++) d.z[j][i] = newer.z[j][i] - older.z[j][i];
        d.at = newer.at; d.source = "difference";
        return d;
    }
    /** "Changed by up to 0.05 mm since 3 Nov 14:02 (on average +0.01 mm)". Pure. */
    static String changeWords(Mesh older, Mesh difference) {
        double big = 0; for (double[] r : difference.z) for (double v : r) if (!Double.isNaN(v)) big = Math.max(big, Math.abs(v));
        return String.format(Locale.getDefault(), "Changed by up to %.2f mm since %s (on average %s mm). Small changes of a few hundredths are normal from one levelling to the next.",
            big, new SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(new Date(older.at)), signed(difference.mean()));
    }

    // ------------------------------------------------------------------ between the probe points

    /** How the surface is filled in between probe points: one flat tile per point, straight lines, or a smooth curve. */
    static final String PROBED = "probed", LINEAR = "linear", SMOOTH = "smooth";
    static final int[] DETAILS = {2, 4, 8};
    /**
     * The mesh filled in between its probe points, `detail` steps from one point to the next: LINEAR is bilinear (it never
     * goes past the measured heights); SMOOTH is a bicubic (Catmull-Rom) surface through every point (it can bulge a little
     * past them between points, as Klipper's bicubic mesh can). PROBED, or a mesh with a missing point, is returned as it is.
     * Values at the probe points are the measured ones. Pure.
     */
    static Mesh interpolate(Mesh m, String how, int detail) {
        if (m == null || PROBED.equals(how) || detail < 2 || m.xs.length < 2 || m.ys.length < 2 || m.count() != m.xs.length * m.ys.length) return m;
        boolean smooth = SMOOTH.equals(how);
        int nx = (m.xs.length - 1) * detail + 1, ny = (m.ys.length - 1) * detail + 1;
        Mesh out = new Mesh();
        out.xs = new double[nx]; out.ys = new double[ny]; out.z = new double[ny][nx];
        for (int i = 0; i < nx; i++) { int a = Math.min(i / detail, m.xs.length - 2); double t = (i - a * detail) / (double) detail; out.xs[i] = m.xs[a] + (m.xs[a + 1] - m.xs[a]) * t; }
        for (int j = 0; j < ny; j++) { int a = Math.min(j / detail, m.ys.length - 2); double t = (j - a * detail) / (double) detail; out.ys[j] = m.ys[a] + (m.ys[a + 1] - m.ys[a]) * t; }
        for (int j = 0; j < ny; j++) {
            int b = Math.min(j / detail, m.ys.length - 2); double v = (j - b * detail) / (double) detail;
            for (int i = 0; i < nx; i++) {
                int a = Math.min(i / detail, m.xs.length - 2); double u = (i - a * detail) / (double) detail;
                if (!smooth) {
                    double top = m.z[b][a] + (m.z[b][a + 1] - m.z[b][a]) * u, bottom = m.z[b + 1][a] + (m.z[b + 1][a + 1] - m.z[b + 1][a]) * u;
                    out.z[j][i] = top + (bottom - top) * v;
                } else {
                    double[] rows = new double[4];
                    for (int r = 0; r < 4; r++) rows[r] = curve(at(m, b - 1 + r, a - 1), at(m, b - 1 + r, a), at(m, b - 1 + r, a + 1), at(m, b - 1 + r, a + 2),
                        pos(m.xs, a - 1), pos(m.xs, a), pos(m.xs, a + 1), pos(m.xs, a + 2), u);
                    out.z[j][i] = curve(rows[0], rows[1], rows[2], rows[3], pos(m.ys, b - 1), pos(m.ys, b), pos(m.ys, b + 1), pos(m.ys, b + 2), v);
                }
            }
        }
        out.at = m.at; out.source = m.source; out.bed = m.bed; out.bedTarget = m.bedTarget; out.file = m.file; out.printer = m.printer; out.phase = m.phase;
        out.expected = nx * ny; out.name = m.name; out.probed = m; out.detail = detail;
        return out;
    }
    /** A grid value; past an edge, carried on in a straight line from the last two points, so the edge gets no false flattening. */
    private static double at(Mesh m, int j, int i) {
        int nx = m.xs.length, ny = m.ys.length;
        if (i < 0) return 2 * at(m, j, 0) - at(m, j, 1);
        if (i >= nx) return 2 * at(m, j, nx - 1) - at(m, j, nx - 2);
        if (j < 0) return 2 * at(m, 0, i) - at(m, 1, i);
        if (j >= ny) return 2 * at(m, ny - 1, i) - at(m, ny - 2, i);
        return m.z[j][i];
    }
    /** A probe line's position, carried on evenly past either end (for the extra points `at` makes up there). */
    private static double pos(double[] v, int k) { return k < 0 ? 2 * v[0] - v[1] : k >= v.length ? 2 * v[v.length - 1] - v[v.length - 2] : v[k]; }
    /**
     * The curve through p1 (at x1) and p2 (at x2), its slopes there from the points either side (p0, p3) at their real
     * positions, at t from 0 (p1) to 1 (p2): Catmull-Rom when the points are evenly spaced, and exact for a tilted plane when
     * they are not (the probe positions are whole millimetres, 23 or 24 mm apart).
     */
    static double curve(double p0, double p1, double p2, double p3, double x0, double x1, double x2, double x3, double t) {
        double h = x2 - x1, m1 = h * (p2 - p0) / (x2 - x0), m2 = h * (p3 - p1) / (x3 - x1);
        double t2 = t * t, t3 = t2 * t;
        return (2 * t3 - 3 * t2 + 1) * p1 + (t3 - 2 * t2 + t) * m1 + (-2 * t3 + 3 * t2) * p2 + (t3 - t2) * m2;
    }
    /** Whether a filled-in mesh's sample (i, j) is one of the probe points. */
    static boolean probePoint(Mesh m, int i, int j) { return m.probed == null || i % m.detail == 0 && j % m.detail == 0; }

    // ------------------------------------------------------------------ the bed under a part

    /** The bed's height at a spot (mm), straight-lined between the four probe points around it; past the probed area, the edge's. Pure. */
    static double heightAt(Mesh m, double x, double y) {
        int nx = m.xs.length, ny = m.ys.length;
        x = Math.max(m.xs[0], Math.min(m.xs[nx - 1], x)); y = Math.max(m.ys[0], Math.min(m.ys[ny - 1], y));
        int a = 0; while (a < nx - 2 && x > m.xs[a + 1]) a++;
        int b = 0; while (b < ny - 2 && y > m.ys[b + 1]) b++;
        double u = (x - m.xs[a]) / (m.xs[a + 1] - m.xs[a]), v = (y - m.ys[b]) / (m.ys[b + 1] - m.ys[b]);
        double low = m.z[b][a] + (m.z[b][a + 1] - m.z[b][a]) * u, high = m.z[b + 1][a] + (m.z[b + 1][a + 1] - m.z[b + 1][a]) * u;
        return low + (high - low) * v;
    }
    /** The bed's lowest and highest height under a rectangle {x0, y0, x1, y1}, sampled every 4 mm and at its edges: {min, max}. Pure. */
    static double[] under(Mesh m, double[] r) {
        double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
        int nx = Math.max(1, (int) Math.ceil((r[2] - r[0]) / 4)), ny = Math.max(1, (int) Math.ceil((r[3] - r[1]) / 4));
        for (int j = 0; j <= ny; j++) for (int i = 0; i <= nx; i++) {
            double h = heightAt(m, r[0] + (r[2] - r[0]) * i / nx, r[1] + (r[3] - r[1]) * j / ny);
            lo = Math.min(lo, h); hi = Math.max(hi, h);
        }
        return new double[] {lo, hi};
    }

    /** Where a footprint sits flattest: its centre, the bed's variation under it there, and under it where it is now. */
    static final class Spot { double x, y, range, rangeNow; boolean moved; }
    /**
     * The spot on the bed where a `w` × `h` mm footprint (now centred at cx, cy) has the least height variation under it:
     * tried every 4 mm inside the bed {x0, y0, x1, y1} and clear (by 2 mm) of the other parts' rectangles. It stays where
     * it is unless somewhere else is flatter by more than 0.01 mm (less than that is within what the probe repeats to); of
     * equally flat spots the nearest wins. Null when the mesh has a missing point or the part does not fit. Pure.
     */
    static Spot flattest(Mesh m, double w, double h, double cx, double cy, double[] bed, List<double[]> others) {
        if (m == null || m.xs.length < 2 || m.ys.length < 2 || m.count() != m.xs.length * m.ys.length || w > bed[2] - bed[0] || h > bed[3] - bed[1]) return null;
        Spot s = new Spot(); s.x = cx; s.y = cy;
        double[] now = under(m, new double[] {cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2});
        s.rangeNow = now[1] - now[0]; s.range = s.rangeNow;
        double best = s.rangeNow - 0.01, bestDistance = Double.POSITIVE_INFINITY;
        for (double y = bed[1] + h / 2; y <= bed[3] - h / 2 + 1e-9; y += 4) for (double x = bed[0] + w / 2; x <= bed[2] - w / 2 + 1e-9; x += 4) {
            double[] r = {x - w / 2, y - h / 2, x + w / 2, y + h / 2}; boolean clear = true;
            if (others != null) for (double[] o : others) if (r[0] < o[2] + 2 && r[2] > o[0] - 2 && r[1] < o[3] + 2 && r[3] > o[1] - 2) { clear = false; break; }
            if (!clear) continue;
            double[] u = under(m, r); double range = u[1] - u[0], distance = Math.hypot(x - cx, y - cy);
            if (range < best - 1e-9 || Math.abs(range - best) <= 1e-9 && distance < bestDistance) { best = range; bestDistance = distance; s.x = Math.round(x * 10) / 10.0; s.y = Math.round(y * 10) / 10.0; s.range = range; s.moved = true; }
        }
        return s;
    }

    /** A print's first layer set against a mesh: the bed's heights under its lines, where they are highest and lowest, and the layer's own height. */
    static final class FirstLayer { double min, max, layerHeight; double[] lowAt, highAt; double[] box; }
    /**
     * The bed under a print's first layer. The start G-code's purge line is left out: only first-layer lines inside the
     * second layer's outline (widened 3 mm) count, when there is a second layer. Null without a full mesh or a first layer. Pure.
     */
    static FirstLayer firstLayer(Mesh m, GcodeToolpath path) {
        if (m == null || path == null || path.layerCount == 0 || m.xs.length < 2 || m.ys.length < 2 || m.count() != m.xs.length * m.ys.length) return null;
        double[] keep = {Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
        if (path.layerCount > 1) {
            keep = new double[] {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
            for (int i = path.layerStart(1); i < path.layerEnd(1); i++) {
                keep[0] = Math.min(keep[0], Math.min(path.x0[i], path.x1[i])); keep[2] = Math.max(keep[2], Math.max(path.x0[i], path.x1[i]));
                keep[1] = Math.min(keep[1], Math.min(path.y0[i], path.y1[i])); keep[3] = Math.max(keep[3], Math.max(path.y0[i], path.y1[i]));
            }
            keep[0] -= 3; keep[1] -= 3; keep[2] += 3; keep[3] += 3;
        }
        FirstLayer f = new FirstLayer(); f.min = Double.POSITIVE_INFINITY; f.max = Double.NEGATIVE_INFINITY;
        f.box = new double[] {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
        for (int i = path.layerStart(0); i < path.layerEnd(0); i++) {
            double ax = path.x0[i], ay = path.y0[i], bx = path.x1[i], by = path.y1[i];
            if (Math.min(ax, bx) < keep[0] || Math.max(ax, bx) > keep[2] || Math.min(ay, by) < keep[1] || Math.max(ay, by) > keep[3]) continue;
            int steps = Math.max(1, (int) Math.ceil(Math.hypot(bx - ax, by - ay) / 4));
            for (int k = 0; k <= steps; k++) {
                double x = ax + (bx - ax) * k / steps, y = ay + (by - ay) * k / steps, z = heightAt(m, x, y);
                if (z < f.min) { f.min = z; f.lowAt = new double[] {x, y}; }
                if (z > f.max) { f.max = z; f.highAt = new double[] {x, y}; }
                f.box[0] = Math.min(f.box[0], x); f.box[1] = Math.min(f.box[1], y); f.box[2] = Math.max(f.box[2], x); f.box[3] = Math.max(f.box[3], y);
            }
        }
        if (f.lowAt == null) return null;
        f.layerHeight = path.layerZ(0);
        return f;
    }
    /**
     * The first layer's check in words: how much the bed varies under it, set against the layer's height, and what to do. The
     * printer's own levelling follows the mesh, so this is a hint where a first layer may come out uneven, not a fault. Pure.
     */
    static String firstLayerWords(Mesh m, FirstLayer f) {
        double range = f.max - f.min; String when = new SimpleDateFormat("d MMM", Locale.getDefault()).format(new Date(m.at));
        String head = String.format(Locale.getDefault(), "Under the first layer the bed varies %.2f mm (%s to %s mm; levelling of %s)", range, signed(f.min), signed(f.max), when);
        double share = f.layerHeight > 0.01 ? range / f.layerHeight : 0;
        if (range < 0.05 || share < 0.25) return head + ": flat enough that the first layer should go down evenly.";
        String where = String.format(Locale.getDefault(), " Highest near X %.0f, Y %.0f; lowest near X %.0f, Y %.0f.", f.highAt[0], f.highAt[1], f.lowAt[0], f.lowAt[1]);
        String of = f.layerHeight > 0.01 ? String.format(Locale.getDefault(), ", %d %% of the %.2f mm first layer", Math.round(share * 100), f.layerHeight) : "";
        return head + of + "." + where + (share >= 0.5
            ? " The printer's levelling follows the bed, but with this much the first layer can come out thin on the high side and loose on the low side: a flatter spot is worth it (plate view › More… › Move to the flattest spot)."
            : " The printer's levelling follows the bed, so this usually prints fine; if the first layer looks uneven there, try a flatter spot (plate view › More… › Move to the flattest spot).");
    }

    // ------------------------------------------------------------------ colours and 3D

    /** Half the colour scale's width (mm) at least, so a nearly flat bed is not painted as dramatic. */
    static final double MIN_SPAN = 0.05;
    /** The colour scale: its centre (the mean, or no change for a difference) and half-width (the farthest point from it). */
    static double[] scale(Mesh m, boolean difference) {
        double centre = difference ? 0 : m.mean(), span = MIN_SPAN;
        for (double[] r : m.z) for (double v : r) if (!Double.isNaN(v)) span = Math.max(span, Math.abs(v - centre));
        return new double[] {centre, span};
    }
    /** Blue below the centre, orange above, a quiet grey at it (darker in dark mode); `t` from −1 to 1. The 2D map, the 3D view and the toolpath viewer share it. */
    static int colour(double t, boolean dark) {
        t = Math.max(-1, Math.min(1, t));
        int mid = dark ? 0xff2a3438 : 0xffeef1f2, low = dark ? 0xff3987e5 : 0xff2a78d6, high = dark ? 0xffd95926 : 0xffeb6834;
        int end = t < 0 ? low : high; double a = Math.abs(t);
        int r = (int) Math.round(((mid >> 16) & 255) * (1 - a) + ((end >> 16) & 255) * a), g = (int) Math.round(((mid >> 8) & 255) * (1 - a) + ((end >> 8) & 255) * a),
            b = (int) Math.round((mid & 255) * (1 - a) + (end & 255) * a);
        return 0xff000000 | (r << 16) | (g << 8) | b;
    }
    static final int[] EXAGGERATIONS = {10, 20, 50, 100, 200, 500, 1000};
    /** The mesh as the 3D pages paint it on their plate: {xs, ys, colours: r, g, b per point, rows by Y}, on the shared colour scale. */
    static JSONObject paint(Mesh m, boolean dark) {
        try {
            double[] s = scale(m, false);
            JSONArray colours = new JSONArray(), xs = new JSONArray(), ys = new JSONArray();
            for (double[] row : m.z) for (double v : row) {
                int c = colour(Double.isNaN(v) ? 0 : (v - s[0]) / s[1], dark);
                colours.put(((c >> 16) & 255) / 255.0).put(((c >> 8) & 255) / 255.0).put((c & 255) / 255.0);
            }
            for (double x : m.xs) xs.put(x); for (double y : m.ys) ys.put(y);
            return new JSONObject().put("xs", xs).put("ys", ys).put("colours", colours);
        } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }
    /** A height exaggeration that makes the mesh's range about 40 mm tall (on a 256 mm bed), as a round 1-2-5 number from 10 to 1000. */
    static int autoExaggeration(Mesh m) {
        double want = 40 / Math.max(0.01, m.range());
        int best = 10;
        for (int v : EXAGGERATIONS) if (Math.abs(Math.log(v / want)) < Math.abs(Math.log(best / want))) best = v;
        return best;
    }

    /**
     * The mesh as a solid for a 3D program or a slicer (binary STL): the surface over the probed area at the bed's real X and Y
     * (mm, from the first probe point), heights × `exaggeration` above a `base` mm thick slab, closed by walls and a bottom.
     */
    static byte[] stl(Mesh m, double exaggeration, double base) {
        int nx = m.xs.length, ny = m.ys.length; double low = m.min();
        double[][][] top = new double[ny][nx][];
        for (int j = 0; j < ny; j++) for (int i = 0; i < nx; i++) {
            double v = Double.isNaN(m.z[j][i]) ? low : m.z[j][i];
            top[j][i] = new double[] {m.xs[i] - m.xs[0], m.ys[j] - m.ys[0], base + (v - low) * exaggeration};
        }
        List<double[][]> tris = new ArrayList<>();
        for (int j = 0; j + 1 < ny; j++) for (int i = 0; i + 1 < nx; i++) {
            double[] a = top[j][i], b = top[j][i + 1], c = top[j + 1][i + 1], d = top[j + 1][i];
            tris.add(new double[][] {a, b, c}); tris.add(new double[][] {a, c, d});
        }
        // The walls: each edge of the surface down to the bottom, facing out.
        // Walked anticlockwise seen from above (front +X, right +Y, back −X, left −Y), so every wall faces out.
        for (int i = 0; i + 1 < nx; i++) { wall(tris, top[0][i], top[0][i + 1]); wall(tris, top[ny - 1][i + 1], top[ny - 1][i]); }
        for (int j = 0; j + 1 < ny; j++) { wall(tris, top[j][nx - 1], top[j + 1][nx - 1]); wall(tris, top[j + 1][0], top[j][0]); }
        // The bottom on the same grid as the walls' feet, facing down: every edge meets its neighbour exactly (watertight).
        for (int j = 0; j + 1 < ny; j++) for (int i = 0; i + 1 < nx; i++) {
            double[] a = foot(top[j][i]), b = foot(top[j][i + 1]), c = foot(top[j + 1][i + 1]), d = foot(top[j + 1][i]);
            tris.add(new double[][] {a, c, b}); tris.add(new double[][] {a, d, c});
        }
        java.nio.ByteBuffer out = java.nio.ByteBuffer.allocate(84 + tris.size() * 50).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        byte[] header = ("Link Workshop bed mesh, heights x" + Math.round(exaggeration)).getBytes(StandardCharsets.US_ASCII);
        out.put(header, 0, Math.min(80, header.length)); out.position(80); out.putInt(tris.size());
        for (double[][] t : tris) {
            double[] u = {t[1][0] - t[0][0], t[1][1] - t[0][1], t[1][2] - t[0][2]}, v = {t[2][0] - t[0][0], t[2][1] - t[0][1], t[2][2] - t[0][2]};
            double nx3 = u[1] * v[2] - u[2] * v[1], ny3 = u[2] * v[0] - u[0] * v[2], nz3 = u[0] * v[1] - u[1] * v[0], len = Math.max(1e-12, Math.sqrt(nx3 * nx3 + ny3 * ny3 + nz3 * nz3));
            out.putFloat((float) (nx3 / len)); out.putFloat((float) (ny3 / len)); out.putFloat((float) (nz3 / len));
            for (double[] p : t) { out.putFloat((float) p[0]); out.putFloat((float) p[1]); out.putFloat((float) p[2]); }
            out.putShort((short) 0);
        }
        return out.array();
    }
    private static double[] foot(double[] p) { return new double[] {p[0], p[1], 0}; }
    /** Two triangles from a top edge (a → b, walked anticlockwise seen from above) down to the bottom. */
    private static void wall(List<double[][]> tris, double[] a, double[] b) {
        double[] a0 = {a[0], a[1], 0}, b0 = {b[0], b[1], 0};
        tris.add(new double[][] {a, a0, b0}); tris.add(new double[][] {a, b0, b});
    }

    // ------------------------------------------------------------------ rebuilding the grid from the status stream

    /** A position the printer reported while probing: {step, x, y, z}. */
    static final class Event {
        final int phase; final double x, y, z;
        Event(int phase, double x, double y, double z) { this.phase = phase; this.x = x; this.y = y; this.z = z; }
    }

    /**
     * The probe grid from the reported positions. Consecutive reports at the same (rounded) X and Y are one visit; a visit's
     * probe reading is its last Z near 0 (a touch); several visits to a point are averaged. Of the steps seen, the one that
     * covers its grid best is taken. Method adapted from Sixth49's cc2-bed-mesh-sniffer (MIT). Pure.
     */
    static Mesh reconstruct(List<Event> events) {
        Map<Integer, Map<String, double[]>> byPhase = new LinkedHashMap<>();   // phase → "x,y" → {x, y, sum, n}
        int lastX = Integer.MIN_VALUE, lastY = Integer.MIN_VALUE, lastPhase = Integer.MIN_VALUE; double contact = Double.NaN;
        for (int k = 0; k <= events.size(); k++) {
            Event e = k < events.size() ? events.get(k) : null;
            int x = e == null ? Integer.MIN_VALUE : (int) Math.round(e.x), y = e == null ? Integer.MIN_VALUE : (int) Math.round(e.y), phase = e == null ? Integer.MIN_VALUE : e.phase;
            if (e == null || x != lastX || y != lastY || phase != lastPhase) {
                // The visit ends: its last touch counts.
                if (lastPhase != Integer.MIN_VALUE && !Double.isNaN(contact)) {
                    double[] p = byPhase.computeIfAbsent(lastPhase, q -> new LinkedHashMap<>()).computeIfAbsent(lastX + "," + lastY,
                        q -> new double[] {0, 0, 0, 0, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY});
                    p[0] = lastX; p[1] = lastY; p[2] += contact; p[3]++; p[4] = Math.min(p[4], contact); p[5] = Math.max(p[5], contact);
                }
                contact = Double.NaN; lastX = x; lastY = y; lastPhase = phase;
            }
            if (e != null && Math.abs(e.z) < CONTACT_MM) contact = e.z;
        }
        Mesh best = null; double bestScore = -1;
        for (Map.Entry<Integer, Map<String, double[]>> phase : byPhase.entrySet()) {
            TreeSet<Double> xs = new TreeSet<>(), ys = new TreeSet<>();
            for (double[] p : phase.getValue().values()) { xs.add(p[0]); ys.add(p[1]); }
            int count = phase.getValue().size(), expected = xs.size() * ys.size();
            double coverage = expected == 0 ? 0 : count / (double) expected, score = count >= 9 ? count * coverage : 0;
            if (score <= bestScore) continue;
            bestScore = score;
            Mesh m = new Mesh(); m.phase = phase.getKey(); m.expected = expected;
            m.xs = xs.stream().mapToDouble(Double::doubleValue).toArray(); m.ys = ys.stream().mapToDouble(Double::doubleValue).toArray();
            m.z = new double[m.ys.length][m.xs.length];
            for (double[] row : m.z) Arrays.fill(row, Double.NaN);
            for (double[] p : phase.getValue().values()) {
                m.z[Arrays.binarySearch(m.ys, p[1])][Arrays.binarySearch(m.xs, p[0])] = round4(p[2] / p[3]);
                if (p[3] >= 2) { m.repeated++; m.repeatSpread = Double.isNaN(m.repeatSpread) ? p[5] - p[4] : Math.max(m.repeatSpread, p[5] - p[4]); }
            }
            if (!Double.isNaN(m.repeatSpread)) m.repeatSpread = round4(m.repeatSpread);
            best = m;
        }
        return best;
    }
    private static double round4(double v) { return Math.round(v * 10_000) / 10_000.0; }
    /** Whether a rebuilt grid can be kept: every point of a grid at least 3 × 3, and no smaller than the last one kept. */
    static boolean complete(Mesh m, int lastCount) {
        return m != null && m.xs.length >= 3 && m.ys.length >= 3 && m.count() == m.expected && m.count() >= Math.max(9, lastCount);
    }

    /**
     * Follows the status stream (each merged status, local or through the cloud): notes the head's position while the
     * printer probes, and when the levelling ends, hands back the rebuilt mesh (or says why there is none). Not thread-safe:
     * one caller (the service's main thread).
     */
    static final class Capture {
        private final List<Event> events = new ArrayList<>();
        private final java.util.Set<Long> probeLocations = new java.util.HashSet<>();
        int probeLocations() { return probeLocations.size(); }
        private boolean active; private String lastSignature = ""; private long startedAt;
        private double bed = Double.NaN, bedTarget = Double.NaN; private String file = "", source = "";
        /** The last levelling that gave no mesh, in words ("" none), and whether a levelling just began (for the log). */
        String problem = ""; boolean began;

        boolean active() { return active; }
        int points() { return events.size(); }

        /** One status; the finished mesh when a levelling has just ended with a full grid, else null. */
        Mesh feed(JSONObject status, long now, String from, int lastCount) {
            began = false;
            JSONObject machine = status == null ? null : status.optJSONObject("machine_status");
            if (machine == null) return null;
            int code = machine.optInt("status", -1), sub = machine.optInt("sub_status", -1);
            boolean levelling = sub == PROBING || sub == LEVELLED || code == 5;
            if (levelling) {
                if (!active) { active = true; began = true; events.clear(); probeLocations.clear(); lastSignature = ""; startedAt = now; source = from; file = ""; }
                JSONObject at = Cc2Codec.position(status);
                double x = at == null ? Double.NaN : at.optDouble("x", Double.NaN), y = at == null ? Double.NaN : at.optDouble("y", Double.NaN), z = at == null ? Double.NaN : at.optDouble("z", Double.NaN);
                if (Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z) && events.size() < MAX_EVENTS) {
                    String signature = sub + "|" + Math.round(x * 100) + "|" + Math.round(y * 100) + "|" + Math.round(z * 1000);
                    if (!signature.equals(lastSignature)) {
                        lastSignature = signature; events.add(new Event(sub, x, y, z));
                        // Match the reconstruction's X/Y rounding. Lifts, repeats, and other phases don't advance progress.
                        if (sub == PROBING && Math.abs(z) < CONTACT_MM)
                            probeLocations.add((Math.round(x) << 32) ^ (Math.round(y) & 0xffffffffL));
                    }
                }
                JSONObject heater = status.optJSONObject("heater_bed");
                if (heater != null) { bed = heater.optDouble("temperature", bed); bedTarget = heater.optDouble("target", bedTarget); }
                JSONObject print = status.optJSONObject("print_status");
                if (code == 2 && print != null && !print.optString("filename").isEmpty()) file = StatusPresentation.clean(print.optString("filename"));
                return null;
            }
            if (!active) return null;
            active = false;
            Mesh m = reconstruct(events); int seen = events.size(); events.clear(); probeLocations.clear();
            if (!complete(m, lastCount)) {
                problem = m == null ? "the printer levelled the bed, but no probe positions came through (" + seen + " reports)"
                    : "the printer levelled the bed, but only " + m.count() + " of " + Math.max(m.expected, lastCount) + " points came through (" + from + ")";
                return null;
            }
            problem = ""; m.at = now; m.source = source; m.bed = bed; m.bedTarget = bedTarget; m.file = file;
            return m;
        }
    }

    // ------------------------------------------------------------------ kept on the phone

    static File directory(File filesDir) { File d = new File(filesDir, "bed-mesh"); d.mkdirs(); return d; }

    /** Saved in the same shape as cc2-bed-mesh's files (x_mm, y_mm, z_mm rows by Y), so a capture opens in either. */
    static JSONObject json(Mesh m) {
        try {
            JSONArray rows = new JSONArray();
            for (double[] row : m.z) { JSONArray r = new JSONArray(); for (double v : row) r.put(Double.isNaN(v) ? JSONObject.NULL : v); rows.put(r); }
            JSONObject capture = new JSONObject().put("source", "status stream while probing (" + m.source + ")").put("probe_phase", m.phase)
                .put("point_count", m.count()).put("expected_points", m.expected).put("repeated_points", m.repeated).put("min_z_mm", m.min()).put("max_z_mm", m.max()).put("peak_to_valley_mm", m.range());
            JSONObject out = new JSONObject().put("schema_version", 1).put("app", "Link Workshop").put("printer_model", "Centauri Carbon 2")
                .put("captured_at", IntegrationStatus.iso(m.at)).put("captured_at_ms", m.at).put("units", "mm")
                .put("plate_width_mm", PLATE_MM).put("plate_height_mm", PLATE_MM)
                .put("x_mm", arr(m.xs)).put("y_mm", arr(m.ys)).put("z_mm", rows).put("capture", capture)
                .put("note", "Rebuilt from the positions the printer reported while probing; not compared with the mesh the printer stores.");
            if (!Double.isNaN(m.repeatSpread)) capture.put("repeat_spread_mm", m.repeatSpread);
            if (!Double.isNaN(m.bed)) out.put("bed_c", m.bed);
            if (!Double.isNaN(m.bedTarget)) out.put("bed_target_c", m.bedTarget);
            if (!m.file.isEmpty()) out.put("before_print", m.file);
            if (!m.printer.isEmpty()) out.put("printer_id", m.printer);
            return out;
        } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }
    private static JSONArray arr(double[] v) throws JSONException { JSONArray a = new JSONArray(); for (double d : v) a.put(d); return a; }

    static Mesh parse(JSONObject o) {
        Mesh m = new Mesh();
        JSONArray xs = o.optJSONArray("x_mm"), ys = o.optJSONArray("y_mm"), rows = o.optJSONArray("z_mm");
        if (xs == null || ys == null || rows == null || rows.length() != ys.length() || xs.length() > 64 || ys.length() > 64) return null;
        m.xs = new double[xs.length()]; for (int i = 0; i < m.xs.length; i++) m.xs[i] = xs.optDouble(i);
        m.ys = new double[ys.length()]; for (int j = 0; j < m.ys.length; j++) m.ys[j] = ys.optDouble(j);
        m.z = new double[m.ys.length][m.xs.length];
        for (int j = 0; j < m.ys.length; j++) {
            JSONArray r = rows.optJSONArray(j); if (r == null || r.length() != m.xs.length) return null;
            for (int i = 0; i < m.xs.length; i++) m.z[j][i] = r.isNull(i) ? Double.NaN : r.optDouble(i, Double.NaN);
        }
        m.at = o.optLong("captured_at_ms", 0);
        JSONObject c = o.optJSONObject("capture");
        m.source = c == null ? "" : c.optString("source");
        m.repeatSpread = c == null ? Double.NaN : c.optDouble("repeat_spread_mm", Double.NaN); m.repeated = c == null ? 0 : c.optInt("repeated_points"); m.phase = c == null ? 0 : c.optInt("probe_phase"); m.expected = c == null ? m.count() : c.optInt("expected_points", m.count());
        m.bed = o.optDouble("bed_c", Double.NaN); m.bedTarget = o.optDouble("bed_target_c", Double.NaN);
        m.file = o.optString("before_print"); m.printer = o.optString("printer_id");
        return m;
    }

    /** Keeps a mesh (newest KEEP kept) and returns its file name. */
    static String save(File dir, Mesh m) throws IOException {
        String stem = new SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.ROOT).format(new Date(m.at)) + "-bed-mesh";
        File f = new File(dir, stem + ".json"); int n = 1;
        while (f.exists()) f = new File(dir, stem + "-" + n++ + ".json");
        File temp = new File(f.getPath() + ".tmp");
        String text; try { text = json(m).toString(1); } catch (JSONException impossible) { throw new IOException(impossible); }
        Files.write(temp.toPath(), text.getBytes(StandardCharsets.UTF_8));
        if (!temp.renameTo(f)) throw new IOException("could not keep the mesh");
        m.name = f.getName();
        List<Mesh> all = list(dir);
        for (int i = KEEP; i < all.size(); i++) new File(dir, all.get(i).name).delete();
        return f.getName();
    }
    /** Every kept mesh, newest first. */
    static List<Mesh> list(File dir) {
        List<Mesh> out = new ArrayList<>();
        File[] files = dir.listFiles((d, n) -> n.endsWith("-bed-mesh.json") || n.matches(".*-bed-mesh-\\d+\\.json"));
        if (files != null) for (File f : files) {
            try { Mesh m = parse(new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8))); if (m != null) { m.name = f.getName(); if (m.at == 0) m.at = f.lastModified(); out.add(m); } }
            catch (IOException | JSONException unreadable) { }
        }
        out.sort((a, b) -> Long.compare(b.at, a.at));
        return out;
    }

    private BedMesh() { }
}
