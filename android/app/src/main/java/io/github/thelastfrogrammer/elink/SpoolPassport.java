package io.github.thelastfrogrammer.elink;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A spool's calibration results, kept with the spool: the flow ratio, pressure advance, temperatures, top volumetric speed
 * and retraction found for it. When that spool's tray is chosen for a filament on the Slice screen they are offered, and
 * set only when the person taps; slice settings changed on the Slice screen can be kept with the spool the same way.
 * Values are the slicer's own filament settings, as text. Pure.
 */
final class SpoolPassport {
    /** Key, label, unit, lowest and highest accepted value. */
    static final String[][] KEYS = {
        {"filament_flow_ratio", "Flow ratio", "", "0.5", "1.5"},
        {"pressure_advance", "Pressure advance", "", "0", "0.5"},
        {"nozzle_temperature", "Nozzle temperature", "°C", "150", "350"},
        {"nozzle_temperature_initial_layer", "First layer nozzle temperature", "°C", "150", "350"},
        {"filament_max_volumetric_speed", "Max volumetric speed", "mm³/s", "1", "60"},
        {"filament_retraction_length", "Retraction length", "mm", "0", "10"},
    };
    static final String PRESSURE_ON = "enable_pressure_advance";

    static String[] spec(String key) { for (String[] k : KEYS) if (k[0].equals(key)) return k; return null; }

    /** A value checked against its range, as the slicer takes it; null when it is not a number or out of range. */
    static String accept(String key, String text) {
        String[] k = spec(key); if (k == null || text == null) return null;
        try {
            double v = Double.parseDouble(text.trim().replace(',', '.'));
            if (!Double.isFinite(v) || v < Double.parseDouble(k[3]) || v > Double.parseDouble(k[4])) return null;
            return v == Math.rint(v) && Math.abs(v) >= 10 ? String.valueOf((long) v) : trim(v);
        } catch (NumberFormatException notNumber) { return null; }
    }
    private static String trim(double v) { String s = String.format(Locale.ROOT, "%.4f", v); return s.replaceFirst("0+$", "").replaceFirst("\\.$", ""); }

    /** The passport's part of a set of slice changes: only the keys a passport keeps, with accepted values. */
    static Map<String, String> from(Map<String, String> edits) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String[] k : KEYS) { String v = edits.get(k[0]); String ok = v == null ? null : accept(k[0], firstValue(v)); if (ok != null) out.put(k[0], ok); }
        return out;
    }
    /** A per-filament setting may arrive as a list ("0.98,0.98"): the first value. */
    private static String firstValue(String v) { int comma = v.indexOf(','); return comma < 0 ? v : v.substring(0, comma); }

    /** The slice changes that setting the passport makes: its values (with pressure advance turned on when it has one). */
    static Map<String, String> changes(Map<String, String> passport) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String[] k : KEYS) if (passport.containsKey(k[0])) out.put(k[0], passport.get(k[0]));
        if (out.containsKey("pressure_advance")) out.put(PRESSURE_ON, "1");
        return out;
    }
    /** Whether the slot's changes already carry everything in the passport. */
    static boolean applied(Map<String, String> passport, Map<String, String> edits) {
        if (passport.isEmpty()) return false;
        for (Map.Entry<String, String> e : changes(passport).entrySet()) {
            String have = edits.get(e.getKey()); if (have == null) return false;
            if (!e.getKey().equals(PRESSURE_ON) && !same(have, e.getValue())) return false;
            if (e.getKey().equals(PRESSURE_ON) && !"1".equals(firstValue(have).trim()) && !"true".equalsIgnoreCase(firstValue(have).trim())) return false;
        }
        return true;
    }
    /** Whether the slot's changes hold passport values that differ from what is kept (something worth keeping). */
    static boolean differs(Map<String, String> passport, Map<String, String> edits) {
        Map<String, String> mine = from(edits); if (mine.isEmpty()) return false;
        for (Map.Entry<String, String> e : mine.entrySet()) if (!passport.containsKey(e.getKey()) || !same(passport.get(e.getKey()), e.getValue())) return true;
        return false;
    }
    private static boolean same(String a, String b) {
        try { return Math.abs(Double.parseDouble(firstValue(a).trim()) - Double.parseDouble(firstValue(b).trim())) < 1e-9; } catch (NumberFormatException text) { return a.trim().equals(b.trim()); }
    }

    /** "flow 0.97, pressure advance 0.032, 215 °C (first layer 220 °C)", or "" when empty. */
    static String words(Map<String, String> passport) {
        List<String> out = new ArrayList<>();
        if (passport.containsKey("filament_flow_ratio")) out.add("flow " + passport.get("filament_flow_ratio"));
        if (passport.containsKey("pressure_advance")) out.add("pressure advance " + passport.get("pressure_advance"));
        String nozzle = passport.get("nozzle_temperature"), first = passport.get("nozzle_temperature_initial_layer");
        if (nozzle != null) out.add(nozzle + " °C" + (first != null && !same(first, nozzle) ? " (first layer " + first + " °C)" : ""));
        else if (first != null) out.add("first layer " + first + " °C");
        if (passport.containsKey("filament_max_volumetric_speed")) out.add("up to " + passport.get("filament_max_volumetric_speed") + " mm³/s");
        if (passport.containsKey("filament_retraction_length")) out.add("retraction " + passport.get("filament_retraction_length") + " mm");
        return String.join(", ", out);
    }

    private SpoolPassport() { }
}
