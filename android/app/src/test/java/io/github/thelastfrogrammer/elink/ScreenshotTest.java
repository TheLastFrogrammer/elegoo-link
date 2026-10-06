package io.github.thelastfrogrammer.elink;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Opt-in: renders the main screen with sample data to PNGs. Run with -Dscreenshots=/path/to/dir. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 34, qualifiers = "w411dp-h891dp-xxhdpi")
public class ScreenshotTest {
    private static JSONObject printing() throws Exception {
        return new JSONObject()
            .put("machine_status", new JSONObject().put("status", 2).put("sub_status", 2075).put("progress", 41))
            .put("print_status", new JSONObject().put("filename", "Benchy_PLA_0.2mm.gcode").put("current_layer", 87).put("total_layer", 212).put("remaining_time_sec", 4520))
            .put("extruder", new JSONObject().put("temperature", 219.6).put("target", 220))
            .put("heater_bed", new JSONObject().put("temperature", 59.8).put("target", 60))
            .put("ztemperature_sensor", new JSONObject().put("temperature", 31.0));
    }

    /** A simulated two-hour print: heat-up, part fan on after the first layers, chamber slowly warming. */
    @Test public void renderRecording() throws Exception {
        String out = System.getProperty("screenshots", "");
        Assume.assumeFalse("Screenshots are opt-in", out.isEmpty());
        android.content.Context context = org.robolectric.RuntimeEnvironment.getApplication();
        for (String theme : new String[] {"light", "dark"}) {
            context.getSharedPreferences("workshop-settings", 0).edit().putInt("theme", theme.equals("dark") ? 2 : 1).commit();
            File dir = new File(context.getFilesDir(), "recordings-" + theme);
            PrintRecorder recorder = new PrintRecorder(dir);
            long start = 1_760_000_000_000L;
            for (int minute = 0; minute <= 120; minute++) {
                double heat = Math.min(1, minute / 6.0);
                int layer = minute < 6 ? 0 : Math.min(212, (int) ((minute - 6) * 1.9));
                int sub = minute == 120 ? 2077 : 2075;
                JSONObject status = new JSONObject()
                    .put("machine_status", new JSONObject().put("status", 2).put("sub_status", sub).put("progress", minute < 6 ? 0 : Math.min(100, (minute - 6) * 100 / 114)))
                    .put("print_status", new JSONObject().put("filename", "Benchy_PLA_0.2mm.gcode").put("uuid", "u1").put("current_layer", layer).put("total_layer", 212).put("remaining_time_sec", (120 - minute) * 60))
                    .put("extruder", new JSONObject().put("temperature", 25 + 195 * heat + (minute > 6 ? Math.sin(minute) * 0.8 : 0)).put("target", 220))
                    .put("heater_bed", new JSONObject().put("temperature", 25 + 35 * Math.min(1, minute / 4.0)).put("target", 60))
                    .put("ztemperature_sensor", new JSONObject().put("temperature", 26 + 9 * (1 - Math.exp(-minute / 40.0))))
                    .put("fans", new JSONObject().put("fan", new JSONObject().put("speed", minute < 9 ? 0 : 255)).put("aux_fan", new JSONObject().put("speed", minute < 9 ? 0 : 89)).put("box_fan", new JSONObject().put("speed", 51)));
                recorder.update(start + minute * 60_000L, status, minute % 3 == 0 ? "cloud" : "local", "Bedroom");
            }
            PrintRecorder.Recording recording = recorder.list().get(0);
            android.content.Intent intent = new android.content.Intent(context, RecordingsActivity.class).putExtra(RecordingsActivity.EXTRA_META, recording.meta.getAbsolutePath());
            RecordingsActivity activity = Robolectric.buildActivity(RecordingsActivity.class, intent).setup().get();
            View root = activity.getWindow().getDecorView();
            int width = 1080, height = 3300;
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, width, height);
            // Simulate a finger on the temperature chart to show the crosshair readout.
            java.util.List<View> charts = new java.util.ArrayList<>(); collect(root, charts);
            if (charts.size() > 2) {
                View temperature = charts.get(2);
                temperature.onTouchEvent(android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_DOWN, temperature.getWidth() * 0.35f, temperature.getHeight() / 2f, 0));
            }
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            root.draw(new Canvas(bitmap));
            File file = new File(out, "recording-" + theme + ".png"); file.getParentFile().mkdirs();
            try (FileOutputStream stream = new FileOutputStream(file)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream); }
        }
    }
    private static void collect(View view, java.util.List<View> charts) {
        if (view instanceof ChartView) charts.add(view);
        if (view instanceof android.view.ViewGroup) for (int i = 0; i < ((android.view.ViewGroup) view).getChildCount(); i++) collect(((android.view.ViewGroup) view).getChildAt(i), charts);
    }

    @Test public void renderScreens() throws Exception {
        String out = System.getProperty("screenshots", "");
        Assume.assumeFalse("Screenshots are opt-in", out.isEmpty());
        for (String theme : new String[] {"light", "dark"}) {
            android.content.Context context = org.robolectric.RuntimeEnvironment.getApplication();
            context.getSharedPreferences("workshop-settings", 0).edit().putInt("theme", theme.equals("dark") ? 2 : 1).putInt("page", 0).commit();
            PrinterService service = Robolectric.setupService(PrinterService.class);
            org.robolectric.Shadows.shadowOf((android.app.Application) context).setComponentNameAndServiceForBindService(
                new android.content.ComponentName(context, PrinterService.class), service.onBind(null));
            MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
            Field printer = MainActivity.class.getDeclaredField("printer"); printer.setAccessible(true); printer.set(activity, service);
            // Let the service's own first cloud check (no account in this sandbox) finish, then show sample cloud data.
            Thread.sleep(500); org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            service.cloudSignedIn = true; service.cloudSerial = "F01ABC0000R818"; service.cloudName = "Bedroom"; service.cloudModel = "Centauri Carbon 2";
            service.cloudOnline = 1; service.cloudCheckedAt = System.currentTimeMillis() - 4000; service.cloudStatus = printing();
            service.feedback = "Printer acknowledged through the cloud. Waiting for its status to update.";
            Method render = MainActivity.class.getDeclaredMethod("render"); render.setAccessible(true);
            Method page = MainActivity.class.getDeclaredMethod("selectPage", int.class); page.setAccessible(true);
            String[] names = {"monitor", "files", "camera", "settings"};
            for (int i = 0; i < 4; i++) {
                page.invoke(activity, i); render.invoke(activity);
                View root = activity.getWindow().getDecorView();
                int width = 1080, height = i == 3 ? 5200 : 2340;
                root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
                root.layout(0, 0, width, height);
                Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                root.draw(new Canvas(bitmap));
                File file = new File(out, names[i] + "-" + theme + ".png"); file.getParentFile().mkdirs();
                try (FileOutputStream stream = new FileOutputStream(file)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream); }
            }
        }
    }
}
