package io.github.thelastfrogrammer.elink;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.json.*;

/** Reproduces the observed gaps in dde7699. Passing means the gap was reproduced, not repaired. */
public final class FeatureGapProofs {
    private static int reproduced;
    private static void observed(boolean value, String label) {
        if (!value) throw new AssertionError("Could not reproduce: " + label);
        reproduced++; System.out.println("REPRODUCED " + reproduced + ": " + label);
    }
    private static File temp(String name) throws IOException { return Files.createTempDirectory("lw-gap-" + name).toFile(); }
    private static JSONObject status(String name, int progress, int sub) throws Exception {
        return new JSONObject().put("machine_status", new JSONObject().put("status", 2).put("sub_status", sub).put("progress", progress))
            .put("print_status", new JSONObject().put("filename", name).put("current_layer", 1).put("total_layers", 10));
    }
    private static Projects.Part part(Projects.Book b, Projects.Project p, int quantity) { return b.addPart(p, "Bracket", Projects.PRINTED, quantity, 1); }
    private static Projects.Attempt attempt(Projects.Project p, Projects.Part part, String recording, int good) {
        Projects.Attempt a = new Projects.Attempt(); a.recording = recording; a.set(part.id, good, good, 0); p.attempts.add(a); return a;
    }
    public static void main(String[] args) throws Exception {
        Projects.Book b = new Projects.Book(); Projects.Project p = b.create("Fixture", 1); Projects.Part part = part(b, p, 2);
        part.revision = "A"; attempt(p, part, "recording-1.json", 2); p.assembled = true;
        part.revision = "B";
        Projects.Book round = Projects.Book.fromJson(b.toJson()); Projects.Project loaded = round.project(p.id); Projects.Part loadedPart = loaded.part(part.id);
        observed(loadedPart.revision.equals("B") && loaded.accepted(loadedPart) == 2 && loaded.allAvailable() && loaded.assembled,
            "revision A's accepted pieces and assembled flag survive a change to revision B without an eligibility decision");

        b = new Projects.Book(); p = b.create("Duplicate", 1); part = part(b, p, 2);
        attempt(p, part, "same-recording.json", 1); attempt(p, part, "same-recording.json", 1);
        round = Projects.Book.fromJson(b.toJson()); loaded = round.project(p.id); loadedPart = loaded.part(part.id);
        observed(loaded.attempts.size() == 2 && loaded.accepted(loadedPart) == 2,
            "two links to the same recording count the same part twice");

        b = new Projects.Book(); p = b.create("Long-lived", 1); part = part(b, p, 9999);
        for (int i = 0; i < 501; i++) attempt(p, part, "recording-" + i + ".json", 1);
        int before = p.accepted(part); round = Projects.Book.fromJson(b.toJson()); loaded = round.project(p.id); loadedPart = loaded.part(part.id);
        observed(before == 501 && loaded.attempts.size() == 500 && loaded.accepted(loadedPart) == 500,
            "loading a saved project silently drops attempt 501 and reduces accepted count");

        SpoolStore spools = new SpoolStore(new File(temp("spools"), "spools.json"));
        TrayPlan.Tray tray = new TrayPlan.Tray(0, 0, "PLA", "PLA", "", "#FF0000");
        SpoolStore.Spool original = spools.put(tray, 1000, 500, 1);
        spools.started("bracket.gcode", Collections.singletonMap("0:0", 100.0), 2);
        spools.observe(status("bracket.gcode", 20, 2075), 3);
        SpoolStore.Spool replacement = spools.put(tray, 1000, 800, 4);
        spools.observe(status("bracket.gcode", 100, 2077), 5);
        double oldLeft = -1, newLeft = -1;
        for (SpoolStore.Spool s : spools.all()) { if (s.id.equals(original.id)) oldLeft = s.left; if (s.id.equals(replacement.id)) newLeft = s.left; }
        observed(oldLeft == 500 && newLeft == 700,
            "a mid-print replacement spool receives all 100 g of the planned debit; the original receives none");

        PrintRecorder recorder = new PrintRecorder(temp("printer-switch"));
        recorder.update(100000, 100000, status("bracket.gcode", 10, 2075), "local", "Printer A");
        File first = recorder.current().meta;
        recorder.update(120000, 120000, status("bracket.gcode", 40, 2075), "local", "Printer B");
        observed(recorder.current().meta.equals(first) && recorder.current().printer.equals("Printer A") && recorder.current().samples == 2 && recorder.list().size() == 1,
            "two printers reporting the same filename without UUID merge into Printer A's recording");

        File libraryDir = temp("library"); GcodeLibrary library = new GcodeLibrary(libraryDir);
        File a = new File(temp("input-a"), "input.gcode"), c = new File(temp("input-b"), "input.gcode");
        Files.writeString(a.toPath(), "G1 X1 ; revision A"); Files.writeString(c.toPath(), "G1 X2 ; revision B");
        library.put(a, "bracket.gcode"); File historicalReference = library.find("bracket.gcode"); library.put(c, "bracket.gcode");
        observed(Files.readString(historicalReference.toPath()).contains("revision B"),
            "a historical filename lookup resolves to replacement G-code bytes after the same name is reused");

        recorder = new PrintRecorder(temp("retention")); String linked = "";
        for (int i = 0; i < 101; i++) {
            long t = 100000 + i * 20000L;
            recorder.update(t, t, status("job-" + i + ".gcode", 10, 2075), "local", "Printer");
            if (i == 0) linked = recorder.current().meta.getPath();
            recorder.update(t + 10000, t + 10000, status("job-" + i + ".gcode", 100, 2077), "local", "Printer");
        }
        observed(!new File(linked).exists() && recorder.list().size() == 100,
            "the 101st recording prunes the oldest metadata with no project-reference check");

        b = new Projects.Book(); p = b.create("Shareable plan", 1); part = part(b, p, 2);
        Projects.Attempt privateAttempt = attempt(p, part, "private-recording.json", 1);
        privateAttempt.note = "PRIVATE_ATTEMPT_NOTE_123"; part.acceptedElsewhere = 1; p.assembled = true;
        ProjectArchive.Options options = new ProjectArchive.Options(); options.notes = false;
        File archive = new File(temp("sharing"), "plan.lwproject");
        try (OutputStream out = new FileOutputStream(archive)) { ProjectArchive.export(temp("project-assets"), b, p, options, out, "0.64.0", 10); }
        String sharedJson;
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(archive); InputStream in = zip.getInputStream(zip.getEntry("project.json"))) {
            sharedJson = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        ProjectArchive.Imported imported = ProjectArchive.read(archive, temp("shared-import"), 20);
        observed(sharedJson.contains("PRIVATE_ATTEMPT_NOTE_123") && sharedJson.contains("private-recording.json")
            && imported.project.attempts.isEmpty() && imported.project.accepted(imported.project.parts.get(0)) == 0,
            "Notes off still exports a private attempt note and recording reference, although importing discards that print history");
        System.out.println("SUMMARY: " + reproduced + " current-behavior reproductions completed; no printer, Android lifecycle, or network exercised.");
    }
}
