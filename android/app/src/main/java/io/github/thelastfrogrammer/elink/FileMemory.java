package io.github.thelastfrogrammer.elink;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Every print of one file together: its settings (from each print's kept G-code), the spools it ran on, the journal's rating
 * and what the readings found, so which settings worked for this part can be read off, and the spool's drying set beside
 * problems that point to moisture. Pure: the screen reads the files and hands them in.
 */
final class FileMemory {
    /** Journal reasons that damp filament often causes. */
    static final Set<String> MOISTURE = new HashSet<>(Arrays.asList("surface", "stringing", "under_extrusion"));
    /** Days after which an opened spool may well have taken up moisture (PLA keeps longer; PETG, TPU and nylon less). */
    static final long DAMP_DAYS = 30;

    /** One print of the file, with what was read about it. */
    static final class Print {
        final PrintRecorder.Recording recording;
        /** The settings its G-code was sliced with (Tuning.config), empty when not kept. */
        final Map<String, String> config;
        final List<RecordingAnalysis.Finding> findings;
        Print(PrintRecorder.Recording recording, Map<String, String> config, List<RecordingAnalysis.Finding> findings) { this.recording = recording; this.config = config; this.findings = findings; }
    }

    /** The recordings of a file (by its name), newest first. */
    static List<PrintRecorder.Recording> of(List<PrintRecorder.Recording> all, String file) {
        String base = GcodeLibrary.baseName(file); List<PrintRecorder.Recording> out = new ArrayList<>();
        if (base == null) return out;
        for (PrintRecorder.Recording r : all) if (base.equals(GcodeLibrary.baseName(r.file))) out.add(r);
        out.sort((a, b) -> Long.compare(b.start, a.start));
        return out;
    }

    /** How well a print went, by the journal first (result, stars), then whether it finished and what the readings found. */
    static double score(Print p) {
        PrintJournal j = p.recording.journal; double s = 0;
        if ("good".equals(j.result)) s += 3; else if ("usable".equals(j.result)) s += 1; else if ("failed".equals(j.result)) s -= 3;
        if (j.stars > 0) s += j.stars - 2.5;
        if ("complete".equals(p.recording.outcome)) s += 1; else if ("stopped".equals(p.recording.outcome)) s -= 2;
        s -= Math.min(3, p.findings.size());
        if (!j.reasons.isEmpty()) s -= 0.5 * Math.min(3, j.reasons.size());
        return s;
    }
    /**
     * The print that worked best: a finished one rated "came out well" or four stars or more, highest score, newest on a tie;
     * null when no print is rated that well (the readings alone cannot say a part came out well).
     */
    static Print best(List<Print> prints) {
        Print best = null; double top = Double.NEGATIVE_INFINITY;
        for (Print p : prints) {
            PrintJournal j = p.recording.journal;
            if (!"complete".equals(p.recording.outcome) || !("good".equals(j.result) || j.stars >= 4)) continue;
            double s = score(p);
            if (s > top || s == top && best != null && p.recording.start > best.recording.start) { top = s; best = p; }
        }
        return best;
    }

    /** The offered settings that differ between two prints, as changes from `from` to `to` (both must be known). */
    static List<Tuning.Change> changes(Map<String, String> from, Map<String, String> to, String why) {
        List<Tuning.Change> out = new ArrayList<>();
        for (String[] s : Tuning.SETTINGS) {
            String a = from.get(s[0]), b = to.get(s[0]);
            if (a != null && b != null && !same(a, b)) out.add(new Tuning.Change(s[0], a, b, why));
        }
        return out;
    }
    private static boolean same(String a, String b) {
        double[] x = Tuning.numbers(a), y = Tuning.numbers(b);
        return x.length > 0 && x.length == y.length ? Arrays.equals(x, y) : a.trim().equals(b.trim());
    }
    /** "Part fan on short layers 100 → 70 %, …", or "" when none. */
    static String words(List<Tuning.Change> changes) { List<String> w = new ArrayList<>(); for (Tuning.Change c : changes) w.add(c.words()); return String.join(", ", w); }

    /** The CANVAS slots a recording's tray column used ("canvas:tray"; the column counts 1 = unit 0 tray 0), in order. */
    static List<String> slots(double[] trays) {
        Set<String> out = new LinkedHashSet<>();
        for (double v : trays) if (!Double.isNaN(v) && v >= 1 && v < 64) { int n = (int) v - 1; out.add(SpoolStore.slot(n / 4, n % 4)); }
        return new ArrayList<>(out);
    }
    /** What to note on the recording about the spools in those slots now (at the print's end). */
    static JSONArray spoolNote(List<String> slots, SpoolStore store) throws JSONException {
        JSONArray out = new JSONArray();
        for (String slot : slots) {
            SpoolStore.Spool s = store.inSlot(slot); if (s == null) continue;
            out.put(new JSONObject().put("slot", slot).put("id", s.id).put("material", s.material()).put("dried", s.dried).put("opened", s.opened));
        }
        return out;
    }
    /** "PLA Matte · black (dried 3 days before this print)" per spool noted on the recording, or "" when none was. */
    static String spoolWords(PrintRecorder.Recording r) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < r.spools.length(); i++) {
            JSONObject s = r.spools.optJSONObject(i); if (s == null) continue;
            out.add(s.optString("material") + " (" + SpoolStore.Spool.dryWords(s.optLong("dried"), s.optLong("opened"), r.start, "before this print") + ")");
        }
        return String.join("; ", out);
    }

    /**
     * When the journal names problems damp filament often causes, the spool's drying beside them: how long it had been since
     * it was dried (or opened), or that it was not noted. "" when the journal names none of them.
     */
    static String moisture(PrintRecorder.Recording r) {
        Set<String> named = new TreeSet<>(); for (String reason : r.journal.reasons) if (MOISTURE.contains(reason)) named.add(PrintJournal.label(PrintJournal.REASONS, reason).toLowerCase(java.util.Locale.ROOT));
        if (named.isEmpty()) return "";
        String what = String.join(" and ", named);
        if (r.spools.length() == 0) return "Damp filament often causes " + what + ". Which spool printed this was not noted; drying it (and marking it dried under Spools) is worth a try.";
        List<String> words = new ArrayList<>(); boolean old = false;
        for (int i = 0; i < r.spools.length(); i++) {
            JSONObject s = r.spools.optJSONObject(i); if (s == null) continue;
            long days = SpoolStore.Spool.daysDry(s.optLong("dried"), s.optLong("opened"), r.start);
            old |= days < 0 || days >= DAMP_DAYS;
            words.add(s.optString("material") + ": " + SpoolStore.Spool.dryWords(s.optLong("dried"), s.optLong("opened"), r.start, "before this print"));
        }
        return "Damp filament often causes " + what + ". " + String.join("; ", words) + "."
            + (old ? " Drying the spool before the next print is worth a try." : " It was dried recently, so look at the settings first.");
    }

    private FileMemory() { }
}
