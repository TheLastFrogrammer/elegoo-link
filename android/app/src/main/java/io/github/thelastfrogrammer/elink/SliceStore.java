package io.github.thelastfrogrammer.elink;

import android.content.Context;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Finished slices, kept in the app's files directory (not the cache) so a cache clean-up, a killed process or a failed upload
 * never costs a slice. The newest {@link #KEEP} are kept. Independent of the screens.
 */
final class SliceStore {
    static final int KEEP = 20;
    private SliceStore() { }

    static File directory(Context context) { File dir = new File(context.getFilesDir(), "slices"); dir.mkdirs(); return dir; }

    /** Moves (or copies) a freshly sliced file into the store under its printer name, and trims older ones. */
    static File keep(Context context, File sliced, String name) throws IOException {
        File dir = directory(context), target = new File(dir, name);
        if (!sliced.equals(target)) {
            if (!sliced.renameTo(target)) Files.copy(sliced.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            sliced.delete();
        }
        target.setLastModified(System.currentTimeMillis());
        prune(dir, target);
        return target;
    }

    /** Newest first. */
    static List<File> list(Context context) {
        File[] files = directory(context).listFiles((dir, n) -> n.endsWith(".gcode"));
        List<File> result = files == null ? new ArrayList<>() : new ArrayList<>(Arrays.asList(files));
        Collections.sort(result, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return result;
    }

    private static void prune(File dir, File keep) {
        File[] files = dir.listFiles((d, n) -> n.endsWith(".gcode"));
        if (files == null || files.length <= KEEP) return;
        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (int i = KEEP; i < files.length; i++) if (!files[i].equals(keep)) files[i].delete();
    }

    /** Leftovers a killed process left in the cache: slices older than an hour and unfinished timelapse downloads. */
    static void sweepCache(Context context) {
        File cache = context.getCacheDir();
        long old = System.currentTimeMillis() - 3_600_000L;
        File[] sliced = new File(cache, "sliced").listFiles();
        if (sliced != null) for (File file : sliced) if (file.lastModified() < old) file.delete();
        File[] timelapse = new File(cache, "timelapse").listFiles();
        if (timelapse != null) for (File file : timelapse) if (file.getName().endsWith(".part")) file.delete();
    }
}
