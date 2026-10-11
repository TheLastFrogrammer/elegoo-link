package io.github.thelastfrogrammer.elink;

import java.text.SimpleDateFormat;
import java.util.*;
import org.json.JSONObject;

/**
 * The hardware checklist (FIELD_TEST.md, on the phone): what to try, the user's result and note for each, and the report to
 * share. Recording a result never does anything to the printer; "Go there" only opens the screen. Independent of Android.
 */
final class FieldCheck {
    enum Result { NONE, WORKS, FAILS, SKIPPED }
    /** Where a check's "Go there" leads: a main tab (0–3), Live toolpath, the Slice screen, or nowhere. */
    static final int NOWHERE = -1, LIVE = 4, SLICE = 5;

    static final class Check {
        final String id, area, title, how; final int open;
        Check(String id, String area, String title, String how, int open) { this.id = id; this.area = area; this.title = title; this.how = how; this.open = open; }
    }

    static final List<Check> CHECKS = Collections.unmodifiableList(Arrays.asList(
        new Check("local", "Connection", "Local Wi-Fi connection", "Settings: enter the printer's IP address and access code, then Connect. Monitor should show Local and live temperatures.", 3),
        new Check("cloud", "Connection", "Cloud monitoring", "Settings: sign in with Elegoo. Monitor should show Cloud and update within a minute.", 3),
        new Check("status", "Monitor", "Status matches the printer", "Temperatures, progress, layer and time left match the printer's own screen.", 0),
        new Check("trays", "Monitor", "CANVAS trays match", "The colour strip shows the loaded trays and outlines the active one.", 0),
        new Check("monitorCamera", "Monitor", "Camera on Monitor while printing", "Tap Watch camera during a print; it should stop when you switch tabs.", 0),
        new Check("files", "Files", "Files tab lists the printer's files", "Open Files. If the list stays blank, say so here: the report includes what was on screen.", 1),
        new Check("printSetup", "Files", "Print setup chooses trays", "Tap a file, then Print setup: it should reach \"Trays are up to date\" and let you choose a tray for each filament.", 1),
        new Check("start", "Files", "A print started from the app", "Start a small print from Print setup: it should use the trays you chose.", 1),
        new Check("timelapse", "Files", "Timelapse download", "On the local connection, download a finished print's timelapse from History, storage and recordings.", 1),
        new Check("localCamera", "Camera", "Local camera stream", "On the printer's Wi-Fi: Camera tab, start the printer's own camera.", 2),
        new Check("cloudCamera", "Camera", "Cloud camera", "Camera tab: watch through the Elegoo cloud.", 2),
        new Check("follow", "Live toolpath", "Live toolpath follows the print", "During a print, the layer and the nozzle dot keep up with the printer.", LIVE),
        new Check("lineUp", "Live toolpath", "Drawing sits on the real print", "Seen from the camera, the toolpath lies on the real print. If it is off to one side, set Plate edges in the line-up and note the margins.", LIVE),
        new Check("timing", "Live toolpath", "Drawing and picture move together", "Try the measured cloud delay and \"Move in step with the camera's pictures\". Note the delay that works.", LIVE),
        new Check("resume", "Live toolpath", "Recovers after leaving the app", "Switch to another app for a minute, then come back: the drawing should be at the printer's position straight away.", LIVE),
        new Check("mask", "Live toolpath", "Printhead mask covers the head", "Printhead size: note the sizes that cover the real head, so they can become the defaults.", LIVE),
        new Check("slice", "Slicer", "Slicing on the phone", "Slice a model and note how long it took and whether the phone stayed responsive.", SLICE),
        new Check("upload", "Slicer", "Upload and print from the slice", "From the slice result, upload and print (locally or through the cloud).", SLICE),
        new Check("maintenance", "Printer", "Maintenance commands", "Home, move an axis or level the bed from Maintenance, and watch the printer do it.", 0),
        new Check("alerts", "Printer", "Alerts in the background", "With the app closed, a finished print or a fault should raise a notification.", NOWHERE)));

    static final class Entry {
        final Result result; final String note; final long at;
        Entry(Result result, String note, long at) { this.result = result; this.note = note == null ? "" : note; this.at = at; }
    }

    /** Saved results by check id; anything unreadable is simply not there. */
    static Map<String, Entry> parse(String json) {
        Map<String, Entry> out = new HashMap<>();
        if (json == null || json.isEmpty()) return out;
        try {
            JSONObject all = new JSONObject(json);
            for (Iterator<String> keys = all.keys(); keys.hasNext(); ) {
                String id = keys.next(); JSONObject e = all.optJSONObject(id); if (e == null) continue;
                Result result; try { result = Result.valueOf(e.optString("result", "NONE")); } catch (IllegalArgumentException unknown) { result = Result.NONE; }
                out.put(id, new Entry(result, e.optString("note"), e.optLong("at")));
            }
        } catch (org.json.JSONException ignored) { }
        return out;
    }

    static String toJson(Map<String, Entry> entries) {
        JSONObject all = new JSONObject();
        try { for (Map.Entry<String, Entry> e : entries.entrySet()) all.put(e.getKey(), new JSONObject().put("result", e.getValue().result.name()).put("note", e.getValue().note).put("at", e.getValue().at)); }
        catch (org.json.JSONException ignored) { }
        return all.toString();
    }

    /** "6 of 20 checked · 1 doesn't work". */
    static String summary(Map<String, Entry> entries) {
        int done = 0, fails = 0;
        for (Check c : CHECKS) { Entry e = entries.get(c.id); if (e != null && e.result != Result.NONE) { done++; if (e.result == Result.FAILS) fails++; } }
        return done + " of " + CHECKS.size() + " checked" + (fails > 0 ? " · " + fails + (fails == 1 ? " problem" : " problems") : "");
    }

    /** The checklist as plain text: by area, each check's result, when, and the note as the user wrote it. */
    static String report(Map<String, Entry> entries, String header, TimeZone zone) {
        SimpleDateFormat when = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT); when.setTimeZone(zone);
        StringBuilder text = new StringBuilder("Link Workshop field check\n").append(header.trim()).append("\n").append(summary(entries)).append("\n");
        String area = null;
        for (Check c : CHECKS) {
            if (!c.area.equals(area)) { area = c.area; text.append("\n## ").append(area).append("\n"); }
            Entry e = entries.get(c.id); Result r = e == null ? Result.NONE : e.result;
            text.append(r == Result.WORKS ? "✓ " : r == Result.FAILS ? "✕ " : r == Result.SKIPPED ? "– " : "· ").append(c.title)
                .append(r == Result.NONE ? " (not checked)" : r == Result.SKIPPED ? " (skipped)" : "");
            if (e != null && e.at > 0 && r != Result.NONE) text.append(" · ").append(when.format(new Date(e.at)));
            text.append("\n");
            if (e != null && !e.note.trim().isEmpty()) text.append("    ").append(e.note.trim().replace("\n", "\n    ")).append("\n");
        }
        return text.toString();
    }

    private FieldCheck() { }
}
