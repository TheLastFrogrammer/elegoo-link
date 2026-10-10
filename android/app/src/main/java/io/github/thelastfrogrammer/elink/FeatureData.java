package io.github.thelastfrogrammer.elink;

import org.json.*;
import java.net.URI;
import java.util.Locale;

/** Presentation and validation of CC2 file/query data. No wire-field names are guessed. */
public final class FeatureData {
    private FeatureData() { }
    public static String size(long value) {
        if (value < 0) return "Not reported";
        if (value < 1024) return value + " B";
        if (value < 1048576) return String.format(Locale.ROOT, "%.1f KiB", value / 1024.0);
        return String.format(Locale.ROOT, "%.1f MiB", value / 1048576.0);
    }
    public static String file(JSONObject file) {
        StringBuilder text = new StringBuilder(StatusPresentation.clean(file.optString("filename", "Unnamed file")));
        if (file.has("size")) text.append("\n").append(size(file.optLong("size", -1)));
        if (file.has("layer")) text.append(" · ").append(file.optInt("layer")).append(" layers");
        if (file.has("print_time")) text.append("\nEstimated print time: ").append(duration(file.optLong("print_time", -1)));
        if (file.has("total_filament_used")) text.append("\nFilament used (reported): ").append(StatusPresentation.clean(file.opt("total_filament_used").toString()));
        if (file.has("color_map")) text.append("\nSliced filament information: ").append(StatusPresentation.clean(file.opt("color_map").toString()));
        return text.toString();
    }
    /**
     * The printer's file list with every entry as {"filename", …}: entries that name the file differently (file_name, name,
     * path, file_path) or are plain names are given "filename"; folders (is_dir) and nameless entries are dropped. The CC2 answers
     * locally with "filename", but other routes (the cloud) may not.
     */
    public static JSONObject normalizeFiles(JSONObject result) {
        JSONArray list = result == null ? null : result.optJSONArray("file_list");
        if (list == null) return result == null ? new JSONObject() : result;
        JSONArray out = new JSONArray();
        for (int i = 0; i < list.length(); i++) {
            Object entry = list.opt(i);
            try {
                if (entry instanceof String) { if (!((String) entry).isEmpty()) out.put(new JSONObject().put("filename", entry)); continue; }
                if (!(entry instanceof JSONObject)) continue;
                JSONObject file = (JSONObject) entry;
                if (file.optBoolean("is_dir", false)) continue;
                String name = file.optString("filename", "");
                for (String key : new String[] {"file_name", "name", "file_path", "path"}) if (name.isEmpty()) name = file.optString(key, "");
                name = name.replaceFirst("^.*/", "");
                if (name.isEmpty()) continue;
                JSONObject copy = new JSONObject(file.toString()).put("filename", name);
                if (!copy.has("size") && copy.has("file_size")) copy.put("size", copy.opt("file_size"));
                if (!copy.has("layer") && copy.has("total_layer")) copy.put("layer", copy.opt("total_layer"));
                out.put(copy);
            } catch (JSONException skipped) { }
        }
        try { return new JSONObject(result.toString()).put("file_list", out); } catch (JSONException impossible) { return result; }
    }
    /** What a file list's entries look like, for diagnostics: the type and field names of the first entry, never its values. */
    public static String fileEntryShape(JSONObject result) {
        JSONArray list = result == null ? null : result.optJSONArray("file_list");
        if (list == null || list.length() == 0) return "no entries";
        Object first = list.opt(0);
        if (first instanceof JSONObject) { java.util.List<String> keys = new java.util.ArrayList<>(); java.util.Iterator<String> it = ((JSONObject) first).keys(); while (it.hasNext()) keys.add(it.next()); java.util.Collections.sort(keys); return "objects with " + keys; }
        return first == null ? "null entries" : first.getClass().getSimpleName() + " entries";
    }
    /** Header for the printer file list: storage, count, page offset, and a warning when the list may be out of date. A benign "received" message is not repeated. */
    public static String fileSummary(String message, String storage, boolean haveList, int count, int offset, boolean fresh, boolean refreshing) {
        if (!haveList) return message;
        StringBuilder text = new StringBuilder();
        if (!message.isEmpty() && !message.equals("Files received from printer.") && !message.startsWith("Loading")) text.append(message).append("\n");
        text.append(storage.equals("local") ? "Internal storage" : "USB drive").append(" · ").append(count).append(count == 1 ? " file" : " files");
        if (offset > 0) text.append(" · from ").append(offset + 1);
        if (refreshing) text.append(" · refreshing…"); else if (!fresh) text.append(" · may be out of date, refresh before starting or deleting");
        return text.toString();
    }
    public static String duration(long seconds) { return seconds < 0 ? "Not reported" : seconds / 3600 + "h " + seconds % 3600 / 60 + "m"; }
    public static String history(JSONObject result) {
        JSONArray rows = result.optJSONArray("history_task_list");
        if (rows == null) return "Print history has not been reported.";
        if (rows.length() == 0) return "No print history reported.";
        StringBuilder text = new StringBuilder();
        for (int i = rows.length() - 1; i >= Math.max(0, rows.length() - 50); i--) {
            JSONObject row = rows.optJSONObject(i); if (row == null) continue;
            if (text.length() > 0) text.append("\n\n");
            int state = row.optInt("task_status", -1);
            text.append(StatusPresentation.clean(row.optString("task_name", "Unnamed job"))).append("\n")
                .append(state == 1 ? "Completed" : state == 2 ? "Cancelled" : "Reported state " + state);
            if (row.has("begin_time") && row.has("end_time")) {
                long elapsed = row.optLong("end_time") - row.optLong("begin_time");
                if (elapsed >= 0) text.append(" · ").append(duration(elapsed));
            }
            switch (row.optInt("time_lapse_video_status", 0)) {
                case 1: text.append("\nTimelapse recorded; the printer has not made the video yet."); break;
                case 2: text.append("\nTimelapse video ready").append(videoSize(row)).append("."); break;
                case 3: text.append("\nThe printer could not make the timelapse video."); break;
                default: break;
            }
        }
        return text.toString();
    }
    /** One {name, detail} pair per history entry, newest first (at most 50): the result, how long it took, and the timelapse state. */
    public static java.util.List<String[]> historyEntries(JSONObject result) {
        java.util.List<String[]> list = new java.util.ArrayList<>();
        JSONArray rows = result == null ? null : result.optJSONArray("history_task_list");
        if (rows == null) return list;
        for (int i = rows.length() - 1; i >= Math.max(0, rows.length() - 50); i--) {
            JSONObject row = rows.optJSONObject(i); if (row == null) continue;
            int state = row.optInt("task_status", -1);
            String took = "";
            if (row.has("begin_time") && row.has("end_time")) { long elapsed = row.optLong("end_time") - row.optLong("begin_time"); if (elapsed >= 0) took = duration(elapsed); }
            String video;
            switch (row.optInt("time_lapse_video_status", 0)) {
                case 1: video = "Timelapse recorded, video not made yet"; break;
                case 2: video = "Timelapse ready" + videoSize(row); break;
                case 3: video = "Timelapse video failed"; break;
                default: video = ""; break;
            }
            list.add(new String[] {StatusPresentation.clean(row.optString("task_name", "Unnamed job")).replaceFirst("(?i)\\.gcode$", ""),
                StatusPresentation.joinParts(state == 1 ? "Completed" : state == 2 ? "Cancelled" : "Reported state " + state, took, video)});
        }
        return list;
    }
    /** The history entries behind {@link #historyEntries}, in the same order (newest first, at most 50). */
    public static java.util.List<JSONObject> historyRows(JSONObject result) {
        java.util.List<JSONObject> list = new java.util.ArrayList<>();
        JSONArray rows = result == null ? null : result.optJSONArray("history_task_list");
        if (rows == null) return list;
        for (int i = rows.length() - 1; i >= Math.max(0, rows.length() - 50); i--) { JSONObject row = rows.optJSONObject(i); if (row != null) list.add(row); }
        return list;
    }

    /** "Mon 3 Nov, 14:05" for epoch seconds (the printer's begin_time/end_time), or "" when the printer gave none. */
    static String when(long epochSeconds, java.util.Locale locale, java.util.TimeZone zone) {
        if (epochSeconds <= 0) return "";
        java.text.SimpleDateFormat format = new java.text.SimpleDateFormat("EEE d MMM, HH:mm", locale); format.setTimeZone(zone);
        return format.format(new java.util.Date(epochSeconds * 1000));
    }

    /**
     * Label/value lines for one history entry: what the entry itself (1036) reports, then whatever the printer answered to the detail
     * query (1037) with. Only fields that are present are shown. The detail answer's layout is not documented anywhere, so its scalar
     * fields are listed under the printer's own field names (readable, not interpreted); lists of objects, such as filament per tray,
     * become one line per item. Links, long texts and the thumbnail are left out.
     */
    public static java.util.List<String[]> historyDetail(JSONObject row, JSONObject detail, java.util.Locale locale, java.util.TimeZone zone) {
        java.util.List<String[]> lines = new java.util.ArrayList<>();
        if (row != null) {
            int state = row.optInt("task_status", -1);
            if (row.has("task_status")) lines.add(new String[] {"Result", state == 1 ? "Completed" : state == 2 ? "Cancelled" : "Reported state " + state});
            if (row.optLong("begin_time") > 0) lines.add(new String[] {"Started", when(row.optLong("begin_time"), locale, zone)});
            if (row.optLong("end_time") > 0) lines.add(new String[] {"Ended", when(row.optLong("end_time"), locale, zone)});
            if (row.has("begin_time") && row.has("end_time") && row.optLong("end_time") >= row.optLong("begin_time") && row.optLong("begin_time") > 0)
                lines.add(new String[] {"Duration", duration(row.optLong("end_time") - row.optLong("begin_time"))});
            int video = row.optInt("time_lapse_video_status", 0);
            if (video == 1) lines.add(new String[] {"Timelapse", "Recorded, video not made yet"});
            else if (video == 2) lines.add(new String[] {"Timelapse", ("Video ready" + videoSize(row)).trim()});
            else if (video == 3) lines.add(new String[] {"Timelapse", "The printer could not make the video"});
        }
        if (detail != null) flatten(detail, "", lines, 0);
        return lines;
    }
    private static final java.util.Set<String> HIDDEN = new java.util.HashSet<>(java.util.Arrays.asList("error_code", "error_msg", "thumbnail", "task_id", "id", "md5"));
    private static void flatten(JSONObject object, String prefix, java.util.List<String[]> lines, int depth) {
        java.util.List<String> keys = new java.util.ArrayList<>();
        for (java.util.Iterator<String> it = object.keys(); it.hasNext(); ) keys.add(it.next());
        java.util.Collections.sort(keys);
        for (String key : keys) {
            if (lines.size() >= 40 || HIDDEN.contains(key) || key.toLowerCase(java.util.Locale.ROOT).contains("thumb") || key.toLowerCase(java.util.Locale.ROOT).contains("url")) continue;
            Object value = object.opt(key); String label = prefix + humanize(key);
            if (value instanceof JSONObject && depth < 2) flatten((JSONObject) value, label + " · ", lines, depth + 1);
            else if (value instanceof JSONArray && depth < 2) {
                JSONArray items = (JSONArray) value;
                for (int i = 0; i < items.length() && i < 12; i++) {
                    Object item = items.opt(i);
                    if (item instanceof JSONObject) {
                        java.util.List<String[]> inner = new java.util.ArrayList<>(); flatten((JSONObject) item, "", inner, 2);
                        StringBuilder text = new StringBuilder();
                        for (String[] pair : inner) text.append(text.length() > 0 ? " · " : "").append(pair[0]).append(" ").append(pair[1]);
                        if (text.length() > 0) lines.add(new String[] {label + " " + (i + 1), text.toString()});
                    } else if (scalar(item)) lines.add(new String[] {label + " " + (i + 1), String.valueOf(item)});
                }
            } else if (scalar(value)) lines.add(new String[] {label, String.valueOf(value)});
        }
    }
    private static boolean scalar(Object value) {
        if (value instanceof Number || value instanceof Boolean) return true;
        return value instanceof String && !((String) value).isEmpty() && ((String) value).length() <= 120 && !((String) value).contains("://");
    }
    private static String humanize(String key) {
        String text = key.replace('_', ' ').replaceAll("([a-z])([A-Z])", "$1 $2").trim().toLowerCase(java.util.Locale.ROOT);
        return text.isEmpty() ? key : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    /** History entries whose timelapse video is ready to download (time_lapse_video_status 2), newest first, at most 10. */
    public static java.util.List<JSONObject> timelapses(JSONObject result) {
        java.util.List<JSONObject> ready = new java.util.ArrayList<>();
        JSONArray rows = result == null ? null : result.optJSONArray("history_task_list");
        if (rows == null) return ready;
        for (int i = rows.length() - 1; i >= 0 && ready.size() < 10; i--) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null || row.optInt("time_lapse_video_status") != 2) continue;
            try { Cc2Codec.timelapse(row.optString("time_lapse_video_url")); ready.add(row); } catch (IllegalArgumentException unusable) { }
        }
        return ready;
    }
    /** " (12.3 MB, 0:45)" from a history entry's video size (bytes) and duration (seconds), or "". */
    public static String videoSize(JSONObject row) {
        long bytes = row.optLong("time_lapse_video_size", 0), seconds = row.optLong("time_lapse_video_duration", 0);
        java.util.List<String> parts = new java.util.ArrayList<>();
        if (bytes > 0) parts.add(String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1048576.0));
        if (seconds > 0) parts.add(String.format(java.util.Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60));
        return parts.isEmpty() ? "" : " (" + String.join(", ", parts) + ")";
    }
    public static String disk(JSONObject data) { return "Internal storage: " + size(data.optLong("used_bytes", -1)) + " used / " + size(data.optLong("total_bytes", -1)); }
    public static String cameraUrl(String host, String supplied) throws Exception {
        new PrinterHttp(host, "");
        URI uri = new URI(supplied);
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
            || !host.equals(uri.getHost()) || uri.getUserInfo() != null || uri.getFragment() != null || uri.getPort() == 0 || uri.getPort() > 65535)
            throw new IllegalArgumentException("Use a camera URL on the selected printer's IP");
        return uri.toASCIIString();
    }
    public static boolean mappings(JSONObject canvas, JSONArray maps) {
        if (maps.length() == 0) return true;
        if (canvas == null || canvas.optJSONArray("canvas_list") == null) return false;
        JSONArray units = canvas.optJSONArray("canvas_list");
        for (int i = 0; i < maps.length(); i++) {
            JSONObject map = maps.optJSONObject(i); if (map == null) return false;
            boolean found = false;
            for (int u = 0; u < units.length(); u++) {
                JSONObject unit = units.optJSONObject(u); if (unit == null || unit.optInt("canvas_id", -1) != map.optInt("canvas_id", -2)
                    || !(Boolean.TRUE.equals(unit.opt("connected")) || unit.optInt("connected", 0) == 1)) continue;
                JSONArray trays = unit.optJSONArray("tray_list"); if (trays == null) continue;
                for (int t = 0; t < trays.length(); t++) {
                    JSONObject tray = trays.optJSONObject(t);
                    if (tray != null && tray.optInt("tray_id", -1) == map.optInt("tray_id", -2) && !tray.optString("filament_type").isEmpty()) found = true;
                }
            }
            if (!found) return false;
        }
        return true;
    }
}
