package io.github.thelastfrogrammer.elink;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The printer's status as other apps see it (the local API and the relay): one versioned shape, "linkworkshop.status/1",
 * that only grows (new fields may appear; none change meaning or go away within version 1). It holds what a screen
 * needs and nothing that identifies the printer or the home: no serial, address, access code, account or cloud id.
 * Pure: the service hands in the status it shows.
 */
final class IntegrationStatus {
    static final String SCHEMA = "linkworkshop.status/1";
    /** A machine-readable phase, so a client need not know the printer's numbers. */
    static final String IDLE = "idle", PREPARING = "preparing", PRINTING = "printing", PAUSING = "pausing", PAUSED = "paused", RESUMING = "resuming",
        STOPPING = "stopping", STOPPED = "stopped", COMPLETE = "complete", BUSY = "busy", OFFLINE = "offline";
    /** A status no newer than this counts as not fresh any more (the cloud's own limit; a local status comes every 15 s). */
    static final long STALE_MS = 60_000;

    /** What the service knows beyond the printer's own status. */
    static final class Context {
        String printerName = "Centauri Carbon 2", route = "not connected";
        boolean fresh;
        /** When the shown status arrived (ms), 0 unknown. */
        long statusAt;
        /** The print being recorded: when it started (ms), 0 none. */
        long printStartedAt;
        /** Leave the file name out (for a relay whose readers should not see it). */
        boolean hideFile;
    }

    static JSONObject build(JSONObject status, Context c, long nowMs) {
        try {
            JSONObject out = new JSONObject().put("schema", SCHEMA).put("updated_at", iso(nowMs));
            out.put("printer", new JSONObject().put("name", c.printerName).put("model", "Centauri Carbon 2"));
            JSONObject connection = new JSONObject().put("route", c.route).put("fresh", c.fresh && status != null);
            if (c.statusAt > 0) connection.put("status_at", iso(c.statusAt)).put("age_s", Math.max(0, (nowMs - c.statusAt) / 1000));
            out.put("connection", connection);
            JSONObject machine = status == null ? null : status.optJSONObject("machine_status");
            if (status == null || !c.fresh || machine == null) {
                return out.put("state", new JSONObject().put("phase", OFFLINE).put("words", status == null || !c.fresh ? "No status" : "Waiting for printer").put("printing", false));
            }
            int code = machine.optInt("status", -1), sub = machine.optInt("sub_status", -1);
            String phase = phase(code, sub);
            boolean active = code == 2 && !STOPPED.equals(phase) && !COMPLETE.equals(phase);
            JSONObject state = new JSONObject().put("phase", phase).put("words", StatusPresentation.state(status)).put("printing", active)
                .put("machine_status", code).put("sub_status", sub);
            JSONArray faults = new JSONArray(); String codes = StatusPresentation.faultCodes(status);
            if (!codes.isEmpty()) for (String f : codes.split(", ")) if (!f.equals("…")) faults.put(f);
            state.put("faults", faults);
            out.put("state", state);

            JSONObject print = new JSONObject(), ps = status.optJSONObject("print_status");
            if (machine.has("progress")) print.put("progress", Math.max(0, Math.min(100, machine.optInt("progress"))));
            if (ps != null && code == 2) {
                String file = StatusPresentation.clean(ps.optString("filename", "")).replaceFirst("(?i)\\.gcode$", "");
                if (!c.hideFile && !file.isEmpty()) print.put("file", file);
                if (ps.has("current_layer")) print.put("layer", ps.optInt("current_layer"));
                if (ps.has("total_layer")) print.put("total_layers", ps.optInt("total_layer"));
                long remaining = ps.optLong("remaining_time_sec", -1);
                if (active && remaining >= 0) print.put("remaining_s", remaining).put("done_at", iso(nowMs + remaining * 1000));
                if (c.printStartedAt > 0) print.put("started_at", iso(c.printStartedAt)).put("elapsed_s", Math.max(0, (nowMs - c.printStartedAt) / 1000));
            }
            if (print.length() > 0) out.put("print", print);

            JSONObject temps = new JSONObject();
            temperature(temps, "nozzle", status.optJSONObject("extruder"));
            temperature(temps, "bed", status.optJSONObject("heater_bed"));
            JSONObject chamber = status.optJSONObject("ztemperature_sensor");
            if (chamber != null && finite(chamber.optDouble("temperature"))) temps.put("chamber", new JSONObject().put("now", round(chamber.optDouble("temperature"))));
            if (temps.length() > 0) out.put("temperatures", temps);
            JSONObject fans = new JSONObject(), reported = status.optJSONObject("fans");
            fan(fans, "part", reported, "fan"); fan(fans, "aux", reported, "aux_fan"); fan(fans, "chamber", reported, "box_fan");
            if (fans.length() > 0) out.put("fans", fans);
            JSONObject led = status.optJSONObject("led"); if (led != null && led.has("status")) out.put("light", led.optInt("status") != 0);
            return out;
        } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }

    /** The phase for the printer's machine state and step. */
    static String phase(int code, int sub) {
        if (code == 1) return IDLE;
        if (code != 2) return code == 0 ? PREPARING : BUSY;
        switch (sub) {
            case 2075: case 2402: return PRINTING;
            case 2501: return PAUSING;
            case 2502: case 2505: return PAUSED;
            case 2401: return RESUMING;
            case 2503: return STOPPING;
            case 2504: return STOPPED;
            case 2077: return COMPLETE;
            default: return PREPARING;
        }
    }

    /** A print event (Automation.Event) for other apps: its type, when, and the print's file and progress then. */
    static JSONObject event(String type, String words, String file, int layer, int total, int progress, boolean hideFile, long nowMs) {
        try {
            JSONObject out = new JSONObject().put("schema", "linkworkshop.event/1").put("type", type).put("words", words).put("at", iso(nowMs));
            String name = StatusPresentation.clean(file == null ? "" : file).replaceFirst("(?i)\\.gcode$", "");
            if (!hideFile && !name.isEmpty()) out.put("file", name);
            if (layer >= 0) out.put("layer", layer);
            if (total >= 0) out.put("total_layers", total);
            if (progress >= 0) out.put("progress", progress);
            return out;
        } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }

    /**
     * A status built earlier (`builtAt`), as it stands now: its times moved on, and offline, as build makes it, once no
     * status has come for STALE_MS (the phone stopped hearing from the printer without anything saying so). Pure.
     */
    static JSONObject aged(String built, long builtAt, long nowMs) {
        try {
            JSONObject s = new JSONObject(built == null || built.isEmpty() ? "{}" : built);
            s.put("updated_at", iso(nowMs));
            long moved = builtAt > 0 ? Math.max(0, nowMs - builtAt) / 1000 : 0;
            JSONObject c = s.optJSONObject("connection"), p = s.optJSONObject("print");
            if (c != null && c.has("age_s")) c.put("age_s", c.optLong("age_s") + moved);
            if (p != null && p.has("elapsed_s")) p.put("elapsed_s", p.optLong("elapsed_s") + moved);
            long age = c != null && c.has("age_s") ? c.optLong("age_s") : moved;
            if (c != null && c.optBoolean("fresh") && age * 1000 > STALE_MS) {
                c.put("fresh", false);
                s.put("state", new JSONObject().put("phase", OFFLINE).put("words", "No status").put("printing", false));
                s.remove("print"); s.remove("temperatures"); s.remove("fans"); s.remove("light");
            }
            return s;
        } catch (JSONException unreadable) { return new JSONObject(); }
    }

    static String iso(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT); f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(ms));
    }
    private static double round(double v) { return Math.round(v * 10) / 10.0; }
    private static void temperature(JSONObject out, String key, JSONObject value) throws JSONException {
        // A reading the printer sent as null or text is left out, never shown as 0.
        if (value == null || !finite(value.optDouble("temperature"))) return;
        JSONObject t = new JSONObject().put("now", round(value.optDouble("temperature")));
        if (finite(value.optDouble("target"))) t.put("target", round(value.optDouble("target")));
        out.put(key, t);
    }
    private static void fan(JSONObject out, String key, JSONObject fans, String name) throws JSONException {
        JSONObject fan = fans == null ? null : fans.optJSONObject(name);
        if (fan != null && finite(fan.optDouble("speed"))) out.put(key, Math.round(fan.optDouble("speed") * 100 / 255));
    }
    private static boolean finite(double v) { return !Double.isNaN(v) && !Double.isInfinite(v); }

    private IntegrationStatus() { }
}
