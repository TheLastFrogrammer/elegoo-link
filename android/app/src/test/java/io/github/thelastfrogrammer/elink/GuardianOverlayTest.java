package io.github.thelastfrogrammer.elink;

import static org.junit.Assert.*;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** The print guardian's checks kept with a recording, and laid over its layer charts. */
public class GuardianOverlayTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test public void marksAreTheWorstPerLayerAndMeasureAlarmsInRed() {
        List<double[]> checks = Arrays.asList(
            new double[] {10, PrintRecorder.GUARDIAN_OFF_PRINT, 0.02, 0.03, 0, 0},     // under the limit: no mark
            new double[] {12, PrintRecorder.GUARDIAN_OFF_PRINT, 0.04, 0.03, 1, 0},     // over
            new double[] {12, PrintRecorder.GUARDIAN_OFF_PRINT, 0.05, 0.03, 1, 0},     // the same layer, worse: this one
            new double[] {14, PrintRecorder.GUARDIAN_SPREAD, 0.08, 0.015, 1, 1},       // raised the alarm
            new double[] {20, PrintRecorder.GUARDIAN_COVERAGE, 0.10, 0.30, 1, 0});     // coverage: the lowest is the worst
        List<double[]> answers = new ArrayList<>(); answers.add(new double[] {14, 1});
        List<Object[]> marks = LayerCharts.marks(new LayerCharts.Guardian(checks, answers), 0xff00796b);
        assertEquals(4, marks.size());
        assertEquals(12.0, (Double) marks.get(0)[0], 0); assertEquals(LayerCharts.AMBER, marks.get(0)[1]);
        assertTrue((String) marks.get(0)[2], ((String) marks.get(0)[2]).contains("filament off the print 5.0 % (limit 3.0 %)"));
        assertEquals(LayerCharts.RED, marks.get(1)[1]); assertTrue(((String) marks.get(1)[2]).startsWith("Guardian alarm:"));
        assertTrue(((String) marks.get(2)[2]).contains("print area covered 10.0 %"));
        assertEquals("You said it looked fine", marks.get(3)[2]); assertEquals(0xff00796b, marks.get(3)[1]);
    }

    @Test public void checksAreKeptWithTheRecordingAndAnswersFindTheirLayer() throws Exception {
        File dir = folder.newFolder("rec");
        PrintRecorder recorder = new PrintRecorder(dir);
        JSONObject status = new JSONObject().put("machine_status", new JSONObject().put("status", 2).put("sub_status", 2075).put("progress", 10))
            .put("print_status", new JSONObject().put("filename", "a.gcode").put("uuid", "u").put("current_layer", 7));
        recorder.update(1_000, status, "local", "CC2");
        PrintRecorder.Recording now = recorder.current();
        recorder.noteGuardianCheck(now.meta, 7, PrintRecorder.GUARDIAN_SPREAD, 0.02, 0.015, true, false);
        recorder.noteGuardianCheck(new File(dir, "other.json"), 7, PrintRecorder.GUARDIAN_SPREAD, 0.5, 0.015, true, true);   // another print: not kept
        recorder.noteGuardianCheck(now.meta, 7, PrintRecorder.GUARDIAN_COVERAGE, 0.6, Double.NaN, false, false);         // no limit yet: kept as NaN
        PrintRecorder.Recording read = PrintRecorder.Recording.read(now.meta);
        assertEquals(2, read.guardianChecks.size());
        assertArrayEquals(new double[] {7, PrintRecorder.GUARDIAN_SPREAD, 0.02, 0.015, 1, 0}, read.guardianChecks.get(0), 1e-9);
        assertTrue(Double.isNaN(read.guardianChecks.get(1)[3]));
        // An answer given at a moment belongs to the layer printing then.
        List<double[]> rows = new ArrayList<>();
        for (int i = 0; i < 5; i++) { double[] row = new double[PrintRecorder.COLUMNS.length]; Arrays.fill(row, Double.NaN); row[PrintRecorder.column("time_ms")] = 1000 * (i + 1); row[PrintRecorder.column("layer")] = 10 + i; rows.add(row); }
        PrintJournal journal = new PrintJournal();
        journal.guardian.add(new JSONObject().put("at", 2500).put("fine", true));
        journal.guardian.add(new JSONObject().put("at", 99_000).put("fine", false));   // after the last sample: the last layer
        List<double[]> answers = RecordingsActivity.answerLayers(journal, rows);
        assertArrayEquals(new double[] {12, 1}, answers.get(0), 0);
        assertArrayEquals(new double[] {14, 0}, answers.get(1), 0);
    }
}
