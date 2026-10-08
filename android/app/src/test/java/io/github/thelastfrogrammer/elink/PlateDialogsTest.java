package io.github.thelastfrogrammer.elink;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;

/** The plate screen's dialogs and what their buttons do through the page bridge (window.plate calls). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class PlateDialogsTest {
    private PlateActivity activity;

    @org.junit.After public void restore() { PlateActivity.prepareEnabled = true; }

    @Before public void open() throws Exception {
        PlateActivity.prepareEnabled = false;
        JSONObject inspected = new JSONObject().put("files", new JSONArray().put(new JSONObject().put("objects", new JSONArray()
            .put(new JSONObject().put("name", "3DBenchy")).put(new JSONObject().put("name", "elegoo_cube")))));
        Intent intent = new Intent(org.robolectric.RuntimeEnvironment.getApplication(), PlateActivity.class)
            .putExtra(PlateActivity.EXTRA_MODELS, new String[] {"/none/model.3mf"}).putExtra(PlateActivity.EXTRA_SELECTION, "{}").putExtra(PlateActivity.EXTRA_INSPECTED, inspected.toString());
        activity = Robolectric.buildActivity(PlateActivity.class, intent).setup().get();
        set("pageReady", true);
        set("placements", new JSONArray()
            .put(new JSONObject().put("file", 0).put("object", 0).put("x", 100).put("y", 100).put("rotation", 0).put("scale", 1))
            .put(new JSONObject().put("file", 0).put("object", 1).put("x", 150).put("y", 100).put("rotation", 0).put("scale", 1)));
        select(0);
    }

    private void set(String name, Object value) throws Exception { Field f = PlateActivity.class.getDeclaredField(name); f.setAccessible(true); f.set(activity, value); }
    private Object get(String name) throws Exception { Field f = PlateActivity.class.getDeclaredField(name); f.setAccessible(true); return f.get(activity); }
    private void select(int index) throws Exception {
        set("selected", index);
        for (String method : new String[] {"showSelected", "setButtons"}) { java.lang.reflect.Method m = PlateActivity.class.getDeclaredMethod(method); m.setAccessible(true); m.invoke(activity); }
    }
    private String lastScript() throws Exception { return Shadows.shadowOf((WebView) get("web")).getLastEvaluatedJavascript(); }
    private Button more() throws Exception { return (Button) get("more"); }
    private AlertDialog dialog() { AlertDialog d = ShadowAlertDialog.getLatestAlertDialog(); assertNotNull(d); assertTrue(d.isShowing()); return d; }

    static void collect(View view, List<View> out) {
        out.add(view);
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) collect(((ViewGroup) view).getChildAt(i), out);
    }
    static List<Button> buttons(AlertDialog dialog) {
        List<View> all = new ArrayList<>(); collect(dialog.getWindow().getDecorView(), all);
        List<Button> out = new ArrayList<>();
        for (View v : all) if (v instanceof Button && v.getVisibility() == View.VISIBLE && ((Button) v).getText().length() > 0) out.add((Button) v);
        return out;
    }
    static Button button(AlertDialog dialog, String text) {
        for (Button b : buttons(dialog)) if (b.getText().toString().equals(text)) return b;
        throw new AssertionError("no button " + text + " in " + buttons(dialog).size() + " buttons");
    }
    private void idle() { Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); }

    @Test public void moreListsTheSelectedModelsActions() throws Exception {
        more().performClick(); idle();
        AlertDialog d = dialog();
        List<String> names = new ArrayList<>(); for (Button b : buttons(d)) names.add(b.getText().toString());
        assertEquals(Arrays.asList("Select a model…", "Move…", "Copy", "Lay flat on a face", "Upright again", "Scale…", "Remove", "Model settings…", "Reset view", "Close"), names);
        for (String name : names.subList(0, 9)) assertTrue(name, button(d, name).isEnabled());
        assertTrue(button(d, "Copy").getMinHeight() >= 0);
    }

    @Test public void moreIsDisabledUntilAModelIsSelected() throws Exception {
        select(-1);
        more().performClick(); idle();
        AlertDialog d = dialog();
        for (String name : new String[] {"Move…", "Copy", "Lay flat on a face", "Upright again", "Scale…", "Remove", "Model settings…"}) assertFalse(name, button(d, name).isEnabled());
        assertTrue("choosing a model works without a selection", button(d, "Select a model…").isEnabled());
        assertTrue(button(d, "Reset view").isEnabled());
    }

    @Test public void copyRemoveAndUprightCallThePage() throws Exception {
        for (String[] pair : new String[][] {{"Copy", "plate.duplicate()"}, {"Remove", "plate.remove()"}, {"Upright again", "plate.upright()"}}) {
            more().performClick(); idle();
            button(dialog(), pair[0]).performClick(); idle();
            assertEquals(pair[0], pair[1], lastScript());
        }
    }

    @Test public void selectListsTheModelsAndSelectsThroughThePage() throws Exception {
        select(-1);
        more().performClick(); idle();
        button(dialog(), "Select a model…").performClick(); idle();
        AlertDialog d = dialog();
        android.widget.ListView list = d.getListView();
        assertEquals(2, list.getCount());
        assertEquals("1. 3DBenchy", list.getItemAtPosition(0).toString()); assertEquals("2. elegoo_cube", list.getItemAtPosition(1).toString());
        list.performItemClick(null, 1, 1); idle();
        assertEquals("plate.select(1)", lastScript());
    }

    @Test public void moveButtonsMoveBySetStepsAndKeepTheDialogOpen() throws Exception {
        more().performClick(); idle();
        button(dialog(), "Move…").performClick(); idle();
        AlertDialog d = dialog();
        for (Button b : buttons(d)) if (b.getContentDescription() != null && b.getContentDescription().toString().equals("Move 5 millimetres left")) { b.performClick(); break; }
        idle(); assertEquals("plate.move(-5,0)", lastScript());
        for (Button b : buttons(d)) if (b.getContentDescription() != null && b.getContentDescription().toString().equals("Move 1 millimetre back")) { b.performClick(); break; }
        idle(); assertEquals("plate.move(0,1)", lastScript());
        for (Button b : buttons(d)) if (b.getContentDescription() != null && b.getContentDescription().toString().equals("Move 10 millimetres right")) { b.performClick(); break; }
        assertEquals("plate.move(10,0)", lastScript());
        assertTrue("dialog stays open for repeated nudges", d.isShowing());
    }

    @Test public void resetViewCallsThePage() throws Exception {
        more().performClick(); idle();
        button(dialog(), "Reset view").performClick(); idle();
        assertEquals("plate.resetCamera()", lastScript());
    }

    @Test public void layFlatStartsAndCancels() throws Exception {
        more().performClick(); idle();
        button(dialog(), "Lay flat on a face").performClick(); idle();
        assertEquals("plate.setLayMode(true)", lastScript());
        assertEquals("Cancel lay flat", more().getText().toString());
        more().performClick(); idle();
        assertEquals("plate.setLayMode(false)", lastScript());
        assertEquals("More…", more().getText().toString());
    }

    @Test public void scaleAppliesThroughThePage() throws Exception {
        more().performClick(); idle();
        button(dialog(), "Scale…").performClick(); idle();
        AlertDialog scale = dialog();
        List<View> all = new ArrayList<>(); collect(scale.getWindow().getDecorView(), all);
        EditText input = null; for (View v : all) if (v instanceof EditText) input = (EditText) v;
        assertNotNull(input); assertEquals("100", input.getText().toString());
        input.setText("150");
        scale.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle();
        assertEquals("plate.setScale(1.5)", lastScript());
    }

    @Test public void scaleOutOfRangeIsRefusedInWords() throws Exception {
        more().performClick(); idle();
        button(dialog(), "Scale…").performClick(); idle();
        AlertDialog scale = dialog();
        List<View> all = new ArrayList<>(); collect(scale.getWindow().getDecorView(), all);
        for (View v : all) if (v instanceof EditText) ((EditText) v).setText("5000");
        String before = lastScript();
        scale.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle();
        assertEquals(before, lastScript());
        assertEquals("Scale between 1% and 2000%.", ((TextView) get("status")).getText().toString());
    }

    @Test public void modelSettingsReturnsTheLayoutAndTheObject() throws Exception {
        more().performClick(); idle();
        button(dialog(), "Model settings…").performClick(); idle();
        assertTrue(activity.isFinishing());
        Intent result = Shadows.shadowOf(activity).getResultIntent();
        assertNotNull(result);
        assertArrayEquals(new int[] {0, 0}, result.getIntArrayExtra(PlateActivity.EXTRA_EDIT_OBJECT));
        assertEquals("3DBenchy", result.getStringExtra(PlateActivity.EXTRA_EDIT_NAME));
        assertEquals(2, new JSONArray(result.getStringExtra(PlateActivity.EXTRA_PLACEMENTS)).length());
    }

    private static void assertArrayEquals(int[] expected, int[] actual) { assertEquals(Arrays.toString(expected), Arrays.toString(actual)); }

    @Test public void doneWithAProblemAsksFirstAndFixItStays() throws Exception {
        set("problemCount", 2);
        ((Button) get("done")).performClick(); idle();
        AlertDialog d = dialog();
        assertTrue(Shadows.shadowOf(d).getTitle().toString().contains("problem"));
        assertTrue(Shadows.shadowOf(d).getMessage().toString().contains("Arrange all"));
        button(d, "Fix it").performClick(); idle();
        assertFalse(activity.isFinishing());
    }

    @Test public void goBackAnywayFinishesWithThePlacements() throws Exception {
        set("problemCount", 2);
        ((Button) get("done")).performClick(); idle();
        button(dialog(), "Go back anyway").performClick(); idle();
        assertTrue(activity.isFinishing());
        assertEquals(Activity.RESULT_OK, Shadows.shadowOf(activity).getResultCode());
        assertEquals(2, new JSONArray(Shadows.shadowOf(activity).getResultIntent().getStringExtra(PlateActivity.EXTRA_PLACEMENTS)).length());
    }

    @Test public void doneWithoutAProblemFinishesAtOnce() throws Exception {
        ((Button) get("done")).performClick(); idle();
        assertTrue(activity.isFinishing());
        assertEquals(Activity.RESULT_OK, Shadows.shadowOf(activity).getResultCode());
    }
}
