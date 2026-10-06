package io.github.thelastfrogrammer.elink;

import java.io.*;
import java.util.*;

/**
 * Phone copies of G-code the app uploaded, sliced or downloaded, by file name, so the toolpath viewer can follow a
 * running print (the printer reports only the file name). Keeps the most recent files within a size budget.
 * Independent of Android.
 */
public final class GcodeLibrary {
    static final int KEEP_FILES = 12;
    static final long KEEP_BYTES = 1536L * 1024 * 1024;
    private final File directory;

    public GcodeLibrary(File directory) { this.directory = directory; }

    /** Copies `source` in under `name` (a printer file name), replacing an older copy, then prunes. */
    public synchronized File put(File source, String name) throws IOException {
        String safe = safeName(name);
        if (safe == null) throw new IOException("Unusable file name: " + name);
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create " + directory);
        File target = new File(directory, safe), temporary = new File(directory, safe + ".part");
        if (source.getCanonicalFile().equals(target.getCanonicalFile())) { target.setLastModified(System.currentTimeMillis()); return target; }
        try (InputStream in = new FileInputStream(source); OutputStream out = new FileOutputStream(temporary)) {
            byte[] buffer = new byte[65536]; int count; while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
        }
        if (target.exists() && !target.delete()) { temporary.delete(); throw new IOException("Cannot replace " + target); }
        if (!temporary.renameTo(target)) { temporary.delete(); throw new IOException("Cannot store " + target); }
        target.setLastModified(System.currentTimeMillis());
        prune();
        return target;
    }

    /** The phone copy of a printer file, matched by name (the printer may report it with a path), or null. */
    public synchronized File find(String printerName) {
        if (printerName == null) return null;
        String base = printerName.substring(printerName.lastIndexOf('/') + 1);
        String safe = safeName(base);
        if (safe == null) return null;
        File file = new File(directory, safe);
        return file.isFile() ? file : null;
    }

    public synchronized List<File> list() {
        File[] files = directory.listFiles((dir, name) -> !name.endsWith(".part"));
        List<File> result = new ArrayList<>(files == null ? Collections.emptyList() : Arrays.asList(files));
        result.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return result;
    }

    private void prune() {
        long total = 0; int kept = 0;
        for (File file : list()) {
            total += file.length(); kept++;
            if (kept > KEEP_FILES || total > KEEP_BYTES) file.delete();
        }
    }

    static String safeName(String name) {
        if (name == null) return null;
        String base = name.substring(name.lastIndexOf('/') + 1).trim();
        if (base.isEmpty() || base.equals(".") || base.equals("..") || base.length() > 200) return null;
        return base.replaceAll("[^A-Za-z0-9 _.()+-]", "_");
    }
}
