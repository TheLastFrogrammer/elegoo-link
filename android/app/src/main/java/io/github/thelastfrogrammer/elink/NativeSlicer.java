package io.github.thelastfrogrammer.elink;

import android.content.Context;
import android.content.res.AssetManager;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    /** What to slice with: presets, filament slots, settings and layout. Serialized as the engine's selection JSON. */
    static final class Selection {
        String printer, process;
        final List<String> filaments = new ArrayList<>(), colours = new ArrayList<>();
        int[] modelFilaments;
        final Map<String, String> overrides = new LinkedHashMap<>();
        int plate;
        boolean projectSettings;
        /** One per copy: {file, object, x, y, rotation, scale[, down x, y, z]}; empty lets the engine arrange. */
        final List<double[]> placements = new ArrayList<>();

        Selection(String printer, String process, List<String> filaments) { this.printer = printer; this.process = process; this.filaments.addAll(filaments); }

        String toJson() {
            try {
                JSONObject json = new JSONObject().put("printer", printer).put("process", process).put("filaments", new JSONArray(filaments));
                JSONArray colourList = new JSONArray(); for (String colour : colours) colourList.put(colour == null ? "" : colour);
                json.put("colours", colourList);
                JSONArray slots = new JSONArray(); if (modelFilaments != null) for (int slot : modelFilaments) slots.put(slot);
                json.put("model_filaments", slots);
                JSONObject settings = new JSONObject(); for (Map.Entry<String, String> entry : overrides.entrySet()) settings.put(entry.getKey(), entry.getValue());
                json.put("overrides", settings).put("plate", plate).put("project_settings", projectSettings);
                JSONArray places = new JSONArray();
                for (double[] p : placements) {
                    JSONObject place = new JSONObject().put("file", (int) p[0]).put("object", (int) p[1]).put("x", p[2]).put("y", p[3]).put("rotation", p[4]).put("scale", p[5]);
                    if (p.length >= 9 && (p[6] != 0 || p[7] != 0 || p[8] != 0)) place.put("down", new JSONArray().put(p[6]).put(p[7]).put(p[8]));
                    places.put(place);
                }
                return json.put("placements", places).toString();
            } catch (org.json.JSONException impossible) { throw new IllegalStateException(impossible); }
        }
    }

    /** Slices the models onto one plate with one filament. */
    Result slice(List<File> models, String printer, String process, List<String> filaments, List<String[]> overrides, File output, Listener listener) throws IOException {
        return slice(models, printer, process, filaments, null, null, overrides, output, listener);
    }

    /**
     * Slices the models onto one plate.
     * colours: optional "#RRGGBB" per filament slot (null entries keep the preset's colour).
     * modelFilaments: optional 1-based slot per model (0 keeps the file's own assignment, e.g. a painted 3MF).
     */
    Result slice(List<File> models, String printer, String process, List<String> filaments, List<String> colours, int[] modelFilaments,
                 List<String[]> overrides, File output, Listener listener) throws IOException {
        Selection selection = new Selection(printer, process, filaments);
        if (colours != null) selection.colours.addAll(colours);
        selection.modelFilaments = modelFilaments;
        for (String[] pair : overrides) selection.overrides.put(pair[0], pair[1]);
        return slice(models, selection, output, listener);
    }

    /** Slices the models onto one plate. Throws IOException with the engine's message on failure or cancellation. */
    Result slice(List<File> models, Selection selection, File output, Listener listener) throws IOException {
        try {
            return new Result(new JSONObject(slice(handle, paths(models), selection.toJson(), output.getAbsolutePath(), listener)));
        } catch (RuntimeException error) {
            throw new IOException(error.getMessage(), error);
        } catch (org.json.JSONException error) {
            throw new IOException("Unexpected result from the slicer", error);
        }
    }

    /** Objects, 3MF plates and project settings of the model files; with meshDir, simplified meshes for previews. */
    JSONObject inspect(List<File> models, File meshDir, int maxTriangles) throws IOException {
        return json(() -> inspect(handle, paths(models), meshDir == null ? "" : meshDir.getAbsolutePath(), maxTriangles));
    }

    /** Where the engine would place each object (and the bed outline, excluded area and prime tower). */
    JSONObject arrange(List<File> models, Selection selection) throws IOException {
        return json(() -> arrange(handle, paths(models), selection.toJson()));
    }

    /** Definitions, preset values and effective values of the given setting keys. */
    JSONObject describe(List<File> models, Selection selection, List<String> keys) throws IOException {
        return json(() -> describe(handle, paths(models), selection.toJson(), keys.toArray(new String[0])));
    }

    /** describe() and arrange() with a selection already in the engine's JSON form. */
    JSONObject describeJson(List<File> models, String selectionJson, List<String> keys) throws IOException {
        return json(() -> describe(handle, paths(models), selectionJson, keys.toArray(new String[0])));
    }
    JSONObject arrangeJson(List<File> models, String selectionJson) throws IOException {
        return json(() -> arrange(handle, paths(models), selectionJson));
    }

    private interface Call { String run(); }
    private static JSONObject json(Call call) throws IOException {
        try { return new JSONObject(call.run()); }
        catch (RuntimeException error) { throw new IOException(error.getMessage(), error); }
        catch (org.json.JSONException error) { throw new IOException("Unexpected result from the slicer", error); }
    }
    private static String[] paths(List<File> models) {
        String[] paths = new String[models.size()];
        for (int i = 0; i < paths.length; i++) paths[i] = models.get(i).getAbsolutePath();
        return paths;
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
    private static native String slice(long handle, String[] models, String selection, String output, Listener listener);
    private static native String inspect(long handle, String[] models, String meshDir, int maxTriangles);
    private static native String arrange(long handle, String[] models, String selection);
    private static native String describe(long handle, String[] models, String selection, String[] keys);
}
