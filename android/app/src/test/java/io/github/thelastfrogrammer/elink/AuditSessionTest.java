package io.github.thelastfrogrammer.elink;

import static org.junit.Assert.*;

import android.content.Context;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Audit F04 and F05: cloud renewals happen once, and only land on the account that started them. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class AuditSessionTest {
    @Before public void key() { CredentialStore.testKey = new SecretKeySpec(new byte[32], "AES"); }
    @After public void clear() { CredentialStore.testKey = null; }

    @Test public void concurrentExpiredRequestsRenewOnce() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1); List<String> used = new CopyOnWriteArrayList<>();
        AtomicInteger refreshes = new AtomicInteger(); List<String> bearers = new CopyOnWriteArrayList<>();
        CloudApi api = new CloudApi(false, new CloudLogin.Account("42", "Maker", "old", "refresh-once", 1, 0), "fixture",
            (method, url, headers, body) -> {
                if (url.endsWith("/token/refresh")) {
                    used.add(body.replaceAll(".*\"refreshToken\":\"([^\"]*)\".*", "$1")); refreshes.incrementAndGet(); entered.countDown();
                    try { if (!release.await(2, TimeUnit.SECONDS)) throw new IOException("fixture timeout"); } catch (InterruptedException e) { throw new IOException(e); }
                    return new CloudApi.Response(200, "{\"code\":0,\"data\":{\"token\":\"new\",\"refreshToken\":\"rotated\",\"accessTokenExpireTime\":9999999999}}");
                }
                bearers.add(headers.get("Authorization"));
                return new CloudApi.Response(200, "{\"code\":0,\"data\":[]}");
            });
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<?> a = threads.submit(() -> { try { api.devices(); } catch (IOException e) { throw new RuntimeException(e); } });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            Future<?> b = threads.submit(() -> { try { api.devices(); } catch (IOException e) { throw new RuntimeException(e); } });
            Thread.sleep(200); release.countDown();
            a.get(3, TimeUnit.SECONDS); b.get(3, TimeUnit.SECONDS);
            assertEquals(1, refreshes.get()); assertEquals(Collections.singletonList("refresh-once"), used);
            assertEquals(Arrays.asList("Bearer new", "Bearer new"), bearers);
        } finally { release.countDown(); threads.shutdownNow(); }
    }

    @Test public void aRenewalAfterSigningInAsSomeoneElseIsNotSaved() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        CloudAccountStore store = new CloudAccountStore(context);
        CloudLogin.Account a = new CloudLogin.Account("A", "Ann", "a1", "ra", 1, 0), renewedA = a.withTokens("A", "a2", "ra2", 99, 0);
        CloudLogin.Account b = new CloudLogin.Account("B", "Bo", "b1", "rb", 1, 0);
        store.save(b);
        PrinterService.saveRenewed(store, a, renewedA);
        assertEquals("B", store.load().userId); assertEquals("b1", store.load().accessToken);
        store.save(a);
        PrinterService.saveRenewed(store, a, renewedA);
        assertEquals("a2", store.load().accessToken);
        store.forget();
        PrinterService.saveRenewed(store, renewedA, renewedA.withTokens("A", "a3", "ra3", 99, 0));
        assertNull("signed out: nothing comes back", store.load());
    }
}
