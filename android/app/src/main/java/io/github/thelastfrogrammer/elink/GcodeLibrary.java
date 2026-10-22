package io.github.thelastfrogrammer.elink;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONArray;
import org.json.JSONObject;

/** Content-addressed phone copies. Names/printer associations are candidates, never proof of a running job. */
public final class GcodeLibrary {
    static final int KEEP_FILES = 12;
    static final long KEEP_BYTES = 1536L * 1024 * 1024, MAX_FILE_BYTES = 512L * 1024 * 1024;
    private static final int MAX_INDEX_BYTES = 1024 * 1024;
    private static final Map<String, Object> LOCKS = new ConcurrentHashMap<>();
    private final File directory, objects, index;
    private final Object lock;
    private final int keepFiles; private final long keepBytes;

    public enum Origin {
        IMPORTED("Phone import"), SLICED("Sliced on this phone"), UPLOADED("Uploaded from this app"),
        DOWNLOADED("Downloaded copy"), FOLDER("G-code folder"), CHOSEN("Chosen file"), LEGACY("Earlier phone copy");
        public final String label;
        Origin(String label) { this.label = label; }
    }
    public static final class Artifact {
        public final File file;
        public final String sha256, name, printer;
        public final long size, at;
        public final Origin origin;
        private Artifact(File file, String hash, long size, String name, String printer, Origin origin, long at) {
            this.file = file; sha256 = hash; this.size = size; this.name = name; this.printer = printer; this.origin = origin; this.at = at;
        }
        public String shortHash() { return sha256.substring(0, 12); }
    }
    public GcodeLibrary(File directory) { this(directory, KEEP_FILES, KEEP_BYTES); }
    GcodeLibrary(File directory, int keepFiles, long keepBytes) {
        this.directory = directory; objects = new File(directory, "objects"); index = new File(directory, "index-v2.json");
        this.keepFiles = keepFiles; this.keepBytes = keepBytes;
        String key; try { key = directory.getCanonicalPath(); } catch (IOException error) { key = directory.getAbsolutePath(); }
        lock = LOCKS.computeIfAbsent(key, ignored -> new Object());
    }
    /** Worker only. Existing names/versions are preserved. */
    public File put(File source, String name) throws IOException { return retain(source, name, "", Origin.IMPORTED).file; }
    public Artifact retain(File source, String name, String printer, Origin origin) throws IOException {
        String base = baseName(name); if (base == null) throw new IOException("Unusable G-code filename");
        synchronized (lock) {
            JSONObject state = readIndex();
            if (Files.isSymbolicLink(directory.toPath()) || Files.isSymbolicLink(objects.toPath())) throw new IOException("Archive directory cannot be a symbolic link");
            if (!objects.isDirectory() && !objects.mkdirs()) throw new IOException("Cannot create G-code archive");
            File temporary = File.createTempFile("incoming-", ".part", objects);
            long size = 0; MessageDigest digest = digest();
            try {
                try (InputStream in = new FileInputStream(source); FileOutputStream out = new FileOutputStream(temporary)) {
                    byte[] buffer = new byte[65536]; int count;
                    while ((count = in.read(buffer)) != -1) {
                        if (Thread.currentThread().isInterrupted()) throw new IOException("G-code retention canceled");
                        size += count; if (size > MAX_FILE_BYTES || size > keepBytes) throw new IOException("G-code exceeds the archive limit");
                        digest.update(buffer, 0, count); out.write(buffer, 0, count);
                    }
                    if (size == 0) throw new IOException("G-code is empty");
                    out.getFD().sync();
                }
                String hash = hex(digest.digest()); File target = object(hash);
                replace(temporary, target); long now = System.currentTimeMillis(); target.setLastModified(now);
                Artifact kept = new Artifact(target, hash, size, base, printerId(printer), origin, now);
                JSONArray entries = state.optJSONArray("entries"), updated = new JSONArray();
                for (int i = 0; i < entries.length(); i++) {
                    JSONObject old = entries.optJSONObject(i); if (old == null) continue;
                    if (hash.equals(old.optString("sha256")) && base.equals(old.optString("name")) && kept.printer.equals(old.optString("printer"))) continue;
                    updated.put(old);
                }
                updated.put(json(kept)); state.put("entries", newest(updated, 256)); writeIndex(state); prune(state, target);
                return kept;
            } catch (org.json.JSONException error) { throw new IOException("Cannot record retained G-code", error); }
            finally { temporary.delete(); }
        }
    }
    /** Worker-only migration. Earlier files remain unverified candidates. */
    public void migrateLegacy() throws IOException {
        synchronized (lock) {
            sweepParts(directory); sweepParts(objects);
            File[] old = directory.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".gcode"));
            if (old == null) return;
            IOException failure = null;
            for (File file : old) if (file.isFile() && !Files.isSymbolicLink(file.toPath())) {
                try { retain(file, file.getName(), "", Origin.LEGACY); if (!file.delete()) throw new IOException("Earlier G-code copy could not be retired"); }
                catch (IOException error) { failure = error; }
            }
            if (failure != null) throw failure;
        }
    }
    private void sweepParts(File folder) {
        if (Files.isSymbolicLink(folder.toPath())) return;
        File[] parts = folder.listFiles((d, name) -> name.endsWith(".part") && (name.startsWith("incoming-") || name.startsWith("index-") || name.endsWith(".gcode.part")));
        if (parts != null) for (File part : parts) if (part.isFile() && !Files.isSymbolicLink(part.toPath())) part.delete();
    }
    /** Candidates from this printer or unscoped phone inputs. Callers must explicitly attach one to the job. */
    public List<Artifact> candidates(String printer, String name) throws IOException {
        synchronized (lock) {
            String base = baseName(name), serial = printerId(printer); if (base == null) return Collections.emptyList();
            JSONArray entries = readIndex().optJSONArray("entries"); List<Artifact> matches = new ArrayList<>();
            for (int i = 0; i < entries.length(); i++) {
                Artifact a = artifact(entries.optJSONObject(i));
                if (!available(a) || !a.printer.isEmpty() && !a.printer.equals(serial)) continue;
                if (base.equals(a.name) || a.origin == Origin.LEGACY && Objects.equals(safeName(base), a.name)) matches.add(a);
            }
            matches.sort((a, b) -> Long.compare(b.at, a.at)); Map<String, Artifact> unique = new LinkedHashMap<>();
            for (Artifact a : matches) unique.putIfAbsent(a.sha256, a);
            return new ArrayList<>(unique.values());
        }
    }
    /**
     * Every kept copy, newest first, one per content (for picking a print's G-code by hand, e.g. for a recording); with
     * `name`, only copies by that name. Any printer's: the caller shows where each came from.
     */
    public List<Artifact> copies(String name) throws IOException {
        synchronized (lock) {
            String base = name == null ? null : baseName(name);
            JSONArray entries = readIndex().optJSONArray("entries"); List<Artifact> all = new ArrayList<>();
            for (int i = 0; i < entries.length(); i++) {
                Artifact a = artifact(entries.optJSONObject(i));
                if (available(a) && (name == null || a.name.equals(base))) all.add(a);
            }
            all.sort((a, b) -> Long.compare(b.at, a.at)); Map<String, Artifact> unique = new LinkedHashMap<>();
            for (Artifact a : all) unique.putIfAbsent(a.sha256, a);
            return new ArrayList<>(unique.values());
        }
    }
    /** Compatibility lookup: only one unscoped candidate. Live following uses selected(), never this method. */
    public File find(String name) {
        try { List<Artifact> matches = candidates("", name); return matches.size() == 1 ? matches.get(0).file : null; }
        catch (IOException error) { return null; }
    }
    /** Explicit human association to an observed job token; not printer-verified bytes. Worker only. */
    public void selectForJob(Artifact a, String printer, String job) throws IOException {
        String serial = printerId(printer);
        if (serial.isEmpty() || job == null || job.isEmpty() || job.length() > 200) throw new IOException("No current printer/job identity");
        synchronized (lock) {
            if (a == null || !a.printer.isEmpty() && !a.printer.equals(serial)) throw new IOException("Copy belongs to another printer");
            if (!object(a.sha256).getCanonicalFile().equals(a.file.getCanonicalFile())) throw new IOException("Copy belongs to another archive");
            if (!verify(a)) throw new IOException("Retained G-code is missing or changed");
            JSONObject state = readIndex(); JSONArray old = state.optJSONArray("bindings"), updated = new JSONArray();
            try {
                for (int i = 0; i < old.length(); i++) {
                    JSONObject row = old.optJSONObject(i); if (row == null) continue;
                    if (serial.equals(row.optString("printer")) && job.equals(row.optString("job"))) continue;
                    updated.put(row);
                }
                updated.put(json(a).put("printer", serial).put("job", job).put("at", System.currentTimeMillis()));
                state.put("bindings", newest(updated, 64)); writeIndex(state); a.file.setLastModified(System.currentTimeMillis());
            } catch (org.json.JSONException error) { throw new IOException("Cannot associate G-code", error); }
        }
    }
    public Artifact selected(String printer, String job) {
        synchronized (lock) { try { Artifact a = binding(readIndex(), printer, job); return available(a) ? a : null; } catch (IOException error) { return null; } }
    }
    public boolean selectionEvicted(String printer, String job) {
        synchronized (lock) { try { Artifact a = binding(readIndex(), printer, job); return a != null && !available(a); } catch (IOException error) { return false; } }
    }
    /** Removes a failed selection only if a newer choice has not replaced it. */
    public void forgetSelection(String printer, String job, String expectedHash) throws IOException {
        synchronized (lock) {
            JSONObject state = readIndex(); JSONArray old = state.optJSONArray("bindings"), kept = new JSONArray();
            for (int i = 0; i < old.length(); i++) {
                JSONObject row = old.optJSONObject(i); if (row == null) continue;
                if (printerId(printer).equals(row.optString("printer")) && Objects.equals(job, row.optString("job")) && Objects.equals(expectedHash, row.optString("sha256"))) continue;
                kept.put(row);
            }
            try { state.put("bindings", kept); writeIndex(state); }
            catch (org.json.JSONException error) { throw new IOException("Cannot clear failed selection", error); }
        }
    }
    private Artifact binding(JSONObject state, String printer, String job) {
        if (job == null || job.isEmpty() || printerId(printer).isEmpty()) return null;
        JSONArray rows = state.optJSONArray("bindings");
        for (int i = rows.length() - 1; i >= 0; i--) {
            JSONObject row = rows.optJSONObject(i);
            if (row != null && printerId(printer).equals(row.optString("printer")) && job.equals(row.optString("job"))) return artifact(row);
        }
        return null;
    }
    /** Worker-only hash check before parsing a selected file. */
    public boolean verify(Artifact a) throws IOException {
        if (!available(a)) return false;
        MessageDigest digest = digest(); long size = 0;
        try (InputStream in = new FileInputStream(a.file)) {
            byte[] buffer = new byte[65536]; int count;
            while ((count = in.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("G-code check canceled");
                size += count; if (size > a.size) return false; digest.update(buffer, 0, count);
            }
        }
        return size == a.size && hex(digest.digest()).equals(a.sha256);
    }
    public List<File> list() { synchronized (lock) { return objectFiles(); } }
    private List<File> objectFiles() {
        File[] files = objects.listFiles((dir, name) -> name.matches("[0-9a-f]{64}\\.gcode")); List<File> out = new ArrayList<>();
        if (files != null) for (File f : files) if (f.isFile() && !Files.isSymbolicLink(f.toPath())) out.add(f);
        out.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified())); return out;
    }
    private void prune(JSONObject state, File newest) throws IOException {
        long bytes = 0; int count = 0; Set<String> removed = new HashSet<>();
        List<File> ordered = objectFiles(); ordered.sort((a, b) -> a.equals(newest) ? b.equals(newest) ? 0 : -1 : b.equals(newest) ? 1 : 0);
        for (File file : ordered) {
            bytes += file.length(); count++;
            if ((count > keepFiles || bytes > keepBytes) && file.delete()) removed.add(file.getName().substring(0, 64));
        }
        if (!removed.isEmpty()) try {
            JSONArray kept = new JSONArray(), entries = state.optJSONArray("entries");
            for (int i = 0; i < entries.length(); i++) {
                JSONObject row = entries.optJSONObject(i); if (row != null && !removed.contains(row.optString("sha256"))) kept.put(row);
            }
            state.put("entries", kept); writeIndex(state); // bindings remain as explicit eviction records
        } catch (org.json.JSONException error) { throw new IOException("Cannot update archive budget", error); }
    }
    private JSONObject readIndex() throws IOException {
        try {
            if (!index.exists()) return new JSONObject().put("version", 2).put("entries", new JSONArray()).put("bindings", new JSONArray());
            if (!index.isFile() || Files.isSymbolicLink(index.toPath()) || index.length() > MAX_INDEX_BYTES) throw new IOException("Invalid archive index");
            JSONObject state = new JSONObject(new String(Files.readAllBytes(index.toPath()), StandardCharsets.UTF_8));
            if (state.optInt("version") != 2 || state.optJSONArray("entries") == null || state.optJSONArray("bindings") == null) throw new IOException("Unknown archive index");
            return state;
        } catch (org.json.JSONException error) { throw new IOException("Unreadable archive index", error); }
    }
    private void writeIndex(JSONObject state) throws IOException {
        byte[] bytes = state.toString().getBytes(StandardCharsets.UTF_8); if (bytes.length > MAX_INDEX_BYTES) throw new IOException("Archive index exceeds its limit");
        File temporary = File.createTempFile("index-", ".part", directory);
        try { try (FileOutputStream out = new FileOutputStream(temporary)) { out.write(bytes); out.getFD().sync(); } replace(temporary, index); }
        finally { temporary.delete(); }
    }
    private Artifact artifact(JSONObject value) {
        if (value == null) return null;
        String hash = value.optString("sha256"), name = baseName(value.optString("name")); long size = value.optLong("size", -1);
        if (!hash.matches("[0-9a-f]{64}") || name == null || size <= 0 || size > MAX_FILE_BYTES) return null;
        Origin origin; try { origin = Origin.valueOf(value.optString("source")); } catch (IllegalArgumentException error) { origin = Origin.LEGACY; }
        return new Artifact(object(hash), hash, size, name, printerId(value.optString("printer")), origin, value.optLong("at"));
    }
    private boolean available(Artifact a) { return a != null && !Files.isSymbolicLink(objects.toPath()) && a.file.isFile() && !Files.isSymbolicLink(a.file.toPath()) && a.file.length() == a.size; }
    private File object(String hash) { return new File(objects, hash + ".gcode"); }
    private static JSONObject json(Artifact a) throws org.json.JSONException {
        return new JSONObject().put("sha256", a.sha256).put("size", a.size).put("name", a.name).put("printer", a.printer).put("source", a.origin.name()).put("at", a.at);
    }
    private static JSONArray newest(JSONArray input, int limit) {
        List<JSONObject> rows = new ArrayList<>(); for (int i = input.length() - 1; i >= 0; i--) if (input.optJSONObject(i) != null) rows.add(input.optJSONObject(i));
        rows.sort((a, b) -> Long.compare(b.optLong("at"), a.optLong("at"))); JSONArray out = new JSONArray();
        for (int i = 0; i < Math.min(limit, rows.size()); i++) out.put(rows.get(i)); return out;
    }
    private static void replace(File source, File target) throws IOException {
        try { Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException error) { Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING); }
    }
    static String printerId(String value) { return value == null ? "" : value.trim().toUpperCase(Locale.ROOT); }
    static String baseName(String value) {
        if (value == null) return null; String base = value.substring(value.lastIndexOf('/') + 1).trim();
        if (base.isEmpty() || base.equals(".") || base.equals("..") || base.length() > 200) return null;
        for (int i = 0; i < base.length(); i++) if (base.charAt(i) < 32 || base.charAt(i) == 127) return null;
        return base;
    }
    /** Legacy flat-cache filename; only used to recognize earlier copies during migration. */
    static String safeName(String name) {
        String base = baseName(name); if (base == null) return null;
        String plain = base.replaceAll("[^A-Za-z0-9 _.()+-]", "_"); if (plain.equals(base)) return plain;
        int dot = plain.lastIndexOf('.'); String stem = dot > 0 ? plain.substring(0, dot) : plain, extension = dot > 0 ? plain.substring(dot) : "";
        return stem + "~" + hex(digest().digest(base.getBytes(StandardCharsets.UTF_8))).substring(0, 12) + extension;
    }
    private static MessageDigest digest() { try { return MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); } }
    private static String hex(byte[] bytes) { StringBuilder out = new StringBuilder(); for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b & 255)); return out.toString(); }
}
