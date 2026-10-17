package io.github.thelastfrogrammer.elink;

import java.util.ArrayDeque;
import java.util.Locale;

/**
 * How the chamber temperature moves: a straight line through the last five minutes of readings, so the Print tab can say
 * "40° in ≈ 12 min" while the chamber warms for ABS, ASA, PC or nylon (the CC2 has no chamber heater: it warms from the bed
 * and the print). Independent of Android.
 */
final class ChamberTrend {
    static final long WINDOW_MS = 5 * 60_000L, MIN_SPAN_MS = 90_000L;
    /** Below this (°C per minute) the chamber counts as steady. */
    static final double STEADY = 0.1;
    private final ArrayDeque<double[]> points = new ArrayDeque<>();

    /** One reading (`mono`: a clock that never steps). Readings closer than 5 s to the last are skipped. */
    synchronized void add(long mono, double celsius) {
        if (Double.isNaN(celsius)) return;
        if (!points.isEmpty() && mono < points.peekLast()[0]) points.clear();   // the clock went back: start again
        if (!points.isEmpty() && mono - points.peekLast()[0] < 5_000) return;
        points.addLast(new double[] {mono, celsius});
        while (!points.isEmpty() && mono - points.peekFirst()[0] > WINDOW_MS) points.removeFirst();
    }
    synchronized void clear() { points.clear(); }

    /** °C per minute over the window (least squares), or NaN with under 90 s or 3 readings. */
    synchronized double perMinute() {
        if (points.size() < 3 || points.peekLast()[0] - points.peekFirst()[0] < MIN_SPAN_MS) return Double.NaN;
        double n = points.size(), sx = 0, sy = 0, sxx = 0, sxy = 0, t0 = points.peekFirst()[0];
        for (double[] p : points) { double x = (p[0] - t0) / 60_000.0; sx += x; sy += p[1]; sxx += x * x; sxy += x * p[1]; }
        double d = n * sxx - sx * sx; return d <= 0 ? Double.NaN : (n * sxy - sx * sy) / d;
    }
    /** Seconds until `goal` at the present rate, 0 when already there, -1 when not warming or unknown. */
    long secondsTo(double goal, double now) {
        if (Double.isNaN(goal) || Double.isNaN(now)) return -1;
        if (now >= goal) return 0;
        double rate = perMinute();
        if (Double.isNaN(rate) || rate < STEADY) return -1;
        return Math.round((goal - now) / rate * 60);
    }
    /** The chamber to wait for with this filament: 40 °C for ABS, ASA, PC and nylon; NaN for the rest (they print best cool). */
    static double goal(String filamentType) {
        String family = FilamentMatch.family(filamentType == null ? "" : filamentType);
        return family.equals("ABS") || family.equals("ASA") || family.equals("PC") || family.startsWith("PA") ? 40 : Double.NaN;
    }
    /** The chamber tile's second line: "↑ 40° in ≈ 12 min", "40° reached", "↑ 0.6°/min", "↓ 0.4°/min", "peak 41°" or "". */
    String line(double now, double goal, double peak) {
        double rate = perMinute();
        if (!Double.isNaN(goal)) {
            if (now >= goal) return String.format(Locale.ROOT, "%.0f° reached", goal);
            long seconds = secondsTo(goal, now);
            if (seconds > 0) return String.format(Locale.ROOT, "↑ %.0f° in ≈ %s", goal, StatusPresentation.span(Math.max(60, seconds)));
        }
        if (!Double.isNaN(rate) && Math.abs(rate) >= STEADY) return String.format(Locale.ROOT, "%s %.1f°/min", rate > 0 ? "↑" : "↓", Math.abs(rate));
        if (!Double.isNaN(peak) && peak > now + 1) return String.format(Locale.ROOT, "peak %.0f°", peak);
        return "";
    }
}
