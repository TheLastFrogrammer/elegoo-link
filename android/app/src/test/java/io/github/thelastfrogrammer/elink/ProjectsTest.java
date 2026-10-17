package io.github.thelastfrogrammer.elink;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Projects: planning before files, shared files without copies, identity, and safe saving (the first-pass acceptance list). */
public class ProjectsTest {
    private File dir;
    @Before public void folder() throws Exception { dir = Files.createTempDirectory("projects").toFile(); }
    @After public void clean() { delete(dir); }
    private static void delete(File f) { File[] all = f.listFiles(); if (all != null) for (File c : all) delete(c); f.delete(); }
    private Projects.Asset copy(Projects.Book book, String name, String text) throws Exception {
        Projects.Asset fresh = Projects.copyIn(dir, new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), name, null, 1), kept = book.adopt(fresh);
        if (kept != fresh) Projects.deleteBytes(dir, fresh);   // as the screen does
        return kept;
    }

    @Test public void partsArePlannedBeforeAnyFileAndSurviveARestart() throws Exception {
        Projects.Book book = new Projects.Book();
        Projects.Project lamp = book.create("Shadow lamp", 1);
        Projects.Part body = book.addPart(lamp, "Main body", Projects.PRINTED, 1, 1);
        Projects.Part lid = book.addPart(lamp, "Lid", Projects.PRINTED, 1, 1);
        Projects.Part clips = book.addPart(lamp, "Retaining clip", Projects.PRINTED, 4, 1);
        Projects.Part check = book.addPart(lamp, "Verify lid clearance", Projects.TASK, 1, 1);
        Projects.save(dir, book);
        Projects.Book again = Projects.load(dir);
        Projects.Project read = again.project(lamp.id);
        assertEquals("Shadow lamp", read.title);
        assertEquals(Arrays.asList(body.id, lid.id, clips.id, check.id), Arrays.asList(read.parts.get(0).id, read.parts.get(1).id, read.parts.get(2).id, read.parts.get(3).id));
        assertEquals(4, read.part(clips.id).quantity);
        assertTrue(read.assetsOn(lid.id).isEmpty());
        assertEquals("4 parts · 6 pieces to print (3 without a file yet) · 0 of 1 task done · 0 files", ProjectsActivity.summary(read));
        // Reordering keeps ids; quantities stay in range.
        assertTrue(again.movePart(read, check.id, -1)); assertFalse(again.movePart(read, body.id, -1));
        assertEquals(check.id, read.parts.get(2).id);
        assertEquals(1, Projects.clamp(0)); assertEquals(Projects.MAX_QUANTITY, Projects.clamp(1_000_000));
    }

    @Test public void oneFileServesTwoProjectsAndRemovingItFromOneLeavesTheOther() throws Exception {
        Projects.Book book = new Projects.Book();
        Projects.Project lamp = book.create("Lamp", 1), shelf = book.create("Shelf", 1);
        Projects.Part lampClips = book.addPart(lamp, "Clip", Projects.PRINTED, 4, 1), shelfClips = book.addPart(shelf, "Clip", Projects.PRINTED, 2, 1);
        Projects.Asset clip = copy(book, "standard-clip-rev-c.stl", "solid clip");
        assertTrue(book.attach(lamp, clip.id, lampClips.id)); assertTrue(book.attach(shelf, clip.id, shelfClips.id));
        assertFalse("not twice in one place", book.attach(lamp, clip.id, lampClips.id));
        assertEquals(1, book.assets.size());
        assertEquals(Arrays.asList("Lamp › Clip", "Shelf › Clip"), book.usedIn(clip.id));
        assertEquals("quantities stay apart", 4, lamp.part(lampClips.id).quantity);
        book.detach(lamp, clip.id, lampClips.id);
        assertEquals(Arrays.asList("Shelf › Clip"), book.usedIn(clip.id));
        assertTrue(Projects.managedFile(dir, clip).isFile());
        assertFalse("still used: not forgotten", book.forget(clip.id));
        book.detach(shelf, clip.id, shelfClips.id);
        assertEquals(1, book.unassigned().size());
        assertTrue(book.forget(clip.id)); Projects.deleteBytes(dir, clip);
        assertFalse(Projects.managedFile(dir, clip).exists());
    }

    @Test public void sameBytesAreKeptOnceAndSameNamesStayApart() throws Exception {
        Projects.Book book = new Projects.Book();
        Projects.Asset a = copy(book, "part.stl", "first"), again = copy(book, "copy of part.stl", "first"), b = copy(book, "part.stl", "second");
        assertSame(a, again); assertNotSame(a, b);
        assertEquals(2, book.assets.size());
        assertNotEquals(Projects.managedFile(dir, a), Projects.managedFile(dir, b));
        assertEquals("first", new String(Files.readAllBytes(Projects.managedFile(dir, a).toPath()), StandardCharsets.UTF_8));
        assertEquals("only the kept copies remain", 2, new File(dir, "assets").listFiles().length);
        // Any language: the name is kept as given.
        Projects.Asset cjk = copy(book, "模型.stl", "third");
        assertEquals("模型.stl", cjk.name); assertTrue(Projects.managedFile(dir, cjk).isFile());
        assertTrue(cjk.sliceable());
    }

    @Test public void cadSourcesAreReferencesNotPrintInputs() throws Exception {
        Projects.Book book = new Projects.Book();
        Projects.Asset cad = copy(book, "lamp-body.FCStd", "cad"), sketch = copy(book, "lid-sketch.jpg", "jpg"), gcode = copy(book, "plate.gcode", "G1");
        assertEquals("source", cad.role); assertFalse(cad.sliceable());
        assertEquals("image", sketch.role); assertEquals("gcode", gcode.role); assertTrue(gcode.gcode()); assertFalse(gcode.sliceable());
        assertEquals("source", Projects.roleFor("mount.step", null));
        assertEquals("model", Projects.roleFor("clip.3mf", null));
    }

    @Test public void removingAPartKeepsItsFilesWithTheProject() throws Exception {
        Projects.Book book = new Projects.Book();
        Projects.Project p = book.create("Lamp", 1); Projects.Part frame = book.addPart(p, "Card frame", Projects.PRINTED, 2, 1);
        Projects.Asset model = copy(book, "frame.stl", "frame");
        book.attach(p, model.id, frame.id);
        book.removePart(p, frame.id);
        assertTrue(p.parts.isEmpty()); assertEquals(Arrays.asList(model.id), p.assetsOn(""));
    }

    @Test public void aRelinkWithDifferentBytesIsAChangeNotASubstitute() {
        Projects.Asset linked = new Projects.Asset(); linked.storage = Projects.EXTERNAL; linked.sha256 = "aaa";
        assertFalse(Projects.recordHash(linked, "aaa"));
        assertTrue(Projects.recordHash(linked, "bbb"));
        assertEquals("bbb", linked.sha256); assertEquals(Arrays.asList("aaa"), linked.earlierHashes);
    }

    @Test public void aDamagedFileFallsBackToThePreviousGoodCopyAndBadPathsAreRefused() throws Exception {
        Projects.Book book = new Projects.Book(); book.create("Kept", 1);
        Projects.save(dir, book); book.create("Second", 2); Projects.save(dir, book);
        Files.write(new File(dir, "projects.json").toPath(), "{\"format\":\"linkWork".getBytes());
        Projects.Book read = Projects.load(dir);
        assertEquals("the copy before the damaged save", 1, read.projects.size());
        assertEquals("Kept", read.projects.get(0).title);
        String evil = "{\"format\":\"linkWorkshopProjects\",\"version\":1,\"assets\":[{\"id\":\"x\",\"name\":\"a\",\"storage\":\"managed\",\"location\":\"../../shared_prefs/x.xml\"}],\"projects\":[]}";
        try { Projects.Book.fromJson(evil); fail("a path out of the folder"); } catch (org.json.JSONException expected) { }
        assertFalse(Projects.safeRelative("a/../b")); assertTrue(Projects.safeRelative("id/file.stl"));
        // An attachment to a missing file or part is dropped or moved to the project, never fatal.
        String loose = "{\"format\":\"linkWorkshopProjects\",\"version\":1,\"assets\":[],\"projects\":[{\"id\":\"p\",\"title\":\"P\",\"parts\":[],\"attachments\":[{\"asset\":\"gone\",\"part\":\"nope\"}]}]}";
        assertTrue(Projects.Book.fromJson(loose).project("p").attachments.isEmpty());
    }
}
