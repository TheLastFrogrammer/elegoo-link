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
public class SliceEstimateTest {
    @Test public void matchesTheMeasuredPeaks() {
        // link-slicer peaks (MB) for spheres: triangles, surface mm², measured.
        double[][] runs = {{39608, 11310, 178}, {201608, 11310, 215}, {998008, 11310, 434}, {1957240, 11310, 799}, {201608, 125664, 650}};
        for (double[] run : runs) {
            double estimate = new SliceEstimate((long) run[0], run[1], 0.2).megabytes;
            assertEquals("triangles " + run[0], run[2], estimate, run[2] * 0.12);
        }
        assertTrue(new SliceEstimate(100000, 50000, 0.1).megabytes > new SliceEstimate(100000, 50000, 0.2).megabytes);
    }

    @Test public void readsInspectAndPlacements() throws Exception {
        JSONObject inspected = new JSONObject("{\"files\":[{\"objects\":[{\"triangles\":1000,\"area\":10000},{\"triangles\":3000,\"area\":20000}]}]}");
        SliceEstimate all = SliceEstimate.of(inspected, null, 0.2);
        assertEquals(4000, all.triangles); assertEquals(30000, all.areaMm2, 1e-9);
        Map<String, Double> scales = new HashMap<>(); scales.put("0:1", 2.0);
        SliceEstimate placed = SliceEstimate.of(inspected, scales, 0.2);
        assertEquals(3000, placed.triangles); assertEquals(80000, placed.areaMm2, 1e-9);
        assertFalse(placed.risky(0));
        assertTrue(placed.risky(100L * 1024 * 1024));
        assertFalse(placed.risky(8L * 1024 * 1024 * 1024));
        assertTrue(placed.describe().startsWith("about "));
    }

    @Test public void validatesSettingValues() throws Exception {
        JSONObject percent = new JSONObject("{\"type\":\"percent\",\"min\":0,\"max\":100}");
        assertNull(SliceSettingsActivity.validate(percent, "35%")); assertNull(SliceSettingsActivity.validate(percent, "35")); assertNull(SliceSettingsActivity.validate(percent, ""));
        assertNotNull(SliceSettingsActivity.validate(percent, "135%")); assertNotNull(SliceSettingsActivity.validate(percent, "lots"));
        JSONObject integer = new JSONObject("{\"type\":\"int\",\"min\":0}");
        assertNull(SliceSettingsActivity.validate(integer, "3")); assertNotNull(SliceSettingsActivity.validate(integer, "2.5")); assertNotNull(SliceSettingsActivity.validate(integer, "-1"));
        assertNotNull(SliceSettingsActivity.validate(integer, "3%"));
        JSONObject either = new JSONObject("{\"type\":\"float_or_percent\",\"min\":0}");
        assertNull(SliceSettingsActivity.validate(either, "0.42")); assertNull(SliceSettingsActivity.validate(either, "110%"));
        assertNull(SliceSettingsActivity.validate(new JSONObject("{\"type\":\"string\"}"), "anything"));
    }
}
