package io.github.thelastfrogrammer.elink;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;

public class FailureReportTest {
    static final double N = Double.NaN;
    // Ten readings a minute apart, printing from layer 1 to 10.
    static final double[] T = {0, 60, 120, 180, 240, 300, 360, 420, 480, 540};
    static final double[] TIME = {1000, 61000, 121000, 181000, 241000, 301000, 361000, 421000, 481000, 541000};
    static final double[] LAYER = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
    static final double[] PRINTING = {2075, 2075, 2075, 2075, 2075, 2075, 2075, 2075, 2075, 2503};
    static final double[] FILAMENT = {1, 1, 1, 1, 1, 1, 0, 0, 0, 0};
    static final double[] NONE = {N, N, N, N, N, N, N, N, N, N};

    @Test public void appliesToStoppedOrFailedPrints() {
        PrintRecorder.Recording r = new PrintRecorder.Recording(new File("a.csv"), new File("a.json"));
        r.outcome = "complete"; assertFalse(FailureReport.applies(r));
        r.journal.result = "failed"; assertTrue(FailureReport.applies(r));
        r.journal.result = ""; r.outcome = "stopped"; assertTrue(FailureReport.applies(r));
    }

    @Test public void theMomentFollowsTheStrongestSign() throws Exception {
        PrintJournal j = new PrintJournal(); List<double[]> guardian = new ArrayList<>(); List<RecordingAnalysis.Finding> found = new ArrayList<>();
        FailureReport.Moment stopped = FailureReport.moment(T, TIME, LAYER, PRINTING, NONE, guardian, j, found);
        assertEquals(480, stopped.t, 0); assertEquals(9, stopped.layer); assertTrue(stopped.how.contains("stopped"));
        assertEquals(360, FailureReport.moment(T, TIME, LAYER, PRINTING, FILAMENT, guardian, j, found).t, 0);
        found.add(new RecordingAnalysis.Finding(RecordingAnalysis.Finding.NOZZLE_DROP, 5, "The nozzle fell below its temperature at layer 5."));
        FailureReport.Moment drop = FailureReport.moment(T, TIME, LAYER, PRINTING, FILAMENT, guardian, j, found);
        assertEquals(5, drop.layer); assertTrue(drop.how, drop.how.startsWith("the readings: the nozzle"));
        guardian.add(new double[] {3, 0, 0.5, 0.3, 1, 0}); guardian.add(new double[] {4, 0, 0.5, 0.3, 1, 1});
        assertEquals(4, FailureReport.moment(T, TIME, LAYER, PRINTING, FILAMENT, guardian, j, found).layer);
        j.guardian.add(new JSONObject().put("at", 125000).put("fine", true));
        j.guardian.add(new JSONObject().put("at", 245000).put("fine", false));
        FailureReport.Moment confirmed = FailureReport.moment(T, TIME, LAYER, PRINTING, FILAMENT, guardian, j, found);
        assertEquals(300, confirmed.t, 0); assertTrue(confirmed.how.contains("confirmed"));
        assertNull(FailureReport.moment(new double[0], new double[0], new double[0], new double[0], new double[0], guardian, j, found));
    }

    @Test public void theLastGoodPictureIsAMinuteBefore() {
        double[] times = {100, 200, 290, 330, 400};
        assertArrayEquals(new int[] {1, 3}, FailureReport.pictures(times, 300));
        assertArrayEquals(new int[] {3, 4}, FailureReport.pictures(times, 420));
        assertArrayEquals(new int[] {4, -1}, FailureReport.pictures(times, 600));
        assertArrayEquals(new int[] {-1, 0}, FailureReport.pictures(times, 50));
    }

    @Test public void readingsAroundTheMoment() {
        double[] nozzle = {215, 215, 214, 215, 215, 204, 215, 215, 215, 215}, target = {215, 215, 215, 215, 215, 215, 215, 215, 215, 215};
        double[] bed = {60, 60, 60, 60, 60, 60, 60, 60, 60, 60};
        double[] fan = {100, 100, 100, 100, 100, 100, 100, 100, 100, 100};
        List<String> lines = FailureReport.readings(T, nozzle, target, bed, bed, fan, PRINTING, 420);
        assertTrue(lines.toString(), lines.get(0).startsWith("At that moment: nozzle 215 °C (set 215), bed 60 °C (set 60), part fan 100 %"));
        assertTrue(lines.toString(), lines.get(1).contains("nozzle fell up to 11 °C"));
        List<String> calm = FailureReport.readings(T, target, target, bed, bed, fan, PRINTING, 120);
        assertTrue(calm.toString(), calm.get(1).contains("held their targets"));
    }

    @Test public void whatTheGcodePrintedOnTheLayer() throws Exception {
        String g = String.join("\n", "M83",
            ";LAYER_CHANGE", ";Z:0.2", "G1 Z0.2", ";TYPE:Outer wall", "G1 X10 Y10 F6000", "G1 X50 Y10 E2", "G1 X50 Y50 E2", ";TYPE:Sparse infill", "G1 X10 Y50 E2", "G1 X10 Y10 E2",
            ";LAYER_CHANGE", ";Z:0.4", "G1 Z0.4", ";TYPE:Bridge", "G1 X50 Y50 E2", "T1", ";TYPE:Sparse infill", "G1 X45 Y50 E1", "");
        GcodeToolpath path = GcodeToolpath.read(new ByteArrayInputStream(g.getBytes(StandardCharsets.UTF_8)));
        String one = FailureReport.gcodeWords(path, 1);
        assertTrue(one, one.startsWith("Layer 1 of 2 (z 0.20 mm) is mostly outer wall 50 %, sparse infill 50 %."));
        String two = FailureReport.gcodeWords(path, 2);
        assertTrue(two, two.contains("bridges") && two.contains("a filament change") && two.contains("much less to print"));
        assertEquals("", FailureReport.gcodeWords(path, 3));
        assertEquals("", FailureReport.gcodeWords(null, 1));
    }
}
