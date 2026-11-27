package io.github.thelastfrogrammer.elink;

import java.util.Iterator;
import java.util.Map;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The status kept while cloud live updates are connected, when a periodic status read arrives. Live updates carry only
 * the fields that changed, so the status they built is newer than the cloud's stored copy of most fields; seen on a CC2,
 * the stored progress and time left stayed at 0 for a whole print while live updates moved them on, and taking the read
 * whole reset them every minute. So a field keeps its live value unless the read's copy was reported after the last live
 * update; fields live updates never sent come from the read. Pure.
 */
final class CloudMerge {
    /** A few seconds' allowance for the cloud's clock against the phone's. */
    static final long SKEW_MS = 2_000;

    /**
     * `live`: the status live updates built. `polled`: the read, its fields' report times in `fieldAt` ("group.key" →
     * epoch ms or s). `liveAt`: when the last live update arrived (phone ms). Returns a new status; neither input changes.
     */
    static JSONObject newest(JSONObject live, JSONObject polled, Map<String, Long> fieldAt, long liveAt) throws JSONException {
        JSONObject out = new JSONObject(live.toString());
        for (Iterator<String> groups = polled.keys(); groups.hasNext(); ) {
            String group = groups.next();
            Object value = polled.get(group);
            if (!(value instanceof JSONObject)) { if (!out.has(group)) out.put(group, value); continue; }
            JSONObject fields = (JSONObject) value, mine = out.optJSONObject(group);
            if (mine == null) { out.put(group, new JSONObject(fields.toString())); continue; }
            for (Iterator<String> keys = fields.keys(); keys.hasNext(); ) {
                String key = keys.next();
                Long at = fieldAt.get(group + "." + key);
                long ms = at == null ? 0 : CloudApi.seconds(at) * 1000;
                if (!mine.has(key) || ms > liveAt + SKEW_MS) mine.put(key, fields.get(key));
            }
        }
        return out;
    }

    private CloudMerge() { }
}
