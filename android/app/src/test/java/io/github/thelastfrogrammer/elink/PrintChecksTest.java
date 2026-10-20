package io.github.thelastfrogrammer.elink;

import static org.junit.Assert.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/** The measured checks, fed with status streams like the printer's. */
public class PrintChecksTest {
    /** Faults: said only once the print ended; each code once, read back from the recording. */
    @Test public void faultsAreCollectedAndToldAtTheEnd() throws Exception {
        PrintChecks quiet = new PrintChecks(), loud = new PrintChecks();
        for (int i = 0; i < 40; i++) { quiet.faults(""); loud.faults(i == 5 || i == 6 ? "1203, 1501" : i == 20 ? "1203" : ""); }
        assertTrue("not before the end", byId(quiet.findings(0, 0, false)).get("faults") == null);
        quiet.ended(1000, true); loud.ended(1000, false);
        assertEquals(PrintChecks.Verdict.WORKS, byId(quiet.findings(0, 0, false)).get("faults").verdict);
        PrintChecks.Finding told = byId(PrintChecks.fromJson(loud.toJson()).findings(0, 0, false)).get("faults");
        assertEquals(PrintChecks.Verdict.PROBLEM, told.verdict);
        assertTrue(told.evidence, told.evidence.contains("codes 1203, 1501 while it ran (40 statuses)"));
    }

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

    /** Heat, filament, the slicer's estimate and the printer's time left, on a print that ends complete. */
    @Test public void heatFilamentAndTimes() throws Exception {
        PrintChecks checks = new PrintChecks(); checks.fileSeconds = 600; checks.fileExtruded = 1000;
        long t = 0;
        for (int i = 0; i < 700; i++) {
            t += 1000; int layer = i < 30 ? 0 : 1 + i / 30;
            checks.status(t, layer, Double.NaN, Double.NaN, Double.NaN, false, Double.NaN, Double.NaN, null);
            checks.heat(t, layer, 220 + (i % 3) - 1, 220, 60, 60);
            checks.progress(t, Math.min(99, i * 100 / 700), i == 350 ? 310 : 700 - i);
        }
        checks.ended(t, true);
        Map<String, PrintChecks.Finding> found = byId(checks.findings(0, 0, false, 1012));
        assertEquals(found.get("heat").evidence, PrintChecks.Verdict.WORKS, found.get("heat").verdict);
        assertEquals(found.get("filament").evidence, PrintChecks.Verdict.WORKS, found.get("filament").verdict);
        assertEquals(found.get("estimate").evidence, PrintChecks.Verdict.WORKS, found.get("estimate").verdict);   // 670 s against 600: +12 %
        assertEquals(found.get("eta").evidence, PrintChecks.Verdict.UNSURE, found.get("eta").verdict);            // 350 s left, said 310: +13 %
        assertTrue(found.get("estimate").evidence.contains("10 min 0 s"));
        // Not complete: nothing about times or filament.
        checks.ended(t, false);
        found = byId(checks.findings(0, 0, false, 1012));
        assertNull(found.get("estimate")); assertNull(found.get("filament")); assertNull(found.get("eta"));
        assertEquals(4 * 3600 + 5 * 60 + 7, LayerReach.seconds(" 4h 5m 7s")); assertEquals(1 * 86400 + 60, LayerReach.seconds("1d 0h 1m 0s"));
    }

    /** A heater that drifts off its target is a problem. */
    @Test public void aDriftingHeaterIsNamed() {
        PrintChecks checks = new PrintChecks(); long t = 0;
        for (int i = 0; i < 200; i++) { t += 1000; checks.heat(t, 1, i < 80 ? 220 : 210, 220, 60, 60); }
        Map<String, PrintChecks.Finding> found = byId(checks.findings(0, 0, false));
        assertEquals(PrintChecks.Verdict.PROBLEM, found.get("heat").verdict);
    }

    /** The self-test's position probe: exact, shifted, mirrored, or not seen. */
    private static PrintChecks.Finding probe(double dx, double dy, boolean mirrorX, boolean seen) {
        PrintChecks checks = new PrintChecks(); checks.selfTest = true; long t = 0;
        for (int layer = 1; layer <= 8; layer++) {
            for (int s = 0; s < 5; s++) checks.status(t += 1000, layer, 110 + s * 5, 120 + layer, 0.2 * layer, true, 250, 250, null);
            if (layer == SelfTest.PROBE_LAYER + 1 && seen)
                for (double[] p : SelfTest.PROBES) for (int r = 0; r < 3; r++)
                    checks.status(t += 1000, layer, (mirrorX ? 256 - p[0] : p[0]) + dx, p[1] + dy, 0.2 * layer + 0.6, true, 250, 250, null);
        }
        PrintChecks.Finding found = null;
        for (PrintChecks.Finding f : PrintChecks.fromJson(toJson(checks)).findings(0, 0, false)) if (f.id.equals("probe")) found = f;
        return found;
    }
    private static org.json.JSONObject toJson(PrintChecks c) { try { return c.toJson(); } catch (org.json.JSONException e) { throw new AssertionError(e); } }

    @Test public void theProbeTellsExactShiftedAndMirrored() {
        PrintChecks.Finding exact = probe(0, 0, false, true);
        assertEquals(exact.evidence, PrintChecks.Verdict.WORKS, exact.verdict);
        assertTrue(exact.evidence, exact.evidence.contains("X 30.0 Y 30.0") && exact.evidence.contains("3 and 3 readings"));
        PrintChecks.Finding shifted = probe(3, -2, false, true);
        assertEquals(PrintChecks.Verdict.PROBLEM, shifted.verdict); assertTrue(shifted.evidence, shifted.evidence.contains("X +3.0 mm, Y -2.0 mm"));
        PrintChecks.Finding mirrored = probe(0, 0, true, true);
        assertEquals(PrintChecks.Verdict.PROBLEM, mirrored.verdict); assertTrue(mirrored.evidence, mirrored.evidence.contains("left to right"));
        PrintChecks.Finding missed = probe(0, 0, false, false);
        assertEquals(PrintChecks.Verdict.UNSURE, missed.verdict);
        assertTrue(SelfTest.atProbe(30.4, 29.8)); assertFalse(SelfTest.atProbe(250, 250));
    }
}
