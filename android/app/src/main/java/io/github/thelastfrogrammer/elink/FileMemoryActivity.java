package io.github.thelastfrogrammer.elink;

import android.content.Intent;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Every print of one file (FileMemory): which print worked best and its settings, with a way to slice again with them,
 * then each print with its rating, spools and their drying, the settings changed since the print before, what the
 * readings found and how a tuning try went. Only reads; slicing again goes through the slicer screen, as tuning does.
 */
public final class FileMemoryActivity extends PlainScreen {
    static final String EXTRA_FILE = "file";
    /** Prints read for the page (newest first); older ones are counted but not read. */
    static final int READ = 40;
    private String file;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        file = GcodeLibrary.baseName(getIntent().getStringExtra(EXTRA_FILE));
        label(content, file == null ? "Prints of a file" : file, 22, ink, true);
        if (file == null) { label(content, "No file name.", 14, muted, false); return; }
        TextView waiting = label(content, "Reading its prints…", 14, muted, false);
        File cache = getCacheDir();
        new Thread(() -> {
            List<PrintRecorder.Recording> mine = FileMemory.of(new PrintRecorder(RecordingsActivity.directory(this)).list(), file);
            List<FileMemory.Print> prints = new ArrayList<>();
            for (PrintRecorder.Recording r : mine.subList(0, Math.min(READ, mine.size()))) {
                List<RecordingAnalysis.Finding> found = Collections.emptyList();
                try { found = RecordingsActivity.findings(r, PrintRecorder.samples(r)); } catch (Exception unreadable) { }
                Map<String, String> config = new LinkedHashMap<>();
                try { config = Tuning.config(RecordingGcode.open(r, cache)); } catch (RuntimeException unreadable) { }
                prints.add(new FileMemory.Print(r, config, found));
            }
            List<Tuning.Trial> trials = Tuning.all(this);
            runOnUiThread(() -> { if (!isDestroyed()) { content.removeView(waiting); show(prints, mine.size(), trials); } });
        }, "file-memory").start();
    }

    private void show(List<FileMemory.Print> prints, int total, List<Tuning.Trial> trials) {
        if (prints.isEmpty()) { label(content, "No recorded prints of this file yet.", 14, muted, false); return; }
        int good = 0, failed = 0;
        for (FileMemory.Print p : prints) { if ("good".equals(p.recording.journal.result)) good++; else if ("failed".equals(p.recording.journal.result) || "stopped".equals(p.recording.outcome)) failed++; }
        label(content, total + (total == 1 ? " print" : " prints") + (good > 0 ? " · " + good + " came out well" : "") + (failed > 0 ? " · " + failed + " failed or stopped" : "")
            + (total > prints.size() ? " · the newest " + prints.size() + " shown" : ""), 14, muted, false);

        // What worked: the best-rated print and its settings, against the newest print's.
        LinearLayout worked = card(); label(worked, "What worked", 15, ink, true);
        FileMemory.Print best = FileMemory.best(prints), newest = prints.get(0);
        if (best == null) label(worked, "No print of this file is rated as having come out well yet. Rate prints in their Journal (on each recording) and the settings that worked show here.", 13, muted, false);
        else {
            label(worked, "Best so far: " + when(best.recording) + " · " + rating(best.recording) + (best.findings.isEmpty() ? " · nothing stood out in the readings" : ""), 13, ink, false);
            List<String> settings = new ArrayList<>();
            for (String[] s : Tuning.SETTINGS) if (best.config.containsKey(s[0])) settings.add(s[1] + " " + best.config.get(s[0]) + (s[2].isEmpty() ? "" : " " + s[2]));
            label(worked, settings.isEmpty() ? "Its G-code is not kept with the recording, so its settings are not known." : String.join(" · ", settings), 13, ink, false);
            if (best != newest && !best.config.isEmpty() && !newest.config.isEmpty()) {
                List<Tuning.Change> back = FileMemory.changes(newest.config, best.config, "As on the print that came out best (" + when(best.recording) + ")");
                if (back.isEmpty()) label(worked, "The newest print already used these settings.", 12, muted, false);
                else {
                    label(worked, "The newest print differs: " + FileMemory.words(back) + ".", 13, ink, false);
                    button(worked, "Slice again with these settings", () -> startActivity(new Intent(this, SliceActivity.class).putExtra(SliceActivity.EXTRA_TUNE,
                        Tuning.request(file, newest.recording, CompareActivity.name(newest.recording), back, new ArrayList<>()))));
                    label(worked, "Opens the slicer with them set on top of the presets; nothing is sliced until you tap Slice.", 12, muted, false);
                }
            }
        }

        // Each print, newest first.
        for (int i = 0; i < prints.size(); i++) {
            FileMemory.Print p = prints.get(i), before = i + 1 < prints.size() ? prints.get(i + 1) : null;
            LinearLayout card = card();
            label(card, when(p.recording) + " · " + RecordingsActivity.outcome(p.recording.outcome) + " · " + rating(p.recording), 15, ink, true);
            String spools = FileMemory.spoolWords(p.recording);
            if (!spools.isEmpty()) label(card, "Spool: " + spools, 13, ink, false);
            if (before != null && !p.config.isEmpty() && !before.config.isEmpty()) {
                String changed = FileMemory.words(FileMemory.changes(before.config, p.config, ""));
                label(card, changed.isEmpty() ? "Same settings as the print before." : "Changed since the print before: " + changed + ".", 13, ink, false);
            } else if (p.config.isEmpty()) label(card, "G-code not kept with it: settings not known.", 12, muted, false);
            if (!p.findings.isEmpty()) {
                List<String> kinds = new ArrayList<>(); for (RecordingAnalysis.Finding f : p.findings) { String w = Tuning.what(f.kind); if (!kinds.contains(w)) kinds.add(w); }
                label(card, "Readings: " + String.join("; ", kinds) + ".", 13, ink, false);
            }
            if (!p.recording.journal.reasons.isEmpty()) {
                List<String> named = new ArrayList<>(); for (String r : p.recording.journal.reasons) named.add(PrintJournal.label(PrintJournal.REASONS, r));
                label(card, "Journal: " + String.join("; ", named) + (p.recording.journal.notes.isEmpty() ? "" : " · " + p.recording.journal.notes), 13, ink, false);
            } else if (!p.recording.journal.notes.isEmpty()) label(card, "Journal: " + p.recording.journal.notes, 13, ink, false);
            String damp = FileMemory.moisture(p.recording);
            if (!damp.isEmpty()) label(card, damp, 13, warning, false);
            Tuning.Trial trial = Tuning.trialFor(trials, p.recording);
            if (trial != null) label(card, "Tuning try: " + trial.changeWords() + (trial.result.isEmpty() ? "" : " · " + trial.result), 12, muted, false);
            PrintRecorder.Recording r = p.recording;
            card.setOnClickListener(v -> startActivity(new Intent(this, RecordingsActivity.class).putExtra(RecordingsActivity.EXTRA_META, r.meta.getAbsolutePath())));
            card.setContentDescription(when(r) + ", open the recording");
        }
    }

    private static String when(PrintRecorder.Recording r) { return new SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(new Date(r.start)); }
    private static String rating(PrintRecorder.Recording r) {
        PrintJournal j = r.journal;
        if (!j.rated()) return "not rated";
        String result = PrintJournal.label(PrintJournal.RESULTS, j.result);
        return (j.stars > 0 ? j.stars + "★" : "") + (j.stars > 0 && result != null ? " · " : "") + (result == null ? "" : result);
    }
}
