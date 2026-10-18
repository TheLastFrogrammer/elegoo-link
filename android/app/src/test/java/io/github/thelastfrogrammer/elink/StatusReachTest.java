package io.github.thelastfrogrammer.elink;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** The header's line on what the connection can do and how old its status is. */
public class StatusReachTest {
    @Test public void agesInWords() {
        assertEquals("", StatusPresentation.age(0, 1000));
        assertEquals("just now", StatusPresentation.age(1000, 3000));
        assertEquals("40 s ago", StatusPresentation.age(1, 40_001));
        assertEquals("3 min ago", StatusPresentation.age(1, 180_001));
        assertEquals("2 h ago", StatusPresentation.age(1, 2 * 3_600_000L + 1));
    }
    @Test public void cloudWatchingIsNotFullControl() {
        long now = 1_000_000;
        assertEquals("Wi-Fi: status, controls, uploads, camera · status just now", StatusPresentation.reach(true, false, false, false, false, now, now));
        assertEquals("Wi-Fi probe: status only, read-only · status 40 s ago", StatusPresentation.reach(true, true, false, false, false, now - 40_000, now));
        assertEquals("Cloud: status only until you agree to cloud control · status 3 min ago", StatusPresentation.reach(false, false, true, true, false, now - 180_000, now));
        assertEquals("Cloud: status, controls, uploads, cloud video · status just now", StatusPresentation.reach(false, false, true, true, true, now, now));
        assertEquals("Cloud: printer offline", StatusPresentation.reach(false, false, true, false, true, 0, now));
        assertEquals("", StatusPresentation.reach(false, false, false, false, false, 0, now));
    }
}
