package io.github.thelastfrogrammer.elink;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * One project as a file to share (.lwproject, a ZIP): manifest.json, project.json (the project, its parts and the files it
 * uses), the kept files chosen, a cover page if wanted, and README.txt with the outline in plain words. Linked files' bytes are
 * never included (they stay where they are), and the manifest says so for each. Nothing from the person's accounts, printers
 * or settings goes in.
 *
 * Importing reads the whole archive first (only the entries it names, through ZipFile, within size limits, each kept file
 * checked against its recorded hash) and adds the project under new ids; files whose bytes are already kept are not copied
 * twice. Independent of Android.
 */
final class ProjectArchive {
    static final String FORMAT = "linkWorkshopProject", EXTENSION = ".lwproject", MIME = "application/vnd.linkworkshop.project+zip";
    static final int VERSION = 1;
    static final long MAX_FILE = 512L * 1024 * 1024, MAX_TOTAL = 2L * 1024 * 1024 * 1024, MAX_JSON = 4L * 1024 * 1024;
    static final int MAX_ENTRIES = 2000;

    static final class Options {
        /** Include the bytes of files kept in the app; web links; notes on the project and parts; the cover page. */
        boolean keptFiles = true, webLinks = true, notes = true;
        byte[] coverPng;
    }

    /** What an export held or an import found: counts, and the files whose bytes are not in it, with why. */
    static final class Report {
        int parts, included, linksOnly; long bytes;
        final List<String> notIncluded = new ArrayList<>();
        String describe() {
            List<String> out = new ArrayList<>();
            out.add(parts == 1 ? "1 part" : parts + " parts");
            out.add(included == 1 ? "1 file included" : included + " files included");
            if (bytes > 0) out.add(ProjectsActivity.size(bytes));
            if (linksOnly > 0) out.add(linksOnly + (linksOnly == 1 ? " web link" : " web links"));
            String text = String.join(" · ", out);
            if (!notIncluded.isEmpty()) text += ". Not included: " + String.join("; ", notIncluded) + ".";
            return text;
        }
    }

    static Report export(File projectsDir, Projects.Book book, Projects.Project project, Options options, OutputStream target, String app, long now) throws IOException, JSONException {
        Report report = new Report(); report.parts = project.parts.size();
        JSONObject p = project.toJson();
        p.remove("archived");
        if (!options.notes) { p.put("description", ""); JSONArray parts = p.getJSONArray("parts"); for (int i = 0; i < parts.length(); i++) parts.getJSONObject(i).put("notes", ""); }
        JSONArray files = new JSONArray(), manifestFiles = new JSONArray(), links = new JSONArray();
        List<Projects.Asset> include = new ArrayList<>();
        for (String id : project.allAssets()) {
            Projects.Asset a = book.asset(id); if (a == null) continue;
            JSONObject entry = a.toJson(); entry.remove("earlier_hashes");
            String why = null;
            if (a.storage.equals(Projects.WEB)) { if (!options.webLinks) { why = "web links left out"; } else report.linksOnly++; }
            else if (a.storage.equals(Projects.EXTERNAL)) { why = "linked on this phone, not kept in the app"; entry.put("location", ""); }
            else if (!options.keptFiles) { why = "files left out"; entry.put("location", ""); }
            else {
                File f = Projects.managedFile(projectsDir, a);
                if (f == null || !f.isFile()) { why = "its kept copy is missing"; entry.put("location", ""); }
                else { entry.put("location", "files/" + a.id + "/" + f.getName()); include.add(a); }
            }
            if (why != null && a.storage.equals(Projects.WEB)) continue;   // a left-out web link is not mentioned in the project at all
            if (why != null) { report.notIncluded.add(a.name + " (" + why + ")"); entry.put("not_included", why); }
            files.put(entry);
            manifestFiles.put(new JSONObject().put("name", a.name).put("included", why == null).put("why", why == null ? "" : why));
        }
        // Attachments to left-out web links go too.
        JSONArray attachments = p.getJSONArray("attachments"), kept = new JSONArray();
        java.util.Set<String> listed = new java.util.HashSet<>(); for (int i = 0; i < files.length(); i++) listed.add(files.getJSONObject(i).getString("id"));
        for (int i = 0; i < attachments.length(); i++) if (listed.contains(attachments.getJSONObject(i).getString("asset"))) kept.put(attachments.getJSONObject(i));
        p.put("attachments", kept);
        JSONObject doc = new JSONObject().put("project", p).put("assets", files);
        try (ZipOutputStream zip = new ZipOutputStream(target)) {
            put(zip, "project.json", doc.toString(1).getBytes(StandardCharsets.UTF_8));
            for (Projects.Asset a : include) {
                File f = Projects.managedFile(projectsDir, a);
                zip.putNextEntry(new ZipEntry("files/" + a.id + "/" + f.getName()));
                try (InputStream in = new LimitedStream(new FileInputStream(f), MAX_FILE)) { byte[] buffer = new byte[65536]; int n; while ((n = in.read(buffer)) > 0) { zip.write(buffer, 0, n); report.bytes += n; } }
                zip.closeEntry(); report.included++;
            }
            if (options.coverPng != null) put(zip, "cover.png", options.coverPng);
            put(zip, "README.txt", outline(book, project, options.notes).getBytes(StandardCharsets.UTF_8));
            JSONObject manifest = new JSONObject().put("format", FORMAT).put("version", VERSION).put("app", app).put("created", now)
                .put("title", project.title).put("files", manifestFiles).put("cover", options.coverPng != null);
            put(zip, "manifest.json", manifest.toString(1).getBytes(StandardCharsets.UTF_8));
        }
        return report;
    }

    /** The project in plain words: title, purpose, parts with quantities and notes, files and links. */
    static String outline(Projects.Book book, Projects.Project project, boolean notes) {
        StringBuilder out = new StringBuilder(project.title).append('\n');
        if (notes && !project.description.isEmpty()) out.append('\n').append(project.description).append('\n');
        out.append("\nParts\n");
        if (project.parts.isEmpty()) out.append("  (none planned yet)\n");
        for (Projects.Part part : project.parts) {
            out.append("  - ").append(part.name);
            if (part.counted()) out.append(" × ").append(part.quantity);
            out.append(" (").append(Projects.label(Projects.KINDS, part.kind).toLowerCase(java.util.Locale.ROOT));
            if (part.kind.equals(Projects.PURCHASED)) out.append(", ").append(part.have).append(" at hand");
            if (part.kind.equals(Projects.TASK)) out.append(part.done ? ", done" : ", to do");
            out.append(")\n");
            if (notes && !part.notes.isEmpty()) out.append("      ").append(part.notes.replace("\n", "\n      ")).append('\n');
            for (String id : project.assetsOn(part.id)) { Projects.Asset a = book.asset(id); if (a != null) out.append("      file: ").append(a.name).append(a.storage.equals(Projects.WEB) ? " <" + a.location + ">" : "").append('\n'); }
        }
        List<String> general = project.assetsOn("");
        if (!general.isEmpty()) {
            out.append("\nFiles\n");
            for (String id : general) { Projects.Asset a = book.asset(id); if (a != null) out.append("  - ").append(a.name).append(a.storage.equals(Projects.WEB) ? " <" + a.location + ">" : "").append('\n'); }
        }
        return out.toString();
    }

    /** An archive read and checked, its kept files copied into the assets folder, not yet in the book. */
    static final class Imported {
        Projects.Project project; final List<Projects.Asset> assets = new ArrayList<>(); final Report report = new Report(); String title = "";
        /** For each archive asset id, the new asset (so attachments can be joined after deduplication). */
        final Map<String, Projects.Asset> byOldId = new HashMap<>();
    }

    /**
     * Reads an archive. On any problem nothing is left behind: copied files are removed and an IOException says what was
     * wrong. The caller adds the result to the book with `adopt`.
     */
    static Imported read(File archive, File projectsDir, long now) throws IOException {
        Imported out = new Imported(); List<Projects.Asset> copied = new ArrayList<>();
        try (ZipFile zip = new ZipFile(archive)) {
            int count = 0; for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); e.nextElement()) if (++count > MAX_ENTRIES) throw new IOException("The project file holds too many entries.");
            JSONObject manifest = json(zip, "manifest.json");
            if (!FORMAT.equals(manifest.optString("format"))) throw new IOException("This is not a Link Workshop project file.");
            if (manifest.optInt("version") > VERSION) throw new IOException("This project was made by a newer version of the app. Update the app first.");
            JSONObject doc = json(zip, "project.json");
            Projects.Project source;
            try { source = Projects.Project.fromJson(doc.getJSONObject("project")); } catch (JSONException bad) { throw new IOException("The project in the file is damaged (" + bad.getMessage() + ")."); }
            JSONArray files = doc.optJSONArray("assets");
            long total = 0;
            for (int i = 0; files != null && i < files.length(); i++) {
                JSONObject entry = files.optJSONObject(i); if (entry == null) continue;
                String oldId = entry.optString("id"); if (oldId.isEmpty() || oldId.length() > 80) throw new IOException("A file in the project has no id.");
                String storage = entry.optString("storage"), name = Projects.displayName(entry.optString("name")), location = entry.optString("location");
                Projects.Asset a;
                if (storage.equals(Projects.WEB)) {
                    if (!Projects.webOk(location)) continue;
                    a = new Projects.Asset(); a.storage = Projects.WEB; a.role = "link"; a.name = name; a.location = Automation.cap(location, 2000); a.added = now;
                    out.report.linksOnly++;
                } else if (!location.isEmpty()) {
                    // A kept file: only at the path the archive gives for this id, checked against its recorded hash.
                    if (!location.startsWith("files/" + oldId + "/") || !Projects.safeRelative(location)) throw new IOException("A file in the project is stored outside its folder.");
                    ZipEntry bytes = zip.getEntry(location); if (bytes == null) throw new IOException(name + " is missing from the project file.");
                    total += Math.max(0, bytes.getSize()); if (total > MAX_TOTAL) throw new IOException("The project's files are larger than expected.");
                    try (InputStream in = new LimitedStream(zip.getInputStream(bytes), MAX_FILE)) { a = Projects.copyIn(projectsDir, in, name, entry.optString("mime"), now); }
                    copied.add(a);
                    String expected = entry.optString("sha256");
                    if (!expected.isEmpty() && !expected.equals(a.sha256)) throw new IOException(name + " in the project file is damaged (its contents do not match).");
                    out.report.included++; out.report.bytes += a.size;
                } else {
                    // Its bytes were not included (a linked file, or left out): kept as a placeholder to relink.
                    a = new Projects.Asset(); a.storage = Projects.EXTERNAL; a.location = ""; a.name = name; a.added = now; a.sha256 = entry.optString("sha256");
                    out.report.notIncluded.add(name + " (" + (entry.optString("not_included").isEmpty() ? "not included" : entry.optString("not_included")) + ")");
                }
                String role = entry.optString("role");
                a.role = storage.equals(Projects.WEB) ? "link" : Projects.isRole(role) ? role : Projects.roleFor(name, null);
                out.byOldId.put(oldId, a); out.assets.add(a);
            }
            // New ids throughout, so importing the same project twice gives two projects and never mixes with one already here.
            Projects.Project p = new Projects.Project();
            p.title = Projects.title(source.title); p.description = source.description; p.created = now; p.updated = now;
            // The cover's picture is one of the project's files: point it at that file's new id.
            try { p.cover = new JSONObject(source.cover.toString()); Projects.Asset picture = out.byOldId.get(p.cover.optString("picture")); p.cover.put("picture", picture == null ? "" : picture.id); }
            catch (JSONException ignored) { p.cover = new JSONObject(); }
            Map<String, String> partIds = new HashMap<>();
            for (Projects.Part old : source.parts) {
                Projects.Part part = new Projects.Part(); part.name = old.name; part.kind = old.kind; part.notes = old.notes; part.quantity = old.quantity; part.have = old.have; part.done = old.done;
                partIds.put(old.id, part.id); p.parts.add(part);
            }
            for (Projects.Attachment a : source.attachments) {
                Projects.Asset asset = out.byOldId.get(a.assetId); if (asset == null) continue;
                String part = a.partId.isEmpty() ? "" : partIds.getOrDefault(a.partId, "");
                p.attachments.add(new Projects.Attachment(asset.id, part));
            }
            out.project = p; out.title = p.title; out.report.parts = p.parts.size();
            return out;
        } catch (IOException | RuntimeException failed) {
            for (Projects.Asset a : copied) Projects.deleteBytes(projectsDir, a);
            if (failed instanceof IOException) throw (IOException) failed;
            throw new IOException("The project file could not be read (" + failed.getClass().getSimpleName() + ").", failed);
        }
    }

    /**
     * Adds an imported project to the book (main thread). Kept files whose bytes are already here use the copy already kept;
     * returns the files whose new copies the caller deletes.
     */
    static List<Projects.Asset> adopt(Projects.Book book, Imported imported) {
        List<Projects.Asset> duplicates = new ArrayList<>(); Map<String, String> swap = new HashMap<>();
        for (Projects.Asset fresh : imported.assets) { Projects.Asset kept = book.adopt(fresh); if (kept != fresh) { duplicates.add(fresh); swap.put(fresh.id, kept.id); } }
        List<Projects.Attachment> joined = new ArrayList<>();
        for (Projects.Attachment a : imported.project.attachments) {
            Projects.Attachment one = new Projects.Attachment(swap.getOrDefault(a.assetId, a.assetId), a.partId);
            boolean seen = false; for (Projects.Attachment b : joined) seen |= b.assetId.equals(one.assetId) && b.partId.equals(one.partId);
            if (!seen) joined.add(one);
        }
        imported.project.attachments.clear(); imported.project.attachments.addAll(joined);
        String picture = imported.project.cover.optString("picture");
        if (swap.containsKey(picture)) try { imported.project.cover.put("picture", swap.get(picture)); } catch (JSONException ignored) { }
        book.projects.add(imported.project);
        return duplicates;
    }

    private static JSONObject json(ZipFile zip, String name) throws IOException {
        ZipEntry entry = zip.getEntry(name); if (entry == null) throw new IOException("This is not a Link Workshop project file (no " + name + ").");
        try (InputStream in = new LimitedStream(zip.getInputStream(entry), MAX_JSON)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[65536]; int n; while ((n = in.read(buffer)) > 0) bytes.write(buffer, 0, n);
            return new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
        } catch (JSONException bad) { throw new IOException("The project file's " + name + " is damaged."); }
    }
    private static void put(ZipOutputStream zip, String name, byte[] data) throws IOException { zip.putNextEntry(new ZipEntry(name)); zip.write(data); zip.closeEntry(); }

    /** Stops reading past `max` bytes, whatever the entry claims about its size. */
    static final class LimitedStream extends java.io.FilterInputStream {
        private final long max; private long read;
        LimitedStream(InputStream in, long max) { super(in); this.max = max; }
        @Override public int read() throws IOException { int b = super.read(); if (b >= 0 && ++read > max) throw new IOException("A file in the project is too large."); return b; }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len); if (n > 0 && (read += n) > max) throw new IOException("A file in the project is too large."); return n;
        }
    }
}
