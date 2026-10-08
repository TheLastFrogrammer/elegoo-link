package io.github.thelastfrogrammer.elink;

import org.junit.Test;
import static org.junit.Assert.*;

public class CloudOnlineStateTest {
    private static final long NOW = 1_800_000_000_000L;

    @Test public void theOnlineEndpointDecidesWhenItAnswers() {
        assertEquals(1, PrinterService.onlineState(1, 0, 0, NOW));
        assertEquals(0, PrinterService.onlineState(0, 1, NOW, NOW));
    }

    @Test public void anUnknownAnswerKeepsWhatLiveUpdatesShowed() {
        assertEquals(1, PrinterService.onlineState(-1, 1, 0, NOW));
        assertEquals(0, PrinterService.onlineState(-1, 0, NOW, NOW));
    }

    @Test public void otherwiseARecentStatusReportCountsAsOnline() {
        assertEquals(1, PrinterService.onlineState(-1, -1, NOW - 30_000, NOW));
        assertEquals(1, PrinterService.onlineState(-1, -1, (NOW - 30_000) / 1000, NOW)); // seconds, as some reports give
        assertEquals(-1, PrinterService.onlineState(-1, -1, NOW - 10 * 60_000, NOW));
        assertEquals(-1, PrinterService.onlineState(-1, -1, 0, NOW));
    }
}
