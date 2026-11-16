package io.github.thelastfrogrammer.elink;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * How the printer is wearing, from what the app already keeps: how fast the bed and the nozzle heat up print after print
 * (from the recordings), how the bed's tilt and flatness move between levellings at the same bed temperature, and how well
 * the probe repeats a point it measures twice (from the bed meshes). Each is a trend from the first few to the last few, so
 * one odd print does not raise anything. Pure: the screen reads the files and hands them in.
 */
final class PrinterHealth {
    /** Heating between these temperatures, while the target is above the top one: clear of the start and of the slow approach. */
    static final double BED_FROM = 35, BED_TO = 55, NOZZLE_FROM = 120, NOZZLE_TO = 190;
    /** Values compared at each end of a trend; and how much slower (or more) before it is worth a look. */
    static final int ENDS = 3;
    static final double SLOWER = 0.2, NOTICE = 0.1, TILT_GROWTH_MM = 0.05, FLATNESS_GROWTH_MM = 0.05, REPEAT_WORRY_MM = 0.02;

    /** A value at a time (ms): one print's heating rate, one levelling's tilt. */
    static final class Point { final long at; final double value; Point(long at, double value) { this.at = at; this.value = value; } }

    /**
     * How fast a heater climbed from `from` to `to` (°C per minute) the first time it did so with its target above `to`
     * (crossings found between readings by a straight line); NaN when it never did, or started above `from`. Pure.
     */
    static double heatRate(double[] t, double[] temp, double[] target, double from, double to) {
        double startAt = Double.NaN;
        for (int i = 1; i < t.length; i++) {
            if (Double.isNaN(temp[i]) || Double.isNaN(temp[i - 1]) || Double.isNaN(target[i]) || target[i] < to + 2) { startAt = Double.NaN; continue; }
            if (Double.isNaN(startAt)) {
                if (temp[i - 1] > from) continue;   // already warmer than the start of the measured stretch
                if (temp[i] >= from) startAt = cross(t[i - 1], temp[i - 1], t[i], temp[i], from);
            }
            if (!Double.isNaN(startAt) && temp[i] >= to) {
                double endAt = cross(t[i - 1], temp[i - 1], t[i], temp[i], to);
                return endAt > startAt ? (to - from) / ((endAt - startAt) / 60) : Double.NaN;
            }
        }
        return Double.NaN;
    }
    private static double cross(double t0, double v0, double t1, double v1, double level) { return v1 == v0 ? t1 : t0 + (t1 - t0) * (level - v0) / (v1 - v0); }

    /** A trend: the median of the first ENDS values against the median of the last ENDS (oldest first); null with fewer than 2 × ENDS. */
    static final class Trend { double before, now, change; long since, until; int count; }
    static Trend trend(List<Point> points) {
        if (points.size() < 2 * ENDS) return null;
        List<Point> sorted = new ArrayList<>(points); sorted.sort((a, b) -> Long.compare(a.at, b.at));
        Trend t = new Trend(); t.count = sorted.size();
        t.before = median(sorted.subList(0, ENDS)); t.now = median(sorted.subList(sorted.size() - ENDS, sorted.size()));
        t.change = t.before == 0 ? 0 : (t.now - t.before) / Math.abs(t.before);
        t.since = sorted.get(0).at; t.until = sorted.get(sorted.size() - 1).at;
        return t;
    }
    private static double median(List<Point> points) {
        List<Double> v = new ArrayList<>(); for (Point p : points) v.add(p.value); Collections.sort(v);
        return v.size() % 2 == 1 ? v.get(v.size() / 2) : (v.get(v.size() / 2 - 1) + v.get(v.size() / 2)) / 2;
    }

    /** "The bed heats 25 % slower than in early September (…)": a heating trend in words, saying when it is worth a look. */
    static String heatingWords(String heater, Trend t, int needed) {
        if (t == null) return String.format(Locale.getDefault(), "Not enough prints yet: the %s's heating is compared once %d prints have heated it from cold.", heater, needed);
        String since = day(t.since);
        String rates = String.format(Locale.getDefault(), "%.1f °C a minute at first, %.1f now (%d prints since %s).", t.before, t.now, t.count, since);
        if (t.change <= -SLOWER) return String.format(Locale.getDefault(), "The %s heats %d %% slower than at first: %s A weakening heater, a loose thermistor or a colder room can be behind it: worth a look if it keeps up.",
            heater, Math.round(-t.change * 100), rates);
        if (t.change <= -NOTICE) return String.format(Locale.getDefault(), "The %s heats a little slower than at first (%d %%): %s Nothing to act on yet; it is worth a look if it passes %d %%.",
            heater, Math.round(-t.change * 100), rates, Math.round(SLOWER * 100));
        if (t.change >= SLOWER) return String.format(Locale.getDefault(), "The %s heats %d %% faster than at first: %s A warmer room or enclosure usually explains it.", heater, Math.round(t.change * 100), rates);
        return String.format(Locale.getDefault(), "The %s heats as it did: %s", heater, rates);
    }

    // ------------------------------------------------------------------ the bed over time

    /** The tilt across the probed area (mm, both directions together) and the flatness without it (mm), from the fitted plane. */
    static double[] tiltAndFlatness(BedMesh.Mesh m) {
        double[] p = BedMesh.plane(m); if (p == null) return null;
        double spanX = m.xs[m.xs.length - 1] - m.xs[0], spanY = m.ys[m.ys.length - 1] - m.ys[0];
        return new double[] {Math.hypot(p[1] * spanX, p[2] * spanY), BedMesh.residualRange(m, p)};
    }
    /** The levellings worth comparing: those at the bed temperature (target, to 5 °C) that has the most, oldest first. */
    static List<BedMesh.Mesh> sameTemperature(List<BedMesh.Mesh> all) {
        Map<Long, List<BedMesh.Mesh>> by = new HashMap<>();
        for (BedMesh.Mesh m : all) {
            double t = !Double.isNaN(m.bedTarget) ? m.bedTarget : m.bed;
            by.computeIfAbsent(Double.isNaN(t) ? -1L : Math.round(t / 5) * 5, k -> new ArrayList<>()).add(m);
        }
        List<BedMesh.Mesh> best = new ArrayList<>();
        for (List<BedMesh.Mesh> group : by.values()) if (group.size() > best.size()) best = group;
        best = new ArrayList<>(best); best.sort((a, b) -> Long.compare(a.at, b.at));
        return best;
    }
    /** The bed's tilt and flatness over time, at one bed temperature, in words. */
    static List<String> bedWords(List<BedMesh.Mesh> all) {
        List<String> out = new ArrayList<>();
        List<BedMesh.Mesh> same = sameTemperature(all);
        List<Point> tilt = new ArrayList<>(), flat = new ArrayList<>();
        for (BedMesh.Mesh m : same) { double[] tf = tiltAndFlatness(m); if (tf != null) { tilt.add(new Point(m.at, tf[0])); flat.add(new Point(m.at, tf[1])); } }
        String temp = same.isEmpty() || Double.isNaN(same.get(0).bedTarget) ? "" : String.format(Locale.getDefault(), " at %.0f °C", Math.round(same.get(0).bedTarget / 5) * 5.0);
        Trend tt = trend(tilt), ft = trend(flat);
        if (tt == null) { out.add(String.format(Locale.getDefault(), "Not enough levellings yet%s: the bed's tilt and flatness are compared once %d are kept at the same bed temperature (%d now).", temp, 2 * ENDS, same.size())); return out; }
        double dTilt = tt.now - tt.before, dFlat = ft.now - ft.before;
        out.add(String.format(Locale.getDefault(), "Tilt%s: %.2f mm at first, %.2f mm now (%d levellings since %s).%s", temp, tt.before, tt.now, tt.count, day(tt.since),
            dTilt >= TILT_GROWTH_MM ? " It has grown: the bed may have shifted on its mounts, or the gantry is out of square. Worth checking before it shows in first layers." : ""));
        out.add(String.format(Locale.getDefault(), "Flatness without the tilt: %.2f mm at first, %.2f mm now.%s", ft.before, ft.now,
            dFlat >= FLATNESS_GROWTH_MM ? " The bed itself is less flat than it was: a warped plate, or debris under a removable plate, can do this." : ""));
        return out;
    }

    /** Probe repeatability, from levellings where the printer probed a point more than once. */
    static String repeatWords(List<BedMesh.Mesh> all) {
        double worst = Double.NaN; int levellings = 0, points = 0;
        for (BedMesh.Mesh m : all) if (!Double.isNaN(m.repeatSpread)) { levellings++; points += m.repeated; worst = Double.isNaN(worst) ? m.repeatSpread : Math.max(worst, m.repeatSpread); }
        if (levellings == 0) return "The printer has not probed any point twice in the levellings kept, so the probe's repeatability cannot be told from them.";
        return String.format(Locale.getDefault(), "Where it probed a point twice (%d points in %d levellings), the readings were at most %.3f mm apart.%s", points, levellings, worst,
            worst > REPEAT_WORRY_MM ? " That is more than a probe usually wanders: a dirty nozzle tip or a loose probe can do this." : " That is as close as a probe usually repeats.");
    }

    static String day(long at) { return new SimpleDateFormat("d MMM", Locale.getDefault()).format(new Date(at)); }

    private PrinterHealth() { }
}
