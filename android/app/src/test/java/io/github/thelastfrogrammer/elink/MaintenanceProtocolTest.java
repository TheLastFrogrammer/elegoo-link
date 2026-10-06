package io.github.thelastfrogrammer.elink;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Request shapes match Elegoo's printer page (ElegooSlicer resources/web/elegoolink/lan_service_web). */
public class MaintenanceProtocolTest {
    @Test public void parameterlessCommandsHaveEmptyParams() throws Exception {
        for (int method : new int[] {Cc2Codec.FEED, Cc2Codec.RETREAT, Cc2Codec.AUTO_LEVEL, Cc2Codec.VIBRATION, Cc2Codec.URGENT_STOP}) {
            JSONObject request = Cc2Codec.maintenanceRequest(7, method);
            assertEquals(method, request.getInt("method")); assertEquals(0, request.getJSONObject("params").length());
            assertTrue(Cc2Codec.changing(method)); assertTrue(Cc2Codec.maintenance(method));
        }
        try { Cc2Codec.maintenanceRequest(1, Cc2Codec.START); fail(); } catch (IllegalArgumentException expected) { }
    }

    @Test public void selfCheckHomeMoveAndTraysUseThePageFormats() throws Exception {
        JSONObject check = Cc2Codec.selfCheckRequest(1).getJSONObject("params");
        assertTrue(check.getBoolean("ringing_optimize") && check.getBoolean("pid_check") && check.getBoolean("auto_bed_leveling"));
        assertEquals("xyz", Cc2Codec.homeRequest(1, "xyz").getJSONObject("params").getString("homed_axes"));
        JSONObject move = Cc2Codec.moveRequest(1, "z", -0.1).getJSONObject("params");
        assertEquals("z", move.getString("axes")); assertEquals(-0.1, move.getDouble("distance"), 1e-9);
        JSONObject tray = Cc2Codec.canvasFilamentRequest(1, true, 0, 3);
        assertEquals(Cc2Codec.CANVAS_LOAD, tray.getInt("method")); assertEquals(3, tray.getJSONObject("params").getInt("tray_id"));
        assertEquals(Cc2Codec.CANVAS_UNLOAD, Cc2Codec.canvasFilamentRequest(1, false, 0, 3).getInt("method"));
    }

    @Test public void unsafeValuesAreRefused() {
        for (Runnable bad : new Runnable[] {
            () -> call(() -> Cc2Codec.homeRequest(1, "x; rm")), () -> call(() -> Cc2Codec.moveRequest(1, "e", 1)),
            () -> call(() -> Cc2Codec.moveRequest(1, "x", 0)), () -> call(() -> Cc2Codec.moveRequest(1, "x", 51)),
            () -> call(() -> Cc2Codec.moveRequest(1, "x", Double.NaN)), () -> call(() -> Cc2Codec.canvasFilamentRequest(1, true, -1, 0)),
            () -> call(() -> Cc2Codec.thumbnailRequest(1, "local", "../etc/passwd")) })
            try { bad.run(); fail(); } catch (IllegalArgumentException expected) { }
    }
    private interface Call { Object run() throws Exception; }
    private static void call(Call call) { try { call.run(); } catch (IllegalArgumentException e) { throw e; } catch (Exception e) { throw new AssertionError(e); } }

    @Test public void thumbnailsAreQueriesAndUsbPathsGetALeadingSlash() throws Exception {
        assertTrue(Cc2Codec.isQuery(Cc2Codec.THUMBNAIL)); assertFalse(Cc2Codec.changing(Cc2Codec.THUMBNAIL));
        assertEquals("a.gcode", Cc2Codec.thumbnailRequest(1, "local", "a.gcode").getJSONObject("params").getString("file_name"));
        assertEquals("/a.gcode", Cc2Codec.thumbnailRequest(1, "u-disk", "a.gcode").getJSONObject("params").getString("file_name"));
        assertTrue(Cc2Codec.queryShape(Cc2Codec.THUMBNAIL, new JSONObject().put("thumbnail", "iVBOR")));
    }

    @Test public void homedAxesAndTimeouts() throws Exception {
        JSONObject status = new JSONObject().put("tool_head", new JSONObject().put("homed_axes", "XY"));
        assertTrue(Cc2Codec.homed(status, "x")); assertFalse(Cc2Codec.homed(status, "z")); assertFalse(Cc2Codec.homed(new JSONObject(), "x"));
        assertEquals(330, Cc2Codec.timeoutSeconds(Cc2Codec.FEED)); assertEquals(330, Cc2Codec.timeoutSeconds(Cc2Codec.CANVAS_UNLOAD));
        assertEquals(60, Cc2Codec.timeoutSeconds(Cc2Codec.HOME)); assertEquals(8, Cc2Codec.timeoutSeconds(Cc2Codec.PAUSE)); assertEquals(15, Cc2Codec.timeoutSeconds(Cc2Codec.FILES));
        assertTrue(CloudControl.allowed(Cc2Codec.FEED)); assertTrue(CloudControl.allowed(Cc2Codec.THUMBNAIL)); assertFalse(CloudControl.allowed(Cc2Codec.CAMERA));
    }
}
