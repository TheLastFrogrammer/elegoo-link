package io.github.thelastfrogrammer.elink.tv;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Link Workshop's local API on the phone, as the TV uses it: what the phone offers, pairing, the status, the cameras, and
 * the two streams (events, a camera). Read only: the only POST is the pairing request. Plain HttpURLConnection; every
 * call blocks, so callers stay off the main thread. Pure Java.
 */
final class PhoneApi {
    static final int CONNECT_MS = 5_000, READ_MS = 10_000, MAX_ANSWER = 256 * 1024;

    /** The phone refused the token (401): the TV was removed on the phone, or was never paired with this one. */
    static final class Unpaired extends IOException { Unpaired() { super("Not paired"); } }
    /** The phone answered, but not with what was asked: its status code and its own words. */
    static final class Refused extends IOException {
        final int code;
        Refused(int code, String words) { super(words); this.code = code; }
    }

    /** A camera the phone shares. Rotation (degrees clockwise) and mirror say how to turn its pictures. */
    static final class Camera {
        final String id, name, source; final boolean live, mirror; final int rotation;
        Camera(String id, String name, boolean live, String source, int rotation, boolean mirror) {
            this.id = id; this.name = name; this.live = live; this.source = source; this.rotation = rotation; this.mirror = mirror;
        }
        static Camera of(JSONObject o) {
            String id = o.optString("id", ""), name = o.optString("name", "").trim();
            int r = ((o.optInt("rotation", 0) % 360) + 360) % 360;
            return new Camera(id, name.isEmpty() ? "Camera" : name, o.optBoolean("live", true), o.optString("source", "camera"), r - r % 90, o.optBoolean("mirror", false));
        }
    }

    final String base;

    PhoneApi(String base) { this.base = base; }

    /** GET /api/v1/info, checked: an IOException unless this is Link Workshop's API, version 1. */
    JSONObject info() throws IOException {
        JSONObject info = json(call("GET", "/api/v1/info", null, null, READ_MS), 200);
        if (!"Link Workshop".equals(info.optString("app")) || info.optInt("api", 0) != 1) throw new Refused(200, "This address answers, but it is not Link Workshop's API.");
        return info;
    }

    /** POST /api/v1/pair: {"id", "code", "poll", "expires_in"}. 429 when the phone is busy with other requests. */
    JSONObject pair(String name) throws IOException {
        String body;
        try { body = new JSONObject().put("name", name).toString(); } catch (JSONException impossible) { throw new IOException(impossible); }
        JSONObject asked = json(call("POST", "/api/v1/pair", null, body, READ_MS), 202);
        if (asked.optString("code").isEmpty() || !asked.optString("poll").startsWith("/api/v1/pair/")) throw new Refused(202, "The phone's answer could not be read.");
        return asked;
    }

    /** GET the pairing's poll address: {"state": "pending" | "approved" (with "token") | "denied"}; null once it has run out (404). */
    JSONObject poll(String path) throws IOException {
        if (!path.startsWith("/api/v1/pair/")) throw new IOException("Not a pairing address");
        HttpURLConnection c = call("GET", path, null, null, READ_MS);
        int code;
        try { code = c.getResponseCode(); } catch (IOException e) { c.disconnect(); throw e; }
        if (code == 404) { c.disconnect(); return null; }
        return json(c, 200);
    }

    JSONObject status(String token) throws IOException { return json(call("GET", "/api/v1/status", token, null, READ_MS), 200); }

    List<Camera> cameras(String token) throws IOException {
        JSONArray all = json(call("GET", "/api/v1/cameras", token, null, READ_MS), 200).optJSONArray("cameras");
        List<Camera> out = new ArrayList<>();
        for (int i = 0; all != null && i < all.length(); i++) {
            JSONObject o = all.optJSONObject(i);
            if (o != null && o.optString("id", "").matches("[A-Za-z0-9._~-]{1,100}")) out.add(Camera.of(o));
        }
        return out;
    }

    /** Opens a stream (events or a camera) and checks it answered 200; the caller reads it and disconnects. */
    HttpURLConnection stream(String path, String token, int readMs) throws IOException {
        HttpURLConnection c = call("GET", path, token, null, readMs);
        try {
            int code = c.getResponseCode();
            if (code == 401) throw new Unpaired();
            if (code != 200) throw new Refused(code, words(c));
            return c;
        } catch (IOException e) { c.disconnect(); throw e; }
    }
    static String cameraPath(String id, int fps) { return "/api/v1/cameras/" + id + "/stream.mjpeg?fps=" + Math.max(1, Math.min(10, fps)); }

    // ------------------------------------------------------------------ plumbing

    private HttpURLConnection call(String method, String path, String token, String body, int readMs) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        c.setRequestMethod(method); c.setConnectTimeout(CONNECT_MS); c.setReadTimeout(readMs); c.setUseCaches(false);
        c.setInstanceFollowRedirects(false);   // the phone never redirects; never carry the token anywhere else
        if (token != null) c.setRequestProperty("Authorization", "Bearer " + token);
        if (body != null) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            c.setDoOutput(true); c.setFixedLengthStreamingMode(bytes.length); c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream o = c.getOutputStream()) { o.write(bytes); } catch (IOException e) { c.disconnect(); throw e; }
        }
        return c;
    }

    /** The answer as JSON when it has the expected code; Unpaired on 401, Refused (with the phone's words) otherwise. */
    private static JSONObject json(HttpURLConnection c, int expected) throws IOException {
        try {
            int code = c.getResponseCode();
            if (code == 401) throw new Unpaired();
            if (code != expected) throw new Refused(code, words(c));
            try { return new JSONObject(read(c.getInputStream())); }
            catch (JSONException unreadable) { throw new Refused(code, "The phone's answer could not be read."); }
        } finally { c.disconnect(); }
    }
    /** The phone's own words for an error ({"error": "…"}), or a plain description. */
    private static String words(HttpURLConnection c) {
        try {
            InputStream err = c.getErrorStream();
            String said = err == null ? "" : new JSONObject(read(err)).optString("error", "");
            if (!said.isEmpty()) return said;
        } catch (IOException | JSONException unreadable) { /* the plain words below */ }
        try { return "The phone answered " + c.getResponseCode() + "."; } catch (IOException e) { return "The phone did not answer."; }
    }
    private static String read(InputStream in) throws IOException {
        try (InputStream s = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] b = new byte[8192]; int n;
            while ((n = s.read(b)) > 0) { out.write(b, 0, n); if (out.size() > MAX_ANSWER) throw new IOException("The phone's answer was too large"); }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
