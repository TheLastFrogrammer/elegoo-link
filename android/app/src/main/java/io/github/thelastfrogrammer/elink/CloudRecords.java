package io.github.thelastfrogrammer.elink;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Elegoo's cloud records of a printer, in the shapes the app already shows. In cloud mode the printer reports each new file
 * and each finished print to the cloud itself, and Elegoo's SDK reads its file list, file details and history from those
 * records (`local-file/page`, `device/event-data/page?eventKey=history_task`) rather than asking the printer (1044 and 1036
 * through the cloud often get no reply). Field names follow the SDK's readers. Independent of Android.
 */
final class CloudRecords {
    /** Rows a page asks for: the same 50 the printer's own list uses. */
    static final int PAGE = 50;

    /** A `local-file/page` answer as a printer 1044 result: file_list (snake_case fields), offset and total. */
    static JSONObject files(JSONObject data, int offset) {
        JSONObject out = new JSONObject(); JSONArray list = new JSONArray();
        JSONArray items = data == null ? null : data.optJSONArray("list");
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null || item.optString("filename", "").isEmpty()) continue;
            try {
                JSONObject file = new JSONObject().put("filename", item.optString("filename"));
                copy(item, "size", file, "size");
                copy(item, "printTime", file, "print_time");
                copy(item, "layer", file, "layer");
                copy(item, "totalFilamentUsed", file, "total_filament_used");
                copy(item, "totalPrintTimes", file, "total_print_times");
                copy(item, "type", file, "type");
                if (item.has("createTime")) file.put("create_time", CloudApi.seconds(item.optLong("createTime")));
                if (item.has("lastPrintTime")) file.put("last_print_time", CloudApi.seconds(item.optLong("lastPrintTime")));
                Object colours = item.opt("colorMap");
                if (colours instanceof JSONArray) file.put("color_map", colours);
                else if (colours instanceof String) try { file.put("color_map", new JSONArray((String) colours)); } catch (JSONException notAList) { }
                // The record names the thumbnail the printer stored; the app asks the cloud for a link to that name only.
                String thumbnail = item.optString("thumbnail", "");
                if (!thumbnail.isEmpty() && !"null".equals(thumbnail)) file.put("cloud_thumbnail", thumbnail);
                list.put(file);
            } catch (JSONException skipped) { }
        }
        try { out.put("file_list", list).put("offset", offset).put("total", data == null ? list.length() : data.optInt("total", list.length())); } catch (JSONException impossible) { }
        return out;
    }

    /**
     * A `device/event-data/page` answer (newest first) as a printer 1036 result: history_task_list oldest first, as the printer
     * sends it, from each entry's eventValue. Times become epoch seconds; the cloud keeps no timelapse state.
     */
    static JSONObject history(JSONObject data) {
        JSONArray rows = new JSONArray();
        JSONArray items = data == null ? null : data.optJSONArray("list");
        for (int i = items == null ? -1 : items.length() - 1; i >= 0; i--) {
            JSONObject item = items.optJSONObject(i); if (item == null) continue;
            JSONObject value;
            Object raw = item.opt("eventValue");
            try { value = raw instanceof JSONObject ? (JSONObject) raw : new JSONObject(String.valueOf(raw)); } catch (JSONException notJson) { continue; }
            try {
                JSONObject row = new JSONObject().put("task_name", value.optString("task_name", ""));
                if (value.has("task_status")) row.put("task_status", value.optInt("task_status"));
                if (value.has("begin_time")) row.put("begin_time", CloudApi.seconds(value.optLong("begin_time")));
                if (value.has("end_time")) row.put("end_time", CloudApi.seconds(value.optLong("end_time")));
                String id = value.optString("task_id", item.optString("id", ""));
                if (!id.isEmpty()) row.put("task_id", id);
                row.put("from_cloud", true);
                rows.put(row);
            } catch (JSONException skipped) { }
        }
        JSONObject out = new JSONObject();
        try { out.put("history_task_list", rows).put("total", data == null ? rows.length() : data.optInt("total", rows.length())); } catch (JSONException impossible) { }
        return out;
    }

    private static void copy(JSONObject from, String key, JSONObject to, String as) throws JSONException {
        Object value = from.opt(key);
        if (value != null && value != JSONObject.NULL) to.put(as, value);
    }
}
