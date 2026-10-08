package io.github.thelastfrogrammer.elink;

import static org.junit.Assert.*;
import java.time.Instant;
import java.util.TimeZone;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Clock-time axis helpers for the recording charts. */
public class ChartClockTest {
    private TimeZone zone;
    @Before public void useUtc() { zone = TimeZone.getDefault(); TimeZone.setDefault(TimeZone.getTimeZone("UTC")); }
    @After public void restoreZone() { TimeZone.setDefault(zone); }

    @Test public void clockOfDayWrapsAtMidnight() {
        assertEquals("14:05", ChartView.clockOfDay(86400L + 14 * 3600 + 5 * 60));
        assertEquals("00:30", ChartView.clockOfDay(2 * 86400L + 30 * 60));
        assertEquals("23:59", ChartView.clockOfDay(86400L - 60));
    }

    @Test public void localSecondsFollowTheDeviceTimeZone() {
        long millis = Instant.parse("2026-10-07T22:30:00Z").toEpochMilli();
        assertEquals("22:30", ChartView.clockOfDay((long) ChartView.localSeconds(millis)));
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/London")); // BST in October: one hour ahead
        assertEquals("23:30", ChartView.clockOfDay((long) ChartView.localSeconds(millis)));
    }

    @Test public void spansAreInPlainWords() {
        assertEquals("under 1 min", ChartView.spanWords(20));
        assertEquals("45 min", ChartView.spanWords(45 * 60));
        assertEquals("3 h 20 min", ChartView.spanWords(200 * 60));
        assertEquals("2 h", ChartView.spanWords(7200));
    }
}
