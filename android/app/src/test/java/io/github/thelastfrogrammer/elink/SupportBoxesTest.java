package io.github.thelastfrogrammer.elink;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SupportBoxesTest {
    @Test public void plateBoxesBecomeEngineBoxesAndBadOnesAreLeftOut() throws Exception {
        JSONArray boxes = new JSONArray("[{\"kind\":\"enforcer\",\"center\":[100,110,5],\"size\":[20,10,10]},"
            + "{\"kind\":\"blocker\",\"center\":[1,2,3],\"size\":[4,5,6]},{\"kind\":\"blocker\",\"center\":[1,2],\"size\":[4,5,6]},"
            + "{\"kind\":\"blocker\",\"center\":[1,2,3],\"size\":[0,5,6]},{\"kind\":\"blocker\",\"center\":[1,\"x\",3],\"size\":[4,5,6]}]");
        List<double[]> out = SliceActivity.supportBoxes(boxes);
        assertEquals(2, out.size());
        assertArrayEquals(new double[] {1, 100, 110, 5, 20, 10, 10}, out.get(0), 1e-9);
        assertArrayEquals(new double[] {0, 1, 2, 3, 4, 5, 6}, out.get(1), 1e-9);
        assertTrue(SliceActivity.supportBoxes(null).isEmpty());
    }

    @Test public void selectionSendsBoxesOnlyWhenThereAreSome() throws Exception {
        NativeSlicer.Selection selection = new NativeSlicer.Selection("P", "Q", Collections.singletonList("F"));
        assertTrue(!new JSONObject(selection.toJson()).has("support_boxes"));
        selection.supportBoxes.add(new double[] {1, 100, 110, 5, 20, 10, 10});
        JSONObject box = new JSONObject(selection.toJson()).getJSONArray("support_boxes").getJSONObject(0);
        assertEquals("enforcer", box.getString("kind"));
        assertEquals(110, box.getJSONArray("center").getDouble(1), 1e-9); assertEquals(20, box.getJSONArray("size").getDouble(0), 1e-9);
    }

    @Test public void typedSizesBecomeAPageCallWithTheBottomOnTheBedOrAbove() {
        assertEquals("plate.setBox(2,[100,90.5,7],[20,10,10])", PlateActivity.boxScript(2, 100, 90.5, new double[] {20, 10, 10, 2}));
        assertNull(PlateActivity.boxScript(0, 0, 0, new double[] {0.2, 10, 10, 0}));
        assertNull(PlateActivity.boxScript(0, 0, 0, new double[] {10, 10, 10, -1}));
        assertNull(PlateActivity.boxScript(0, 0, 0, new double[] {10, Double.NaN, 10, 0}));
    }

    @Test public void theOnlyWhereEnforcedChoicesFollowTheOthers() {
        assertEquals(6, SliceActivity.SUPPORT_CHOICES.length);
        assertTrue(SliceActivity.SUPPORT_CHOICES[4].contains("only where enforced")); assertTrue(SliceActivity.SUPPORT_CHOICES[5].startsWith("Tree"));
    }
}
