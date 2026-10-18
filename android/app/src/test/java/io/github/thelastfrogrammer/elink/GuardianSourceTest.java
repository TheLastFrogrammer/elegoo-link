package io.github.thelastfrogrammer.elink;

import static org.junit.Assert.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Which phone copy the guardian compares a print with: the chosen one, or the only one for that printer, never a guess between versions. */
public class GuardianSourceTest {
    private File dir;
    @Before public void folder() throws Exception { dir = Files.createTempDirectory("guardian-source").toFile(); }
    @After public void clean() { delete(dir); }
    private static void delete(File f) { File[] all = f.listFiles(); if (all != null) for (File c : all) delete(c); f.delete(); }
    private File source(String text) throws Exception { File f = File.createTempFile("gcode", ".gcode", dir); Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8)); return f; }

    @Test public void anUploadedPrintIsFoundForItsPrinter() throws Exception {
        GcodeLibrary library = new GcodeLibrary(new File(dir, "library"));
        GcodeLibrary.Artifact sent = library.retain(source("G1 X1"), "Benchy.gcode", "SN1", GcodeLibrary.Origin.UPLOADED);
        GcodeJobTracker.Job job = new GcodeJobTracker.Job("SN1", "/local/Benchy.gcode");
        GuardianRun.Source found = GuardianRun.source(library, job, "Benchy.gcode");
        assertNull(found.problem); assertEquals(sent.sha256, found.artifact.sha256); assertFalse("assumed, not chosen", found.chosen);
        // Another printer's job does not use it.
        assertNotNull(GuardianRun.source(library, new GcodeJobTracker.Job("SN2", "Benchy.gcode"), "Benchy.gcode").problem);
    }

    @Test public void twoVersionsWaitForAChoiceAndTheChoiceWins() throws Exception {
        GcodeLibrary library = new GcodeLibrary(new File(dir, "library"));
        library.retain(source("G1 X1"), "Part.gcode", "SN1", GcodeLibrary.Origin.UPLOADED);
        GcodeLibrary.Artifact newer = library.retain(source("G1 X2"), "Part.gcode", "SN1", GcodeLibrary.Origin.UPLOADED);
        GcodeJobTracker.Job job = new GcodeJobTracker.Job("SN1", "Part.gcode");
        GuardianRun.Source unsure = GuardianRun.source(library, job, "Part.gcode");
        assertNull(unsure.file); assertTrue(unsure.problem, unsure.problem.startsWith("2 different copies named Part.gcode"));
        library.selectForJob(newer, "SN1", job.token);
        GuardianRun.Source chosen = GuardianRun.source(library, job, "Part.gcode");
        assertTrue(chosen.chosen); assertEquals(newer.sha256, chosen.artifact.sha256);
        // A new print of the same name is a new job: the earlier choice does not carry over.
        assertNotNull(GuardianRun.source(library, new GcodeJobTracker.Job("SN1", "Part.gcode"), "Part.gcode").problem);
    }

    @Test public void withoutAnObservedJobOnlyAnUnscopedCopyCounts() throws Exception {
        GcodeLibrary library = new GcodeLibrary(new File(dir, "library"));
        assertTrue(GuardianRun.source(library, null, "Cube.gcode").problem.startsWith("No copy"));
        library.retain(source("G1 X3"), "Cube.gcode", "", GcodeLibrary.Origin.SLICED);
        assertNull(GuardianRun.source(library, null, "Cube.gcode").problem);
    }
}
