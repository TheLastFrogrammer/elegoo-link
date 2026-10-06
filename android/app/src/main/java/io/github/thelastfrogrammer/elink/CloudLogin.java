package io.github.thelastfrogrammer.elink;

import java.net.URI;
import java.util.*;
import org.json.JSONObject;

/**
 * Elegoo account sign-in through the same web page ElegooSlicer embeds (see CLOUD_LOGIN.md).
 * The page sends IPC requests with window.wx.postMessage(json) and expects replies through HandleStudio(json).
 */
public final class CloudLogin {
    public static final String GLOBAL_URL = "https://account.elegoo.com/account/slicer-login";
    public static final String CHINA_URL = "https://account.elegoo.com.cn/account/slicer-login";
    /** Only these origins receive the bridge or replies; the page itself may navigate elsewhere (for example single sign-on). */
    public static final Set<String> ORIGINS = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList("https://account.elegoo.com", "https://account.elegoo.com.cn")));
    /** The page only uses the wx bridge when the user agent names ElegooSlicer; version matches ElegooSlicer 2d507e39. */
    public static final String SLICER_AGENT = "ElegooSlicer/1.5.3.5";

    private CloudLogin() { }

    public static String url(boolean china, String language, String region, String deviceId, boolean dark) {
        StringBuilder url = new StringBuilder(china ? CHINA_URL : GLOBAL_URL).append("?language=").append(param(language));
        if (!region.isEmpty()) url.append("&region=").append(param(region));
        return url.append("&theme=").append(dark ? "dark" : "light").append("&deviceId=").append(param(deviceId)).toString();
    }
    private static String param(String value) {
        try { return java.net.URLEncoder.encode(value, "UTF-8"); } catch (java.io.UnsupportedEncodingException impossible) { throw new AssertionError(impossible); }
    }

    /** True only for an https page on an allowed origin with no user info or explicit port. */
    public static boolean trusted(String url) {
        if (url == null) return false;
        try {
            URI uri = new URI(url);
            return "https".equals(uri.getScheme()) && uri.getRawUserInfo() == null && uri.getPort() == -1 && uri.getHost() != null
                && ORIGINS.contains("https://" + uri.getHost().toLowerCase(Locale.ROOT));
        } catch (Exception error) { return false; }
    }

    public static final class Account {
        public final String userId, nickname, accessToken, refreshToken;
        public final long accessExpires, refreshExpires;
        Account(String userId, String nickname, String accessToken, String refreshToken, long accessExpires, long refreshExpires) {
            this.userId = userId; this.nickname = nickname; this.accessToken = accessToken; this.refreshToken = refreshToken;
            this.accessExpires = accessExpires; this.refreshExpires = refreshExpires;
        }
        static Account fromReport(JSONObject params) {
            String nickname = params.optString("nickname", "");
            if (nickname.isEmpty()) nickname = params.optString("username", "");
            return new Account(params.optString("userId", "").trim(), nickname, params.optString("accessToken", ""), params.optString("refreshToken", ""),
                params.optLong("accessTokenExpireTime", 0), params.optLong("refreshTokenExpireTime", 0));
        }
        public JSONObject toJson() throws Exception {
            return new JSONObject().put("userId", userId).put("nickname", nickname).put("accessToken", accessToken).put("refreshToken", refreshToken)
                .put("accessTokenExpireTime", accessExpires).put("refreshTokenExpireTime", refreshExpires);
        }
        public static Account fromJson(JSONObject json) { return fromReport(json); }
        boolean complete() { return !userId.isEmpty() && !accessToken.isEmpty(); }
        /** Secret-free description: never includes tokens. */
        public String summary() {
            String who = nickname.isEmpty() ? "Elegoo account" : nickname;
            String id = userId.length() > 4 ? "…" + userId.substring(userId.length() - 4) : userId;
            return who + " (user " + id + ")" + (accessExpires > 0 ? " · access token valid until " + date(accessExpires) : "")
                + (refreshToken.isEmpty() ? " · no refresh token" : "");
        }
        private static String date(long time) {
            // The page reports epoch seconds or milliseconds depending on version.
            long millis = time < 100_000_000_000L ? time * 1000 : time;
            return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date(millis));
        }
    }

    public interface Host {
        void signedIn(Account account);
        void failed(String message);
        void openExternal(String url);
    }

    /** Turns one page message into the HandleStudio(...) scripts to run in reply. */
    public static final class Bridge {
        private final String deviceId;
        private final Host host;
        private int events;
        public Bridge(String deviceId, Host host) { this.deviceId = deviceId; this.host = host; }

        public List<String> receive(String raw) {
            List<String> replies = new ArrayList<>();
            JSONObject message;
            try { message = new JSONObject(raw); } catch (Exception error) { return replies; }
            if (!"request".equals(message.optString("type", "request"))) return replies;
            String id = message.optString("id", ""), method = message.optString("method", "");
            JSONObject params = message.optJSONObject("params");
            if (params == null) params = new JSONObject();
            try {
                switch (method) {
                    case "report.ready":
                        replies.add(response(id, method, 0, "success"));
                        replies.add(script(new JSONObject().put("method", "client.setDeviceId").put("type", "event")
                            .put("id", "link-workshop-" + ++events).put("data", new JSONObject().put("deviceId", deviceId))));
                        break;
                    case "report.userInfo":
                        Account account = Account.fromReport(params);
                        if (!account.complete()) {
                            host.failed("Elegoo did not return a complete sign-in. Nothing was saved.");
                            replies.add(response(id, method, -1, "Incomplete account information"));
                        } else {
                            host.signedIn(account);
                            replies.add(response(id, method, 0, "success"));
                        }
                        break;
                    case "report.loginFailed":
                        host.failed("Elegoo reported that sign-in failed.");
                        replies.add(response(id, method, 0, "success"));
                        break;
                    case "report.websiteOpen":
                        String url = params.optString("url", "");
                        if (url.startsWith("https://")) host.openExternal(url);
                        replies.add(response(id, method, 0, "success"));
                        break;
                    case "report.notLogged": case "reload": case "isLoading":
                        replies.add(response(id, method, 0, "success"));
                        break;
                    default:
                        replies.add(response(id, method, 404, "Unsupported method"));
                }
            } catch (Exception error) { replies.clear(); }
            return replies;
        }
        private static String response(String id, String method, int code, String text) throws Exception {
            return script(new JSONObject().put("id", id).put("method", method).put("type", "response").put("code", code)
                .put("message", text).put("data", new JSONObject()));
        }
        private static String script(JSONObject message) {
            // JSON is valid JavaScript except for these two line terminators inside strings.
            return "HandleStudio(" + message.toString().replace("\u2028", "\\u2028").replace("\u2029", "\\u2029") + ")";
        }
    }
}
