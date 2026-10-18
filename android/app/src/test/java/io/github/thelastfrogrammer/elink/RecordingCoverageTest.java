package io.github.thelastfrogrammer.elink;

import static org.junit.Assert.*;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** A recording says when the app was not watching, and when its pictures fell behind the interval asked for. */
public class RecordingCoverageTest {
    private static List<double[]> rows(long... times) {
        List<double[]> out = new ArrayList<>();
        for (long t : times) { double[] row = new double[PrintRecorder.COLUMNS.length]; java.util.Arrays.fill(row, Double.NaN); row[PrintRecorder.column("time_ms")] = t; out.add(row); }
        return out;
    }
    private static PrintRecorder.Recording recording(long minutes, int every, int pictures) {
        PrintRecorder.Recording r = new PrintRecorder.Recording(new File("x.csv"), new File("x.json"));
        r.start = 0; r.end = minutes * 60_000L; r.lastSample = r.end; r.elapsed = r.end; r.pictureMinutes = every; r.pictures = pictures;
        return r;
    }

    @Test public void watchedThroughoutSaysNothing() {
        assertEquals("", RecordingsActivity.coverage(rows(0, 30_000, 60_000, 90_000), recording(40, 5, 8)));
    }
    @Test public void gapsAndMissingPicturesAreNamed() {
        String text = RecordingsActivity.coverage(rows(0, 30_000, 30_000 + 20 * 60_000L, 30_000 + 21 * 60_000L), recording(40, 5, 2));
        assertTrue(text, text.startsWith("The app was not watching once, "));
        assertTrue(text, text.contains("Pictures: 2 of about 8 (every 5 min)."));
    }
    @Test public void layerPicturesAreNotJudgedByTime() {
        PrintRecorder.Recording r = recording(40, 5, 0); r.pictureLayers = 2;
        assertEquals("", RecordingsActivity.coverage(rows(0, 30_000), r));
    }
}
