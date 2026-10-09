package io.github.thelastfrogrammer.elink;

import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Choosing which loaded CANVAS tray each filament of a print comes from: what the file was sliced for (per G-code tool),
 * suggestions that match material first and colour second, and a plain verdict for every choice. Suggestions only fill the
 * form; the user still confirms every tray. Independent of Android.
 */
final class FilamentMatch {
    /** Material families, longest names first so "PETG" is not read as "PET" and "PLA-CF" keeps its fibre note. */
    private static final String[] FAMILIES = {"PCTG", "PETG", "PET", "PLA", "ABS", "ASA", "TPU", "TPE", "PVA", "HIPS", "PPS", "PA", "PC", "PP", "BVOH"};

    /** What the file wants from one tool: material and colour, either of which may be unknown. */
    static final class Need {
        final int t; final String type, colour, label, source;
        Need(int t, String type, String colour, String label, String source) {
            this.t = t; this.type = type == null ? "" : type.trim(); this.colour = TrayPlan.colour(colour); this.label = label == null ? "" : label.trim(); this.source = source;
        }
        boolean known() { return !type.isEmpty() || colour != null; }
        /** "PLA · red", "PLA", "red", or "" when the file does not say. */
        String describe() {
            String name = WorkshopUi.colourName(colour);
            String material = !label.isEmpty() ? label : type;
            return material.isEmpty() ? (name == null ? "" : name) : name == null ? material : material + " · " + name;
        }
    }

    enum Level { OK, CHECK, WRONG }
    static final class Verdict {
        final Level level; final String text;
        Verdict(Level level, String text) { this.level = level; this.text = text; }
    }

    // ------------------------------------------------------------------ what the file wants
    /** The family of a material name or preset ("Elegoo PLA Matte @ECC2" → "PLA", "PETG-CF" → "PETG"), or "" if none is named. */
    static String family(String name) {
        if (name == null) return "";
        String upper = " " + name.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9+]+", " ") + " ";
        for (String family : FAMILIES)
            if (upper.matches(".*[ ]" + family + "(\\+|[ ]|CF|GF|HF|[0-9]).*")) return family;
        return "";
    }
    /** Fibre-filled (CF/GF) materials, which wear a standard nozzle. */
    static boolean filled(String name) { return name != null && name.toUpperCase(Locale.ROOT).matches(".*(CF|GF)\\b.*"); }

    /** Needs from a plan saved when the file was sliced on this phone. */
    static List<Need> fromPlan(TrayPlan plan) {
        List<Need> needs = new ArrayList<>();
        if (plan == null) return needs;
        for (int t = 0; t < plan.count; t++) {
            TrayPlan.Need saved = plan.need(t);
            if (saved != null) needs.add(new Need(t, family(saved.preset), saved.colour, shortPreset(saved.preset), "your slice on this phone"));
        }
        return needs;
    }
    /** Needs from the slicer comments of a file inspected on this phone (filament_type / filament_colour, by position). */
    static List<Need> fromComments(SlicedMaterials materials, int count) {
        List<Need> needs = new ArrayList<>();
        if (materials == null) return needs;
        for (SlicedMaterials.Entry entry : materials.entries)
            if (entry.index < count && (entry.type != null || entry.color != null)) needs.add(new Need(entry.index, entry.type, entry.color, entry.type, "the file's slicer notes"));
        return needs;
    }
    /**
     * Needs from the printer's own file details ("color_map"), whose exact form is not documented: a list of objects with a
     * tool number (t, tool, index, id or slot) and a colour and/or material, read loosely; anything else gives nothing.
     */
    static List<Need> fromColorMap(Object colorMap) {
        List<Need> needs = new ArrayList<>();
        JSONArray list = colorMap instanceof JSONArray ? (JSONArray) colorMap : null;
        if (list == null && colorMap instanceof String) { try { list = new JSONArray((String) colorMap); } catch (Exception notAList) { return needs; } }
        if (list == null) return needs;
        for (int i = 0; i < list.length() && i < TrayPlan.MAX_TOOLS; i++) {
            JSONObject item = list.optJSONObject(i); if (item == null) continue;
            int t = i;
            for (String key : new String[] {"t", "tool", "index", "id", "slot"}) if (item.has(key)) { t = item.optInt(key, i); break; }
            if (t < 0 || t >= TrayPlan.MAX_TOOLS) continue;
            String colour = first(item, "color", "colour", "filament_color", "filament_colour");
            String type = first(item, "type", "filament_type", "material", "name");
            Need need = new Need(t, StatusPresentation.clean(type), colour, StatusPresentation.clean(type), "the printer's file details");
            if (need.known()) needs.add(need);
        }
        return needs;
    }
    /** The first source that says anything, by tool; later sources fill tools the earlier ones do not cover. */
    @SafeVarargs static Need[] merge(int count, List<Need>... sources) {
        Need[] byTool = new Need[count];
        for (List<Need> source : sources) for (Need need : source) if (need.t < count && byTool[need.t] == null && need.known()) byTool[need.t] = need;
        return byTool;
    }

    // ------------------------------------------------------------------ matching
    /** How well a tray fits a need: material decides, colour breaks ties; negative = a different material. */
    static double score(Need need, TrayPlan.Tray tray) {
        if (need == null || !need.known()) return 0;
        double score = 0;
        if (!need.type.isEmpty()) {
            String wanted = family(need.type), loaded = family(tray.type + " " + tray.name);
            if (wanted.isEmpty() || loaded.isEmpty()) score += 10;                 // cannot tell
            else if (!wanted.equals(loaded)) return -100;
            else score += 60 + (sameVariant(need.type, tray.type + " " + tray.name) ? 10 : 0);
        }
        if (need.colour != null && tray.colour != null) score += Math.max(0, 40 - distance(need.colour, tray.colour) / 2.5);
        return score;
    }
    private static boolean sameVariant(String a, String b) {
        String x = a.toUpperCase(Locale.ROOT), y = b.toUpperCase(Locale.ROOT);
        for (String word : new String[] {"MATTE", "SILK", "CF", "GF", "HS", "HF", "+", "PRO", "TRANSLUCENT", "GALAXY", "WOOD", "MARBLE"})
            if (x.contains(word) != y.contains(word)) return false;
        return true;
    }

    /**
     * A tray for each tool (index into `trays`, or -1): the best-scoring pairs first, each tray used once while trays last,
     * and never a tray of another material. Tools whose needs are unknown are left empty.
     */
    static int[] suggest(Need[] needs, List<TrayPlan.Tray> trays) {
        int[] chosen = new int[needs.length]; Arrays.fill(chosen, -1);
        boolean[] used = new boolean[trays.size()];
        List<double[]> pairs = new ArrayList<>();
        for (int t = 0; t < needs.length; t++) {
            if (needs[t] == null || !needs[t].known()) continue;
            for (int i = 0; i < trays.size(); i++) { double s = score(needs[t], trays.get(i)); if (s > 0) pairs.add(new double[] {s, t, i}); }
        }
        pairs.sort((a, b) -> Double.compare(b[0], a[0]));
        for (double[] pair : pairs) { int t = (int) pair[1], i = (int) pair[2]; if (chosen[t] < 0 && !used[i]) { chosen[t] = i; used[i] = true; } }
        // More tools than trays of the right material: share the best one (the printer feeds both from it).
        for (double[] pair : pairs) { int t = (int) pair[1]; if (chosen[t] < 0) chosen[t] = (int) pair[2]; }
        return chosen;
    }

    /** The verdict for a filament left at the printer's own choice, saying when no loaded tray has the material it was sliced for. */
    static Verdict unset(Need need, List<TrayPlan.Tray> loaded) {
        if (need != null && !need.type.isEmpty() && !family(need.type).isEmpty()) {
            boolean any = false;
            for (TrayPlan.Tray tray : loaded) if (family(need.type).equals(family(tray.type + " " + tray.name))) any = true;
            if (!any) return new Verdict(Level.CHECK, "No loaded tray has " + family(need.type) + ". Load it in a CANVAS tray and refresh, or leave every filament to the printer.");
        }
        return new Verdict(Level.CHECK, "Uses the printer's own choice for this filament.");
    }

    /** The verdict for tool `need.t` printing from `tray`; `sharedWith` = other tools mapped to the same tray (1-based names). */
    static Verdict check(Need need, TrayPlan.Tray tray, List<Integer> sharedWith) {
        if (tray == null) return new Verdict(Level.CHECK, "Uses the printer's own choice for this filament.");
        List<String> notes = new ArrayList<>(); Level level = Level.OK;
        String loaded = tray.type + (tray.name.isEmpty() || tray.name.equalsIgnoreCase(tray.type) ? "" : " " + tray.name);
        if (need != null && !need.type.isEmpty()) {
            String wanted = family(need.type), has = family(loaded);
            if (!wanted.isEmpty() && !has.isEmpty() && !wanted.equals(has)) { level = Level.WRONG; notes.add("The file was sliced for " + need.type + "; this tray has " + loaded + "."); }
            else if (filled(need.type) != filled(loaded)) { level = Level.CHECK; notes.add(filled(need.type) ? "The file was sliced for a fibre-filled " + need.type + "; this tray has " + loaded + "." : "This tray has fibre-filled " + loaded + ", which wears a standard nozzle."); }
        }
        if (need != null && need.colour != null && tray.colour != null && distance(need.colour, tray.colour) > 35) {
            if (level == Level.OK) level = Level.CHECK;
            notes.add("Colour differs: the file shows " + WorkshopUi.colourName(need.colour) + ", the tray is " + WorkshopUi.colourName(tray.colour) + ".");
        }
        if (sharedWith != null && !sharedWith.isEmpty()) {
            if (level == Level.OK) level = Level.CHECK;
            StringBuilder others = new StringBuilder();
            for (int other : sharedWith) others.append(others.length() > 0 ? ", " : "").append(other);
            notes.add("Filament " + others + " also prints from this tray.");
        }
        if (notes.isEmpty()) notes.add(need == null || !need.known() ? "The file does not say which material this filament is; check it is right." : "Matches what the file was sliced for.");
        return new Verdict(level, String.join(" ", notes));
    }

    // ------------------------------------------------------------------ helpers
    /** CIE76 colour difference between two #RRGGBB colours (0 = same; about 2 is barely visible; above 35 clearly different). */
    static double distance(String a, String b) {
        double[] x = lab(a), y = lab(b);
        return Math.sqrt(Math.pow(x[0] - y[0], 2) + Math.pow(x[1] - y[1], 2) + Math.pow(x[2] - y[2], 2));
    }
    private static double[] lab(String hex) {
        int rgb = Integer.parseInt(hex.substring(1), 16);
        double r = lin(((rgb >> 16) & 255) / 255.0), g = lin(((rgb >> 8) & 255) / 255.0), b = lin((rgb & 255) / 255.0);
        double x = (0.4124 * r + 0.3576 * g + 0.1805 * b) / 0.95047, y = 0.2126 * r + 0.7152 * g + 0.0722 * b, z = (0.0193 * r + 0.1192 * g + 0.9505 * b) / 1.08883;
        x = f(x); y = f(y); z = f(z);
        return new double[] {116 * y - 16, 500 * (x - y), 200 * (y - z)};
    }
    private static double lin(double c) { return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4); }
    private static double f(double t) { return t > 0.008856 ? Math.cbrt(t) : 7.787 * t + 16 / 116.0; }
    private static String first(JSONObject item, String... keys) { for (String key : keys) { String v = item.optString(key, ""); if (!v.isEmpty()) return v; } return ""; }
    /** "Elegoo PLA Matte @ECC2" → "Elegoo PLA Matte". */
    static String shortPreset(String preset) { return preset == null ? "" : preset.replaceFirst("\\s*@.*$", "").trim(); }

    private FilamentMatch() { }
}
