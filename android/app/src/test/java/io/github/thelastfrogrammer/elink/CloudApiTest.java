package io.github.thelastfrogrammer.elink;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.IOException;
import java.util.*;

public class CloudApiTest {
    private static final class Call { String method, url, body; Map<String, String> headers; }
    private static final class FakeCloud implements CloudApi.Transport {
        final List<Call> calls = new ArrayList<>();
        final Deque<CloudApi.Response> replies = new ArrayDeque<>();
        public CloudApi.Response send(String method, String url, Map<String, String> headers, String body) throws IOException {
            Call call = new Call(); call.method = method; call.url = url; call.headers = headers; call.body = body; calls.add(call);
            if (replies.isEmpty()) throw new IOException("unexpected request " + url);
            return replies.removeFirst();
        }
        FakeCloud reply(int status, String body) { replies.add(new CloudApi.Response(status, body)); return this; }
        FakeCloud ok(Object data) throws Exception { return reply(200, new JSONObject().put("code", 0).put("data", data).toString()); }
    }
    private static CloudLogin.Account account(long accessExpiresSeconds) {
        return new CloudLogin.Account("42", "Maker", "old-access", "old-refresh", accessExpiresSeconds, 0);
    }
    private static long inAnHour() { return System.currentTimeMillis() / 1000 + 3600; }
    private static JSONObject field(String key, String value, long time) throws Exception {
        return new JSONObject().put("reportLinkKey", key).put("reportValue", value).put("updateTime", time).put("reportDataRefId", "1");
    }

    @Test public void listsBoundPrintersWithBearerTokenAndAgentOnTheGlobalHost() throws Exception {
        FakeCloud cloud = new FakeCloud().ok(new JSONArray()
            .put(new JSONObject().put("serialNo", "SN0001").put("pcode", "CC2").put("deviceName", "Workshop"))
            .put(new JSONObject().put("pcode", "missing serial")));
        List<CloudApi.Device> devices = new CloudApi(false, account(inAnHour()), "agent/1", cloud).devices();
        assertEquals(1, devices.size()); assertEquals("SN0001", devices.get(0).serial); assertEquals("Workshop", devices.get(0).name);
        Call call = cloud.calls.get(0);
        assertEquals("GET", call.method); assertEquals("https://matrix.elegoo.com/api/v1/device-management-server/device/list", call.url);
        assertEquals("Bearer old-access", call.headers.get("Authorization")); assertEquals("agent/1", call.headers.get("User-Agent"));
    }

    @Test public void chinaAccountsUseTheChinaHostAndOnlineStatusPostsTheSerial() throws Exception {
        FakeCloud cloud = new FakeCloud().ok(new JSONObject().put("onlineStatus", 1));
        assertEquals(1, new CloudApi(true, account(inAnHour()), "a", cloud).online("SN 1"));
        Call call = cloud.calls.get(0);
        assertEquals("https://matrix.elegoo.com.cn/api/v1/device-management-server/device-register/online-status", call.url);
        assertEquals("SN 1", new JSONObject(call.body).getString("deviceCode")); assertEquals("application/json", call.headers.get("Content-Type"));
    }

    @Test public void statusIsRebuiltWithTheSdkTypingRules() throws Exception {
        JSONObject data = new JSONObject()
            .put("machine_status", new JSONArray().put(field("status", "2", 1_700_000_000_000L)).put(field("sub_status", "2075", 1_700_000_000_000L))
                .put(field("progress", "41", 1_700_000_005_000L)).put(field("exception_status", "[3]", 0)))
            .put("extruder", new JSONArray().put(field("temperature", "219.6", 0)).put(field("target", "220", 0)))
            .put("print_status", new JSONArray().put(field("filename", "123", 0)).put(field("current_layer", "12", 0)).put(field("remaining_time_sec", "3720", 0)))
            .put("fans", new JSONArray().put(field("part", "{\"speed\":128}", 0)).put(field("enabled", "true", 0)).put(field("note", "plain text", 0)));
        FakeCloud cloud = new FakeCloud().ok(data);
        CloudApi.Snapshot snapshot = new CloudApi(false, account(inAnHour()), "a", cloud).status("SN/1");
        assertTrue(cloud.calls.get(0).url.endsWith("report-data/list?deviceCode=SN%2F1"));
        JSONObject status = snapshot.status;
        assertEquals(2L, status.getJSONObject("machine_status").get("status"));
        assertEquals(0, status.getJSONObject("machine_status").getJSONArray("exception_status").length());
        assertEquals(219.6, status.getJSONObject("extruder").getDouble("temperature"), 0.001);
        assertEquals("123", status.getJSONObject("print_status").get("filename"));
        assertEquals(128, status.getJSONObject("fans").getJSONObject("part").getInt("speed"));
        assertEquals(Boolean.TRUE, status.getJSONObject("fans").get("enabled"));
        assertEquals("plain text", status.getJSONObject("fans").get("note"));
        assertEquals(1_700_000_005_000L, snapshot.reportedAt);
        String overview = StatusPresentation.overview(status);
        assertTrue(overview, overview.startsWith("Printing · 41%"));
        assertTrue(overview, overview.contains("Nozzle 219.6°C / 220°C"));
        assertTrue(overview, overview.contains("1h 2m remaining"));
    }

    private static long expired() { return System.currentTimeMillis() / 1000 - 5; }

    @Test public void expiredTokenIsRenewedLikeTheAccountPageAndTheNewTokenIsUsed() throws Exception {
        FakeCloud cloud = new FakeCloud()
            .ok(new JSONObject().put("token", "new-access").put("refreshToken", "new-refresh").put("accessTokenExpireTime", inAnHour() * 1000)
                .put("refreshTokenExpireTime", inAnHour() * 1000).put("accountId", "42"))
            .ok(new JSONArray());
        CloudApi api = new CloudApi(false, account(expired()), "a", cloud);
        api.devices();
        Call refresh = cloud.calls.get(0);
        assertTrue(refresh.url.endsWith("/account-auth/token/refresh"));
        assertEquals("old-refresh", new JSONObject(refresh.body).getString("refreshToken"));
        assertEquals("account", new JSONObject(refresh.body).getString("clientId"));
        assertNull("The account page renews without a bearer token", refresh.headers.get("Authorization"));
        assertEquals("Bearer new-access", cloud.calls.get(1).headers.get("Authorization"));
        assertEquals("new-refresh", api.account().refreshToken); assertEquals("Maker", api.account().nickname);
        assertEquals(inAnHour() * 1000, api.account().accessExpires);
        assertTrue(api.takeTrace().get(0).startsWith("Access token expired "));
    }

    @Test public void sdkFieldNamesFromRenewalAreAlsoAccepted() throws Exception {
        FakeCloud cloud = new FakeCloud().ok(new JSONObject().put("accessToken", "n").put("expiresTime", inAnHour()).put("refreshExpiresTime", 7L));
        CloudApi api = new CloudApi(false, account(expired()), "a", cloud);
        api.refresh();
        assertEquals("n", api.account().accessToken); assertEquals(inAnHour(), api.account().accessExpires); assertEquals(7L, api.account().refreshExpires);
        assertEquals("old-refresh", api.account().refreshToken);
    }

    @Test public void validTokenIsNeverRenewedEvenWhenRefused() throws Exception {
        // A refused renewal ended the sign-in on the phone, so a refusal must not trigger one.
        for (CloudApi.Response refusal : Arrays.asList(new CloudApi.Response(401, ""), new CloudApi.Response(200, "{\"code\":401,\"msg\":\"\u8d26\u53f7\u672a\u767b\u5f55\"}"))) {
            FakeCloud cloud = new FakeCloud(); cloud.replies.add(refusal);
            try { new CloudApi(false, account(inAnHour()), "a", cloud).devices(); fail(); }
            catch (CloudApi.CloudException error) {
                assertTrue(error.unauthorized); assertTrue(error.getMessage(), error.getMessage().contains("Sign out and sign in again"));
                assertFalse(error.getMessage().contains("old-"));
            }
            assertEquals(1, cloud.calls.size());
        }
    }

    @Test public void refusedRenewalExplainsAndTraceShowsEachStepWithoutSecrets() throws Exception {
        FakeCloud cloud = new FakeCloud().reply(200, "{\"code\":400,\"msg\":\"\u65e0\u6548\u7684\u5237\u65b0\u4ee4\u724c\"}");
        CloudApi api = new CloudApi(false, account(expired()), "a", cloud);
        try { api.devices(); fail(); }
        catch (CloudApi.CloudException error) {
            assertTrue(error.unauthorized);
            assertTrue(error.getMessage(), error.getMessage().startsWith("Renewing the Elegoo sign-in failed (Elegoo cloud error 400"));
        }
        List<String> trace = api.takeTrace();
        assertEquals(2, trace.size());
        assertTrue(trace.get(0), trace.get(0).startsWith("Access token expired "));
        assertTrue(trace.get(1), trace.get(1).startsWith("POST account-auth/token/refresh → HTTP 200, code 400"));
        for (String line : trace) assertFalse(line, line.contains("old-access") || line.contains("old-refresh"));
        assertTrue(api.takeTrace().isEmpty());
        assertEquals("not reported", CloudApi.when(0));
    }

    @Test public void serverErrorsAreReportedWithCleanMessages() throws Exception {
        FakeCloud cloud = new FakeCloud().reply(200, "{\"code\":10086,\"message\":\"device\\nnot bound\"}");
        try { new CloudApi(false, account(inAnHour()), "a", cloud).devices(); fail(); }
        catch (CloudApi.CloudException error) { assertFalse(error.unauthorized); assertEquals("Elegoo cloud error 10086: device not bound", error.getMessage()); }
        FakeCloud broken = new FakeCloud().reply(502, "<html>");
        try { new CloudApi(false, account(inAnHour()), "a", broken).devices(); fail(); }
        catch (CloudApi.CloudException error) { assertEquals("Elegoo cloud returned HTTP 502.", error.getMessage()); }
    }

    @Test public void tokenDescriptionShowsClaimsButNeverTheToken() throws Exception {
        java.util.Base64.Encoder b64 = java.util.Base64.getUrlEncoder().withoutPadding();
        String payload = new JSONObject().put("clientId", "account").put("accountId", "42").put("email", "me@example.com").put("exp", 1).toString();
        String jwt = b64.encodeToString("{\"alg\":\"HS256\"}".getBytes("UTF-8")) + "." + b64.encodeToString(payload.getBytes("UTF-8")) + ".c2lnbmF0dXJl";
        String text = CloudApi.describe(jwt);
        assertTrue(text, text.startsWith("JWT with claims: accountId, clientId, email, exp"));
        assertTrue(text, text.contains("clientId = account"));
        assertFalse(text, text.contains("me@example.com") || text.contains(jwt) || text.contains("c2lnbmF0dXJl"));
        assertEquals("opaque token, 6 characters", CloudApi.describe("abcdef"));
    }

    @Test public void accountCheckIsTracedWithoutRenewingAndRequestsCarryPageHeaders() throws Exception {
        FakeCloud cloud = new FakeCloud().reply(200, "{\"code\":401,\"msg\":\"no\"}");
        CloudApi api = new CloudApi(false, account(inAnHour()), "a", cloud); api.language("de");
        api.accountCheck();
        assertEquals(1, cloud.calls.size());
        assertEquals("GET account-info/account → HTTP 200, code 401 no", api.takeTrace().get(0));
        assertEquals("de", cloud.calls.get(0).headers.get("User-Lang"));
        assertEquals(36, cloud.calls.get(0).headers.get("X-Client-Request-Id").length());
    }

    @Test public void renewedTokensKeepTheSignInNote() throws Exception {
        CloudLogin.Account signedIn = CloudLogin.Account.fromReport(new JSONObject().put("userId", "42").put("accessToken", "a").put("refreshToken", "r").put("bindCode", "x"));
        assertEquals("Sign-in page reported: accessToken, bindCode, refreshToken, userId", signedIn.signInNote);
        CloudLogin.Account expiring = signedIn.withTokens("42", "a", "r", expired(), 0);
        FakeCloud cloud = new FakeCloud().ok(new JSONObject().put("accessToken", "n").put("expiresTime", inAnHour())).ok(new JSONArray());
        CloudApi api = new CloudApi(false, expiring, "a", cloud); api.devices();
        assertEquals(signedIn.signInNote, api.account().signInNote);
        assertEquals(signedIn.signInNote, CloudLogin.Account.fromJson(api.account().toJson()).signInNote);
        assertTrue(CloudLogin.Account.fromJson(new JSONObject().put("userId", "1").put("accessToken", "t")).signInNote.startsWith("Signed in with an earlier version"));
    }

    @Test public void refusedOptionalEndpointsDoNotRenewOrAbort() throws Exception {
        // Sequence seen on the phone after a fresh sign-in: device/list accepted, online-status refused.
        String refused = "{\"code\":401,\"msg\":\"no\"}";
        FakeCloud cloud = new FakeCloud().ok(new JSONArray().put(new JSONObject().put("serialNo", "SN1")))
            .reply(200, refused).reply(200, refused);
        CloudApi api = new CloudApi(false, account(inAnHour()), "a", cloud);
        assertEquals(1, api.devices().size());
        assertEquals(-1, api.online("SN1"));
        try { api.status("SN1"); fail(); } catch (CloudApi.CloudException error) { assertTrue(error.unauthorized); }
        assertEquals(3, cloud.calls.size());
        for (Call call : cloud.calls) assertFalse(call.url.contains("token/refresh"));
    }

    @Test public void transportRefusesPlainHttp() {
        try { CloudApi.https("GET", "http://matrix.elegoo.com/", new HashMap<>(), null); fail(); } catch (IOException expected) { }
    }

    @Test public void reportAgeIsReadable() {
        assertEquals("5 s", CloudStatusActivity.age(5_000)); assertEquals("3 min", CloudStatusActivity.age(180_000)); assertEquals("2.0 h", CloudStatusActivity.age(7_200_000));
    }
}
