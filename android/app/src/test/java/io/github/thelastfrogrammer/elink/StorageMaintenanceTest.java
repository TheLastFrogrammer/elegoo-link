package io.github.thelastfrogrammer.elink;

import static org.junit.Assert.*;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** What recordings take and the clean-up's order; maintenance counted from recorded prints. */
public class StorageMaintenanceTest {
    private File dir;
    @Before public void folder() throws Exception { dir = Files.createTempDirectory("storage").toFile(); }
    @After public void clean() { delete(dir); }
    private static void delete(File f) { File[] all = f.listFiles(); if (all != null) for (File c : all) delete(c); f.delete(); }

    private PrintRecorder.Recording recording(String name, int pictures, int pictureBytes, int videoBytes, long start) throws Exception {
        PrintRecorder.Recording r = new PrintRecorder.Recording(new File(dir, name + ".csv"), new File(dir, name + ".json"));
        Files.write(r.csv.toPath(), new byte[100]); Files.write(r.meta.toPath(), new byte[50]);
        r.pictureFolder().mkdirs();
        for (int i = 0; i < pictures; i++) Files.write(new File(r.pictureFolder(), String.format(Locale.ROOT, "%06d.jpg", i * 60)).toPath(), new byte[pictureBytes]);
        if (videoBytes > 0) Files.write(r.video().toPath(), new byte[videoBytes]);
        r.start = start; r.file = name + ".gcode";
        return r;
    }

    @Test public void usageAndDroppingKeepTheLastPictureAndTheReadings() throws Exception {
        PrintRecorder.Recording r = recording("a", 5, 1000, 4000, 0);
        r.farFolder().mkdirs(); Files.write(new File(r.farFolder(), "000010.jpg").toPath(), new byte[700]);
        File gcode = new File(dir, "part.gcode"); StringBuilder text = new StringBuilder(); for (int i = 0; i < 500; i++) text.append("G1 X1 Y2 E0.1\n"); Files.write(gcode.toPath(), text.toString().getBytes("UTF-8"));
        RecordingGcode.pack(gcode, "part.gcode", r, RecordingGcode.ADDED);
        RecordingStorage.Usage u = RecordingStorage.of(r);
        assertEquals(5700, u.pictures); assertEquals(1000, u.lastPicture); assertEquals(4000, u.videos); assertEquals(150, u.readings); assertTrue(u.gcode > 0);
        assertEquals(4700, RecordingStorage.drop(r, RecordingStorage.Part.PICTURES));
        assertEquals("the last picture stays", 1, PrintRecorder.pictures(r).size()); assertFalse(r.farFolder().exists());
        assertEquals(4000, RecordingStorage.drop(r, RecordingStorage.Part.VIDEOS));
        assertTrue(RecordingStorage.drop(r, RecordingStorage.Part.GCODE) > 0); assertNull(RecordingGcode.info(r));
        assertTrue("readings stay", r.csv.isFile() && r.meta.isFile());
    }

    @Test public void theCleanUpTakesOldPicturesFirstAndLeavesProtectedPrintsAlone() throws Exception {
        List<PrintRecorder.Recording> newestFirst = new ArrayList<>(); Map<String, RecordingStorage.Usage> usage = new HashMap<>();
        for (int i = 0; i < 5; i++) { PrintRecorder.Recording r = recording("r" + i, 3, 10_000, 50_000, 1000 - i); newestFirst.add(r); usage.put(r.meta.getName(), RecordingStorage.of(r)); }
        // Pictures only for the newest two prints: the other three lose theirs (all but the last).
        List<RecordingStorage.Step> keep = RecordingStorage.plan(newestFirst, usage, 0, 2, new HashSet<>());
        assertEquals(3, keep.size()); for (RecordingStorage.Step s : keep) assertEquals(RecordingStorage.Part.PICTURES, s.part);
        assertEquals("pictures of 3 prints", RecordingStorage.describe(keep));
        // A limit: the oldest prints' pictures first, then videos, until under it; the newest (protected) never.
        long total = 0; for (RecordingStorage.Usage u : usage.values()) total += u.total();
        List<RecordingStorage.Step> limit = RecordingStorage.plan(newestFirst, usage, total - 100_000, 0, new HashSet<>(Arrays.asList("r0.json")));
        assertEquals(RecordingStorage.Part.PICTURES, limit.get(0).part); assertEquals("r4.json", limit.get(0).recording.meta.getName());
        boolean videos = false; for (RecordingStorage.Step s : limit) { assertNotEquals("r0.json", s.recording.meta.getName()); videos |= s.part == RecordingStorage.Part.VIDEOS; }
        assertTrue("pictures were not enough, so videos too", videos);
        assertTrue(RecordingStorage.bytes(limit) >= 100_000);
        assertTrue("off: nothing", RecordingStorage.plan(newestFirst, usage, 0, 0, new HashSet<>()).isEmpty());
    }

    @Test public void maintenanceCountsHoursFilamentAndDaysSinceDone() throws Exception {
        long day = 86_400_000L, now = 100 * day;
        PrintRecorder.Recording before = recording("old", 0, 0, 0, 10 * day); before.elapsed = 20 * 3_600_000L; before.extrudedMm = 400_000;
        PrintRecorder.Recording after = recording("new", 0, 0, 0, 60 * day); after.elapsed = 30 * 3_600_000L; after.extrudedMm = 336_000;   // ≈ 1 kg of PLA
        List<PrintRecorder.Recording> prints = Arrays.asList(after, before);
        Maintenance.Task nozzle = new Maintenance.Task("nozzle", "Clean the nozzle", Maintenance.HOURS, 50, 50 * day);
        assertEquals("only prints since it was done", 30, Maintenance.used(nozzle, prints, now), 1e-9);
        assertFalse(Maintenance.due(nozzle, 30)); assertFalse(Maintenance.soon(nozzle, 30)); assertTrue(Maintenance.soon(nozzle, 46)); assertTrue(Maintenance.due(nozzle, 50));
        Maintenance.Task gears = new Maintenance.Task("gears", "Check the gears", Maintenance.KG, 5, 50 * day);
        assertEquals(PrintRecorder.grams(336_000, "PLA") / 1000, Maintenance.used(gears, prints, now), 1e-9);
        Maintenance.Task frame = new Maintenance.Task("frame", "Check the frame", Maintenance.DAYS, 90, 20 * day);
        assertEquals(80, Maintenance.used(frame, prints, now), 1e-9);
        assertTrue(Maintenance.progress(nozzle, 30, Locale.US).startsWith("30 of 50 h since "));
        // Due tasks are reminded of once per period; marking one done starts a new period.
        Maintenance.Task plate = new Maintenance.Task("plate", "Wash the plate", Maintenance.HOURS, 25, 50 * day);
        List<Maintenance.Task> tasks = new ArrayList<>(Arrays.asList(nozzle, plate, frame));
        assertEquals(1, Maintenance.newlyDue(tasks, prints, now).size());
        assertTrue("not again", Maintenance.newlyDue(tasks, prints, now).isEmpty());
        assertEquals("1 due", Maintenance.summary(tasks, prints, now));
        plate.doneAt = 70 * day; assertEquals("All up to date", Maintenance.summary(tasks, prints, now));
        // Nothing is due on the day the starting list begins.
        for (Maintenance.Task t : Maintenance.defaults(now)) assertFalse(t.name, Maintenance.due(t, Maintenance.used(t, prints, now)));
    }
}
