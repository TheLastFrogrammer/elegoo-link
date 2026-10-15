package io.github.thelastfrogrammer.elink;

import org.junit.Test;
import static org.junit.Assert.*;

public class PictureVisitsTest {
    private static boolean due(boolean enabled, boolean otherVideo, boolean local, int streak, long sinceEmpty, boolean pictureDue) {
        return PictureVisits.due(enabled, otherVideo, local, true, false, true, true, false, streak, sinceEmpty, pictureDue);
    }
    @Test public void aVisitStartsOnlyInCloudModeWhenAPictureIsDueAndNothingElseHoldsTheChannel() {
        assertTrue(due(true, false, false, 0, 0, true));
        assertFalse("turned off in Settings", due(false, false, false, 0, 0, true));
        assertFalse("the Print tab's video is open", due(true, true, false, 0, 0, true));
        assertFalse("connected locally", due(true, false, true, 0, 0, true));
        assertFalse("no picture due", due(true, false, false, 0, 0, false));
        assertFalse("not agreed to cloud control", PictureVisits.due(true, false, false, true, false, true, false, false, 0, 0, true));
        assertFalse("the cloud camera screen holds the channel", PictureVisits.due(true, false, false, true, false, true, true, true, 0, 0, true));
        assertFalse("printer offline", PictureVisits.due(true, false, false, true, false, false, true, false, 0, 0, true));
    }
    @Test public void twoEmptyChannelsPauseVisitsForHalfAnHour() {
        assertFalse(due(true, false, false, 2, 10 * 60_000L, true));
        assertTrue(due(true, false, false, 2, 31 * 60_000L, true));
        assertTrue(due(true, false, false, 1, 0, true));
    }
}
