package io.github.thelastfrogrammer.elink;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/** The plate panel's one-line problem summary (the page's issue keys in, a sentence out). */
public class PlateSummaryTest {
    private static List<String> issues(String... keys) { return Arrays.asList(keys); }

    @Test public void nothingWrongGivesNoSummary() {
        assertNull(PlateActivity.summarize(Arrays.asList(issues(), issues())));
        assertNull(PlateActivity.summarize(Collections.<List<String>>emptyList()));
    }

    @Test public void anOverlappingPairIsOneLineNotOnePerModel() {
        String line = PlateActivity.summarize(Arrays.asList(issues("touching another copy"), issues("touching another copy")));
        assertEquals("2 models overlap. Drag them clear or tap Arrange all.", line);
    }

    @Test public void offTheBedNamesTheButtonByItsRealName() {
        String line = PlateActivity.summarize(Arrays.asList(issues("off the bed"), issues()));
        assertEquals("1 model is off the bed. Drag it clear or tap Arrange all.", line);
        assertTrue(line.contains("Arrange all"));
    }

    @Test public void severalKindsAreJoinedAndTallAdvisesScalingOrLaying() {
        String line = PlateActivity.summarize(Arrays.asList(issues("off the bed", "touching another copy"), issues("touching another copy"), issues("taller than the printer")));
        assertEquals("1 model is off the bed · 1 model is too tall for the printer · 2 models overlap. Drag them clear or tap Arrange all."
            + " For a tall one: scale it down, or More… > Lay flat.", line);
    }
}
