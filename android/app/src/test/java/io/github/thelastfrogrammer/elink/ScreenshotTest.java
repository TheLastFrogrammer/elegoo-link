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
