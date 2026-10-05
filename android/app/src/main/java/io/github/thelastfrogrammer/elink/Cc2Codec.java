/* Derived from ELEGOO's Apache-2.0 CC2 adapter. See android/PROTOCOL.md.
 * Android port and modifications, 2026. */
package io.github.thelastfrogrammer.elink;

import org.json.JSONException;
import org.json.JSONObject;
import java.util.Iterator;

/** Pure protocol logic, independent of Android and MQTT transport. */
public final class Cc2Codec {
    public static final int ATTRIBUTES = 1001, STATUS = 1002, PAUSE = 1021, STOP = 1022;
    private JSONObject snapshot;
    private int sequence = -1, gaps;

    public static JSONObject request(int id, int method) throws JSONException {
        if (method != ATTRIBUTES && method != STATUS && method != PAUSE && method != STOP)
            throw new IllegalArgumentException("Unverified command");
        return new JSONObject().put("id", id).put("method", method).put("params", new JSONObject());
    }

    public static boolean validRegistration(JSONObject message, String clientId) {
        return clientId.equals(message.optString("client_id")) && "ok".equals(message.optString("error"));
    }

    /** Returns false when a delta has no baseline or sequence gaps require a refresh. */
    public synchronized boolean accept(JSONObject message) throws JSONException {
        int method = message.optInt("method", -1);
        JSONObject result = message.optJSONObject("result");
        if (result == null || (method != STATUS && method != 6000)) return false;
        if (method == STATUS) {
            if (result.optInt("error_code", -1) != 0) return false;
            snapshot = copy(result);
            sequence = -1;
            gaps = 0;
            return true;
        }
        if (snapshot == null) return false;
        int id = message.optInt("id", -1);
        if (id >= 0) {
            boolean continuous = sequence == -1 || id == sequence + 1 || id == 0;
            sequence = id;
            gaps = continuous ? 0 : gaps + 1;
            if (gaps >= 5) { snapshot = null; return false; }
        }
        merge(snapshot, result);
        return true;
    }

    public synchronized JSONObject snapshot() throws JSONException {
        return snapshot == null ? new JSONObject() : copy(snapshot);
    }

    private static JSONObject copy(JSONObject object) throws JSONException { return new JSONObject(object.toString()); }
    private static void merge(JSONObject target, JSONObject source) throws JSONException {
        Iterator<String> keys = source.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = source.get(key);
            if (!"exception_code".equals(key) && value instanceof JSONObject && target.opt(key) instanceof JSONObject)
                merge(target.getJSONObject(key), (JSONObject) value);
            else target.put(key, value);
        }
    }

    public static boolean canPause(JSONObject status) {
        JSONObject machine = status.optJSONObject("machine_status");
        return machine != null && machine.optInt("status", -1) == 2 && machine.optInt("sub_status", -1) == 2075;
    }
    public static boolean canStop(JSONObject status) {
        JSONObject machine = status.optJSONObject("machine_status");
        if (machine == null || machine.optInt("status", -1) != 2) return false;
        int sub = machine.optInt("sub_status", -1);
        return sub != 2503 && sub != 2504 && sub != 2077;
    }
}
