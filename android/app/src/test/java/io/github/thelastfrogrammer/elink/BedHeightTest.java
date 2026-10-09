package io.github.thelastfrogrammer.elink;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class BedHeightTest {
    private static JSONObject status(String homed, double z) throws Exception {
        return new JSONObject().put("tool_head", new JSONObject().put("homed_axes", homed)).put("gcode_move", new JSONObject().put("x", 1).put("y", 2).put("z", z));
    }

    @Test public void unhomedZIsNeverTrusted() throws Exception {
        BedHeight bed = new BedHeight();
        bed.report(status("", 80.14), 1_000);
        assertEquals(BedHeight.State.UNKNOWN, bed.state(1_000));
        assertTrue(Double.isNaN(bed.z()));
        assertTrue(bed.describe(1_000).contains("Home Z"));
        bed.report(status("xyz", 4.8), 2_000);
        assertEquals(BedHeight.State.KNOWN, bed.state(2_000));
        assertEquals(4.8, bed.z(), 0);
        bed.report(status("", 4.8), 3_000);   // steppers released: homing lost
        assertEquals(BedHeight.State.UNKNOWN, bed.state(3_000));
    }

    @Test public void aMoveCountsOnlyOnceReportsAgreeAtTheNewHeight() throws Exception {
        BedHeight bed = new BedHeight();
        bed.report(status("xyz", 5), 0);
        bed.moveSent(50, 1_000);
        assertEquals(BedHeight.State.MOVING, bed.state(1_000));
        bed.report(status("xyz", 5), 1_500);    // before the printer acted: old height
        bed.report(status("xyz", 5), 2_000);    // still old, and within the lag: not "stopped"
        assertEquals(BedHeight.State.MOVING, bed.state(2_000));
        bed.report(status("xyz", 31), 2_500);   // on its way
        bed.report(status("xyz", 55), 4_500);   // arrived
        assertEquals(BedHeight.State.MOVING, bed.state(4_500));
        bed.report(status("xyz", 55), 5_500);   // and stayed
        assertEquals(BedHeight.State.KNOWN, bed.state(5_500));
        assertEquals(55, bed.z(), 0);
    }

    @Test public void theSameStatusTwiceIsOneReport() throws Exception {
        BedHeight bed = new BedHeight();
        bed.report(status("xyz", 5), 0);
        bed.moveSent(50, 1_000);
        JSONObject arrived = status("xyz", 55);
        bed.report(arrived, 2_000); bed.report(arrived, 3_000);
        assertEquals(BedHeight.State.MOVING, bed.state(3_000));
    }

    @Test public void homingSettlesOnTwoAgreeingReportsAfterTheLag() throws Exception {
        BedHeight bed = new BedHeight();
        bed.report(status("", 120), 0);
        bed.moveSent(0, 1_000);
        bed.report(status("xyz", 0.4), 2_000);   // within the lag: not counted
        bed.report(status("xyz", 0.0), 4_500);
        assertEquals(BedHeight.State.MOVING, bed.state(4_500));
        bed.report(status("xyz", 0.0), 5_500);
        assertEquals(BedHeight.State.KNOWN, bed.state(5_500));
    }

    @Test public void noAgreementForLongIsUnconfirmed() throws Exception {
        BedHeight bed = new BedHeight();
        bed.report(status("xyz", 5), 0);
        bed.moveSent(10, 1_000);
        assertEquals(BedHeight.State.UNCONFIRMED, bed.state(1_000 + BedHeight.GIVE_UP_MS + 1));
    }
}
