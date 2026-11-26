package io.github.thelastfrogrammer.elink;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class CloudBedMeshTest {
    private static JSONObject phase(int n) throws Exception {
        return new JSONObject().put("machine_status", new JSONObject().put("status", 5).put("sub_status", n));
    }
    private static JSONObject move(String key, JSONObject position) throws Exception { return new JSONObject().put(key, position); }
    private static long grid(CloudBedMesh mesh, String serial, int rows, boolean zOnLastRow) throws Exception {
        long now = 1000;
        mesh.delta(serial, phase(BedMesh.PROBING), now++);
        for (int y = 0; y < rows; y++) for (int x = 0; x < 11; x++) {
            mesh.delta(serial, move("gcode_move_inf", new JSONObject().put("x", 10 + x * 23.6).put("y", 10 + y * 23.6)), now++);
            if (zOnLastRow || y < rows - 1) {
                mesh.delta(serial, move("gcode_move_inf", new JSONObject().put("z", (x - y) * .014)), now++);
                mesh.delta(serial, move("gcode_move_inf", new JSONObject().put("z", 3)), now++);
            }
        }
        return now;
    }

    @Test public void usesSplitLiveDeltasAndFinishesAtLevelled() throws Exception {
        CloudBedMesh capture = new CloudBedMesh();
        long now = grid(capture, "SN1", 11, true);
        BedMesh.Mesh mesh = capture.delta("SN1", phase(BedMesh.LEVELLED), now);
        assertNotNull(capture.message(), mesh);
        assertEquals(121, mesh.count());
        assertEquals(.14, mesh.z[0][10], .0001);
        assertEquals(-.14, mesh.z[10][0], .0001);
        assertEquals(PrintRecorder.digest("SN1"), mesh.printer);
        assertEquals(CloudBedMesh.SOURCE, mesh.source);
    }

    /** Live updates carry only what changed; `known` is the merged status they build, as the service keeps it. */
    private static void merge(JSONObject known, JSONObject partial) throws Exception {
        for (java.util.Iterator<String> k = partial.keys(); k.hasNext(); ) {
            String key = k.next(); Object v = partial.get(key);
            if (v instanceof JSONObject && known.optJSONObject(key) != null) merge(known.getJSONObject(key), (JSONObject) v);
            else known.put(key, v instanceof JSONObject ? new JSONObject(v.toString()) : v);
        }
    }
    private static BedMesh.Mesh send(CloudBedMesh mesh, JSONObject known, JSONObject partial, long now) throws Exception {
        merge(known, partial); return mesh.delta("SN1", partial, known, now);
    }

    @Test public void changeOnlyUpdatesKeepARowWhoseYWasNeverResent() throws Exception {
        // The head waits at the first row's Y before probing, so along that row only X is ever reported.
        CloudBedMesh capture = new CloudBedMesh(); JSONObject known = new JSONObject(); long now = 1000;
        send(capture, known, move("gcode_move_inf", new JSONObject().put("x", 150).put("y", 10).put("z", 3)), now++);
        send(capture, known, phase(BedMesh.PROBING), now++);
        double px = 150, py = 10;
        for (int y = 0; y < 11; y++) for (int x = 0; x < 11; x++) {
            double nx = 10 + x * 23.6, ny = 10 + y * 23.6; JSONObject p = new JSONObject();
            if (nx != px) p.put("x", nx);
            if (ny != py) p.put("y", ny);
            px = nx; py = ny;
            if (p.length() > 0) send(capture, known, move("gcode_move_inf", p), now++);
            send(capture, known, move("gcode_move_inf", new JSONObject().put("z", (x - y) * .014)), now++);
            send(capture, known, move("gcode_move_inf", new JSONObject().put("z", 3)), now++);
        }
        BedMesh.Mesh mesh = send(capture, known, phase(BedMesh.LEVELLED), now);
        assertNotNull(capture.message(), mesh);
        assertEquals(121, mesh.count());
        assertEquals(0, mesh.z[0][0], .0001); assertEquals(.14, mesh.z[0][10], .0001);
    }

    /** A levelling as the printer reports it, change-only, from a bed already at `bedC`; returns the time after it. */
    private static long changeOnlyRun(CloudBedMesh capture, JSONObject known, double bedC, int rowsWithTouches) throws Exception {
        long now = 1000;
        send(capture, known, new JSONObject().put("heater_bed", new JSONObject().put("temperature", bedC).put("target", bedC)), now++);
        send(capture, known, move("gcode_move_inf", new JSONObject().put("x", 150).put("y", 10).put("z", 3)), now++);
        send(capture, known, phase(BedMesh.PROBING), now++);
        double px = 150, py = 10;
        for (int y = 0; y < 11; y++) for (int x = 0; x < 11; x++) {
            double nx = 10 + x * 23.6, ny = 10 + y * 23.6; JSONObject p = new JSONObject();
            if (nx != px) p.put("x", nx);
            if (ny != py) p.put("y", ny);
            px = nx; py = ny;
            if (p.length() > 0) send(capture, known, move("gcode_move_inf", p), now++);
            if (y < rowsWithTouches) {
                send(capture, known, move("gcode_move_inf", new JSONObject().put("z", (x - y) * .014)), now++);
                send(capture, known, move("gcode_move_inf", new JSONObject().put("z", 3)), now++);
            }
        }
        return now;
    }

    @Test public void aCloudMeshKeepsTheBedTemperatureFromTheLiveStatus() throws Exception {
        // The bed's temperature was reported before probing and not again (it did not change): the mesh still has it.
        CloudBedMesh capture = new CloudBedMesh(); JSONObject known = new JSONObject();
        long now = changeOnlyRun(capture, known, 60, 11);
        BedMesh.Mesh mesh = send(capture, known, phase(BedMesh.LEVELLED), now);
        assertNotNull(capture.message(), mesh);
        assertEquals(60, mesh.bed, 0); assertEquals(60, mesh.bedTarget, 0);
    }

    @Test public void eachRunIsSummedUpInCountsWithoutPositions() throws Exception {
        CloudBedMesh capture = new CloudBedMesh(); JSONObject known = new JSONObject();
        long now = changeOnlyRun(capture, known, 60, 10);   // the last row's touches never came through
        assertNull(send(capture, known, phase(BedMesh.LEVELLED), now));
        // 11 × 11 places visited, 10 rows touched: the log says touches never came through, not that updates were missing.
        assertTrue(capture.stats, capture.stats.startsWith("342 live updates while probing, 341 with a position, 110 probe touches at 110 places"));
        assertFalse("no coordinates in the log", capture.stats.contains("23.6") || capture.stats.contains("10,"));
    }

    private static JSONObject step(int state, int sub) throws Exception {
        return new JSONObject().put("machine_status", new JSONObject().put("status", state).put("sub_status", sub));
    }

    @Test public void anotherLevellingStepBetweenRowsPausesWithoutEndingOrAddingPoints() throws Exception {
        // Between rows 6 and 7 the printer re-homes (still levelling, another step): it touches down at the bed's middle,
        // which is not a probe point, and ends at row 7's Y, so that Y is not sent again when probing resumes.
        CloudBedMesh capture = new CloudBedMesh(); JSONObject known = new JSONObject(); long now = 1000;
        send(capture, known, move("gcode_move_inf", new JSONObject().put("x", 150).put("y", 10).put("z", 3)), now++);
        send(capture, known, phase(BedMesh.PROBING), now++);
        double px = 150, py = 10;
        for (int y = 0; y < 11; y++) {
            if (y == 7) {
                send(capture, known, step(5, 2900), now++);
                double row = 10 + 7 * 23.6;
                send(capture, known, move("gcode_move_inf", new JSONObject().put("x", 128).put("y", row)), now++);
                send(capture, known, move("gcode_move_inf", new JSONObject().put("z", 0.0)), now++);
                send(capture, known, move("gcode_move_inf", new JSONObject().put("z", 3)), now++);
                assertTrue("still levelling: not ended", capture.active());
                send(capture, known, phase(BedMesh.PROBING), now++);
                px = 128; py = row;
            }
            for (int x = 0; x < 11; x++) {
                double nx = 10 + x * 23.6, ny = 10 + y * 23.6; JSONObject p = new JSONObject();
                if (nx != px) p.put("x", nx);
                if (ny != py) p.put("y", ny);
                px = nx; py = ny;
                if (p.length() > 0) send(capture, known, move("gcode_move_inf", p), now++);
                send(capture, known, move("gcode_move_inf", new JSONObject().put("z", (x - y) * .014)), now++);
                send(capture, known, move("gcode_move_inf", new JSONObject().put("z", 3)), now++);
            }
        }
        BedMesh.Mesh mesh = send(capture, known, phase(BedMesh.LEVELLED), now);
        assertNotNull(capture.message(), mesh);
        assertEquals(121, mesh.count());
        assertEquals(11, mesh.xs.length);   // the re-homing touch at X 128 is not a column
        assertEquals(-.07, mesh.z[7][2], .0001);
    }

    @Test public void aPausedCaptureStillEndsWhenThePrinterStopsLevelling() throws Exception {
        CloudBedMesh capture = new CloudBedMesh(); JSONObject known = new JSONObject();
        long now = changeOnlyRun(capture, known, 60, 5);
        send(capture, known, step(5, 2900), now++);
        assertTrue(capture.active());
        assertNull(send(capture, known, new JSONObject().put("machine_status", new JSONObject().put("status", 1)), now));
        assertFalse(capture.active());
        assertTrue(capture.message(), capture.message().contains("not a complete"));
    }

    @Test public void theMergedStatusNeverSuppliesAProbeHeight() throws Exception {
        // A Z already in the merged status (here a touch from before probing began) is not a probe reading.
        CloudBedMesh capture = new CloudBedMesh(); JSONObject known = new JSONObject(); long now = 1000;
        send(capture, known, move("gcode_move_inf", new JSONObject().put("x", 10).put("y", 10).put("z", 0.02)), now++);
        send(capture, known, phase(BedMesh.PROBING), now++);
        assertNull(send(capture, known, phase(BedMesh.LEVELLED), now));
        assertTrue(capture.message(), capture.message().contains("not a complete"));
    }

    @Test public void noZFromEarlierXYAndNoSmallPerfectGrid() throws Exception {
        CloudBedMesh capture = new CloudBedMesh();
        long now = grid(capture, "SN1", 11, false);
        assertNull(capture.delta("SN1", phase(BedMesh.LEVELLED), now));
        assertTrue(capture.message(), capture.message().contains("not a complete"));
    }

    @Test public void onlyNewSnapshotsCanCloseAndNoneCanContributePoints() throws Exception {
        CloudBedMesh capture = new CloudBedMesh();
        assertNull(capture.snapshot("SN1", new CloudApi.Snapshot(phase(BedMesh.PROBING), 1), 2000));
        assertFalse(capture.active());
        grid(capture, "SN1", 11, true);
        JSONObject ended = phase(BedMesh.LEVELLED);
        long now = 4000;
        assertNull(capture.snapshot("SN1", new CloudApi.Snapshot(ended, 4, 1, 1), now));
        assertNull(capture.snapshot("SN2", new CloudApi.Snapshot(ended, 4, 0, 3), now));
        assertTrue(capture.active());
        assertNotNull(capture.snapshot("SN1", new CloudApi.Snapshot(ended, 4, 0, 3), now));
    }

    @Test public void printerChangeAndGapNeverCompleteUnknownRuns() throws Exception {
        CloudBedMesh capture = new CloudBedMesh();
        long now = grid(capture, "SN1", 11, true);
        assertNull(capture.delta("SN2", phase(BedMesh.LEVELLED), now));
        now = grid(capture, "SN1", 11, true);
        assertNull(capture.delta("SN1", phase(BedMesh.LEVELLED), now + CloudBedMesh.SILENCE_MS));
        assertTrue(capture.message(), capture.message().contains("paused"));
    }
    @Test public void liveProgressCountsProbeLocationsRatherThanMovesOrRepeatTouches() throws Exception {
        CloudBedMesh capture = new CloudBedMesh();
        capture.delta("SN1", phase(BedMesh.PROBING), 1);
        assertEquals(0, capture.probeLocations());
        capture.delta("SN1", move("gcode_move", new JSONObject().put("x", 10).put("y", 10).put("z", 3)), 2);
        assertEquals(0, capture.probeLocations());
        capture.delta("SN1", move("gcode_move", new JSONObject().put("z", .1)), 3);
        capture.delta("SN1", move("gcode_move", new JSONObject().put("z", .2)), 4);
        assertEquals(1, capture.probeLocations());
        capture.delta("SN1", move("gcode_move", new JSONObject().put("x", 34)), 5);
        assertEquals(1, capture.probeLocations());
        capture.delta("SN1", move("gcode_move", new JSONObject().put("z", .16)), 6);
        assertEquals(2, capture.probeLocations());
        capture.delta("SN1", phase(BedMesh.LEVELLED), 7);
        assertEquals(0, capture.probeLocations());
    }
}
