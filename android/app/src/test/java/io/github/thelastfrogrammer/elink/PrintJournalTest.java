package io.github.thelastfrogrammer.elink;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class PrintJournalTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    private static JSONObject status(int sub, String file, int layer, int progress) throws Exception {
        return new JSONObject().put("machine_status", new JSONObject().put("status", 2).put("sub_status", sub).put("progress", progress))
            .put("print_status", new JSONObject().put("filename", file).put("uuid", "u-" + file).put("current_layer", layer).put("total_layer", 10));
    }

    @Test public void tagsAreTidiedAndCapped() {
        assertEquals(List.of("petg", "functional", "gift box"), List.copyOf(PrintJournal.tags(" PETG, #functional ;Gift   Box,, petg")));
        StringBuilder many = new StringBuilder(); for (int i = 0; i < 20; i++) many.append("t").append(i).append(',');
        assertEquals(12, PrintJournal.tags(many.toString()).size());
        assertEquals(24, PrintJournal.tags("x".repeat(40)).iterator().next().length());
    }

    @Test public void summaryAndSearchUseEverythingNoted() throws Exception {
        PrintJournal j = new PrintJournal(); j.stars = 2; j.result = "failed"; j.reasons.add("warping"); j.tags.addAll(PrintJournal.tags("abs, big part")); j.notes = "Try a brim next time";
        assertEquals("★★☆☆☆ · Failed: Warping or lifted corners · #abs #big-part", j.summary());
        assertTrue(PrintJournal.matches("Bracket.gcode", j, "bracket warping"));
        assertTrue(PrintJournal.matches("Bracket.gcode", j, "#abs brim"));
        assertTrue(PrintJournal.matches("Bracket.gcode", j, "FAILED"));
        assertFalse(PrintJournal.matches("Bracket.gcode", j, "spaghetti"));
        assertTrue(PrintJournal.matches("x.gcode", new PrintJournal(), "unrated"));
        PrintJournal back = PrintJournal.fromJson(j.toJson());
        assertEquals(j.summary(), back.summary()); assertEquals(j.notes, back.notes);
        PrintJournal odd = PrintJournal.fromJson(new JSONObject().put("stars", 9).put("result", "exploded").put("reasons", new JSONArray().put("warping").put("aliens")));
        assertEquals(5, odd.stars); assertEquals("", odd.result); assertEquals(1, odd.reasons.size());
    }

    @Test public void problemsAreCountedMostCommonFirst() {
        PrintJournal a = new PrintJournal(), b = new PrintJournal(), c = new PrintJournal();
        a.reasons.add("warping"); b.reasons.add("warping"); b.reasons.add("stringing"); c.reasons.add("stringing"); c.reasons.add("warping");
        Map<String, Integer> counts = PrintJournal.problemCounts(List.of(a, b, c, new PrintJournal()));
        assertEquals(List.of("warping", "stringing"), List.copyOf(counts.keySet())); assertEquals(3, (int) counts.get("warping"));
    }

    @Test public void theJournalIsSavedForFinishedAndRunningPrints() throws Exception {
        File dir = folder.newFolder();
        PrintRecorder recorder = new PrintRecorder(dir); PrintRecorder.active = recorder;
        try {
            recorder.update(0, status(2075, "one.gcode", 1, 1), "local", "CC2");
            recorder.update(20_000, status(2077, "one.gcode", 10, 100), "local", "CC2");
            File finished = recorder.lastFinished();
            assertEquals(recorder.list().get(0).meta, finished);
            PrintJournal j = new PrintJournal(); j.stars = 4; j.result = "good";
            PrintRecorder.saveJournal(finished, j);
            assertEquals(4, PrintRecorder.Recording.read(finished).journal.stars);
            // While a print records, the journal goes to the recorder's copy, so the next sample keeps it.
            recorder.update(40_000, status(2075, "two.gcode", 1, 1), "local", "CC2");
            File running = recorder.current().meta;
            PrintJournal note = new PrintJournal(); note.notes = "watching the first layer";
            PrintRecorder.saveJournal(running, note);
            recorder.noteGuardian(true, "filament off the print", 50_000);
            recorder.update(70_000, status(2075, "two.gcode", 2, 20), "local", "CC2");
            PrintJournal kept = PrintRecorder.Recording.read(running).journal;
            assertEquals("watching the first layer", kept.notes); assertEquals(1, kept.guardian.size()); assertTrue(kept.guardian.get(0).getBoolean("fine"));
            assertEquals("the finished print's journal is untouched", 4, PrintRecorder.Recording.read(finished).journal.stars);
            assertEquals("an interrupted print does not become the last finished", finished, recorder.lastFinished());
        } finally { PrintRecorder.active = null; }
        assertNull(new PrintRecorder(folder.newFolder()).lastFinished());
    }
}
