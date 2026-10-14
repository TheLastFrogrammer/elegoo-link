package io.github.thelastfrogrammer.elink;

import static org.junit.Assert.*;
import java.io.File;
import java.time.Instant;
import java.util.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Plain-language list rows, outcomes, durations and the end-of-print summary of a recording. */
public class RecordingsSummaryTest {
    private TimeZone zone;
    @Before public void useUtc() { zone = TimeZone.getDefault(); TimeZone.setDefault(TimeZone.getTimeZone("UTC")); }
    @After public void restoreZone() { TimeZone.setDefault(zone); }

    private static long at(String iso) { return Instant.parse(iso).toEpochMilli(); }

    @Test public void durationsReadInHoursAndMinutes() {
        assertEquals("under 1 min", RecordingsActivity.duration(20_000));
        assertEquals("45 min", RecordingsActivity.duration(45 * 60_000L));
        assertEquals("3 h 20 min", RecordingsActivity.duration(200 * 60_000L));
        assertEquals("2 h", RecordingsActivity.duration(120 * 60_000L));
    }

    @Test public void resultsUseWordsAHobbyistKnows() {
        assertEquals("Completed", RecordingsActivity.outcome("complete"));
        assertEquals("Stopped", RecordingsActivity.outcome("stopped"));
        assertEquals("Unfinished", RecordingsActivity.outcome("printing"));
        assertEquals("Ended (printer went idle)", RecordingsActivity.outcome("ended"));
        assertEquals("Unknown", RecordingsActivity.outcome("whatever"));
    }

    @Test public void dateIncludesWeekdayAndClockTime() {
        assertEquals("Tue 6 Oct, 14:05", RecordingsActivity.when(at("2026-10-06T14:05:00Z"), Locale.US, TimeZone.getTimeZone("UTC")));
    }

    @Test public void listRowGivesWhenHowLongAndResult() {
        PrintRecorder.Recording recording = new PrintRecorder.Recording(new File("a.csv"), new File("a.json"));
        recording.start = at("2026-10-07T22:30:00Z"); recording.end = at("2026-10-08T01:50:00Z"); recording.outcome = "stopped";
        assertEquals("Wed 7 Oct, 22:30 · 3 h 20 min · Stopped", RecordingsActivity.rowSummary(recording, Locale.US, TimeZone.getTimeZone("UTC")));
        recording.outcome = "printing"; recording.end = 0; recording.lastSample = recording.start + 40 * 60_000L;
        assertEquals("Wed 7 Oct, 22:30 · 40 min · Unfinished", RecordingsActivity.rowSummary(recording, Locale.US, TimeZone.getTimeZone("UTC")));
    }

    @Test public void lastReadingNamesTheTimeProgressAndTemperatures() {
        List<double[]> rows = new ArrayList<>();
        rows.add(row("2026-10-07T22:00:00Z", 10, 219, 220, 60, 60));
        rows.add(row("2026-10-07T22:55:00Z", 41, 84, 220, 40, 60));
        assertEquals("Last reading 22:55 · progress 41 % · nozzle 84 °C (target 220 °C) · bed 40 °C (target 60 °C)", RecordingsActivity.lastReading(rows));
        assertEquals("", RecordingsActivity.lastReading(new ArrayList<>()));
    }

    /** A sample with the columns the summary reads; chamber and fan values are left unreported. */
    private static double[] row(String iso, double progress, double nozzle, double nozzleTarget, double bed, double bedTarget) {
        double[] row = new double[PrintRecorder.COLUMNS.length]; Arrays.fill(row, Double.NaN);
        row[PrintRecorder.column("time_ms")] = at(iso); row[PrintRecorder.column("progress")] = progress;
        row[PrintRecorder.column("nozzle_c")] = nozzle; row[PrintRecorder.column("nozzle_target_c")] = nozzleTarget;
        row[PrintRecorder.column("bed_c")] = bed; row[PrintRecorder.column("bed_target_c")] = bedTarget;
        return row;
    }
    @Test public void readingLinesPutOneCapitalisedReadingOnEachLine() {
        assertEquals("Last reading 22:55\nProgress 41\u00a0%\nNozzle 84\u00a0°C (target 220\u00a0°C)",
            RecordingsActivity.readingLines("Last reading 22:55 · progress 41 % · nozzle 84 °C (target 220 °C)"));
        assertEquals("", RecordingsActivity.readingLines(""));
    }
    @Test public void filamentColourIsTheTrayThatPrintedItNotTheSlicedOne() {
        double nan = Double.NaN, black = 0x101010, white = 0xF0F0F0;
        // One filament: the black tray was active the whole print (even before filament was laid down).
        double[][] one = {{0}, {0}, {12}, {30}, {28}};
        int[] single = RecordingsActivity.printedColours(new double[] {black, black, black, black, black}, one, 1);
        assertEquals(0x101010, single[0]);
        // Two filaments: tool 0 laid down while white was active, tool 1 while black was; no reading leaves it unknown.
        double[][] two = {{20, 0}, {22, 0}, {0, 18}, {0, 19}};
        int[] pair = RecordingsActivity.printedColours(new double[] {white, white, black, black}, two, 2);
        assertEquals(0xF0F0F0, pair[0]); assertEquals(0x101010, pair[1]);
        int[] unknown = RecordingsActivity.printedColours(new double[] {nan, nan}, new double[][] {{5}, {6}}, 1);
        assertEquals(-1, unknown[0]);
    }
}
