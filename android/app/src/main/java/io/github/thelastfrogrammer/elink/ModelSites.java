package io.github.thelastfrogrammer.elink;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Finding printable models on model sites, only through their official ways in: Thingiverse's REST API (with the user's
 * own app token) and MyMiniFactory's API (with the user's own key; its downloads need a MyMiniFactory login, so files are
 * opened on its page). Printables, MakerWorld and Cults3D offer no public file API, so they are searched in the browser,
 * and a file downloaded there opens in the app through "Open with". Tokens and keys go only to their own site's API host.
 * Independent of Android.
 */
final class ModelSites {
    static final String THINGIVERSE = "Thingiverse", MYMINIFACTORY = "MyMiniFactory";
    static final String THINGIVERSE_API = "api.thingiverse.com", MMF_HOST = "www.myminifactory.com";
    static final long MAX_DOWNLOAD = 200L * 1024 * 1024;
    static final Set<String> MODEL_TYPES = new HashSet<>(Arrays.asList("stl", "3mf", "obj", "step", "stp", "amf"));

    /** One model found on a site. */
    static final class Model {
        final String source, id, name, creator, thumbnail, page, license;
        Model(String source, String id, String name, String creator, String thumbnail, String page, String license) {
            this.source = source; this.id = id; this.name = name; this.creator = creator; this.thumbnail = thumbnail; this.page = page; this.license = license;
        }
    }
    /** One downloadable file of a model. */
    static final class ModelFile {
        final String id, name, url; final long size;
        ModelFile(String id, String name, long size, String url) { this.id = id; this.name = name; this.size = size; this.url = url; }
        boolean printable() { return MODEL_TYPES.contains(extension(name)); }
    }

    // ------------------------------------------------------------------ addresses
    static String thingiverseSearch(String term, int page) {
        return "https://" + THINGIVERSE_API + "/search/" + encodePath(term.trim()) + "/?type=things&per_page=20&page=" + Math.max(1, page);
    }
    static String thingiverseFiles(String thingId) {
        if (!thingId.matches("[0-9]{1,12}")) throw new IllegalArgumentException("Not a Thingiverse thing");
        return "https://" + THINGIVERSE_API + "/things/" + thingId + "/files";
    }
    static String mmfSearch(String term, int page, String key) {
        return "https://" + MMF_HOST + "/api/v2/search?q=" + encode(term.trim()) + "&page=" + Math.max(1, page) + "&per_page=20&key=" + encode(key);
    }
    /** The site's own search page, for sites searched in the browser. */
    static String webSearch(String site, String term) {
        String q = encode(term.trim());
        switch (site) {
            case "Printables": return "https://www.printables.com/search/models?q=" + q;
            case "MakerWorld": return "https://makerworld.com/en/search/models?keyword=" + q;
            case "Cults3D": return "https://cults3d.com/en/search?q=" + q;
            case THINGIVERSE: return "https://www.thingiverse.com/search?q=" + q + "&type=things";
            case MYMINIFACTORY: return "https://www.myminifactory.com/search/?query=" + q;
            default: throw new IllegalArgumentException("Unknown site");
        }
    }
    static final String[] BROWSER_SITES = {"Printables", "MakerWorld", "Cults3D", THINGIVERSE, MYMINIFACTORY};

    // ------------------------------------------------------------------ answers
    /** Thingiverse search: {"hits": [thing…]} (or a bare list on older answers). */
    static List<Model> parseThingiverseSearch(String body) throws org.json.JSONException {
        Object root = new org.json.JSONTokener(body).nextValue();
        JSONArray hits = root instanceof JSONArray ? (JSONArray) root : ((JSONObject) root).optJSONArray("hits");
        List<Model> models = new ArrayList<>();
        for (int i = 0; hits != null && i < hits.length(); i++) {
            JSONObject thing = hits.optJSONObject(i); if (thing == null) continue;
            String id = thing.opt("id") == null ? "" : String.valueOf(thing.opt("id"));
            if (!id.matches("[0-9]{1,12}")) continue;
            JSONObject creator = thing.optJSONObject("creator");
            models.add(new Model(THINGIVERSE, id, clean(thing.optString("name", "Untitled")), creator == null ? "" : clean(creator.optString("name", "")),
                https(thing.optString("thumbnail", thing.optString("preview_image", ""))), https(thing.optString("public_url", "https://www.thingiverse.com/thing:" + id)),
                clean(thing.optString("license", ""))));
        }
        return models;
    }
    /** Thingiverse files of a thing: [{id, name, size, download_url}…]. */
    static List<ModelFile> parseThingiverseFiles(String body) throws org.json.JSONException {
        JSONArray list = new JSONArray(body);
        List<ModelFile> files = new ArrayList<>();
        for (int i = 0; i < list.length(); i++) {
            JSONObject file = list.optJSONObject(i); if (file == null) continue;
            String id = file.opt("id") == null ? "" : String.valueOf(file.opt("id"));
            String url = https(file.optString("download_url", ""));
            if (url.isEmpty() && id.matches("[0-9]{1,12}")) url = "https://" + THINGIVERSE_API + "/files/" + id + "/download";
            if (url.isEmpty()) continue;
            files.add(new ModelFile(id, safeName(file.optString("name", "model")), file.optLong("size", -1), url));
        }
        return files;
    }
    /** MyMiniFactory search: {"items": [object…]}. */
    static List<Model> parseMmfSearch(String body) throws org.json.JSONException {
        JSONObject root = new JSONObject(body);
        JSONArray items = root.optJSONArray("items");
        List<Model> models = new ArrayList<>();
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i); if (item == null) continue;
            String id = item.opt("id") == null ? "" : String.valueOf(item.opt("id"));
            JSONObject designer = item.optJSONObject("designer");
            String thumb = "";
            JSONArray images = item.optJSONArray("images");
            JSONObject image = images == null ? null : images.optJSONObject(0);
            if (image != null) { JSONObject t = image.optJSONObject("thumbnail"); thumb = t != null ? t.optString("url", "") : image.optString("url", ""); }
            String page = item.optString("url", "");
            if (page.isEmpty() && id.matches("[0-9]{1,12}")) page = "https://" + MMF_HOST + "/object/" + id;
            models.add(new Model(MYMINIFACTORY, id, clean(item.optString("name", "Untitled")),
                designer == null ? "" : clean(designer.optString("name", designer.optString("username", ""))), https(thumb), https(page), clean(item.optString("license", ""))));
        }
        return models;
    }

    // ------------------------------------------------------------------ fetching
    interface Http { HttpURLConnection open(URL url) throws IOException; }
    interface Progress { boolean update(long done, long total); }

    /** GET a JSON answer; `token` (may be empty) is sent as a bearer token only to Thingiverse's API host. */
    static String getJson(Http http, String address, String token) throws IOException {
        URL url = new URL(address);
        if (!"https".equals(url.getProtocol())) throw new IOException("Only secure (https) addresses are used.");
        HttpURLConnection connection = http.open(url);
        try {
            connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(15_000); connection.setReadTimeout(20_000);
            connection.setRequestProperty("Accept", "application/json");
            if (!token.isEmpty() && THINGIVERSE_API.equals(url.getHost())) connection.setRequestProperty("Authorization", "Bearer " + token);
            int code = connection.getResponseCode();
            if (code == 401 || code == 403) throw new IOException("The site refused the request (HTTP " + code + "). Check the token or key in Site keys.");
            if (code < 200 || code >= 300) throw new IOException("The site answered HTTP " + code + ".");
            return read(connection.getInputStream(), 8L * 1024 * 1024);
        } finally { connection.disconnect(); }
    }

    /**
     * Downloads a model file to `target`, following up to five redirects (the token only ever goes to Thingiverse's API host,
     * never to the storage a download redirects to), https only, at most MAX_DOWNLOAD bytes.
     */
    static void download(Http http, String address, String token, File target, Progress progress) throws IOException {
        download(http, address, (url, connection) -> {
            if (!token.isEmpty() && THINGIVERSE_API.equals(url.getHost())) connection.setRequestProperty("Authorization", "Bearer " + token);
        }, target, progress);
    }
    /** Adds what a request to `url` may carry (a token, or the in-app browser's own cookies for that address); asked at every hop. */
    interface Credentials { void apply(URL url, HttpURLConnection connection); }
    /** As above, with the credentials for each hop chosen by `credentials`; returns the final answer's Content-Disposition (or ""). */
    static String download(Http http, String address, Credentials credentials, File target, Progress progress) throws IOException {
        URL url = new URL(address);
        for (int hop = 0; hop <= 5; hop++) {
            if (!"https".equals(url.getProtocol())) throw new IOException("The download moved to an address that is not secure; it was not followed.");
            HttpURLConnection connection = http.open(url);
            try {
                connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(15_000); connection.setReadTimeout(30_000);
                credentials.apply(url, connection);
                int code = connection.getResponseCode();
                if (code >= 300 && code < 400) {
                    String next = connection.getHeaderField("Location");
                    if (next == null) throw new IOException("The download moved without saying where.");
                    url = new URL(url, next); continue;
                }
                if (code == 401 || code == 403) throw new IOException("The site refused the download (HTTP " + code + "). Check the token in Site keys.");
                if (code < 200 || code >= 300) throw new IOException("The download failed (HTTP " + code + ").");
                long total = connection.getContentLengthLong();
                if (total > MAX_DOWNLOAD) throw new IOException("The file is larger than 200 MB.");
                try (InputStream in = connection.getInputStream(); OutputStream out = new FileOutputStream(target)) {
                    byte[] buffer = new byte[64 * 1024]; long done = 0; int n;
                    while ((n = in.read(buffer)) >= 0) {
                        done += n;
                        if (done > MAX_DOWNLOAD) throw new IOException("The file is larger than 200 MB.");
                        out.write(buffer, 0, n);
                        if (!progress.update(done, total)) throw new InterruptedIOException("Download cancelled.");
                    }
                }
                String disposition = connection.getHeaderField("Content-Disposition");
                return disposition == null ? "" : disposition;
            } finally { connection.disconnect(); }
        }
        throw new IOException("The download was redirected too many times.");
    }

    // ------------------------------------------------------------------ files from the in-app browser
    /** Whether the in-app browser takes a file of this name: a model file or a ZIP of them (or no extension yet: sniffed later). */
    static boolean browserTakes(String name) { String e = extension(name); return e.isEmpty() || e.equals("zip") || MODEL_TYPES.contains(e) || e.equals("bin"); }

    /** A file name for a download: Content-Disposition's filename* or filename, else the address's last segment, else "model". */
    static String fileNameFor(String address, String disposition, String mimeType) {
        String name = "";
        if (disposition != null) {
            java.util.regex.Matcher star = java.util.regex.Pattern.compile("(?i)filename\\*\\s*=\\s*([^']*)'[^']*'([^;]+)").matcher(disposition);
            java.util.regex.Matcher plain = java.util.regex.Pattern.compile("(?i)filename\\s*=\\s*(\"([^\"]*)\"|[^;]+)").matcher(disposition);
            try {
                if (star.find()) name = URLDecoder.decode(star.group(2).trim().replace("+", "%2B"), star.group(1).isEmpty() ? "UTF-8" : star.group(1).trim());
                else if (plain.find()) name = plain.group(2) != null ? plain.group(2) : plain.group(1).trim();
            } catch (Exception unreadable) { name = ""; }
        }
        if (name.isEmpty() && address != null && address.startsWith("https://")) {
            try {
                String path = new URL(address).getPath();
                name = URLDecoder.decode(path.substring(path.lastIndexOf('/') + 1).replace("+", "%2B"), "UTF-8");
            } catch (Exception unreadable) { name = ""; }
        }
        name = safeName(name);
        if (extension(name).isEmpty() && mimeType != null) {
            String m = mimeType.toLowerCase(Locale.ROOT);
            if (m.contains("zip") && !m.contains("3mf")) name += ".zip";
            else if (m.contains("3mf")) name += ".3mf";
            else if (m.contains("stl")) name += ".stl";
        }
        return name;
    }

    /** The model type a downloaded file really is, from its first bytes: "3mf", "zip", "stl", "obj", "step", or "" if unknown. */
    static String sniff(File file) throws IOException {
        byte[] head = new byte[512]; int n;
        try (InputStream in = new FileInputStream(file)) { n = Math.max(0, in.read(head)); }
        if (n >= 4 && head[0] == 'P' && head[1] == 'K' && head[2] == 3 && head[3] == 4) {
            try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(file)) { return zip.getEntry("3D/3dmodel.model") != null ? "3mf" : "zip"; }
            catch (IOException broken) { return "zip"; }
        }
        String text = new String(head, 0, n, StandardCharsets.ISO_8859_1).trim();
        if (text.startsWith("ISO-10303-21")) return "step";
        if (n >= 84) {
            long triangles = (head[80] & 0xffL) | (head[81] & 0xffL) << 8 | (head[82] & 0xffL) << 16 | (head[83] & 0xffL) << 24;
            if (84 + 50 * triangles == file.length()) return "stl";
        }
        if (text.startsWith("solid") && text.contains("facet")) return "stl";
        if (text.matches("(?s)^(#[^\\n]*\\n\\s*)*(v|o|g|mtllib|vn|vt)\\s.*")) return "obj";
        return "";
    }

    static final int MAX_ZIP_ENTRIES = 500;
    static final long MAX_UNZIPPED = 1024L * 1024 * 1024;
    /**
     * Unpacks the model files of a ZIP into `folder` (flattened: no folders, so nothing can land outside it; a name already
     * taken gets a number), at most MAX_ZIP_ENTRIES entries and MAX_UNZIPPED bytes in all. Returns the model files.
     */
    static List<File> unzipModels(File zipFile, File folder) throws IOException {
        List<File> out = new ArrayList<>(); long written = 0; int entries = 0;
        try (java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(new BufferedInputStream(new FileInputStream(zipFile)))) {
            java.util.zip.ZipEntry entry; byte[] buffer = new byte[64 * 1024];
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > MAX_ZIP_ENTRIES) throw new IOException("The ZIP holds too many files.");
                String name = safeName(entry.getName());
                if (entry.isDirectory() || name.startsWith(".") || entry.getName().contains("__MACOSX") || !MODEL_TYPES.contains(extension(name))) continue;
                File target = new File(folder, name);
                for (int i = 2; target.exists(); i++) { int dot = name.lastIndexOf('.'); target = new File(folder, name.substring(0, dot) + " (" + i + ")" + name.substring(dot)); }
                try (OutputStream file = new FileOutputStream(target)) {
                    int n;
                    while ((n = zip.read(buffer)) >= 0) {
                        written += n;
                        if (written > MAX_UNZIPPED) throw new IOException("The ZIP unpacks to more than 1 GB.");
                        file.write(buffer, 0, n);
                    }
                } catch (IOException failed) { target.delete(); throw failed; }
                out.add(target);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers
    static String extension(String name) { int dot = name == null ? -1 : name.lastIndexOf('.'); return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT); }
    /** A file name safe to save under: no folders, no control characters, at most 120 characters. */
    static String safeName(String name) {
        String base = name == null ? "" : name.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}:*?\"<>|]", "_").trim();
        if (base.isEmpty() || base.equals(".") || base.equals("..")) base = "model";
        return base.length() > 120 ? base.substring(base.length() - 120) : base;
    }
    private static String clean(String text) { String t = text == null ? "" : text.replaceAll("[\\p{Cntrl}]", " ").trim(); return t.length() > 200 ? t.substring(0, 200) + "…" : t; }
    private static String https(String url) { return url != null && url.startsWith("https://") ? url : ""; }
    private static String encode(String text) {
        try { return URLEncoder.encode(text, "UTF-8"); } catch (UnsupportedEncodingException impossible) { throw new IllegalStateException(impossible); }
    }
    private static String encodePath(String text) { return encode(text).replace("+", "%20"); }
    private static String read(InputStream in, long limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[16 * 1024]; int n;
        while ((n = in.read(buffer)) >= 0) { out.write(buffer, 0, n); if (out.size() > limit) throw new IOException("The site's answer was too large."); }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
