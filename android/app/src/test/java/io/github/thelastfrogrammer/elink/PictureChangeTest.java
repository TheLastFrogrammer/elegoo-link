package io.github.thelastfrogrammer.elink;

import static org.junit.Assert.*;

import java.util.Random;
import org.junit.Test;

/** The guardian's change check, on made-up picture runs: a bed with camera noise, and a print that grows, spreads or leaves. */
public class PictureChangeTest {
    private static final int W = PictureChange.W, H = PictureChange.H;

    /** A grey bed with noise, a darker part where the print is (a box from x0..x1, its top at `top` rows from the bottom). */
    private static int[] frame(Random noise, double light, int x0, int x1, int top, boolean[] mess) {
        int[] g = new int[W * H];
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            int v = 150;
            if (x >= x0 && x < x1 && y >= H - 20 - top && y < H - 20) v = 60;      // the print, growing up from the bed
            if (mess != null && mess[y * W + x]) v = 40;
            g[y * W + x] = (int) Math.max(0, Math.min(255, v * light + noise.nextGaussian() * 6));
        }
        return g;
    }
    private static boolean[] blobs(Random r, int count) {
        boolean[] m = new boolean[W * H];
        for (int b = 0; b < count; b++) {
            int cx = 5 + r.nextInt(W - 10), cy = 5 + r.nextInt(H - 30);
            for (int dy = -3; dy <= 3; dy++) for (int dx = -3; dx <= 3; dx++) m[(cy + dy) * W + cx + dx] = true;
        }
        return m;
    }

    @Test public void aPrintGrowingNormallyNeverAlarms() {
        Random noise = new Random(1); PictureChange.Watch watch = new PictureChange.Watch();
        for (int layer = 1; layer <= 40; layer++) assertFalse("layer " + layer + ": " + watch.reason, watch.add(frame(noise, 1, 60, 100, layer / 2, null), null));
        assertEquals("looks as expected", watch.reason);
    }

    @Test public void theLightChangingIsNotAProblem() {
        Random noise = new Random(2); PictureChange.Watch watch = new PictureChange.Watch();
        for (int layer = 1; layer <= 30; layer++) {
            double light = layer < 15 ? 1 : 0.7;                                  // the chamber light dims, and stays so
            assertFalse(watch.reason, watch.add(frame(noise, light, 60, 100, layer / 2, null), null));
        }
    }

    @Test public void aFlickerThatGoesBackIsNotAProblem() {
        Random noise = new Random(3); PictureChange.Watch watch = new PictureChange.Watch();
        boolean[] shadow = new boolean[W * H]; for (int i = 0; i < W * H / 3; i++) shadow[i] = true;   // a passing shadow over a third of it
        for (int layer = 1; layer <= 30; layer++) assertFalse(watch.reason, watch.add(frame(noise, 1, 60, 100, layer / 2, layer == 12 ? shadow : null), null));
    }

    @Test public void spaghettiSpreadingAlarmsOnce() {
        Random noise = new Random(4), where = new Random(5); PictureChange.Watch watch = new PictureChange.Watch();
        int alarms = 0; boolean[] mess = new boolean[W * H];
        for (int layer = 1; layer <= 30; layer++) {
            if (layer > 10) { boolean[] more = blobs(where, 6); for (int i = 0; i < mess.length; i++) mess[i] |= more[i]; }
            if (watch.add(frame(noise, 1, 60, 100, Math.min(layer, 10) / 2, mess), null)) alarms++;
        }
        assertEquals(watch.reason, 1, alarms);
        assertTrue(watch.reason, watch.reason.startsWith("changes spreading"));
    }

    @Test public void aPartKnockedOffAlarms() {
        Random noise = new Random(6); PictureChange.Watch watch = new PictureChange.Watch();
        boolean raised = false; String why = "";
        for (int layer = 1; layer <= 30; layer++) {
            boolean gone = layer >= 15;                                           // the part is gone from the bed: bare bed where it stood
            int[] f = gone ? frame(noise, 1, 0, 0, 0, null) : frame(noise, 1, 30, 130, 30, null);
            if (watch.add(f, null)) { raised = true; why = watch.reason; }
        }
        assertTrue(watch.reason, raised);
        assertTrue(why, why.contains("come loose"));
    }

    @Test public void looksFineAllowsMore() {
        Random noise = new Random(7), where = new Random(8); PictureChange.Watch watch = new PictureChange.Watch();
        boolean[] mess = new boolean[W * H]; int alarms = 0; boolean growing = true;
        for (int layer = 1; layer <= 40; layer++) {
            if (layer > 8 && growing) { boolean[] more = blobs(where, 6); for (int i = 0; i < mess.length; i++) mess[i] |= more[i]; }
            // The person looks, says it is fine (a brim, a purge), and the mess stops growing.
            if (watch.add(frame(noise, 1, 60, 100, 4, mess), null)) { alarms++; watch.looksFine(); growing = false; }
        }
        assertEquals("one alarm; after \"looks fine\" what is there is learned", 1, alarms);
        assertEquals(watch.reason, "looks as expected", watch.reason);
    }

    @Test public void theHeadMaskedOutIsIgnored() {
        Random noise = new Random(9); PictureChange.Watch watch = new PictureChange.Watch();
        boolean[] head = new boolean[W * H]; for (int y = 0; y < 40; y++) for (int x = 0; x < W; x++) head[y * W + x] = true;
        Random place = new Random(10);
        for (int layer = 1; layer <= 30; layer++) {
            boolean[] moving = new boolean[W * H]; int hx = place.nextInt(W - 30);
            for (int y = 5; y < 35; y++) for (int x = hx; x < hx + 30; x++) moving[y * W + x] = true;   // the head somewhere in the masked band
            assertFalse(watch.reason, watch.add(frame(noise, 1, 60, 100, layer / 2, moving), head));
        }
    }
}
