package io.github.thelastfrogrammer.elink;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;

public class FeatureIndexTest {
    private static String first(String query) { return FeatureIndex.search(query, Collections.emptyList()).get(0).id; }

    @Test public void everydayWordsFindTheRightPlace() {
        assertEquals("level", first("level"));
        assertEquals("level", first("bed leveling"));
        assertEquals("timelapse", first("timelapse"));
        assertEquals("calibration", first("pressure advance"));
        assertEquals("find-models", first("thingiverse"));
        assertEquals("camera", first("webcam"));
        assertEquals("appearance", first("dark"));
        assertEquals("probe", first("probe"));
        assertEquals("history", first("reprint"));
        assertEquals("vpn", first("tailscale"));
        assertTrue(FeatureIndex.search("zzzz nothing", Collections.emptyList()).isEmpty());
    }

    @Test public void recentFeaturesComeFirstWhenNothingIsTyped() {
        List<String> recent = FeatureIndex.used(FeatureIndex.used(Collections.emptyList(), "probe"), "slice");
        assertEquals(Arrays.asList("slice", "probe"), recent);
        List<FeatureIndex.Feature> list = FeatureIndex.search("", recent);
        assertEquals("slice", list.get(0).id); assertEquals("probe", list.get(1).id);
        assertEquals(FeatureIndex.ALL.size(), list.size());
        List<String> many = new ArrayList<>(); for (String id : new String[] {"a", "b", "c", "d", "e", "f"}) many = FeatureIndex.used(many, id);
        assertEquals(5, many.size()); assertEquals("f", many.get(0));
    }

    @Test public void idsAreUniqueAndPrinterChangingActionsAreNeverPressed() {
        Set<String> ids = new HashSet<>();
        for (FeatureIndex.Feature f : FeatureIndex.ALL) assertTrue(f.id, ids.add(f.id));
        for (String changing : new String[] {"load", "tray", "move", "level", "vibration", "printer-files", "history", "connect", "cloud"})
            assertFalse(changing + " must only be shown, not pressed", FeatureIndex.byId(changing).press);
    }
}
