package io.github.thelastfrogrammer.elink;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The tuning loop: what a recording's findings (RecordingAnalysis.findings) suggest changing in the slicer, starting from
 * the values the print's own G-code was sliced with; the changes are only ever applied on the slicer screen, on top of
 * the presets (which stay as they are), after the person's tap. Each re-slice is kept as a trial for that file name, so
 * the next recording of it can be set against the print before and say whether what was found went away.
 */
final class Tuning {
    static final String PREFS = "tuning", TRIALS = "trials";
    static final int KEEP = 100;

    /** The settings offered: config key, everyday name, unit, lowest and highest sane value. */
    static final String[][] SETTINGS = {
        {"fan_max_speed", "Part fan on short layers", "%", "0", "100"},
        {"fan_min_speed", "Part fan on long layers", "%", "0", "100"},
        {"nozzle_temperature", "Nozzle temperature", "°C", "150", "350"},
        {"slow_down_layer_time", "Minimum layer time", "s", "0", "120"},
        {"top_shell_layers", "Top layers", "", "0", "30"},
        {"filament_flow_ratio", "Flow ratio", "", "0.8", "1.2"},
        {"filament_max_volumetric_speed", "Highest flow", "mm³/s", "1", "60"},
    };
    /** Also read from the file, to keep suggestions inside what the filament allows. */
    private static final String[] ALSO = {"nozzle_temperature_range_high", "filament_settings_id", "print_settings_id", "filament_type"};

    static String[] setting(String key) { for (String[] s : SETTINGS) if (s[0].equals(key)) return s; return null; }

    /** One setting to change: from the file's value to the new one (per filament, comma-separated, as the file has it), and why. */
    static final class Change {
        final String key, from, to, why;
        Change(String key, String from, String to, String why) { this.key = key; this.from = from; this.to = to; this.why = why; }
        /** "Part fan on short layers 100 → 70 %". */
        String words() {
            String[] s = setting(key); String unit = s == null || s[2].isEmpty() ? "" : " " + s[2];
            return (s == null ? key : s[1]) + " " + (from == null || from.isEmpty() ? "" : from + " → ") + to + unit;
        }
        JSONObject json() throws JSONException { return new JSONObject().put("key", key).put("from", from == null ? "" : from).put("to", to).put("why", why == null ? "" : why); }
        static Change of(JSONObject o) { return new Change(o.optString("key"), o.optString("from"), o.optString("to"), o.optString("why")); }
    }

    /**
     * The settings a sliced file was made with, from the settings block the slicer writes at its end (read from its last
     * 512 KiB only): the offered keys and a few more, as written ("210,215" for two filaments). Empty when it has none.
     */
    static Map<String, String> config(File gcode) {
        Map<String, String> out = new LinkedHashMap<>();
        if (gcode == null || !gcode.isFile()) return out;
        try (RandomAccessFile in = new RandomAccessFile(gcode, "r")) {
            long size = in.length(), from = Math.max(0, size - 512 * 1024);
            byte[] tail = new byte[(int) (size - from)]; in.seek(from); in.readFully(tail);
            String text = new String(tail, StandardCharsets.UTF_8);
            int start = text.lastIndexOf("; CONFIG_BLOCK_START"), end = text.lastIndexOf("; CONFIG_BLOCK_END");
            if (start < 0 || end < start) return out;
            for (String line : text.substring(start, end).split("\n")) {
                int eq = line.indexOf(" = "); if (!line.startsWith("; ") || eq < 0) continue;
                String key = line.substring(2, eq).trim(), value = line.substring(eq + 3).trim();
                if (value.length() > 200) continue;
                if (setting(key) != null || java.util.Arrays.asList(ALSO).contains(key)) out.put(key, value.replace("\"", ""));
            }
        } catch (Exception unreadable) { out.clear(); }
        return out;
    }

    /** What the findings suggest changing, from the file's values; the same setting is suggested once. Pure. */
    static List<Change> suggest(List<RecordingAnalysis.Finding> found, Map<String, String> config) {
        Map<String, Change> out = new LinkedHashMap<>();
        for (RecordingAnalysis.Finding f : found) {
            if (RecordingAnalysis.Finding.COOLING.equals(f.kind)) {
                String why = "Layer " + f.layer + " cooled much longer than the layers before it with the fan high";
                double[] fanMax = numbers(config.get("fan_max_speed"));
                if (fanMax.length > 0 && max(fanMax) > 60) {
                    double[] to = new double[fanMax.length]; for (int i = 0; i < to.length; i++) to[i] = Math.max(40, Math.round(fanMax[i] * 0.7 / 5) * 5.0);
                    put(out, new Change("fan_max_speed", config.get("fan_max_speed"), join(to, 0), why));
                    double[] fanMin = numbers(config.get("fan_min_speed"));
                    if (fanMin.length == to.length) {
                        boolean lower = false; double[] min = fanMin.clone();
                        for (int i = 0; i < min.length; i++) if (min[i] > to[i]) { min[i] = to[i]; lower = true; }
                        if (lower) put(out, new Change("fan_min_speed", config.get("fan_min_speed"), join(min, 0), "Kept at or below the fan on short layers"));
                    }
                }
                warmer(out, config, why);
            } else if (RecordingAnalysis.Finding.NOZZLE_DROP.equals(f.kind)) {
                String why = "The nozzle could not hold its temperature while printing";
                double[] flow = numbers(config.get("filament_max_volumetric_speed"));
                if (flow.length > 0) {
                    double[] to = new double[flow.length]; for (int i = 0; i < to.length; i++) to[i] = Math.max(1, Math.round(flow[i] * 0.8 * 2) / 2.0);
                    put(out, new Change("filament_max_volumetric_speed", config.get("filament_max_volumetric_speed"), join(to, 1), why));
                }
                warmer(out, config, why);
            }
            // A stall or a bed falling in a draught has no slicer setting that fixes it.
        }
        return new ArrayList<>(out.values());
    }
    private static void warmer(Map<String, Change> out, Map<String, String> config, String why) {
        double[] nozzle = numbers(config.get("nozzle_temperature")), high = numbers(config.get("nozzle_temperature_range_high"));
        if (nozzle.length == 0) return;
        double[] to = new double[nozzle.length]; boolean any = false;
        for (int i = 0; i < to.length; i++) {
            double limit = i < high.length ? high[i] : high.length > 0 ? high[0] : nozzle[i] + 5;
            to[i] = Math.min(nozzle[i] + 5, Math.max(nozzle[i], limit)); any |= to[i] > nozzle[i];
        }
        if (any) put(out, new Change("nozzle_temperature", config.get("nozzle_temperature"), join(to, 0), why));
    }
    private static void put(Map<String, Change> out, Change c) { if (!out.containsKey(c.key) && !c.to.equals(c.from)) out.put(c.key, c); }

    /** Words for each finding that no setting addresses, so the card can say so instead of offering nothing. Pure. */
    static String noSetting(String kind) {
        if (RecordingAnalysis.Finding.STALL.equals(kind)) return "A stall or pause is not a slicer setting: look at what the printer was doing then.";
        if (RecordingAnalysis.Finding.BED_DROP.equals(kind)) return "A bed losing heat is a draught or the heater, not a slicer setting: keep the lid and door closed.";
        return null;
    }

    /** Why a typed value cannot be used, or null when it can (one number per filament, inside the setting's range). Pure. */
    static String check(String key, String value) {
        String[] s = setting(key); if (s == null) return "Not a setting the tuning offers.";
        String[] parts = value.split(","); if (value.trim().isEmpty() || parts.length > 16) return s[1] + ": type a number.";
        double low = Double.parseDouble(s[3]), high = Double.parseDouble(s[4]);
        for (String p : parts) {
            double v; try { v = Double.parseDouble(p.trim()); } catch (NumberFormatException no) { return s[1] + ": \"" + p.trim() + "\" is not a number."; }
            if (!(v >= low && v <= high)) return String.format(Locale.getDefault(), "%s: keep it between %s and %s%s.", s[1], s[3], s[4], s[2].isEmpty() ? "" : " " + s[2]);
            if (key.equals("top_shell_layers") && v != Math.rint(v)) return s[1] + ": a whole number.";
        }
        return null;
    }

    static double[] numbers(String value) {
        if (value == null || value.trim().isEmpty()) return new double[0];
        String[] parts = value.replace("%", "").split(",");
        double[] out = new double[parts.length];
        try { for (int i = 0; i < parts.length; i++) out[i] = Double.parseDouble(parts[i].trim()); } catch (NumberFormatException no) { return new double[0]; }
        return out;
    }
    private static double max(double[] v) { double m = Double.NEGATIVE_INFINITY; for (double x : v) m = Math.max(m, x); return m; }
    private static String join(double[] v, int decimals) {
        StringBuilder out = new StringBuilder();
        for (double x : v) { if (out.length() > 0) out.append(','); out.append(decimals == 0 || x == Math.rint(x) ? String.valueOf(Math.round(x)) : String.format(Locale.ROOT, "%." + decimals + "f", x)); }
        return out.toString();
    }

    // ------------------------------------------------------------------ trials

    /** One re-slice: the file it made, when, the recording it came from, the changes and the findings they were for. */
    static final class Trial {
        long at; String file = "", basedOn = "", basedOnName = "", result = "", resultFor = "";
        final List<Change> changes = new ArrayList<>(); final List<String> kinds = new ArrayList<>();
        JSONObject json() throws JSONException {
            JSONArray c = new JSONArray(); for (Change x : changes) c.put(x.json());
            return new JSONObject().put("at", at).put("file", file).put("basedOn", basedOn).put("basedOnName", basedOnName).put("result", result).put("resultFor", resultFor)
                .put("changes", c).put("kinds", new JSONArray(kinds));
        }
        static Trial of(JSONObject o) {
            Trial t = new Trial(); t.at = o.optLong("at"); t.file = o.optString("file"); t.basedOn = o.optString("basedOn"); t.basedOnName = o.optString("basedOnName");
            t.result = o.optString("result"); t.resultFor = o.optString("resultFor");
            JSONArray c = o.optJSONArray("changes"); for (int i = 0; c != null && i < c.length(); i++) if (c.optJSONObject(i) != null) t.changes.add(Change.of(c.optJSONObject(i)));
            JSONArray k = o.optJSONArray("kinds"); for (int i = 0; k != null && i < k.length(); i++) t.kinds.add(k.optString(i));
            return t;
        }
        /** "Part fan on short layers 100 → 70 %, Nozzle temperature 210 → 215 °C". */
        String changeWords() { List<String> w = new ArrayList<>(); for (Change c : changes) w.add(c.words()); return String.join(", ", w); }
    }

    /** What the person asked the slicer to carry: the file name it is for, the recording and the changes (as an intent extra). */
    static String request(String file, PrintRecorder.Recording basedOn, String basedOnName, List<Change> changes, List<String> kinds) {
        try {
            Trial t = new Trial(); t.file = file == null ? "" : file; t.basedOn = basedOn == null ? "" : basedOn.meta.getAbsolutePath(); t.basedOnName = basedOnName == null ? "" : basedOnName;
            t.changes.addAll(changes); t.kinds.addAll(kinds);
            return t.json().toString();
        } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }
    static Trial parse(String json) { try { return json == null ? null : Trial.of(new JSONObject(json)); } catch (JSONException unreadable) { return null; } }

    private static SharedPreferences prefs(Context context) { return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
    static synchronized List<Trial> all(Context context) {
        List<Trial> out = new ArrayList<>();
        try { JSONArray a = new JSONArray(prefs(context).getString(TRIALS, "[]")); for (int i = 0; i < a.length(); i++) if (a.optJSONObject(i) != null) out.add(Trial.of(a.optJSONObject(i))); }
        catch (JSONException unreadable) { }
        return out;
    }
    private static synchronized void save(Context context, List<Trial> trials) {
        JSONArray a = new JSONArray();
        try { for (Trial t : trials.subList(Math.max(0, trials.size() - KEEP), trials.size())) a.put(t.json()); } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
        prefs(context).edit().putString(TRIALS, a.toString()).apply();
    }
    /** Keeps a re-slice (the slicer calls this when the slice is done). */
    static synchronized void remember(Context context, Trial trial) { List<Trial> all = all(context); all.add(trial); save(context, all); }
    /** Notes how a trial went, once the print after it has been looked at. */
    static synchronized void result(Context context, Trial trial, String result, PrintRecorder.Recording after) {
        List<Trial> all = all(context);
        for (Trial t : all) if (t.at == trial.at && t.file.equals(trial.file)) { t.result = result; t.resultFor = after.meta.getAbsolutePath(); }
        save(context, all);
    }

    /** The re-slice this recording printed: the newest trial for its file name made before it started, and not from it. Pure over the list. */
    static Trial trialFor(List<Trial> all, PrintRecorder.Recording recording) {
        String base = GcodeLibrary.baseName(recording.file); Trial best = null;
        if (base == null) return null;
        for (Trial t : all)
            if (base.equals(t.file) && t.at < recording.start && !t.basedOn.equals(recording.meta.getAbsolutePath()) && (best == null || t.at > best.at)) best = t;
        return best;
    }
    /** The re-slices made from this recording's findings, oldest first. Pure over the list. */
    static List<Trial> triedFrom(List<Trial> all, PrintRecorder.Recording recording) {
        List<Trial> out = new ArrayList<>();
        for (Trial t : all) if (t.basedOn.equals(recording.meta.getAbsolutePath())) out.add(t);
        return out;
    }

    /** Whether each finding a trial was for came back in the print after it, in words; "" when it was for none. Pure. */
    static String outcome(Trial trial, List<RecordingAnalysis.Finding> now, boolean finished) {
        List<String> words = new ArrayList<>();
        for (String kind : new java.util.LinkedHashSet<>(trial.kinds)) {
            RecordingAnalysis.Finding again = null; for (RecordingAnalysis.Finding f : now) if (f.kind.equals(kind)) { again = f; break; }
            String what = what(kind);
            if (again == null) words.add(finished ? what + ": not seen this time." : what + ": not seen so far (the print did not finish).");
            else words.add(what + ": seen again" + (again.layer > 0 ? " on layer " + again.layer : "") + ".");
        }
        return String.join(" ", words);
    }
    /** Whether none of the trial's findings came back. Pure. */
    static boolean helped(Trial trial, List<RecordingAnalysis.Finding> now) {
        for (RecordingAnalysis.Finding f : now) if (trial.kinds.contains(f.kind)) return false;
        return !trial.kinds.isEmpty();
    }
    static String what(String kind) {
        switch (kind) {
            case RecordingAnalysis.Finding.COOLING: return "A layer cooling much longer than the rest";
            case RecordingAnalysis.Finding.NOZZLE_DROP: return "The nozzle falling below its temperature";
            case RecordingAnalysis.Finding.BED_DROP: return "The bed falling below its temperature";
            case RecordingAnalysis.Finding.STALL: return "A stall";
            default: return "Settings tried by hand";
        }
    }

    private Tuning() { }
}
