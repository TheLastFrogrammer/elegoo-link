package io.github.thelastfrogrammer.elink;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.Test;

/** The status other apps see: one versioned shape, nothing that identifies the printer or the home. */
public class IntegrationStatusTest {
    private static JSONObject printing() throws Exception {
        return new JSONObject()
            .put("machine_status", new JSONObject().put("status", 2).put("sub_status", 2075).put("progress", 42))
            .put("print_status", new JSONObject().put("filename", "Desk_mount_PLA.gcode").put("current_layer", 30).put("total_layer", 120).put("remaining_time_sec", 3600))
            .put("extruder", new JSONObject().put("temperature", 214.96).put("target", 215))
            .put("heater_bed", new JSONObject().put("temperature", 60.04).put("target", 60))
            .put("ztemperature_sensor", new JSONObject().put("temperature", 31.2))
            .put("fans", new JSONObject().put("fan", new JSONObject().put("speed", 255)).put("aux_fan", new JSONObject().put("speed", 89)))
            .put("led", new JSONObject().put("status", 1))
            // What the printer also sends, and other apps must never see.
            .put("sn", "F01PRINTERSERIAL").put("ip", "192.168.1.77").put("access_code", "123456").put("mac", "aa:bb:cc:dd:ee:ff");
    }
    private static IntegrationStatus.Context context(boolean fresh) {
        IntegrationStatus.Context c = new IntegrationStatus.Context(); c.fresh = fresh; c.route = "local network"; c.statusAt = 10_000_000; c.printStartedAt = 10_000_000 - 1_800_000L; return c;
    }

    @Test public void aPrintReadsAsOneStableShape() throws Exception {
        JSONObject s = IntegrationStatus.build(printing(), context(true), 10_000_000);
        assertEquals(IntegrationStatus.SCHEMA, s.getString("schema"));
        assertEquals("printing", s.getJSONObject("state").getString("phase"));
        assertTrue(s.getJSONObject("state").getBoolean("printing"));
        assertEquals("Printing", s.getJSONObject("state").getString("words"));
        JSONObject p = s.getJSONObject("print");
        assertEquals(42, p.getInt("progress")); assertEquals("Desk_mount_PLA", p.getString("file")); assertEquals(30, p.getInt("layer")); assertEquals(120, p.getInt("total_layers"));
        assertEquals(3600, p.getLong("remaining_s")); assertEquals(1800, p.getLong("elapsed_s"));
        assertEquals(215.0, s.getJSONObject("temperatures").getJSONObject("nozzle").getDouble("now"), 0);
        assertEquals(215.0, s.getJSONObject("temperatures").getJSONObject("nozzle").getDouble("target"), 0);
        assertEquals(31.2, s.getJSONObject("temperatures").getJSONObject("chamber").getDouble("now"), 0);
        assertEquals(100, s.getJSONObject("fans").getInt("part")); assertEquals(35, s.getJSONObject("fans").getInt("aux"));
        assertTrue(s.getBoolean("light"));
        assertTrue(s.getJSONObject("connection").getBoolean("fresh"));
    }

    @Test public void nothingThatIdentifiesThePrinterOrTheHome() throws Exception {
        String text = IntegrationStatus.build(printing(), context(true), 10_000_000).toString();
        for (String secret : new String[] {"F01PRINTERSERIAL", "192.168.1.77", "123456", "aa:bb:cc", "\"sn\"", "\"ip\""}) assertFalse(secret, text.contains(secret));
        assertFalse("the file can be left out", IntegrationStatus.build(printing(), hidden(), 10_000_000).toString().contains("Desk_mount"));
    }
    private static IntegrationStatus.Context hidden() { IntegrationStatus.Context c = context(true); c.hideFile = true; return c; }

    @Test public void phasesAndNoStatus() throws Exception {
        assertEquals("paused", IntegrationStatus.phase(2, 2502)); assertEquals("complete", IntegrationStatus.phase(2, 2077)); assertEquals("idle", IntegrationStatus.phase(1, 0));
        assertEquals("preparing", IntegrationStatus.phase(2, 1045)); assertEquals("busy", IntegrationStatus.phase(5, 0));
        JSONObject offline = IntegrationStatus.build(printing(), context(false), 10_000_000);
        assertEquals("offline", offline.getJSONObject("state").getString("phase")); assertFalse(offline.has("print"));
        JSONObject done = IntegrationStatus.build(new JSONObject().put("machine_status", new JSONObject().put("status", 2).put("sub_status", 2077).put("progress", 100)), context(true), 0);
        assertFalse("a finished print is not printing", done.getJSONObject("state").getBoolean("printing"));
        JSONObject event = IntegrationStatus.event("print_finished", "A print finishes", "Desk_mount.gcode", 120, 120, 100, false, 0);
        assertEquals("linkworkshop.event/1", event.getString("schema")); assertEquals("Desk_mount", event.getString("file"));
    }
}
