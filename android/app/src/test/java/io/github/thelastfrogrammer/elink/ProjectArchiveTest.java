package io.github.thelastfrogrammer.elink;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Sharing a project as a file and importing it on another phone. */
public class ProjectArchiveTest {
    private File here, there, archive;
    @Before public void folders() throws Exception { here = Files.createTempDirectory("here").toFile(); there = Files.createTempDirectory("there").toFile(); archive = File.createTempFile("project", ".lwproject"); }
    @After public void clean() { delete(here); delete(there); archive.delete(); }
    private static byte[] bytes(InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(); byte[] b = new byte[4096]; int n; while ((n = in.read(b)) > 0) out.write(b, 0, n); return out.toByteArray();
    }
    private static void delete(File f) { File[] all = f.listFiles(); if (all != null) for (File c : all) delete(c); f.delete(); }
    private static Projects.Asset kept(File dir, Projects.Book book, String name, String text) throws Exception {
        Projects.Asset fresh = Projects.copyIn(dir, new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), name, null, 1), a = book.adopt(fresh);
        if (a != fresh) Projects.deleteBytes(dir, fresh); return a;
    }
    private Projects.Book lamp(Projects.Project[] out) throws Exception {
        Projects.Book book = new Projects.Book(); Projects.Project p = book.create("Lamp", 1); p.description = "Scene-card lamp";
        Projects.Part clip = book.addPart(p, "Clip", Projects.PRINTED, 4, 1); clip.notes = "Rev C fits";
        Projects.Part lid = book.addPart(p, "Lid", Projects.PRINTED, 1, 1);
        Projects.Part screws = book.addPart(p, "Screws", Projects.PURCHASED, 6, 1); screws.have = 2;
        Projects.Asset model = kept(here, book, "clip-rev-c.stl", "solid clip"), sketch = kept(here, book, "lid sketch.jpg", "jpeg bytes");
        Projects.Asset cad = new Projects.Asset(); cad.storage = Projects.EXTERNAL; cad.name = "lamp.FCStd"; cad.role = "source"; cad.location = "content://provider/doc/1"; cad.sha256 = "abc"; book.assets.add(cad);
        Projects.Asset page = new Projects.Asset(); page.storage = Projects.WEB; page.role = "link"; page.name = "Supplier"; page.location = "https://example.com/screws"; book.assets.add(page);
        book.attach(p, model.id, clip.id); book.attach(p, sketch.id, lid.id); book.attach(p, cad.id, ""); book.attach(p, page.id, screws.id);
        p.cover.put("picture", sketch.id).put("style", "poster");
        out[0] = p; return book;
    }

    @Test public void aProjectTravelsWithItsPartsFilesAndLinks() throws Exception {
        Projects.Project[] p = new Projects.Project[1]; Projects.Book book = lamp(p);
        ProjectArchive.Options options = new ProjectArchive.Options(); options.coverPng = new byte[] {1, 2, 3};
        ProjectArchive.Report sent;
        try (FileOutputStream out = new FileOutputStream(archive)) { sent = ProjectArchive.export(here, book, p[0], options, out, "test", 5); }
        assertEquals(2, sent.included); assertEquals(1, sent.linksOnly);
        assertTrue(sent.describe(), sent.describe().contains("lamp.FCStd (linked on this phone, not kept in the app)"));
        try (ZipFile zip = new ZipFile(archive)) {
            assertNotNull(zip.getEntry("cover.png")); assertNotNull(zip.getEntry("README.txt")); assertNotNull(zip.getEntry("manifest.json"));
            String readme = new String(bytes(zip.getInputStream(zip.getEntry("README.txt"))), StandardCharsets.UTF_8);
            assertTrue(readme, readme.contains("Clip × 4 (printed part)") && readme.contains("Rev C fits") && readme.contains("<https://example.com/screws>"));
            String all = new String(bytes(zip.getInputStream(zip.getEntry("project.json"))), StandardCharsets.UTF_8);
            assertFalse("no phone-only document address", all.contains("content://"));
        }
        ProjectArchive.Imported got = ProjectArchive.read(archive, there, 9);
        Projects.Book other = new Projects.Book(); assertTrue(ProjectArchive.adopt(other, got).isEmpty());
        Projects.Project q = other.projects.get(0);
        assertEquals("Lamp", q.title); assertEquals("Scene-card lamp", q.description); assertNotEquals(p[0].id, q.id);
        assertEquals(3, q.parts.size()); assertEquals(4, q.parts.get(0).quantity); assertEquals("Rev C fits", q.parts.get(0).notes); assertEquals("purchases at hand stay on the phone that has them", 0, q.parts.get(2).have);
        Projects.Asset model = other.asset(q.assetsOn(q.parts.get(0).id).get(0));
        assertEquals("clip-rev-c.stl", model.name);
        assertEquals("solid clip", new String(Files.readAllBytes(Projects.managedFile(there, model).toPath()), StandardCharsets.UTF_8));
        Projects.Asset cad = other.asset(q.assetsOn("").get(0));
        assertEquals(Projects.EXTERNAL, cad.storage); assertEquals("a placeholder to relink", "", cad.location);
        assertEquals("https://example.com/screws", other.asset(q.assetsOn(q.parts.get(2).id).get(0)).location);
        assertEquals("the cover picture follows its file", other.asset(q.cover.getString("picture")).name, "lid sketch.jpg");
        // Imported again: a second project, and the kept bytes are not copied twice.
        ProjectArchive.Imported again = ProjectArchive.read(archive, there, 10);
        java.util.List<Projects.Asset> duplicates = ProjectArchive.adopt(other, again);
        assertEquals(2, duplicates.size()); for (Projects.Asset a : duplicates) Projects.deleteBytes(there, a);
        assertEquals(2, other.projects.size()); assertNotEquals(other.projects.get(0).id, other.projects.get(1).id);
        assertEquals(2, new File(there, "assets").listFiles().length);
    }

    @Test public void notesFilesAndLinksCanBeLeftOut() throws Exception {
        Projects.Project[] p = new Projects.Project[1]; Projects.Book book = lamp(p);
        ProjectArchive.Options options = new ProjectArchive.Options(); options.keptFiles = false; options.webLinks = false; options.notes = false;
        try (FileOutputStream out = new FileOutputStream(archive)) { ProjectArchive.export(here, book, p[0], options, out, "test", 5); }
        try (ZipFile zip = new ZipFile(archive)) {
            String all = new String(bytes(zip.getInputStream(zip.getEntry("project.json"))), StandardCharsets.UTF_8);
            assertFalse(all.contains("Rev C fits")); assertFalse(all.contains("example.com")); assertFalse(all.contains("Scene-card"));
            for (java.util.Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) assertFalse(e.nextElement().getName().startsWith("files/"));
        }
        Projects.Book other = new Projects.Book(); ProjectArchive.adopt(other, ProjectArchive.read(archive, there, 9));
        assertEquals(3, other.assets.size());
        for (Projects.Asset a : other.assets) assertEquals(Projects.EXTERNAL, a.storage);
    }

    private void write(String... entries) throws Exception {
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(archive))) {
            for (int i = 0; i < entries.length; i += 2) { zip.putNextEntry(new ZipEntry(entries[i])); zip.write(entries[i + 1].getBytes(StandardCharsets.UTF_8)); zip.closeEntry(); }
        }
    }
    @Test public void damagedOrHostileFilesAreRefusedAndLeaveNothingBehind() throws Exception {
        String manifest = "{\"format\":\"linkWorkshopProject\",\"version\":1}";
        String project = "{\"id\":\"p\",\"title\":\"T\",\"parts\":[],\"attachments\":[{\"asset\":\"a\",\"part\":\"\"}]}";
        // A kept file whose bytes do not match its hash.
        write("manifest.json", manifest, "project.json", "{\"project\":" + project + ",\"assets\":[{\"id\":\"a\",\"name\":\"x.stl\",\"storage\":\"managed\",\"location\":\"files/a/x.stl\",\"sha256\":\"00\"}]}", "files/a/x.stl", "solid");
        try { ProjectArchive.read(archive, there, 1); fail("tampered"); } catch (java.io.IOException expected) { assertTrue(expected.getMessage(), expected.getMessage().contains("damaged")); }
        File[] left = new File(there, "assets").listFiles(); assertTrue("nothing left behind", left == null || left.length == 0);
        // A path that climbs out, or points at another file's folder.
        write("manifest.json", manifest, "project.json", "{\"project\":" + project + ",\"assets\":[{\"id\":\"a\",\"name\":\"x.stl\",\"storage\":\"managed\",\"location\":\"files/a/../../x\"}]}");
        try { ProjectArchive.read(archive, there, 1); fail("path"); } catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("outside")); }
        write("manifest.json", manifest, "project.json", "{\"project\":" + project + ",\"assets\":[{\"id\":\"a\",\"name\":\"x.stl\",\"storage\":\"managed\",\"location\":\"files/b/x.stl\"}]}", "files/b/x.stl", "solid");
        try { ProjectArchive.read(archive, there, 1); fail("other folder"); } catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("outside")); }
        // Not a project, or from a newer app.
        write("manifest.json", "{\"format\":\"linkWorkshopBackup\",\"version\":1}");
        try { ProjectArchive.read(archive, there, 1); fail("backup"); } catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("not a Link Workshop project")); }
        write("manifest.json", "{\"format\":\"linkWorkshopProject\",\"version\":99}", "project.json", "{}");
        try { ProjectArchive.read(archive, there, 1); fail("newer"); } catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("newer")); }
        // Reading stops at the limit whatever an entry claims.
        try (InputStream in = new ProjectArchive.LimitedStream(new ByteArrayInputStream(new byte[100]), 10)) { bytes(in); fail("limit"); } catch (java.io.IOException expected) { }
    }
}
