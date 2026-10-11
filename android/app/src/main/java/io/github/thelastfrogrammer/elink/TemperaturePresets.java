package io.github.thelastfrogrammer.elink;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;

/**
 * Heater presets for the temperature dialog: one per loaded CANVAS material (the nozzle in the middle of the range the tray
 * reports, the bed a typical value for that material), then common materials the trays don't cover, then Cool down.
 * A preset only fills the dialog's fields; nothing is sent until Set heaters. Independent of Android.
 */
final class TemperaturePresets {
    static final int NOZZLE_MAX = 300, BED_MAX = 100;

    static final class Preset {
        final String label, material; final int nozzle, bed; final boolean fromTray;
        Preset(String label, String material, int nozzle, int bed, boolean fromTray) { this.label = label; this.material = material; this.nozzle = nozzle; this.bed = bed; this.fromTray = fromTray; }
        /** "PLA Matte · red: 215° / 60°". */
        String text() { return label + ": " + (nozzle == 0 && bed == 0 ? "heaters off" : nozzle + "° / " + bed + "°"); }
    }

    /** Typical nozzle and bed for a material family, or null when unknown. Values stay inside the printer's limits. */
    static int[] typical(String type) {
        String t = type == null ? "" : type.toUpperCase(Locale.ROOT);
        if (t.startsWith("PLA")) return new int[] {215, 60};
        if (t.startsWith("PETG") || t.startsWith("PET")) return new int[] {240, 70};
        if (t.startsWith("ABS")) return new int[] {250, 100};
        if (t.startsWith("ASA")) return new int[] {255, 100};
        if (t.startsWith("TPU") || t.startsWith("TPE")) return new int[] {225, 45};
        if (t.startsWith("PA") || t.startsWith("PAHT") || t.startsWith("NYLON")) return new int[] {270, 90};
        if (t.startsWith("PCTG")) return new int[] {250, 80};
        if (t.startsWith("PC")) return new int[] {270, 100};
        return null;
    }
    private static final String[] COMMON = {"PLA", "PETG", "ABS", "TPU"};

    static List<Preset> from(JSONObject canvas) {
        List<Preset> out = new ArrayList<>(); Set<String> seen = new HashSet<>(); Set<String> families = new HashSet<>();
        JSONArray units = canvas == null ? null : canvas.optJSONArray("canvas_list");
        for (int u = 0; units != null && u < units.length(); u++) {
            JSONObject unit = units.optJSONObject(u); JSONArray list = unit == null ? null : unit.optJSONArray("tray_list");
            for (int i = 0; list != null && i < list.length(); i++) {
                JSONObject tray = list.optJSONObject(i); if (tray == null) continue;
                String type = StatusPresentation.clean(tray.optString("filament_type")).trim(); if (type.isEmpty()) continue;
                String name = StatusPresentation.clean(tray.optString("filament_name")).trim();
                int[] guess = typical(type);
                int low = tray.optInt("min_nozzle_temp", 0), high = tray.optInt("max_nozzle_temp", 0);
                int nozzle = low > 0 && high >= low ? (int) (Math.round((low + high) / 2.0 / 5) * 5) : guess == null ? 0 : guess[0];
                if (nozzle <= 0) continue;   // nothing reported and an unknown material: no guess
                nozzle = Math.min(NOZZLE_MAX, nozzle);
                int bed = guess == null ? 60 : guess[1];
                String material = name.isEmpty() || name.equalsIgnoreCase(type) ? type : name.toUpperCase(Locale.ROOT).contains(type.toUpperCase(Locale.ROOT)) ? name : type + " " + name;
                String colour = WorkshopUi.colourName(TrayPlan.colour(tray.optString("filament_color")));
                String label = colour == null ? material : material + " · " + colour;
                if (!seen.add(material.toUpperCase(Locale.ROOT) + "|" + nozzle + "|" + bed)) continue;   // two spools of the same material: one preset
                out.add(new Preset(label, type, nozzle, bed, true));
                for (String family : COMMON) if (type.toUpperCase(Locale.ROOT).startsWith(family)) families.add(family);
            }
        }
        for (String family : COMMON) if (!families.contains(family)) { int[] t = typical(family); out.add(new Preset(family + " (typical)", family, t[0], t[1], false)); }
        out.add(new Preset("Cool down", "", 0, 0, false));
        return out;
    }
}
