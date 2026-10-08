package io.github.thelastfrogrammer.elink;

import org.junit.Ignore;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

/** Failure audit, cloud sign-in: expired or revoked tokens, a refused or failing renewal, and an offline printer. */
public class FailureCloudAccountTest {
    private static final long NOW = 1_800_000_000_000L;

    private static CloudLogin.Account expired() { return new CloudLogin.Account("42", "Maker", "old-access", "old-refresh", 1, 0); }
    private static CloudApi api(AtomicInteger refreshes, int refreshStatus) {
        return new CloudApi(false, expired(), "agent", (method, url, headers, body) -> {
            if (url.endsWith("/token/refresh")) { refreshes.incrementAndGet(); return new CloudApi.Response(refreshStatus, "{}"); }
            return new CloudApi.Response(200, "{\"code\":0,\"data\":[]}");
        });
    }

    /** PrinterService polls every 15-60 s; each poll goes through authorized(), which renews again while the token stays expired. */
    @Ignore("demonstrates: MEDIUM - a refused renewal is retried by every later request (every poll, 15-60 s, all night with background watching) instead of stopping at the first refusal")
    @Test public void aRefusedRenewalIsNotRepeatedOnEveryRequest() {
        AtomicInteger refreshes = new AtomicInteger(); CloudApi api = api(refreshes, 401);
        for (int i = 0; i < 3; i++) try { api.devices(); fail("sign-in is dead"); } catch (IOException expected) { }
        assertEquals("one refused renewal should mark the sign-in dead until the user signs in again", 1, refreshes.get());
    }

    /** refresh() turns every CloudException (HTTP 5xx, rate limit, any non-zero code) into "Sign out and sign in again". */
    @Ignore("demonstrates: MEDIUM - an Elegoo outage (HTTP 503) on the renewal endpoint is reported as an expired sign-in ('Sign out and sign in again')")
    @Test public void aServerErrorDuringRenewalIsNotReportedAsAnExpiredSignIn() {
        CloudApi api = api(new AtomicInteger(), 503);
        try { api.devices(); fail(); }
        catch (IOException error) {
            assertFalse(error.getMessage(), error.getMessage().contains("Sign out and sign in again"));
            assertFalse("must not be flagged as unauthorized", error instanceof CloudApi.CloudException && ((CloudApi.CloudException) error).unauthorized);
        }
    }

    /**
     * onlineState keeps the previous answer when the online endpoint says -1 (it refuses some accounts). With no cloud MQTT
     * "offline" push, a printer that lost power keeps "online" for ever although its newest report is hours old; the app then shows
     * the frozen print as live, controls stay enabled, and "Updated N s ago" counts from the app's own poll.
     */
    @Ignore("demonstrates: HIGH - printer power loss watched through the cloud: online state stays 1 for ever when the online endpoint answers -1 and no MQTT offline push arrives (reported status 3 h old)")
    @Test public void aThreeHourOldCloudReportIsNotTreatedAsOnline() {
        assertNotEquals(1, PrinterService.onlineState(-1, 1, NOW - 3 * 3_600_000L, NOW));
    }

    /** Works well: with a fresh report and an unknown endpoint answer the printer counts as online; a stale one does not (only when nothing was known before). */
    @Test public void onlyAnUnknownStartingStateUsesTheReportAge() {
        assertEquals(1, PrinterService.onlineState(-1, -1, NOW - 30_000, NOW));
        assertEquals(-1, PrinterService.onlineState(-1, -1, NOW - 3 * 3_600_000L, NOW));
    }
}
