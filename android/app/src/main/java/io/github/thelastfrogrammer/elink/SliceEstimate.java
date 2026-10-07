package io.github.thelastfrogrammer.elink;

import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Rough peak memory of a slice, from what the engine's inspect() reports. Fitted to link-slicer runs (Linux,
 * Centauri Carbon 2, 0.20 mm): about 120 MB of presets and engine state, 0.33 KB per mesh triangle and 3.8 KB per
 * square millimetre of model surface, the surface term growing as layers get thinner. Spheres of 40 k to 2 M
 * triangles and 60 to 200 mm landed within 10% of the measured peak; Benchy is overestimated by 15%.
 * Independent of Android.
 */
final class SliceEstimate {
    static final double BASE_MB = 120, KB_PER_TRIANGLE = 0.33, KB_PER_MM2 = 3.8;

    final long triangles;
    final double areaMm2;
    final double megabytes;

    SliceEstimate(long triangles, double areaMm2, double layerHeight) {
        this.triangles = triangles; this.areaMm2 = areaMm2;
        double layers = layerHeight > 0.01 ? 0.2 / layerHeight : 1;
        megabytes = BASE_MB + triangles * KB_PER_TRIANGLE / 1024 + areaMm2 * KB_PER_MM2 / 1024 * layers;
    }

    /**
     * From inspect() JSON. scales: optional uniform scale per "file:object" (placements), applied to the surface;
     * copies of an object share their slices, so each object counts once.
     */
    static SliceEstimate of(JSONObject inspected, java.util.Map<String, Double> scales, double layerHeight) {
        long triangles = 0; double area = 0;
        JSONArray files = inspected.optJSONArray("files");
        if (files != null) for (int f = 0; f < files.length(); f++) {
            JSONArray objects = files.optJSONObject(f).optJSONArray("objects");
            if (objects == null) continue;
            for (int o = 0; o < objects.length(); o++) {
                JSONObject object = objects.optJSONObject(o);
                Double scale = scales == null ? null : scales.get(f + ":" + o);
                if (scales != null && !scales.isEmpty() && scale == null) continue; // left off the plate
                double s = scale == null ? 1 : scale;
                triangles += object.optLong("triangles");
                area += object.optDouble("area", 0) * s * s;
            }
        }
        return new SliceEstimate(triangles, area, layerHeight);
    }

    /** True when the slice likely needs more than the share of free memory Android leaves an app in the foreground. */
    boolean risky(long availableBytes) { return availableBytes > 0 && megabytes * 1024 * 1024 > availableBytes * 0.8; }

    String describe() {
        return String.format(Locale.ROOT, "about %.0f MB (%,d triangles, %.0f cm² surface)", megabytes, triangles, areaMm2 / 100);
    }
}
