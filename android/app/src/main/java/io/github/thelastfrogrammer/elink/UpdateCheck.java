package io.github.thelastfrogrammer.elink;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Whether a newer signed release is published: one read of the project's GitHub releases (public, no account, nothing about
 * the phone or printer sent), picking the newest android-vX.Y.Z release that has an APK. Installing is left to the person:
 * the APK opens in the browser and Android checks it is signed like the installed app.
 */
final class UpdateCheck {
    static final String RELEASES = "https://api.github.com/repos/TheLastFrogrammer/elegoo-link/releases?per_page=20";
    static final String PREF_AUTO = "updateCheckAuto", PREF_LAST = "updateCheckLast";
    static final long DAY_MS = 24L * 60 * 60 * 1000;

    /** A published release: its version, the APK to download, its page, and the first lines of its notes. */
    static final class Release {
        final String version, apk, page, notes;
        Release(String version, String apk, String page, String notes) { this.version = version; this.apk = apk; this.page = page; this.notes = notes; }
    }

    /** The newest release in GitHub's releases list (drafts and pre-releases left out), or null. Pure, for tests. */
    static Release newest(String json) {
        Release best = null;
        try {
            JSONArray all = new JSONArray(json);
            for (int i = 0; i < all.length(); i++) {
                JSONObject r = all.optJSONObject(i);
                if (r == null || r.optBoolean("draft") || r.optBoolean("prerelease")) continue;
                String tag = r.optString("tag_name");
                if (!tag.startsWith("android-v")) continue;
                String version = tag.substring("android-v".length()), apk = null;
                JSONArray assets = r.optJSONArray("assets");
                for (int k = 0; assets != null && k < assets.length(); k++) {
                    JSONObject a = assets.optJSONObject(k);
                    if (a != null && a.optString("name").endsWith(".apk") && a.optString("browser_download_url").startsWith("https://github.com/")) apk = a.optString("browser_download_url");
                }
                if (apk == null || !version.matches("\\d+(\\.\\d+){0,3}")) continue;
                String body = r.optString("body", "").trim(); if (body.length() > 400) body = body.substring(0, 400) + "…";
                String page = r.optString("html_url"); if (!page.startsWith("https://github.com/")) page = "";
                if (best == null || compare(version, best.version) > 0) best = new Release(version, apk, page, body);
            }
        } catch (org.json.JSONException unreadable) { return null; }
        return best;
    }
    /** Compares dotted versions ("0.99.2" < "0.100.0"); anything after the numbers ("0.99.2-dev") is ignored. Pure. */
    static int compare(String a, String b) {
        String[] x = a.replaceAll("[^0-9.].*$", "").split("\\."), y = b.replaceAll("[^0-9.].*$", "").split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            long p = i < x.length && !x[i].isEmpty() ? Long.parseLong(x[i]) : 0, q = i < y.length && !y[i].isEmpty() ? Long.parseLong(y[i]) : 0;
            if (p != q) return Long.compare(p, q);
        }
        return 0;
    }

    /** Reads the releases list (worker only). */
    static Release fetch() throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(RELEASES).openConnection();
        c.setConnectTimeout(10_000); c.setReadTimeout(15_000);
        c.setRequestProperty("Accept", "application/vnd.github+json"); c.setRequestProperty("User-Agent", "LinkWorkshop");
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("GitHub answered " + code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[16_384]; int n; long total = 0;
                while ((n = in.read(buffer)) > 0) { total += n; if (total > 2_000_000) throw new IOException("Unexpectedly large answer"); out.write(buffer, 0, n); }
                return newest(new String(out.toByteArray(), StandardCharsets.UTF_8));
            }
        } finally { c.disconnect(); }
    }

    private UpdateCheck() { }
}
