package io.github.thelastfrogrammer.elink;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class GcodeJobTrackerTest {
    private JSONObject status(String filename, int state, int progress, long seconds, String id) throws Exception {
        return new JSONObject().put("machine_status", new JSONObject().put("status", state).put("progress", progress))
            .put("print_status", new JSONObject().put("filename", filename).put("print_duration", seconds).put("uuid", id));
    }
    @Test public void progressingStatusKeepsTheObservedToken() throws Exception {
        GcodeJobTracker t = new GcodeJobTracker(); GcodeJobTracker.Job a = t.update("CC2-A", "local:1", status("a.gcode", 2, 10, 100, "u"), 1000);
        assertSame(a, t.update("cc2-a", "local:1", status("a.gcode", 2, 11, 110, "u"), 2000));
    }
    @Test public void newPrintOfTheSameNameAndFirmwareUuidDoesNotReuseASelection() throws Exception {
        GcodeJobTracker t = new GcodeJobTracker(); GcodeJobTracker.Job a = t.update("A", "route", status("a.gcode", 2, 80, 500, "same-file-uuid"), 1000);
        t.update("A", "route", status("a.gcode", 1, 100, 600, "same-file-uuid"), 2000);
        assertNotEquals(a.token, t.update("A", "route", status("a.gcode", 2, 0, 0, "same-file-uuid"), 3000).token);
    }
    @Test public void printerRouteFileAndReportedIdChangesAllCreateNewScopes() throws Exception {
        GcodeJobTracker t = new GcodeJobTracker(); String previous = "";
        String[][] changes = {{"A","local:1","/local/a.gcode","u"},{"B","local:1","/local/a.gcode","u"},{"B","cloud:2","/local/a.gcode","u"},
            {"B","cloud:2","/udisk/a.gcode","u"},{"B","cloud:2","/udisk/a.gcode","new"}};
        for (String[] c : changes) { GcodeJobTracker.Job now = t.update(c[0], c[1], status(c[2], 2, 10, 100, c[3]), 1000); assertNotEquals(previous, now.token); previous = now.token; }
    }
    @Test public void resumedAppAfterATelemetryGapNeedsANewChoice() throws Exception {
        GcodeJobTracker t = new GcodeJobTracker(); GcodeJobTracker.Job a = t.update("A", "r", status("a.gcode", 2, 10, 100, "u"), 1000);
        assertNull(t.current(1000 + GcodeJobTracker.GAP_MS));
        assertNotEquals(a.token, t.update("A", "r", status("a.gcode", 2, 20, 200, "u"), 1001 + GcodeJobTracker.GAP_MS).token);
    }
    @Test public void durationOrProgressResetReleasesTheOldScope() throws Exception {
        GcodeJobTracker t = new GcodeJobTracker(); GcodeJobTracker.Job a = t.update("A", "r", status("a.gcode", 2, 60, 500, "u"), 1000);
        GcodeJobTracker.Job b = t.update("A", "r", status("a.gcode", 2, 0, 0, "u"), 2000); assertNotEquals(a.token, b.token);
        assertSame(b, t.update("A", "r", status("a.gcode", 2, 60, 100, "u"), 3000));
        assertNotEquals(b.token, t.update("A", "r", status("a.gcode", 2, 10, 110, "u"), 4000).token);
    }
    @Test public void missingIdentityAndServiceRestartNeverRestoreByName() throws Exception {
        GcodeJobTracker a = new GcodeJobTracker(), b = new GcodeJobTracker(); JSONObject value = status("a.gcode", 2, 10, 100, "");
        assertNull(a.update("", "r", value, 1000));
        assertNotEquals(a.update("A", "r", value, 1000).token, b.update("A", "r", value, 1000).token);
    }
}
