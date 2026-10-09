package io.github.thelastfrogrammer.elink;

import java.util.*;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Multi-filament slicing with CANVAS trays: the trays a printer reports, the slicer preset matching a tray's material,
 * and the plan a sliced file carries to print start (G-code tool t, i.e. filament slot t + 1, prints from a tray).
 * Independent of Android.
 */
final class TrayPlan {
    static final int MAX_TOOLS = 8;

    /** A loaded tray as the printer reports it in canvas_info.canvas_list[].tray_list[]. */
    static final class Tray {
        final int canvasId, trayId; final String type, name, brand, colour;
        Tray(int canvasId, int trayId, String type, String name, String brand, String colour) {
            this.canvasId = canvasId; this.trayId = trayId; this.type = type; this.name = name; this.brand = brand; this.colour = colour;
        }
        String label() {
            String material = name.isEmpty() || name.equalsIgnoreCase(type) ? type : type + " · " + name;
            return "CANVAS " + canvasId + " · tray " + trayId + " · " + material + (colour == null ? "" : " " + colour);
        }
        boolean same(int canvas, int tray) { return canvasId == canvas && trayId == tray; }
        /** "PLA Matte · red": the material once (a name that repeats the type is used as is) and the colour in words. */
        String material() {
            String material = name.isEmpty() || name.equalsIgnoreCase(type) ? type : name.toUpperCase(Locale.ROOT).contains(type.toUpperCase(Locale.ROOT)) ? name : type + " " + name;
            String word = WorkshopUi.colourName(colour);
            return word == null ? material : material + " · " + word;
        }
        /** "CANVAS 0 tray 1". */
        String where() { return "CANVAS " + canvasId + " tray " + trayId; }
    }

    /** One G-code tool's tray. */
    static final class Tool {
        final int t, canvasId, trayId;
        Tool(int t, int canvasId, int trayId) { this.t = t; this.canvasId = canvasId; this.trayId = trayId; }
    }

    /** What a tool was sliced as: the filament preset and its colour (either may be empty). */
    static final class Need {
        final int t; final String preset, colour;
        Need(int t, String preset, String colour) { this.t = t; this.preset = preset == null ? "" : preset; this.colour = TrayPlan.colour(colour); }
    }

    final int count;
    final List<Tool> tools;
    final List<Need> needs;

    TrayPlan(int count, List<Tool> tools) { this(count, tools, new ArrayList<>()); }
    TrayPlan(int count, List<Tool> tools, List<Need> needs) {
        this.count = count; this.tools = Collections.unmodifiableList(new ArrayList<>(tools)); this.needs = Collections.unmodifiableList(new ArrayList<>(needs));
    }
    /** This plan with each tool's sliced filament preset and colour recorded, so print setup can match trays to them. */
    TrayPlan withNeeds(List<String> presets, List<String> colours) {
        List<Need> list = new ArrayList<>();
        for (int t = 0; t < count && t < presets.size(); t++) list.add(new Need(t, presets.get(t), colours == null || t >= colours.size() ? null : colours.get(t)));
        return new TrayPlan(count, tools, list);
    }
    /** What tool t was sliced as, or null. */
    Need need(int t) { for (Need need : needs) if (need.t == t) return need; return null; }

    /** Tool count to preselect: a stored plan's count, else the highest T0–T7 seen in the file plus one, else 1. A starting point the user must still verify. */
    static int defaultToolCount(TrayPlan plan, Collection<Integer> seen) {
        if (plan != null) return plan.count;
        int highest = -1;
        if (seen != null) for (int t : seen) if (t >= 0 && t < MAX_TOOLS) highest = Math.max(highest, t);
        return highest + 1 < 1 ? 1 : highest + 1;
    }

    /** The tray for tool t, or null. */
    Tool tool(int t) { for (Tool tool : tools) if (tool.t == t) return tool; return null; }

    String toJson() {
        try {
            JSONArray list = new JSONArray();
            for (Tool tool : tools) list.put(new JSONObject().put("t", tool.t).put("canvas_id", tool.canvasId).put("tray_id", tool.trayId));
            JSONObject root = new JSONObject().put("count", count).put("tools", list);
            if (!needs.isEmpty()) {
                JSONArray sliced = new JSONArray();
                for (Need need : needs) sliced.put(new JSONObject().put("t", need.t).put("preset", need.preset).put("colour", need.colour == null ? "" : need.colour));
                root.put("needs", sliced);
            }
            return root.toString();
        } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }

    /** Parses a stored plan; null when missing or malformed. */
    static TrayPlan parse(String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            JSONObject root = new JSONObject(json);
            int count = root.getInt("count");
            if (count < 1 || count > MAX_TOOLS) return null;
            List<Tool> tools = new ArrayList<>();
            JSONArray list = root.optJSONArray("tools");
            if (list != null) for (int i = 0; i < list.length(); i++) {
                JSONObject item = list.getJSONObject(i);
                int t = item.getInt("t");
                if (t < 0 || t >= count) return null;
                tools.add(new Tool(t, item.getInt("canvas_id"), item.getInt("tray_id")));
            }
            List<Need> needs = new ArrayList<>();
            JSONArray sliced = root.optJSONArray("needs");     // older plans have none
            if (sliced != null) for (int i = 0; i < sliced.length(); i++) {
                JSONObject item = sliced.optJSONObject(i); if (item == null) continue;
                int t = item.optInt("t", -1);
                if (t >= 0 && t < count) needs.add(new Need(t, StatusPresentation.clean(item.optString("preset")), item.optString("colour")));
            }
            return new TrayPlan(count, tools, needs);
        } catch (JSONException malformed) { return null; }
    }

    /** Loaded trays of connected CANVAS units, in reported order (a unit that does not report "connected" counts). */
    static List<Tray> trays(JSONObject canvas) { return trays(canvas, false); }
    /** As above; with `strict`, only units that report themselves connected (what print start accepts). */
    static List<Tray> trays(JSONObject canvas, boolean strict) {
        List<Tray> result = new ArrayList<>();
        JSONArray units = canvas == null ? null : canvas.optJSONArray("canvas_list");
        if (units == null) return result;
        for (int u = 0; u < units.length(); u++) {
            JSONObject unit = units.optJSONObject(u);
            if (unit == null || unit.optInt("canvas_id", -1) < 0) continue;
            Object connected = unit.opt("connected");
            if ((strict || connected != null) && !(Boolean.TRUE.equals(connected) || unit.optInt("connected", 0) == 1)) continue;
            JSONArray list = unit.optJSONArray("tray_list"); if (list == null) continue;
            for (int t = 0; t < list.length(); t++) {
                JSONObject tray = list.optJSONObject(t);
                if (tray == null || tray.optInt("tray_id", -1) < 0) continue;
                String type = StatusPresentation.clean(tray.optString("filament_type")).trim();
                if (type.isEmpty()) continue; // empty tray
                result.add(new Tray(unit.optInt("canvas_id"), tray.optInt("tray_id"), type, StatusPresentation.clean(tray.optString("filament_name")).trim(),
                    StatusPresentation.clean(tray.optString("brand")).trim(), colour(tray.optString("filament_color"))));
            }
        }
        return result;
    }

    /** "#RRGGBB" from a reported colour ("#44aa33", "44AA33", "#44AA33FF"), or null. */
    static String colour(String raw) {
        if (raw == null) return null;
        String hex = raw.trim();
        if (hex.startsWith("#")) hex = hex.substring(1);
        if (hex.startsWith("0x") || hex.startsWith("0X")) hex = hex.substring(2);
        if (hex.length() == 8) hex = hex.substring(0, 6); // RGBA
        if (!hex.matches("[0-9A-Fa-f]{6}")) return null;
        return "#" + hex.toUpperCase(Locale.ROOT);
    }

    /**
     * The filament preset for a tray's material: the brand's preset named like the tray's filament (e.g. "Elegoo PLA
     * Matte @ECC2"), then the brand's preset for its type, then Elegoo's and the generic preset for the type, then any
     * preset of that type; `fallback` when none matches.
     */
    static String preset(Tray tray, List<String> presets, String fallback) {
        String brand = tray.brand.isEmpty() ? "Elegoo" : tray.brand;
        String name = tray.name;
        // Names may repeat the brand ("ELEGOO PLA Matte").
        if (name.toLowerCase(Locale.ROOT).startsWith(brand.toLowerCase(Locale.ROOT) + " ")) name = name.substring(brand.length() + 1).trim();
        List<String> wanted = new ArrayList<>();
        if (!name.isEmpty()) { wanted.add(brand + " " + name + " @"); wanted.add("Elegoo " + name + " @"); }
        wanted.add(brand + " " + tray.type + " @"); wanted.add("Elegoo " + tray.type + " @"); wanted.add("Generic " + tray.type + " @");
        for (String prefix : wanted)
            for (String preset : presets) if (preset.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))) return preset;
        String type = " " + tray.type.toLowerCase(Locale.ROOT) + " ";
        for (String preset : presets) if ((" " + preset.toLowerCase(Locale.ROOT) + " ").contains(type)) return preset;
        return fallback;
    }
}
