package io.github.thelastfrogrammer.elink;

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
    @Test public void aRefusedRenewalIsNotRepeatedOnEveryRequest() {
        AtomicInteger refreshes = new AtomicInteger(); CloudApi api = api(refreshes, 401);
        for (int i = 0; i < 3; i++) try { api.devices(); fail("sign-in is dead"); } catch (IOException expected) { }
        assertEquals("one refused renewal should mark the sign-in dead until the user signs in again", 1, refreshes.get());
    }

    /** refresh() turns every CloudException (HTTP 5xx, rate limit, any non-zero code) into "Sign out and sign in again". */
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
    @Test public void aThreeHourOldCloudReportIsNotTreatedAsOnline() {
        assertNotEquals(1, PrinterService.onlineState(-1, 1, NOW - 3 * 3_600_000L, NOW));
    }

    /** Works well: with a fresh report and an unknown endpoint answer the printer counts as online; a stale one does not (only when nothing was known before). */
    @Test public void onlyAnUnknownStartingStateUsesTheReportAge() {
        assertEquals(1, PrinterService.onlineState(-1, -1, NOW - 30_000, NOW));
        assertEquals(-1, PrinterService.onlineState(-1, -1, NOW - 3 * 3_600_000L, NOW));
    }

    /** A renewal made for any reason (poll, Agora credential, upload) is reported so it can be saved. */
    @Test public void everyRenewalIsHandedToTheOwnerToSave() throws Exception {
        CloudApi api = new CloudApi(false, expired(), "agent", (method, url, headers, body) -> url.endsWith("/token/refresh")
            ? new CloudApi.Response(200, "{\"code\":0,\"data\":{\"token\":\"new-access\",\"refreshToken\":\"new-refresh\",\"accessTokenExpireTime\":" + (System.currentTimeMillis() + 3_600_000) + ",\"accountId\":\"42\"}}")
            : new CloudApi.Response(200, "{\"code\":0,\"data\":[]}"));
        java.util.List<String> saved = new java.util.ArrayList<>();
        api.onAccountChanged(account -> saved.add(account.accessToken + "/" + account.refreshToken));
        api.devices();
        assertEquals(java.util.Collections.singletonList("new-access/new-refresh"), saved);
    }

    @Test public void onlyARealRefusalMarksTheSignInAsEnded() {
        CloudApi refused = api(new AtomicInteger(), 401), outage = api(new AtomicInteger(), 503);
        try { refused.devices(); } catch (IOException expected) { assertTrue(expected.getMessage(), expected.getMessage().contains("Sign out and sign in again")); }
        try { outage.devices(); } catch (IOException expected) { assertTrue(expected.getMessage(), expected.getMessage().contains("having trouble")); }
        assertTrue(refused.needsSignIn());
        assertFalse(outage.needsSignIn());
    }

    @Test public void statusStaysStaleUntilItIsFetchedAfterAnAcknowledgement() {
        assertTrue(PrinterService.cloudSettled(1_000, 0));
        assertFalse("a poll 2 s after the ack may still show the old state", PrinterService.cloudSettled(12_000, 10_000));
        assertTrue(PrinterService.cloudSettled(16_000, 10_000));
    }
}
