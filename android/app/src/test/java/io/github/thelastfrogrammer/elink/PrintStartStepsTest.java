package io.github.thelastfrogrammer.elink;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** The start page claims a print only from a status that came after the tap and shows this file printing. */
public class PrintStartStepsTest {
    @Test public void onlyAFreshStatusWithThisFileCountsAsPrinting() {
        assertEquals(4, PrintStartSteps.stage(false, true, 2, "/local/Cube.gcode", "Cube.gcode", 5_000));
        assertEquals("an older status printing does not count", 2, PrintStartSteps.stage(false, false, 2, "Cube.gcode", "Cube.gcode", 5_000));
        assertEquals("another file printing is not this one", 3, PrintStartSteps.stage(false, true, 2, "Other.gcode", "Cube.gcode", 5_000));
        assertEquals("busy but not printing yet", 3, PrintStartSteps.stage(false, true, 10, "", "Cube.gcode", 5_000));
        assertEquals("still idle: waiting", 2, PrintStartSteps.stage(false, true, 1, "", "Cube.gcode", 5_000));
    }
    @Test public void refusedAndTimeoutAreSaid() {
        assertEquals(-1, PrintStartSteps.stage(true, true, 2, "Cube.gcode", "Cube.gcode", 0));
        assertEquals(-2, PrintStartSteps.stage(false, true, 1, "", "Cube.gcode", PrintStartSteps.GIVE_UP_MS + 1));
    }
}
