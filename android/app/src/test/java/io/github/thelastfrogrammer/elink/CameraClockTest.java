package io.github.thelastfrogrammer.elink;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.Test;

/** The camera clock, on made-up streams: a still bed, and a head that moves off from a stop at known times. */
public class CameraClockTest {
    private static final int N = CameraClock.W * CameraClock.H;

    /** Frames every `stepMs` for `ms`; while the head moves (1 s after each start) a patch of cells changes in each frame. */
    private static Object[] stream(long[] moveStartsSeen, long stepMs, long ms, Random noise) {
        List<Long> at = new ArrayList<>(); List<byte[]> frames = new ArrayList<>();
        for (long t = 0; t <= ms; t += stepMs) {
            byte[] f = new byte[N];
            for (int i = 0; i < N; i++) f[i] = (byte) Math.max(0, Math.min(255, 140 + noise.nextGaussian() * 5));
            for (long s : moveStartsSeen) if (t >= s && t < s + 1000) {
                int x0 = (int) ((t - s) / 1000.0 * (CameraClock.W - 10));   // the head crossing the picture
                for (int y = 10; y < 25; y++) for (int x = x0; x < x0 + 10; x++) f[y * CameraClock.W + x] = 30;
            }
            at.add(10_000 + t); frames.add(f);
        }
        long[] times = new long[at.size()]; for (int i = 0; i < times.length; i++) times[i] = at.get(i);
        return new Object[] {times, frames.toArray(new byte[0][])};
    }

    @Test public void theDelayIsTheMedianGapFromReportedMoveToSeenMotion() {
        // The status reports moves at 2, 6, 10 and 14 s; the picture shows them 0.7 s later (one 0.9 s, as the status lags a bit).
        long[] reported = {12_000, 16_000, 20_000, 24_000};
        long[] seen = {2_700, 6_700, 10_900, 14_700};
        Object[] s = stream(seen, 100, 20_000, new Random(1));
        long[] at = (long[]) s[0]; byte[][] frames = (byte[][]) s[1];
        long[] starts = CameraClock.starts(at, CameraClock.motion(frames));
        assertEquals("one start per move, none from noise", 4, starts.length);
        CameraClock.Result r = CameraClock.delay(reported, starts);
        assertNotNull(r);
        assertEquals(4, r.moves);
        assertEquals("the middle of 700, 700, 700, 900 (frames every 100 ms)", 700, r.delayMs, 100);
        assertTrue(r.spreadMs <= 300);
    }

    @Test public void tooFewMatchesGiveNoAnswer() {
        Object[] s = stream(new long[] {3_000}, 100, 10_000, new Random(2));
        long[] starts = CameraClock.starts((long[]) s[0], CameraClock.motion((byte[][]) s[1]));
        assertNull("one move is not enough", CameraClock.delay(new long[] {12_500}, starts));
        assertNull("moves far from any motion are not matched", CameraClock.delay(new long[] {30_000, 40_000}, starts));
    }

    /** PrintChecks notes a move each time the reported position leaves a stop (two or more readings alike). */
    @Test public void movesAfterAStopAreNoted() {
        PrintChecks checks = new PrintChecks(); checks.selfTest = true; long t = 0;
        double[][] path = {{100, 100}, {101, 100}, {30, 30}, {30, 30}, {30, 30}, {220, 220}, {220, 220}, {30, 30}, {30, 30}, {102, 101}, {103, 101}};
        long[] when = new long[path.length];
        for (int i = 0; i < path.length; i++) { t += 1000; when[i] = t; checks.status(t, 4, path[i][0], path[i][1], 1.0, true, 250, 250, null); }
        assertEquals("left (30,30), (220,220), (30,30)", java.util.Arrays.asList(when[5], when[7], when[9]), checks.departures);
        // The probe goes A, B, A, B: four moves, each after a 3 s stop.
        String g = SelfTest.probeGcode();
        assertEquals(4, g.split("G4 P3000", -1).length - 1);
    }
}
