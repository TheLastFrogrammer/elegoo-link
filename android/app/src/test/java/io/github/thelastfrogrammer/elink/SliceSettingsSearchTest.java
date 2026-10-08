package io.github.thelastfrogrammer.elink;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** The print settings search: everyday words find the settings they mean, and the names and terms point at real keys. */
public class SliceSettingsSearchTest {
    private static Set<String> known() {
        Set<String> keys = new HashSet<>(SliceSettingsActivity.PROCESS_KEYS);
        keys.addAll(SliceSettingsActivity.FILAMENT_KEYS);
        return keys;
    }

    @Test public void wallWordsFindWallLoops() {
        assertTrue(SliceSettingsActivity.termKeys("walls").contains("wall_loops"));
        assertTrue(SliceSettingsActivity.termKeys("perimeters").contains("wall_loops"));
        assertTrue(SliceSettingsActivity.termKeys("perimeter").contains("wall_loops"));
    }

    @Test public void strongFindsWallsInfillAndShells() {
        Set<String> keys = SliceSettingsActivity.termKeys("strong");
        for (String key : new String[] {"wall_loops", "sparse_infill_density", "top_shell_layers", "bottom_shell_layers", "top_shell_thickness", "bottom_shell_thickness"})
            assertTrue(key, keys.contains(key));
        assertEquals(keys, SliceSettingsActivity.termKeys("stro"));
    }

    @Test public void solidFindsSolidLayersAndDenseInfill() {
        Set<String> keys = SliceSettingsActivity.termKeys("solid");
        assertTrue(keys.contains("sparse_infill_density"));
        assertTrue(keys.contains("top_shell_layers"));
    }

    @Test public void stringingAndOozingFindRetraction() {
        for (String word : new String[] {"stringing", "string", "oozing", "ooze"}) {
            Set<String> keys = SliceSettingsActivity.termKeys(word);
            assertTrue(word, keys.contains("filament_retraction_length"));
            assertTrue(word, keys.contains("filament_retraction_speed"));
            assertTrue(word, keys.contains("filament_deretraction_speed"));
            assertTrue(word, keys.contains("filament_z_hop"));
        }
    }

    @Test public void adhesionAndStickFindBrimAndFirstLayer() {
        for (String word : new String[] {"adhesion", "stick", "sticking", "first"}) {
            Set<String> keys = SliceSettingsActivity.termKeys(word);
            assertTrue(word, keys.contains("initial_layer_speed") || keys.contains("brim_type"));
        }
        Set<String> adhesion = SliceSettingsActivity.termKeys("adhesion");
        assertTrue(adhesion.contains("brim_type"));
        assertTrue(adhesion.contains("initial_layer_speed"));
        assertTrue(adhesion.contains("textured_plate_temp_initial_layer"));
        assertTrue(SliceSettingsActivity.termKeys("bed").contains("textured_plate_temp"));
    }

    @Test public void shortOrUnknownWordsMatchNothing() {
        assertTrue(SliceSettingsActivity.termKeys("").isEmpty());
        assertTrue(SliceSettingsActivity.termKeys("st").isEmpty());
        assertTrue(SliceSettingsActivity.termKeys("zebra").isEmpty());
    }

    @Test public void everyTermPointsAtASettingThisScreenKnows() {
        Set<String> known = known();
        for (Map.Entry<String, String[]> term : SliceSettingsActivity.SEARCH_TERMS.entrySet())
            for (String key : term.getValue()) assertTrue(term.getKey() + " -> " + key, known.contains(key));
    }

    @Test public void everyPlainLabelIsForASettingThisScreenShows() {
        Set<String> known = known();
        for (String key : SliceSettingsActivity.LABELS.keySet()) assertTrue(key, known.contains(key));
        assertEquals("Walls (wall loops)", SliceSettingsActivity.LABELS.get("wall_loops"));
    }
}
