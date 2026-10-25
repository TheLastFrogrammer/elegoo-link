package io.github.thelastfrogrammer.elink;

import static org.junit.Assert.assertTrue;

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
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
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
    private static void automationScreen(String theme) throws Exception {
        setTheme(theme.equals("dark"));
        AutomationRun.rulesFile(org.robolectric.RuntimeEnvironment.getApplication()).delete();
        AutomationActivity workshop = Robolectric.buildActivity(AutomationActivity.class).setup().get();
        show(workshop, "automation-" + theme + ".png", 2600);
    }
    @Test public void renderPrintChart() throws Exception {
        java.util.List<double[]> rows = new java.util.ArrayList<>();
        for (int i = 0; i <= 60; i++) {
            double[] r = new double[PrintRecorder.COLUMNS.length]; java.util.Arrays.fill(r, Double.NaN);
            java.util.List<String> c = java.util.Arrays.asList(PrintRecorder.COLUMNS);
            r[c.indexOf("elapsed_s")] = i * 60; r[c.indexOf("progress")] = i * 100 / 60.0;
            r[c.indexOf("nozzle_c")] = Math.min(220, 25 + i * 40); r[c.indexOf("nozzle_target_c")] = 220;
            r[c.indexOf("bed_c")] = Math.min(60, 25 + i * 8); r[c.indexOf("bed_target_c")] = 60; r[c.indexOf("chamber_c")] = 25 + 15 * (1 - Math.exp(-i / 15.0));
            rows.add(r);
        }
        android.graphics.Bitmap chart = PrintChart.bitmap(org.robolectric.RuntimeEnvironment.getApplication(), null, rows);
        File file = new File(out(), "automation-chart.png"); file.getParentFile().mkdirs();
        try (FileOutputStream stream = new FileOutputStream(file)) { chart.compress(Bitmap.CompressFormat.PNG, 100, stream); }
    }
    @Test public void renderAutomationLight() throws Exception { automationScreen("light"); }
    @Test @Config(qualifiers = DARK_QUALIFIERS) public void renderAutomationDark() throws Exception { automationScreen("dark"); }
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
        // A few camera pictures, so the charts carry their ticks and the preview panel shows.
        night.pictureFolder().mkdirs();
        for (int i = 0; i < 6; i++) {
            android.graphics.Bitmap frame = android.graphics.Bitmap.createBitmap(320, 240, android.graphics.Bitmap.Config.ARGB_8888);
            new android.graphics.Canvas(frame).drawColor(0xff304048 + i * 0x00080808);
            try (java.io.OutputStream out = new java.io.FileOutputStream(new File(night.pictureFolder(), String.format(java.util.Locale.ROOT, "%06d.jpg", 600 + i * 1800)))) { frame.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out); }
        }
        android.content.Intent intent = new android.content.Intent(org.robolectric.RuntimeEnvironment.getApplication(), RecordingsActivity.class)
            .putExtra(RecordingsActivity.EXTRA_META, night.meta.getAbsolutePath());
        RecordingsActivity detail = Robolectric.buildActivity(RecordingsActivity.class, intent).setup().get();
        shot(detail.getWindow().getDecorView(), 1080, 4400, new File(out(), "recording-night-" + theme + ".png"));
    }
    /** The storage page and the maintenance reminders, over the seeded recordings. */
    @Test public void renderStorageAndMaintenance() throws Exception {
        setTheme(false);
        seedRecordings();
        for (Class<? extends android.app.Activity> page : java.util.Arrays.asList(StorageActivity.class, MaintenanceActivity.class)) {
            android.app.Activity shown = Robolectric.buildActivity(page).setup().get();
            for (int i = 0; i < 200 && ((android.view.ViewGroup) ((android.view.ViewGroup) shown.findViewById(android.R.id.content)).getChildAt(0)).getChildCount() > 0
                && ((android.view.ViewGroup) ((android.widget.ScrollView) ((android.view.ViewGroup) shown.findViewById(android.R.id.content)).getChildAt(0)).getChildAt(0)).getChildCount() < 3; i++) {
                Thread.sleep(20); org.robolectric.shadows.ShadowLooper.idleMainLooper();
            }
            shot(shown.getWindow().getDecorView(), 1080, 2400, new File(out(), page.getSimpleName().toLowerCase(java.util.Locale.ROOT) + "-light.png"));
        }
    }

    /** Two prints side by side, layer by layer. */
    @Test public void renderCompare() throws Exception {
        setTheme(false);
        seedRecordings();
        PrintRecorder recorder = new PrintRecorder(RecordingsActivity.directory(org.robolectric.RuntimeEnvironment.getApplication()));
        java.util.List<PrintRecorder.Recording> all = recorder.list();
        android.content.Intent intent = new android.content.Intent(org.robolectric.RuntimeEnvironment.getApplication(), CompareActivity.class)
            .putExtra(CompareActivity.EXTRA_A, all.get(1).meta.getAbsolutePath()).putExtra(CompareActivity.EXTRA_B, all.get(2).meta.getAbsolutePath());
        CompareActivity compare = Robolectric.buildActivity(CompareActivity.class, intent).setup().get();
        shot(compare.getWindow().getDecorView(), 1080, 3000, new File(out(), "compare-light.png"));
    }
    /** A print sliced again with tuning changes: what changed, whether the finding came back, and the print before. */
    @Test public void renderTuning() throws Exception {
        setTheme(false);
        seedRecordings();
        android.content.Context context = org.robolectric.RuntimeEnvironment.getApplication();
        java.util.List<PrintRecorder.Recording> all = new PrintRecorder(RecordingsActivity.directory(context)).list();
        PrintRecorder.Recording after = all.get(0), before = null;
        for (PrintRecorder.Recording r : all) if (r.start < after.start) { before = r; break; }
        org.junit.Assume.assumeNotNull(before);
        java.util.Map<String, String> config = new java.util.HashMap<>(); config.put("fan_max_speed", "100"); config.put("nozzle_temperature", "210");
        Tuning.Trial trial = Tuning.parse(Tuning.request(GcodeLibrary.baseName(after.file), before, CompareActivity.name(before),
            Tuning.suggest(java.util.Collections.singletonList(new RecordingAnalysis.Finding(RecordingAnalysis.Finding.COOLING, 68, "")), config),
            java.util.Collections.singletonList(RecordingAnalysis.Finding.COOLING)));
        trial.at = after.start - 1000; Tuning.remember(context, trial);
        android.content.Intent intent = new android.content.Intent(context, RecordingsActivity.class).putExtra(RecordingsActivity.EXTRA_META, after.meta.getAbsolutePath());
        RecordingsActivity detail = Robolectric.buildActivity(RecordingsActivity.class, intent).setup().get();
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
        org.junit.Assert.assertFalse("the result is kept with the try", Tuning.trialFor(Tuning.all(context), after).result.isEmpty());
        shot(detail.getWindow().getDecorView(), 1080, 2600, new File(out(), "tuning-light.png"));
    }
    /** Every print of one file: what worked, each print's spool, drying and what changed. */
    @Test public void renderFileMemory() throws Exception {
        setTheme(false);
        android.content.Context context = org.robolectric.RuntimeEnvironment.getApplication();
        PrintRecorder recorder = new PrintRecorder(RecordingsActivity.directory(context));
        simulate(recorder, "2026-10-10T18:00:00Z", "Desk_mount_PLA.gcode", 30, 2077, 0);
        simulate(recorder, "2026-10-14T18:00:00Z", "Desk_mount_PLA.gcode", 30, 2077, 0);
        simulate(recorder, "2026-10-20T18:00:00Z", "Desk_mount_PLA.gcode", 30, 2077, 0);
        java.util.List<PrintRecorder.Recording> mine = FileMemory.of(recorder.list(), "Desk_mount_PLA.gcode");
        String fixture = new String(java.nio.file.Files.readAllBytes(new File("src/test/resources/gcode/tolerance-cc2.gcode").toPath()), java.nio.charset.StandardCharsets.UTF_8);
        String[][] made = {{"100", "210", "failed", "1", "surface"}, {"70", "215", "good", "5", ""}, {"100", "210", "usable", "3", "stringing"}};   // oldest first
        for (int i = 0; i < 3; i++) {
            PrintRecorder.Recording r = mine.get(2 - i);
            File g = new File(context.getCacheDir(), "mount-" + i + ".gcode");
            java.nio.file.Files.write(g.toPath(), fixture.replace("; fan_max_speed = 100", "; fan_max_speed = " + made[i][0]).replace("; nozzle_temperature = 210", "; nozzle_temperature = " + made[i][1]).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            RecordingGcode.pack(g, "Desk_mount_PLA.gcode", r, RecordingGcode.CHOSEN);
            PrintJournal j = new PrintJournal(); j.result = made[i][2]; j.stars = Integer.parseInt(made[i][3]); if (!made[i][4].isEmpty()) j.reasons.add(made[i][4]);
            PrintRecorder.saveJournal(r.meta, j);
            PrintRecorder.saveSpools(r.meta, new org.json.JSONArray().put(new org.json.JSONObject().put("slot", "0:0").put("material", "PLA · black").put("dried", 0).put("opened", r.start - (40 - i * 3) * 86_400_000L)));
        }
        android.content.Intent intent = new android.content.Intent(context, FileMemoryActivity.class).putExtra(FileMemoryActivity.EXTRA_FILE, "Desk_mount_PLA.gcode");
        FileMemoryActivity page = Robolectric.buildActivity(FileMemoryActivity.class, intent).setup().get();
        for (int i = 0; i < 300 && ((android.view.ViewGroup) ((android.widget.ScrollView) ((android.view.ViewGroup) page.findViewById(android.R.id.content)).getChildAt(0)).getChildAt(0)).getChildCount() < 4; i++) {
            Thread.sleep(20); org.robolectric.shadows.ShadowLooper.idleMainLooper();
        }
        shot(page.getWindow().getDecorView(), 1080, 2800, new File(out(), "file-memory-light.png"));
    }
    /** Settings › Other apps: the local API running, a device asking to pair, one paired, and the relay set up. */
    @Test public void renderIntegrations() throws Exception {
        setTheme(false);
        android.content.Context context = org.robolectric.RuntimeEnvironment.getApplication();
        Integrations.prefs(context).edit().putBoolean(Integrations.LOCAL, true).putInt(Integrations.PORT, 0).putInt(Integrations.RELAY_PICTURES, 5).commit();
        // (The relay's address and secret need Android's key store, which tests do not have: the card shows them unset.)
        PrinterService service = Robolectric.setupService(PrinterService.class);
        try {
            ((org.robolectric.shadows.ShadowApplication) org.robolectric.shadow.api.Shadow.extract(context)).setComponentNameAndServiceForBindService(
                new android.content.ComponentName(context, PrinterService.class), service.onBind(null));
            assertTrue("the local API runs when turned on", service.integrations.running());
            service.integrations.auth.add("Living room TV", System.currentTimeMillis() - 3_600_000L);
            service.integrations.auth.request("Fire TV", System.currentTimeMillis());
            IntegrationsActivity page = Robolectric.buildActivity(IntegrationsActivity.class).setup().get();
            org.robolectric.shadows.ShadowLooper.idleMainLooper();
            shot(page.getWindow().getDecorView(), 1080, 3000, new File(out(), "integrations-light.png"));
        } finally { service.onDestroy(); }
    }
    @Test public void renderNightRecordingLight() throws Exception { nightRecording("light"); }
    @Test @Config(qualifiers = DARK_QUALIFIERS) public void renderNightRecordingDark() throws Exception { nightRecording("dark"); }
}
