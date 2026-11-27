package io.github.thelastfrogrammer.elink;

import java.util.HashMap;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class CloudMergeTest {
    private static final long LIVE_AT = 1_800_000_000_000L;

    private static JSONObject status(int progress, long left, int layer, int state) throws Exception {
        return new JSONObject().put("machine_status", new JSONObject().put("status", state).put("progress", progress))
            .put("print_status", new JSONObject().put("remaining_time_sec", left).put("current_layer", layer));
    }

    @Test public void anOlderStoredCopyDoesNotTakeLiveProgressBack() throws Exception {
        // As on a CC2: the stored progress and time left stayed at 0 (reported at the print's start) while live moved on.
        JSONObject live = status(23, 3120, 52, 2), polled = status(0, 0, 52, 2);
        Map<String, Long> at = new HashMap<>();
        at.put("machine_status.progress", (LIVE_AT - 3_600_000) / 1000);   // seconds, as the cloud sometimes gives
        at.put("print_status.remaining_time_sec", LIVE_AT - 3_600_000);
        at.put("print_status.current_layer", LIVE_AT - 5_000);
        at.put("machine_status.status", LIVE_AT - 3_600_000);
        JSONObject kept = CloudMerge.newest(live, polled, at, LIVE_AT);
        assertEquals(23, kept.getJSONObject("machine_status").getInt("progress"));
        assertEquals(3120, kept.getJSONObject("print_status").getLong("remaining_time_sec"));
    }

    @Test public void aNewerStoredValueWinsAndFieldsLiveNeverSentAreAdded() throws Exception {
        // The print ended while live updates missed it: the read's state is newer, and its new fields are taken.
        JSONObject live = status(99, 30, 249, 2), polled = status(100, 0, 250, 1);
        polled.put("heater_bed", new JSONObject().put("temperature", 41));
        Map<String, Long> at = new HashMap<>();
        for (String k : new String[] {"machine_status.status", "machine_status.progress", "print_status.remaining_time_sec", "print_status.current_layer"}) at.put(k, LIVE_AT + 60_000);
        JSONObject kept = CloudMerge.newest(live, polled, at, LIVE_AT);
        assertEquals(1, kept.getJSONObject("machine_status").getInt("status"));
        assertEquals(100, kept.getJSONObject("machine_status").getInt("progress"));
        assertEquals(41, kept.getJSONObject("heater_bed").getInt("temperature"));
        assertEquals("the inputs are not changed", 99, live.getJSONObject("machine_status").getInt("progress"));
    }

    @Test public void aFieldWithNoReportTimeKeepsTheLiveValue() throws Exception {
        JSONObject kept = CloudMerge.newest(status(40, 900, 80, 2), status(0, 0, 80, 2), new HashMap<>(), LIVE_AT);
        assertEquals(40, kept.getJSONObject("machine_status").getInt("progress"));
    }
}
