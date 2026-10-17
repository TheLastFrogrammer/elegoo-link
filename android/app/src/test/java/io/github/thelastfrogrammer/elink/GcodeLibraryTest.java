package io.github.thelastfrogrammer.elink;

import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

public class GcodeLibraryTest {
    private File dir;
    private GcodeLibrary library;
    @Before public void setup() throws Exception { dir = Files.createTempDirectory("gcode-archive").toFile(); library = new GcodeLibrary(dir); }
    private File source(String content) throws Exception {
        File file = File.createTempFile("source-", ".gcode"); Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8)); return file;
    }
    private String contents(File file) throws Exception { return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8); }
    private GcodeLibrary.Artifact keep(String bytes, String printer) throws Exception { return library.retain(source(bytes), "part.gcode", printer, GcodeLibrary.Origin.UPLOADED); }

    @Test public void sameNameKeepsBothVersionsAndDoesNotSelectEitherForAJob() throws Exception {
        File first = library.put(source("first"), "part.gcode"), second = library.put(source("second"), "part.gcode");
        assertNotEquals(first, second); assertEquals("first", contents(first)); assertEquals("second", contents(second));
        assertEquals(2, library.list().size()); assertEquals(2, library.candidates("CC2-A", "/local/part.gcode").size());
        assertNull("a name cannot choose between content versions", library.find("part.gcode"));
        assertNull(library.selected("CC2-A", "observed-job"));
    }
    @Test public void identicalBytesDeduplicateWithoutLosingDifferentNames() throws Exception {
        File a = library.put(source("same bytes"), "a.gcode"), b = library.put(source("same bytes"), "b.gcode");
        assertEquals(a, b); assertEquals(1, library.list().size());
        assertEquals(a, library.find("a.gcode")); assertEquals(b, library.find("b.gcode"));
        assertTrue(a.getName(), a.getName().matches("[0-9a-f]{64}\\.gcode"));
    }
    @Test public void printerAssociationsNeverCrossMatchByFilename() throws Exception {
        GcodeLibrary.Artifact a = keep("first", "CC2-A"), b = keep("second", "CC2-B");
        assertEquals(a.sha256, library.candidates("cc2-a", "part.gcode").get(0).sha256);
        assertEquals(b.sha256, library.candidates("CC2-B", "part.gcode").get(0).sha256);
        assertTrue(library.candidates("CC2-C", "part.gcode").isEmpty());
        try { library.selectForJob(a, "CC2-B", "job"); fail(); } catch (IOException expected) { }
        assertNull(library.selected("CC2-B", "job"));
    }
    @Test public void explicitSelectionIsScopedToPrinterAndObservedJobAndSurvivesAnotherInstance() throws Exception {
        GcodeLibrary.Artifact first = keep("first", "CC2-A"); library.selectForJob(first, "CC2-A", "job-1");
        keep("second", "CC2-A"); GcodeLibrary other = new GcodeLibrary(dir);
        assertEquals(first.sha256, other.selected("cc2-a", "job-1").sha256);
        assertNull(other.selected("CC2-A", "job-2")); assertNull(other.selected("CC2-B", "job-1"));
        assertEquals("first", contents(other.selected("CC2-A", "job-1").file));
    }
    @Test public void oldFlatCacheMigratesAsAnUnscopedCandidate() throws Exception {
        File old = new File(dir, GcodeLibrary.safeName("猫.gcode")); Files.write(old.toPath(), "legacy".getBytes(StandardCharsets.UTF_8));
        library.migrateLegacy(); assertFalse(old.exists());
        List<GcodeLibrary.Artifact> candidates = library.candidates("CC2-A", "猫.gcode");
        assertEquals(1, candidates.size()); assertEquals(GcodeLibrary.Origin.LEGACY, candidates.get(0).origin);
        assertEquals("legacy", contents(candidates.get(0).file)); assertNull(library.selected("CC2-A", "job"));
        library.migrateLegacy(); assertEquals(1, library.list().size());
    }
    @Test public void failedMigrationLeavesTheOriginalCopy() throws Exception {
        File old = new File(dir, "part.gcode"); Files.write(old.toPath(), "legacy".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(dir, "index-v2.json").toPath(), "broken".getBytes(StandardCharsets.UTF_8));
        try { library.migrateLegacy(); fail(); } catch (IOException expected) { }
        assertEquals("legacy", contents(old));
    }
    @Test public void changedBytesCannotBeConfirmedAndRetainingAgainRepairsTheObject() throws Exception {
        File original = source("first"); GcodeLibrary.Artifact artifact = library.retain(original, "part.gcode", "CC2-A", GcodeLibrary.Origin.UPLOADED);
        Files.write(artifact.file.toPath(), "other".getBytes(StandardCharsets.UTF_8));
        assertFalse(library.verify(artifact));
        try { library.selectForJob(artifact, "CC2-A", "job"); fail(); } catch (IOException expected) { }
        GcodeLibrary.Artifact repaired = library.retain(original, "part.gcode", "CC2-A", GcodeLibrary.Origin.UPLOADED);
        assertTrue(library.verify(repaired)); assertEquals("first", contents(repaired.file));
    }
    @Test public void emptyOrFailedImportsDoNotReplaceGoodVersionsOrLeavePartialObjects() throws Exception {
        GcodeLibrary.Artifact good = keep("valid", "CC2-A");
        for (File file : new File[] {source(""), new File(dir, "missing.gcode")}) {
            try { library.retain(file, "part.gcode", "CC2-A", GcodeLibrary.Origin.IMPORTED); fail(); } catch (IOException expected) { }
        }
        assertTrue(library.verify(good)); assertEquals(1, library.list().size());
        assertEquals(0, new File(dir, "objects").listFiles((d, n) -> n.endsWith(".part")).length);
    }
    @Test public void countEvictionIsExplicitAndNeverSubstitutesAnotherVersion() throws Exception {
        library = new GcodeLibrary(dir, 2, 100);
        GcodeLibrary.Artifact a = keep("one", "CC2-A"); library.selectForJob(a, "CC2-A", "job"); a.file.setLastModified(1000);
        GcodeLibrary.Artifact b = keep("two", "CC2-A"); b.file.setLastModified(2000);
        GcodeLibrary.Artifact c = keep("three", "CC2-A");
        assertEquals(2, library.list().size()); assertTrue(c.file.isFile()); assertFalse(a.file.exists());
        assertNull(library.selected("CC2-A", "job")); assertTrue(library.selectionEvicted("CC2-A", "job"));
    }
    @Test public void byteBudgetKeepsNewCopyAndRejectsAnIndividuallyOversizedCopy() throws Exception {
        library = new GcodeLibrary(dir, 12, 7);
        GcodeLibrary.Artifact old = keep("12345", "CC2-A"); old.file.setLastModified(1000);
        GcodeLibrary.Artifact fresh = keep("1234", "CC2-A");
        assertFalse(old.file.exists()); assertTrue(fresh.file.exists());
        try { keep("12345678", "CC2-A"); fail(); } catch (IOException expected) { }
        assertTrue(library.verify(fresh)); assertEquals(1, library.list().size());
    }
    @Test public void namesCannotEscapeObjectStorageAndUnicodeNamesStayDistinct() throws Exception {
        File stored = library.put(source("x"), "../../evil name?.gcode");
        assertEquals(new File(dir, "objects").getCanonicalFile(), stored.getCanonicalFile().getParentFile());
        assertNotEquals(GcodeLibrary.safeName("猫.gcode"), GcodeLibrary.safeName("犬.gcode"));
        assertNull(GcodeLibrary.safeName("..")); assertNull(library.find(null));
        try { library.put(source("x"), "dir/"); fail(); } catch (IOException expected) { }
    }
    @Test public void serviceAndViewerInstancesDoNotLoseEachOthersAssociations() throws Exception {
        GcodeLibrary other = new GcodeLibrary(dir);
        library.retain(source("a"), "part.gcode", "CC2-A", GcodeLibrary.Origin.UPLOADED);
        other.retain(source("b"), "part.gcode", "CC2-A", GcodeLibrary.Origin.UPLOADED);
        assertEquals(2, library.candidates("CC2-A", "part.gcode").size());
    }
    @Test public void failedOldLoadCannotForgetANewerSelection() throws Exception {
        GcodeLibrary.Artifact a = keep("one", "CC2-A"), b = keep("two", "CC2-A");
        library.selectForJob(a, "CC2-A", "job"); library.selectForJob(b, "CC2-A", "job");
        library.forgetSelection("CC2-A", "job", a.sha256); assertEquals(b.sha256, library.selected("CC2-A", "job").sha256);
        library.forgetSelection("CC2-A", "job", b.sha256); assertNull(library.selected("CC2-A", "job"));
    }
    @Test public void anArtifactFromAnotherArchiveCannotCreateABindingHere() throws Exception {
        GcodeLibrary other = new GcodeLibrary(Files.createTempDirectory("other-archive").toFile());
        GcodeLibrary.Artifact foreign = other.retain(source("foreign"), "part.gcode", "CC2-A", GcodeLibrary.Origin.CHOSEN);
        try { library.selectForJob(foreign, "CC2-A", "job"); fail(); } catch (IOException expected) { }
        assertNull(library.selected("CC2-A", "job"));
    }
    @Test public void startupRemovesOnlyInterruptedArchivePartsAndStillMigratesValidCopies() throws Exception {
        File incomplete = new File(dir, "part.gcode.part"), unrelated = new File(dir, "keep-me.txt");
        Files.write(incomplete.toPath(), "partial".getBytes(StandardCharsets.UTF_8)); Files.write(unrelated.toPath(), "keep".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(dir, "empty.gcode").toPath(), new byte[0]); Files.write(new File(dir, "good.gcode").toPath(), "valid".getBytes(StandardCharsets.UTF_8));
        try { library.migrateLegacy(); fail("the unusable empty legacy copy is reported"); } catch (IOException expected) { }
        assertFalse(incomplete.exists()); assertTrue(unrelated.isFile()); assertNotNull(library.find("good.gcode"));
        assertTrue("failed input remains available for diagnosis", new File(dir, "empty.gcode").isFile());
    }
}
