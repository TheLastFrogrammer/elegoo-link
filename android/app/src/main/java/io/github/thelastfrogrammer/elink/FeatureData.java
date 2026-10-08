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
