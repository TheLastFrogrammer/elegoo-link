package io.github.thelastfrogrammer.elink;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;

public class CloudLoginTest {
    private static final class Host implements CloudLogin.Host {
        CloudLogin.Account account; final List<String> failures = new ArrayList<>(), opened = new ArrayList<>();
        public void signedIn(CloudLogin.Account account) { this.account = account; }
        public void failed(String message) { failures.add(message); }
        public void openExternal(String url) { opened.add(url); }
    }
    private static JSONObject payload(String script) throws Exception {
        assertTrue(script, script.startsWith("HandleStudio(") && script.endsWith(")"));
        return new JSONObject(script.substring("HandleStudio(".length(), script.length() - 1));
    }
    private static String request(String id, String method, JSONObject params) throws Exception {
        return new JSONObject().put("id", id).put("method", method).put("type", "request").put("params", params).toString();
    }

    @Test public void readyIsAcknowledgedAndReceivesTheDeviceId() throws Exception {
        List<String> replies = new CloudLogin.Bridge("abc123", new Host()).receive(request("req-1", "report.ready", new JSONObject()));
        assertEquals(2, replies.size());
        JSONObject response = payload(replies.get(0));
        assertEquals("req-1", response.getString("id")); assertEquals("response", response.getString("type")); assertEquals(0, response.getInt("code"));
        JSONObject event = payload(replies.get(1));
        assertEquals("event", event.getString("type")); assertEquals("client.setDeviceId", event.getString("method"));
        assertEquals("abc123", event.getJSONObject("data").getString("deviceId"));
    }

    @Test public void userInfoHandsOverTheAccountAndIsAcknowledged() throws Exception {
        Host host = new Host();
        JSONObject params = new JSONObject().put("userId", "1234567").put("accessToken", "access").put("refreshToken", "refresh")
            .put("accessTokenExpireTime", 1_800_000_000L).put("refreshTokenExpireTime", 1_900_000_000_000L).put("nickname", "Maker");
        List<String> replies = new CloudLogin.Bridge("d", host).receive(request("req-2", "report.userInfo", params));
        assertEquals(0, payload(replies.get(0)).getInt("code"));
        assertEquals("1234567", host.account.userId); assertEquals("access", host.account.accessToken); assertEquals("refresh", host.account.refreshToken);
        assertEquals(1_800_000_000L, host.account.accessExpires);
        String summary = host.account.summary();
        assertTrue(summary, summary.startsWith("Maker (user …4567)"));
        assertFalse("Summary must not include tokens", summary.contains("access ") && summary.contains("refresh"));
        assertFalse(summary.contains("refresh\"")); assertFalse(summary.contains("access\""));
        CloudLogin.Account copy = CloudLogin.Account.fromJson(host.account.toJson());
        assertEquals(host.account.refreshExpires, copy.refreshExpires); assertEquals("Maker", copy.nickname);
    }

    @Test public void incompleteUserInfoIsRejectedAndNothingIsHandedOver() throws Exception {
        Host host = new Host();
        List<String> replies = new CloudLogin.Bridge("d", host).receive(request("req-3", "report.userInfo", new JSONObject().put("userId", "1")));
        assertNull(host.account); assertEquals(1, host.failures.size());
        assertNotEquals(0, payload(replies.get(0)).getInt("code"));
    }

    @Test public void websiteOpenOnlyLeavesForHttpsAndUnknownMethodsAreRefused() throws Exception {
        Host host = new Host(); CloudLogin.Bridge bridge = new CloudLogin.Bridge("d", host);
        bridge.receive(request("a", "report.websiteOpen", new JSONObject().put("url", "https://www.elegoo.com/privacy")));
        bridge.receive(request("b", "report.websiteOpen", new JSONObject().put("url", "intent://evil")));
        assertEquals(Collections.singletonList("https://www.elegoo.com/privacy"), host.opened);
        assertEquals(404, payload(bridge.receive(request("c", "printer.delete", new JSONObject())).get(0)).getInt("code"));
    }

    @Test public void responsesAndMalformedMessagesAreIgnored() throws Exception {
        CloudLogin.Bridge bridge = new CloudLogin.Bridge("d", new Host());
        assertTrue(bridge.receive("not json").isEmpty());
        assertTrue(bridge.receive(new JSONObject().put("id", "x").put("type", "response").put("method", "report.ready").toString()).isEmpty());
    }

    @Test public void repliesCannotBreakOutOfTheScriptCall() throws Exception {
        String script = new CloudLogin.Bridge("d", new Host()).receive(request("x \");alert(1)//", "report.notLogged", new JSONObject())).get(0);
        assertFalse(script.contains(" "));
        assertEquals("x \");alert(1)//", payload(script).getString("id"));
    }

    @Test public void onlyElegooAccountOriginsAreTrusted() {
        assertTrue(CloudLogin.trusted("https://account.elegoo.com/account/slicer-login?x=1"));
        assertTrue(CloudLogin.trusted("https://account.elegoo.com.cn/account/slicer-login"));
        assertFalse(CloudLogin.trusted("http://account.elegoo.com/account/slicer-login"));
        assertFalse(CloudLogin.trusted("https://account.elegoo.com.evil.example/"));
        assertFalse(CloudLogin.trusted("https://account.elegoo.com@evil.example/"));
        assertFalse(CloudLogin.trusted("https://account.elegoo.com:8443/"));
        assertFalse(CloudLogin.trusted("https://accounts.google.com/"));
        assertFalse(CloudLogin.trusted(null));
    }

    @Test public void loginUrlCarriesRegionThemeAndDevice() {
        assertEquals("https://account.elegoo.com/account/slicer-login?language=en&region=US&theme=dark&deviceId=abc",
            CloudLogin.url(false, "en", "US", "abc", true));
        assertTrue(CloudLogin.url(true, "zh-CN", "CN", "abc", false).startsWith(CloudLogin.CHINA_URL + "?language=zh-CN&region=CN&theme=light"));
        assertFalse(CloudLogin.url(false, "en", "", "abc", false).contains("region="));
    }
}
