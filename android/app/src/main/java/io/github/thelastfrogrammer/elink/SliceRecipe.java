package io.github.thelastfrogrammer.elink;

import android.content.Context;
import android.os.Bundle;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * What each kept slice was made from: the slicer screen's models and settings (as it keeps them across a restart), with
 * the models kept beside the slices, so "Slice again with these changes" can start from exactly that slice. Kept for the
 * slices SliceStore keeps; a model no recipe needs any more is removed. Models already kept by Projects are not copied.
 */
final class SliceRecipe {
    /** Larger models are not copied (the recipe then asks for the model again). */
    static final long LARGEST = 300L * 1024 * 1024;
    /** What a recipe leaves out: the result shown at the time, not how to make it. */
    private static final Set<String> RESULT = new HashSet<>(java.util.Arrays.asList("sliced", "resultText", "slicedFiles", "slicedNames", "slicedName", "status", "tune"));

    static File recipes(Context context) { File dir = new File(SliceStore.directory(context), "recipes"); dir.mkdirs(); return dir; }
    static File models(Context context) { File dir = new File(SliceStore.directory(context), "models"); dir.mkdirs(); return dir; }
    private static File recipe(Context context, String name) { String safe = GcodeLibrary.safeName(name); return safe == null ? null : new File(recipes(context), safe + ".json"); }

    /** Keeps the recipe for each named slice. Worker only. */
    static void keep(Context context, List<String> names, Bundle state, List<File> inputs) {
        try {
            File own = new File(context.getFilesDir(), "projects").getCanonicalFile();
            ArrayList<String> kept = new ArrayList<>();
            for (File model : inputs) {
                File source = model.getCanonicalFile();
                if (source.getPath().startsWith(own.getPath() + File.separator)) { kept.add(source.getPath()); continue; }
                if (!source.isFile() || source.length() > LARGEST) { kept.add(""); continue; }
                // In a folder by its content, under its own name: the slice is named after the model.
                File folder = new File(models(context), sha256(source)), copy = new File(folder, source.getName());
                if (!copy.isFile()) {
                    folder.mkdirs(); File part = new File(folder, ".part");
                    Files.copy(source.toPath(), part.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    if (!part.renameTo(copy)) { part.delete(); kept.add(""); continue; }
                }
                kept.add(copy.getPath());
            }
            JSONObject json = toJson(state);
            json.put("models", new JSONObject().put("t", "sl").put("v", new JSONArray(kept)));
            json.put("names", new JSONArray(names));
            for (String n : names) { File f = recipe(context, n); if (f != null) Files.write(f.toPath(), json.toString().getBytes(StandardCharsets.UTF_8)); }
        } catch (IOException | JSONException | RuntimeException notKept) {
            Diagnostics.note(Diagnostics.SLICER, "slice recipe not kept: " + notKept.getClass().getSimpleName());
        }
        prune(context);
    }

    /** The screen state a slice was made from, or null when none was kept or one of its models is gone. */
    static Bundle load(Context context, String name) {
        File f = recipe(context, name);
        if (f == null || !f.isFile()) return null;
        try {
            Bundle state = fromJson(new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)));
            ArrayList<String> paths = state.getStringArrayList("models");
            if (paths == null) return null;
            for (String p : paths) if (p.isEmpty() || !new File(p).isFile()) return null;
            return state;
        } catch (IOException | JSONException | RuntimeException unreadable) { return null; }
    }

    /** Removes recipes for slices no longer kept, and models no recipe uses. */
    static synchronized void prune(Context context) {
        Set<String> slices = new HashSet<>(); for (File s : SliceStore.list(context)) slices.add(GcodeLibrary.safeName(s.getName()) + ".json");
        Set<String> used = new HashSet<>();
        File[] all = recipes(context).listFiles();
        if (all != null) for (File r : all) {
            if (!slices.contains(r.getName())) { r.delete(); continue; }
            try {
                JSONArray paths = new JSONObject(new String(Files.readAllBytes(r.toPath()), StandardCharsets.UTF_8)).getJSONObject("models").getJSONArray("v");
                for (int i = 0; i < paths.length(); i++) { File parent = new File(paths.getString(i)).getParentFile(); if (parent != null) used.add(parent.getName()); }
            } catch (IOException | JSONException unreadable) { r.delete(); }
        }
        File[] copies = models(context).listFiles();
        if (copies != null) for (File m : copies) if (!used.contains(m.getName())) delete(m);
    }

    /** A bundle of the types the slicer screen keeps, as JSON ({key: {t, v}}). Pure. */
    static JSONObject toJson(Bundle state) throws JSONException {
        JSONObject out = new JSONObject();
        for (String key : state.keySet()) {
            if (RESULT.contains(key)) continue;
            Object v = state.get(key);
            if (v instanceof String) out.put(key, new JSONObject().put("t", "s").put("v", v));
            else if (v instanceof Integer) out.put(key, new JSONObject().put("t", "i").put("v", v));
            else if (v instanceof Boolean) out.put(key, new JSONObject().put("t", "b").put("v", v));
            else if (v instanceof int[]) { JSONArray a = new JSONArray(); for (int x : (int[]) v) a.put(x); out.put(key, new JSONObject().put("t", "ia").put("v", a)); }
            else if (v instanceof double[]) { JSONArray a = new JSONArray(); for (double x : (double[]) v) a.put(x); out.put(key, new JSONObject().put("t", "da").put("v", a)); }
            else if (v instanceof String[]) out.put(key, new JSONObject().put("t", "sa").put("v", new JSONArray(java.util.Arrays.asList((String[]) v))));
            else if (v instanceof ArrayList) out.put(key, new JSONObject().put("t", "sl").put("v", new JSONArray((ArrayList<?>) v)));
        }
        return out;
    }
    static Bundle fromJson(JSONObject json) throws JSONException {
        Bundle out = new Bundle();
        for (java.util.Iterator<String> keys = json.keys(); keys.hasNext(); ) {
            String key = keys.next(); JSONObject one = json.optJSONObject(key); if (one == null) continue;
            switch (one.optString("t")) {
                case "s": out.putString(key, one.getString("v")); break;
                case "i": out.putInt(key, one.getInt("v")); break;
                case "b": out.putBoolean(key, one.getBoolean("v")); break;
                case "ia": { JSONArray a = one.getJSONArray("v"); int[] v = new int[a.length()]; for (int i = 0; i < v.length; i++) v[i] = a.getInt(i); out.putIntArray(key, v); break; }
                case "da": { JSONArray a = one.getJSONArray("v"); double[] v = new double[a.length()]; for (int i = 0; i < v.length; i++) v[i] = a.getDouble(i); out.putDoubleArray(key, v); break; }
                case "sa": { JSONArray a = one.getJSONArray("v"); String[] v = new String[a.length()]; for (int i = 0; i < v.length; i++) v[i] = a.getString(i); out.putStringArray(key, v); break; }
                case "sl": { JSONArray a = one.getJSONArray("v"); ArrayList<String> v = new ArrayList<>(); for (int i = 0; i < a.length(); i++) v.add(a.getString(i)); out.putStringArrayList(key, v); break; }
                default: break;
            }
        }
        return out;
    }

    private static void delete(File f) { File[] all = f.listFiles(); if (all != null) for (File c : all) delete(c); f.delete(); }
    private static String sha256(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            MessageDigest d = MessageDigest.getInstance("SHA-256"); byte[] buffer = new byte[65_536]; int n;
            while ((n = in.read(buffer)) > 0) d.update(buffer, 0, n);
            StringBuilder hex = new StringBuilder(); for (byte b : d.digest()) hex.append(String.format(Locale.ROOT, "%02x", b & 255));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private SliceRecipe() { }
}
