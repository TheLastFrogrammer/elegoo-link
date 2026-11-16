package io.github.thelastfrogrammer.elink;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * How hard a print pushes the hot end: each layer's highest flow (mm³/s, from its G-code: LayerProfile) set against the
 * filament's limit from the file's own settings (filament_max_volumetric_speed). Layers near the limit are where the heater
 * can lose ground and lines come out thin; a nozzle that fell below its temperature on a layer that asks for little flow
 * points elsewhere (a draught, a fan on the heater block). Pure.
 */
final class FlowCheck {
    /** At or above this share of the limit a layer is near it; at or above TIED a nozzle drop there is likely the flow's doing. */
    static final double NEAR = 0.9, TIED = 0.8;

    /** The layers (1-based) whose highest flow is at least NEAR of the limit; empty without a limit. */
    static List<Integer> nearLimit(LayerProfile p) {
        List<Integer> out = new ArrayList<>();
        if (p == null || p.maxFlow <= 0) return out;
        for (int i = 0; i < p.layers; i++) if (p.flowMax[i] >= NEAR * p.maxFlow) out.add(i + 1);
        return out;
    }
    /** "3–14, 20, 31–33". */
    static String ranges(List<Integer> layers) {
        StringBuilder out = new StringBuilder();
        for (int k = 0; k < layers.size(); k++) {
            int start = layers.get(k), end = start;
            while (k + 1 < layers.size() && layers.get(k + 1) == end + 1) end = layers.get(++k);
            if (out.length() > 0) out.append(", ");
            out.append(start == end ? String.valueOf(start) : start + "–" + end);
        }
        return out.toString();
    }

    /** After slicing: which layers run near the filament's limit, or "" when none do (or the file does not say its limit). */
    static String sliceWords(LayerProfile p) {
        List<Integer> near = nearLimit(p);
        if (near.isEmpty()) return "";
        double top = 0; for (int layer : near) top = Math.max(top, p.flowMax[layer - 1]);
        return String.format(Locale.getDefault(), "Flow: %d %s (%s) %s up to %.1f mm³/s, %d %% of this filament's %.0f mm³/s limit. The hot end works near its limit there: if those layers come out thin or rough, a lower flow limit or speed evens it out (the tuning loop can set it after the print).",
            near.size(), near.size() == 1 ? "layer" : "layers", ranges(near), near.size() == 1 ? "asks for" : "ask for", top, Math.round(top / p.maxFlow * 100), p.maxFlow);
    }

    /**
     * For a nozzle that fell below its temperature on `layer`: whether that layer asked for much flow. "" when the G-code does
     * not say (no limit, no such layer).
     */
    static String dropWords(LayerProfile p, int layer) {
        if (p == null || p.maxFlow <= 0 || layer < 1 || layer > p.layers) return "";
        double flow = p.flowMax[layer - 1], share = flow / p.maxFlow;
        if (share >= TIED) return String.format(Locale.getDefault(), "Layer %d asks for up to %.1f mm³/s, %d %% of the filament's %.0f mm³/s limit: the heater most likely lost ground to the flow, so a lower flow limit is the likely fix.",
            layer, flow, Math.round(share * 100), p.maxFlow);
        return String.format(Locale.getDefault(), "Layer %d asks for only %.1f mm³/s (%d %% of the filament's limit): flow is not the likely cause. Look for a draught, or the part fan blowing on the heater block (a missing silicone sock does this).",
            layer, flow, Math.round(share * 100));
    }

    private FlowCheck() { }
}
