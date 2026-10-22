package io.github.thelastfrogrammer.elink;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.File;
import java.nio.file.Files;
import java.util.List;

public class PrintRecorderTest {
    private File dir;
    @Before public void setUp() throws Exception { dir = Files.createTempDirectory("recordings").toFile(); }

    private static JSONObject status(int sub, String file, int layer, int progress) throws Exception {
        return new JSONObject()
            .put("machine_status", new JSONObject().put("status", 2).put("sub_status", sub).put("progress", progress))
            .put("print_status", new JSONObject().put("filename", file).put("uuid", "uuid-" + file).put("current_layer", layer).put("total_layer", 100).put("remaining_time_sec", 3600))
            .put("extruder", new JSONObject().put("temperature", 219.6).put("target", 220))
            .put("heater_bed", new JSONObject().put("temperature", 60).put("target", 60))
            .put("ztemperature_sensor", new JSONObject().put("temperature", 31))
            .put("gcode_move_inf", new JSONObject().put("z", 1.2).put("speed_mode", 1))
            .put("fans", new JSONObject().put("fan", new JSONObject().put("speed", 255)).put("aux_fan", new JSONObject().put("speed", 128)));
    }
    private static JSONObject idle() throws Exception { return new JSONObject().put("machine_status", new JSONObject().put("status", 1)); }

    @Test public void samplesAreThrottledExceptOnLayerChangesAndTheEnd() throws Exception {
        PrintRecorder recorder = new PrintRecorder(dir);
        assertFalse(recorder.update(0, idle(), "local", "CC2"));
        assertTrue(recorder.update(1_000, status(2075, "a.gcode", 1, 1), "local", "CC2"));
        assertFalse("within 10 s, same layer", recorder.update(5_000, status(2075, "a.gcode", 1, 2), "local", "CC2"));
        assertTrue("layer changed", recorder.update(6_000, status(2075, "a.gcode", 2, 2), "local", "CC2"));
        assertTrue("10 s passed", recorder.update(16_000, status(2075, "a.gcode", 2, 3), "cloud", "CC2"));
        assertTrue("completion always recorded", recorder.update(17_000, status(2077, "a.gcode", 100, 100), "cloud", "CC2"));
        assertNull(recorder.current());
        List<PrintRecorder.Recording> all = recorder.list();
        assertEquals(1, all.size());
        PrintRecorder.Recording recording = all.get(0);
        assertEquals("complete", recording.outcome); assertEquals(4, recording.samples); assertEquals(100, recording.layers); assertEquals("a.gcode", recording.file);
        List<double[]> rows = PrintRecorder.samples(recording);
        assertEquals(4, rows.size());
        double[] first = rows.get(0);
        assertEquals(1, first[PrintRecorder.column("progress")], 0); assertEquals(219.6, first[PrintRecorder.column("nozzle_c")], 0.001);
        assertEquals(100, first[PrintRecorder.column("part_fan_pct")], 0); assertEquals(50, first[PrintRecorder.column("aux_fan_pct")], 0);
        assertTrue(Double.isNaN(first[PrintRecorder.column("chamber_fan_pct")]));
        assertEquals(1.2, first[PrintRecorder.column("z_mm")], 0.001); assertEquals(0, first[PrintRecorder.column("source")], 0);
        assertEquals(1, rows.get(2)[PrintRecorder.column("source")], 0);
        assertEquals(15, rows.get(2)[PrintRecorder.column("elapsed_s")], 0);
    }

    @Test public void stoppedNewPrintAndIdleOutcomes() throws Exception {
        PrintRecorder recorder = new PrintRecorder(dir);
        recorder.update(0, status(2075, "a.gcode", 1, 1), "local", "CC2");
        recorder.update(20_000, status(2075, "b.gcode", 1, 1), "local", "CC2");
        recorder.update(40_000, status(2504, "b.gcode", 3, 5), "local", "CC2");
        recorder.update(60_000, status(2075, "c.gcode", 1, 1), "local", "CC2");
        recorder.update(80_000, idle(), "local", "CC2");
        List<PrintRecorder.Recording> all = recorder.list();
        assertEquals(3, all.size());
        assertEquals("ended", all.get(0).outcome); assertEquals("stopped", all.get(1).outcome); assertEquals("interrupted", all.get(2).outcome);
    }

    /** Written on the background thread, the files still read back complete, in order, right after each update. */
    @Test public void backgroundWritesReadBackInOrder() throws Exception {
        PrintRecorder recorder = new PrintRecorder(dir); recorder.writeInBackground();
        recorder.update(1_000, status(2075, "a.gcode", 1, 1), "local", "CC2");
        for (int i = 2; i <= 30; i++) recorder.update(i * 11_000L, status(2075, "a.gcode", i, i), "local", "CC2");
        recorder.update(400_000, status(2077, "a.gcode", 31, 100), "local", "CC2");
        List<PrintRecorder.Recording> all = recorder.list();
        assertEquals(1, all.size()); assertEquals("complete", all.get(0).outcome);
        List<double[]> rows = PrintRecorder.samples(all.get(0));
        assertEquals(31, rows.size());
        for (int i = 0; i < rows.size(); i++) assertEquals(i + 1, rows.get(i)[PrintRecorder.column("layer")], 0);
        assertNotNull(recorder.lastFinished());
    }

    /** The end of a print is told once (for its video), but not a print only interrupted by another one starting. */
    @Test public void theEndOfAPrintIsToldButNotAnInterruption() throws Exception {
        PrintRecorder recorder = new PrintRecorder(dir);
        List<String> told = new java.util.ArrayList<>();
        recorder.printEnded = r -> told.add(r.file + ":" + r.outcome);
        recorder.update(0, status(2075, "a.gcode", 1, 1), "local", "CC2");
        recorder.update(20_000, status(2075, "b.gcode", 1, 1), "local", "CC2");   // a interrupted
        recorder.update(40_000, status(2077, "b.gcode", 3, 100), "local", "CC2");
        recorder.update(41_000, status(2077, "b.gcode", 3, 100), "local", "CC2");   // the printer keeps reporting the end
        assertEquals(java.util.Collections.singletonList("b.gcode:complete"), told);
    }

    @Test public void anUnfinishedRecordingResumesAfterAnAppRestart() throws Exception {
        new PrintRecorder(dir).update(0, status(2075, "a.gcode", 1, 1), "local", "CC2");
        PrintRecorder restarted = new PrintRecorder(dir);
        restarted.update(60_000, status(2075, "a.gcode", 5, 10), "cloud", "CC2");
        assertEquals(1, restarted.list().size()); assertEquals(2, restarted.list().get(0).samples);
        // Much later the same file is a new print.
        PrintRecorder later = new PrintRecorder(dir);
        later.update(60_000 + PrintRecorder.RESUME_WINDOW_MS + 1, status(2075, "a.gcode", 1, 1), "local", "CC2");
        assertEquals(2, later.list().size());
    }

    @Test public void deleteRemovesBothFilesAndOddFilenamesAreSafe() throws Exception {
        PrintRecorder recorder = new PrintRecorder(dir);
        recorder.update(0, status(2075, "../evil name,with comma.gcode", 1, 1), "local", "CC2");
        PrintRecorder.Recording recording = recorder.list().get(0);
        assertEquals(dir.getCanonicalFile(), recording.csv.getCanonicalFile().getParentFile());
        assertEquals(1, PrintRecorder.samples(recording).size());
        recorder.delete(recording);
        assertTrue(recorder.list().isEmpty()); assertFalse(recording.csv.exists());
    }

    @Test public void chartScalesAreReadable() {
        assertEquals(20, ChartView.niceStep(17), 0); assertEquals(0.5, ChartView.niceStep(0.31), 1e-9); assertEquals(1, ChartView.niceStep(0), 0);
        assertEquals(1800, ChartView.niceTime(1500), 0); assertEquals("1:05", ChartView.elapsed(3900)); assertEquals("—", ChartView.format(Double.NaN));
    }

    /** The active tray and its colour go into each sample; pictures come at the print's own interval and are kept with it. */
    @Test public void traysAndPicturesAreRecordedWithThePrint() throws Exception {
        PrintRecorder recorder = new PrintRecorder(dir);
        recorder.setDefaultPictureMinutes(5);
        recorder.setCanvas(new JSONObject("{\"active_canvas_id\":0,\"active_tray_id\":1,\"canvas_list\":[{\"canvas_id\":0,\"tray_list\":[{\"tray_id\":1,\"filament_color\":\"#D02828\"}]}]}"));
        assertTrue(recorder.update(1_000, 1_000, status(2075, "b.gcode", 1, 1), "cloud", "CC2"));
        PrintRecorder.Recording now = recorder.current();
        assertEquals(5, now.pictureMinutes);
        assertTrue("the first picture is due at once", recorder.pictureDue(1_000));
        byte[] jpeg = new byte[200]; jpeg[0] = (byte) 0xFF; jpeg[1] = (byte) 0xD8;
        assertTrue(recorder.savePicture(jpeg, 61_000));
        assertFalse("not again before five minutes", recorder.pictureDue(61_000 + 4 * 60_000));
        assertTrue(recorder.pictureDue(61_000 + 5 * 60_000));
        recorder.setPictureMinutes(0); assertFalse("this print's pictures turned off", recorder.pictureDue(10_000_000));
        List<java.io.File> pictures = PrintRecorder.pictures(now);
        assertEquals(1, pictures.size()); assertEquals("000060.jpg", pictures.get(0).getName());
        double[] row = PrintRecorder.samples(now).get(0);
        assertEquals(2, row[PrintRecorder.column("tray")], 0); assertEquals(0xD02828, row[PrintRecorder.column("tray_rgb")], 0);
        List<String> changes = RecordingsActivity.trayChanges(new double[] {2, 2, 5}, new double[] {0xD02828, 0xD02828, 0xFFFFFF}, new double[] {0, 60, 125});
        assertEquals(2, changes.size()); assertTrue(changes.get(0), changes.get(0).startsWith("From 0:00: tray 2 (")); assertTrue(changes.get(1), changes.get(1).contains("tray 5 (white)"));
        recorder.delete(now); assertFalse("pictures go with the recording", now.pictureFolder().exists());
    }

    /** Material per layer from the sliced lines: length × width × height. */
    @Test public void layerVolumesFollowTheLines() throws Exception {
        String gcode = ";LAYER_CHANGE\n;Z:0.2\n;HEIGHT:0.2\nG1 X0 Y0 Z0.2 F600\n;TYPE:Outer wall\n;WIDTH:0.4\nG1 X10 Y0 E1\n;LAYER_CHANGE\n;Z:0.4\n;HEIGHT:0.2\nG1 Z0.4\nG1 X10 Y20 E2\n";
        GcodeToolpath path = GcodeToolpath.read(new java.io.ByteArrayInputStream(gcode.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        double[] v = path.layerVolumes();
        assertEquals(2, v.length); assertEquals(10 * 0.4 * 0.2, v[0], 1e-6); assertEquals(20 * 0.4 * 0.2, v[1], 1e-6);
    }

    /** Which filament each layer mostly used, from the tool changes (T0, T1) in the file: the colours of the material chart. */
    @Test public void eachLayersMainFilamentComesFromTheToolChanges() throws Exception {
        String gcode = ";LAYER_CHANGE\n;Z:0.2\nG1 X0 Y0 Z0.2 F600\nT0\nG1 X10 Y0 E1\nT1\nG1 X12 Y0 E2\n;LAYER_CHANGE\n;Z:0.4\nG1 Z0.4\nG1 X12 Y20 E3\nT0\nG1 X13 Y20 E4\n";
        GcodeToolpath path = GcodeToolpath.read(new java.io.ByteArrayInputStream(gcode.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertArrayEquals(new int[] {0, 1}, path.layerMainTool());
        double[][] v = path.layerToolVolumes(2);
        assertTrue(v[0][0] > v[0][1]); assertTrue(v[0][1] > 0); assertTrue(v[1][1] > v[1][0]);
    }

    /** Progress maps onto the file's extrusions in order, so each filament's share comes out as the print goes. */
    @Test public void materialLaidDownFollowsProgressAndGivesSmoothRates() throws Exception {
        String gcode = ";LAYER_CHANGE\n;Z:0.2\nG1 X0 Y0 Z0.2 F600\nT0\nG1 X10 Y0 E1\nT1\nG1 X20 Y0 E2\n";
        GcodeToolpath path = GcodeToolpath.read(new java.io.ByteArrayInputStream(gcode.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals(2, path.toolCount());
        double[][] at = path.toolVolumesAt(new double[] {0, 0.5, 1, Double.NaN}, 2);
        double half = path.toolVolumes(2)[0];
        assertEquals(0, at[0][0], 1e-9); assertEquals(half, at[1][0], 1e-6); assertEquals(0, at[1][1], 1e-6);
        assertEquals(half, at[2][1], 1e-6); assertTrue(Double.isNaN(at[3][0]));
        // An even 1 mm³ a second of filament 1: 3.6 g/h at 1 g/cm³, steady even where readings are sparse.
        double[] t = new double[20]; double[][] cumulative = new double[20][2];
        for (int i = 0; i < 20; i++) { t[i] = i * 60; cumulative[i][0] = i * 60; }
        double[][] rate = RecordingsActivity.depositionRates(t, cumulative, new double[] {0.001, 0.001});
        for (int i = 0; i < 20; i++) { assertEquals(3.6, rate[i][0], 1e-9); assertEquals(0, rate[i][1], 1e-9); }
    }

    /** Progress in whole percents, read every 20 s: the rate comes out steady, not a sawtooth between step counts. */
    @Test public void depositionRateIsSteadyWhenProgressMovesInSteps() {
        int n = 900; double[] t = new double[n]; double[][] cumulative = new double[n][1];
        for (int i = 0; i < n; i++) { t[i] = i * 20; cumulative[i][0] = Math.floor(t[i] / 147.0) * 147.0; }   // 1 mm³/s, stepping every 147 s
        double[][] rate = RecordingsActivity.depositionRates(t, cumulative, new double[] {0.001});
        for (int i = 100; i < n - 100; i++) assertEquals("reading " + i, 3.6, rate[i][0], 0.15);   // within the 20 s reading spacing (whole-window counting swung ±25 %)
    }

    /** Warm-up: each step the printer reports before the first layer, merged, and the file's own start once it reports printing. */
    @Test public void warmUpIsTheStepsBeforeTheFirstLayer() {
        double n = Double.NaN;
        double[] t = {0, 30, 90, 180, 300, 360, 420}, sub = {1081, 1405, 1405, 2901, 2075, 2075, 2075}, layer = {n, n, n, 0, 0, 1, 2};
        List<Object[]> phases = RecordingsActivity.warmUpPhases(sub, layer, t);
        assertEquals(4, phases.size());
        assertEquals("getting the file", phases.get(0)[2]);
        assertEquals("heating the bed", phases.get(1)[2]); assertEquals(30.0, phases.get(1)[0]); assertEquals(180.0, phases.get(1)[1]);
        assertEquals("levelling the bed", phases.get(2)[2]);
        assertEquals("the file's start", phases.get(3)[2]); assertEquals(360.0, phases.get(3)[1]);
        assertEquals("Warm-up: 6 min before the first layer (getting the file 1 min, heating the bed 3 min, levelling the bed 2 min, the file's start 1 min). Shaded on the charts.",
            RecordingsActivity.warmUpWords(phases));
        assertTrue("already printing when the recording began", RecordingsActivity.warmUpPhases(new double[] {2075, 2075}, new double[] {3, 4}, new double[] {0, 30}).isEmpty());
        assertEquals("preparing", RecordingsActivity.warmUpStep(1234));
        assertNull("a pause is not warm-up", RecordingsActivity.warmUpStep(2502));
    }

    /** The tray band merges readings into stretches and names the stretches the printer did not report. */
    @Test public void trayReadingsMergeIntoStretchesWithGapsNamed() {
        double n = Double.NaN;
        double[] t = {0, 60, 120, 180, 600, 900, 960}, tray = {3, 3, 3, n, n, 3, 1}, rgb = {0xFF8800, 0xFF8800, 0xFF8800, n, n, 0xFF8800, 0xFFFFFF};
        List<double[]> s = RecordingsActivity.traySegments(tray, rgb, t);
        assertEquals(4, s.size());
        assertArrayEquals(new double[] {0, 180, 3, 0xFF8800}, s.get(0), 0);
        assertTrue(Double.isNaN(s.get(1)[2])); assertEquals(180, s.get(1)[0], 0); assertEquals(900, s.get(1)[1], 0);
        assertArrayEquals(new double[] {960, 960, 1, 0xFFFFFF}, s.get(3), 0);
        String gaps = RecordingsActivity.trayGaps(s);
        assertTrue(gaps, gaps.contains("0:03–0:15"));
        assertNull("all reported", RecordingsActivity.trayGaps(RecordingsActivity.traySegments(new double[] {2, 2}, new double[] {1, 1}, new double[] {0, 60})));
        // One tray before and after a gap is still one tray.
        assertEquals(1, RecordingsActivity.trayChanges(new double[] {3, n, 3}, new double[] {1, n, 1}, new double[] {0, 60, 120}).size());
    }

    @Test public void filamentUsedAddsUpEachFilamentAndAll() {
        double[][] cumulative = {{0, 0}, {1000, 0}, {Double.NaN, Double.NaN}, {2000, 500}};
        double[][] used = RecordingsActivity.usedGrams(cumulative, new double[] {0.00124, 0.001});
        assertEquals(0, used[0][2], 1e-9);
        assertEquals(1.24, used[1][0], 1e-9); assertEquals(1.24, used[1][2], 1e-9);
        assertTrue(Double.isNaN(used[2][0])); assertTrue(Double.isNaN(used[2][2]));
        assertEquals(2.48, used[3][0], 1e-9); assertEquals(0.5, used[3][1], 1e-9); assertEquals(2.98, used[3][2], 1e-9);
        // The total joins in only once the second filament is used, starting from the reading before.
        double[] together = RecordingsActivity.sharedTotal(used, 2);
        assertTrue(Double.isNaN(together[0])); assertEquals(1.24, together[1], 1e-9); assertTrue(Double.isNaN(together[2])); assertEquals(2.98, together[3], 1e-9);
    }
    @Test public void elapsedTimeCountsFromTheFirstSampleOnTheMonotonicClock() throws Exception {
        PrintRecorder recorder = new PrintRecorder(dir);
        assertEquals(-1, recorder.elapsedSeconds(5_000));
        assertTrue(recorder.update(1_000_000, 10_000, status(2075, "a.gcode", 1, 1), "local", "CC2"));
        assertEquals(0, recorder.elapsedSeconds(10_000));
        assertEquals(125, recorder.elapsedSeconds(135_000));
        recorder.update(2_000_000, 140_000, idle(), "local", "CC2");
        assertEquals("no print, no elapsed time", -1, recorder.elapsedSeconds(150_000));
    }
    @Test public void picturesEveryNLayersFollowTheLayerNotTheClock() throws Exception {
        PrintRecorder recorder = new PrintRecorder(dir); recorder.setDefaultPictureLayers(2);
        assertTrue(recorder.update(1_000, 1_000, status(2075, "a.gcode", 1, 1), "local", "CC2"));
        assertEquals("Every 2 layers", recorder.current().pictureWords());
        assertTrue("the first layer gets a picture", recorder.pictureDue(1_000));
        assertTrue(recorder.savePicture(new byte[200], 1_000, "test", PrintRecorder.PRINTER_CAMERA));
        assertFalse(recorder.pictureDue(600_000));   // hours later, still layer 1: no picture by time
        recorder.update(700_000, 700_000, status(2075, "a.gcode", 2, 2), "local", "CC2");
        assertFalse("one layer on", recorder.pictureDue(700_000));
        recorder.update(710_000, 710_000, status(2075, "a.gcode", 3, 3), "local", "CC2");
        assertTrue("two layers on", recorder.pictureDue(710_000));
        recorder.setPictureMinutes(5);
        assertEquals("back to time", "Every 5 minutes", recorder.current().pictureWords());
    }

    private static JSONObject moving(int layer, int progress, double e, long remaining) throws Exception {
        JSONObject s = status(2075, "e.gcode", layer, progress);
        s.getJSONObject("gcode_move_inf").put("e", e); s.getJSONObject("print_status").put("remaining_time_sec", remaining);
        return s;
    }
    @Test public void filamentFedSkipsResetsAndRetractions() throws Exception {
        PrintRecorder recorder = new PrintRecorder(dir);
        recorder.update(0, moving(1, 0, 0, 3600), "local", "CC2");
        recorder.update(20_000, moving(2, 1, 100, 3600), "local", "CC2");
        recorder.update(40_000, moving(3, 2, 99.2, 3600), "local", "CC2");     // a retraction
        recorder.update(60_000, moving(4, 3, 0.5, 3600), "local", "CC2");      // G92 E0: skipped
        recorder.update(80_000, moving(5, 4, 50.5, 3600), "local", "CC2");
        assertEquals(149.2, recorder.current().extrudedMm, 0.01);
        List<double[]> rows = PrintRecorder.samples(recorder.current());
        assertEquals(149.2, rows.get(rows.size() - 1)[PrintRecorder.column("extruded_mm")], 0.01);
        assertEquals(149.2, PrintRecorder.Recording.read(recorder.current().meta).extrudedMm, 0.01);
        assertEquals(0.45, PrintRecorder.grams(149.2, "PLA"), 0.01);
    }
    @Test public void finishedPrintsTeachHowLongTheEstimateRuns() throws Exception {
        PrintRecorder recorder = new PrintRecorder(dir);
        for (int i = 0; i < 3; i++) {
            String file = "p" + i + ".gcode"; long t0 = i * 10_000_000L;
            recorder.update(t0, status(2075, file, 1, 0), "local", "CC2");
            JSONObject ten = status(2075, file, 10, 10); ten.getJSONObject("print_status").put("remaining_time_sec", 900);
            recorder.update(t0 + 100_000, ten, "local", "CC2");   // estimate at 10 %: 100 + 900 = 1000 s
            recorder.update(t0 + 1_200_000, status(2077, file, 100, 100), "local", "CC2");   // took 1200 s: 20 % over
        }
        assertEquals(1.2, recorder.learnedRatioNow(), 0.01);
        List<PrintRecorder.Recording> all = recorder.list();
        assertEquals(1.2, PrintRecorder.ratio(all.get(0)), 0.01);
        // A new print: at 10 % the printer says 900 s left after 100 s; by experience 1200 s in all, so 1100 s left.
        recorder.update(50_000_000L, status(2075, "new.gcode", 1, 0), "local", "CC2");
        JSONObject ten = status(2075, "new.gcode", 10, 10); ten.getJSONObject("print_status").put("remaining_time_sec", 900);
        recorder.update(50_100_000L, ten, "local", "CC2");
        assertEquals(1100, recorder.learnedRemaining(50_100_000L), 1);
        assertTrue("fewer than three prints teach nothing", Double.isNaN(PrintRecorder.learnedRatio(all.subList(0, 2))));
    }

    /** The end of a print reported once more, or reported without its uuid (cloud poll versus live report), is not a new print. */
    @Test public void theEndOfAPrintDoesNotStartAnotherRecording() throws Exception {
        PrintRecorder recorder = new PrintRecorder(dir);
        JSONObject noUuid = status(2075, "clip.gcode", 1, 1); noUuid.getJSONObject("print_status").put("uuid", "");
        recorder.update(0, noUuid, "cloud", "CC2");
        recorder.update(20_000, status(2075, "clip.gcode", 2, 5), "cloud", "CC2");     // the uuid appears: the same print
        recorder.update(40_000, status(2077, "clip.gcode", 24, 99), "cloud", "CC2");
        JSONObject endAgain = status(2077, "clip.gcode", 24, 100); endAgain.getJSONObject("print_status").put("uuid", "");
        assertFalse(recorder.update(45_000, endAgain, "cloud", "CC2"));
        assertFalse(recorder.update(50_000, status(2077, "clip.gcode", 24, 100), "cloud", "CC2"));
        recorder.update(60_000, idle(), "cloud", "CC2");
        List<PrintRecorder.Recording> all = recorder.list();
        assertEquals(1, all.size());
        assertEquals("complete", all.get(0).outcome); assertEquals("uuid-clip.gcode", all.get(0).id);
        // Printed again after going idle: a new recording.
        recorder.update(70_000, status(2075, "clip.gcode", 1, 1), "cloud", "CC2");
        assertEquals(2, recorder.list().size());
        // A different uuid for the same file is a different print.
        assertFalse(PrintRecorder.samePrint("uuid-1", "clip.gcode", "uuid-2", "clip.gcode"));
        assertTrue(PrintRecorder.samePrint("clip.gcode", "clip.gcode", "uuid-2", "clip.gcode"));
    }

    /** Two printers running files of the same name are two prints; a print resumes only on its own printer. */
    @Test public void printersAreToldApart() throws Exception {
        PrintRecorder recorder = new PrintRecorder(dir);
        recorder.update(0, 0, status(2075, "part.gcode", 1, 1), "local", "Bench", "SERIAL-A");
        recorder.update(20_000, 20_000, status(2075, "part.gcode", 5, 10), "cloud", "Garage", "SERIAL-B");
        recorder.update(40_000, 40_000, status(2075, "part.gcode", 6, 12), "local", "Bench", "serial-a");   // the same printer, back
        List<PrintRecorder.Recording> all = recorder.list();
        assertEquals(2, all.size());
        assertNotEquals(all.get(0).printerId, all.get(1).printerId);
        assertFalse("only a digest is kept", all.get(0).printerId.contains("SERIAL") || all.get(1).printerId.contains("SERIAL"));
        PrintRecorder.Recording bench = all.get(0).printer.equals("Bench") ? all.get(0) : all.get(1);
        assertEquals("resumed on its own printer", 2, bench.samples);
    }
}
