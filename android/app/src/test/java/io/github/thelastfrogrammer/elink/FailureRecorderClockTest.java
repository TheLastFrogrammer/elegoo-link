package io.github.thelastfrogrammer.elink;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.File;
import java.nio.file.Files;
import java.util.List;

/** Failure audit, clock changes during a recorded print. PrintRecorder throttles and times samples with the wall clock it is given. */
public class FailureRecorderClockTest {
    private File dir;
    @Before public void setUp() throws Exception { dir = Files.createTempDirectory("recordings").toFile(); }

    private static JSONObject printing(int layer) throws Exception {
        return new JSONObject()
            .put("machine_status", new JSONObject().put("status", 2).put("sub_status", 2075).put("progress", 10))
            .put("print_status", new JSONObject().put("filename", "a.gcode").put("uuid", "u1").put("current_layer", layer).put("total_layer", 100).put("remaining_time_sec", 3600));
    }

    /** NTP or the user sets the clock back one hour: now - lastSample is negative, so "less than 10 s since the last sample" holds for an hour. */
    @Ignore("demonstrates: MEDIUM - a backwards clock step stops recording for as long as the step (here 1 h) unless the layer changes; the graph has a gap and elapsed_s goes negative once recording resumes")
    @Test public void aBackwardsClockStepDoesNotStopRecording() throws Exception {
        PrintRecorder recorder = new PrintRecorder(dir); long t = 10_000_000_000L;
        assertTrue(recorder.update(t, printing(5), "local", "CC2"));
        assertTrue(recorder.update(t + 10_000, printing(5), "local", "CC2"));
        long back = t + 10_000 - 3_600_000; int written = 0;
        for (int i = 1; i <= 10; i++) if (recorder.update(back + i * 10_000L, printing(5), "local", "CC2")) written++;
        assertTrue("samples written in the 100 s after the step: " + written, written >= 5);
    }

    /** The clock jumps forward 3 h (time zone fixed by hand, NTP catch-up): one sample later the recording claims a 3 h longer print. */
    @Ignore("demonstrates: LOW - a forward clock jump adds its whole size to elapsed_s and the recording's duration, because both are wall-clock differences")
    @Test public void aForwardClockJumpDoesNotInflateTheDuration() throws Exception {
        PrintRecorder recorder = new PrintRecorder(dir); long t = 10_000_000_000L;
        recorder.update(t, printing(1), "local", "CC2");
        recorder.update(t + 600_000, printing(2), "local", "CC2");
        recorder.update(t + 600_000 + 3 * 3_600_000L + 10_000, printing(3), "local", "CC2");
        List<PrintRecorder.Recording> all = recorder.list();
        assertTrue("duration " + all.get(0).duration() / 1000 + " s for 620 s of real printing", all.get(0).duration() < 3_600_000L);
    }

    /** Works well: a printer that goes idle without reporting completion (power loss, reboot) is "ended", never "complete", and its length is not inflated by the time the app was away. */
    @Test public void aPrintThatVanishesIsNotRecordedAsComplete() throws Exception {
        PrintRecorder recorder = new PrintRecorder(dir); long t = 10_000_000_000L;
        recorder.update(t, printing(1), "local", "CC2");
        recorder.update(t + 600_000, printing(2), "local", "CC2");
        recorder.update(t + 600_000 + 5 * 3_600_000L, new JSONObject().put("machine_status", new JSONObject().put("status", 1)), "local", "CC2");
        PrintRecorder.Recording recording = recorder.list().get(0);
        assertEquals("ended", recording.outcome);
        assertTrue(recording.duration() <= 600_000 + 60_000);
    }
}
