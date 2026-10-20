package io.github.thelastfrogrammer.elink;

import static org.junit.Assert.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/** The measured checks, fed with status streams like the printer's. */
public class PrintChecksTest {
    /** Ten 0.2 mm layers of a 40 mm square at 100–140, with nothing outside it. */
    private static LayerReach square() throws Exception {
        StringBuilder g = new StringBuilder("G90\nM83\n");
        for (int i = 1; i <= 10; i++) g.append(";LAYER_CHANGE\nG1 Z").append(0.2 * i).append("\nG1 X100 Y100\nG1 X140 Y100 E1\nG1 X140 Y140 E1\nG1 X100 Y140 E1\n");
        return LayerReach.read(new java.io.ByteArrayInputStream(g.toString().getBytes("UTF-8")), 0, 0);
    }
    private static Map<String, PrintChecks.Finding> byId(List<PrintChecks.Finding> found) {
        Map<String, PrintChecks.Finding> map = new HashMap<>(); for (PrintChecks.Finding f : found) map.put(f.id, f); return map;
    }

    @Test public void aSmoothPrintThatBehavesPassesEverything() throws Exception {
        LayerReach reach = square(); assertArrayEquals(new float[] {100, 100, 140, 140}, reach.boxForPrinterLayer(3), 0.001f);
        PrintChecks checks = new PrintChecks(); long t = 0;
        for (int layer = 1; layer <= 10; layer++) {
            double z = 0.2 * layer;
            for (int s = 0; s < 6; s++) checks.status(t += 1000, layer, 100 + s * 8, 120, z, true, 250, 250, reach);   // printing
            checks.status(t += 1000, layer, 250, 250, z + 0.2 + 0.6, true, 250, 250, reach);                            // parked for the next layer
            checks.status(t += 1000, layer, 250, 250, z + 0.2 + 0.6, true, 250, 250, reach);
            checks.status(t += 1000, layer, 120, 120, z + 0.2, true, 250, 250, reach);                                  // back down
            checks.picture(0, 900);
        }
        Map<String, PrintChecks.Finding> found = byId(checks.findings(10, 0, false));
        for (String id : new String[] {"statusRate", "park", "parkReturn", "layers", "position", "pictures"})
            assertEquals(id + ": " + (found.get(id) == null ? "missing" : found.get(id).evidence), PrintChecks.Verdict.WORKS, found.get(id).verdict);
        // Kept with the recording and read back the same.
        PrintChecks again = PrintChecks.fromJson(checks.toJson());
        assertEquals(checks.findings(10, 0, false).size(), again.findings(10, 0, false).size());
        assertEquals(found.get("park").evidence, byId(again.findings(10, 0, false)).get("park").evidence);
    }

    @Test public void problemsAreNamed() throws Exception {
        LayerReach reach = square(); PrintChecks checks = new PrintChecks(); long t = 0;
        for (int layer = 1; layer <= 12; layer++)                                   // two layers more than the file, never parked,
            for (int s = 0; s < 5; s++) checks.status(t += 4000, layer, -100 - s, -100, 0.2 * layer, true, 250, 250, reach);   // slow status, mirrored positions
        for (int i = 0; i < 3; i++) checks.picture(1, 12_000);
        Map<String, PrintChecks.Finding> found = byId(checks.findings(0, 3, true));
        assertEquals(PrintChecks.Verdict.PROBLEM, found.get("statusRate").verdict);
        assertEquals(PrintChecks.Verdict.PROBLEM, found.get("park").verdict);
        assertEquals(PrintChecks.Verdict.PROBLEM, found.get("layers").verdict);
        assertEquals(PrintChecks.Verdict.PROBLEM, found.get("position").verdict);
        assertTrue(found.get("position").evidence.contains("another frame"));
        assertEquals(PrintChecks.Verdict.PROBLEM, found.get("pictures").verdict);
        assertNull("no parked returns seen: nothing to say", found.get("parkReturn"));
    }

    @Test public void nothingIsClaimedWithoutEvidence() {
        PrintChecks checks = new PrintChecks();
        checks.status(1000, 1, 120, 120, 0.2, false, Double.NaN, Double.NaN, null);   // the cloud, no file, not smooth
        assertTrue(checks.findings(0, 0, false).isEmpty());
    }
}
