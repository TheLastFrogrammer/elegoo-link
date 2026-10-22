package io.github.thelastfrogrammer.elink;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.json.JSONObject;

/**
 * A print's G-code kept with its recording (compressed, beside the recording's files), so the charts, checks and live
 * toolpath have it for good: the phone's G-code archive keeps only its newest files, and its link between a chosen copy
 * and a print lasts only while the app watches that print without a break. Packed when the print runs from a copy the
 * app knows (one you chose, sliced or sent here), or added to the recording afterwards. Read through an unpacked copy in
 * the app's cache.
 */
final class RecordingGcode {
    /** How the copy came to the recording. */
    static final String CHOSEN = "chosen", MATCHED = "matched", ADDED = "added";
    static final int CACHE_FILES = 3;

    /** What was packed: the file's name, its SHA-256 (of the G-code itself), its size and how it came. */
    static final class Info {
        final String name, sha256, how; final long size, at;
        Info(String name, String sha256, long size, String how, long at) { this.name = name; this.sha256 = sha256; this.size = size; this.how = how; this.at = at; }
        String words() {
            String came = CHOSEN.equals(how) ? "the copy you chose for the print" : ADDED.equals(how) ? "added to the recording afterwards" : "the phone's copy of the print's file";
            return String.format(Locale.getDefault(), "%s · %s · %.1f MB · %s", name, came, size / 1048576.0, sha256.substring(0, 12));
        }
    }

    static File packed(PrintRecorder.Recording r) { return new File(r.meta.getParentFile(), base(r) + ".gcode.gz"); }
    private static File infoFile(PrintRecorder.Recording r) { return new File(r.meta.getParentFile(), base(r) + ".gcode.json"); }
    private static String base(PrintRecorder.Recording r) { return r.meta.getName().replaceFirst("\\.json$", ""); }

    static Info info(PrintRecorder.Recording r) {
        File info = infoFile(r);
        if (!info.isFile() || !packed(r).isFile() || info.length() > 64 * 1024) return null;
        try {
            JSONObject j = new JSONObject(new String(Files.readAllBytes(info.toPath()), StandardCharsets.UTF_8));
            String sha = j.optString("sha256");
            if (!sha.matches("[0-9a-f]{64}")) return null;
            return new Info(j.optString("name", r.file), sha, j.optLong("size"), j.optString("how", MATCHED), j.optLong("at"));
        } catch (Exception unreadable) { return null; }
    }

    /**
     * Packs `gcode` into the recording (replacing an earlier one); does nothing when the same file is already there.
     * Worker only: reads and compresses the whole file. Returns what is packed now.
     */
    static synchronized Info pack(File gcode, String name, PrintRecorder.Recording r, String how) throws IOException {
        if (gcode == null || !gcode.isFile() || gcode.length() == 0) throw new IOException("No G-code to keep");
        if (gcode.length() > GcodeLibrary.MAX_FILE_BYTES) throw new IOException("The G-code is too large to keep");
        String sha = sha256(gcode);
        Info had = info(r);
        if (had != null && had.sha256.equals(sha)) {
            // The same file: only a choice outranks a match (it says the person confirmed it).
            if (CHOSEN.equals(how) && !CHOSEN.equals(had.how)) writeInfo(r, new Info(had.name, sha, had.size, how, had.at));
            return info(r);
        }
        File target = packed(r), temporary = new File(target.getParentFile(), target.getName() + ".part");
        try {
            try (InputStream in = new FileInputStream(gcode); OutputStream out = new GZIPOutputStream(new FileOutputStream(temporary), 1 << 16)) {
                byte[] buffer = new byte[1 << 16]; int n;
                while ((n = in.read(buffer)) > 0) { if (Thread.currentThread().isInterrupted()) throw new IOException("Cancelled"); out.write(buffer, 0, n); }
            }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } finally { temporary.delete(); }
        Info kept = new Info(GcodeLibrary.baseName(name) == null ? r.file : GcodeLibrary.baseName(name), sha, gcode.length(), how, System.currentTimeMillis());
        writeInfo(r, kept);
        return kept;
    }

    /**
     * The packed G-code as a plain file to read (unpacked into `cacheDir` once, checked against its SHA-256), or null when the
     * recording has none or it is damaged. Worker only.
     */
    static synchronized File open(PrintRecorder.Recording r, File cacheDir) {
        Info info = info(r); if (info == null) return null;
        File folder = new File(cacheDir, "recording-gcode"); folder.mkdirs();
        File plain = new File(folder, info.sha256 + ".gcode");
        if (plain.isFile() && plain.length() == info.size) { plain.setLastModified(System.currentTimeMillis()); return plain; }
        File temporary = new File(folder, info.sha256 + ".part");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256"); long size = 0;
            try (InputStream in = new GZIPInputStream(new FileInputStream(packed(r)), 1 << 16); OutputStream out = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[1 << 16]; int n;
                while ((n = in.read(buffer)) > 0) { size += n; if (size > GcodeLibrary.MAX_FILE_BYTES) throw new IOException("Too large"); digest.update(buffer, 0, n); out.write(buffer, 0, n); }
            }
            if (size != info.size || !hex(digest.digest()).equals(info.sha256)) return null;
            Files.move(temporary.toPath(), plain.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception damaged) { return null; }
        finally { temporary.delete(); }
        File[] all = folder.listFiles((d, n) -> n.endsWith(".gcode"));
        if (all != null && all.length > CACHE_FILES) {
            Arrays.sort(all, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            for (int i = CACHE_FILES; i < all.length; i++) if (!all[i].equals(plain)) all[i].delete();
        }
        return plain;
    }

    /** Takes the G-code out of the recording. */
    static synchronized void remove(PrintRecorder.Recording r) { packed(r).delete(); infoFile(r).delete(); }

    /**
     * Whether a file fits the recording, in words, or "" when nothing says otherwise: a different name, a different layer
     * count than the printer reported for the print, or fewer layers than it reached. Pure, for tests.
     */
    static String mismatch(String recordedName, int totalLayers, int reachedLayer, String fileName, int fileLayers) {
        List<String> words = new ArrayList<>();
        String a = GcodeLibrary.baseName(recordedName), b = GcodeLibrary.baseName(fileName);
        if (a != null && b != null && !a.equals(b)) words.add("The print was named " + a + "; this file is " + b + ".");
        if (fileLayers > 0 && totalLayers > 0 && totalLayers != fileLayers) words.add(String.format(Locale.getDefault(), "The printer reported %d layers for the print; this file has %d.", totalLayers, fileLayers));
        else if (fileLayers > 0 && reachedLayer > fileLayers) words.add(String.format(Locale.getDefault(), "The print reached layer %d; this file has only %d layers.", reachedLayer, fileLayers));
        return String.join(" ", words);
    }

    private static void writeInfo(PrintRecorder.Recording r, Info i) throws IOException {
        File target = infoFile(r), temporary = new File(target.getParentFile(), target.getName() + ".part");
        try {
            String json = new JSONObject().put("name", i.name).put("sha256", i.sha256).put("size", i.size).put("how", i.how).put("at", i.at).toString();
            Files.write(temporary.toPath(), json.getBytes(StandardCharsets.UTF_8));
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (org.json.JSONException impossible) { throw new IOException(impossible); }
        finally { temporary.delete(); }
    }
    static String sha256(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new FileInputStream(file)) { byte[] buffer = new byte[1 << 16]; int n; while ((n = in.read(buffer)) > 0) digest.update(buffer, 0, n); }
            return hex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IOException(impossible); }
    }
    private static String hex(byte[] bytes) { StringBuilder out = new StringBuilder(); for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b & 255)); return out.toString(); }

    private RecordingGcode() { }
}
