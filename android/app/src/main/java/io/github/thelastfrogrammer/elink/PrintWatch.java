package io.github.thelastfrogrammer.elink;

import org.json.JSONObject;

/**
 * Print watch: from the printer's own readings, says when a running print may need a look. It notices a print that stops
 * advancing, and a nozzle or bed that stays well away from its target. Only while the printer reports it is printing (not
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
        if (!key.equals(job)) { job = key; layer = -1; progress = -1; since = -1; layerStart = -1; longestLayer = 0; stallSent = false; nozzle.reset(); bed.reset(); }
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
        for (String more : new String[] {nozzle.check(status.optJSONObject("extruder"), now), bed.check(status.optJSONObject("heater_bed"), now)})
            if (!more.isEmpty()) message = message.isEmpty() ? more : message + " " + more;
        return message.isEmpty() ? "" : message + " Check the printer.";
    }
}
