package io.github.thelastfrogrammer.elink;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the print recordings take on the phone, the automatic clean-up's settings, and freeing space by hand, print by print.
 * Readings, charts, notes and checks are never removed here; only pictures (all but the last), videos and kept G-code.
 */
public final class StorageActivity extends PlainScreen {
    static final long[] LIMITS_MB = {0, 500, 1024, 2048, 5120, 10240};
    static final int[] KEEPS = {0, 50, 20, 10};
    private List<PrintRecorder.Recording> all = new ArrayList<>();
    private final Map<String, RecordingStorage.Usage> usage = new HashMap<>();
    private Set<String> protect;

    @Override protected void onCreate(Bundle saved) { super.onCreate(saved); load(); }

    private void load() {
        content.removeAllViews();
        label(content, "Storage", 22, ink, true);
        TextView waiting = label(content, "Adding up…", 14, muted, false);
        new Thread(() -> {
            List<PrintRecorder.Recording> read = new PrintRecorder(RecordingsActivity.directory(this)).list();
            Map<String, RecordingStorage.Usage> sizes = new HashMap<>(); for (PrintRecorder.Recording r : read) sizes.put(r.meta.getName(), RecordingStorage.of(r));
            Set<String> keep = RecordingStorage.protectedNames(this, read);
            runOnUiThread(() -> { if (isDestroyed()) return; all = read; usage.clear(); usage.putAll(sizes); protect = keep; content.removeView(waiting); show(); });
        }, "storage-sizes").start();
    }

    private void show() {
        long pictures = 0, videos = 0, gcode = 0, readings = 0;
        for (RecordingStorage.Usage u : usage.values()) { pictures += u.pictures; videos += u.videos; gcode += u.gcode; readings += u.readings; }
        LinearLayout sum = card(); label(sum, "Print recordings use " + RecordingStorage.size(pictures + videos + gcode + readings), 16, ink, true);
        label(sum, "Pictures " + RecordingStorage.size(pictures) + " · videos " + RecordingStorage.size(videos) + " · kept G-code " + RecordingStorage.size(gcode) + " · readings and notes " + RecordingStorage.size(readings), 13, muted, false);
        label(sum, "Free on the phone: " + RecordingStorage.size(getFilesDir().getUsableSpace()) + ". The app keeps up to " + PrintRecorder.KEEP + " recordings; older ones go by themselves unless a project uses them.", 13, muted, false);

        SharedPreferences prefs = getSharedPreferences("workshop-settings", MODE_PRIVATE);
        LinearLayout auto = card(); label(auto, "Automatic clean-up", 16, ink, true);
        label(auto, "After each print, if set: oldest prints first, pictures go before videos, videos before kept G-code. Each print keeps its last picture, its readings, charts, notes and checks. The newest print, a running one and prints a project uses are left alone.", 13, muted, false);
        long limit = prefs.getLong(RecordingStorage.LIMIT_MB, 0); int keep = prefs.getInt(RecordingStorage.KEEP_PICTURES, 0);
        button(auto, "Size limit: " + (limit == 0 ? "none" : RecordingStorage.size(limit * 1024 * 1024)), () -> {
            String[] names = new String[LIMITS_MB.length]; for (int i = 0; i < names.length; i++) names[i] = LIMITS_MB[i] == 0 ? "No limit" : RecordingStorage.size(LIMITS_MB[i] * 1024 * 1024);
            new AlertDialog.Builder(this).setTitle("Keep recordings under").setItems(names, (d, w) -> { prefs.edit().putLong(RecordingStorage.LIMIT_MB, LIMITS_MB[w]).apply(); load(); }).show();
        });
        button(auto, "Pictures: " + (keep == 0 ? "kept for every print" : "only the newest " + keep + " prints"), () -> {
            String[] names = new String[KEEPS.length]; for (int i = 0; i < names.length; i++) names[i] = KEEPS[i] == 0 ? "Keep every print's pictures" : "Only the newest " + KEEPS[i] + " prints";
            new AlertDialog.Builder(this).setTitle("Camera pictures").setItems(names, (d, w) -> { prefs.edit().putInt(RecordingStorage.KEEP_PICTURES, KEEPS[w]).apply(); load(); }).show();
        });
        if (limit > 0 || keep > 0) {
            Map<String, RecordingStorage.Usage> sizes = usage;
            List<RecordingStorage.Step> steps = RecordingStorage.plan(all, sizes, limit * 1024 * 1024, keep, protect);
            if (!steps.isEmpty()) button(auto, "Free " + RecordingStorage.size(RecordingStorage.bytes(steps)) + " now…", () -> new AlertDialog.Builder(this).setTitle("Free " + RecordingStorage.size(RecordingStorage.bytes(steps)) + "?")
                .setMessage("This removes " + RecordingStorage.describe(steps) + ". It cannot be undone.").setNegativeButton("Cancel", null)
                .setPositiveButton("Remove", (d, w) -> run(() -> { for (RecordingStorage.Step s : steps) RecordingStorage.drop(s.recording, s.part); })).show());
            else label(auto, "Nothing to remove now.", 13, muted, false);
        }

        LinearLayout list = card(); label(list, "By print, largest first", 16, ink, true);
        label(list, "Tap a print to remove its pictures, videos or kept G-code, or to open it.", 13, muted, false);
        List<PrintRecorder.Recording> bySize = new ArrayList<>(all);
        bySize.sort((a, b) -> Long.compare(usage.get(b.meta.getName()).total(), usage.get(a.meta.getName()).total()));
        for (PrintRecorder.Recording r : bySize.subList(0, Math.min(40, bySize.size()))) {
            RecordingStorage.Usage u = usage.get(r.meta.getName());
            TextView row = label(list, CompareActivity.name(r) + "\n" + RecordingStorage.size(u.total()) + " · pictures " + RecordingStorage.size(u.pictures) + ", videos " + RecordingStorage.size(u.videos)
                + ", G-code " + RecordingStorage.size(u.gcode) + (protect.contains(r.meta.getName()) ? " · left alone by the clean-up" : ""), 14, ink, false);
            row.setMinHeight(dp(56)); row.setPadding(0, dp(8), 0, dp(8)); row.setBackgroundResource(android.R.drawable.list_selector_background);
            row.setOnClickListener(v -> choose(r, u));
        }
    }

    private void choose(PrintRecorder.Recording r, RecordingStorage.Usage u) {
        List<String> names = new ArrayList<>(); List<Runnable> actions = new ArrayList<>();
        names.add("Open the recording"); actions.add(() -> startActivity(new Intent(this, RecordingsActivity.class).putExtra(RecordingsActivity.EXTRA_META, r.meta.getAbsolutePath())));
        for (RecordingStorage.Part part : RecordingStorage.Part.values()) {
            long n = u.freeable(part); if (n <= 0) continue;
            names.add("Remove " + part.words + " (" + RecordingStorage.size(n) + ")" + (part == RecordingStorage.Part.PICTURES ? ", keeping the last" : ""));
            actions.add(() -> confirm("Remove this print's " + part.words + "?", () -> RecordingStorage.drop(r, part)));
        }
        names.add("Delete the whole recording"); actions.add(() -> confirm("Delete this recording, with its readings, notes and pictures?", () -> new PrintRecorder(RecordingsActivity.directory(this)).delete(r)));
        new AlertDialog.Builder(this).setTitle(CompareActivity.name(r)).setItems(names.toArray(new String[0]), (d, w) -> actions.get(w).run()).setNegativeButton("Cancel", null).show();
    }
    private void confirm(String question, Runnable work) {
        new AlertDialog.Builder(this).setTitle(question).setMessage("It cannot be undone.").setNegativeButton("Cancel", null).setPositiveButton("Remove", (d, w) -> run(work)).show();
    }
    private void run(Runnable work) {
        new Thread(() -> { work.run(); runOnUiThread(() -> { if (isDestroyed()) return; Toast.makeText(this, "Done.", Toast.LENGTH_SHORT).show(); load(); }); }, "storage-free").start();
    }
}
