package io.github.thelastfrogrammer.elink;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Projects: what someone is making, outlined as planned parts before any model exists, with the files and links that belong
 * to it. Files are kept once ("assets") and joined to projects and parts by attachments, so one model can serve several
 * projects without a second copy, and removing it from one project never deletes it from another. Names are labels only:
 * every project, part and asset has its own id, and a kept file's content is identified by its SHA-256.
 *
 * Stored as one versioned JSON file (projects.json) written atomically with the previous good copy kept beside it.
 * Independent of Android; the screen is ProjectsActivity.
 */
final class Projects {
    static final String FORMAT = "linkWorkshopProjects";
    static final int VERSION = 1, MAX_QUANTITY = 9999;
    /** Part kinds: only printed and purchased parts have quantities; a task is done or not; a reference is a note. */
    static final String PRINTED = "printed", PURCHASED = "purchased", TASK = "task", REFERENCE = "reference";
    static final String[][] KINDS = {{PRINTED, "Printed part"}, {PURCHASED, "Purchased part"}, {TASK, "Task"}, {REFERENCE, "Reference / note"}};
    /** What a file is for. */
    static final String[][] ROLES = {{"model", "Model"}, {"source", "CAD / source"}, {"gcode", "G-code"}, {"image", "Image"}, {"document", "Document"}, {"link", "Web link"}};
    /** How a file is held: a copy kept by the app, a link to a document elsewhere on the phone, or a web address. */
    static final String MANAGED = "managed", EXTERNAL = "external", WEB = "web";

    static final class Asset {
        String id = UUID.randomUUID().toString(), name = "", role = "document", storage = MANAGED;
        /** Managed: the path under the assets folder. External: the document's content:// address. Web: the URL. */
        String location = "";
        /** SHA-256 of the bytes when they were last read ("" when never read); earlier ones when a relink brought different bytes. */
        String sha256 = "", mime = "";
        final List<String> earlierHashes = new ArrayList<>();
        long size = -1, added;

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject().put("id", id).put("name", name).put("role", role).put("storage", storage).put("location", location)
                .put("sha256", sha256).put("mime", mime).put("size", size).put("added", added);
            if (!earlierHashes.isEmpty()) o.put("earlier_hashes", new JSONArray(earlierHashes));
            return o;
        }
        static Asset fromJson(JSONObject o) throws JSONException {
            Asset a = new Asset(); a.id = need(o, "id"); a.name = Automation.cap(o.optString("name"), 200); a.role = known(ROLES, o.optString("role"), "document");
            a.storage = o.optString("storage"); if (!a.storage.equals(MANAGED) && !a.storage.equals(EXTERNAL) && !a.storage.equals(WEB)) throw new JSONException("unknown storage");
            a.location = o.optString("location"); a.sha256 = o.optString("sha256"); a.mime = o.optString("mime"); a.size = o.optLong("size", -1); a.added = o.optLong("added");
            JSONArray earlier = o.optJSONArray("earlier_hashes"); for (int i = 0; earlier != null && i < earlier.length(); i++) a.earlierHashes.add(earlier.optString(i));
            if (a.storage.equals(MANAGED) && !safeRelative(a.location)) throw new JSONException("a kept file's path leaves its folder");
            return a;
        }
        boolean sliceable() { return storage.equals(MANAGED) && ModelSites.MODEL_TYPES.contains(ModelSites.extension(name)); }
        boolean gcode() { return storage.equals(MANAGED) && name.toLowerCase(Locale.ROOT).endsWith(".gcode"); }
    }

    static final class Part {
        String id = UUID.randomUUID().toString(), name = "", kind = PRINTED, notes = "";
        /** How many the project needs (printed and purchased parts). Planning only: nothing here says any are made. */
        int quantity = 1;
        /** Purchased parts: how many are at hand. */
        int have;
        /** Tasks: done or not. */
        boolean done;

        boolean counted() { return kind.equals(PRINTED) || kind.equals(PURCHASED); }
        JSONObject toJson() throws JSONException {
            return new JSONObject().put("id", id).put("name", name).put("kind", kind).put("notes", notes).put("quantity", quantity).put("have", have).put("done", done);
        }
        static Part fromJson(JSONObject o) throws JSONException {
            Part p = new Part(); p.id = need(o, "id"); p.name = Automation.cap(o.optString("name"), 120); p.kind = known(KINDS, o.optString("kind"), PRINTED);
            p.notes = Automation.cap(o.optString("notes"), 4000); p.quantity = clamp(o.optInt("quantity", 1)); p.have = Math.max(0, Math.min(MAX_QUANTITY, o.optInt("have")));
            p.done = o.optBoolean("done"); return p;
        }
    }

    /** One file joined to a project, and to one of its parts ("" for the project as a whole). */
    static final class Attachment {
        final String assetId, partId;
        Attachment(String assetId, String partId) { this.assetId = assetId; this.partId = partId == null ? "" : partId; }
    }

    static final class Project {
        String id = UUID.randomUUID().toString(), title = "", description = "";
        boolean archived; long created, updated;
        /** The cover page's choices (CoverPage.Options as JSON); empty for the defaults. */
        JSONObject cover = new JSONObject();
        final List<Part> parts = new ArrayList<>();
        final List<Attachment> attachments = new ArrayList<>();

        Part part(String partId) { for (Part p : parts) if (p.id.equals(partId)) return p; return null; }
        /** The files on a part, or on the project itself with "", in the order they were added. */
        List<String> assetsOn(String partId) {
            List<String> out = new ArrayList<>(); for (Attachment a : attachments) if (a.partId.equals(partId == null ? "" : partId)) out.add(a.assetId); return out;
        }
        /** Every file in the project, each once. */
        Set<String> allAssets() { Set<String> out = new LinkedHashSet<>(); for (Attachment a : attachments) out.add(a.assetId); return out; }
        JSONObject toJson() throws JSONException {
            JSONArray partList = new JSONArray(); for (Part p : parts) partList.put(p.toJson());
            JSONArray links = new JSONArray(); for (Attachment a : attachments) links.put(new JSONObject().put("asset", a.assetId).put("part", a.partId));
            return new JSONObject().put("id", id).put("title", title).put("description", description).put("archived", archived)
                .put("created", created).put("updated", updated).put("parts", partList).put("attachments", links).put("cover", cover);
        }
        static Project fromJson(JSONObject o) throws JSONException {
            Project p = new Project(); p.id = need(o, "id"); p.title = Automation.cap(o.optString("title"), 120); p.description = Automation.cap(o.optString("description"), 4000);
            p.archived = o.optBoolean("archived"); p.created = o.optLong("created"); p.updated = o.optLong("updated");
            JSONObject cover = o.optJSONObject("cover"); if (cover != null && cover.toString().length() < 8000) p.cover = cover;
            JSONArray parts = o.optJSONArray("parts"); for (int i = 0; parts != null && i < parts.length(); i++) p.parts.add(Part.fromJson(parts.getJSONObject(i)));
            JSONArray links = o.optJSONArray("attachments");
            for (int i = 0; links != null && i < links.length(); i++) {
                JSONObject a = links.getJSONObject(i); String part = a.optString("part");
                if (!part.isEmpty() && p.part(part) == null) part = "";   // its part is gone: the file stays with the project
                p.attachments.add(new Attachment(need(a, "asset"), part));
            }
            return p;
        }
    }

    /** Everything: the projects and the files they share. */
    static final class Book {
        final List<Project> projects = new ArrayList<>();
        final List<Asset> assets = new ArrayList<>();

        Project project(String id) { for (Project p : projects) if (p.id.equals(id)) return p; return null; }
        Asset asset(String id) { for (Asset a : assets) if (a.id.equals(id)) return a; return null; }

        Project create(String title, long now) {
            Project p = new Project(); p.title = title(title); p.created = p.updated = now; projects.add(p); return p;
        }
        Part addPart(Project project, String name, String kind, int quantity, long now) {
            Part part = new Part(); part.name = name == null || name.trim().isEmpty() ? "Part" : Automation.cap(name.trim(), 120);
            part.kind = known(KINDS, kind, PRINTED); part.quantity = clamp(quantity);
            project.parts.add(part); project.updated = now; return part;
        }
        /** Moves a part up (-1) or down (+1) in its project's list. */
        boolean movePart(Project project, String partId, int by) {
            int at = -1; for (int i = 0; i < project.parts.size(); i++) if (project.parts.get(i).id.equals(partId)) at = i;
            int to = at + by; if (at < 0 || to < 0 || to >= project.parts.size()) return false;
            project.parts.add(to, project.parts.remove(at)); return true;
        }
        /** Removes a planned part. Its files stay with the project (not deleted, not taken from other parts or projects). */
        void removePart(Project project, String partId) {
            project.parts.removeIf(p -> p.id.equals(partId));
            List<Attachment> moved = new ArrayList<>();
            for (Attachment a : project.attachments) moved.add(a.partId.equals(partId) ? new Attachment(a.assetId, "") : a);
            project.attachments.clear();
            for (Attachment a : moved) if (!has(project, a.assetId, a.partId)) project.attachments.add(a);
        }
        /** Joins a file to a project (and part); false when it is already there. */
        boolean attach(Project project, String assetId, String partId) {
            if (asset(assetId) == null || has(project, assetId, partId)) return false;
            project.attachments.add(new Attachment(assetId, partId)); return true;
        }
        /** Takes a file off a project's part (or off the project, with ""); the file itself and its other places stay. */
        boolean detach(Project project, String assetId, String partId) {
            String part = partId == null ? "" : partId;
            return project.attachments.removeIf(a -> a.assetId.equals(assetId) && a.partId.equals(part));
        }
        boolean has(Project project, String assetId, String partId) {
            String part = partId == null ? "" : partId;
            for (Attachment a : project.attachments) if (a.assetId.equals(assetId) && a.partId.equals(part)) return true;
            return false;
        }
        /** Where a file is used: "Lamp › Retaining clip", "Lamp", … */
        List<String> usedIn(String assetId) {
            List<String> out = new ArrayList<>();
            for (Project p : projects) for (Attachment a : p.attachments) if (a.assetId.equals(assetId)) {
                Part part = p.part(a.partId); out.add(p.title + (part == null ? "" : " › " + part.name));
            }
            return out;
        }
        /** Files in no project. */
        List<Asset> unassigned() {
            Set<String> used = new LinkedHashSet<>(); for (Project p : projects) used.addAll(p.allAssets());
            List<Asset> out = new ArrayList<>(); for (Asset a : assets) if (!used.contains(a.id)) out.add(a); return out;
        }
        /** A kept file with these bytes already, so the same file is never kept twice. */
        Asset managedWithHash(String sha256) {
            if (sha256 == null || sha256.isEmpty()) return null;
            for (Asset a : assets) if (a.storage.equals(MANAGED) && a.sha256.equals(sha256)) return a;
            return null;
        }
        /**
         * Takes in a freshly copied file: when a kept file already has the same bytes that one is returned (and the caller
         * deletes the new copy's bytes), so the same file is never kept twice.
         */
        Asset adopt(Asset fresh) {
            Asset same = fresh.storage.equals(MANAGED) ? managedWithHash(fresh.sha256) : null;
            if (same != null) return same;
            assets.add(fresh); return fresh;
        }
        /** Forgets a file that no project uses any more; false while it is still used. The caller deletes a kept copy's bytes. */
        boolean forget(String assetId) {
            for (Project p : projects) for (Attachment a : p.attachments) if (a.assetId.equals(assetId)) return false;
            return assets.removeIf(a -> a.id.equals(assetId));
        }

        String toJson() throws JSONException {
            JSONArray list = new JSONArray(); for (Project p : projects) list.put(p.toJson());
            JSONArray files = new JSONArray(); for (Asset a : assets) files.put(a.toJson());
            return new JSONObject().put("format", FORMAT).put("version", VERSION).put("projects", list).put("assets", files).toString(1);
        }
        static Book fromJson(String text) throws JSONException {
            JSONObject o = new JSONObject(text);
            if (!FORMAT.equals(o.optString("format"))) throw new JSONException("not a projects file");
            if (o.optInt("version") > VERSION) throw new JSONException("made by a newer version of the app");
            Book book = new Book();
            JSONArray files = o.optJSONArray("assets");
            for (int i = 0; files != null && i < files.length(); i++) book.assets.add(Asset.fromJson(files.getJSONObject(i)));
            JSONArray list = o.optJSONArray("projects");
            for (int i = 0; list != null && i < list.length(); i++) {
                Project p = Project.fromJson(list.getJSONObject(i));
                p.attachments.removeIf(a -> book.asset(a.assetId) == null);
                book.projects.add(p);
            }
            return book;
        }
    }

    // ------------------------------------------------------------------ storage

    static File file(File dir) { return new File(dir, "projects.json"); }
    static File assetsDir(File dir) { return new File(dir, "assets"); }

    /** Reads the projects; an unreadable file falls back to the previous good copy, and with neither the book starts empty. */
    static Book load(File dir) {
        for (File f : new File[] {file(dir), new File(dir, "projects.json.bak")}) {
            if (!f.isFile()) continue;
            try { return Book.fromJson(new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)); }
            catch (Exception unreadable) { Diagnostics.note(Diagnostics.FILES, "projects: " + f.getName() + " could not be read (" + unreadable.getClass().getSimpleName() + ")"); }
        }
        return new Book();
    }
    /** Writes the projects whole: to a temporary file, then in place, keeping the previous copy as projects.json.bak. */
    static synchronized void save(File dir, Book book) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
        byte[] data;
        try { data = book.toJson().getBytes(StandardCharsets.UTF_8); } catch (JSONException impossible) { throw new IOException(impossible); }
        File temp = new File(dir, "projects.json.tmp"), target = file(dir), backup = new File(dir, "projects.json.bak");
        try (FileOutputStream out = new FileOutputStream(temp)) { out.write(data); out.getFD().sync(); }
        if (target.isFile()) { backup.delete(); if (!target.renameTo(backup)) java.nio.file.Files.copy(target.toPath(), backup.toPath()); }
        if (!temp.renameTo(target)) throw new IOException("Could not save the projects");
    }

    /**
     * Copies a file in: read once from `in`, hashed while copied under its own folder in assets. The result is not in the book
     * yet: hand it to Book.adopt (on the thread that owns the book).
     */
    static Asset copyIn(File dir, InputStream in, String name, String mime, long now) throws IOException {
        Asset asset = new Asset(); asset.name = displayName(name); asset.role = roleFor(asset.name, mime); asset.storage = MANAGED; asset.mime = mime == null ? "" : mime; asset.added = now;
        String folder = asset.id;
        File target = new File(new File(assetsDir(dir), folder), storedName(asset.name));
        target.getParentFile().mkdirs();
        MessageDigest digest = sha256(); long size = 0;
        try (OutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[65536]; int n;
            while ((n = in.read(buffer)) > 0) { out.write(buffer, 0, n); digest.update(buffer, 0, n); size += n; }
        } catch (IOException failed) { deleteTree(target.getParentFile()); throw failed; }
        asset.sha256 = hex(digest.digest()); asset.size = size; asset.location = folder + "/" + target.getName();
        return asset;
    }
    static File managedFile(File dir, Asset asset) { return asset.storage.equals(MANAGED) && safeRelative(asset.location) ? new File(assetsDir(dir), asset.location) : null; }
    /** Deletes a kept copy's bytes (after Book.forget). */
    static void deleteBytes(File dir, Asset asset) { File f = managedFile(dir, asset); if (f != null) deleteTree(f.getParentFile()); }

    /** SHA-256 of a stream, read to the end. */
    static String hash(InputStream in) throws IOException {
        MessageDigest digest = sha256(); byte[] buffer = new byte[65536]; int n;
        while ((n = in.read(buffer)) > 0) digest.update(buffer, 0, n);
        return hex(digest.digest());
    }
    /**
     * A linked file read again: records new bytes as a change (the earlier hash is kept) rather than silently treating them as
     * the same file. Returns true when the bytes differ from those last seen.
     */
    static boolean recordHash(Asset asset, String sha256) {
        if (sha256 == null || sha256.isEmpty() || sha256.equals(asset.sha256)) return false;
        boolean changed = !asset.sha256.isEmpty();
        if (changed && !asset.earlierHashes.contains(asset.sha256)) asset.earlierHashes.add(asset.sha256);
        asset.sha256 = sha256; return changed;
    }

    static String roleFor(String name, String mime) {
        String ext = ModelSites.extension(name), type = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        switch (ext) {
            case "stl": case "3mf": case "obj": case "amf": return "model";
            case "step": case "stp": case "f3d": case "fcstd": case "scad": case "blend": case "sldprt": case "iges": case "igs": case "dxf": case "svg": case "skp": return "source";
            case "gcode": case "bgcode": return "gcode";
            case "png": case "jpg": case "jpeg": case "webp": case "gif": case "heic": return "image";
            default: return type.startsWith("image/") ? "image" : "document";
        }
    }
    static boolean isRole(String id) { for (String[] e : ROLES) if (e[0].equals(id)) return true; return false; }
    static String label(String[][] list, String id) { for (String[] e : list) if (e[0].equals(id)) return e[1]; return id; }
    /** A web address that can be opened: http(s) with a host. */
    static boolean webOk(String url) { return Automation.webhookUrlOk(url); }

    // ------------------------------------------------------------------ helpers

    private static String need(JSONObject o, String key) throws JSONException {
        String v = o.optString(key); if (v.isEmpty() || v.length() > 80) throw new JSONException("missing " + key); return v;
    }
    private static String known(String[][] list, String id, String fallback) { for (String[] e : list) if (e[0].equals(id)) return id; return fallback; }
    static int clamp(int quantity) { return Math.max(1, Math.min(MAX_QUANTITY, quantity)); }
    static String title(String title) { String t = title == null ? "" : title.trim(); return t.isEmpty() ? "New project" : Automation.cap(t, 120); }
    /** The name as given, for showing (any language); folders and control characters taken off. */
    static String displayName(String name) {
        String base = name == null ? "" : name.replace('\\', '/'); base = base.substring(base.lastIndexOf('/') + 1).replaceAll("\\p{Cntrl}", "").trim();
        return base.isEmpty() ? "file" : Automation.cap(base, 200);
    }
    /**
     * The kept copy's file name: the original name (each copy has its own folder, so names never clash), unless it is too long
     * for the file system or has characters a file name cannot, then "file" with the same extension.
     */
    static String storedName(String name) {
        String base = displayName(name);
        if (base.getBytes(StandardCharsets.UTF_8).length <= 200 && !base.matches(".*[:*?\"<>|].*") && !base.startsWith(".")) return base;
        String ext = ModelSites.extension(base);
        return "file" + (ext.isEmpty() || !ext.matches("[a-z0-9]{1,8}") ? "" : "." + ext);
    }
    static boolean safeRelative(String path) {
        if (path == null || path.isEmpty() || path.startsWith("/") || path.contains("\\")) return false;
        for (String part : path.split("/")) if (part.isEmpty() || part.equals("..") || part.equals(".")) return false;
        return true;
    }
    private static MessageDigest sha256() { try { return MessageDigest.getInstance("SHA-256"); } catch (Exception impossible) { throw new IllegalStateException(impossible); } }
    private static String hex(byte[] bytes) { StringBuilder out = new StringBuilder(); for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b & 0xff)); return out.toString(); }
    private static void deleteTree(File f) { File[] all = f.listFiles(); if (all != null) for (File c : all) deleteTree(c); f.delete(); }
}
