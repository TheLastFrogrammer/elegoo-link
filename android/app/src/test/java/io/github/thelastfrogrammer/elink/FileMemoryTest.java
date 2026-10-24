package io.github.thelastfrogrammer.elink;

import static org.junit.Assert.*;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Every print of a file together: the best one, what changed between prints, spools and drying. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class FileMemoryTest {
    private static final long DAY = 86_400_000L;
    private File dir;

    private PrintRecorder.Recording print(String file, long start, String outcome, String result, int stars, String... reasons) throws Exception {
        if (dir == null) dir = Files.createTempDirectory("file-memory").toFile();
        PrintRecorder.Recording r = new PrintRecorder.Recording(new File(dir, start + ".csv"), new File(dir, start + ".json"));
        r.file = file; r.start = start; r.outcome = outcome; r.journal.result = result; r.journal.stars = stars; r.journal.reasons.addAll(Arrays.asList(reasons));
        return r;
    }
    private static Map<String, String> config(String fan, String nozzle) { Map<String, String> c = new HashMap<>(); c.put("fan_max_speed", fan); c.put("nozzle_temperature", nozzle); c.put("top_shell_layers", "5"); return c; }

    @Test public void theBestRatedFinishedPrintIsWhatWorked() throws Exception {
        FileMemory.Print failed = new FileMemory.Print(print("mount.gcode", 1 * DAY, "complete", "failed", 1, "surface"), config("100", "210"), new ArrayList<>());
        FileMemory.Print good = new FileMemory.Print(print("mount.gcode", 2 * DAY, "complete", "good", 5), config("70", "215"), new ArrayList<>());
        FileMemory.Print stopped = new FileMemory.Print(print("mount.gcode", 3 * DAY, "stopped", "good", 5), config("60", "220"), new ArrayList<>());
        FileMemory.Print newest = new FileMemory.Print(print("mount.gcode", 4 * DAY, "complete", "usable", 3), config("100", "210"),
            Collections.singletonList(new RecordingAnalysis.Finding(RecordingAnalysis.Finding.COOLING, 68, "")));
        List<FileMemory.Print> all = Arrays.asList(newest, stopped, good, failed);
        assertSame(good, FileMemory.best(all));
        assertNull("unrated prints say nothing about what worked", FileMemory.best(Collections.singletonList(
            new FileMemory.Print(print("mount.gcode", 5 * DAY, "complete", "", 0), config("100", "210"), new ArrayList<>()))));
        List<Tuning.Change> back = FileMemory.changes(newest.config, good.config, "best");
        assertEquals("Part fan on short layers 100 → 70 %, Nozzle temperature 210 → 215 °C", FileMemory.words(back));
        assertTrue("numbers compare as numbers", FileMemory.changes(config("70", "215"), config("70.0", "215"), "").isEmpty());
    }

    @Test public void recordingsOfTheFileNewestFirst() throws Exception {
        List<PrintRecorder.Recording> all = Arrays.asList(print("mount.gcode", 1, "complete", "", 0), print("other.gcode", 2, "complete", "", 0), print("mount.gcode", 3, "complete", "", 0));
        List<PrintRecorder.Recording> mine = FileMemory.of(all, "mount.gcode");
        assertEquals(2, mine.size()); assertEquals(3, mine.get(0).start);
    }

    @Test public void theTraysUsedNameTheSpoolsAndTheirDrying() throws Exception {
        assertEquals(Arrays.asList("0:0", "0:2", "1:0"), FileMemory.slots(new double[] {Double.NaN, 1, 1, 3, 5, 1}));
        SpoolStore store = SpoolStore.at(new File(Files.createTempDirectory("spools").toFile(), "spools.json"));
        long now = 400 * DAY;
        SpoolStore.Spool black = store.put(new TrayPlan.Tray(0, 0, "PLA", "PLA", "Elegoo", "#000000"), 1000, 1000, now - 45 * DAY);
        store.put(new TrayPlan.Tray(0, 2, "PETG", "PETG", "Elegoo", "#FFFFFF"), 1000, 600, now);   // part used: opened when, not known
        JSONArray note = FileMemory.spoolNote(Arrays.asList("0:0", "0:2", "0:3"), store);
        assertEquals(2, note.length());
        PrintRecorder.Recording damp = print("mount.gcode", now, "complete", "usable", 3, "surface", "layer_shift"); damp.spools = note;
        String words = FileMemory.moisture(damp);
        assertTrue(words, words.startsWith("Damp filament often causes rough surface or artifacts."));
        assertTrue(words, words.contains("opened 45 days before this print, not marked dried"));
        assertTrue(words, words.contains("drying not noted"));
        assertTrue(words, words.endsWith("Drying the spool before the next print is worth a try."));
        store.setDried(black.id, now - 2 * DAY, now);
        PrintRecorder.Recording dried = print("mount.gcode", now, "complete", "usable", 3, "stringing");
        dried.spools = FileMemory.spoolNote(Collections.singletonList("0:0"), store);
        assertTrue(FileMemory.moisture(dried), FileMemory.moisture(dried).contains("dried 2 days before this print") && FileMemory.moisture(dried).endsWith("look at the settings first."));
        assertEquals("", FileMemory.moisture(print("mount.gcode", now, "complete", "failed", 1, "layer_shift")));
        assertEquals("dried today", SpoolStore.Spool.dryWords(now, 0, now + 3600_000, "ago"));
    }

    @Test public void spoolsNotedOnARecordingAreKept() throws Exception {
        dir = Files.createTempDirectory("file-memory-meta").toFile();
        PrintRecorder.Recording r = print("mount.gcode", 1000, "complete", "", 0);
        Files.write(r.meta.toPath(), r.toJson().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        PrintRecorder.saveSpools(r.meta, new JSONArray().put(new org.json.JSONObject().put("slot", "0:1").put("material", "PLA · black").put("dried", 5).put("opened", 0)));
        PrintRecorder.Recording back = PrintRecorder.Recording.read(r.meta);
        assertEquals("PLA · black (dried the same day)", FileMemory.spoolWords(back));
        assertEquals(1, back.spools.length());
    }
}
