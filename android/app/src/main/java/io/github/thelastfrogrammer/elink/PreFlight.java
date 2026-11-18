package io.github.thelastfrogrammer.elink;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * "Before you start" in Print setup: what the app already knows that bears on this print, in one place at the moment it
 * matters. How this file's last print went (and what the journal said), how old the bed's last levelling is and how the
 * bed lies under the file's first layer, how long since the loaded spools were dried, and whether this print is a tuning
 * try. The filament match and the filament left are checked by the tray rows already, so they are not repeated here.
 * Only words: nothing is changed and nothing stops the print. Pure.
 */
final class PreFlight {
    static final long DAY = 86_400_000L;
    /** A levelling older than this is mentioned; a spool opened or dried longer ago than FileMemory.DAMP_DAYS is too. */
    static final long MESH_OLD_DAYS = 14;

    /** A line to show: whether it asks for a look (warn) or is just so (note, ok). */
    static final class Item {
        static final int OK = 0, NOTE = 1, WARN = 2;
        final int level; final String words;
        Item(int level, String words) { this.level = level; this.words = words; }
    }

    /** The last print of this file: how it ended and what the journal says; nothing when it was never printed here. */
    static Item lastPrint(PrintRecorder.Recording last, long now) {
        if (last == null) return null;
        String when = days(last.start, now);
        PrintJournal j = last.journal; List<String> reasons = new ArrayList<>();
        for (String r : j.reasons) reasons.add(PrintJournal.label(PrintJournal.REASONS, r).toLowerCase(Locale.ROOT));
        String why = reasons.isEmpty() ? "" : " (" + String.join(", ", reasons) + ")";
        if ("failed".equals(j.result)) return new Item(Item.WARN, "Last time (" + when + ") this file failed" + why + ". Its recording shows where.");
        if ("stopped".equals(last.outcome)) return new Item(Item.WARN, "Last time (" + when + ") this print was stopped" + why + ".");
        if ("usable".equals(j.result)) return new Item(Item.NOTE, "Last time (" + when + ") it came out usable, with flaws" + why + ".");
        if ("good".equals(j.result) || j.stars >= 4) return new Item(Item.OK, "Last time (" + when + ") it came out well.");
        return new Item(Item.NOTE, "Printed before (" + when + "), " + RecordingsActivity.outcome(last.outcome).toLowerCase(Locale.ROOT) + "; not rated in the journal.");
    }

    /** The bed's last levelling: how old, and (with the file here) how the bed lies under its first layer. */
    static BedMesh.Mesh meshFor(List<BedMesh.Mesh> meshes, String printerId) {
        BedMesh.Mesh newest = null;
        for (BedMesh.Mesh m : meshes) if (printerId != null && !printerId.isEmpty() && printerId.equals(m.printer)
                && (newest == null || m.at > newest.at)) newest = m;
        return newest;
    }

    static Item bed(BedMesh.Mesh latest, BedMesh.FirstLayer first, long now, boolean levelling) {
        if (latest == null) return new Item(Item.NOTE, "No bed mesh identified for this printer: the app keeps one each time the printer levels on the local network.");
        long age = (now - latest.at) / DAY;
        String when = "The bed was last levelled " + days(latest.at, now);
        if (first != null) {
            double range = first.max - first.min, share = first.layerHeight > 0.01 ? range / first.layerHeight : 0;
            String under = String.format(Locale.getDefault(), "%s; under this file's first layer it varied %.2f mm", when, range);
            if (share >= 0.5) return new Item(Item.WARN, under + String.format(Locale.getDefault(), ", %d %% of the first layer. A flatter spot (plate view) or a fresh levelling helps.", Math.round(share * 100)));
            return new Item(age > MESH_OLD_DAYS && !levelling ? Item.NOTE : Item.OK, under + "." + (age > MESH_OLD_DAYS && !levelling ? " That is a while ago: the bed check above levels it again." : ""));
        }
        return new Item(age > MESH_OLD_DAYS && !levelling ? Item.NOTE : Item.OK, when + "." + (age > MESH_OLD_DAYS && !levelling ? " That is a while ago: the bed check above levels it again." : ""));
    }

    /** The loaded spools' drying: those opened or dried long ago, or never noted, by tray. */
    static Item spools(List<String> trays, List<SpoolStore.Spool> spools, long now) {
        List<String> old = new ArrayList<>(), unknown = new ArrayList<>();
        for (int k = 0; k < spools.size(); k++) {
            SpoolStore.Spool s = spools.get(k); if (s == null) continue;
            long days = SpoolStore.Spool.daysDry(s.dried, s.opened, now);
            String name = trays.get(k) + " (" + s.material() + ")";
            if (days < 0) unknown.add(name); else if (days >= FileMemory.DAMP_DAYS) old.add(name + ", " + s.dryWords(now));
        }
        if (old.isEmpty() && unknown.isEmpty()) return spools.isEmpty() ? null : new Item(Item.OK, "The loaded spools were dried or opened recently.");
        List<String> parts = new ArrayList<>();
        if (!old.isEmpty()) parts.add("may have taken up moisture: " + String.join("; ", old));
        if (!unknown.isEmpty()) parts.add("drying not noted: " + String.join("; ", unknown));
        return new Item(old.isEmpty() ? Item.NOTE : Item.WARN, "Spools " + String.join(". Spools ", parts) + ". Damp filament strings and roughens tops; mark a spool dried on its tray.");
    }

    /** A tuning try for this file made since its last print: this print will be set against the one before. */
    static Item tuning(Tuning.Trial trial) {
        if (trial == null) return null;
        return new Item(Item.NOTE, "This file was sliced again with " + trial.changeWords() + ": its recording will say whether what was found before came back.");
    }

    /** The newest re-slice of this file made after its last print (or ever, when never printed) and not yet judged. Pure over the list. */
    static Tuning.Trial pendingTrial(List<Tuning.Trial> all, String file, PrintRecorder.Recording last) {
        String base = GcodeLibrary.baseName(file); Tuning.Trial best = null;
        if (base == null) return null;
        long after = last == null ? 0 : last.start;
        for (Tuning.Trial t : all) if (base.equals(t.file) && t.at > after && t.result.isEmpty() && (best == null || t.at > best.at)) best = t;
        return best;
    }

    /** The mark before an item: ✓ fine, ! worth a look, · just so. */
    static String mark(int level) { return level == Item.OK ? "✓ " : level == Item.WARN ? "! " : "· "; }

    static String days(long at, long now) {
        long d = Math.max(0, (now - at) / DAY);
        return d == 0 ? "today" : d == 1 ? "yesterday" : d < 14 ? d + " days ago" : "on " + new SimpleDateFormat("d MMM", Locale.getDefault()).format(new Date(at));
    }

    private PreFlight() { }
}
