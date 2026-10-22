package io.github.thelastfrogrammer.elink;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What the print recordings take on the phone, and freeing it: a recording's pictures (all but its last, which stays as its
 * thumbnail), its videos, or its kept G-code, never its readings, charts, notes or checks. By hand, or by the automatic
 * clean-up (off until set): pictures only for the newest N prints, and a size limit met by thinning the oldest prints first,
 * pictures before videos before G-code. The running print, the newest recording and any a project links to are left alone.
 */
final class RecordingStorage {
    static final String LIMIT_MB = "storageLimitMb", KEEP_PICTURES = "keepPicturesPrints";
    enum Part {
        PICTURES("pictures"), VIDEOS("videos"), GCODE("kept G-code");
        final String words; Part(String words) { this.words = words; }
    }

    /** Bytes a recording takes, by kind; `lastPicture` is the picture that stays when pictures are removed. */
    static final class Usage {
        long readings, pictures, lastPicture, videos, gcode;
        long total() { return readings + pictures + videos + gcode; }
        long freeable(Part part) { return part == Part.PICTURES ? Math.max(0, pictures - lastPicture) : part == Part.VIDEOS ? videos : gcode; }
    }

    static Usage of(PrintRecorder.Recording r) {
        Usage u = new Usage();
        u.readings = r.csv.length() + r.meta.length();
        File[] main = r.pictureFolder().listFiles();
        String last = "";
        if (main != null) for (File f : main) {
            if (f.isDirectory()) continue;
            String name = f.getName();
            if (name.endsWith(".mp4")) u.videos += f.length();
            else if (name.matches("\\d+\\.jpg")) { u.pictures += f.length(); if (name.compareTo(last) > 0) { last = name; u.lastPicture = f.length(); } }
            else u.readings += f.length();   // the pictures' positions file
        }
        File[] far = r.farFolder().listFiles();
        if (far != null) for (File f : far) u.pictures += f.length();
        u.gcode = RecordingGcode.packed(r).length() + new File(r.meta.getParentFile(), r.meta.getName().replaceFirst("\\.json$", "") + ".gcode.json").length();
        return u;
    }

    /** Removes one kind of file from a recording; the bytes freed. */
    static long drop(PrintRecorder.Recording r, Part part) {
        long freed = 0;
        if (part == Part.PICTURES) {
            List<File> main = PrintRecorder.pictures(r);
            for (int i = 0; i < main.size() - 1; i++) { long n = main.get(i).length(); if (main.get(i).delete()) freed += n; }
            File[] far = r.farFolder().listFiles(); if (far != null) for (File f : far) { long n = f.length(); if (f.delete()) freed += n; }
            r.farFolder().delete();
        } else if (part == Part.VIDEOS) {
            for (File f : new File[] {r.video(), r.sideBySide()}) { long n = f.length(); if (f.delete()) freed += n; }
        } else {
            freed = RecordingGcode.packed(r).length(); RecordingGcode.remove(r);
        }
        return freed;
    }

    /** One step of a clean-up: this part of this recording, and what it frees. */
    static final class Step {
        final PrintRecorder.Recording recording; final Part part; final long bytes;
        Step(PrintRecorder.Recording recording, Part part, long bytes) { this.recording = recording; this.part = part; this.bytes = bytes; }
    }

    /**
     * What the clean-up would remove: pictures of every print past the newest `keepPicturesOf` (0 = all keep them), then,
     * while the total is over `limit` bytes (0 = no limit), the oldest prints' pictures, then videos, then kept G-code.
     * `newestFirst` as PrintRecorder.list gives them; `usage` by meta name; `protect` meta names never touched. Pure.
     */
    static List<Step> plan(List<PrintRecorder.Recording> newestFirst, Map<String, Usage> usage, long limit, int keepPicturesOf, Set<String> protect) {
        List<Step> steps = new ArrayList<>(); Set<String> planned = new HashSet<>();
        long total = 0; for (PrintRecorder.Recording r : newestFirst) total += usage.get(r.meta.getName()).total();
        if (keepPicturesOf > 0) for (int i = keepPicturesOf; i < newestFirst.size(); i++) {
            PrintRecorder.Recording r = newestFirst.get(i); String key = r.meta.getName();
            long bytes = usage.get(key).freeable(Part.PICTURES);
            if (protect.contains(key) || bytes <= 0) continue;
            steps.add(new Step(r, Part.PICTURES, bytes)); planned.add(key + Part.PICTURES); total -= bytes;
        }
        if (limit > 0) for (Part part : Part.values()) for (int i = newestFirst.size() - 1; i >= 0 && total > limit; i--) {
            PrintRecorder.Recording r = newestFirst.get(i); String key = r.meta.getName();
            long bytes = usage.get(key).freeable(part);
            if (protect.contains(key) || bytes <= 0 || planned.contains(key + part)) continue;
            steps.add(new Step(r, part, bytes)); planned.add(key + part); total -= bytes;
        }
        return steps;
    }
    static long bytes(List<Step> steps) { long n = 0; for (Step s : steps) n += s.bytes; return n; }
    /** "pictures of 12 prints, videos of 3 prints", for the confirmation. Pure. */
    static String describe(List<Step> steps) {
        List<String> parts = new ArrayList<>();
        for (Part part : Part.values()) { int n = 0; for (Step s : steps) if (s.part == part) n++; if (n > 0) parts.add(part.words + " of " + n + (n == 1 ? " print" : " prints")); }
        return String.join(", ", parts);
    }

    /** The recordings not to touch: the running one, the newest, and those a project links to (all when the projects cannot be read). */
    static Set<String> protectedNames(Context context, List<PrintRecorder.Recording> newestFirst) {
        Set<String> out = new HashSet<>();
        if (!newestFirst.isEmpty()) out.add(newestFirst.get(0).meta.getName());
        PrintRecorder running = PrintRecorder.active; PrintRecorder.Recording now = running == null ? null : running.current();
        if (now != null) out.add(now.meta.getName());
        Set<String> linked = Projects.referencedRecordings(new File(context.getFilesDir(), "projects"));
        if (linked == null) for (PrintRecorder.Recording r : newestFirst) out.add(r.meta.getName()); else out.addAll(linked);
        return out;
    }
    /** The clean-up as the settings ask, now; the bytes freed (0 when it is off). Worker only. */
    static long applyPolicy(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("workshop-settings", Context.MODE_PRIVATE);
        long limit = prefs.getLong(LIMIT_MB, 0) * 1024 * 1024; int keep = prefs.getInt(KEEP_PICTURES, 0);
        if (limit <= 0 && keep <= 0) return 0;
        List<PrintRecorder.Recording> all = new PrintRecorder(RecordingsActivity.directory(context)).list();
        Map<String, Usage> usage = new java.util.HashMap<>(); for (PrintRecorder.Recording r : all) usage.put(r.meta.getName(), of(r));
        List<Step> steps = plan(all, usage, limit, keep, protectedNames(context, all));
        long freed = 0; for (Step s : steps) freed += drop(s.recording, s.part);
        if (freed > 0) Diagnostics.note(Diagnostics.CAMERA, "storage clean-up: removed " + describe(steps) + " (" + size(freed) + ")");
        return freed;
    }

    static String size(long bytes) {
        if (bytes >= 1024L * 1024 * 1024) return String.format(Locale.getDefault(), "%.1f GB", bytes / (1024.0 * 1024 * 1024));
        if (bytes >= 1024 * 1024) return String.format(Locale.getDefault(), "%.0f MB", bytes / (1024.0 * 1024));
        return String.format(Locale.getDefault(), "%d KB", Math.max(0, bytes / 1024));
    }

    private RecordingStorage() { }
}
