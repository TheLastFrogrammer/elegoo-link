package io.github.thelastfrogrammer.elink;

import static org.junit.Assert.assertEquals;

import java.util.Locale;
import org.junit.Test;

/** The calls the native plate panel sends to the page for the non-gesture Select and Move dialogs. */
public class PlateMoveScriptTest {
    @Test public void moveUsesPlainNumbersWhateverTheLocale() {
        Locale before = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            assertEquals("plate.move(-5,0)", PlateActivity.moveScript(-5, 0));
            assertEquals("plate.move(0,10)", PlateActivity.moveScript(0, 10));
            assertEquals("plate.move(0.5,-1)", PlateActivity.moveScript(0.5, -1));
        } finally { Locale.setDefault(before); }
    }

    @Test public void stepsAreOneFiveTen() {
        assertEquals(3, PlateActivity.MOVE_STEPS.length);
        assertEquals(1, PlateActivity.MOVE_STEPS[0]); assertEquals(5, PlateActivity.MOVE_STEPS[1]); assertEquals(10, PlateActivity.MOVE_STEPS[2]);
    }

    @Test public void selectCallsThePageApi() { assertEquals("plate.select(2)", PlateActivity.selectScript(2)); }
}
