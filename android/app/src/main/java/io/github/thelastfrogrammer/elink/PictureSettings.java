package io.github.thelastfrogrammer.elink;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * When a print's recording takes its pictures, kept for each camera (the printer's own under the plain keys, an added camera
 * under its own): every N minutes, or every N layers at any moment of the layer or with the head far from the camera.
 * Prints sliced with Smooth timelapse take theirs with the head parked, whatever is set here.
 */
final class PictureSettings {
    static final int[] MINUTES = {1, 2, 5, 10, 15, 30, 60};
    static final int[] LAYERS = {1, 2, 3, 5, 10, 20};

    /** The camera that takes the pictures: "" for the printer's own, else an added camera's id. */
    static String camera(Context context) { ExtraCameras.Camera c = ExtraCameras.forPictures(context); return c == null ? "" : c.id; }
    private static SharedPreferences prefs(Context context) { return context.getSharedPreferences("workshop-settings", Context.MODE_PRIVATE); }
    private static String key(String base, String id) { return GcodeViewerActivity.camKey(base, id); }

    /** Minutes between pictures (0 = by layers or none); an added camera without its own choice follows the printer's camera. */
    static int minutes(Context context, String id) { SharedPreferences s = prefs(context); return s.getInt(key("pictureMinutes", id), s.getInt("pictureMinutes", 5)); }
    static int layers(Context context, String id) { SharedPreferences s = prefs(context); return s.getInt(key("pictureLayers", id), s.getInt("pictureLayers", 0)); }
    static boolean far(Context context, String id) { SharedPreferences s = prefs(context); return s.getBoolean(key("pictureFar", id), s.getBoolean("pictureFar", false)); }

    /** Off: minutes 0 and layers 0. By time: minutes > 0. By layers: layers > 0, with or without far. */
    static void save(Context context, String id, int minutes, int layers, boolean far) {
        prefs(context).edit().putInt(key("pictureMinutes", id), minutes).putInt(key("pictureLayers", id), layers).putBoolean(key("pictureFar", id), far && layers > 0).apply();
    }

    /** "Every 5 minutes", "Every 2 layers, head far from the camera", "No pictures". */
    static String words(int minutes, int layers, boolean far) {
        if (layers > 0) return (layers == 1 ? "Every layer" : "Every " + layers + " layers") + (far ? ", head far from the camera" : "");
        return minutes == 0 ? "No pictures" : minutes == 1 ? "Every minute" : "Every " + minutes + " minutes";
    }
}
