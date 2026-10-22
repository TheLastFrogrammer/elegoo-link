package io.github.thelastfrogrammer.elink;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/** A recording's layers against its file, warm-up against earlier prints, and the print's cost. */
public class RecordingAnalysisTest {
    private static final double N = Double.NaN;

    @Test public void layerTimesRunFromEachLayersFirstReadingToTheNext() {
        double[] t = {0, 10, 40, 70, 100, 130}, layer = {N, 1, 1, 2, 3, 3};
        assertArrayEquals(new double[] {60, 30, N, N}, RecordingAnalysis.layerSeconds(t, layer, 4, false), 1e-9);
        assertEquals("ended: the last layer runs to the last reading", 30, RecordingAnalysis.layerSeconds(t, layer, 4, true)[2], 1e-9);
        // A layer number that goes back (a reprint of a layer report) does not reset the clock.
        assertArrayEquals(new double[] {60, 30}, RecordingAnalysis.layerSeconds(new double[] {0, 60, 70, 90}, new double[] {1, 2, 1, 3}, 2, false), 1e-9);
    }

    @Test public void fedFilamentPerLayerFromTheExtruderReadings() {
        double[] layer = {1, 1, 2, 3}, extruded = {0, 50, 120, 300};
        assertArrayEquals(new double[] {120, 180, N}, RecordingAnalysis.layerFed(layer, extruded, 3, false), 1e-9);
        assertEquals(0, RecordingAnalysis.layerFed(layer, extruded, 3, true)[2], 1e-9);
        String words = RecordingAnalysis.fedTotals(new double[] {100, 100, 102}, new double[] {100, 100, 100});
        assertTrue(words, words.contains("+0.7 %") && words.contains("not a measurement at the nozzle"));
        assertEquals("too few layers to say", "", RecordingAnalysis.fedTotals(new double[] {100}, new double[] {100}));
    }

    @Test public void slowLayersAreNamedAgainstTheFilesEstimate() {
        double[] measured = {60, 300, 65, 70, N}, estimate = {60, 60, 60, 60, 60};
        String slow = RecordingAnalysis.slowLayers(measured, estimate);
        assertTrue(slow, slow.startsWith("Layer 2 (5:00, file 1:00) took much longer"));
        assertEquals("", RecordingAnalysis.slowLayers(new double[] {100, 100}, new double[] {60, 60}));
        assertTrue(RecordingAnalysis.layerTimeTotals(measured, estimate).contains("Over the 4 layers with both: 8:15 printing against the file's 4:00"));
    }

    @Test public void theFileGivesEachLayerAnEstimateScaledToItsOwnTotal() throws Exception {
        String gcode = String.join("\n", "; estimated printing time (normal mode) = 1m 0s", "M83",
            ";LAYER_CHANGE", ";Z:0.2", ";HEIGHT:0.2", ";TYPE:Outer wall", "G1 X0 Y0 F6000", "G1 X100 Y0 E5 F6000",   // 1 s
            ";LAYER_CHANGE", ";Z:0.4", ";HEIGHT:0.2", "G1 Z0.4 F600", "G1 X100 Y100 E3 F3000", "G4 P1000", "");   // 0.04 (from Z 0: no move set 0.2) + 2 + 1 s
        LayerProfile p = LayerProfile.read(new java.io.ByteArrayInputStream(gcode.getBytes("UTF-8")));
        assertEquals(2, p.layers); assertEquals(60, p.estimatedSeconds);
        assertEquals(1, p.moveSeconds[0], 1e-9); assertEquals(3.04, p.moveSeconds[1], 1e-9);
        assertArrayEquals(new double[] {5, 3}, Arrays.copyOf(p.filament, 2), 1e-9);
        double[] e = p.estimatedLayerSeconds();
        assertEquals(60, e[0] + e[1], 1e-9); assertEquals(60 * 1 / 4.04, e[0], 1e-9);
    }

    @Test public void flaggedLayersShowWhatTheFileAsksForThere() throws Exception {
        String gcode = String.join("\n", "; filament_max_volumetric_speed = 10", "M83",
            ";LAYER_CHANGE", ";Z:0.2", ";HEIGHT:0.2", ";TYPE:Outer wall", "G1 X0 Y0 F6000", "G1 X10 Y0 E1 F1200",
            ";TYPE:Sparse infill", "G1 X40 Y0 E3 F6000", "");
        LayerProfile p = LayerProfile.read(new java.io.ByteArrayInputStream(gcode.getBytes("UTF-8")));
        List<double[]> answers = new ArrayList<>(); answers.add(new double[] {1, 0});
        List<String> lines = RecordingAnalysis.flaggedLayers(new LayerCharts.Guardian(new ArrayList<>(), answers), p);
        assertEquals(1, lines.size());
        assertTrue(lines.get(0), lines.get(0).startsWith("Layer 1 (you called it a real problem): sparse infill 75 %, outer wall 25 %;"));
        assertTrue(lines.get(0), lines.get(0).contains("(filament's limit 10)"));
        // An alarm alone counts too; a "looks fine" answer does not.
        List<double[]> fine = new ArrayList<>(); fine.add(new double[] {1, 1});
        assertTrue(RecordingAnalysis.flaggedLayers(new LayerCharts.Guardian(new ArrayList<>(), fine), p).isEmpty());
        assertTrue(RecordingAnalysis.flaggedLayers(new LayerCharts.Guardian(Arrays.asList(new double[] {1, 2, 0.1, 0.02, 1, 1}), new ArrayList<>()), p).get(0).contains("the guardian raised its alarm"));
    }

    @Test public void warmUpIsComparedWithEarlierPrints() {
        List<List<Object[]>> earlier = new ArrayList<>();
        for (double heat : new double[] {180, 200, 220}) earlier.add(Arrays.asList(new Object[] {0.0, heat, "heating the bed"}, new Object[] {heat, heat + 120, "levelling the bed"}));
        Map<String, Double> usual = RecordingAnalysis.usualWarmUp(earlier);
        assertEquals(200, usual.get("heating the bed"), 1e-9);
        Map<String, Integer> counts = new HashMap<>(); counts.put("heating the bed", 3); counts.put("levelling the bed", 3);
        List<Object[]> slow = Arrays.asList(new Object[] {0.0, 600.0, "heating the bed"}, new Object[] {600.0, 720.0, "levelling the bed"});
        assertEquals("Longer than usual: heating the bed took 10 min (usually 3 min)", RecordingAnalysis.warmUpAgainstUsual(slow, usual, counts).split("\\. ")[0]);
        List<Object[]> normal = new ArrayList<>(); normal.add(new Object[] {0.0, 210.0, "heating the bed"});
        assertEquals("The warm-up took about as long as usual.", RecordingAnalysis.warmUpAgainstUsual(normal, usual, counts));
        counts.put("heating the bed", 2);
        assertEquals("too few earlier prints", "", RecordingAnalysis.warmUpAgainstUsual(normal, usual, counts));
    }

    @Test public void costIsFilamentAndEnergyWhenPricesAreSet() {
        assertEquals("", RecordingAnalysis.cost(50, 0, 2, 0, 0));
        String filament = RecordingAnalysis.cost(50, 20, 2, 0, 0);
        assertTrue(filament, filament.contains("filament") && filament.contains("(50 g)") && filament.contains("for energy too"));
        String both = RecordingAnalysis.cost(50, 20, 2, 150, 0.30);
        assertTrue(both, both.contains("energy") && both.contains("0.30 kWh at 150 W") && !both.contains("for energy too"));
    }
}
