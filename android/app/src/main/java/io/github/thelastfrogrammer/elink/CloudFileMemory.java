package io.github.thelastfrogrammer.elink;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Where the G-code of files this app sent through the Elegoo cloud lives in Elegoo's storage: printer file name to storage object
 * name, per printer. Kept in the app's private storage. The object name is only a path; the download link (accessUrl) is never kept.
 */
final class CloudFileMemory {
    static final int KEEP = 200;
    private final SharedPreferences prefs;

    CloudFileMemory(SharedPreferences prefs) { this.prefs = prefs; }
    CloudFileMemory(Context context) { this(context.getSharedPreferences("cloud-files", Context.MODE_PRIVATE)); }

    private static String key(String serial, String filename) { return serial + "/" + filename; }

    synchronized void remember(String serial, String filename, String objectName) {
        if (serial == null || serial.isEmpty() || filename == null || filename.isEmpty() || objectName == null || objectName.isEmpty()) return;
        try {
            JSONObject all = load();
            all.put(key(serial, filename), new JSONObject().put("object", objectName).put("at", System.currentTimeMillis()));
            trim(all);
            prefs.edit().putString("files", all.toString()).apply();
        } catch (org.json.JSONException ignored) { }
    }

    /** The storage object name for a file this app sent to that printer, or "". */
    synchronized String objectNameFor(String serial, String filename) {
        JSONObject entry = load().optJSONObject(key(serial, filename));
        return entry == null ? "" : entry.optString("object", "");
    }

    synchronized void forget(String serial, String filename) {
        JSONObject all = load(); all.remove(key(serial, filename));
        prefs.edit().putString("files", all.toString()).apply();
    }

    private JSONObject load() {
        try { return new JSONObject(prefs.getString("files", "{}")); } catch (org.json.JSONException error) { return new JSONObject(); }
    }

    private static void trim(JSONObject all) {
        if (all.length() <= KEEP) return;
        List<String> keys = new ArrayList<>();
        for (Iterator<String> it = all.keys(); it.hasNext(); ) keys.add(it.next());
        keys.sort((a, b) -> Long.compare(all.optJSONObject(a).optLong("at"), all.optJSONObject(b).optLong("at")));
        for (int i = 0; i < keys.size() - KEEP; i++) all.remove(keys.get(i));
    }
}
