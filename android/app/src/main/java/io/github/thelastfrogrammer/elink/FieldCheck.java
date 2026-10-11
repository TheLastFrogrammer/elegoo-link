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
        new Check("local", "Connection", "Local Wi-Fi connection", "Settings: enter the printer's IP address and access code, then tap Connect. Monitor should show Local and live temperatures.", 3),
        new Check("cloud", "Connection", "Cloud monitoring", "Settings: sign in with Elegoo. Monitor should show Cloud and update within a minute.", 3),
        new Check("status", "Monitor", "Status matches the printer", "During a print, compare Monitor with the printer's own screen. Temperatures, progress, layer and time left should match.", 0),
        new Check("trays", "Monitor", "CANVAS trays match", "On Monitor, look at the colour strip. It should show each loaded tray, with the active one outlined.", 0),
        new Check("monitorCamera", "Monitor", "Camera on Monitor while printing", "During a print, tap Watch camera on Monitor. The picture should stop when you switch to another tab.", 0),
        new Check("files", "Files", "Files tab lists the printer's files", "Open Files. The list should show the printer's files. If it stays blank, mark Problem and say what you saw.", 1),
        new Check("printSetup", "Files", "Print setup chooses trays", "Tap a file, then Print setup. It should show \"Trays are up to date\" and let you choose a tray for each filament.", 1),
        new Check("start", "Files", "A print started from the app", "Start a small print from Print setup. It should use the trays you chose.", 1),
        new Check("timelapse", "Files", "Timelapse download", "On the local connection, open History, storage and recordings on the Files tab. Download a finished print's timelapse.", 1),
        new Check("localCamera", "Camera", "Local camera stream", "On the printer's Wi-Fi, open the Camera tab and start the printer's own camera. A live picture should appear.", 2),
        new Check("cloudCamera", "Camera", "Cloud camera", "On the cloud connection, open the Camera tab and watch through the Elegoo cloud. A live picture should appear.", 2),
        new Check("follow", "Live toolpath", "Live toolpath follows the print", "During a print, open Live toolpath. The layer and the nozzle dot should keep up with the printer's own screen.", LIVE),
        new Check("lineUp", "Live toolpath", "Drawing sits on the real print", "Look at the print through the camera. The drawing should sit on the real print; if it is off to one side, set Plate edges in the line-up and note the margins.", LIVE),
        new Check("timing", "Live toolpath", "Drawing and picture move together", "Try the measured cloud delay and \"Move in step with the camera's pictures\". The drawing and the picture should move together; note the delay that works.", LIVE),
        new Check("resume", "Live toolpath", "Recovers after leaving the app", "Switch to another app for a minute, then come back. The drawing should jump to the printer's current position at once.", LIVE),
        new Check("mask", "Live toolpath", "Printhead mask covers the head", "Adjust Printhead size until the mask covers the real head. Note the sizes that work, so they can become the defaults.", LIVE),
        new Check("slice", "Slicer", "Slicing on the phone", "Slice a model on the phone. Note how long it took and whether the phone stayed responsive.", SLICE),
        new Check("upload", "Slicer", "Upload and print from the slice", "From the slice result, upload and print, locally or through the cloud. The print should start on the printer.", SLICE),
        new Check("maintenance", "Printer", "Maintenance commands", "In Maintenance, home the printer, move an axis or level the bed, and watch the printer do it.", 0),
        new Check("alerts", "Printer", "Alerts in the background", "Leave the app in the background while connected (don't force-stop it). When a print finishes or a fault happens, a notification should appear.", NOWHERE)));

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
