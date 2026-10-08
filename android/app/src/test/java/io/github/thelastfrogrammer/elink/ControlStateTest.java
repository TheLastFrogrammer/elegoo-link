package io.github.thelastfrogrammer.elink;

import static org.junit.Assert.*;
import org.json.JSONObject;
import org.junit.Test;
import io.github.thelastfrogrammer.elink.ControlState.Block;

public class ControlStateTest {
    private static Block local(boolean fresh, boolean pin) { return ControlState.block(true, fresh, pin, true, false, false, false, false, false); }
    private static Block cloud(boolean fresh, boolean offline, boolean ok, boolean busy) { return ControlState.block(false, false, false, false, true, fresh, offline, ok, busy); }
    private static JSONObject status(int state, int sub) throws Exception { return new JSONObject().put("machine_status", new JSONObject().put("status", state).put("sub_status", sub)); }

    @Test public void localControlsNeedFreshStatusAndNotThePinProbe() {
        assertEquals(Block.NONE, local(true, false));
        assertEquals(Block.STALE, local(false, false));
        assertEquals(Block.PIN_PROBE, local(true, true));
        assertEquals(Block.PIN_PROBE, local(false, true));
    }
    @Test public void cloudControlsNeedTheAgreementFreshStatusAndNoCommandInFlight() {
        assertEquals(Block.NONE, cloud(true, false, true, false));
        assertEquals(Block.CLOUD_AGREEMENT, cloud(true, false, false, false));
        assertEquals(Block.CLOUD_AGREEMENT, cloud(true, false, false, true));
        assertEquals(Block.CLOUD_BUSY, cloud(true, false, true, true));
        assertEquals(Block.CLOUD_OFFLINE, cloud(false, true, true, false));
        assertEquals(Block.CLOUD_WAITING, cloud(false, false, true, false));
    }
    @Test public void withoutAnyConnectionNothingIsAvailable() {
        assertEquals(Block.DISCONNECTED, ControlState.block(false, false, false, false, false, false, false, true, false));
        assertEquals(Block.CONNECTING, ControlState.block(false, false, false, true, false, false, false, true, false));
        // A local session wins over cloud data, as in PrinterService.
        assertEquals(Block.NONE, ControlState.block(true, true, false, false, true, true, false, false, true));
    }
    @Test public void onlyNoneMeansCommandsAreAccepted() {
        for (int bits = 0; bits < 512; bits++) {
            boolean ready = (bits & 1) != 0, fresh = (bits & 2) != 0, pin = (bits & 4) != 0, connecting = (bits & 8) != 0, cloud = (bits & 16) != 0, cloudFresh = (bits & 32) != 0,
                offline = (bits & 64) != 0, ok = (bits & 128) != 0, busy = (bits & 256) != 0;
            boolean accepted = ready ? fresh && !pin : !connecting && cloud && cloudFresh && ok && !busy;
            assertEquals("bits " + bits, accepted, ControlState.block(ready, fresh, pin, connecting, cloud, cloudFresh, offline, ok, busy) == Block.NONE);
        }
    }
    @Test public void maintenanceExplainsFaultsAndPrints() throws Exception {
        assertEquals("", ControlState.upkeepReason(status(1, 0)));
        assertTrue(ControlState.upkeepReason(status(2, 2075)).contains("idle"));
        JSONObject faulty = status(1, 0).put("exception", new JSONObject().put("exception_code", new JSONObject().put("1001", new JSONObject())));
        assertTrue(ControlState.upkeepReason(faulty).contains("fault"));
        assertTrue(ControlState.upkeepReason(new JSONObject()).contains("idle"));
    }
    @Test public void tuningNoteFollowsTheState() throws Exception {
        assertTrue(ControlState.tuningNote(status(1, 0)).startsWith("Speed"));
        assertTrue(ControlState.tuningNote(status(2, 2075)).startsWith("Heater temperatures can be set only"));
        assertTrue(ControlState.tuningNote(status(2, 2502)).contains("running print"));
    }
}
