package io.github.thelastfrogrammer.elink;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Locale;
import org.json.JSONObject;

/**
 * Smooth timelapse: at every layer change the sliced G-code lifts the head a little, parks it at one corner, waits there
 * for the picture, and puts it back exactly where it was (Klipper's SAVE_GCODE_STATE / RESTORE_GCODE_STATE, which the CC2's
 * gcode_move module provides). The app, watching the printer, takes the layer's picture while the head reports that corner,
 * so every picture shows the print without the head in front of it, from the same moment of each layer.
 * The corner and the wait are kept per sliced file name, for the print's recording to know what to look for.
 */
final class SmoothTimelapse {
    static final String PREFS = "smooth-timelapse";
    /** Corners inside the CC2's 256 mm bed with 6 mm to spare: back right, back left, front right, front left (Y grows to the back). */
    static final String[] CORNERS = {"Back right", "Back left", "Front right", "Front left"};
    static final double[][] PARK = {{250, 250}, {6, 250}, {250, 6}, {6, 6}};
    static final int[] WAITS_MS = {2000, 3000, 5000, 8000};
    /** How close the reported head must be to the corner to count as parked (mm). */
    static final double NEAR_MM = 2.0;

    /** The layer-change G-code for a corner and wait (the engine's time_lapse_gcode, run right after each layer change). */
    static String gcode(int corner, int waitMs) {
        double[] at = PARK[Math.max(0, Math.min(PARK.length - 1, corner))];
        return String.format(Locale.ROOT, String.join("\n",
            "; Link Workshop smooth timelapse: park for the layer's picture, then return",
            "SAVE_GCODE_STATE NAME=link_timelapse",
            "G91",
            "G1 Z0.6 F900",
            "G90",
            "G1 X%.1f Y%.1f F18000",
            "M400",
            "G4 P%d",
            "RESTORE_GCODE_STATE NAME=link_timelapse MOVE=1 MOVE_SPEED=250",
            // Restated for anything that reads the file (previews, estimates): the restore above already put the head back.
            "G1 Z{layer_z} F900"), at[0], at[1], Math.max(500, Math.min(15000, waitMs)));
    }

    /** Remembers the park for these output files (or forgets it, when the slice had none). */
    static void remember(Context context, Iterable<String> names, int corner, int waitMs, boolean on) {
        SharedPreferences.Editor edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        for (String name : names) {
            String key = GcodeLibrary.safeName(name);
            if (!on) { edit.remove(key); continue; }
            try { edit.putString(key, new JSONObject().put("x", PARK[corner][0]).put("y", PARK[corner][1]).put("wait", waitMs).toString()); }
            catch (org.json.JSONException impossible) { throw new IllegalStateException(impossible); }
        }
        edit.apply();
    }

    /** The park {x, y, wait ms} for a printed file name, or null when it was not sliced here with a smooth timelapse. */
    static double[] forFile(Context context, String filename) {
        if (filename == null || filename.isEmpty()) return null;
        String name = filename.replaceFirst("^.*/", "");
        String stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(GcodeLibrary.safeName(name), null);
        if (stored == null) return null;
        try { JSONObject park = new JSONObject(stored); return new double[] {park.getDouble("x"), park.getDouble("y"), park.optDouble("wait", 3000)}; }
        catch (org.json.JSONException damaged) { return null; }
    }

    /** Whether a reported head position is at the park corner. Pure, for tests. */
    static boolean parked(double x, double y, double parkX, double parkY) {
        return !Double.isNaN(x) && !Double.isNaN(y) && Math.abs(x - parkX) <= NEAR_MM && Math.abs(y - parkY) <= NEAR_MM;
    }
}
