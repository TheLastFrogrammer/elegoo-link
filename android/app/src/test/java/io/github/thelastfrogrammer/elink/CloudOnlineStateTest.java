package io.github.thelastfrogrammer.elink;

import org.junit.Test;
import static org.junit.Assert.*;

public class CloudOnlineStateTest {
    private static final long NOW = 1_800_000_000_000L;

    @Test public void theOnlineEndpointDecidesWhenItAnswers() {
        assertEquals(1, PrinterService.onlineState(1, 0, 0, NOW));
        assertEquals(0, PrinterService.onlineState(0, 1, NOW, NOW));
    }

    @Test public void anUnknownAnswerKeepsOfflineButOnlyKeepsOnlineWhileThePrinterReports() {
        assertEquals(1, PrinterService.onlineState(-1, 1, NOW - 30_000, NOW));
        assertEquals(0, PrinterService.onlineState(-1, 0, NOW, NOW));
        assertEquals(0, PrinterService.onlineState(-1, 0, 0, NOW));
        assertEquals("no report time: online cannot be kept", -1, PrinterService.onlineState(-1, 1, 0, NOW));
        assertEquals(-1, PrinterService.onlineState(-1, 1, NOW - 10 * 60_000, NOW));
    }

    @Test public void otherwiseARecentStatusReportCountsAsOnline() {
        assertEquals(1, PrinterService.onlineState(-1, -1, NOW - 30_000, NOW));
        assertEquals(1, PrinterService.onlineState(-1, -1, (NOW - 30_000) / 1000, NOW)); // seconds, as some reports give
        assertEquals(-1, PrinterService.onlineState(-1, -1, NOW - 10 * 60_000, NOW));
        assertEquals(-1, PrinterService.onlineState(-1, -1, 0, NOW));
    }

    @Test public void withLiveUpdatesConnectedTheyDecideNotTheOnlineEndpoint() {
        // Seen on a CC2: the endpoint said offline once a minute while the printer printed and reported live.
        assertEquals(1, PrinterService.polledOnline(0, 1, NOW, NOW, true));
        assertEquals("an online answer is still believed", 1, PrinterService.polledOnline(1, 0, NOW, NOW, true));
        assertEquals("a live offline stands", 0, PrinterService.polledOnline(-1, 0, NOW, NOW, true));
        assertEquals("nothing live yet: the endpoint decides", 0, PrinterService.polledOnline(0, -1, NOW, NOW, true));
        assertEquals("without live updates the endpoint decides as before", 0, PrinterService.polledOnline(0, 1, NOW, NOW, false));
    }
}
