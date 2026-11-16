package io.github.thelastfrogrammer.elink;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;

/** Time left corrected by how long earlier prints really took against what the printer said, by quarter of the print. */
public class FinishTimesTest {
    @After public void forget() { FinishTimes.current = null; }

    /** A print of `total` s whose printer said `said` × the real time left, early on (first half) and `late` × later; sampled every 60 s. */
    static double[][] print(double total, double said, double late, boolean paused) {
        int n = (int) (total / 60) + 1; double[] t = new double[n], progress = new double[n], left = new double[n], sub = new double[n];
        for (int i = 0; i < n; i++) {
            t[i] = i * 60; progress[i] = 100 * t[i] / total; double real = total - t[i];
            left[i] = real * (progress[i] < 50 ? said : late); sub[i] = paused && i == n / 2 ? 2502 : 2075;
        }
        return new double[][] {t, progress, left, sub};
    }
    static double[] factors(double[][] p) { return FinishTimes.factors(p[0], p[1], p[2], p[3]); }

    @Test public void eachQuarterLearnsItsOwnError() {
        // The printer says 80 % of the real time left in the first half (the print runs 25 % longer), and is right later.
        double[] f = factors(print(7200, 0.8, 1.0, false));
        assertEquals(1.25, f[0], 1e-9); assertEquals(1.25, f[1], 1e-9); assertEquals(1.0, f[2], 1e-9); assertEquals(1.0, f[3], 1e-9);
        for (double v : factors(print(7200, 0.8, 1.0, true))) assertTrue("a paused print says nothing about the estimate", Double.isNaN(v));
        // The last 5 minutes are left out: a few seconds there would be a large share.
        double[][] shortOne = print(240, 0.8, 0.8, false);
        for (double v : factors(shortOne)) assertTrue(Double.isNaN(v));
    }

    @Test public void theSameFileCountsFirstThenRecentPrints() {
        List<double[]> newestFirst = new ArrayList<>(); List<Boolean> same = new ArrayList<>();
        // Three other files that ran 10 % long, and two earlier prints of this file that ran 30 % long.
        for (int k = 0; k < 3; k++) { newestFirst.add(factors(print(7200, 1 / 1.1, 1 / 1.1, false))); same.add(false); }
        for (int k = 0; k < 2; k++) { newestFirst.add(factors(print(7200, 1 / 1.3, 1 / 1.3, false))); same.add(true); }
        FinishTimes.Learned l = FinishTimes.learn(newestFirst, same);
        assertTrue(l.sameFile[0]); assertEquals(1.3, l.factor[0], 1e-6); assertEquals(2, l.prints[0]);
        FinishTimes.Learned others = FinishTimes.learn(newestFirst.subList(0, 3), same.subList(0, 3));
        assertFalse(others.sameFile[2]); assertEquals(1.1, others.factor[2], 1e-6);
        assertNull("two prints of other files are not enough", FinishTimes.learn(newestFirst.subList(0, 2), same.subList(0, 2)));
        // Applied: 60 minutes said at 30 % becomes 78; the words say why.
        assertEquals(4680, FinishTimes.corrected(l, 3600, 30));
        assertTrue(FinishTimes.basis(l, 30).contains("2 earlier prints with this filename"));
        assertEquals(3600, FinishTimes.corrected(null, 3600, 30));
    }

    @Test public void theBasisChangesWhenSameFileEvidenceRunsOut() {
        double[] other = {1.1, 1.1, 1.1, 1.1}, same = {1.3, Double.NaN, Double.NaN, Double.NaN};
        FinishTimes.Learned l = FinishTimes.learn(Arrays.asList(other, other, other, same, same),
            Arrays.asList(false, false, false, true, true));
        assertEquals(1300, FinishTimes.corrected(l, 1000, 24));
        assertEquals(1100, FinishTimes.corrected(l, 1000, 25));
        assertTrue(FinishTimes.basis(l, 24).contains("2 earlier prints with this filename"));
        assertTrue(FinishTimes.basis(l, 25).contains("3 recent prints on this printer"));
        assertEquals(1000, FinishTimes.corrected(l, 1000, -1));
        assertTrue(FinishTimes.basis(l, Double.NaN).isEmpty());
    }

    @Test public void historyMustBelongToTheIdentifiedPrinter() {
        assertTrue(FinishTimes.samePrinter("printer-a", "printer-a"));
        assertFalse(FinishTimes.samePrinter("printer-a", "printer-b"));
        assertFalse(FinishTimes.samePrinter("printer-a", ""));
        assertFalse(FinishTimes.samePrinter("", ""));
    }

    @Test public void everyScreenReadsTheCorrectedTime() throws Exception {
        JSONObject machine = new JSONObject().put("status", 2).put("sub_status", 2075).put("progress", 60), print = new JSONObject().put("remaining_time_sec", 1000);
        assertEquals(1000, FinishTimes.left(machine, print));
        FinishTimes.Learned l = new FinishTimes.Learned(); Arrays.fill(l.factor, Double.NaN); l.factor[2] = 1.2; FinishTimes.current = l;
        assertEquals(1200, FinishTimes.left(machine, print));
        assertTrue(StatusPresentation.runningLine(machine, print).endsWith("0h 20m left"));
        JSONObject status = new JSONObject().put("machine_status", machine).put("print_status", print.put("filename", "p.gcode"));
        IntegrationStatus.Context c = new IntegrationStatus.Context(); c.fresh = true;
        JSONObject s = IntegrationStatus.build(status, c, 0);
        assertEquals(1200, s.getJSONObject("print").getLong("remaining_s")); assertEquals(1000, s.getJSONObject("print").getLong("printer_remaining_s"));
    }
}
