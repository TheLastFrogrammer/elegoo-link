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
}
