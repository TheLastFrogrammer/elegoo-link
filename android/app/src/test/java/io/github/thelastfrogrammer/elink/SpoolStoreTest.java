package io.github.thelastfrogrammer.elink;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.File;
import java.nio.file.Files;
import java.util.*;

public class SpoolStoreTest {
    private static JSONObject canvas(String tray0Type, String tray0Colour) throws Exception {
        return new JSONObject("{\"canvas_list\":[{\"canvas_id\":0,\"tray_list\":[{\"tray_id\":0,\"filament_type\":\"" + tray0Type + "\",\"filament_color\":\"" + tray0Colour + "\"},"
            + "{\"tray_id\":1,\"filament_type\":\"PETG\",\"filament_color\":\"#1E5AA8\"}]}]}");
    }
    private static JSONObject printing(String file, int sub, int progress) throws Exception {
        return new JSONObject().put("machine_status", new JSONObject().put("status", 2).put("sub_status", sub).put("progress", progress)).put("print_status", new JSONObject().put("filename", file));
    }
    private static JSONObject idle() throws Exception { return new JSONObject().put("machine_status", new JSONObject().put("status", 1)); }

    @Test public void aFinishedPrintTakesItsGramsOffTheSpoolsItUsed() throws Exception {
        File file = new File(Files.createTempDirectory("spools").toFile(), "spools.json");
        SpoolStore store = new SpoolStore(file);
        List<TrayPlan.Tray> trays = TrayPlan.trays(canvas("PLA", "#D02828"));
        store.put(trays.get(0), 1000, 300, 1); store.put(trays.get(1), 1000, 1000, 1);
        assertNull(store.shortfall("0:0", 100));
        assertEquals("Only about 300 g left on this spool; this filament needs about 290 g.", store.shortfall("0:0", 290));
        Map<String, Double> uses = new HashMap<>(); uses.put("0:0", 120.0); uses.put("0:1", 40.0);
        store.started("box.gcode", uses, 10);
        assertNull("another print running before ours is not ours", store.observe(printing("other.gcode", 2075, 10), 20));
        store.started("box.gcode", uses, 30);
        store.observe(printing("box.gcode", 2075, 40), 40);
        assertEquals("About 160 g of filament taken off the spools.", store.observe(printing("box.gcode", 2077, 100), 50));
        SpoolStore reread = new SpoolStore(file);
        assertEquals(180, reread.inSlot("0:0").left, 0.01); assertEquals(960, reread.inSlot("0:1").left, 0.01);
        assertNull("settled once", reread.observe(printing("box.gcode", 2077, 100), 60));
    }

    @Test public void aStoppedPrintTakesOffThePrintedShare() throws Exception {
        SpoolStore store = new SpoolStore(new File(Files.createTempDirectory("spools").toFile(), "spools.json"));
        store.put(TrayPlan.trays(canvas("PLA", "#D02828")).get(0), 1000, 1000, 1);
        store.started("box.gcode", Collections.singletonMap("0:0", 200.0), 10);
        store.observe(printing("box.gcode", 2075, 25), 20);
        assertNotNull(store.observe(idle(), 30));
        assertEquals(950, store.inSlot("0:0").left, 0.01);
    }

    @Test public void aChangedTrayMovesItsSpoolToTheShelfAndItCanGoBack() throws Exception {
        SpoolStore store = new SpoolStore(new File(Files.createTempDirectory("spools").toFile(), "spools.json"));
        TrayPlan.Tray red = TrayPlan.trays(canvas("PLA", "#D02828")).get(0);
        store.put(red, 1000, 512, 1);
        JSONObject unitMissing = new JSONObject("{\"canvas_list\":[]}");
        assertFalse("a unit that does not answer keeps its spools", store.sync(unitMissing, 2));
        assertTrue(store.sync(canvas("PLA", "#FFFFFF"), 3));
        assertNull(store.inSlot("0:0")); assertEquals(1, store.shelf().size());
        assertTrue("a white spool is not a candidate for the red one", store.candidates(TrayPlan.trays(canvas("PLA", "#FFFFFF")).get(0)).isEmpty());
        assertEquals(1, store.candidates(red).size());
        store.fromShelf(store.shelf().get(0).id, red, 4);
        assertEquals(512, store.inSlot("0:0").left, 0.01); assertTrue(store.shelf().isEmpty());
    }

    @Test public void gramsComeFromTheSlicedFilesClosingNotesAndSurviveInThePlan() throws Exception {
        File gcode = File.createTempFile("sliced", ".gcode");
        StringBuilder text = new StringBuilder("G1 X0\n"); for (int i = 0; i < 2000; i++) text.append("G1 X").append(i).append(" E0.1\n");
        text.append("; filament used [mm] = 1000.00, 400.00\n; filament used [g] = 12.10, 3.45\n; total filament used [g] = 15.55\n");
        Files.write(gcode.toPath(), text.toString().getBytes("UTF-8"));
        assertArrayEquals(new double[] {12.10, 3.45}, SlicedMaterials.gramsFromTail(gcode), 1e-9);
        TrayPlan plan = TrayPlan.parse(new TrayPlan(2, new ArrayList<>()).withNeeds(Arrays.asList("PLA", "PETG"), null).withGrams(new double[] {12.10, 3.45}).toJson());
        assertEquals(12.10, plan.grams(0), 1e-9); assertEquals(3.45, plan.grams(1), 1e-9); assertEquals("PETG", plan.need(1).preset);
        File none = File.createTempFile("plain", ".gcode"); Files.write(none.toPath(), "G1 X0\n".getBytes("UTF-8"));
        assertEquals(0, SlicedMaterials.gramsFromTail(none).length);
    }
}
