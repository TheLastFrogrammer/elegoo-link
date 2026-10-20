package io.github.thelastfrogrammer.elink;

import static org.junit.Assert.*;

import java.io.File;
import java.nio.file.Files;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** The layer-change park for a smooth timelapse, and the recording taking each layer's picture only while the head is there. */
public class SmoothTimelapseTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test public void theParkGcodeSavesLiftsParksWaitsAndRestores() {
        String gcode = SmoothTimelapse.gcode(0, 3000);
        String[] lines = gcode.split("\n");
        assertEquals("SAVE_GCODE_STATE NAME=link_timelapse", lines[1]);
        assertTrue(gcode.contains("G91\nG1 Z0.6 F900\nG90\nG1 X250.0 Y250.0 F18000\nM400\nG4 P3000\n"));
        assertTrue(gcode.contains("RESTORE_GCODE_STATE NAME=link_timelapse MOVE=1"));
        assertTrue("no extrusion inside the park", !gcode.matches("(?s).*\\bE-?\\d.*"));
        assertTrue(SmoothTimelapse.gcode(3, 1).contains("G1 X6.0 Y6.0") && SmoothTimelapse.gcode(3, 1).contains("G4 P500"));
    }

    @Test public void parkedMeansWithinTwoMillimetres() {
        assertTrue(SmoothTimelapse.parked(249.0, 251.5, 250, 250));
        assertFalse(SmoothTimelapse.parked(246.0, 250, 250, 250));
        assertFalse(SmoothTimelapse.parked(Double.NaN, 250, 250, 250));
    }

    private static JSONObject status(int layer, double x, double y) throws Exception {
        return new JSONObject().put("machine_status", new JSONObject().put("status", 2).put("sub_status", 2075).put("progress", 10))
            .put("print_status", new JSONObject().put("filename", "cube.gcode").put("current_layer", layer).put("uuid", "u1"))
            .put("gcode_move", new JSONObject().put("x", x).put("y", y).put("z", layer * 0.2));
    }

    @Test public void oneParkedPicturePerLayerAndNoneWhileTheHeadIsOverThePrint() throws Exception {
        PrintRecorder recorder = new PrintRecorder(folder.newFolder("recordings"));
        long t = 1_000_000;
        recorder.update(t, t, status(1, 120, 120), "local", "Bedroom");
        assertNotNull(recorder.current());
        recorder.setPark(250, 250);
        assertTrue(recorder.current().smooth());
        assertFalse("not parked: no picture", recorder.headAt(120, 120)); assertFalse(recorder.pictureDue(t));
        recorder.update(t + 5000, t + 5000, status(2, 250, 250), "local", "Bedroom");
        assertTrue("parked on a new layer: ask the camera", recorder.headAt(250, 250));
        assertFalse("asked once per parked moment", recorder.headAt(250, 250));
        assertTrue(recorder.pictureDue(t + 5000));
        assertTrue(recorder.savePicture(new byte[200], t + 5000));
        assertFalse("the layer has its picture", recorder.pictureDue(t + 6000)); assertFalse(recorder.headAt(250, 250));
        recorder.update(t + 9000, t + 9000, status(2, 130, 110), "local", "Bedroom");
        assertFalse(recorder.headAt(130, 110));
        recorder.update(t + 15000, t + 15000, status(3, 250, 250), "local", "Bedroom");
        assertTrue("next layer, parked again", recorder.headAt(250, 250));
        // Remembered with the recording, and said so in the list.
        PrintRecorder.Recording read = PrintRecorder.Recording.read(recorder.current().meta);
        assertTrue(read.smooth()); assertEquals("Every layer, head parked", read.pictureWords());
        assertTrue(Files.exists(new File(recorder.current().pictureFolder(), String.format("%06d.jpg", 5L)).toPath()));
    }
}
