package io.github.thelastfrogrammer.elink;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Every value an automation can send or watch: the event's own values, values worked out from the printer's status (named,
 * with units), the active CANVAS tray, the app's own (recording, guardian), and every raw field the printer reports as
 * {status.path.to.field}. Fields that could identify the printer or its network are never included. Independent of Android.
 */
final class AutomationValues {
    static final class Value {
        final String key, group, description; final boolean number;
        Value(String key, String group, String description, boolean number) { this.key = key; this.group = group; this.description = description; this.number = number; }
    }
    static final List<Value> CATALOGUE = new ArrayList<>();
    private static void add(String group, boolean number, String key, String description) { CATALOGUE.add(new Value(key, group, description, number)); }
    static {
        add("Event", false, "event", "The event's id, such as print_finished");
        add("Event", false, "event_name", "The event in words, such as “A print finishes”");
        add("Event", false, "detail", "More about the event: the fault codes, the guardian's reason, the value that crossed its limit");
        add("Event", false, "printer", "The printer's name");
        add("Event", false, "time", "The time of the event (HH:mm)");
        add("Event", false, "date", "The date of the event (yyyy-MM-dd)");
        add("Print", false, "state", "The printer's state in words (Printing, Paused, Idle, …)");
        add("Print", false, "file", "The file being printed");
        add("Print", true, "progress", "Progress in percent");
        add("Print", true, "layer", "The current layer");
        add("Print", true, "total_layers", "The print's layer count");
        add("Print", false, "remaining", "Time left, in minutes (“42 min”)");
        add("Print", true, "remaining_s", "Time left, in seconds");
        add("Print", false, "done_at", "When the print should be done (HH:mm)");
        add("Print", true, "elapsed_s", "How long the print has run, in seconds (as the printer counts it)");
        add("Print", false, "elapsed", "How long the print has run, in words (“1 h 12 min”)");
        add("Print", true, "total_s", "The printer's total time for the print so far, pauses included, in seconds");
        add("Print", true, "speed_mode", "The printer's speed mode number");
        add("Temperatures", true, "nozzle", "Nozzle temperature, °C");
        add("Temperatures", true, "nozzle_target", "Nozzle target temperature, °C");
        add("Temperatures", true, "bed", "Bed temperature, °C");
        add("Temperatures", true, "bed_target", "Bed target temperature, °C");
        add("Temperatures", true, "chamber", "Chamber temperature, °C");
        add("Temperatures", true, "chamber_max", "Highest chamber temperature the printer measured, °C");
        add("Temperatures", true, "chamber_min", "Lowest chamber temperature the printer measured, °C");
        add("Temperatures", true, "chamber_rate", "How fast the chamber warms (or cools, below 0) over the last five minutes, °C per minute");
        add("Temperatures", true, "chamber_goal", "The chamber to wait for with the loaded filament: 40 °C for ABS, ASA, PC and nylon");
        add("Temperatures", true, "chamber_ready_in", "Minutes until the chamber reaches that goal at the present rate (0 when there)");
        add("Fans", true, "part_fan", "Part cooling fan, percent");
        add("Fans", true, "aux_fan", "Auxiliary fan, percent");
        add("Fans", true, "chamber_fan", "Chamber (box) fan, percent");
        add("Fans", true, "heatsink_fan", "Hotend heatsink fan, percent");
        add("Fans", true, "controller_fan", "Controller (electronics) fan, percent");
        add("Fans", true, "part_fan_rpm", "Part cooling fan speed, rpm (when the printer reports it)");
        add("Fans", true, "aux_fan_rpm", "Auxiliary fan speed, rpm");
        add("Fans", true, "chamber_fan_rpm", "Chamber fan speed, rpm");
        add("Fans", true, "heatsink_fan_rpm", "Hotend heatsink fan speed, rpm");
        add("Fans", true, "controller_fan_rpm", "Controller fan speed, rpm");
        add("Motion", true, "x", "Nozzle position X, mm");
        add("Motion", true, "y", "Nozzle position Y, mm");
        add("Motion", true, "z", "Nozzle height Z, mm");
        add("Motion", true, "extruded_mm", "Filament pushed by the extruder (E position), mm");
        add("Motion", false, "homed_axes", "Axes the printer has homed, such as xyz");
        add("Printer", false, "light", "The chamber light: on or off");
        add("Printer", false, "usb_stick", "Whether a USB stick is in: yes or no");
        add("Printer", false, "camera_connected", "Whether the printer reports its camera connected: yes or no");
        add("Printer", false, "faults", "Fault codes the printer reports, or none");
        add("Filament", true, "tray", "The active CANVAS tray (1–4 on the first unit)");
        add("Filament", false, "filament_type", "The active tray's filament type, such as PLA");
        add("Filament", false, "filament_name", "The active tray's filament name");
        add("Filament", false, "filament_brand", "The active tray's filament brand");
        add("Filament", false, "filament_colour", "The active tray's colour, #RRGGBB");
        add("Filament", true, "filament_min_nozzle", "The active filament's lowest nozzle temperature, °C");
        add("Filament", true, "filament_max_nozzle", "The active filament's highest nozzle temperature, °C");
        add("App", true, "pictures", "Camera pictures kept with this print's recording");
        add("App", true, "filament_used_m", "Filament the print has fed so far, metres (from the extruder position)");
        add("App", false, "done_at_learned", "When the print should be done by what your past prints took against the printer's estimate (HH:mm)");
        add("App", false, "guardian", "The print guardian's latest verdict");
    }
    /** Things an action can attach, listed with the values (they are not fill-ins). */
    static final String[][] ATTACHMENTS = {
        {"Latest camera picture", "The newest picture of this print (from its recording, or the camera), as a JPEG"},
        {"Chart of this print", "Temperatures and progress so far, drawn as a PNG like the recording page"}};

    static Value find(String key) { for (Value v : CATALOGUE) if (v.key.equals(key)) return v; return null; }

    /** The named values from a status, the active tray in `canvas` (may be null) and the app's own `app` values. Missing ones are left out. */
    static Map<String, String> from(JSONObject status, JSONObject canvas, Map<String, String> app, long nowMs, Locale locale, java.util.TimeZone zone) {
        Map<String, String> v = new LinkedHashMap<>();
        if (status != null && status.length() > 0) {
            JSONObject machine = status.optJSONObject("machine_status"), print = status.optJSONObject("print_status");
            boolean printing = machine != null && machine.optInt("status", -1) == 2;
            v.put("state", StatusPresentation.state(status));
            if (machine != null && machine.has("progress")) v.put("progress", String.valueOf(Math.max(0, Math.min(100, machine.optInt("progress")))));
            if (print != null) {
                if (printing) v.put("file", StatusPresentation.clean(print.optString("filename", "")));
                if (print.has("current_layer")) v.put("layer", String.valueOf(print.optInt("current_layer")));
                if (print.has("total_layer")) v.put("total_layers", String.valueOf(print.optInt("total_layer")));
                long remaining = print.optLong("remaining_time_sec", -1);
                if (printing && remaining >= 0) {
                    v.put("remaining", Math.round(remaining / 60.0) + " min"); v.put("remaining_s", String.valueOf(remaining));
                    v.put("done_at", StatusPresentation.doneAt(nowMs, remaining, locale, zone));
                }
                if (print.has("print_duration")) { long run = print.optLong("print_duration"); v.put("elapsed_s", String.valueOf(run)); v.put("elapsed", StatusPresentation.span(run)); }
                if (print.has("total_duration")) v.put("total_s", String.valueOf(print.optLong("total_duration")));
            }
            temperature(v, "nozzle", status.optJSONObject("extruder")); temperature(v, "bed", status.optJSONObject("heater_bed"));
            JSONObject chamber = status.optJSONObject("ztemperature_sensor");
            if (chamber != null) {
                if (chamber.has("temperature")) v.put("chamber", one(chamber.optDouble("temperature")));
                if (chamber.has("measured_max_temperature")) v.put("chamber_max", one(chamber.optDouble("measured_max_temperature")));
                if (chamber.has("measured_min_temperature")) v.put("chamber_min", one(chamber.optDouble("measured_min_temperature")));
            }
            JSONObject fans = status.optJSONObject("fans");
            fan(v, "part_fan", fans, "fan"); fan(v, "aux_fan", fans, "aux_fan"); fan(v, "chamber_fan", fans, "box_fan");
            fan(v, "heatsink_fan", fans, "heater_fan"); fan(v, "controller_fan", fans, "controller_fan");
            JSONObject move = status.optJSONObject("gcode_move_inf");
            if (move != null) {
                for (String axis : new String[] {"x", "y", "z"}) if (move.has(axis)) v.put(axis, String.format(Locale.ROOT, "%.2f", move.optDouble(axis)));
                if (move.has("e")) v.put("extruded_mm", one(move.optDouble("e")));
                if (move.has("speed_mode")) v.put("speed_mode", String.valueOf(move.optInt("speed_mode")));
            }
            JSONObject toolhead = status.optJSONObject("toolhead"); if (toolhead != null && toolhead.has("homed_axes")) v.put("homed_axes", toolhead.optString("homed_axes"));
            JSONObject led = status.optJSONObject("led"); if (led != null && led.has("status")) v.put("light", led.optInt("status") != 0 ? "on" : "off");
            JSONObject devices = status.optJSONObject("external_device");
            if (devices != null) {
                if (devices.has("u_disk")) v.put("usb_stick", devices.optBoolean("u_disk") ? "yes" : "no");
                if (devices.has("camera")) v.put("camera_connected", devices.optBoolean("camera") ? "yes" : "no");
            }
            String codes = StatusPresentation.faultCodes(status); v.put("faults", codes.isEmpty() ? "none" : codes);
        }
        tray(v, canvas != null ? canvas : status == null ? null : status.optJSONObject("canvas_info"));
        if (app != null) v.putAll(app);
        return v;
    }
    private static void tray(Map<String, String> v, JSONObject c) {
        if (c == null || !c.has("active_canvas_id") || !c.has("active_tray_id")) return;
        int unit = c.optInt("active_canvas_id", -1), tray = c.optInt("active_tray_id", -1);
        if (unit < 0 || tray < 0) return;
        v.put("tray", String.valueOf(unit * 4 + tray + 1));
        JSONArray units = c.optJSONArray("canvas_list");
        for (int u = 0; units != null && u < units.length(); u++) {
            JSONObject one = units.optJSONObject(u); if (one == null || one.optInt("canvas_id", -1) != unit) continue;
            JSONArray trays = one.optJSONArray("tray_list");
            for (int t = 0; trays != null && t < trays.length(); t++) {
                JSONObject item = trays.optJSONObject(t); if (item == null || item.optInt("tray_id", -1) != tray) continue;
                text(v, "filament_type", item, "filament_type"); text(v, "filament_name", item, "filament_name"); text(v, "filament_brand", item, "brand");
                String colour = TrayPlan.colour(item.optString("filament_color")); if (colour != null) v.put("filament_colour", colour);
                if (item.optInt("min_nozzle_temp", 0) > 0) v.put("filament_min_nozzle", String.valueOf(item.optInt("min_nozzle_temp")));
                if (item.optInt("max_nozzle_temp", 0) > 0) v.put("filament_max_nozzle", String.valueOf(item.optInt("max_nozzle_temp")));
            }
        }
    }
    private static void text(Map<String, String> v, String key, JSONObject o, String field) { String s = o.optString(field, "").trim(); if (!s.isEmpty()) v.put(key, StatusPresentation.clean(s)); }
    private static void temperature(Map<String, String> v, String key, JSONObject value) {
        if (value == null) return;
        if (value.has("temperature")) v.put(key, one(value.optDouble("temperature")));
        if (value.has("target")) v.put(key + "_target", one(value.optDouble("target")));
    }
    private static void fan(Map<String, String> v, String key, JSONObject fans, String name) {
        JSONObject fan = fans == null ? null : fans.optJSONObject(name);
        if (fan == null) return;
        if (fan.has("speed")) v.put(key, String.valueOf(Math.round(fan.optDouble("speed") * 100 / 255)));
        if (fan.has("rpm")) v.put(key + "_rpm", String.valueOf(fan.optLong("rpm")));
    }
    private static String one(double value) { return String.format(Locale.ROOT, "%.1f", value); }

    /** Every field the printer reported, as {status.path} → value (lists as path.0, path.1, …); identifying fields left out. */
    static Map<String, String> raw(JSONObject status) {
        Map<String, String> out = new LinkedHashMap<>();
        if (status != null) walk("status", status, out);
        return out;
    }
    private static void walk(String path, Object value, Map<String, String> out) {
        if (value instanceof JSONObject) {
            JSONObject o = (JSONObject) value; List<String> keys = new ArrayList<>(); for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) keys.add(it.next());
            java.util.Collections.sort(keys);
            for (String key : keys) if (!StatusPresentation.privateKey(key)) walk(path + "." + key, o.opt(key), out);
        } else if (value instanceof JSONArray) {
            JSONArray a = (JSONArray) value; for (int i = 0; i < a.length() && i < 32; i++) walk(path + "." + i, a.opt(i), out);
        } else if (value != null && value != JSONObject.NULL) out.put(path, String.valueOf(value));
    }

    /** A value as a number, or NaN (units and words such as "42 min" are read up to the first space). */
    static double number(Map<String, String> values, String key) {
        String s = values == null ? null : values.get(key);
        if (s == null || s.isEmpty()) return Double.NaN;
        if (s.equals("on") || s.equals("yes")) return 1; if (s.equals("off") || s.equals("no")) return 0;
        try { return Double.parseDouble(s.split(" ")[0]); } catch (NumberFormatException words) { return Double.NaN; }
    }
}
