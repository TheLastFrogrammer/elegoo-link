package io.github.thelastfrogrammer.elink;

import java.util.*;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class TrayPlanTest {
    private static final List<String> PRESETS = Arrays.asList("Generic ABS @Elegoo Centauri", "Generic PA @Elegoo", "Generic PLA @Elegoo Centauri",
        "Elegoo PETG @ECC2", "Elegoo PETG HF @ECC2", "Elegoo PLA @ECC2", "Elegoo PLA Matte @ECC2", "Elegoo PLA+ @ECC2", "Elegoo Rapid PLA+ @ECC2", "Elegoo TPU 95A @ECC2");

    private static TrayPlan.Tray tray(String type, String name, String brand) { return new TrayPlan.Tray(0, 0, type, name, brand, null); }

    @Test public void readsLoadedTraysOfConnectedUnits() throws Exception {
        JSONObject canvas = new JSONObject("{\"auto_refill\":true,\"canvas_list\":["
            + "{\"canvas_id\":0,\"connected\":1,\"tray_list\":["
            + "{\"tray_id\":0,\"filament_type\":\"PLA\",\"filament_name\":\"PLA Matte\",\"brand\":\"ELEGOO\",\"filament_color\":\"#44aa33\"},"
            + "{\"tray_id\":1,\"filament_type\":\"\",\"filament_color\":\"\"},"
            + "{\"tray_id\":2,\"filament_type\":\"PETG\",\"filament_color\":\"FFFFFFFF\"},"
            + "{\"tray_id\":3,\"filament_type\":\"PLA\",\"filament_color\":\"blue\"}]},"
            + "{\"canvas_id\":1,\"connected\":0,\"tray_list\":[{\"tray_id\":0,\"filament_type\":\"ABS\",\"filament_color\":\"#000000\"}]}]}");
        List<TrayPlan.Tray> trays = TrayPlan.trays(canvas);
        assertEquals(3, trays.size());
        assertEquals("#44AA33", trays.get(0).colour);
        assertEquals("CANVAS 0 · tray 0 · PLA · PLA Matte #44AA33", trays.get(0).label());
        assertEquals(2, trays.get(1).trayId); assertEquals("#FFFFFF", trays.get(1).colour);
        assertNull(trays.get(2).colour);
        assertTrue(TrayPlan.trays(null).isEmpty());
        assertTrue(TrayPlan.trays(new JSONObject()).isEmpty());
    }

    @Test public void normalisesColours() {
        assertEquals("#D02828", TrayPlan.colour("#d02828"));
        assertEquals("#D02828", TrayPlan.colour(" d02828 "));
        assertEquals("#D02828", TrayPlan.colour("#D02828FF"));
        assertEquals("#D02828", TrayPlan.colour("0xD02828"));
        assertNull(TrayPlan.colour("#D028")); assertNull(TrayPlan.colour("red")); assertNull(TrayPlan.colour(null)); assertNull(TrayPlan.colour(""));
    }

    @Test public void matchesTraysToPresets() {
        assertEquals("Elegoo PLA Matte @ECC2", TrayPlan.preset(tray("PLA", "PLA Matte", "ELEGOO"), PRESETS, null));
        assertEquals("Elegoo PLA Matte @ECC2", TrayPlan.preset(tray("PLA", "ELEGOO PLA Matte", "ELEGOO"), PRESETS, null));
        assertEquals("Elegoo Rapid PLA+ @ECC2", TrayPlan.preset(tray("PLA+", "Rapid PLA+", ""), PRESETS, null));
        assertEquals("Elegoo PLA @ECC2", TrayPlan.preset(tray("PLA", "", "ELEGOO"), PRESETS, null));
        assertEquals("Elegoo PLA @ECC2", TrayPlan.preset(tray("PLA", "Mystery Blend", "Other Brand"), PRESETS, null));
        assertEquals("Elegoo PETG @ECC2", TrayPlan.preset(tray("PETG", "", ""), PRESETS, null));
        assertEquals("Generic ABS @Elegoo Centauri", TrayPlan.preset(tray("ABS", "", "Someone"), PRESETS, null));
        assertEquals("Generic PA @Elegoo", TrayPlan.preset(tray("PA", "", ""), PRESETS, null));
        assertEquals("fallback", TrayPlan.preset(tray("PEEK", "", ""), PRESETS, "fallback"));
    }

    @Test public void plansRoundTrip() {
        TrayPlan plan = new TrayPlan(3, Arrays.asList(new TrayPlan.Tool(0, 0, 2), new TrayPlan.Tool(2, 1, 0)));
        TrayPlan read = TrayPlan.parse(plan.toJson());
        assertEquals(3, read.count); assertEquals(2, read.tools.size());
        assertEquals(2, read.tool(0).trayId); assertNull(read.tool(1)); assertEquals(1, read.tool(2).canvasId);
        assertNull(TrayPlan.parse(null)); assertNull(TrayPlan.parse("")); assertNull(TrayPlan.parse("{"));
        assertNull(TrayPlan.parse("{\"count\":0}")); assertNull(TrayPlan.parse("{\"count\":9}"));
        assertNull(TrayPlan.parse("{\"count\":1,\"tools\":[{\"t\":1,\"canvas_id\":0,\"tray_id\":0}]}"));
        assertEquals(0, TrayPlan.parse("{\"count\":2}").tools.size());
    }

    @Test public void defaultToolCountPrefersThePlanThenTheFilesToolsThenOne() {
        assertEquals(3, TrayPlan.defaultToolCount(new TrayPlan(3, java.util.Collections.<TrayPlan.Tool>emptyList()), java.util.Arrays.asList(0)));
        assertEquals(3, TrayPlan.defaultToolCount(null, java.util.Arrays.asList(0, 2)));
        assertEquals(1, TrayPlan.defaultToolCount(null, java.util.Collections.<Integer>emptyList()));
        assertEquals(1, TrayPlan.defaultToolCount(null, null));
        // Selections outside T0-T7 are ignored, as the inspector report warns they may be sentinels.
        assertEquals(2, TrayPlan.defaultToolCount(null, java.util.Arrays.asList(1, 99, -1)));
        assertEquals(1, TrayPlan.defaultToolCount(null, java.util.Arrays.asList(99)));
    }
}
