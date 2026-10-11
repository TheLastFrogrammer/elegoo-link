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
}
