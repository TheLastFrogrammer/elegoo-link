package io.github.thelastfrogrammer.elink;

import static org.junit.Assert.*;

import java.io.File;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

public class SpoolPassportTest {
    @Test public void valuesAreCheckedAgainstTheirRange() {
        assertEquals("0.97", SpoolPassport.accept("filament_flow_ratio", " 0,97 "));
        assertEquals("0.032", SpoolPassport.accept("pressure_advance", "0.0320"));
        assertEquals("215", SpoolPassport.accept("nozzle_temperature", "215.0"));
        assertNull(SpoolPassport.accept("nozzle_temperature", "500"));
        assertNull(SpoolPassport.accept("filament_flow_ratio", "fast"));
        assertNull(SpoolPassport.accept("layer_height", "0.2"));
    }

    @Test public void fromSliceChangesKeepsOnlyPassportKeys() {
        Map<String, String> edits = new LinkedHashMap<>();
        edits.put("filament_flow_ratio", "0.98,0.98"); edits.put("fan_max_speed", "80"); edits.put("nozzle_temperature", "999");
        Map<String, String> p = SpoolPassport.from(edits);
        assertEquals(1, p.size()); assertEquals("0.98", p.get("filament_flow_ratio"));
    }

    @Test public void usingAndKeeping() {
        Map<String, String> passport = new LinkedHashMap<>(); passport.put("filament_flow_ratio", "0.97"); passport.put("pressure_advance", "0.03");
        Map<String, String> edits = new LinkedHashMap<>();
        assertFalse(SpoolPassport.applied(passport, edits));
        assertFalse(SpoolPassport.differs(passport, edits));
        edits.putAll(SpoolPassport.changes(passport));
        assertEquals("1", edits.get("enable_pressure_advance"));
        assertTrue(SpoolPassport.applied(passport, edits));
        assertFalse(SpoolPassport.differs(passport, edits));
        edits.put("filament_flow_ratio", "0.95");
        assertFalse(SpoolPassport.applied(passport, edits));
        assertTrue(SpoolPassport.differs(passport, edits));
        assertFalse(SpoolPassport.applied(new LinkedHashMap<>(), edits));
    }

    @Test public void words() {
        Map<String, String> p = new LinkedHashMap<>();
        assertEquals("", SpoolPassport.words(p));
        p.put("nozzle_temperature_initial_layer", "220"); p.put("nozzle_temperature", "215"); p.put("filament_flow_ratio", "0.97");
        assertEquals("flow 0.97, 215 °C (first layer 220 °C)", SpoolPassport.words(p));
    }

    @Test public void theSpoolKeepsItsPassport() throws Exception {
        File file = new File(Files.createTempDirectory("passport").toFile(), "spools.json");
        SpoolStore store = new SpoolStore(file);
        TrayPlan.Tray tray = new TrayPlan.Tray(0, 1, "PLA", "", "", "#FF0000");
        SpoolStore.Spool spool = store.put(tray, 1000, 1000, 1);
        Map<String, String> values = new LinkedHashMap<>(); values.put("pressure_advance", "0.04"); values.put("speed", "1");
        store.setPassport(spool.id, values, 5);
        SpoolStore.Spool again = new SpoolStore(file).inSlot(SpoolStore.slot(tray));
        assertEquals("0.04", again.passport.get("pressure_advance")); assertEquals(1, again.passport.size()); assertEquals(5, again.passportAt);
        store.setPassport(spool.id, new LinkedHashMap<>(), 6);
        assertTrue(new SpoolStore(file).inSlot(SpoolStore.slot(tray)).passport.isEmpty());
    }
}
