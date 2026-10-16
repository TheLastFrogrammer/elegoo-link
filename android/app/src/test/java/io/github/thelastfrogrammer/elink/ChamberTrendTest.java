package io.github.thelastfrogrammer.elink;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ChamberTrendTest {
    private static final long MIN = 60_000L;

    @Test public void countsDownToTheGoalWhileWarming() {
        ChamberTrend t = new ChamberTrend();
        for (int i = 0; i <= 4; i++) t.add(i * MIN, 30 + 0.5 * i);   // 0.5 °C a minute
        assertEquals(0.5, t.perMinute(), 1e-9);
        assertEquals(16 * 60, t.secondsTo(40, 32), 1);
        assertEquals("↑ 40° in ≈ " + StatusPresentation.span(16 * 60), t.line(32, 40, Double.NaN));
        assertEquals("40° reached", t.line(40.2, 40, Double.NaN));
    }
    @Test public void needsEnoughReadingsAndSaysWhenSteadyOrCooling() {
        ChamberTrend t = new ChamberTrend();
        t.add(0, 30); t.add(30_000, 31);
        assertTrue("under 90 s", Double.isNaN(t.perMinute()));
        assertEquals(-1, t.secondsTo(40, 31));
        ChamberTrend steady = new ChamberTrend();
        for (int i = 0; i <= 4; i++) steady.add(i * MIN, 35);
        assertEquals("not warming: no countdown", -1, steady.secondsTo(40, 35));
        assertEquals("peak 41°", steady.line(35, Double.NaN, 41.4));
        ChamberTrend cooling = new ChamberTrend();
        for (int i = 0; i <= 4; i++) cooling.add(i * MIN, 40 - 0.4 * i);
        assertEquals("↓ 0.4°/min", cooling.line(38.4, Double.NaN, Double.NaN));
        // Only the last five minutes count.
        ChamberTrend window = new ChamberTrend();
        for (int i = 0; i <= 20; i++) window.add(i * MIN, i < 10 ? 25 + i : 35);
        assertEquals(0, window.perMinute(), 1e-9);
    }
    @Test public void onlyEnclosureMaterialsHaveAGoal() {
        assertEquals(40, ChamberTrend.goal("ABS"), 0); assertEquals(40, ChamberTrend.goal("Elegoo ASA"), 0); assertEquals(40, ChamberTrend.goal("PA6-CF"), 0);
        assertTrue(Double.isNaN(ChamberTrend.goal("PLA"))); assertTrue(Double.isNaN(ChamberTrend.goal("")));
    }
}
