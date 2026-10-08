package io.github.thelastfrogrammer.elink;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.AlertDialog;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;

/** The viewer's legend, More… and Show / hide dialogs, and the type names. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ViewerDialogsTest {
    private GcodeViewerActivity activity;

    @Before public void open() throws Exception {
        File gcode = new File(org.robolectric.RuntimeEnvironment.getApplication().getCacheDir(), "t.gcode");
        try (java.io.InputStream in = getClass().getResourceAsStream("/gcode/tolerance-cc2.gcode")) { java.nio.file.Files.copy(in, gcode.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
        Intent intent = new Intent(org.robolectric.RuntimeEnvironment.getApplication(), GcodeViewerActivity.class)
            .putExtra(GcodeViewerActivity.EXTRA_FILE, gcode.getAbsolutePath()).putExtra(GcodeViewerActivity.EXTRA_NAME, "t.gcode");
        activity = Robolectric.buildActivity(GcodeViewerActivity.class, intent).setup().get();
        Field path = GcodeViewerActivity.class.getDeclaredField("path"); path.setAccessible(true);
        long deadline = System.currentTimeMillis() + 30_000;
        while (path.get(activity) == null && System.currentTimeMillis() < deadline) { Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); Thread.sleep(20); }
        Field ready = GcodeViewerActivity.class.getDeclaredField("pageReady"); ready.setAccessible(true); ready.set(activity, true);
        java.lang.reflect.Method enable = GcodeViewerActivity.class.getDeclaredMethod("setControlsEnabled", boolean.class); enable.setAccessible(true); enable.invoke(activity, true);
    }

    private Object get(String name) throws Exception { Field f = GcodeViewerActivity.class.getDeclaredField(name); f.setAccessible(true); return f.get(activity); }
    private void idle() { Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); }
    private AlertDialog dialog() { AlertDialog d = ShadowAlertDialog.getLatestAlertDialog(); assertTrue(d.isShowing()); return d; }
    private String lastScript() throws Exception { return Shadows.shadowOf((WebView) get("web")).getLastEvaluatedJavascript(); }

    @Test public void engineNamesGetPlainOnes() {
        assertEquals("Start / end G-code", GcodeViewerActivity.displayName(16));
        assertEquals("Outer wall", GcodeViewerActivity.displayName(0));
        assertEquals("Bridge", GcodeViewerActivity.displayName(7));
        for (int i = 0; i < GcodeToolpath.FEATURES.length; i++) assertTrue(GcodeViewerActivity.displayName(i), !GcodeViewerActivity.displayName(i).equals("Custom"));
    }

    @Test public void legendKeepsSupportsAndThePrimeTowerAheadOfTheLongest() {
        double[] lengths = new double[GcodeToolpath.FEATURES.length];
        lengths[0] = 900; lengths[1] = 800; lengths[3] = 700; lengths[4] = 600; lengths[5] = 500; lengths[11] = 5; lengths[14] = 3;
        int[] chosen = GcodeViewerActivity.legendTypes(lengths, 4);
        assertEquals(Arrays.toString(new int[] {0, 1, 11, 14}), Arrays.toString(chosen));
        assertEquals(0, GcodeViewerActivity.legendTypes(new double[GcodeToolpath.FEATURES.length], 4).length);
    }

    @Test public void legendIsAFewChipsEachAtLeast48dpTall() throws Exception {
        ViewGroup flow = (ViewGroup) get("legend");
        assertTrue(flow.getChildCount() >= 2 && flow.getChildCount() <= 5);
        float density = activity.getResources().getDisplayMetrics().density;
        for (int i = 0; i < flow.getChildCount(); i++) assertTrue(((View) flow.getChildAt(i)).getMinimumHeight() >= 48 * density - 1);
        View last = flow.getChildAt(flow.getChildCount() - 1);
        assertTrue(last.getContentDescription().toString().contains("more line types"));
    }

    @Test public void tappingAChipHidesThatTypeAndHoldingShowsOnlyIt() throws Exception {
        ViewGroup flow = (ViewGroup) get("legend");
        flow.getChildAt(0).performClick(); idle();
        assertEquals(1, ((Integer) get("hidden")) & 1);
        assertTrue(lastScript().contains("\"hidden\":" + get("hidden")));
        flow = (ViewGroup) get("legend");
        flow.getChildAt(0).performLongClick(); idle();
        int hidden = (Integer) get("hidden");
        assertEquals(0, hidden & 1);
        assertTrue(hidden != 0);
    }

    @Test public void moreListsTheViewerActionsAndTheListTogglesATypeThroughThePage() throws Exception {
        ((Button) get("more")).performClick(); idle();
        AlertDialog d = dialog();
        List<String> names = new ArrayList<>(); for (Button b : PlateDialogsTest.buttons(d)) names.add(b.getText().toString());
        assertEquals(Arrays.asList("Show / hide line types…", "Show travel moves (blue)", "Step through this layer…", "What am I seeing?", "Close"), names);
        PlateDialogsTest.button(d, "Show travel moves (blue)").performClick(); idle();
        assertTrue(lastScript().contains("\"showTravel\":true"));
        ((Button) get("more")).performClick(); idle();
        PlateDialogsTest.button(dialog(), "Show / hide line types…").performClick(); idle();
        List<View> all = new ArrayList<>(); PlateDialogsTest.collect(dialog().getWindow().getDecorView(), all);
        List<CheckBox> boxes = new ArrayList<>(); for (View v : all) if (v instanceof CheckBox) boxes.add((CheckBox) v);
        assertTrue(boxes.size() >= 5);
        boolean named = false; for (CheckBox b : boxes) named |= b.getText().toString().startsWith("Start / end G-code");
        assertTrue(named);
        boxes.get(0).setChecked(false); idle();
        assertEquals(1, ((Integer) get("hidden")) & 1);
        dialog().getButton(AlertDialog.BUTTON_NEUTRAL).performClick(); idle();
        assertEquals(0, (int) (Integer) get("hidden"));
    }

    @Test public void helpExplainsTheColoursAndTheStepSliderAppears() throws Exception {
        ((Button) get("more")).performClick(); idle();
        PlateDialogsTest.button(dialog(), "What am I seeing?").performClick(); idle();
        String text = Shadows.shadowOf(dialog()).getMessage().toString();
        assertTrue(text.contains("Travel moves") && text.contains("Gestures"));
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle();
        ((Button) get("more")).performClick(); idle();
        PlateDialogsTest.button(dialog(), "Step through this layer…").performClick(); idle();
        assertEquals(View.VISIBLE, ((View) get("moveBar")).getVisibility());
    }
}
