package io.github.thelastfrogrammer.elink;

import android.content.Context;
import android.content.res.AssetManager;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The on-phone slicer: ElegooSlicer's engine (libslic3r) in liblinkslicer.so, built from slicer/ in this repository.
 * Its presets and data ship as assets/slicer and are unpacked to the app's files directory on first use.
 * Not thread-safe: use one instance from one thread at a time (see SliceActivity's slicer thread).
 */
final class NativeSlicer implements AutoCloseable {
    static final int PRINTER = 0, PROCESS = 1, FILAMENT = 2;

    /** Progress from the engine, possibly on one of its worker threads. Return false to cancel. */
    interface Listener { boolean progress(int percent, String text); }

    static final class Result {
        final File gcode; final double printSeconds, filamentMm, filamentGrams; final List<String> warnings = new ArrayList<>();
        Result(JSONObject json) {
            gcode = new File(json.optString("gcode")); printSeconds = json.optDouble("print_time_s");
            filamentMm = json.optDouble("filament_mm"); filamentGrams = json.optDouble("filament_g");
            JSONArray list = json.optJSONArray("warnings");
            if (list != null) for (int i = 0; i < list.length(); i++) warnings.add(list.optString(i));
        }
    }

    private static Boolean libraryLoaded;
    private long handle;

    /** True when this build includes the slicer library and its data. */
    static synchronized boolean available(Context context) {
        if (libraryLoaded == null) {
            try { System.loadLibrary("linkslicer"); libraryLoaded = true; }
            catch (UnsatisfiedLinkError error) { libraryLoaded = false; }
        }
        if (!libraryLoaded) return false;
        try { context.getAssets().open("slicer/VERSION").close(); return true; } catch (IOException error) { return false; }
    }

    /** Loads the vendor's presets (a few seconds). Call off the main thread. */
    static NativeSlicer open(Context context, String vendor) throws IOException {
        if (!available(context)) throw new IOException("This build does not include the slicer.");
        File resources = unpack(context);
        File work = new File(context.getCacheDir(), "slicer-work");
        if (!work.isDirectory() && !work.mkdirs()) throw new IOException("Cannot create " + work);
        NativeSlicer slicer = new NativeSlicer();
        try { slicer.handle = create(resources.getAbsolutePath(), work.getAbsolutePath(), vendor); }
        catch (RuntimeException error) { throw new IOException(error.getMessage(), error); }
        return slicer;
    }

    String[] presets(int kind, String printer) { return presets(handle, kind, printer == null ? "" : printer); }

    /** Slices the models onto one plate with one filament. */
    Result slice(List<File> models, String printer, String process, List<String> filaments, List<String[]> overrides, File output, Listener listener) throws IOException {
        return slice(models, printer, process, filaments, null, null, overrides, output, listener);
    }

    /**
     * Slices the models onto one plate. Throws IOException with the engine's message on failure or cancellation.
     * colours: optional "#RRGGBB" per filament slot (null entries keep the preset's colour).
     * modelFilaments: optional 1-based slot per model (0 keeps the file's own assignment, e.g. a painted 3MF).
     */
    Result slice(List<File> models, String printer, String process, List<String> filaments, List<String> colours, int[] modelFilaments,
                 List<String[]> overrides, File output, Listener listener) throws IOException {
        String[] colourArray = new String[colours == null ? 0 : colours.size()];
        for (int i = 0; i < colourArray.length; i++) colourArray[i] = colours.get(i) == null ? "" : colours.get(i);
        String[] paths = new String[models.size()];
        for (int i = 0; i < paths.length; i++) paths[i] = models.get(i).getAbsolutePath();
        String[] keys = new String[overrides.size()], values = new String[overrides.size()];
        for (int i = 0; i < keys.length; i++) { keys[i] = overrides.get(i)[0]; values[i] = overrides.get(i)[1]; }
        try {
            String json = slice(handle, paths, printer, process, filaments.toArray(new String[0]), colourArray,
                modelFilaments == null ? new int[0] : modelFilaments, keys, values, output.getAbsolutePath(), listener);
            return new Result(new JSONObject(json));
        } catch (RuntimeException error) {
            throw new IOException(error.getMessage(), error);
        } catch (org.json.JSONException error) {
            throw new IOException("Unexpected result from the slicer", error);
        }
    }

    @Override public void close() { if (handle != 0) { destroy(handle); handle = 0; } }

    /** Copies assets/slicer to files/slicer when the bundled VERSION differs from the unpacked one. */
    static File unpack(Context context) throws IOException {
        AssetManager assets = context.getAssets();
        File target = new File(context.getFilesDir(), "slicer");
        String version = read(assets.open("slicer/VERSION"));
        File marker = new File(target, "VERSION");
        if (marker.isFile() && version.equals(read(new FileInputStream(marker)))) return target;
        deleteTree(target);
        String[] files = read(assets.open("slicer/files.txt")).split("\n");
        byte[] buffer = new byte[65536];
        for (String name : files) {
            if (name.isEmpty() || name.contains("..")) continue;
            File out = new File(target, name);
            File parent = out.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create " + parent);
            try (InputStream in = assets.open("slicer/" + name); OutputStream os = new FileOutputStream(out)) {
                int count; while ((count = in.read(buffer)) != -1) os.write(buffer, 0, count);
            }
        }
        try (OutputStream os = new FileOutputStream(marker)) { os.write(version.getBytes(StandardCharsets.UTF_8)); }
        return target;
    }

    private static String read(InputStream input) throws IOException {
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count; while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            return out.toString("UTF-8");
        }
    }

    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        file.delete();
    }

    private static native long create(String resources, String work, String vendor);
    private static native void destroy(long handle);
    private static native String[] presets(long handle, int kind, String printer);
    private static native String slice(long handle, String[] models, String printer, String process, String[] filaments,
                                       String[] colours, int[] modelFilaments, String[] keys, String[] values, String output, Listener listener);
}
