package io.github.thelastfrogrammer.elink;

import static org.junit.Assert.*;

import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

/** The Layer charts screen for a file before printing: it reads the file and shows the four charts behind chips. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w411dp-h891dp-xxhdpi")
public class LayerChartsActivityTest {
    private static int count(View view, Class<?> kind) {
        int n = kind.isInstance(view) ? 1 : 0;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) n += count(((ViewGroup) view).getChildAt(i), kind);
        return n;
    }
    private static boolean says(View view, String part) {
        if (view instanceof TextView && ((TextView) view).getText().toString().contains(part)) return true;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) if (says(((ViewGroup) view).getChildAt(i), part)) return true;
        return false;
    }

    @Test public void aFileShowsItsLayerCharts() throws Exception {
        File file = new File(RuntimeEnvironment.getApplication().getCacheDir(), "box.gcode");
        Files.write(file.toPath(), String.join("\n", "; filament_max_volumetric_speed = 1", "M83",
            ";LAYER_CHANGE", ";Z:0.2", ";HEIGHT:0.2", ";TYPE:Outer wall", "G1 X10 Y10 F6000", "G1 X40 Y10 E2 F6000",
            ";LAYER_CHANGE", ";Z:0.4", ";HEIGHT:0.2", ";TYPE:Sparse infill", "G1 X40 Y40 E1 F3000", "").getBytes(StandardCharsets.UTF_8));
        LayerChartsActivity activity = Robolectric.buildActivity(LayerChartsActivity.class,
            new Intent().putExtra(LayerChartsActivity.EXTRA_FILE, file.getAbsolutePath()).putExtra(LayerChartsActivity.EXTRA_NAME, "box.gcode")).setup().get();
        View root = activity.findViewById(android.R.id.content);
        for (int i = 0; i < 100 && count(root, ChartView.class) == 0; i++) { Thread.sleep(20); ShadowLooper.idleMainLooper(); }
        assertEquals("cross section, speed, flow, acceleration", 4, count(root, ChartView.class));
        assertTrue("the layers asking for more than the filament's 1 mm³/s are named", says(root, "Layers 1–2 ask for more flow"));
    }

    @Test public void aMissingFileSaysSo() {
        LayerChartsActivity activity = Robolectric.buildActivity(LayerChartsActivity.class,
            new Intent().putExtra(LayerChartsActivity.EXTRA_FILE, "/nowhere/gone.gcode")).setup().get();
        assertTrue(says(activity.findViewById(android.R.id.content), "not on this phone any more"));
    }
}
