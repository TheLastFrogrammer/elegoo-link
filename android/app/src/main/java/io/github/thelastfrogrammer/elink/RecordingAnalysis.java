package io.github.thelastfrogrammer.elink;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What a recording says per layer, set against its G-code (LayerProfile): how long each layer took against the file's
 * estimate, the filament the printer reported feeding against what the file plans, the file's settings at the layers the
 * guardian or you flagged, how the warm-up compares with earlier prints, and what the print cost. Pure, for tests.
 */
final class RecordingAnalysis {
    /**
     * Seconds each printer layer took (index = layer - 1), from the readings: from the first reading on a layer to the first
     * on a later one. NaN for layers never seen, and for the last one seen (it has no end in the readings unless the print
     * ended there: then to the last reading).
     */
    static double[] layerSeconds(double[] t, double[] layer, int layers, boolean ended) {
        double[] out = new double[Math.max(0, layers)]; Arrays.fill(out, Double.NaN);
        int current = -1; double since = Double.NaN;
        for (int i = 0; i < t.length; i++) {
            if (Double.isNaN(layer[i]) || layer[i] < 1) continue;
            int now = (int) layer[i];
            if (now != current) {
                if (current >= 1 && current <= out.length && now > current) out[current - 1] = t[i] - since;
                if (now > current) { current = now; since = t[i]; }
            }
        }
        if (ended && current >= 1 && current <= out.length && t.length > 0 && t[t.length - 1] > since) out[current - 1] = t[t.length - 1] - since;
        return out;
    }

    /**
     * Filament the printer reported feeding on each layer (mm, index = layer - 1), from the extruded_mm readings at each
     * layer's first reading; NaN where it was not reported on both sides. The extruder position is what the printer was
     * told to feed, so this follows the file unless the printer ran other code (a purge, a filament change) or readings missed.
     */
    static double[] layerFed(double[] layer, double[] extruded, int layers, boolean ended) {
        double[] out = new double[Math.max(0, layers)]; Arrays.fill(out, Double.NaN);
        int current = -1; double since = Double.NaN, last = Double.NaN;
        for (int i = 0; i < layer.length; i++) {
            if (!Double.isNaN(extruded[i])) last = extruded[i];
            if (Double.isNaN(layer[i]) || layer[i] < 1) continue;
            int now = (int) layer[i];
            if (now > current) {
                if (current >= 1 && current <= out.length && !Double.isNaN(since) && !Double.isNaN(extruded[i])) out[current - 1] = extruded[i] - since;
                current = now; since = extruded[i];
            }
        }
        if (ended && current >= 1 && current <= out.length && !Double.isNaN(since) && !Double.isNaN(last)) out[current - 1] = last - since;
        return out;
    }

    /**
     * The layers that took much longer than the file's estimate: at least twice it and a minute more. "Layers 12 (4:10, file
     * 1:30), 40 (…) took much longer than the file's estimate." or "". Up to five, slowest first.
     */
    static String slowLayers(double[] measured, double[] estimate) {
        List<int[]> slow = new ArrayList<>();
        for (int i = 0; i < Math.min(measured.length, estimate.length); i++)
            if (!Double.isNaN(measured[i]) && estimate[i] > 0 && measured[i] >= 2 * estimate[i] && measured[i] - estimate[i] >= 60) slow.add(new int[] {i, (int) Math.round(measured[i] - estimate[i])});
        if (slow.isEmpty()) return "";
        slow.sort((a, b) -> Integer.compare(b[1], a[1]));
        List<String> words = new ArrayList<>();
        for (int[] s : slow.subList(0, Math.min(5, slow.size()))) words.add((s[0] + 1) + " (" + clock(measured[s[0]]) + ", file " + clock(estimate[s[0]]) + ")");
        return (slow.size() == 1 ? "Layer " : "Layers ") + String.join(", ", words) + (slow.size() > 5 ? " and " + (slow.size() - 5) + " more" : "")
            + (slow.size() == 1 ? " took" : " took") + " much longer than the file's estimate: a pause, a filament change or a slowed print speed shows here.";
    }
    /** The whole print's measured layer time against the file's, in words, or "". */
    static String layerTimeTotals(double[] measured, double[] estimate) {
        double m = 0, e = 0; int n = 0;
        for (int i = 0; i < Math.min(measured.length, estimate.length); i++) if (!Double.isNaN(measured[i]) && estimate[i] > 0) { m += measured[i]; e += estimate[i]; n++; }
        if (n < 3 || e <= 0) return "";
        return String.format(Locale.getDefault(), "Over the %d layers with both: %s printing against the file's %s (%+.0f %%).", n, clock(m), clock(e), (m / e - 1) * 100);
    }

    /** "Fed 12.41 m on the layers seen, the file plans 12.30 m there (+0.9 %)", or "". */
    static String fedTotals(double[] fed, double[] planned) {
        double f = 0, p = 0; int n = 0;
        for (int i = 0; i < Math.min(fed.length, planned.length); i++) if (!Double.isNaN(fed[i]) && planned[i] > 0) { f += fed[i]; p += planned[i]; n++; }
        if (n < 3 || p <= 0) return "";
        return String.format(Locale.getDefault(), "On the %d layers with both, the printer reported feeding %.2f m against the file's %.2f m (%+.1f %%). This is what it was told to feed, not a measurement at the nozzle: a gap means it ran other code (a purge, a filament change) or readings were missed, not a slipping feeder.",
            n, f / 1000, p / 1000, (f / p - 1) * 100);
    }

    /**
     * The file's settings at each layer the guardian raised its alarm on, or you called a real problem: what kind of lines it
     * prints, how fast, and how much flow it asks for. One line each, up to six.
     */
    static List<String> flaggedLayers(LayerCharts.Guardian g, LayerProfile p) {
        Map<Integer, String> why = new LinkedHashMap<>();
        for (double[] a : g.answers) if (a[1] < 0.5) why.put((int) a[0], "you called it a real problem");
        for (double[] c : g.checks) if (c[5] > 0.5) why.putIfAbsent((int) c[0], "the guardian raised its alarm");
        List<String> out = new ArrayList<>();
        for (Map.Entry<Integer, String> e : why.entrySet()) {
            int i = e.getKey() - 1; if (i < 0 || i >= p.layers) continue;
            double total = p.total(i); List<String> parts = new ArrayList<>();
            if (total > 0) {
                Integer[] order = new Integer[LayerProfile.TYPES]; for (int k = 0; k < order.length; k++) order[k] = k;
                Arrays.sort(order, (a, b) -> Double.compare(p.area[i][b], p.area[i][a]));
                for (int k = 0; k < 3; k++) { int type = order[k]; double share = p.area[i][type] / total; if (share >= 0.05) parts.add(GcodeViewerActivity.displayName(type).toLowerCase(Locale.ROOT) + " " + Math.round(share * 100) + " %"); }
            }
            String flow = p.maxFlow > 0 ? String.format(Locale.getDefault(), "highest flow %.1f mm³/s (filament's limit %.0f)", p.flowMax[i], p.maxFlow) : String.format(Locale.getDefault(), "highest flow %.1f mm³/s", p.flowMax[i]);
            out.add(String.format(Locale.getDefault(), "Layer %d (%s): %s; %.0f mm/s on average; %s; acceleration up to %.0f mm/s².",
                e.getKey(), e.getValue(), parts.isEmpty() ? "no lines" : String.join(", ", parts), p.speed[i], flow, p.accelMax[i]));
            if (out.size() == 6) break;
        }
        return out;
    }

    /**
     * Each warm-up step's usual length (the median over earlier prints that had it, s), by step name. `earlier`: each earlier
     * print's warm-up phases as RecordingsActivity.warmUpPhases gives them.
     */
    static Map<String, Double> usualWarmUp(List<List<Object[]>> earlier) {
        Map<String, List<Double>> all = new LinkedHashMap<>();
        for (List<Object[]> phases : earlier) {
            Map<String, Double> each = new LinkedHashMap<>();
            for (Object[] p : phases) each.merge((String) p[2], (Double) p[1] - (Double) p[0], Double::sum);
            for (Map.Entry<String, Double> e : each.entrySet()) all.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue());
        }
        Map<String, Double> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<Double>> e : all.entrySet()) { List<Double> v = e.getValue(); v.sort(Double::compare); out.put(e.getKey(), v.get(v.size() / 2)); }
        return out;
    }
    /** How many earlier prints the usual times rest on, at least, before saying anything. */
    static final int WARM_UP_PRINTS = 3;
    /**
     * This print's warm-up steps that ran much longer than usual (at least 1.5 times and two minutes more), in words; or a
     * line saying it was usual; or "" with too few earlier prints. `counts`: how many earlier prints had each step.
     */
    static String warmUpAgainstUsual(List<Object[]> phases, Map<String, Double> usual, Map<String, Integer> counts) {
        if (phases.isEmpty()) return "";
        Map<String, Double> each = new LinkedHashMap<>();
        for (Object[] p : phases) each.merge((String) p[2], (Double) p[1] - (Double) p[0], Double::sum);
        List<String> long_ = new ArrayList<>(); boolean compared = false;
        for (Map.Entry<String, Double> e : each.entrySet()) {
            Double was = usual.get(e.getKey()); Integer n = counts.get(e.getKey());
            if (was == null || n == null || n < WARM_UP_PRINTS) continue;
            compared = true;
            if (e.getValue() >= 1.5 * was && e.getValue() - was >= 120) long_.add(e.getKey() + " took " + ChartView.spanWords(e.getValue()) + " (usually " + ChartView.spanWords(was) + ")");
        }
        if (!compared) return "";
        return long_.isEmpty() ? "The warm-up took about as long as usual." : "Longer than usual: " + String.join("; ", long_) + ". A heater, the bed's levelling or the room's temperature can be behind it; worth a look if it keeps happening.";
    }

    /**
     * What the print cost: filament (grams at the price per kg) and, when the printer's average power and the electricity
     * price are set, the energy over its time. "" when there is no price to go on.
     */
    static String cost(double grams, double pricePerKg, double hours, double watts, double pricePerKwh) {
        java.text.NumberFormat money = java.text.NumberFormat.getCurrencyInstance(Locale.getDefault());
        boolean filament = pricePerKg > 0 && grams > 0, energy = watts > 0 && pricePerKwh > 0 && hours > 0;
        if (!filament && !energy) return "";
        double f = filament ? grams / 1000 * pricePerKg : 0, kwh = energy ? watts * hours / 1000 : 0, e = kwh * pricePerKwh;
        List<String> parts = new ArrayList<>();
        if (filament) parts.add(String.format(Locale.getDefault(), "filament %s (%.0f g)", money.format(f), grams));
        if (energy) parts.add(String.format(Locale.getDefault(), "energy %s (%.2f kWh at %.0f W)", money.format(e), kwh, watts));
        return "Cost: about " + money.format(f + e) + " · " + String.join(", ", parts) + (energy ? "" : " · add the printer's power in History › Prices for energy too");
    }

    /**
     * What the readings point to, in words: layers that cooled much longer than the ones before them with the part fan high
     * (a weak bond or a visible line at that height is likely), long stalls, and the nozzle or bed falling well below its
     * target while printing. Each says where (layer, height, time) and what usually causes it. Empty when nothing stands out.
     */
    static List<String> diagnose(double[] t, double[] layer, double[] fan, double[] z, double[] nozzle, double[] nozzleTarget, double[] bed, double[] bedTarget, double[] sub, boolean ended) {
        List<String> out = new ArrayList<>();
        int layers = lastLayer(layer);
        if (layers >= 4) {
            double[] took = layerSeconds(t, layer, layers, ended), fans = perLayerMean(layer, fan, layers), height = perLayerMin(layer, z, layers);
            for (int i = 3; i < layers; i++) {
                if (Double.isNaN(took[i])) continue;
                List<Double> before = new ArrayList<>();
                for (int k = Math.max(0, i - 10); k < i; k++) if (!Double.isNaN(took[k])) before.add(took[k]);
                if (before.size() < 3) continue;
                before.sort(Double::compare); double usual = before.get(before.size() / 2);
                String at = "Layer " + (i + 1) + (Double.isNaN(height[i]) ? "" : String.format(Locale.getDefault(), " (%.1f mm up)", height[i]));
                if (took[i] >= Math.max(300, 10 * usual)) out.add(at + " took " + ChartView.spanWords(took[i]) + " against about " + clock(usual) + " for the layers before: a pause or a stall. A line or a blob at that height usually comes from it.");
                else if (took[i] >= Math.max(30, 2.5 * usual) && !Double.isNaN(fans[i]) && fans[i] >= 70)
                    out.add(at + " took " + clock(took[i]) + " against about " + clock(usual) + " for the layers before, with the part fan at " + Math.round(fans[i]) + " %: the wall cooled much longer than usual, so the layer above may bond weakly there (a crack or line around the part). Less fan, a little more heat, or a minimum layer time evens it out.");
                if (out.size() >= 4) break;
            }
        }
        String nozzleDrop = drop(t, layer, nozzle, nozzleTarget, sub, 8), bedDrop = drop(t, layer, bed, bedTarget, sub, 5);
        if (!nozzleDrop.isEmpty()) out.add("The nozzle fell " + nozzleDrop + " while printing: a draught or a fan blowing on the heater block, or the heater losing ground at high flow. Under-extruded lines are likely there.");
        if (!bedDrop.isEmpty()) out.add("The bed fell " + bedDrop + " while printing: a draught, or the bed heater struggling. Warping or a part letting go can follow.");
        return out;
    }
    /** "to 195 °C (target 210) at 1:12" for the worst reading more than `margin` below a target while printing, or "". */
    private static String drop(double[] t, double[] layer, double[] value, double[] target, double[] sub, double margin) {
        int worst = -1; double most = margin;
        for (int i = 0; i < t.length; i++) {
            if (Double.isNaN(value[i]) || Double.isNaN(target[i]) || target[i] <= 0 || Double.isNaN(layer[i]) || layer[i] < 1) continue;
            if (!Double.isNaN(sub[i]) && (int) sub[i] != 2075) continue;   // only while printing: not warm-up, pauses or the end
            double below = target[i] - value[i];
            if (below > most) { most = below; worst = i; }
        }
        return worst < 0 ? "" : String.format(Locale.getDefault(), "to %.0f °C (target %.0f) at %s, layer %d", value[worst], target[worst], ChartView.elapsed(t[worst]), (int) layer[worst]);
    }
    /** The lowest of a column's readings on each layer (index = layer - 1), NaN where none. */
    static double[] perLayerMin(double[] layer, double[] values, int layers) {
        double[] out = new double[Math.max(0, layers)]; Arrays.fill(out, Double.NaN);
        for (int i = 0; i < Math.min(layer.length, values.length); i++) {
            if (Double.isNaN(layer[i]) || Double.isNaN(values[i]) || layer[i] < 1 || layer[i] > layers) continue;
            int k = (int) layer[i] - 1; out[k] = Double.isNaN(out[k]) ? values[i] : Math.min(out[k], values[i]);
        }
        return out;
    }

    /** The mean of a column's readings on each layer (index = layer - 1), NaN where a layer had none. */
    static double[] perLayerMean(double[] layer, double[] values, int layers) {
        double[] sum = new double[Math.max(0, layers)], out = new double[Math.max(0, layers)]; int[] n = new int[Math.max(0, layers)];
        for (int i = 0; i < Math.min(layer.length, values.length); i++) {
            if (Double.isNaN(layer[i]) || Double.isNaN(values[i]) || layer[i] < 1 || layer[i] > layers) continue;
            int k = (int) layer[i] - 1; sum[k] += values[i]; n[k]++;
        }
        for (int k = 0; k < out.length; k++) out[k] = n[k] == 0 ? Double.NaN : sum[k] / n[k];
        return out;
    }
    /** The highest layer in the readings, or 0. */
    static int lastLayer(double[] layer) { double top = 0; for (double v : layer) if (!Double.isNaN(v)) top = Math.max(top, v); return (int) top; }
    /** The picture taken at a layer: the last one at or below it, else the first one above; -1 with none. `layers` from RecordingsActivity.pictureLayers. */
    static int pictureAtLayer(double[] layers, int layer) {
        int below = -1, above = -1;
        for (int i = 0; i < layers.length; i++) {
            if (Double.isNaN(layers[i])) continue;
            if (layers[i] <= layer) { if (below < 0 || layers[i] >= layers[below]) below = i; }
            else if (above < 0 || layers[i] < layers[above]) above = i;
        }
        return below >= 0 ? below : above;
    }

    static String clock(double seconds) { long s = Math.round(seconds); return s >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60) : String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60); }

    private RecordingAnalysis() { }
}
