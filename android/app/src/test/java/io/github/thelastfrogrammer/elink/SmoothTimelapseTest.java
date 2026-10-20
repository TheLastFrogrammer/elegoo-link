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

    @Test public void farFromTheCameraWaitsForTheFarSideOfEachLayer() throws Exception {
        PrintRecorder recorder = new PrintRecorder(folder.newFolder("far"));
        recorder.setDefaultPictureLayers(1); recorder.setDefaultPictureFar(true);
        long t = 2_000_000; double cx = -10, cy = -20;   // a camera at the front left
        recorder.update(t, t, status(1, 100, 100), "local", "Bedroom");
        assertTrue(recorder.current().pictureFar); assertEquals("Every layer, head far from the camera", recorder.current().pictureWords());
        // Layer 1 is only measured (nothing to compare with yet): the head reaches 150,150 at most.
        assertFalse(recorder.headFrom(100, 100, cx, cy)); assertFalse(recorder.headFrom(150, 150, cx, cy));
        recorder.update(t + 1000, t + 1000, status(2, 100, 100), "local", "Bedroom");
        assertFalse("near the camera: wait", recorder.headFrom(100, 100, cx, cy)); assertFalse(recorder.pictureDue(t + 1000));
        assertTrue("nearly as far as the last layer got", recorder.headFrom(148, 149, cx, cy)); assertTrue(recorder.pictureDue(t + 1000));
        assertFalse("swung back toward the camera before the picture: called off", recorder.headFrom(60, 60, cx, cy)); assertFalse(recorder.pictureDue(t + 1000));
        assertTrue(recorder.headFrom(150, 150, cx, cy));
        assertTrue(recorder.savePicture(new byte[200], t + 1200));
        assertFalse("one picture per layer", recorder.headFrom(151, 151, cx, cy));
        recorder.update(t + 2000, t + 2000, status(3, 100, 100), "local", "Bedroom");
        assertTrue(recorder.headFrom(150, 151, cx, cy));
    }

    private static final String TWO_LAYERS = String.join("\n",
        "G90", "M83", "G1 X5 Y5 Z0.2 F3000", "G1 X60 Y5 E2 ; purge line before the first layer comment",
        ";LAYER_CHANGE", ";Z:0.2", "G1 X100 Y100 F9000", "G1 X140 Y100 E1", "G1 X140 Y140 E1", "G1 X100 Y140 E1",
        ";LAYER_CHANGE", ";Z:0.4", "G1 Z0.4", "G1 X100 Y100", "G1 X120 Y100 E1", "G1 X120 Y120 E1", "G29.1 X999 Y999", "");

    @Test public void layerReachCountsLayersAsTheToolpathDoes() throws Exception {
        LayerReach reach = LayerReach.read(new java.io.ByteArrayInputStream(TWO_LAYERS.getBytes("UTF-8")), 0, 0);
        GcodeToolpath path = GcodeToolpath.read(new java.io.ByteArrayInputStream(TWO_LAYERS.getBytes("UTF-8")));
        assertEquals(path.layerCount, reach.layers);
        assertEquals(2, reach.layers);
        float[] first = reach.forPrinterLayer(1), second = reach.forPrinterLayer(2);
        assertEquals("the purge line counts with layer 1", Math.hypot(5, 5), first[0], 0.01);
        assertEquals(Math.hypot(140, 140), first[1], 0.01);
        assertEquals(Math.hypot(120, 120), second[1], 0.01);
        assertNull(reach.forPrinterLayer(3)); assertNull(reach.forPrinterLayer(0));
    }

    @Test public void withTheFileTheFarSideIsKnownFromTheFirstLayer() throws Exception {
        PrintRecorder recorder = new PrintRecorder(folder.newFolder("farfile"));
        recorder.setDefaultPictureLayers(1); recorder.setDefaultPictureFar(true);
        long t = 3_000_000;
        recorder.update(t, t, status(1, 100, 100), "local", "Bedroom");
        recorder.setReach(LayerReach.read(new java.io.ByteArrayInputStream(TWO_LAYERS.getBytes("UTF-8")), 0, 0));
        // Layer 1 reaches 7.1 to 198 mm from a camera at 0,0: the far 20 % starts at about 160 mm.
        assertFalse(recorder.headFrom(100, 100, 0, 0));
        assertTrue("no layer before needed: the file says where far is", recorder.headFrom(135, 135, 0, 0));
        assertTrue(recorder.pictureDue(t));
    }

    @Test public void bothTogether() throws Exception {
        // The farthest corner from the CC2's own camera spot (front left, above the bed) is the back right.
        assertEquals(0, SmoothTimelapse.farthestCorner(-10, -20));
        assertEquals(3, SmoothTimelapse.farthestCorner(266, 276));
        PrintRecorder recorder = new PrintRecorder(folder.newFolder("both"));
        long t = 4_000_000;
        recorder.update(t, t, status(1, 100, 100), "local", "Bedroom");
        recorder.setPark(250, 250);
        recorder.setReach(LayerReach.read(new java.io.ByteArrayInputStream(TWO_LAYERS.getBytes("UTF-8")), 0, 0));
        // Layer 1's park was missed (no status while parked): later in the layer the head goes far, and that takes the picture.
        assertFalse(recorder.headAt(60, 60)); assertFalse(recorder.headFrom(60, 60, 0, 0));
        assertTrue(recorder.headFrom(136, 136, 0, 0)); assertTrue(recorder.pictureDue(t));
        assertTrue(recorder.savePicture(new byte[200], t));
        recorder.update(t + 9000, t + 9000, status(2, 250, 250), "local", "Bedroom");
        assertTrue("next layer: parked, as planned", recorder.headAt(250, 250));
    }

    /** Comparing: each layer keeps a parked picture and a far one, in two sets; a far one waits while a parked one is on its way. */
    @Test public void compareKeepsBothSetsApart() throws Exception {
        PrintRecorder recorder = new PrintRecorder(folder.newFolder("compare"));
        long t = 5_000_000;
        recorder.update(t, t, status(1, 60, 60), "local", "Bedroom");
        recorder.setPark(250, 250, true);
        recorder.setReach(LayerReach.read(new java.io.ByteArrayInputStream(TWO_LAYERS.getBytes("UTF-8")), 0, 0));
        PrintRecorder.Recording now = recorder.current();
        assertTrue(now.compare);
        // Layer 1: the far side first, then the park; each lands in its own set.
        assertTrue(recorder.headFrom(136, 136, 0, 0));
        assertTrue(recorder.savePicture(new byte[200], t));
        assertTrue(recorder.headAt(250, 250));
        assertFalse("the parked head is not the far side of the layer", recorder.headFrom(250, 250, 0, 0));
        assertTrue(recorder.savePicture(new byte[200], t + 1000));
        assertEquals(1, PrintRecorder.pictures(now).size()); assertEquals(1, PrintRecorder.farPictures(now).size());
        // Layer 2: parked first; the far picture waits for the parked one to arrive.
        recorder.update(t + 9000, t + 9000, status(2, 250, 250), "local", "Bedroom");
        assertTrue(recorder.headAt(250, 250));
        assertFalse(recorder.headFrom(120, 120, 0, 0));
        assertTrue(recorder.savePicture(new byte[200], t + 9000));
        assertTrue(recorder.headFrom(120, 120, 0, 0));
        assertTrue(recorder.savePicture(new byte[200], t + 12000));
        assertEquals(2, PrintRecorder.pictures(now).size()); assertEquals(2, PrintRecorder.farPictures(now).size());
        assertEquals(2, now.pictures); assertEquals(2, now.farPictures);
        PrintRecorder.Recording read = PrintRecorder.Recording.read(now.meta);
        assertTrue(read.compare); assertEquals(2, read.farPictures);
        recorder.delete(now);
        assertFalse(now.pictureFolder().exists());
    }

    /** The park's G91 … G90 leaves relative extrusion relative (as Klipper does): every extrusion after it still counts. */
    @Test public void aParkKeepsRelativeExtrusion() throws Exception {
        String gcode = String.join("\n", "G90", "M83", ";LAYER_CHANGE", "G1 Z0.2", "G1 X10 Y10", "G1 X20 Y10 E1", SmoothTimelapse.gcode(0, 2000).replace("{layer_z}", "0.4"),
            ";LAYER_CHANGE", "G1 X10 Y20", "G1 X20 Y20 E0.5", "G1 X20 Y30 E0.5", "G1 X10 Y30 E0.5", "");
        GcodeToolpath path = GcodeToolpath.read(new java.io.ByteArrayInputStream(gcode.getBytes("UTF-8")));
        assertEquals(2, path.layerCount);
        assertEquals("all four extrusions drawn", 4, path.count);
        LayerReach reach = LayerReach.read(new java.io.ByteArrayInputStream(gcode.getBytes("UTF-8")), 0, 0);
        assertEquals(2.5, reach.extruded, 1e-6);
        assertArrayEquals(new float[] {10, 20, 20, 30}, reach.boxForPrinterLayer(2), 0.001f);
    }
}
