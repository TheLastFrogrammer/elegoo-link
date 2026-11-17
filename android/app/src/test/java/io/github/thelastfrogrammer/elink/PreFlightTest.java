package io.github.thelastfrogrammer.elink;

import static org.junit.Assert.*;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class PreFlightTest {
    static final long NOW = 1_800_000_000_000L, DAY = PreFlight.DAY;

    private static PrintRecorder.Recording print(long start, String outcome) {
        PrintRecorder.Recording r = new PrintRecorder.Recording(new File("p" + start + ".csv"), new File("p" + start + ".json"));
        r.start = start; r.outcome = outcome; r.file = "Benchy.gcode"; return r;
    }

    @Test public void lastPrintSaysHowItWent() {
        assertNull(PreFlight.lastPrint(null, NOW));
        PrintRecorder.Recording failed = print(NOW - 3 * DAY, "complete");
        failed.journal.result = "failed"; failed.journal.reasons.add("warping");
        PreFlight.Item item = PreFlight.lastPrint(failed, NOW);
        assertEquals(PreFlight.Item.WARN, item.level);
        assertTrue(item.words, item.words.contains("3 days ago") && item.words.contains("failed") && item.words.contains("warping"));
        PrintRecorder.Recording good = print(NOW - DAY, "complete"); good.journal.result = "good";
        assertEquals(PreFlight.Item.OK, PreFlight.lastPrint(good, NOW).level);
        assertTrue(PreFlight.lastPrint(good, NOW).words.contains("yesterday"));
        assertEquals(PreFlight.Item.WARN, PreFlight.lastPrint(print(NOW, "stopped"), NOW).level);
        assertEquals(PreFlight.Item.NOTE, PreFlight.lastPrint(print(NOW, "complete"), NOW).level);
    }

    @Test public void bedAgeAndFirstLayer() {
        assertEquals(PreFlight.Item.NOTE, PreFlight.bed(null, null, NOW, true).level);
        BedMesh.Mesh m = new BedMesh.Mesh(); m.at = NOW - 20 * DAY;
        PreFlight.Item old = PreFlight.bed(m, null, NOW, false);
        assertEquals(PreFlight.Item.NOTE, old.level); assertTrue(old.words, old.words.contains("a while ago"));
        assertEquals(PreFlight.Item.OK, PreFlight.bed(m, null, NOW, true).level);
        BedMesh.FirstLayer f = new BedMesh.FirstLayer(); f.min = -0.1; f.max = 0.05; f.layerHeight = 0.2;
        PreFlight.Item uneven = PreFlight.bed(m, f, NOW, true);
        assertEquals(PreFlight.Item.WARN, uneven.level); assertTrue(uneven.words, uneven.words.contains("75 %"));
        f.max = -0.05;
        assertEquals(PreFlight.Item.OK, PreFlight.bed(m, f, NOW, true).level);
    }

    @Test public void spoolsByDrying() {
        assertNull(PreFlight.spools(Collections.emptyList(), Collections.emptyList(), NOW));
        SpoolStore.Spool fresh = new SpoolStore.Spool(); fresh.type = "PLA"; fresh.dried = NOW - 2 * DAY;
        assertEquals(PreFlight.Item.OK, PreFlight.spools(Arrays.asList("Tray 1"), Arrays.asList(fresh), NOW).level);
        SpoolStore.Spool damp = new SpoolStore.Spool(); damp.type = "PETG"; damp.opened = NOW - 60 * DAY;
        SpoolStore.Spool unknown = new SpoolStore.Spool(); unknown.type = "PLA";
        PreFlight.Item item = PreFlight.spools(Arrays.asList("Tray 1", "Tray 2", "Tray 3"), Arrays.asList(fresh, damp, unknown), NOW);
        assertEquals(PreFlight.Item.WARN, item.level);
        assertTrue(item.words, item.words.contains("Tray 2") && item.words.contains("moisture") && item.words.contains("Tray 3") && !item.words.contains("Tray 1"));
        assertEquals(PreFlight.Item.NOTE, PreFlight.spools(Arrays.asList("Tray 3"), Arrays.asList(unknown), NOW).level);
    }

    @Test public void pendingTrialIsTheNewestUnjudgedAfterTheLastPrint() {
        List<Tuning.Trial> all = new ArrayList<>();
        Tuning.Trial before = new Tuning.Trial(); before.file = "Benchy.gcode"; before.at = NOW - 10 * DAY; all.add(before);
        Tuning.Trial after = new Tuning.Trial(); after.file = "Benchy.gcode"; after.at = NOW - DAY; after.changes.add(new Tuning.Change("temperature", "210", "215", "")); all.add(after);
        Tuning.Trial judged = new Tuning.Trial(); judged.file = "Benchy.gcode"; judged.at = NOW; judged.result = "helped"; all.add(judged);
        Tuning.Trial other = new Tuning.Trial(); other.file = "Cube.gcode"; other.at = NOW; all.add(other);
        assertSame(after, PreFlight.pendingTrial(all, "Benchy.gcode", print(NOW - 5 * DAY, "complete")));
        assertNull(PreFlight.pendingTrial(all, "Benchy.gcode", print(NOW - DAY / 2, "complete")));
        assertNotNull(PreFlight.tuning(after));
        assertNull(PreFlight.tuning(null));
    }
}
