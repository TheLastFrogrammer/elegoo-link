package io.github.thelastfrogrammer.elink;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Backup and restore of the app's own setup: settings (every settings file but the Elegoo sign-in), automation rules and
 * templates, spools, and optionally the print recordings with their pictures. Secrets (printer access codes, added cameras'
 * addresses and passwords, webhook tokens, the Home Assistant password) are never written in the clear: they go in only when
 * the person sets a backup password, encrypted with it (PBKDF2 and AES-GCM). The Elegoo sign-in is never included.
 */
final class Backup {
    static final String FORMAT = "linkWorkshopBackup", SECRETS = "secrets.bin";
    static final int VERSION = 1, ITERATIONS = 150_000;
    static final String[] FILES = {"automations.json", "automation-templates.json", "spools.json"};
    static final long MAX_ENTRY = 512L * 1024 * 1024, MAX_TOTAL = 8L * 1024 * 1024 * 1024;

    /** Settings files that stay out: the Elegoo sign-in (tokens) and the WebView's and libraries' own. */
    static boolean skipPrefs(String name) {
        return name.equals("cloud-account") || name.startsWith("WebView") || name.contains("chromium") || name.startsWith("androidx")
            || name.startsWith("com.google") || name.startsWith("_") || !name.matches("[A-Za-z0-9._-]{1,80}");
    }
    /** Keys that stay out of every settings file: encrypted secrets (they only mean anything with this phone's key). */
    static boolean skipKey(String key) { return key.startsWith("cipher:") || key.startsWith("iv:") || key.startsWith("remember:"); }

    static final class Summary {
        String app = ""; long created; int prefs, files, recordings; long recordingBytes; boolean secrets;
        String describe() {
            List<String> parts = new ArrayList<>();
            parts.add(prefs + (prefs == 1 ? " settings file" : " settings files"));
            if (files > 0) parts.add(files + (files == 1 ? " data file" : " data files") + " (automations, spools)");
            if (recordings > 0) parts.add(recordings + " recording files (" + Math.max(1, recordingBytes / (1024 * 1024)) + " MB)");
            parts.add(secrets ? "passwords and addresses, protected by the backup password" : "no passwords or addresses (enter them again after restoring)");
            return String.join("; ", parts);
        }
    }

    static File prefsDir(Context context) { return new File(context.getApplicationInfo().dataDir, "shared_prefs"); }
    static File recordingsDir(Context context) { return new File(context.getFilesDir(), "recordings"); }
    static long recordingsSize(Context context) { return size(recordingsDir(context)); }
    private static long size(File f) { if (f.isFile()) return f.length(); long n = 0; File[] all = f.listFiles(); if (all != null) for (File c : all) n += size(c); return n; }

    /** Writes a backup. `password` null or empty: no secrets. */
    static Summary export(Context context, OutputStream target, boolean withRecordings, char[] password) throws Exception {
        Summary summary = new Summary();
        try (ZipOutputStream zip = new ZipOutputStream(target)) {
            File[] prefsFiles = prefsDir(context).listFiles((d, n) -> n.endsWith(".xml"));
            List<String> names = new ArrayList<>();
            if (prefsFiles != null) for (File f : prefsFiles) { String name = f.getName().substring(0, f.getName().length() - 4); if (!skipPrefs(name)) names.add(name); }
            java.util.Collections.sort(names);
            for (String name : names) {
                JSONObject values = prefsJson(context.getSharedPreferences(name, Context.MODE_PRIVATE));
                if (values.length() == 0) continue;
                put(zip, "prefs/" + name + ".json", values.toString(1).getBytes(StandardCharsets.UTF_8)); summary.prefs++;
            }
            for (String name : FILES) { File f = new File(context.getFilesDir(), name); if (f.isFile()) { put(zip, "files/" + name, read(f, MAX_ENTRY)); summary.files++; } }
            if (withRecordings) {
                File root = recordingsDir(context);
                for (File f : walk(root)) {
                    String rel = root.toURI().relativize(f.toURI()).getPath();
                    zip.putNextEntry(new ZipEntry("recordings/" + rel));
                    try (InputStream in = new FileInputStream(f)) { copy(in, zip, MAX_ENTRY); }
                    zip.closeEntry(); summary.recordings++; summary.recordingBytes += f.length();
                }
            }
            if (password != null && password.length > 0) {
                CredentialStore store = new CredentialStore(context);
                JSONObject secrets = new JSONObject();
                for (String key : store.remembered()) try { secrets.put(key, store.load(key)); } catch (Exception unreadable) { }
                put(zip, SECRETS, seal(secrets.toString().getBytes(StandardCharsets.UTF_8), password));
                summary.secrets = true;
            }
            summary.created = System.currentTimeMillis();
            try { summary.app = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName; } catch (Exception unknown) { }
            JSONObject manifest = new JSONObject().put("format", FORMAT).put("version", VERSION).put("app", summary.app).put("created", summary.created)
                .put("prefs", summary.prefs).put("files", summary.files).put("recordings", summary.recordings).put("recording_bytes", summary.recordingBytes).put("secrets", summary.secrets);
            put(zip, "manifest.json", manifest.toString(1).getBytes(StandardCharsets.UTF_8));
        }
        return summary;
    }

    /** What a backup holds, from its manifest (checked to be one of this app's backups). */
    static Summary inspect(File file) throws IOException {
        try (ZipFile zip = new ZipFile(file)) {
            ZipEntry entry = zip.getEntry("manifest.json");
            if (entry == null) throw new IOException("This is not a Link Workshop backup (no manifest).");
            JSONObject m;
            try (InputStream in = zip.getInputStream(entry)) { m = new JSONObject(new String(readAll(in, 1024 * 1024), StandardCharsets.UTF_8)); }
            catch (JSONException bad) { throw new IOException("The backup's manifest is damaged."); }
            if (!FORMAT.equals(m.optString("format"))) throw new IOException("This is not a Link Workshop backup.");
            if (m.optInt("version") > VERSION) throw new IOException("This backup was made by a newer version of the app. Update the app first.");
            Summary s = new Summary();
            s.app = m.optString("app"); s.created = m.optLong("created"); s.prefs = m.optInt("prefs"); s.files = m.optInt("files");
            s.recordings = m.optInt("recordings"); s.recordingBytes = m.optLong("recording_bytes"); s.secrets = zip.getEntry(SECRETS) != null;
            return s;
        }
    }

    /**
     * Restores a backup: settings files are replaced by the backup's (their secrets on this phone are kept), data files are
     * replaced, recordings are added (ones already here are kept), secrets are restored when the password opens them.
     */
    static Summary restore(Context context, File file, char[] password) throws Exception {
        Summary summary = inspect(file);
        try (ZipFile zip = new ZipFile(file)) {
            // Secrets first: a wrong password stops the restore before anything changes.
            JSONObject secrets = null;
            ZipEntry sealed = zip.getEntry(SECRETS);
            if (sealed != null && password != null && password.length > 0) {
                try (InputStream in = zip.getInputStream(sealed)) { secrets = new JSONObject(new String(open(readAll(in, 4 * 1024 * 1024), password), StandardCharsets.UTF_8)); }
                catch (javax.crypto.AEADBadTagException wrong) { throw new IOException("The backup password is not right."); }
            }
            long total = 0;
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || name.equals("manifest.json") || name.equals(SECRETS)) continue;
                if (java.util.Arrays.asList(name.split("/")).contains("..") || name.startsWith("/") || name.contains("\\")) throw new IOException("The backup holds a file outside its folders: " + name);
                total += Math.max(0, entry.getSize()); if (total > MAX_TOTAL) throw new IOException("The backup is larger than expected.");
                if (name.startsWith("prefs/") && name.endsWith(".json")) {
                    String prefsName = name.substring(6, name.length() - 5);
                    if (skipPrefs(prefsName)) continue;
                    try (InputStream in = zip.getInputStream(entry)) { restorePrefs(context.getSharedPreferences(prefsName, Context.MODE_PRIVATE), new JSONObject(new String(readAll(in, 16 * 1024 * 1024), StandardCharsets.UTF_8)), prefsName.equals("printer-profile")); }
                } else if (name.startsWith("files/")) {
                    String fileName = name.substring(6);
                    if (!java.util.Arrays.asList(FILES).contains(fileName)) continue;
                    try (InputStream in = zip.getInputStream(entry)) { writeAtomic(new File(context.getFilesDir(), fileName), readAll(in, MAX_ENTRY)); }
                } else if (name.startsWith("recordings/")) {
                    File root = recordingsDir(context), target = new File(root, name.substring(11));
                    if (!target.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator)) throw new IOException("The backup holds a file outside its folders: " + name);
                    if (target.exists()) continue;
                    target.getParentFile().mkdirs();
                    try (InputStream in = zip.getInputStream(entry); OutputStream out = new FileOutputStream(target)) { copy(in, out, MAX_ENTRY); }
                }
            }
            if (secrets != null) {
                CredentialStore store = new CredentialStore(context);
                for (Iterator<String> it = secrets.keys(); it.hasNext(); ) { String key = it.next(); try { store.saveSecret(key, secrets.getString(key)); } catch (Exception skipped) { } }
            }
            summary.secrets = secrets != null;
        }
        return summary;
    }

    static JSONObject prefsJson(SharedPreferences prefs) throws JSONException {
        JSONObject out = new JSONObject();
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            if (skipKey(e.getKey()) || e.getValue() == null) continue;
            Object v = e.getValue(); String type;
            if (v instanceof String) type = "s"; else if (v instanceof Boolean) type = "b"; else if (v instanceof Integer) type = "i";
            else if (v instanceof Long) type = "l"; else if (v instanceof Float) type = "f"; else if (v instanceof Set) { type = "set"; v = new JSONArray(new ArrayList<>((Set<?>) v)); }
            else continue;
            out.put(e.getKey(), new JSONObject().put("t", type).put("v", v));
        }
        return out;
    }
    /** Replaces a settings file's values with the backup's; `keepOthers` keeps the keys the backup does not name (the printer profile's secrets). */
    static void restorePrefs(SharedPreferences prefs, JSONObject values, boolean keepOthers) throws JSONException {
        SharedPreferences.Editor edit = prefs.edit();
        if (!keepOthers) edit.clear();
        else for (String key : prefs.getAll().keySet()) if (!skipKey(key) && !values.has(key)) edit.remove(key);
        for (Iterator<String> it = values.keys(); it.hasNext(); ) {
            String key = it.next(); if (skipKey(key)) continue;
            JSONObject typed = values.optJSONObject(key); if (typed == null) continue;
            switch (typed.optString("t")) {
                case "s": edit.putString(key, typed.getString("v")); break;
                case "b": edit.putBoolean(key, typed.getBoolean("v")); break;
                case "i": edit.putInt(key, typed.getInt("v")); break;
                case "l": edit.putLong(key, typed.getLong("v")); break;
                case "f": edit.putFloat(key, (float) typed.getDouble("v")); break;
                case "set": { Set<String> set = new HashSet<>(); JSONArray a = typed.getJSONArray("v"); for (int i = 0; i < a.length(); i++) set.add(a.getString(i)); edit.putStringSet(key, set); break; }
                default: break;
            }
        }
        if (!edit.commit()) throw new IllegalStateException("Could not save the restored settings");
    }

    // Password protection for secrets: "LWS1", salt (16), IV (12), AES-256-GCM of the JSON, key from PBKDF2-HMAC-SHA256.
    static byte[] seal(byte[] plain, char[] password) throws Exception {
        SecureRandom random = new SecureRandom(); byte[] salt = new byte[16], iv = new byte[12]; random.nextBytes(salt); random.nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key(password, salt), new GCMParameterSpec(128, iv));
        cipher.updateAAD("LWS1".getBytes(StandardCharsets.US_ASCII));
        byte[] sealed = cipher.doFinal(plain);
        ByteArrayOutputStream out = new ByteArrayOutputStream(); out.write("LWS1".getBytes(StandardCharsets.US_ASCII)); out.write(salt); out.write(iv); out.write(sealed);
        return out.toByteArray();
    }
    static byte[] open(byte[] sealed, char[] password) throws Exception {
        if (sealed.length < 4 + 16 + 12 + 16 || !new String(sealed, 0, 4, StandardCharsets.US_ASCII).equals("LWS1")) throw new IOException("The backup's protected part is damaged.");
        byte[] salt = java.util.Arrays.copyOfRange(sealed, 4, 20), iv = java.util.Arrays.copyOfRange(sealed, 20, 32);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE, key(password, salt), new GCMParameterSpec(128, iv));
        cipher.updateAAD("LWS1".getBytes(StandardCharsets.US_ASCII));
        return cipher.doFinal(sealed, 32, sealed.length - 32);
    }
    private static SecretKeySpec key(char[] password, byte[] salt) throws Exception {
        byte[] raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(new PBEKeySpec(password, salt, ITERATIONS, 256)).getEncoded();
        return new SecretKeySpec(raw, "AES");
    }

    private static List<File> walk(File root) { List<File> out = new ArrayList<>(); walk(root, out); return out; }
    private static void walk(File f, List<File> out) { if (f.isFile()) { out.add(f); return; } File[] all = f.listFiles(); if (all != null) { java.util.Arrays.sort(all); for (File c : all) walk(c, out); } }
    private static void put(ZipOutputStream zip, String name, byte[] data) throws IOException { zip.putNextEntry(new ZipEntry(name)); zip.write(data); zip.closeEntry(); }
    private static byte[] read(File f, long max) throws IOException { try (InputStream in = new FileInputStream(f)) { return readAll(in, max); } }
    static byte[] readAll(InputStream in, long max) throws IOException { ByteArrayOutputStream out = new ByteArrayOutputStream(); copy(in, out, max); return out.toByteArray(); }
    static void copy(InputStream in, OutputStream out, long max) throws IOException {
        byte[] buffer = new byte[65536]; long total = 0; int n;
        while ((n = in.read(buffer)) > 0) { total += n; if (total > max) throw new IOException("A file in the backup is too large."); out.write(buffer, 0, n); }
    }
    private static void writeAtomic(File target, byte[] data) throws IOException {
        File temp = new File(target.getPath() + ".restore");
        try (OutputStream out = new FileOutputStream(temp)) { out.write(data); }
        if (!temp.renameTo(target)) throw new IOException("Could not write " + target.getName());
    }
}
