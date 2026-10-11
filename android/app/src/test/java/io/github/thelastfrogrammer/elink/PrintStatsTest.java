package io.github.thelastfrogrammer.elink;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.File;
import java.nio.file.Files;
import java.util.*;

public class PrintStatsTest {
    private static PrintRecorder.Recording rec(String outcome, long start, long hours) {
        PrintRecorder.Recording r = new PrintRecorder.Recording(new File("x.csv"), new File("x.json"));
        r.outcome = outcome; r.start = start; r.elapsed = hours * 3600_000L; return r;
    }
    @Test public void countsPrintsTimeSuccessAndFilament() {
        long now = 100 * PrintStats.DAY_MS;
        List<PrintRecorder.Recording> recs = Arrays.asList(rec("complete", now - PrintStats.DAY_MS, 2), rec("complete", now - 40 * PrintStats.DAY_MS, 3),
            rec("stopped", now - 2 * PrintStats.DAY_MS, 1), rec("printing", now, 5));
        List<SpoolStore.Usage> used = Arrays.asList(new SpoolStore.Usage(now - PrintStats.DAY_MS, "a.gcode", "PLA", 120), new SpoolStore.Usage(now - 50 * PrintStats.DAY_MS, "b.gcode", "petg", 80));
        PrintStats s = PrintStats.of(recs, used, now);
        assertEquals("the running print is not counted", 3, s.prints); assertEquals(67, s.successPercent());
        assertEquals(6, s.hours, 1e-9); assertEquals(2, s.prints30); assertEquals(3, s.hours30, 1e-9);
        assertEquals(200, s.grams, 1e-9); assertEquals(120, s.grams30, 1e-9); assertEquals(80, s.gramsByType.get("PETG"), 1e-9);
        List<String> lines = s.lines(0);
        assertEquals("3 prints · " + PrintStats.hours(6) + " printing · 67% completed", lines.get(0));
        assertTrue(lines.get(3), lines.get(3).startsWith("Filament from tracked spools: 200 g (PETG 80 g, PLA 120 g)"));
        assertFalse("no price, no cost", lines.get(3).contains("about"));
        assertTrue(s.lines(25).get(3).contains("about"));
        assertEquals("", PrintStats.cost(100, 0)); assertEquals("45 min", PrintStats.hours(0.75));
        assertEquals(1, PrintStats.of(new ArrayList<>(), new ArrayList<>(), now).lines(0).size());
    }

    @Test public void finishedPrintsLeaveAUsageLog() throws Exception {
        SpoolStore store = new SpoolStore(new File(Files.createTempDirectory("spools").toFile(), "spools.json"));
        TrayPlan.Tray tray = TrayPlan.trays(new org.json.JSONObject("{\"canvas_list\":[{\"canvas_id\":0,\"tray_list\":[{\"tray_id\":0,\"filament_type\":\"PLA\",\"filament_color\":\"#D02828\"}]}]}")).get(0);
        store.put(tray, 1000, 1000, 1);
        store.started("a.gcode", Collections.singletonMap("0:0", 50.0), 2);
        store.observe(new org.json.JSONObject().put("machine_status", new org.json.JSONObject().put("status", 2).put("sub_status", 2077).put("progress", 100)).put("print_status", new org.json.JSONObject().put("filename", "a.gcode")), 3);
        List<SpoolStore.Usage> used = store.usage(); assertEquals(1, used.size()); assertEquals("PLA", used.get(0).type); assertEquals(50, used.get(0).grams, 1e-9);
    }
}
