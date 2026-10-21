package io.github.thelastfrogrammer.elink;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/** Colour by height: a start filament and changes going up become the engine's bands. */
public class ColourBandsTest {
    @Test public void changesBecomeBandsUpToTheTop() {
        List<double[]> bands = SliceActivity.bands(1, Arrays.asList(new double[] {8, 3}, new double[] {5, 2}));   // given out of order
        assertEquals(3, bands.size());
        assertArrayEquals(new double[] {0, 5, 1}, bands.get(0), 1e-9);
        assertArrayEquals(new double[] {5, 8, 2}, bands.get(1), 1e-9);
        assertArrayEquals(new double[] {8, SliceActivity.TOP, 3}, bands.get(2), 1e-9);
    }

    @Test public void noChangesMeansNoBandsAndRepeatsCollapse() {
        assertTrue("off: the models keep their own filament", SliceActivity.bands(2, Collections.emptyList()).isEmpty());
        List<double[]> same = SliceActivity.bands(1, Arrays.asList(new double[] {4, 2}, new double[] {4, 3}));
        assertEquals("two changes at one height: the later wins, with no empty band", 2, same.size());
        assertArrayEquals(new double[] {0, 4, 1}, same.get(0), 1e-9);
        assertArrayEquals(new double[] {4, SliceActivity.TOP, 3}, same.get(1), 1e-9);
    }
}
