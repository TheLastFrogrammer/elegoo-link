package io.github.thelastfrogrammer.elink;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONObject;

/**
 * Pushes the status, print events, a picture now and then and the finished timelapse to the person's own relay on the
 * internet (INTEGRATIONS.md has a ready Cloudflare Worker), for readers that cannot reach the home network, such as a
 * Reddit app. Every push is signed with a secret only the phone and the relay know (HMAC-SHA256 over the time, the path
 * and the body's hash), so the relay accepts nothing else. HTTPS only. The relay's address and secret are kept encrypted
 * and never logged. Pushes only go out while this phone follows the printer. Independent of Android.
 */
final class IntegrationRelay {
    static final long STATUS_MS = 15_000, BACKOFF_MS = 60_000;
    static final int TIMELAPSE_LIMIT = 24 * 1024 * 1024;

    interface Opener { HttpURLConnection open(URL url) throws IOException; }
    static Opener https() { return url -> (HttpURLConnection) url.openConnection(); }

    private final Opener opener;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "integration-relay"); t.setDaemon(true); return t; });
    private String url = ""; private byte[] secret = new byte[0]; private long statusEveryMs = 60_000;
    private String lastStatusKey = ""; private long lastStatusAt, quietUntil;
    /** What happened last, in words (no address), for the settings screen. */
    volatile String lastResult = "Nothing sent yet.";

    IntegrationRelay(Opener opener) { this.opener = opener; }

    /** Why an address cannot be used, or "" when it can: https, a host name, no user info, no query. */
    static String problem(String url) {
        try {
            URL u = new URL(url.trim());
            if (!"https".equals(u.getProtocol())) return "The relay's address must start with https://.";
            if (u.getHost().isEmpty() || u.getUserInfo() != null || u.getQuery() != null || u.getRef() != null) return "Use the relay's plain address, such as https://printer-relay.example.workers.dev.";
            return "";
        } catch (IOException bad) { return "That is not a web address."; }
    }

    synchronized void configure(String url, byte[] secret, long statusEveryMs) {
        this.statusEveryMs = Math.max(STATUS_MS, statusEveryMs);
        String base = url == null ? "" : url.trim().replaceAll("/+$", "");
        if (!base.equals(this.url)) { lastStatusKey = ""; quietUntil = 0; }
        this.url = base; this.secret = secret == null ? new byte[0] : secret.clone();
    }
    synchronized boolean ready() { return !url.isEmpty() && problem(url).isEmpty() && secret.length >= 16; }

    /** The status: sent when it changed (times aside), at most every statusEveryMs (STATUS_MS at least), and at least every 5 minutes. */
    void status(JSONObject status, long now) {
        String key = IntegrationServer.comparable(status);
        synchronized (this) {
            if (!ready() || now < quietUntil) return;
            boolean changed = !key.equals(lastStatusKey);
            if (now - lastStatusAt < statusEveryMs || !changed && now - lastStatusAt < Math.max(5 * 60_000L, statusEveryMs)) return;
            lastStatusKey = key; lastStatusAt = now;
        }
        push("/v1/status", "application/json", status.toString().getBytes(StandardCharsets.UTF_8), false);
    }
    /** A print event, at once (and the next status goes with it). */
    void event(JSONObject event) { synchronized (this) { if (!ready()) return; lastStatusAt = 0; } push("/v1/event", "application/json", event.toString().getBytes(StandardCharsets.UTF_8), true); }
    void picture(byte[] jpeg) { if (jpeg != null && ready()) push("/v1/snapshot", "image/jpeg", jpeg, false); }
    void timelapse(File mp4) {
        if (mp4 == null || !mp4.isFile() || !ready()) return;
        if (mp4.length() > TIMELAPSE_LIMIT) { lastResult = "The timelapse is larger than " + TIMELAPSE_LIMIT / (1024 * 1024) + " MB, so it was not sent."; return; }
        worker.execute(() -> { try { send("/v1/timelapse", "video/mp4", Files.readAllBytes(mp4.toPath())); } catch (IOException unreadable) { lastResult = "The timelapse could not be read."; } });
    }
    /** A test push from the settings screen; the answer arrives in lastResult. */
    void test(JSONObject status) { push("/v1/status", "application/json", status.toString().getBytes(StandardCharsets.UTF_8), true); }

    private void push(String path, String type, byte[] body, boolean evenWhenQuiet) {
        worker.execute(() -> { synchronized (this) { if (!evenWhenQuiet && System.currentTimeMillis() < quietUntil) return; } send(path, type, body); });
    }

    private void send(String path, String type, byte[] body) {
        String base; byte[] key;
        synchronized (this) { base = url; key = secret.clone(); }
        if (base.isEmpty()) return;
        HttpURLConnection c = null;
        try {
            long ts = System.currentTimeMillis() / 1000;
            c = opener.open(new URL(base + path));
            c.setInstanceFollowRedirects(false); c.setConnectTimeout(10_000); c.setReadTimeout(20_000); c.setDoOutput(true); c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", type); c.setRequestProperty("User-Agent", "LinkWorkshop");
            c.setRequestProperty("X-LW-Timestamp", String.valueOf(ts)); c.setRequestProperty("X-LW-Signature", "v1=" + sign(key, ts, "POST", path, body));
            c.setFixedLengthStreamingMode(body.length);
            try (OutputStream out = c.getOutputStream()) { out.write(body); }
            int code = c.getResponseCode();
            drain(c);
            String what = path.substring(path.lastIndexOf('/') + 1);
            if (code / 100 == 2) lastResult = "Sent the " + what + " at " + new java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new java.util.Date()) + ".";
            else { lastResult = "The relay answered " + code + (code == 401 ? ": the secret on the relay and here differ, or the phone's clock is off" : "") + "."; quiet(); }
        } catch (IOException | RuntimeException failed) {
            lastResult = "Could not reach the relay (" + failed.getClass().getSimpleName() + ")."; quiet();
        } finally { if (c != null) c.disconnect(); }
    }
    private synchronized void quiet() { quietUntil = System.currentTimeMillis() + BACKOFF_MS; }
    private static void drain(HttpURLConnection c) {
        try (InputStream in = c.getResponseCode() / 100 == 2 ? c.getInputStream() : c.getErrorStream()) { if (in != null) { byte[] b = new byte[4096]; int total = 0, n; while (total < 65536 && (n = in.read(b)) > 0) total += n; } }
        catch (IOException ignored) { }
    }

    /** The signature: hex HMAC-SHA256 with the secret over "timestamp\nMETHOD\npath\nhex SHA-256 of the body". */
    static String sign(byte[] secret, long timestamp, String method, String path, byte[] body) {
        try {
            String text = timestamp + "\n" + method + "\n" + path + "\n" + hex(MessageDigest.getInstance("SHA-256").digest(body));
            Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return hex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
    /** A new secret: 32 random bytes, as base64url text to paste into the relay. */
    static String newSecret() { byte[] b = new byte[32]; new java.security.SecureRandom().nextBytes(b); return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
    /** The secret text as the bytes both sides sign with (its UTF-8 text, so the relay can use the same string). */
    static byte[] secretBytes(String text) { return text == null ? new byte[0] : text.trim().getBytes(StandardCharsets.UTF_8); }
    static String hex(byte[] bytes) { StringBuilder s = new StringBuilder(); for (byte b : bytes) s.append(String.format(Locale.ROOT, "%02x", b & 255)); return s.toString(); }

    void close() { worker.shutdownNow(); }
}
