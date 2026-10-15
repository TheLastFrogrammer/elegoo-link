package io.github.thelastfrogrammer.elink;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;
import static org.junit.Assert.*;

public class GcodeRepairTest {
    private static File write(String text) throws Exception {
        File file = File.createTempFile("repair", ".gcode"); Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8)); return file;
    }

    @Test public void addsTheLoadBeforeTheTravelToThePurgeAtTheFilesTemperature() throws Exception {
        File in = write("; generated for another printer\nM104 S140\nM190 S60\nM109 S215 ; wait\nG28\nG1 Z5 F600\nG1 X128.5 Y-1.2 F20000\nG1 X20 E15 F1200\nG1 X30 Y30 E1\n");
        File out = File.createTempFile("repaired", ".gcode");
        GcodeRepair.Result result = GcodeRepair.addFilamentLoad(in, out, 2, 220);
        assertNotNull(result);
        assertEquals(7, result.line); assertEquals(215, result.temperature); assertEquals(2, result.tray);
        String text = new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8);
        int block = text.indexOf(GcodeRepair.BEGIN), travel = text.indexOf("G1 X128.5 Y-1.2"), purge = text.indexOf("G1 X20 E15");
        assertTrue(block > text.indexOf("M109 S215") && block < travel && travel < purge);
        assertTrue(text.contains("M109 S215\nM6211 A1 L200 T2 Q215 R215 S215\nT2\n"));
        assertEquals("the rest of the file is unchanged", text.replace(GcodeRepair.block(2, 215), ""), new String(Files.readAllBytes(in.toPath()), StandardCharsets.UTF_8));
        // The inspector now sees a file that loads filament.
        assertFalse(GcodeInspector.inspect(out).needsFilamentLoad());
        assertTrue(GcodeInspector.inspect(in).needsFilamentLoad());
    }

    @Test public void leavesFilesThatAlreadyLoadOrNeverExtrudeAlone() throws Exception {
        File out = File.createTempFile("repaired", ".gcode");
        assertNull(GcodeRepair.addFilamentLoad(write("M109 S220\nM6211 A1 L200 T0 Q220 R220 S220\nT0\nG1 X1 E2\n"), out, 0, 220));
        assertNull(GcodeRepair.addFilamentLoad(write("M109 S220\nT1\nG1 X1 E2\n"), out, 0, 220));
        assertNull(GcodeRepair.addFilamentLoad(write("G28\nG1 X10 Y10\n"), out, 0, 220));
    }

    @Test public void usesTheFallbackTemperatureAndTheFirstExtrusionWithoutATravel() throws Exception {
        File out = File.createTempFile("repaired", ".gcode");
        GcodeRepair.Result result = GcodeRepair.addFilamentLoad(write("G28\nG92 E0\nG1 E5 F300\n"), out, 0, 220);
        assertEquals(3, result.line); assertEquals(220, result.temperature);
    }
}
