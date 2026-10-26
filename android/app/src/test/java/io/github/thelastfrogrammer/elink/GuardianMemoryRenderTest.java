package io.github.thelastfrogrammer.elink;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/**
 * The guardian's settings: what it learned from answers on the printer's camera and how that does on a recorded print, and
 * forgetting it. Renders the card light and dark with -Dscreenshots=DIR (DIR/guardian-memory-<theme>.png).
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 34, qualifiers = "w411dp-h891dp-notnight-xxhdpi")
public class GuardianMemoryRenderTest {
    private static android.content.Context context() { return RuntimeEnvironment.getApplication(); }
    private static Object field(Object target, String name) throws Exception { Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target); }
    private static void settle() throws Exception { for (int i = 0; i < 3; i++) { Thread.sleep(400); org.robolectric.shadows.ShadowLooper.idleMainLooper(); } }

    /** Seven "looks fine" answers on the printer's camera (not lined up), and one recorded print with a false and a real alarm. */
    private static void seed() throws Exception {
        String key = GuardianMemory.key(PrintRecorder.PRINTER_CAMERA, GuardianMemory.lineUp(false, 0, null));
        List<double[]> checks = new ArrayList<>();
        double[] values = {0.01, 0.05, 0.05, 0.052, 0.01, 0.2, 0.2, 0.2};
        int run = 0; boolean on = false;
        for (int i = 0; i < values.length; i++) {
            boolean over = values[i] > 0.03; run = over ? run + 1 : 0; boolean raise = run >= 3 && !on; on = run >= 3;
            checks.add(new double[] {i + 1, PrintRecorder.GUARDIAN_OFF_PRINT, values[i], 0.03, over ? 1 : 0, raise ? 1 : 0, 1});
        }
        GuardianMemory memory = new GuardianMemory();
        for (int i = 0; i < 6; i++) memory.learn(key, java.util.Collections.singletonList(new GuardianMemory.Answer(PrintRecorder.GUARDIAN_OFF_PRINT, 1.6, true, 100 + i)));
        memory.learn(key, GuardianMemory.answered(checks, 0, 4, true, 200));
        memory.learn(key, GuardianMemory.answered(checks, 4, 8, false, 300));
        context().getSharedPreferences(GuardianRun.MEMORY, 0).edit().putString("answers", memory.toJson()).commit();
        File dir = RecordingsActivity.directory(context()); dir.mkdirs();
        PrintRecorder.Recording r = new PrintRecorder.Recording(new File(dir, "print-1.csv"), new File(dir, "print-1.json"));
        r.id = "p1"; r.file = "Cube.gcode"; r.outcome = "complete"; r.start = 1000; r.guardianCamera = key; r.guardianChecks.addAll(checks);
        r.journal.guardian.add(new JSONObject().put("at", 200).put("fine", true).put("check", 4));
        r.journal.guardian.add(new JSONObject().put("at", 300).put("fine", false).put("check", 8));
        java.nio.file.Files.write(r.meta.toPath(), r.toJson().toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void render(boolean dark) throws Exception {
        context().getSharedPreferences("workshop-settings", 0).edit().clear().putInt("theme", dark ? 2 : 1).putInt("page", 0).commit();
        seed();
        PrinterService service = Robolectric.setupService(PrinterService.class);
        ((org.robolectric.shadows.ShadowApplication) org.robolectric.shadow.api.Shadow.extract(context())).setComponentNameAndServiceForBindService(
            new android.content.ComponentName(context(), PrinterService.class), service.onBind(null));
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        Method page = MainActivity.class.getDeclaredMethod("selectPage", int.class); page.setAccessible(true); page.invoke(activity, 4);
        Method open = null; for (Method m : MainActivity.class.getDeclaredMethods()) if (m.getName().equals("openSection")) open = m; open.setAccessible(true);
        open.invoke(activity, field(activity, "preferencesSection"));
        settle();
        TextView learned = (TextView) field(activity, "guardianLearned"); Button forget = (Button) field(activity, "guardianForget");
        String text = learned.getText().toString();
        assertTrue(text, text.startsWith("Learned from 8 answers on the printer's camera: allows 1.6× more filament off the print than at first."));
        assertTrue(text, text.contains("On your last recorded print this would have raised 1 alarm instead of 2, and would still have raised the one you called real."));
        assertEquals(View.VISIBLE, forget.getVisibility());
        View card = (View) learned.getParent();
        String dir = System.getProperty("screenshots", "");
        if (!dir.isEmpty()) {
            card.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            card.layout(0, 0, 1080, card.getMeasuredHeight());
            Bitmap bitmap = Bitmap.createBitmap(1080, card.getMeasuredHeight(), Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(dark ? 0xff101418 : 0xfff4f6f6);
            card.draw(new Canvas(bitmap));
            File file = new File(dir, "guardian-memory-" + (dark ? "dark" : "light") + ".png"); file.getParentFile().mkdirs();
            try (FileOutputStream stream = new FileOutputStream(file)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream); }
        }
        // Forgetting goes back to the first limits once confirmed.
        forget.performClick();
        android.app.AlertDialog dialog = (android.app.AlertDialog) org.robolectric.shadows.ShadowDialog.getLatestDialog();
        assertTrue(dialog.isShowing());
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick();
        settle();
        assertTrue(GuardianRun.memory(context()).empty());
        assertTrue(learned.getText().toString(), learned.getText().toString().contains("It has nothing to go on yet"));
        assertEquals(View.GONE, forget.getVisibility());
        activity.finish();
    }

    @Test public void learnedLineAndForgetLight() throws Exception { render(false); }
    @Test @Config(qualifiers = "w411dp-h891dp-night-xxhdpi") public void learnedLineAndForgetDark() throws Exception { render(true); }
}
