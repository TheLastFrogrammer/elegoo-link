package io.github.thelastfrogrammer.elink;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONObject;

/**
 * When a print was stopped or marked failed: the moment it most likely went wrong, the last picture before it and the
 * first after, the readings around it, and what the G-code was printing on that layer. The moment is, in order: a guardian
 * alarm the person confirmed as a real problem, the guardian's first alarm, the first finding in the readings with a layer
 * (a nozzle drop, a stall…), the filament sensor's first "no filament", and otherwise the last reading while printing
 * (where the print was stopped). Only reads what the recording kept. Pure.
 */
final class FailureReport {
    /** How far before the moment the last good picture must be (s): the trouble often starts a little before it is seen. */
    static final double GOOD_BEFORE_S = 60;
    /** The stretch before the moment the readings are summed up over (s). */
    static final double BEFORE_S = 300;

    static boolean applies(PrintRecorder.Recording r) { return "stopped".equals(r.outcome) || "failed".equals(r.journal.result); }

    /** The moment: when (elapsed s), the layer (printer's, 1-based; -1 unknown), and how it was found, in words. */
    static final class Moment {
        final double t; final int layer; final String how;
        Moment(double t, int layer, String how) { this.t = t; this.layer = layer; this.how = how; }
    }

    /**
     * The moment the print went wrong, from the readings (t elapsed s, time wall ms, layer, sub_status, filament_detected),
     * the guardian's checks and the journal's answers, and the findings in the readings. Null without readings.
     */
    static Moment moment(double[] t, double[] time, double[] layer, double[] sub, double[] filament, List<double[]> guardian, PrintJournal journal, List<RecordingAnalysis.Finding> findings) {
        if (t.length == 0) return null;
        // A guardian alarm the person answered "Real problem".
        long confirmed = Long.MAX_VALUE;
        for (JSONObject g : journal.guardian) if (!g.optBoolean("fine") && g.optLong("at") > 0) confirmed = Math.min(confirmed, g.optLong("at"));
        if (confirmed != Long.MAX_VALUE)
            for (int i = 0; i < t.length; i++) if (!Double.isNaN(time[i]) && time[i] >= confirmed) return new Moment(t[i], layerAt(layer, i), "the guardian's alarm you confirmed as a real problem");
        // The guardian's first alarm, by layer.
        int alarm = Integer.MAX_VALUE;
        for (double[] g : guardian) if (g.length > 5 && g[5] == 1 && !Double.isNaN(g[0]) && g[0] >= 1) alarm = Math.min(alarm, (int) g[0]);
        if (alarm != Integer.MAX_VALUE) { int i = firstAtLayer(layer, alarm); if (i >= 0) return new Moment(t[i], alarm, "the guardian's first alarm"); }
        // The earliest finding in the readings that has a layer.
        RecordingAnalysis.Finding first = null;
        for (RecordingAnalysis.Finding f : findings) if (f.layer >= 1 && (first == null || f.layer < first.layer)) first = f;
        if (first != null) { int i = firstAtLayer(layer, first.layer); if (i >= 0) return new Moment(t[i], first.layer, "the first thing the readings found: " + lowerFirst(first.words)); }
        // The filament sensor's first "no filament" while printing.
        for (int i = 0; i < t.length; i++) if (filament[i] == 0 && sub[i] == 2075) return new Moment(t[i], layerAt(layer, i), "the filament sensor's first report of no filament");
        // Where it was stopped: the last reading while printing.
        for (int i = t.length - 1; i >= 0; i--) if (sub[i] == 2075) return new Moment(t[i], layerAt(layer, i), "the last reading while it was printing, where it was stopped");
        return new Moment(t[t.length - 1], layerAt(layer, t.length - 1), "the last reading");
    }
    private static int layerAt(double[] layer, int i) { for (int k = i; k >= 0; k--) if (!Double.isNaN(layer[k]) && layer[k] >= 1) return (int) layer[k]; return -1; }
    private static int firstAtLayer(double[] layer, int wanted) { for (int i = 0; i < layer.length; i++) if (!Double.isNaN(layer[i]) && layer[i] >= wanted) return i; return -1; }
    private static String lowerFirst(String s) { return s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1); }

    /**
     * The last picture taken at least GOOD_BEFORE_S before the moment, and the first at or after it (or, when none came after,
     * the last one taken after the good one): indexes into the picture times (elapsed s, sorted), -1 when none.
     */
    static int[] pictures(double[] times, double moment) {
        int good = -1, bad = -1;
        for (int i = 0; i < times.length; i++) if (!Double.isNaN(times[i]) && times[i] <= moment - GOOD_BEFORE_S) good = i;
        for (int i = good + 1; i < times.length; i++) if (!Double.isNaN(times[i]) && times[i] >= moment) { bad = i; break; }
        if (bad < 0) for (int i = times.length - 1; i > good; i--) if (!Double.isNaN(times[i])) { bad = i; break; }
        return new int[] {good, bad};
    }

    /** The readings at the moment and over the minutes before it, in words. */
    static List<String> readings(double[] t, double[] nozzle, double[] nozzleTarget, double[] bed, double[] bedTarget, double[] fan, double[] sub, double moment) {
        List<String> out = new ArrayList<>();
        int at = -1; for (int i = 0; i < t.length; i++) if (t[i] <= moment) at = i;
        if (at < 0) return out;
        List<String> now = new ArrayList<>();
        heater(now, "nozzle", nozzle[at], nozzleTarget[at]); heater(now, "bed", bed[at], bedTarget[at]);
        if (!Double.isNaN(fan[at])) now.add(String.format(Locale.getDefault(), "part fan %.0f %%", fan[at]));
        if (!Double.isNaN(sub[at])) { String state = StatusPresentation.state(status((int) sub[at])); if (!state.isEmpty()) now.add(state.toLowerCase(Locale.ROOT)); }
        if (!now.isEmpty()) out.add("At that moment: " + String.join(", ", now) + ".");
        // Over the minutes before: how far each heater strayed from its target.
        double worstNozzle = 0, worstBed = 0; int count = 0;
        for (int i = 0; i <= at; i++) {
            if (t[i] < moment - BEFORE_S || Double.isNaN(sub[i]) || (int) sub[i] != 2075) continue;
            count++;
            if (!Double.isNaN(nozzle[i]) && !Double.isNaN(nozzleTarget[i]) && nozzleTarget[i] > 0) worstNozzle = Math.max(worstNozzle, nozzleTarget[i] - nozzle[i]);
            if (!Double.isNaN(bed[i]) && !Double.isNaN(bedTarget[i]) && bedTarget[i] > 0) worstBed = Math.max(worstBed, bedTarget[i] - bed[i]);
        }
        if (count > 1) {
            List<String> strayed = new ArrayList<>();
            if (worstNozzle >= 5) strayed.add(String.format(Locale.getDefault(), "the nozzle fell up to %.0f °C below its target", worstNozzle));
            if (worstBed >= 3) strayed.add(String.format(Locale.getDefault(), "the bed fell up to %.0f °C below its target", worstBed));
            out.add(strayed.isEmpty() ? String.format(Locale.getDefault(), "In the %d minutes before, both heaters held their targets.", Math.round(BEFORE_S / 60))
                : String.format(Locale.getDefault(), "In the %d minutes before, %s.", Math.round(BEFORE_S / 60), String.join(" and ", strayed)));
        }
        return out;
    }
    private static void heater(List<String> out, String name, double now, double target) {
        if (Double.isNaN(now)) return;
        out.add(Double.isNaN(target) || target <= 0 ? String.format(Locale.getDefault(), "%s %.0f °C", name, now) : String.format(Locale.getDefault(), "%s %.0f °C (set %.0f)", name, now, target));
    }
    private static JSONObject status(int sub) {
        try { return new JSONObject().put("machine_status", new JSONObject().put("status", 2).put("sub_status", sub)); } catch (org.json.JSONException impossible) { throw new IllegalStateException(impossible); }
    }

    /** What the G-code printed on the layer (printer's, 1-based): its height, the main features by length, and what is hard to print there. */
    static String gcodeWords(GcodeToolpath path, int layer) {
        if (path == null || layer < 1 || layer > path.layerCount) return "";
        int index = layer - 1;
        double[] lengths = path.layerFeatureLengths(index); double total = 0; for (double l : lengths) total += l;
        if (total <= 0) return String.format(Locale.getDefault(), "Layer %d (z %.2f mm) has no extrusion in the G-code.", layer, path.layerZ(index));
        Integer[] order = new Integer[lengths.length]; for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> Double.compare(lengths[b], lengths[a]));
        List<String> main = new ArrayList<>();
        for (int k = 0; k < 3 && lengths[order[k]] / total >= 0.05; k++)
            main.add(String.format(Locale.getDefault(), "%s %d %%", GcodeToolpath.FEATURES[order[k]].toLowerCase(Locale.ROOT), Math.round(lengths[order[k]] / total * 100)));
        StringBuilder out = new StringBuilder(String.format(Locale.getDefault(), "Layer %d of %d (z %.2f mm) is mostly %s.", layer, path.layerCount, path.layerZ(index), String.join(", ", main)));
        List<String> hard = new ArrayList<>();
        String[][] notes = {{"Bridge", "bridges"}, {"Overhang wall", "overhanging walls"}, {"Support", "supports"}, {"Support interface", "support interfaces"}};
        for (String[] n : notes) { int f = feature(n[0]); if (f >= 0 && lengths[f] / total >= 0.02) hard.add(n[1]); }
        if (index > 0) {
            double before = 0, now = total; for (double l : path.layerFeatureLengths(index - 1)) before += l;
            if (before > 0 && now < before * 0.5) hard.add("much less to print than the layer below (a narrowing part, or the top of one)");
        }
        if (path.layerTools(index).size() > 1) hard.add("a filament change");
        if (!hard.isEmpty()) out.append(" It has ").append(String.join(", ", hard)).append(".");
        return out.toString();
    }
    private static int feature(String name) { for (int i = 0; i < GcodeToolpath.FEATURES.length; i++) if (GcodeToolpath.FEATURES[i].equals(name)) return i; return -1; }

    private FailureReport() { }
}
