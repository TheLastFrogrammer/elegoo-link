package io.github.thelastfrogrammer.elink;

import static org.junit.Assert.*;
import java.util.*;
import org.json.JSONArray;
import org.junit.Test;

/** Matching a print's filaments to loaded CANVAS trays: material first, colour second, and plain verdicts. */
public class FilamentMatchTest {
    private static TrayPlan.Tray tray(int id, String type, String name, String colour) { return new TrayPlan.Tray(0, id, type, name, "Elegoo", colour); }
    private static FilamentMatch.Need need(int t, String type, String colour) { return new FilamentMatch.Need(t, type, colour, type, "test"); }

    @Test public void readsMaterialFamiliesFromNamesAndPresets() {
        assertEquals("PLA", FilamentMatch.family("Elegoo PLA Matte @ECC2"));
        assertEquals("PLA", FilamentMatch.family("PLA+"));
        assertEquals("PLA", FilamentMatch.family("PLA-CF"));
        assertEquals("PETG", FilamentMatch.family("Elegoo PETG HF @ECC2"));
        assertEquals("PET", FilamentMatch.family("PET"));
        assertEquals("PA", FilamentMatch.family("PA6-CF"));
        assertEquals("TPU", FilamentMatch.family("Elegoo TPU 95A @ECC2"));
        assertEquals("PCTG", FilamentMatch.family("PCTG"));
        assertEquals("", FilamentMatch.family("Mystery spool"));
        assertTrue(FilamentMatch.filled("PLA-CF")); assertFalse(FilamentMatch.filled("PLA Matte"));
    }

    @Test public void suggestsTheRightMaterialThenTheClosestColourAndUsesEachTrayOnce() {
        List<TrayPlan.Tray> trays = Arrays.asList(tray(0, "PLA", "PLA", "#F0F0F0"), tray(1, "PETG", "PETG", "#101010"), tray(2, "PLA", "PLA Matte", "#C82020"), tray(3, "PLA", "PLA", "#1E5AA8"));
        FilamentMatch.Need[] needs = {need(0, "PLA", "#D02828"), need(1, "PLA", "#FFFFFF"), need(2, "PETG", "#000000"), need(3, "ABS", "#000000")};
        int[] picks = FilamentMatch.suggest(needs, trays);
        assertEquals(2, picks[0]);   // red PLA → the red tray
        assertEquals(0, picks[1]);   // white PLA → the white tray
        assertEquals(1, picks[2]);   // PETG → the only PETG, despite other dark trays
        assertEquals(-1, picks[3]);  // no ABS loaded: never a tray of another material
    }

    @Test public void sharesATrayOnlyWhenThereAreMoreFilamentsThanMatchingTrays() {
        List<TrayPlan.Tray> trays = Collections.singletonList(tray(0, "PLA", "PLA", "#FFFFFF"));
        int[] picks = FilamentMatch.suggest(new FilamentMatch.Need[] {need(0, "PLA", "#FFFFFF"), need(1, "PLA", "#FF0000")}, trays);
        assertArrayEquals(new int[] {0, 0}, picks);
        assertArrayEquals(new int[] {-1}, FilamentMatch.suggest(new FilamentMatch.Need[] {null}, trays));
    }

    @Test public void verdictsSayWhatIsWrong() {
        TrayPlan.Tray petg = tray(0, "PETG", "PETG", "#FFFFFF"), red = tray(1, "PLA", "PLA Matte", "#D02828");
        FilamentMatch.Verdict wrong = FilamentMatch.check(need(0, "PLA", "#FFFFFF"), petg, null);
        assertEquals(FilamentMatch.Level.WRONG, wrong.level); assertTrue(wrong.text, wrong.text.contains("sliced for PLA") && wrong.text.contains("PETG"));
        FilamentMatch.Verdict colour = FilamentMatch.check(need(0, "PLA", "#FFFFFF"), red, null);
        assertEquals(FilamentMatch.Level.CHECK, colour.level); assertTrue(colour.text, colour.text.contains("white") && colour.text.contains("red"));
        assertEquals(FilamentMatch.Level.OK, FilamentMatch.check(need(0, "PLA", "#D52A2A"), red, null).level);
        FilamentMatch.Verdict shared = FilamentMatch.check(need(1, "PLA", "#D02828"), red, Collections.singletonList(1));
        assertEquals(FilamentMatch.Level.CHECK, shared.level); assertTrue(shared.text.contains("Filament 1 also prints from this tray"));
        assertEquals(FilamentMatch.Level.CHECK, FilamentMatch.check(need(0, "PLA-CF", null), red, null).level);
        assertEquals(FilamentMatch.Level.CHECK, FilamentMatch.check(null, null, null).level);
        assertTrue(FilamentMatch.check(null, red, null).text.contains("does not say"));
        assertTrue(FilamentMatch.unset(need(0, "PETG", null), Collections.singletonList(red)).text.contains("No loaded tray has PETG"));
        assertTrue(FilamentMatch.unset(need(0, "PLA", null), Collections.singletonList(red)).text.contains("printer's own choice"));
    }

    @Test public void needsComeFromThePlanThenThePrinterThenTheFile() throws Exception {
        TrayPlan plan = new TrayPlan(2, new ArrayList<>()).withNeeds(Arrays.asList("Elegoo PLA Matte @ECC2", "Elegoo PETG @ECC2"), Arrays.asList("#D02828", null));
        List<FilamentMatch.Need> fromPlan = FilamentMatch.fromPlan(TrayPlan.parse(plan.toJson()));
        assertEquals(2, fromPlan.size());
        assertEquals("PLA", fromPlan.get(0).type); assertEquals("Elegoo PLA Matte", fromPlan.get(0).label); assertEquals("#D02828", fromPlan.get(0).colour);
        assertEquals("Elegoo PLA Matte · red", fromPlan.get(0).describe());
        List<FilamentMatch.Need> printer = FilamentMatch.fromColorMap(new JSONArray("[{\"t\":1,\"color\":\"#00FF00\",\"type\":\"ABS\"},{\"t\":2,\"color\":\"#0000FF\"},{\"nothing\":1},\"x\"]"));
        assertEquals(2, printer.size()); assertEquals(1, printer.get(0).t); assertEquals("ABS", printer.get(0).type);
        assertTrue(FilamentMatch.fromColorMap("not a list").isEmpty()); assertTrue(FilamentMatch.fromColorMap(null).isEmpty());
        FilamentMatch.Need[] merged = FilamentMatch.merge(3, fromPlan, printer);
        assertEquals("PETG", merged[1].type);            // the phone's plan wins
        assertEquals("#0000FF", merged[2].colour);        // the printer fills what the plan does not cover
        assertNull(FilamentMatch.merge(1, new ArrayList<>())[0]);
    }

    @Test public void olderPlansWithoutNeedsStillRead() {
        TrayPlan old = TrayPlan.parse("{\"count\":2,\"tools\":[{\"t\":0,\"canvas_id\":0,\"tray_id\":1}]}");
        assertNotNull(old); assertTrue(old.needs.isEmpty()); assertTrue(FilamentMatch.fromPlan(old).isEmpty());
        assertTrue(FilamentMatch.fromPlan(null).isEmpty());
    }

    @Test public void colourDistanceTellsSimilarFromDifferent() {
        assertEquals(0, FilamentMatch.distance("#123456", "#123456"), 1e-9);
        assertTrue(FilamentMatch.distance("#D02828", "#C82020") < 10);
        assertTrue(FilamentMatch.distance("#FFFFFF", "#D02828") > 35);
    }
}
