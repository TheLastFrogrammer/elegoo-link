package io.github.thelastfrogrammer.elink;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** What Live toolpath's Details show: the G-code line, the slicer's time there and the filament, all from the file. */
public class GcodeDetailsTest {
    private static final String GCODE = "M73 P0 R20\n;LAYER_CHANGE\n;Z:0.2\nG1 X0 Y0 Z0.2 F600\nT0\nG1 X10 Y0 E1\nM73 P50 R10\nT1\nG1 X20 Y0 E2\n"
        + "; filament_type = PLA;PETG\n; filament_colour = #000000;#00FF80\n; filament_settings_id = \"Elegoo PLA @ECC2\";\"Elegoo PETG @ECC2\"\n";

    @Test public void eachSegmentKnowsItsLineTimeAndFilament() throws Exception {
        File file = File.createTempFile("details", ".gcode"); Files.write(file.toPath(), GCODE.getBytes(StandardCharsets.UTF_8));
        GcodeToolpath path = GcodeToolpath.read(file);
        assertEquals(2, path.count);
        assertEquals(6, path.lineOf(0)); assertEquals(9, path.lineOf(1));
        assertArrayEquals(new float[] {0, 20}, path.timeAt(0), 1e-6f); assertArrayEquals(new float[] {10, 20}, path.timeAt(1), 1e-6f);
        assertEquals("Filament 1 · PLA · black · Elegoo PLA @ECC2", path.filamentText(path.toolOf(0)));
        assertEquals("Filament 2 · PETG · green · Elegoo PETG @ECC2", path.filamentText(path.toolOf(1)));
        try (GcodeToolpath.LineCursor lines = new GcodeToolpath.LineCursor(file)) {
            assertEquals("G1 X10 Y0 E1", lines.line(6)); assertEquals("G1 X20 Y0 E2", lines.line(9));
            assertEquals("going back starts over", "M73 P0 R20", lines.line(1)); assertEquals("", lines.line(99));
        }
        GcodeToolpath plain = GcodeToolpath.read(new java.io.ByteArrayInputStream("G1 X0 Y0 Z0.2\nG1 X5 Y0 E1\n".getBytes(StandardCharsets.UTF_8)));
        assertNull("no M73 marks: no time", plain.timeAt(0)); assertEquals("Filament 1", plain.filamentText(0));
    }
}
