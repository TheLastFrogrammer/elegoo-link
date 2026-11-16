package io.github.thelastfrogrammer.elink;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.json.JSONObject;

/**
 * Time left, corrected by what your own prints showed: in each finished print the app recorded, how much time was really
 * left at each point set against what the printer said was left, by quarter of the print (the printer's error can change as
 * a print goes on). The same file printed before counts first; otherwise the newest prints. Prints with a pause are left
 * out (a pause makes the real time longer for no fault of the estimate). Pure apart from the one learned set kept for the
 * running print, which every screen reads.
 */
final class FinishTimes {
    static final String PREF = "learnedFinishTimes";
    static final int BANDS = 4, NEWEST = 20, SAME_FILE = 2, ANY_FILES = 3;
    /** Only readings with this much left (s) count: near the end a few seconds' difference would be a large share. */
    static final long MIN_LEFT_S = 300;
    /** Factors outside this range are taken as a print gone wrong (stopped, restarted), not as the estimate's error. */
    static final double LOWEST = 0.5, HIGHEST = 2.0;

    /** The correction and its source can differ from one quarter of the print to the next. */
    static final class Learned {
        final double[] factor = new double[BANDS];
        final int[] prints = new int[BANDS];
        final boolean[] sameFile = new boolean[BANDS];
        boolean any() { for (double f : factor) if (!Double.isNaN(f)) return true; return false; }
    }

    /** Both recordings must identify the same physical printer before we use one to predict the other. */
    static boolean samePrinter(String activeId, String historicalId) {
        return activeId != null && !activeId.isEmpty() && activeId.equals(historicalId);
    }

    /** One finished print's factors (real time left ÷ printer's time left) per quarter: the median of its readings in each; NaN where none. Pure. */
    static double[] factors(double[] elapsed, double[] progress, double[] remaining, double[] sub) {
        double end = Double.NaN;
        for (double t : elapsed) if (!Double.isNaN(t)) end = Double.isNaN(end) ? t : Math.max(end, t);
        double[] out = new double[BANDS]; java.util.Arrays.fill(out, Double.NaN);
        if (Double.isNaN(end)) return out;
        for (double s : sub) if (s == 2501 || s == 2502 || s == 2505) return out;   // paused at some point
        List<List<Double>> bands = new ArrayList<>(); for (int b = 0; b < BANDS; b++) bands.add(new ArrayList<>());
        for (int i = 0; i < elapsed.length; i++) {
            if (Double.isNaN(elapsed[i]) || !validProgress(progress[i]) || Double.isNaN(remaining[i]) || remaining[i] < MIN_LEFT_S) continue;
            if (!Double.isNaN(sub[i]) && (int) sub[i] != 2075) continue;   // only while printing
            double real = end - elapsed[i], f = real / remaining[i];
            if (real <= 0 || f < LOWEST || f > HIGHEST) continue;
            bands.get(band(progress[i])).add(f);
        }
        for (int b = 0; b < BANDS; b++) out[b] = median(bands.get(b));
        return out;
    }
    static int band(double progress) { return Math.max(0, Math.min(BANDS - 1, (int) (progress / (100.0 / BANDS)))); }

    /**
     * Learns from finished prints' factors (newest first), `same` marking those of the file now printing: the same file's when
     * at least SAME_FILE of them have a value for a quarter, else the newest ANY_FILES or more of any file. Null when neither. Pure.
     */
    static Learned learn(List<double[]> newestFirst, List<Boolean> same) {
        Learned l = new Learned(); java.util.Arrays.fill(l.factor, Double.NaN);
        for (int b = 0; b < BANDS; b++) {
            List<Double> mine = new ArrayList<>(), all = new ArrayList<>();
            for (int k = 0; k < newestFirst.size(); k++) {
                double f = newestFirst.get(k)[b]; if (Double.isNaN(f)) continue;
                if (same.get(k)) mine.add(f);
                if (all.size() < NEWEST) all.add(f);
            }
            if (mine.size() >= SAME_FILE) { l.factor[b] = median(mine); l.prints[b] = mine.size(); l.sameFile[b] = true; }
            else if (all.size() >= ANY_FILES) { l.factor[b] = median(all); l.prints[b] = all.size(); }
        }
        return l.any() ? l : null;
    }
    private static double median(List<Double> v) {
        if (v.isEmpty()) return Double.NaN;
        List<Double> s = new ArrayList<>(v); Collections.sort(s);
        return s.size() % 2 == 1 ? s.get(s.size() / 2) : (s.get(s.size() / 2 - 1) + s.get(s.size() / 2)) / 2;
    }

    // ------------------------------------------------------------------ the running print

    /** The learned set for the print running now (null: none, or the setting is off). Set by the printer service. */
    static volatile Learned current;

    /** Time left, corrected when something was learned for this stage of the print; the printer's own value otherwise. */
    static long left(JSONObject machine, JSONObject print) {
        long reported = print == null ? -1 : print.optLong("remaining_time_sec", -1);
        return corrected(current, reported, machine == null ? Double.NaN : machine.optDouble("progress", Double.NaN));
    }
    static long corrected(Learned l, long reported, double progress) {
        if (l == null || reported < 0 || !validProgress(progress)) return reported;
        double f = l.factor[band(progress)];
        return Double.isNaN(f) ? reported : Math.round(reported * f);
    }
    /** What the correction rests on, for the Print tab: "your 4 earlier prints of this file ran 12 % longer than the printer said". */
    static String basis(Learned l, double progress) {
        if (l == null || !validProgress(progress)) return "";
        int b = band(progress);
        double f = l.factor[b]; if (Double.isNaN(f)) return "";
        long percent = Math.round(Math.abs(f - 1) * 100);
        String who = l.prints[b] + (l.sameFile[b] ? " earlier prints with this filename" : " recent prints on this printer");
        return percent < 2 ? "Estimate based on " + who + "; they finished close to the printer's estimate."
            : String.format(Locale.getDefault(), "Based on %s: at this stage they took %d %% %s than the printer estimated.", who, percent, f > 1 ? "longer" : "less time");
    }

    private static boolean validProgress(double progress) { return progress >= 0 && progress <= 100; }

    private FinishTimes() { }
}
