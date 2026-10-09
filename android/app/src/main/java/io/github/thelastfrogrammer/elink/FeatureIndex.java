package io.github.thelastfrogrammer.elink;

import java.util.*;

/**
 * "Find a feature": every place in the app a hobbyist may look for, with the words they might use, ranked for a query.
 * Each feature names the tab it lives on and the visible text of the button or heading to bring into view; whether choosing
 * it presses that button is decided per feature (only for opening screens and dialogs, never for printer-changing actions).
 * Independent of Android.
 */
final class FeatureIndex {
    static final int MONITOR = 0, FILES = 1, CAMERA = 2, SETTINGS = 3;
    static final String[] TABS = {"Monitor", "Files", "Camera", "Settings"};
    /** What has to happen before the target is visible. */
    enum Prepare { NONE, HISTORY_OPEN, MAINTENANCE_OPEN, SETTINGS_LOCAL, SETTINGS_CLOUD, ADVANCED_CONNECTION }

    static final class Feature {
        final String id, title, words; final int tab; final String target; final Prepare prepare; final boolean press;
        /** The card heading to show instead when the target is hidden (for example printer controls while it prints). */
        String fallback = "";
        Feature or(String heading) { fallback = heading; return this; }
        Feature(String id, String title, String words, int tab, String target, Prepare prepare, boolean press) {
            this.id = id; this.title = title; this.words = words; this.tab = tab; this.target = target; this.prepare = prepare; this.press = press;
        }
        String where() { return TABS[tab]; }
    }

    /** press = true only where the button opens a screen or a dialog that asks again before changing anything. */
    static final List<Feature> ALL = Collections.unmodifiableList(Arrays.asList(
        // Monitor
        new Feature("toolpath", "Live toolpath", "toolpath gcode viewer layers 3d preview live follow print", MONITOR, "Live toolpath", Prepare.NONE, true),
        new Feature("temperature", "Set heater temperatures", "temperature heat preheat nozzle bed hotend", MONITOR, "Set heater temperatures", Prepare.NONE, true).or("Printer controls"),
        new Feature("fan", "Fan setting", "fan cooling part fan aux chamber", MONITOR, "Fan setting", Prepare.NONE, true).or("Printer controls"),
        new Feature("speed", "Print speed mode", "speed silent sport ludicrous balanced", MONITOR, "Print speed mode", Prepare.NONE, true).or("Printer controls"),
        new Feature("load", "Load or unload filament", "filament load unload change spool feed retract", MONITOR, "Load filament", Prepare.MAINTENANCE_OPEN, false).or("Maintenance"),
        new Feature("tray", "CANVAS tray filament", "canvas tray multicolour multi colour ams refill", MONITOR, "Load or unload a CANVAS tray", Prepare.MAINTENANCE_OPEN, false).or("Maintenance"),
        new Feature("move", "Move axes or home", "move jog axis axes home x y z bed", MONITOR, "Move axes", Prepare.MAINTENANCE_OPEN, false).or("Maintenance"),
        new Feature("level", "Auto-level bed", "level leveling levelling mesh bed calibration probe", MONITOR, "Auto-level bed", Prepare.MAINTENANCE_OPEN, false).or("Maintenance"),
        new Feature("vibration", "Vibration test", "vibration input shaping resonance shaper calibration", MONITOR, "Vibration test", Prepare.MAINTENANCE_OPEN, false).or("Maintenance"),
        // Files
        new Feature("printer-files", "Files on the printer", "files gcode printer storage usb print start delete download", FILES, "Printer files", Prepare.NONE, false),
        new Feature("history", "Print history and Print again", "history past prints reprint print again details", FILES, "Refresh history", Prepare.HISTORY_OPEN, false),
        new Feature("timelapse", "Timelapse videos", "timelapse video recording movie", FILES, "Refresh history", Prepare.HISTORY_OPEN, false),
        new Feature("recordings", "Print recordings and charts", "recordings charts graphs log temperature progress csv", FILES, "Print recordings", Prepare.HISTORY_OPEN, true),
        new Feature("storage", "Printer storage space", "storage space disk capacity free full", FILES, "Refresh storage", Prepare.HISTORY_OPEN, false),
        new Feature("slice", "Slice a model", "slice slicer stl 3mf obj step model prepare", FILES, "Slice a model", Prepare.NONE, true),
        new Feature("find-models", "Find models online", "find download thingiverse printables makerworld cults myminifactory search models online", FILES, "Find models online", Prepare.NONE, true),
        new Feature("calibration", "Calibration prints", "calibration pressure advance pa flow temperature tower retraction input shaping test print", FILES, "Slice a model", Prepare.NONE, true),
        new Feature("recent", "Recent slices", "recent sliced previous last gcode", FILES, "Recent slices", Prepare.NONE, true),
        new Feature("upload", "Send a G-code file from the phone", "upload send gcode phone choose file", FILES, "Choose G-code", Prepare.NONE, true),
        // Camera
        new Feature("camera", "Watch the camera", "camera video watch stream live view webcam", CAMERA, "Camera", Prepare.NONE, false),
        new Feature("camera-3d", "Line up the camera in 3D", "camera line up align overlay 3d position lens", CAMERA, "Line up the camera in 3D", Prepare.NONE, true),
        new Feature("snapshot", "Camera snapshot", "snapshot photo picture save image", CAMERA, "Save snapshot", Prepare.NONE, false),
        // Settings
        new Feature("connect", "Connect on Wi-Fi", "connect local lan wifi ip access code printer address", SETTINGS, "Printer connection", Prepare.SETTINGS_LOCAL, false),
        new Feature("cloud", "Elegoo cloud sign-in", "cloud elegoo account sign in login remote away", SETTINGS, "Elegoo cloud", Prepare.SETTINGS_CLOUD, false),
        new Feature("vpn", "Remote access through a VPN", "vpn remote tailscale away route", SETTINGS, "Remote access setup", Prepare.ADVANCED_CONNECTION, true),
        new Feature("printers", "Saved printers", "saved printers profiles switch printer", SETTINGS, "Saved printers", Prepare.SETTINGS_LOCAL, true),
        new Feature("appearance", "Appearance (dark mode)", "appearance theme dark light night", SETTINGS, "Appearance", Prepare.NONE, true),
        new Feature("alerts", "Notifications", "notifications alerts done finished fault", SETTINGS, "Completion and new fault notifications", Prepare.NONE, false),
        new Feature("probe", "Probe the printer", "probe diagnostics ports methods debug", SETTINGS, "Probe printer", Prepare.NONE, true),
        new Feature("diagnostics", "Share diagnostics", "diagnostics logs report bug share", SETTINGS, "Share diagnostics", Prepare.NONE, true),
        new Feature("help", "Connection help", "help connection trouble lan only access code where", SETTINGS, "Connection help", Prepare.NONE, true),
        new Feature("about", "About and licences", "about licence license version open source", SETTINGS, "About & licenses", Prepare.NONE, true)));

    static Feature byId(String id) { for (Feature f : ALL) if (f.id.equals(id)) return f; return null; }

    /**
     * Features for a query, best first: a title starting with the query, then a word of the title, then a search word, then
     * anywhere; every word of the query must match somewhere. An empty query lists the recently used first, then the rest.
     */
    static List<Feature> search(String query, List<String> recent) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<Feature> out = new ArrayList<>();
        if (q.isEmpty()) {
            for (String id : recent) { Feature f = byId(id); if (f != null && !out.contains(f)) out.add(f); }
            for (Feature f : ALL) if (!out.contains(f)) out.add(f);
            return out;
        }
        String[] terms = q.split("\\s+");
        List<Object[]> scored = new ArrayList<>();
        for (Feature f : ALL) {
            String title = f.title.toLowerCase(Locale.ROOT), words = f.words + " " + f.where().toLowerCase(Locale.ROOT);
            int score = 0; boolean all = true;
            for (String term : terms) {
                int s = title.startsWith(term) ? 40 : (" " + title).contains(" " + term) ? 30 : (" " + words + " ").contains(" " + term) ? 20 : title.contains(term) || words.contains(term) ? 10 : 0;
                if (s == 0) { all = false; break; }
                score += s;
            }
            if (!all) continue;
            int recency = recent.indexOf(f.id); if (recency >= 0) score += 5 - Math.min(4, recency);
            scored.add(new Object[] {score, f});
        }
        scored.sort((a, b) -> (Integer) b[0] - (Integer) a[0]);
        for (Object[] s : scored) out.add((Feature) s[1]);
        return out;
    }

    /** The recently used list after using `id`: it moves to the front; five are kept. */
    static List<String> used(List<String> recent, String id) {
        List<String> next = new ArrayList<>(recent); next.remove(id); next.add(0, id);
        while (next.size() > 5) next.remove(next.size() - 1);
        return next;
    }
}
