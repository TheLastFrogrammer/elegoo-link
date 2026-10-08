package io.github.thelastfrogrammer.elink;

import android.app.Activity;
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
import java.lang.reflect.Method;
import java.time.Instant;

/**
 * Opt-in: renders the cloud sign-in, cloud details, cloud camera and print recordings screens to PNGs, light and dark.
 * Run with -Dscreenshots=/path/to/dir. Dark renders use the night qualifier and the app's dark setting.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 34, qualifiers = "w411dp-h891dp-notnight-xxhdpi")
public class MoreScreensRenderTest {
    private static final String DARK_QUALIFIERS = "w411dp-h891dp-night-xxhdpi";
    private static final String LIGHT_QUALIFIERS = "w411dp-h891dp-notnight-xxhdpi";

    private static File out() {
        String dir = System.getProperty("screenshots", "");
        Assume.assumeFalse("Screenshots are opt-in", dir.isEmpty());
        return new File(dir, "more");
    }

    private static void shot(View root, int width, int height, File file) throws Exception {
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(bitmap));
        file.getParentFile().mkdirs();
        try (FileOutputStream stream = new FileOutputStream(file)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream); }
    }

    private static void setTheme(boolean dark) {
        android.content.Context context = org.robolectric.RuntimeEnvironment.getApplication();
        context.getSharedPreferences("workshop-settings", 0).edit().putInt("theme", dark ? 2 : 1).commit();
    }

    private static void show(Activity activity, String name, int height) throws Exception {
        shot(activity.getWindow().getDecorView(), 1080, height, new File(out(), name));
    }

    private static void waitForWorker() throws Exception {
        Thread.sleep(800);
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
    }

    // ---- Sign-in ----

    private static void loginScreens(String theme) throws Exception {
        setTheme(theme.equals("dark"));
        android.content.Intent intent = new android.content.Intent(org.robolectric.RuntimeEnvironment.getApplication(), CloudLoginActivity.class);
        CloudLoginActivity login = Robolectric.buildActivity(CloudLoginActivity.class, intent).setup().get();
        show(login, "login-start-" + theme + ".png", 2340);
        login.failed("Elegoo reported that sign-in failed.");
        show(login, "login-failed-" + theme + ".png", 2340);
    }
    @Test public void renderLoginLight() throws Exception { loginScreens("light"); }
    @Test @Config(qualifiers = DARK_QUALIFIERS) public void renderLoginDark() throws Exception { loginScreens("dark"); }

    // ---- Cloud details ----

    private static void statusScreen(String theme) throws Exception {
        setTheme(theme.equals("dark"));
        CloudStatusActivity status = Robolectric.buildActivity(CloudStatusActivity.class).setup().get();
        show(status, "cloud-status-" + theme + ".png", 2340);
    }
    @Test public void renderCloudStatusLight() throws Exception { statusScreen("light"); }
    @Test @Config(qualifiers = DARK_QUALIFIERS) public void renderCloudStatusDark() throws Exception { statusScreen("dark"); }

    // ---- Cloud camera ----

    private static void cameraScreens(String theme) throws Exception {
        setTheme(theme.equals("dark"));
        android.content.Intent intent = new android.content.Intent(org.robolectric.RuntimeEnvironment.getApplication(), CloudCameraActivity.class)
            .putExtra(CloudCameraActivity.EXTRA_SERIAL, "F01ABC0000R818").putExtra(CloudCameraActivity.EXTRA_NAME, "Bedroom");
        CloudCameraActivity camera = Robolectric.buildActivity(CloudCameraActivity.class, intent).setup().get();
        waitForWorker();
        show(camera, "camera-start-" + theme + ".png", 2340);
        Method handle = CloudCameraActivity.class.getDeclaredMethod("handle", String.class); handle.setAccessible(true);
        handle.invoke(camera, new JSONObject().put("type", "status").put("text", "The printer is not sending video. It may be off, offline, or its camera may be disabled.").toString());
        show(camera, "camera-problem-" + theme + ".png", 2340);
    }
    @Test public void renderCameraLight() throws Exception { cameraScreens("light"); }
    @Test @Config(qualifiers = DARK_QUALIFIERS) public void renderCameraDark() throws Exception { cameraScreens("dark"); }

    // ---- Recordings ----

    /** Simulates one print. endCode 2077 = complete, 2504 = stopped, 0 = the app never saw an end. failAt > 0 lets the heaters fall from that minute. */
    private static void simulate(PrintRecorder recorder, String start, String file, int minutes, int endCode, int failAt) throws Exception {
        long begin = Instant.parse(start).toEpochMilli();
        for (int m = 0; m <= minutes; m++) {
            int sub = m == minutes && endCode != 0 ? endCode : 2075;
            double nozzle = 220, bed = 60;
            if (failAt > 0 && m >= failAt) { nozzle = Math.max(60, 220 - (m - failAt) * 3.5); bed = Math.max(30, 60 - (m - failAt) * 1.2); }
            JSONObject status = new JSONObject()
                .put("machine_status", new JSONObject().put("status", 2).put("sub_status", sub).put("progress", Math.min(100, m * 100 / minutes)))
                .put("print_status", new JSONObject().put("filename", file).put("uuid", file).put("current_layer", m / 2).put("total_layer", minutes / 2).put("remaining_time_sec", (minutes - m) * 60))
                .put("extruder", new JSONObject().put("temperature", nozzle).put("target", 220))
                .put("heater_bed", new JSONObject().put("temperature", bed).put("target", 60))
                .put("ztemperature_sensor", new JSONObject().put("temperature", 30.0))
                .put("fans", new JSONObject().put("fan", new JSONObject().put("speed", 255)).put("aux_fan", new JSONObject().put("speed", 89)));
            recorder.update(begin + m * 60_000L, status, "local", "Bedroom");
        }
    }

    private static void seedRecordings() throws Exception {
        PrintRecorder recorder = new PrintRecorder(RecordingsActivity.directory(org.robolectric.RuntimeEnvironment.getApplication()));
        simulate(recorder, "2026-10-05T19:10:00Z", "Cable_clip_PLA.gcode", 25, 2077, 0);
        simulate(recorder, "2026-10-06T14:05:00Z", "Benchy_PLA_0.2mm.gcode", 150, 2077, 0);
        simulate(recorder, "2026-10-07T22:30:00Z", "Gridfinity_bin_PETG.gcode", 200, 2504, 120);
        simulate(recorder, "2026-10-08T06:50:00Z", "Phone_stand_PLA.gcode", 40, 0, 0);
    }

    private static void recordingsList(String theme) throws Exception {
        setTheme(theme.equals("dark"));
        seedRecordings();
        RecordingsActivity list = Robolectric.buildActivity(RecordingsActivity.class).setup().get();
        show(list, "recordings-list-" + theme + ".png", 2340);
    }
    @Test public void renderRecordingsLight() throws Exception { recordingsList("light"); }
    @Test @Config(qualifiers = DARK_QUALIFIERS) public void renderRecordingsDark() throws Exception { recordingsList("dark"); }

    private static void nightRecording(String theme) throws Exception {
        setTheme(theme.equals("dark"));
        seedRecordings();
        PrintRecorder recorder = new PrintRecorder(RecordingsActivity.directory(org.robolectric.RuntimeEnvironment.getApplication()));
        PrintRecorder.Recording night = null;
        for (PrintRecorder.Recording r : recorder.list()) if (r.file.startsWith("Gridfinity")) night = r;
        android.content.Intent intent = new android.content.Intent(org.robolectric.RuntimeEnvironment.getApplication(), RecordingsActivity.class)
            .putExtra(RecordingsActivity.EXTRA_META, night.meta.getAbsolutePath());
        RecordingsActivity detail = Robolectric.buildActivity(RecordingsActivity.class, intent).setup().get();
        shot(detail.getWindow().getDecorView(), 1080, 3600, new File(out(), "recording-night-" + theme + ".png"));
    }
    @Test public void renderNightRecordingLight() throws Exception { nightRecording("light"); }
    @Test @Config(qualifiers = DARK_QUALIFIERS) public void renderNightRecordingDark() throws Exception { nightRecording("dark"); }
}
