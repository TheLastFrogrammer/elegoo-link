package io.github.thelastfrogrammer.elink;

import static org.junit.Assert.*;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.zip.ZipFile;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * The feature-gap review's reproductions (docs/research/app-review-2026-10-17/proofs), turned round: each now checks the
 * repaired behaviour.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class FeatureGapAcceptanceTest {
    @Before public void setUp() { CredentialStore.testKey = new SecretKeySpec(new byte[32], "AES"); }
    @After public void tearDown() { CredentialStore.testKey = null; }

    private static File temp(String name) throws Exception { return Files.createTempDirectory("lw-gap-" + name).toFile(); }
    private static JSONObject status(String name, int progress, int sub) throws Exception {
        return new JSONObject().put("machine_status", new JSONObject().put("status", 2).put("sub_status", sub).put("progress", progress))
            .put("print_status", new JSONObject().put("filename", name).put("current_layer", 1).put("total_layers", 10));
    }
    private static Projects.Part part(Projects.Book b, Projects.Project p, int quantity) { return b.addPart(p, "Bracket", Projects.PRINTED, quantity, 1); }
    private static Projects.Attempt attempt(Projects.Project p, Projects.Part part, String recording, int good) {
        Projects.Attempt a = new Projects.Attempt(); a.recording = recording; a.set(part.id, good, good, 0); a.stamp(part); p.attempts.add(a); return a;
    }
    private static String read(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n); return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    /** G04: a new revision asks; "print new ones" stops the old pieces counting and the assembly needs checking again. */
    @Test public void aRevisionChangeDecidesWhetherOldPiecesCount() throws Exception {
        Projects.Book b = new Projects.Book(); Projects.Project p = b.create("Fixture", 1); Projects.Part part = part(b, p, 2);
        part.revision = "A"; attempt(p, part, "recording-1.json", 2); p.assembled = true;
        p.changeRevision(part, "B", false);
        Projects.Project loaded = Projects.Book.fromJson(b.toJson()).project(p.id); Projects.Part loadedPart = loaded.part(part.id);
        assertEquals("B", loadedPart.revision);
        assertEquals("revision A's pieces do not count for B", 0, loaded.accepted(loadedPart));
        assertFalse(loaded.allAvailable()); assertFalse(loaded.assembled);
        assertEquals("they stay in the history", 2, loaded.earlierRevisionPieces(loadedPart));
        assertEquals("A", loaded.attempts.get(0).revisions.get(part.id));

        // "They still count": kept, also through a further revision.
        Projects.Book c = new Projects.Book(); Projects.Project q = c.create("Fixture", 1); Projects.Part bracket = part(c, q, 2);
        bracket.revision = "A"; attempt(q, bracket, "recording-1.json", 2);
        q.changeRevision(bracket, "B", true); q.changeRevision(bracket, "C", true);
        Projects.Project reread = Projects.Book.fromJson(c.toJson()).project(q.id);
        assertEquals(2, reread.accepted(reread.part(bracket.id)));
        // Prints from before revisions were kept are marked with the old revision when it changes.
        Projects.Book d = new Projects.Book(); Projects.Project r = d.create("Old", 1); Projects.Part old = part(d, r, 1);
        Projects.Attempt legacy = new Projects.Attempt(); legacy.set(old.id, 1, 1, 0); r.attempts.add(legacy);
        assertEquals(1, r.accepted(old));
        r.changeRevision(old, "B", false);
        assertEquals(0, r.accepted(old));
    }

    /** G05: one recording counts once; the screen refuses linking it again. */
    @Test public void theSameRecordingCountsOnce() throws Exception {
        Projects.Book b = new Projects.Book(); Projects.Project p = b.create("Duplicate", 1); Projects.Part part = part(b, p, 2);
        attempt(p, part, "same-recording.json", 1); attempt(p, part, "same-recording.json", 1);
        Projects.Project loaded = Projects.Book.fromJson(b.toJson()).project(p.id);
        assertEquals("nothing is dropped", 2, loaded.attempts.size());
        assertEquals("but its pieces count once", 1, loaded.accepted(loaded.part(part.id)));
        assertTrue(loaded.hasRecording("same-recording.json")); assertFalse(loaded.hasRecording("")); assertFalse(loaded.hasRecording("other.json"));
    }

    /** G07: a long-lived project keeps every print. */
    @Test public void moreThanFiveHundredPrintsSurviveSaving() throws Exception {
        Projects.Book b = new Projects.Book(); Projects.Project p = b.create("Long-lived", 1); Projects.Part part = part(b, p, 9999);
        for (int i = 0; i < 501; i++) attempt(p, part, "recording-" + i + ".json", 1);
        Projects.Project loaded = Projects.Book.fromJson(b.toJson()).project(p.id);
        assertEquals(501, loaded.attempts.size()); assertEquals(501, loaded.accepted(loaded.part(part.id)));
    }

    /** G08: a spool changed mid-print takes only the share printed while it was in the tray. */
    @Test public void aReplacementSpoolTakesOnlyItsShare() throws Exception {
        SpoolStore spools = new SpoolStore(new File(temp("spools"), "spools.json"));
        TrayPlan.Tray tray = new TrayPlan.Tray(0, 0, "PLA", "PLA", "", "#FF0000");
        SpoolStore.Spool original = spools.put(tray, 1000, 500, 1);
        spools.started("bracket.gcode", Collections.singletonMap("0:0", 100.0), 2);
        spools.observe(status("bracket.gcode", 20, 2075), 3);
        SpoolStore.Spool replacement = spools.put(tray, 1000, 800, 4);
        spools.observe(status("bracket.gcode", 100, 2077), 5);
        double oldLeft = -1, newLeft = -1;
        for (SpoolStore.Spool s : spools.all()) { if (s.id.equals(original.id)) oldLeft = s.left; if (s.id.equals(replacement.id)) newLeft = s.left; }
        assertEquals("the first 20 % came off the original", 480, oldLeft, 0.01);
        assertEquals("the rest off the replacement", 720, newLeft, 0.01);
    }

    /** G03: two printers with a file of the same name (no uuid) are two recordings. */
    @Test public void twoPrintersAreTwoRecordings() throws Exception {
        PrintRecorder recorder = new PrintRecorder(new File(temp("printer-switch"), "recordings"));
        recorder.update(100000, 100000, status("bracket.gcode", 10, 2075), "local", "Printer A", "SN-A");
        File first = recorder.current().meta;
        recorder.update(120000, 120000, status("bracket.gcode", 40, 2075), "local", "Printer B", "SN-B");
        assertNotEquals(first, recorder.current().meta);
        assertEquals("Printer B", recorder.current().printer);
        assertEquals(2, recorder.list().size());
    }

    /** G07: a recording a project links to outlives the usual pruning. */
    @Test public void projectEvidenceIsNotPruned() throws Exception {
        File root = temp("retention");
        PrintRecorder recorder = new PrintRecorder(new File(root, "recordings")); File linked = null;
        for (int i = 0; i < 101; i++) {
            long t = 100000 + i * 20000L;
            recorder.update(t, t, status("job-" + i + ".gcode", 10, 2075), "local", "Printer");
            if (i == 0) {
                linked = recorder.current().meta;
                Projects.Book b = new Projects.Book(); Projects.Project p = b.create("Kept", 1); Projects.Part part = part(b, p, 1);
                attempt(p, part, linked.getName(), 1); Projects.save(new File(root, "projects"), b);
            }
            recorder.update(t + 10000, t + 10000, status("job-" + i + ".gcode", 100, 2077), "local", "Printer");
        }
        assertTrue(linked.exists());
        assertEquals(101, recorder.list().size());
    }

    /** G01: a shared plan carries no print history, progress or (with notes off) notes, in any archive entry. */
    @Test public void aSharedPlanHoldsOnlyThePlan() throws Exception {
        Projects.Book b = new Projects.Book(); Projects.Project p = b.create("Shareable plan", 1); Projects.Part part = part(b, p, 2);
        part.notes = "PRIVATE_PART_NOTE"; p.description = "PRIVATE_DESCRIPTION";
        Projects.Part bought = b.addPart(p, "Screws", Projects.PURCHASED, 8, 1); bought.have = 8;
        Projects.Part task = b.addPart(p, "Sand edges", Projects.TASK, 1, 1); task.done = true;
        Projects.Attempt privateAttempt = attempt(p, part, "private-recording.json", 1);
        privateAttempt.note = "PRIVATE_ATTEMPT_NOTE_123"; part.acceptedElsewhere = 1; p.assembled = true;
        ProjectArchive.Options options = new ProjectArchive.Options(); options.notes = false;
        File archive = new File(temp("sharing"), "plan.lwproject");
        try (OutputStream out = new FileOutputStream(archive)) { ProjectArchive.export(temp("project-assets"), b, p, options, out, "0.66.0", 10); }
        StringBuilder everything = new StringBuilder();
        try (ZipFile zip = new ZipFile(archive)) {
            for (java.util.Enumeration<? extends java.util.zip.ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                java.util.zip.ZipEntry entry = e.nextElement(); try (InputStream in = zip.getInputStream(entry)) { everything.append(read(in)); }
            }
        }
        String all = everything.toString();
        for (String marker : new String[] {"PRIVATE_ATTEMPT_NOTE_123", "private-recording.json", "PRIVATE_PART_NOTE", "PRIVATE_DESCRIPTION", "accepted_elsewhere", "assembled", "attempts", "\"have\"", "\"done\""})
            assertFalse("the archive holds " + marker, all.contains(marker));
        assertFalse("progress is not in the outline", all.contains("at hand") || all.contains(", done"));
        ProjectArchive.Imported imported = ProjectArchive.read(archive, temp("shared-import"), 20);
        assertEquals(3, imported.project.parts.size());
        assertEquals("Bracket", imported.project.parts.get(0).name);
        assertEquals(0, imported.project.parts.get(1).have);
    }

    /** G02: a backup brings the projects' kept files back onto an empty phone, checked by their hashes. */
    @Test public void projectFilesComeBackWithABackup() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        File dir = new File(context.getFilesDir(), "projects");
        Projects.Book b = new Projects.Book(); Projects.Project p = b.create("Lamp", 1); Projects.Part part = part(b, p, 1);
        Projects.Asset model = b.adopt(Projects.copyIn(dir, new java.io.ByteArrayInputStream("solid lamp".getBytes(StandardCharsets.UTF_8)), "lamp.stl", null, 1));
        b.attach(p, model.id, part.id); attempt(p, part, "r.json", 1); Projects.save(dir, b);
        File backup = File.createTempFile("backup", ".zip");
        try (FileOutputStream out = new FileOutputStream(backup)) { Backup.export(context, out, false, true, null); }
        assertEquals(1, Backup.inspect(backup).projectFiles);

        // An empty phone: the projects and their files are gone.
        deleteTree(dir); assertFalse(dir.exists());
        Backup.Summary restored = Backup.restore(context, backup, null);
        assertEquals(0, restored.missingProjectFiles);
        Projects.Book back = Projects.load(dir); Projects.Project lamp = back.project(p.id);
        assertEquals("the same project, with its counts", 1, lamp.accepted(lamp.part(part.id)));
        File kept = Projects.managedFile(dir, back.asset(model.id));
        assertEquals("solid lamp", new String(Files.readAllBytes(kept.toPath()), StandardCharsets.UTF_8));

        // Without the files, the restore says which are missing.
        File setupOnly = File.createTempFile("backup", ".zip");
        try (FileOutputStream out = new FileOutputStream(setupOnly)) { Backup.export(context, out, false, false, null); }
        deleteTree(dir);
        assertEquals(1, Backup.restore(context, setupOnly, null).missingProjectFiles);
    }
    private static void deleteTree(File f) { File[] all = f.listFiles(); if (all != null) for (File c : all) deleteTree(c); f.delete(); }
}
