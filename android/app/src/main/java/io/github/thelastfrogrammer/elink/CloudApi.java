package io.github.thelastfrogrammer.elink;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Read-only Elegoo cloud HTTPS client, following the C++ SDK's HttpService (src/cloud/services/http_service.cpp).
 * Only reads: token refresh, bound printer list, online state and the last status the printer reported to the cloud.
 */
public final class CloudApi {
    public static final String GLOBAL = "https://matrix.elegoo.com", CHINA = "https://matrix.elegoo.com.cn";
    /**
     * Renew only once the access token has expired, as Elegoo's account page does. A refused renewal appears to end the
     * whole sign-in, so the app never renews speculatively.
     */
    static final long REFRESH_MARGIN_SECONDS = 0;
    /** The account page renews its tokens with this client ID, even for slicer sign-ins; the SDK's "Slicer" is refused. */
    static final String REFRESH_CLIENT_ID = "account";

    public static final class Response { final int status; final String body; public Response(int status, String body) { this.status = status; this.body = body; } }
    public interface Transport { Response send(String method, String url, Map<String, String> headers, String body) throws IOException; }

    public static final class CloudException extends IOException {
        final boolean unauthorized;
        CloudException(String message, boolean unauthorized) { super(message); this.unauthorized = unauthorized; }
    }

    public static final class Device {
        public final String serial, model, name;
        Device(String serial, String model, String name) { this.serial = serial; this.model = model; this.name = name; }
    }

    /** A rebuilt status in the same shape as the printer's LAN status, plus the newest report time (epoch ms, 0 if unknown). */
    public static final class Snapshot {
        public final JSONObject status; public final long reportedAt;
        Snapshot(JSONObject status, long reportedAt) { this.status = status; this.reportedAt = reportedAt; }
    }

    private final String base, userAgent;
    private String language = "en";
    private final Transport transport;
    private CloudLogin.Account account;
    private final List<String> trace = new ArrayList<>();

    public CloudApi(boolean china, CloudLogin.Account account, String userAgent, Transport transport) {
        this.base = china ? CHINA : GLOBAL; this.account = account; this.userAgent = userAgent; this.transport = transport;
    }
    public CloudLogin.Account account() { return account; }
    /** User agent for cloud requests: ElegooSlicer's product token plus this app's own. */
    public static String agent(android.content.Context context) {
        String version; try { version = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName; } catch (Exception error) { version = "dev"; }
        return CloudLogin.SLICER_AGENT + " (Android " + android.os.Build.VERSION.RELEASE + "; " + (android.os.Build.SUPPORTED_ABIS.length > 0 ? android.os.Build.SUPPORTED_ABIS[0] : "unknown") + ") LinkWorkshop/" + version;
    }
    public void language(String language) { if (language != null && !language.isEmpty()) this.language = language; }
    /** Secret-free record of requests since the last call: paths, HTTP status, server code and message, refresh reasons. */
    public synchronized List<String> takeTrace() { List<String> copy = new ArrayList<>(trace); trace.clear(); return copy; }
    private synchronized void note(String line) { if (trace.size() < 40) trace.add(line); }
    static String when(long time) {
        if (time <= 0) return "not reported";
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date(seconds(time) * 1000));
    }

    static long seconds(long time) { return time > 100_000_000_000L ? time / 1000 : time; }
    boolean needsRefresh(long nowSeconds) { return account.accessExpires > 0 && seconds(account.accessExpires) - nowSeconds < REFRESH_MARGIN_SECONDS; }

    /** Exchanges the refresh token for new tokens. The caller persists {@link #account()} afterwards. */
    public void refresh() throws IOException {
        if (account.refreshToken.isEmpty()) throw new CloudException("Elegoo sign-in has expired. Sign in again.", true);
        JSONObject data;
        try {
            // Like the account page: no Authorization header on renewal.
            data = call("POST", "/api/v1/account-center-server/account-auth/token/refresh",
                new JSONObject().put("refreshToken", account.refreshToken).put("clientId", REFRESH_CLIENT_ID).toString(), false).optJSONObject("data");
        } catch (CloudException error) {
            // Any refusal here means the saved sign-in cannot be renewed; only a new sign-in helps.
            throw new CloudException("Renewing the Elegoo sign-in failed (" + error.getMessage() + "). Sign out and sign in again in Settings.", true);
        } catch (org.json.JSONException impossible) { throw new IOException(impossible); }
        String access = data == null ? "" : data.optString("token", data.optString("accessToken", ""));
        if (access.isEmpty()) throw new CloudException("Elegoo returned no new sign-in token. Sign in again.", true);
        String userId = data.optString("accountId", "");
        // The page reads either naming for each field.
        long accessExpires = data.optLong("accessTokenExpireTime", data.optLong("expiresTime", 0));
        long refreshExpires = data.optLong("refreshTokenExpireTime", data.optLong("refreshExpiresTime", account.refreshExpires));
        account = account.withTokens(userId.isEmpty() ? account.userId : userId, access, data.optString("refreshToken", account.refreshToken), accessExpires, refreshExpires);
    }

    /** Checks the token against the account service only, without renewing it; the result is recorded in the trace. */
    public void accountCheck() {
        try { call("GET", "/api/v1/account-center-server/account-info/account", null, true); }
        catch (IOException error) { /* already traced */ }
    }

    /** Secret-free description of a token: for a JWT, its claim names and a few identifying claim values; never the signature. */
    static String describe(String token) {
        String[] parts = token.split("\\.");
        if (parts.length != 3) return "opaque token, " + token.length() + " characters";
        try {
            JSONObject claims = new JSONObject(new String(java.util.Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
            List<String> keys = new ArrayList<>();
            for (Iterator<String> it = claims.keys(); it.hasNext(); ) keys.add(it.next());
            Collections.sort(keys);
            StringBuilder text = new StringBuilder("JWT with claims: ").append(String.join(", ", keys));
            for (String key : Arrays.asList("iss", "aud", "azp", "client_id", "clientId", "cid", "client", "source", "platform", "scope", "loginType", "login_type"))
                if (claims.has(key)) text.append("\n  ").append(key).append(" = ").append(StatusPresentation.clean(String.valueOf(claims.opt(key))));
            return text.toString();
        } catch (Exception error) { return "three-part token that is not readable JSON, " + token.length() + " characters"; }
    }

    /** Agora identity Elegoo issues for live commands; the same one ElegooSlicer uses (source=slicer). */
    public static final class AgoraCredential {
        public final String userId, rtmUserId, rtmToken, rtcUserId, rtcToken;
        AgoraCredential(String userId, String rtmUserId, String rtmToken) { this(userId, rtmUserId, rtmToken, "", ""); }
        AgoraCredential(String userId, String rtmUserId, String rtmToken, String rtcUserId, String rtcToken) {
            this.userId = userId; this.rtmUserId = rtmUserId; this.rtmToken = rtmToken; this.rtcUserId = rtcUserId; this.rtcToken = rtcToken;
        }
    }
    public AgoraCredential agoraCredential() throws IOException {
        JSONObject data = authorized("GET", "/api/v1/device-management-server/device/list/agora-token?source=slicer", null, true).optJSONObject("data");
        JSONObject token = data == null ? null : data.optJSONObject("agoraToken");
        // userId is numeric in the SDK's parsing; keep its decimal text.
        String userId = token == null ? "" : String.valueOf(token.optLong("userId", 0));
        String rtmUserId = token == null ? "" : token.optString("rtmUserId", ""), rtmToken = token == null ? "" : token.optString("rtmToken", "");
        if ("0".equals(userId) || rtmUserId.isEmpty() || rtmToken.isEmpty()) throw new CloudException("Elegoo did not issue cloud control credentials for this account.", false);
        // Camera (Agora RTC): numeric rtcUserId and rtcToken, as the SDK's getAgoraCredential parses them.
        String rtcUserId = String.valueOf(token.optLong("rtcUserId", 0)), rtcToken = token.optString("rtcToken", "");
        return new AgoraCredential(userId, rtmUserId, rtmToken, "0".equals(rtcUserId) ? "" : rtcUserId, rtcToken);
    }

    /** Login for Elegoo's cloud MQTT status pushes (SDK getMqttCredential). */
    public static final class MqttCredential {
        public final String host, clientId, username, password;
        MqttCredential(String host, String clientId, String username, String password) { this.host = host; this.clientId = clientId; this.username = username; this.password = password; }
    }
    public MqttCredential mqttCredential(String clientId) throws IOException {
        JSONObject data = authorized("GET", "/api/v1/device-management-server/mqtt-link/mqtt-client?mqttClientId=" + URLEncoder.encode(clientId, "UTF-8"), null, true).optJSONObject("data");
        if (data == null || data.optString("host").isEmpty() || data.optString("mqttUserName").isEmpty())
            throw new CloudException("Elegoo did not issue live-update credentials for this app.", false);
        return new MqttCredential(data.optString("host"), data.optString("mqttClientId", clientId), data.optString("mqttUserName"), data.optString("mqttPassword"));
    }

    public List<Device> devices() throws IOException {
        JSONArray data = authorized("GET", "/api/v1/device-management-server/device/list", null, true).optJSONArray("data");
        List<Device> devices = new ArrayList<>();
        if (data == null) return devices;
        for (int i = 0; i < data.length(); i++) {
            JSONObject item = data.optJSONObject(i);
            if (item != null && !item.optString("serialNo", "").isEmpty())
                devices.add(new Device(item.optString("serialNo"), item.optString("pcode", ""), item.optString("deviceName", "")));
        }
        return devices;
    }

    /**
     * The SDK's raw onlineStatus value; 1 is treated as online. Optional: on the user's account this endpoint refuses a token
     * that device/list accepts, so a refusal returns -1 instead of renewing the sign-in or failing the whole refresh.
     */
    public int online(String serial) throws IOException {
        try {
            JSONObject data = authorized("POST", "/api/v1/device-management-server/device-register/online-status",
                new JSONObject().put("deviceCode", serial).toString(), false).optJSONObject("data");
            return data == null ? -1 : data.optInt("onlineStatus", -1);
        } catch (CloudException error) { if (error.unauthorized) return -1; throw error; }
        catch (org.json.JSONException impossible) { throw new IOException(impossible); }
    }

    public Snapshot status(String serial) throws IOException {
        // No renewal on refusal: device/list already proved the token works, so renewing would not help.
        JSONObject data = authorized("GET", "/api/v1/device-management-server/device/report-data/list?deviceCode=" + URLEncoder.encode(serial, "UTF-8"), null, false).optJSONObject("data");
        return assemble(data == null ? new JSONObject() : data);
    }

    private JSONObject authorized(String method, String path, String body, boolean essential) throws IOException {
        if (needsRefresh(System.currentTimeMillis() / 1000)) {
            note("Access token expired " + when(account.accessExpires) + "; renewing before the request");
            refresh();
        }
        try { return call(method, path, body, true); }
        catch (CloudException error) {
            // No automatic renewal on refusal: a failed renewal ended the sign-in on the user's phone.
            if (error.unauthorized && essential)
                throw new CloudException("Elegoo no longer accepts this sign-in. Sign out and sign in again in Settings.", true);
            throw error;
        }
    }

    private JSONObject call(String method, String path, String body, boolean bearer) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        if (bearer) headers.put("Authorization", "Bearer " + account.accessToken);
        headers.put("User-Agent", userAgent);
        headers.put("Accept", "application/json");
        // Sent by Elegoo's own account page with every request.
        headers.put("User-Lang", language);
        headers.put("X-Client-Request-Id", UUID.randomUUID().toString());
        if (body != null) headers.put("Content-Type", "application/json");
        Response response = transport.send(method, base + path, headers, body);
        String endpoint = method + " " + (path.contains("?") ? path.substring(0, path.indexOf('?')) : path).replaceFirst("^/api/v1/[^/]+/", "");
        String result = endpoint + " → HTTP " + response.status;
        try { JSONObject json = new JSONObject(response.body); result += ", code " + json.opt("code") + " " + StatusPresentation.clean(json.optString("message", json.optString("msg", ""))); }
        catch (Exception ignored) { }
        note(result.trim());
        if (response.status == 401 || response.status == 403) throw new CloudException("Elegoo refused the sign-in (HTTP " + response.status + ").", true);
        if (response.status < 200 || response.status >= 300) throw new CloudException("Elegoo cloud returned HTTP " + response.status + ".", false);
        JSONObject json;
        try { json = new JSONObject(response.body); } catch (Exception error) { throw new CloudException("Elegoo cloud returned an unreadable response.", false); }
        int code = json.optInt("code", -1);
        if (code != 0) {
            String message = StatusPresentation.clean(json.optString("message", json.optString("msg", "")));
            throw new CloudException("Elegoo cloud error " + code + (message.isEmpty() ? "" : ": " + message), code == 401 || code == 403);
        }
        return json;
    }

    private static final Pattern INTEGER = Pattern.compile("-?\\d+"), DECIMAL = Pattern.compile("-?\\d*\\.\\d+([eE][-+]?\\d+)?");
    private static final Map<String, Set<String>> STRING_FIELDS = new HashMap<>();
    static {
        STRING_FIELDS.put("external_device", new HashSet<>(Collections.singletonList("type")));
        STRING_FIELDS.put("print_status", new HashSet<>(Arrays.asList("uuid", "filename", "state")));
        STRING_FIELDS.put("tool_head", new HashSet<>(Collections.singletonList("homed_axes")));
        STRING_FIELDS.put("mono_filament_info", new HashSet<>(Arrays.asList("brand", "filament_type", "filament_name", "filament_code", "filament_color")));
    }

    /** Rebuilds {group: {field: value}} from the cloud's per-field reports, with the SDK's typing rules. */
    static Snapshot assemble(JSONObject data) {
        JSONObject status = new JSONObject(); long newest = 0;
        try {
            for (Iterator<String> groups = data.keys(); groups.hasNext(); ) {
                String group = groups.next(); JSONArray items = data.optJSONArray(group);
                if (items == null) continue;
                JSONObject fields = new JSONObject();
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.optJSONObject(i);
                    if (item == null) continue;
                    String key = item.optString("reportLinkKey", ""), value = item.optString("reportValue", "");
                    if (key.isEmpty()) continue;
                    newest = Math.max(newest, item.optLong("updateTime", 0));
                    Set<String> strings = STRING_FIELDS.get(group);
                    fields.put(key, strings != null && strings.contains(key) ? value : typed(value));
                }
                status.put(group, fields);
            }
            JSONObject machine = status.optJSONObject("machine_status");
            // As in the SDK: a cloud snapshot carries no live exception list.
            if (machine != null) machine.put("exception_status", new JSONArray());
        } catch (org.json.JSONException impossible) { throw new IllegalStateException(impossible); }
        return new Snapshot(status, newest);
    }
    static Object typed(String value) {
        String text = value.trim();
        try {
            if (text.startsWith("{")) return new JSONObject(text);
            if (text.startsWith("[")) return new JSONArray(text);
            if (text.startsWith("\"")) return new org.json.JSONTokener(text).nextValue();
        } catch (Exception error) { return value; }
        if (text.equals("true")) return Boolean.TRUE;
        if (text.equals("false")) return Boolean.FALSE;
        try {
            if (INTEGER.matcher(text).matches()) return Long.parseLong(text);
            if (DECIMAL.matcher(text).matches()) return Double.parseDouble(text);
        } catch (NumberFormatException error) { return value; }
        return value;
    }

    /** HttpsURLConnection transport; https only, no redirects to other hosts. */
    public static Response https(String method, String url, Map<String, String> headers, String body) throws IOException {
        if (!url.startsWith("https://")) throw new IOException("Cloud requests must use https");
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(10_000); connection.setReadTimeout(15_000);
            connection.setRequestMethod(method);
            for (Map.Entry<String, String> header : headers.entrySet()) connection.setRequestProperty(header.getKey(), header.getValue());
            if (body != null) {
                connection.setDoOutput(true);
                try (OutputStream output = connection.getOutputStream()) { output.write(body.getBytes(StandardCharsets.UTF_8)); }
            }
            int status = connection.getResponseCode();
            InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (input == null) return new Response(status, "");
            try (InputStream in = input) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int read;
                while ((read = in.read(buffer)) != -1) { bytes.write(buffer, 0, read); if (bytes.size() > 4 * 1024 * 1024) throw new IOException("Cloud response too large"); }
                return new Response(status, bytes.toString("UTF-8"));
            }
        } finally { connection.disconnect(); }
    }
}
