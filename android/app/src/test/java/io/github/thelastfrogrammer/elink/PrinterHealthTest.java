package io.github.thelastfrogrammer.elink;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Wear over the weeks: heating rates, the bed's tilt and flatness at one temperature, and the probe's repeatability. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class PrinterHealthTest {
    private static final long DAY = 86_400_000L;

    @Test public void heatingIsMeasuredFromColdBetweenTwoTemperatures() {
        // Readings every 10 s; the bed climbs 4 °C a minute from 25 °C to its 60 °C target.
        int n = 80; double[] t = new double[n], temp = new double[n], target = new double[n];
        for (int i = 0; i < n; i++) { t[i] = i * 10; target[i] = 60; temp[i] = Math.min(60, 25 + 4 * t[i] / 60); }
        assertEquals(4.0, PrinterHealth.heatRate(t, temp, target, 35, 55), 1e-9);
        // Started warm (already above 35 °C): not measured.
        double[] warm = temp.clone(); for (int i = 0; i < n; i++) warm[i] = Math.max(40, warm[i]);
        assertTrue(Double.isNaN(PrinterHealth.heatRate(t, warm, target, 35, 55)));
        // A target below the top of the stretch (heating to 50 °C only): not measured.
        double[] low = new double[n]; java.util.Arrays.fill(low, 50);
        assertTrue(Double.isNaN(PrinterHealth.heatRate(t, temp, low, 35, 55)));
    }

    @Test public void aTrendComparesTheFirstFewWithTheLastFew() {
        List<PrinterHealth.Point> rates = new ArrayList<>();
        double[] v = {4.0, 4.1, 3.9, 9.0 /* one odd print */, 3.4, 3.0, 3.1, 2.9};
        for (int i = 0; i < v.length; i++) rates.add(new PrinterHealth.Point(i * 7 * DAY, v[i]));
        java.util.Collections.shuffle(rates, new java.util.Random(3));
        PrinterHealth.Trend t = PrinterHealth.trend(rates);
        assertEquals(4.0, t.before, 1e-9); assertEquals(3.0, t.now, 1e-9); assertEquals(-0.25, t.change, 1e-9); assertEquals(8, t.count);
        String words = PrinterHealth.heatingWords("bed", t, 6);
        assertTrue(words, words.startsWith("The bed heats 25 % slower than at first: 4.0 °C a minute at first, 3.0 now (8 prints since"));
        assertNull("too few to say", PrinterHealth.trend(rates.subList(0, 5)));
        assertTrue(PrinterHealth.heatingWords("nozzle", null, 6).startsWith("Not enough prints yet"));
        List<PrinterHealth.Point> steady = new ArrayList<>(); for (int i = 0; i < 6; i++) steady.add(new PrinterHealth.Point(i, 4 + (i % 2) * 0.1));
        assertTrue(PrinterHealth.heatingWords("bed", PrinterHealth.trend(steady), 6).startsWith("The bed heats as it did"));
        List<PrinterHealth.Point> drifting = new ArrayList<>(); double[] d = {4.0, 4.0, 4.0, 3.5, 3.4, 3.4};
        for (int i = 0; i < d.length; i++) drifting.add(new PrinterHealth.Point(i, d[i]));
        String little = PrinterHealth.heatingWords("bed", PrinterHealth.trend(drifting), 6);
        assertTrue(little, little.startsWith("The bed heats a little slower than at first (15 %)") && little.contains("Nothing to act on yet"));
    }

    /** A 5 × 5 levelling with a tilt across X (mm over the probed area) and a dip in the middle. */
    static BedMesh.Mesh mesh(long at, double target, double tilt, double dip) {
        BedMesh.Mesh m = new BedMesh.Mesh(); m.at = at; m.bedTarget = target; m.bed = target;
        m.xs = new double[] {10, 69, 128, 187, 246}; m.ys = m.xs.clone(); m.z = new double[5][5];
        for (int j = 0; j < 5; j++) for (int i = 0; i < 5; i++) m.z[j][i] = tilt * (m.xs[i] - 10) / 236 - (i == 2 && j == 2 ? dip : 0);
        return m;
    }

    @Test public void theBedIsComparedAtOneTemperature() {
        List<BedMesh.Mesh> all = new ArrayList<>();
        for (int k = 0; k < 6; k++) all.add(mesh(k * 7 * DAY, 60, 0.05 + 0.02 * k, 0.02 + 0.03 * k));   // tilt and dip growing at 60 °C
        all.add(mesh(3 * DAY, 100, 0.5, 0.3));                                                           // one hot levelling: left out
        assertEquals(6, PrinterHealth.sameTemperature(all).size());
        double[] tf = PrinterHealth.tiltAndFlatness(mesh(0, 60, 0.10, 0));
        assertEquals(0.10, tf[0], 1e-9); assertEquals(0, tf[1], 1e-9);
        List<String> words = PrinterHealth.bedWords(all);
        assertTrue(words.get(0), words.get(0).startsWith("Tilt at 60 °C: 0.07 mm at first, 0.13 mm now (6 levellings since") && words.get(0).contains("It has grown"));
        assertTrue(words.get(1), words.get(1).contains("less flat than it was"));
        assertTrue(PrinterHealth.bedWords(all.subList(0, 3)).get(0).startsWith("Not enough levellings yet at 60 °C"));
    }

    @Test public void theProbesRepeatabilityComesFromPointsProbedTwice() throws Exception {
        // Every point probed twice: the second touch 0.004 mm off the first, one point 0.03 mm off.
        BedMesh.Capture c = new BedMesh.Capture();
        for (int pass = 0; pass < 2; pass++) for (JSONObject s : BedMeshTest.levelling(5, 121)) {
            JSONObject at = s.getJSONObject("gcode_move");
            if (pass == 1 && at.getDouble("z") < 1) at.put("z", at.getDouble("z") + (at.getDouble("x") == 10 && at.getDouble("y") == 10 ? 0.03 : 0.004));
            c.feed(s, 0, "local network", 0);
        }
        BedMesh.Mesh m = c.feed(BedMeshTest.status(1, 0, 0, 0, 0), 1, "local network", 0);
        assertEquals(121, m.repeated); assertEquals(0.03, m.repeatSpread, 1e-6);
        BedMesh.Mesh back = BedMesh.parse(BedMesh.json(m));
        assertEquals(0.03, back.repeatSpread, 1e-6); assertEquals(121, back.repeated);
        String words = PrinterHealth.repeatWords(java.util.Collections.singletonList(back));
        assertTrue(words, words.startsWith("Where it probed a point twice (121 points in 1 levellings), the readings were at most 0.030 mm apart.") && words.contains("dirty nozzle"));
        assertTrue(PrinterHealth.repeatWords(java.util.Collections.singletonList(BedMeshTest.probedOnce())).startsWith("The printer has not probed any point twice"));
    }
}
