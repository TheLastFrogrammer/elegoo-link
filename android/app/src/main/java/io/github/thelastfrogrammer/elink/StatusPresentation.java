package io.github.thelastfrogrammer.elink;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** Read-only presentation of actual reported fields; unknown states and fault codes remain explicit. */
public final class StatusPresentation {
    private StatusPresentation() { }
    public static String state(JSONObject status) {
        JSONObject machine = status.optJSONObject("machine_status");
        if (machine == null) return "Waiting for printer";
        int state = machine.optInt("status", -1), sub = machine.optInt("sub_status", -1);
        if (state == 1) return "Idle";
        if (state == 0) return "Initializing";
        if (state != 2) return other(state, sub);
        switch (sub) {
            case 2075: return "Printing";
            case 2501: return "Pausing";
            case 2502: case 2505: return "Paused";
            case 2401: return "Resuming";
            case 2402: return "Resumed";
            case 2503: return "Stopping";
            case 2504: return "Stopped";
            case 2077: return "Print complete";
            case 1081: case 1082: case 1086: return "Preparing: getting the file";
            case 1045: case 1096: return "Preparing: heating the nozzle";
            case 1405: case 1906: return "Preparing: heating the bed";
            case 2801: case 2802: return "Preparing: homing";
            case 2901: case 2902: return "Preparing: levelling the bed";
            default: return "Preparing to print (" + sub + ")";
        }
    }
    /**
     * The other machine states and their steps, as the printer's own app names them (Elegoo Link's CC2 message adapter:
     * filament, levelling, PID, resonance test, self-check, update, homing, file transfer, video, extruder, emergency stop,
     * power-loss recovery). Unknown values keep their number, so nothing is guessed.
     */
    static String other(int state, int sub) {
        String done = " done", failed = " failed";
        switch (state) {
            case 3: case 4:
                return sub == 1133 || sub == 1134 || sub == 1135 ? "Loading filament" : sub == 1136 ? "Filament loaded"
                    : sub == 1144 ? "Unloading filament" : sub == 1145 ? "Filament unloaded" : "Filament change";
            case 5: return sub == 2902 ? "Bed levelling" + done : "Levelling the bed";
            case 6: return sub == 1505 ? "Heater tuning (PID)" + done : sub == 1506 ? "Heater tuning (PID)" + failed : "Tuning the heaters (PID)";
            case 7: return sub == 5935 ? "Vibration test" + done : sub == 5936 ? "Vibration test" + failed : "Vibration test";
            case 8: {
                String step = sub == 5934 || sub == 5935 || sub == 5936 ? "vibration test" : sub >= 1503 && sub <= 1506 ? "heater tuning" : sub == 2901 || sub == 2902 ? "bed levelling" : "";
                boolean ok = sub == 5935 || sub == 1505 || sub == 2902, bad = sub == 5936 || sub == 1506;
                return "Self-check" + (step.isEmpty() ? "" : ": " + step + (ok ? done : bad ? failed : ""));
            }
            case 9: return sub == 2074 ? "Update" + done : sub == 2075 ? "Update" + failed : "Updating the firmware";
            case 10: return sub == 2802 ? "Homing" + done : sub == 2803 ? "Homing" + failed : "Homing";
            case 11: return sub == 3001 ? "File received" : "Receiving a file";
            case 12: return "Making the timelapse video";
            case 13: return sub == 1061 ? "Loading the extruder" : sub == 1063 ? "Extruder loaded" : sub == 1062 ? "Unloading the extruder" : sub == 1064 ? "Extruder unloaded" : "Extruder operation";
            case 14: return "Emergency stop";
            case 15: return "Power-loss recovery";
            default: return "Machine state " + state;
        }
    }
    public static String faultCodes(JSONObject status) {
        JSONObject exception = status.optJSONObject("exception");
        JSONObject codes = exception == null ? status.optJSONObject("exception_code") : exception.optJSONObject("exception_code");
        if (codes == null || codes.length() == 0) return "";
        List<String> keys = new ArrayList<>(); Iterator<String> it = codes.keys();
        while (it.hasNext()) keys.add(it.next()); Collections.sort(keys);
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < Math.min(keys.size(), 24); i++) { if (i > 0) text.append(", "); text.append(clean(keys.get(i))); }
        if (keys.size() > 24) text.append(" …");
        return text.toString();
    }
    public static String canvas(JSONObject canvas) {
        if (canvas == null) return "CANVAS information has not been reported.";
        StringBuilder text = new StringBuilder("Automatic refill: ");
        text.append(canvas.has("auto_refill") ? (canvas.optBoolean("auto_refill") ? "On" : "Off") : "Not reported");
        JSONArray list = canvas.optJSONArray("canvas_list");
        if (list == null || list.length() == 0) return text.append("\nNo CANVAS trays reported.").toString();
        for (int i = 0; i < Math.min(list.length(), 8); i++) {
            JSONObject unit = list.optJSONObject(i); if (unit == null) continue;
            int id = unit.optInt("canvas_id", -1);
            Object connected = unit.opt("connected");
            text.append("\n\nCANVAS ").append(id).append(connected == null ? " · Connection not reported" : (Boolean.TRUE.equals(connected) || unit.optInt("connected", 0) == 1) ? " · Connected" : " · Not connected");
            JSONArray trays = unit.optJSONArray("tray_list"); if (trays == null) continue;
            for (int t = 0; t < Math.min(trays.length(), 16); t++) {
                JSONObject tray = trays.optJSONObject(t); if (tray == null) continue;
                int trayId = tray.optInt("tray_id", -1);
                boolean active = canvas.has("active_canvas_id") && canvas.has("active_tray_id")
                    && id == canvas.optInt("active_canvas_id") && trayId == canvas.optInt("active_tray_id");
                text.append("\n").append(active ? "● " : "○ ").append("Tray ").append(trayId).append(active ? " · Active" : "");
                text.append(" · ").append(clean(tray.optString("filament_type", "Material not reported")));
                String name = clean(tray.optString("filament_name")), color = clean(tray.optString("filament_color"));
                if (!name.isEmpty()) text.append(" · ").append(name);
                if (!color.isEmpty()) text.append(" · ").append(color);
                if (tray.has("status")) text.append(" · State ").append(tray.optInt("status"));
                if (tray.has("min_nozzle_temp") && tray.has("max_nozzle_temp"))
                    text.append("\n    Nozzle ").append(tray.optInt("min_nozzle_temp")).append("–").append(tray.optInt("max_nozzle_temp")).append("°C");
            }
        }
        return text.toString();
    }
    /** State, temperatures and current job in a few lines, from the same fields the Monitor tab shows. */
    public static String overview(JSONObject status) {
        JSONObject machine = status.optJSONObject("machine_status"), print = status.optJSONObject("print_status");
        boolean printing = machine != null && machine.optInt("status", -1) == 2;
        StringBuilder text = new StringBuilder(state(status));
        if (printing) text.append(" · ").append(Math.max(0, Math.min(100, machine.optInt("progress", 0)))).append("%");
        text.append("\nNozzle ").append(temperature(status, "extruder")).append(" · Bed ").append(temperature(status, "heater_bed"))
            .append(" · Chamber ").append(temperature(status, "ztemperature_sensor"));
        if (printing && print != null) {
            long remaining = print.optLong("remaining_time_sec", -1);
            text.append("\n").append(clean(print.optString("filename", "Current print"))).append("\nLayer ").append(print.optInt("current_layer", 0))
                .append(" / ").append(print.optInt("total_layer", 0)).append(" · ")
                .append(remaining < 0 ? "Time unavailable" : remaining / 3600 + "h " + (remaining % 3600) / 60 + "m remaining");
        }
        return text.toString();
    }
    static String temperature(JSONObject status, String key) {
        JSONObject value = status.optJSONObject(key); if (value == null || !value.has("temperature")) return "—";
        String text = String.format(java.util.Locale.ROOT, "%.1f°C", value.optDouble("temperature", 0));
        return value.has("target") ? text + String.format(java.util.Locale.ROOT, " / %.0f°C", value.optDouble("target", 0)) : text;
    }
    /** The non-empty parts joined with " · ", so an empty part leaves no dangling separator. */
    static String joinParts(String... parts) {
        StringBuilder text = new StringBuilder();
        for (String part : parts) if (part != null && !part.trim().isEmpty()) text.append(text.length() > 0 ? " · " : "").append(part.trim());
        return text.toString();
    }
    public static String clean(String value) { return value.replaceAll("[\\p{Cntrl}]", " ").substring(0, Math.min(value.length(), 160)); }

    /** The viewer's one plain line when the phone holds no copy of the G-code being printed. */
    static final String NO_FILE_LINE = "The toolpath needs the G-code file. Files sent from this phone, or downloaded over the local connection, show here.";

    /** "41% · layer 87 of 212 · 1h 15m left": what the printer reports about the running print, only the parts it reports. */
    static String runningLine(JSONObject machine, JSONObject print) {
        List<String> parts = new ArrayList<>();
        if (machine != null && machine.has("progress")) parts.add(Math.max(0, Math.min(100, machine.optInt("progress"))) + "%");
        if (print != null && print.optInt("total_layer", 0) > 0) parts.add("layer " + print.optInt("current_layer", 0) + " of " + print.optInt("total_layer"));
        long remaining = print == null ? -1 : print.optLong("remaining_time_sec", -1);
        if (remaining >= 0) parts.add(remaining / 3600 + "h " + (remaining % 3600) / 60 + "m left");
        return parts.isEmpty() ? "Printing" : String.join(" · ", parts);
    }

    /** "2h 05m", "45m" or "0m" for a stat tile; "—" when unknown (negative). */
    static String span(long seconds) {
        if (seconds < 0) return "\u2014";
        long h = seconds / 3600, m = (seconds % 3600) / 60;
        return h > 0 ? h + "h " + String.format(Locale.ROOT, "%02d", m) + "m" : m + "m";
    }

    /** "14:30" for a finish today, "Tue 14:30" on another day, from the printer's remaining seconds and the phone's clock. */
    static String doneAt(long nowMs, long remainingSec, Locale locale, TimeZone zone) {
        long end = nowMs + remainingSec * 1000;
        java.text.SimpleDateFormat time = new java.text.SimpleDateFormat("HH:mm", locale), day = new java.text.SimpleDateFormat("EEE HH:mm", locale);
        time.setTimeZone(zone); day.setTimeZone(zone);
        Calendar a = Calendar.getInstance(zone), b = Calendar.getInstance(zone); a.setTimeInMillis(nowMs); b.setTimeInMillis(end);
        boolean today = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
        return (today ? time : day).format(new Date(end));
    }
}
