package io.github.thelastfrogrammer.elink;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.List;

public class TemperaturePresetsTest {
    @Test public void loadedTraysComeFirstWithTheirOwnNozzleRange() throws Exception {
        JSONObject canvas = new JSONObject("{\"canvas_list\":[{\"canvas_id\":0,\"tray_list\":["
            + "{\"tray_id\":0,\"filament_type\":\"PLA\",\"filament_name\":\"PLA Matte\",\"filament_color\":\"#D02828\",\"min_nozzle_temp\":190,\"max_nozzle_temp\":230},"
            + "{\"tray_id\":1,\"filament_type\":\"PLA\",\"filament_name\":\"PLA Matte\",\"filament_color\":\"#D02828\",\"min_nozzle_temp\":190,\"max_nozzle_temp\":230},"
            + "{\"tray_id\":2,\"filament_type\":\"PETG\",\"filament_color\":\"#1E5AA8\"},"
            + "{\"tray_id\":3,\"filament_type\":\"\"}]}]}");
        List<TemperaturePresets.Preset> presets = TemperaturePresets.from(canvas);
        assertEquals("PLA Matte · red", presets.get(0).label); assertEquals(210, presets.get(0).nozzle); assertEquals(60, presets.get(0).bed);
        assertEquals("a second identical spool adds no preset", "PETG", presets.get(1).material);
        assertEquals(240, presets.get(1).nozzle); assertEquals(70, presets.get(1).bed);
        assertEquals("ABS (typical)", presets.get(2).label); assertEquals("TPU (typical)", presets.get(3).label);
        TemperaturePresets.Preset last = presets.get(presets.size() - 1);
        assertEquals("Cool down", last.label); assertEquals(0, last.nozzle); assertEquals("Cool down: heaters off", last.text());
        for (TemperaturePresets.Preset p : presets) { assertTrue(p.nozzle <= TemperaturePresets.NOZZLE_MAX); assertTrue(p.bed <= TemperaturePresets.BED_MAX); }
    }

    @Test public void withoutTraysTheCommonMaterialsAndCoolDownRemain() {
        List<TemperaturePresets.Preset> presets = TemperaturePresets.from(null);
        assertEquals(5, presets.size()); assertEquals("PLA (typical): 215° / 60°", presets.get(0).text());
        assertNull("an unknown material gets no guess", TemperaturePresets.typical("Mystery"));
        assertArrayEquals(new int[] {250, 80}, TemperaturePresets.typical("PCTG"));
    }
}
