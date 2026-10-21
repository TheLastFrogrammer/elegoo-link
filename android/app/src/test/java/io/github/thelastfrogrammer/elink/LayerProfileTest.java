package io.github.thelastfrogrammer.elink;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;

/** Each layer as the G-code asks for it: cross section by line type, speed, flow and acceleration. */
public class LayerProfileTest {
    // Filament 1.75 mm: 1 mm fed is π·0.875² ≈ 2.405 mm³.
    private static final double MM3 = Math.PI * 1.75 * 1.75 / 4;
    private static final String GCODE = String.join("\n",
        "; filament_diameter = 1.75", "; filament_max_volumetric_speed = 12", "; layer_height = 0.2",
        "G90", "M83",
        ";LAYER_CHANGE", ";Z:0.2", ";HEIGHT:0.2", "SET_VELOCITY_LIMIT ACCEL=1000",
        ";TYPE:Outer wall", "G1 X10 Y10 F6000", "G1 X20 Y10 E1 F1200",          // 10 mm at 20 mm/s, 1 mm fed
        ";TYPE:Sparse infill", "M204 S5000", "G1 X20 Y30 E1 F6000",            // 20 mm at 100 mm/s, 1 mm fed
        ";LAYER_CHANGE", ";Z:0.4", ";HEIGHT:0.2", "G1 Z0.4",
        ";TYPE:Inner wall", "G91", "G1 Z0.6", "G90", "G1 X30 Y30 E2 F600",      // the park's G91…G90 keeps relative E: 10 mm at 10 mm/s
        "");

    @Test public void eachLayerByLineTypeSpeedFlowAndAcceleration() throws Exception {
        LayerProfile p = LayerProfile.read(new java.io.ByteArrayInputStream(GCODE.getBytes("UTF-8")));
        assertEquals(2, p.layers);
        // Cross section: the material of each type over the layer's height.
        int outer = 0, sparse = 3, inner = 1;
        assertEquals(MM3 / 0.2, p.area[0][outer], 1e-6);
        assertEquals(MM3 / 0.2, p.area[0][sparse], 1e-6);
        assertEquals(2 * MM3 / 0.2, p.area[1][inner], 1e-6);
        assertEquals(2 * MM3 / 0.2, p.total(0), 1e-6);
        assertArrayEquals(new int[] {outer, inner, sparse}, p.typesUsed());
        // Speed weighted by length: (20·10 + 100·20) / 30.
        assertEquals((20.0 * 10 + 100.0 * 20) / 30, p.speed[0], 1e-6);
        // Flow: the infill melts 1 mm of filament in 0.2 s.
        assertEquals(MM3 / 0.2, p.flowMax[0], 1e-6);
        assertEquals(12, p.maxFlow, 1e-9);
        // Acceleration: Klipper's and M204's, weighted by length.
        assertEquals((1000.0 * 10 + 5000.0 * 20) / 30, p.accel[0], 1e-6);
        assertEquals(5000, p.accelMax[0], 1e-9);
        assertEquals(5000, p.accel[1], 1e-9);
        assertEquals(10, p.speed[1], 1e-9);
    }

    @Test public void theFilamentSensorAndTheExtruderAreRead() throws Exception {
        // The CC2 reports the extruder position as gcode_move.extruder; Klipper-style "e" is read too.
        assertEquals(12.5, Cc2Codec.extruderPosition(new JSONObject().put("x", 1).put("extruder", 12.5)), 1e-9);
        assertEquals(3, Cc2Codec.extruderPosition(new JSONObject().put("e", 3)), 1e-9);
        assertTrue(Double.isNaN(Cc2Codec.extruderPosition(new JSONObject().put("x", 1))));
        assertTrue(Double.isNaN(Cc2Codec.extruderPosition(null)));
        // Runouts: each time the sensor goes from filament to none.
        List<double[]> rows = new ArrayList<>(); int sensor = PrintRecorder.column("filament_detected"), at = PrintRecorder.column("elapsed_s");
        double[] seen = {1, 1, 0, 0, 1, 0};
        for (int i = 0; i < seen.length; i++) { double[] row = new double[PrintRecorder.COLUMNS.length]; java.util.Arrays.fill(row, Double.NaN); row[sensor] = seen[i]; row[at] = i * 600; rows.add(row); }
        assertEquals("The filament sensor reported no filament at 0:20, 0:50 into the print.", RecordingsActivity.runouts(rows, RecordingsActivity.column(rows, "elapsed_s")));
        rows.clear(); assertEquals("", RecordingsActivity.runouts(rows, new double[0]));
    }
}
