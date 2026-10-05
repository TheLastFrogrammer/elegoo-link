package io.github.thelastfrogrammer.elink;

import android.content.*;
import org.json.*;

/** Names, IPs and serials only. Secrets live exclusively in CredentialStore. */
public final class ProfileStore {
    private final SharedPreferences prefs;
    public ProfileStore(Context context) { prefs = context.getSharedPreferences("workshop-settings", Context.MODE_PRIVATE); }
    public JSONArray all() { try { return new JSONArray(prefs.getString("profiles", "[]")); } catch (Exception error) { return new JSONArray(); } }
    public JSONObject find(String host) {
        JSONArray rows = all(); for (int i = 0; i < rows.length(); i++) { JSONObject row = rows.optJSONObject(i); if (row != null && host.equals(row.optString("host"))) return row; } return new JSONObject();
    }
    public void save(String name, String host, String serial) throws Exception {
        new PrinterHttp(host, ""); if (!serial.isEmpty() && !Cc2Discovery.validSerial(serial)) throw new IllegalArgumentException("Invalid serial");
        JSONArray rows = all(), next = new JSONArray();
        for (int i = 0; i < rows.length(); i++) { JSONObject row = rows.optJSONObject(i); if (row != null && !host.equals(row.optString("host"))) next.put(row); }
        if (next.length() >= 20) throw new IllegalStateException("Twenty printer profiles supported");
        next.put(new JSONObject().put("name", StatusPresentation.clean(name.isEmpty() ? host : name)).put("host", host).put("serial", serial));
        if (!prefs.edit().putString("profiles", next.toString()).commit()) throw new IllegalStateException("Cannot save profile");
    }
    public void remove(String host) {
        JSONArray rows = all(), next = new JSONArray(); for (int i = 0; i < rows.length(); i++) { JSONObject row = rows.optJSONObject(i); if (row != null && !host.equals(row.optString("host"))) next.put(row); }
        prefs.edit().putString("profiles", next.toString()).apply();
    }
}
