package io.github.thelastfrogrammer.elink;

import static org.junit.Assert.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** A print's G-code kept with its recording: packed, read back, chosen over matched, and found again after the app's job link is gone. */
public class RecordingGcodeTest {
    private File dir;
    @Before public void folder() throws Exception { dir = Files.createTempDirectory("recording-gcode").toFile(); }
    @After public void clean() { delete(dir); }
    private static void delete(File f) { File[] all = f.listFiles(); if (all != null) for (File c : all) delete(c); f.delete(); }
    private File source(String text) throws Exception { File f = File.createTempFile("gcode", ".gcode", dir); Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8)); return f; }
    private PrintRecorder.Recording recording(String file) {
        File recordings = new File(dir, "recordings"); recordings.mkdirs();
        PrintRecorder.Recording r = new PrintRecorder.Recording(new File(recordings, "1-a.csv"), new File(recordings, "1-a.json"));
        r.file = file; return r;
    }

    @Test public void packedReadsBackExactlyAndChosenOutranksMatched() throws Exception {
        PrintRecorder.Recording r = recording("Part.gcode");
        assertNull(RecordingGcode.info(r)); assertNull(RecordingGcode.open(r, new File(dir, "cache")));
        StringBuilder text = new StringBuilder(); for (int i = 0; i < 2000; i++) text.append("G1 X").append(i % 200).append(" Y5 E0.1\n");
        File gcode = source(text.toString());
        RecordingGcode.Info matched = RecordingGcode.pack(gcode, "Part.gcode", r, RecordingGcode.MATCHED);
        assertEquals(RecordingGcode.MATCHED, matched.how); assertEquals(gcode.length(), matched.size);
        assertTrue("compressed", RecordingGcode.packed(r).length() < gcode.length() / 3);
        File plain = RecordingGcode.open(r, new File(dir, "cache"));
        assertArrayEquals(Files.readAllBytes(gcode.toPath()), Files.readAllBytes(plain.toPath()));
        // The same file chosen later: marked chosen, not packed again.
        long packedAt = RecordingGcode.packed(r).lastModified();
        assertEquals(RecordingGcode.CHOSEN, RecordingGcode.pack(gcode, "Part.gcode", r, RecordingGcode.CHOSEN).how);
        assertEquals(packedAt, RecordingGcode.packed(r).lastModified());
        // A matched copy never downgrades a choice.
        assertEquals(RecordingGcode.CHOSEN, RecordingGcode.pack(gcode, "Part.gcode", r, RecordingGcode.MATCHED).how);
        RecordingGcode.remove(r); assertNull(RecordingGcode.info(r));
    }

    @Test public void aDamagedPackIsNotUsed() throws Exception {
        PrintRecorder.Recording r = recording("Part.gcode");
        RecordingGcode.pack(source("G1 X1 E1\n"), "Part.gcode", r, RecordingGcode.ADDED);
        Files.write(RecordingGcode.packed(r).toPath(), new byte[] {1, 2, 3});
        assertNull(RecordingGcode.open(r, new File(dir, "cache")));
    }

    @Test public void theKeptCopyIsUsedWhenTheJobLinkIsGoneAndAFreshChoiceStillWins() throws Exception {
        GcodeLibrary library = new GcodeLibrary(new File(dir, "library"));
        File cache = new File(dir, "cache");
        PrintRecorder.Recording r = recording("Part.gcode");
        GcodeLibrary.Artifact older = library.retain(source("G1 X1"), "Part.gcode", "SN1", GcodeLibrary.Origin.UPLOADED);
        GcodeLibrary.Artifact newer = library.retain(source("G1 X2"), "Part.gcode", "SN1", GcodeLibrary.Origin.CHOSEN);
        RecordingGcode.pack(newer.file, newer.name, r, RecordingGcode.CHOSEN);
        // A new job token (the app restarted): two copies by that name, but the recording kept the chosen one.
        GuardianRun.Source found = GuardianRun.source(library, new GcodeJobTracker.Job("SN1", "Part.gcode"), r, cache);
        assertNull(found.problem); assertNotNull(found.kept); assertTrue(found.chosen);
        assertEquals("G1 X2", new String(Files.readAllBytes(found.file.toPath()), StandardCharsets.UTF_8));
        assertTrue(GuardianRun.fileSourceLine(found), GuardianRun.fileSourceLine(found).startsWith("File source: kept with this print's recording (the copy you chose)"));
        // A choice made in this stretch of watching comes first.
        GcodeJobTracker.Job job = new GcodeJobTracker.Job("SN1", "Part.gcode");
        library.selectForJob(older, "SN1", job.token);
        assertEquals(older.sha256, GuardianRun.source(library, job, r, cache).artifact.sha256);
        // Another file's print does not use this recording's copy.
        assertNull(GuardianRun.source(library, new GcodeJobTracker.Job("SN1", "Other.gcode"), r, cache).kept);
    }

    @Test public void aRecordingTakesTheOneCopyFromItsPrinterKeptBeforeItEnded() throws Exception {
        GcodeLibrary library = new GcodeLibrary(new File(dir, "library"));
        GcodeLibrary.Artifact mine = library.retain(source("G1 X1"), "Part.gcode", "SN1", GcodeLibrary.Origin.CHOSEN);
        library.retain(source("G1 X9"), "Part.gcode", "SN2", GcodeLibrary.Origin.UPLOADED);
        library.retain(source("G1 X5"), "Other.gcode", "", GcodeLibrary.Origin.SLICED);
        PrintRecorder.Recording r = recording("Part.gcode"); r.printerId = PrintRecorder.digest("SN1"); r.end = System.currentTimeMillis() + 60_000;
        assertEquals(2, library.copies("Part.gcode").size()); assertEquals(3, library.copies(null).size());
        assertEquals("the other printer's copy is left out", mine.sha256, RecordingsActivity.matchFor(library.copies("Part.gcode"), r).sha256);
        r.end = mine.at - 1;
        assertNull("kept only after the print ended: not this print's", RecordingsActivity.matchFor(library.copies("Part.gcode"), r));
        r.printerId = ""; r.end = 0;
        assertNull("printer unknown: two candidates, no guess", RecordingsActivity.matchFor(library.copies("Part.gcode"), r));
    }

    @Test public void aFileThatDoesNotFitThePrintIsNamed() {
        assertEquals("", RecordingGcode.mismatch("Part.gcode", 120, 120, "Part.gcode", 120));
        assertEquals("", RecordingGcode.mismatch("Part.gcode", 0, 40, "Part.gcode", 120));
        String both = RecordingGcode.mismatch("Part.gcode", 120, 60, "/x/Bracket.gcode", 80);
        assertTrue(both, both.contains("The print was named Part.gcode; this file is Bracket.gcode.") && both.contains("reported 120 layers for the print; this file has 80"));
        assertTrue(RecordingGcode.mismatch("Part.gcode", 0, 90, "Part.gcode", 80).contains("reached layer 90; this file has only 80"));
    }

    @Test public void picturesSitAtTheirTimeAndLayer() {
        double[] times = RecordingsActivity.pictureTimes(Arrays.asList(new File("000060.jpg"), new File("000300.jpg")));
        assertArrayEquals(new double[] {60, 300}, times, 0);
        double n = Double.NaN;
        double[] layers = RecordingsActivity.pictureLayers(new double[] {10, 60, 300}, new double[] {0, 50, 100, 290}, new double[] {n, 0, 3, 7});
        assertTrue("before the first layer", Double.isNaN(layers[0]));
        assertTrue(Double.isNaN(layers[1])); assertEquals(7, layers[2], 0);
        // The picture of a moment: the last taken at or before it, else the first after.
        double[] at = {0, 50, 100, Double.NaN};
        assertEquals(1, ChartView.pictureAtOrBefore(at, 99));
        assertEquals(2, ChartView.pictureAtOrBefore(at, 100));
        assertEquals(0, ChartView.pictureAtOrBefore(new double[] {10, 20}, 5));
        assertEquals(-1, ChartView.pictureAtOrBefore(new double[0], 5));
    }
}
