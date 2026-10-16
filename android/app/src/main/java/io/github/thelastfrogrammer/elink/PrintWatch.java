package io.github.thelastfrogrammer.elink;

import org.json.JSONObject;

/**
 * Print watch: from the printer's own readings, says when a running print may need a look. It notices a print that stops
 * advancing, a nozzle or bed that stays well away from its target, a fan that reports it has stopped while it should turn
 * (the hotend's heatsink fan, the electronics fan), and a nozzle target outside the loaded filament's range. Only while the printer reports it is printing (not
 * while it heats, levels, pauses or stops). Each problem is reported once until it clears. It only reports: nothing is sent
 * to the printer. The thresholds are cautious on purpose, to avoid false alarms. Independent of Android.
 */
final class PrintWatch {
    static final long MIN_STALL_MS = 20 * 60_000L, NOZZLE_MS = 3 * 60_000L, BED_MS = 5 * 60_000L, GRACE_MS = 3 * 60_000L;
    static final double NOZZLE_OFF = 15, BED_OFF = 10;

    private String job = "";
    private int layer = -1, progress = -1;
    private long since = -1, layerStart = -1, longestLayer;
    private boolean stallSent;
    private final Heater nozzle = new Heater("Nozzle", NOZZLE_OFF, NOZZLE_MS), bed = new Heater("Bed", BED_OFF, BED_MS);
    static final long HEATSINK_MS = 60_000L, CONTROLLER_MS = 2 * 60_000L;
    static final double STOPPED_RPM = 200, SPINNING_RPM = 500, HOT_NOZZLE = 150;
    private final Fan heatsink = new Fan("heater_fan", HEATSINK_MS), controller = new Fan("controller_fan", CONTROLLER_MS);
    /** The CANVAS trays as last reported (for the active filament's temperature range); set before update. */
    JSONObject canvas;
    private String rangeSent = "";

    /**
     * One fan's watch, from the speed the printer measures (rpm). Only once the fan has been seen turning (at least 500 rpm)
     * do low readings count, so a printer that reports 0 for a fan it does not measure never raises an alarm.
     */
    private static final class Fan {
        final String key; final long hold; boolean reports, sent; long stoppedSince = -1;
        Fan(String key, long hold) { this.key = key; this.hold = hold; }
        /** `shouldTurn`: the moment calls for it (a hot nozzle, or the fan told to run). */
        boolean stopped(JSONObject fans, boolean shouldTurn, long now) {
            JSONObject fan = fans == null ? null : fans.optJSONObject(key);
            if (fan == null || !fan.has("rpm")) { stoppedSince = -1; return false; }
            double rpm = fan.optDouble("rpm", 0);
            if (rpm >= SPINNING_RPM) { reports = true; stoppedSince = -1; sent = false; return false; }
            if (!reports || !shouldTurn || rpm >= STOPPED_RPM) { stoppedSince = -1; return false; }
            if (stoppedSince < 0) stoppedSince = now;
            if (sent || now - stoppedSince < hold) return false;
            sent = true; return true;
        }
    }

    /** One heater's watch: away from its target by more than `off` degrees for longer than `hold`, after a grace period. */
    private static final class Heater {
        final String name; final double off; final long hold;
        double target = Double.NaN; long targetSince = -1, awaySince = -1; boolean sent;
        Heater(String name, double off, long hold) { this.name = name; this.off = off; this.hold = hold; }
        void reset() { target = Double.NaN; targetSince = -1; awaySince = -1; sent = false; }
        String check(JSONObject reading, long now) {
            if (reading == null || !reading.has("temperature") || !reading.has("target")) { awaySince = -1; return ""; }
            double t = reading.optDouble("temperature"), goal = reading.optDouble("target");
            if (goal != target) { target = goal; targetSince = now; awaySince = -1; sent = false; }
            if (!(goal > 0) || now - targetSince < GRACE_MS || Math.abs(t - goal) <= off) { awaySince = -1; if (Math.abs(t - goal) <= off / 2) sent = false; return ""; }
            if (awaySince < 0) awaySince = now;
            if (sent || now - awaySince < hold) return "";
            sent = true;
            return String.format(java.util.Locale.getDefault(), "%s is %.0f °C %s its target (%.0f of %.0f °C) for %d min.", name, Math.abs(t - goal), t < goal ? "below" : "above", t, goal, (now - awaySince) / 60_000);
        }
    }

    /** How long a print may go without a new layer or percent: at least 20 min, longer for slow layers. */
    long stallLimit(JSONObject print) {
        long limit = Math.max(MIN_STALL_MS, 3 * longestLayer);
        if (print != null) {
            int total = print.optInt("total_layer", 0), current = print.optInt("current_layer", 0); long remaining = print.optLong("remaining_time_sec", -1);
            if (total > current && remaining > 0) limit = Math.max(limit, 4 * remaining * 1000 / (total - current + 1));   // 4× the printer's own time per layer
        }
        return limit;
    }

    /** The message for this status (`now` from a clock that never steps), or "". */
    String update(JSONObject status, long now) {
        JSONObject machine = status == null ? null : status.optJSONObject("machine_status"), print = status == null ? null : status.optJSONObject("print_status");
        boolean printing = machine != null && machine.optInt("status", -1) == 2 && machine.optInt("sub_status", -1) == 2075;
        String key = print == null ? "" : print.optString("uuid", ""); if (key.isEmpty() && print != null) key = print.optString("filename", "");
        if (!printing || key.isEmpty()) {
            // Paused, stopping or idle: the clocks start again when printing resumes.
            since = -1; layerStart = -1; nozzle.awaySince = -1; bed.awaySince = -1;
            if (machine != null && machine.optInt("status", -1) != 2) { job = ""; longestLayer = 0; nozzle.reset(); bed.reset(); }
            return "";
        }
        if (!key.equals(job)) { job = key; layer = -1; progress = -1; since = -1; layerStart = -1; longestLayer = 0; stallSent = false; nozzle.reset(); bed.reset(); rangeSent = ""; }
        int nowLayer = print.optInt("current_layer", -1), nowProgress = machine.optInt("progress", -1);
        if (nowLayer != layer || nowProgress != progress || since < 0) {
            if (nowLayer != layer && layerStart >= 0 && layer > 1) longestLayer = Math.max(longestLayer, now - layerStart);   // the first layer is slow by design
            if (nowLayer != layer) layerStart = now;
            layer = nowLayer; progress = nowProgress; since = now; stallSent = false;
        }
        String message = "";
        if (!stallSent && now - since > stallLimit(print)) {
            stallSent = true;
            message = "Print may be stuck: still " + (layer >= 0 ? "layer " + layer : "the same layer") + (progress >= 0 ? " at " + progress + "%" : "") + " after " + (now - since) / 60_000 + " min.";
        }
        JSONObject extruder = status.optJSONObject("extruder"), fans = status.optJSONObject("fans");
        double nozzleNow = extruder == null ? Double.NaN : extruder.optDouble("temperature", Double.NaN);
        String fanMessage = "";
        if (heatsink.stopped(fans, nozzleNow >= HOT_NOZZLE, now))
            fanMessage = String.format(java.util.Locale.getDefault(), "The hotend's heatsink fan reports it has stopped (below %.0f rpm) with the nozzle at %.0f °C. Without it the hotend can clog (heat creep).", STOPPED_RPM, nozzleNow);
        JSONObject board = fans == null ? null : fans.optJSONObject("controller_fan");
        if (controller.stopped(fans, board != null && board.optDouble("speed", 0) > 0, now))
            fanMessage += (fanMessage.isEmpty() ? "" : " ") + "The electronics fan reports it has stopped although it should run.";
        String range = range(extruder);
        for (String more : new String[] {nozzle.check(extruder, now), bed.check(status.optJSONObject("heater_bed"), now), fanMessage, range})
            if (!more.isEmpty()) message = message.isEmpty() ? more : message + " " + more;
        return message.isEmpty() ? "" : message + " Check the printer.";
    }

    /** A nozzle target outside the active filament's range (by more than 5 °C), once per filament and target. */
    private String range(JSONObject extruder) {
        JSONObject tray = activeTray(canvas);
        if (tray == null || extruder == null || !extruder.has("target")) return "";
        int low = tray.optInt("min_nozzle_temp", 0), high = tray.optInt("max_nozzle_temp", 0); double target = extruder.optDouble("target", 0);
        if (low <= 0 || high <= low || target <= 0 || target >= low - 5 && target <= high + 5) return "";
        String key = tray.optString("filament_type") + low + "-" + high + "@" + Math.round(target);
        if (key.equals(rangeSent)) return "";
        rangeSent = key;
        return String.format(java.util.Locale.getDefault(), "The nozzle is set to %.0f °C, outside the loaded %s's range (%d–%d °C). Check the file was sliced for this filament.",
            target, tray.optString("filament_type", "filament").trim().isEmpty() ? "filament" : tray.optString("filament_type").trim(), low, high);
    }
    static JSONObject activeTray(JSONObject c) {
        if (c == null || !c.has("active_canvas_id") || !c.has("active_tray_id")) return null;
        int unit = c.optInt("active_canvas_id", -1), tray = c.optInt("active_tray_id", -1);
        org.json.JSONArray units = c.optJSONArray("canvas_list");
        for (int u = 0; units != null && u < units.length(); u++) {
            JSONObject one = units.optJSONObject(u); if (one == null || one.optInt("canvas_id", -1) != unit) continue;
            org.json.JSONArray trays = one.optJSONArray("tray_list");
            for (int t = 0; trays != null && t < trays.length(); t++) { JSONObject item = trays.optJSONObject(t); if (item != null && item.optInt("tray_id", -1) == tray) return item; }
        }
        return null;
    }
}
