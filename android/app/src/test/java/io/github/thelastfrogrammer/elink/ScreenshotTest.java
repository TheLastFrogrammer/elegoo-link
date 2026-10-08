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
            service.canvas = new org.json.JSONObject("{\"auto_refill\":true,\"active_canvas_id\":0,\"active_tray_id\":1,\"canvas_list\":[{\"canvas_id\":0,\"connected\":1,\"tray_list\":["
                + "{\"tray_id\":0,\"filament_type\":\"PLA\",\"filament_name\":\"PLA Matte\",\"filament_color\":\"#D02828\",\"min_nozzle_temp\":190,\"max_nozzle_temp\":230},"
                + "{\"tray_id\":1,\"filament_type\":\"PLA\",\"filament_name\":\"PLA\",\"filament_color\":\"#F0F0F0\",\"min_nozzle_temp\":190,\"max_nozzle_temp\":230},"
                + "{\"tray_id\":2,\"filament_type\":\"PETG\",\"filament_color\":\"#1E5AA8\",\"min_nozzle_temp\":230,\"max_nozzle_temp\":260},"
                + "{\"tray_id\":3,\"filament_type\":\"\",\"filament_color\":\"\"}]}]}");
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

    private static JSONObject idle() throws Exception {
        return new JSONObject().put("machine_status", new JSONObject().put("status", 1).put("sub_status", 0).put("progress", 0))
            .put("extruder", new JSONObject().put("temperature", 24.5).put("target", 0)).put("heater_bed", new JSONObject().put("temperature", 23.9).put("target", 0))
            .put("ztemperature_sensor", new JSONObject().put("temperature", 24.0));
    }

    /** The main screen in each connection state, every tab at full height: -Dscreenshots=DIR writes state-<name>-<tab>-<theme>.png. */
    @Test public void renderStates() throws Exception {
        String out = System.getProperty("screenshots", "");
        Assume.assumeFalse("Screenshots are opt-in", out.isEmpty());
        String[] states = {"offline", "cloud-agree", "cloud-printing", "cloud-idle", "local-printing", "local-idle"};
        String[] tabs = {"monitor", "files", "camera", "settings"};
        for (String theme : new String[] {"light", "dark"}) for (String state : states) {
            MainActivity activity = prepare(state, theme);
            Method render = MainActivity.class.getDeclaredMethod("render"); render.setAccessible(true);
            Method page = MainActivity.class.getDeclaredMethod("selectPage", int.class); page.setAccessible(true);
            Field pagesField = MainActivity.class.getDeclaredField("pages"); pagesField.setAccessible(true);
            for (int i = 0; i < 4; i++) {
                page.invoke(activity, i); render.invoke(activity);
                View root = activity.getWindow().getDecorView();
                int width = 1080, height = 7000;
                root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
                root.layout(0, 0, width, height);
                View shown = ((android.widget.LinearLayout[]) pagesField.get(activity))[i];
                height = Math.min(7000, ((View) shown.getParent()).getTop() + shown.getBottom() + 600);
                root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
                root.layout(0, 0, width, height);
                Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                root.draw(new Canvas(bitmap));
                File file = new File(out, "state-" + state + "-" + tabs[i] + "-" + theme + ".png"); file.getParentFile().mkdirs();
                try (FileOutputStream stream = new FileOutputStream(file)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream); }
            }
            activity.finish();
        }
    }

    /** A MainActivity in one of the sample connection states with sample files, trays and history. */
    private static MainActivity prepare(String state, String theme) throws Exception {
        android.content.Context context = org.robolectric.RuntimeEnvironment.getApplication();
        context.getSharedPreferences("workshop-settings", 0).edit().clear().putInt("theme", theme.equals("dark") ? 2 : 1).putInt("page", 0)
            .putBoolean("cloudControlUnderstood", !state.equals("cloud-agree") && !state.equals("offline")).commit();
        PrinterService service = Robolectric.setupService(PrinterService.class);
        org.robolectric.Shadows.shadowOf((android.app.Application) context).setComponentNameAndServiceForBindService(
            new android.content.ComponentName(context, PrinterService.class), service.onBind(null));
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        Field printer = MainActivity.class.getDeclaredField("printer"); printer.setAccessible(true); printer.set(activity, service);
        Thread.sleep(500); org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        boolean cloud = state.startsWith("cloud"), local = state.startsWith("local"), busy = state.endsWith("printing");
        JSONObject status = busy ? printing() : idle();
        if (cloud) {
            service.cloudSignedIn = true; service.cloudSerial = "F01ABC0000R818"; service.cloudName = "Bedroom"; service.cloudModel = "Centauri Carbon 2";
            service.cloudOnline = 1; service.cloudCheckedAt = System.currentTimeMillis() - 4000; service.cloudStatus = status;
        }
        if (local) {
            Field wanted = PrinterService.class.getDeclaredField("wanted"); wanted.setAccessible(true); wanted.setBoolean(service, true);
            Cc2Session session = new Cc2Session("192.168.1.50", "123456", null);
            Field ready = Cc2Session.class.getDeclaredField("ready"); ready.setAccessible(true); ready.setBoolean(session, true);
            Field at = Cc2Session.class.getDeclaredField("statusAt"); at.setAccessible(true); at.setLong(session, System.nanoTime());
            Field sessionField = PrinterService.class.getDeclaredField("session"); sessionField.setAccessible(true); sessionField.set(service, session);
            service.status = status; service.connection = "Connected to 192.168.1.50";
            service.attributes = new JSONObject().put("hostname", "Bedroom").put("machine_model", "Centauri Carbon 2").put("software_version", new JSONObject().put("ota_version", "01.03.02.15"));
        }
        if (local || cloud && !state.equals("cloud-agree")) {
            service.filePage = new JSONObject("{\"total\":3,\"file_list\":[{\"filename\":\"Benchy_PLA_0.2mm.gcode\",\"size\":4823044,\"layer\":212},{\"filename\":\"Calibration cube.gcode\",\"size\":912331,\"layer\":150},{\"filename\":\"Phone stand v3.gcode\",\"size\":2433102,\"layer\":340}]}");
            service.filesAt = System.nanoTime(); service.fileMessage = "Files received from printer.";
            service.disk = new JSONObject().put("used_bytes", 3_200_000_000L).put("total_bytes", 8_000_000_000L);
            service.history = new JSONObject("{\"history_task_list\":[{\"task_name\":\"Benchy.gcode\",\"task_status\":1,\"begin_time\":1,\"end_time\":9000,\"time_lapse_video_status\":2}]}");
        }
        if (cloud || local) service.canvas = new JSONObject("{\"auto_refill\":true,\"active_canvas_id\":0,\"active_tray_id\":1,\"canvas_list\":[{\"canvas_id\":0,\"connected\":1,\"tray_list\":["
            + "{\"tray_id\":0,\"filament_type\":\"PLA\",\"filament_name\":\"PLA Matte\",\"filament_color\":\"#D02828\",\"min_nozzle_temp\":190,\"max_nozzle_temp\":230},"
            + "{\"tray_id\":1,\"filament_type\":\"PLA\",\"filament_name\":\"PLA\",\"filament_color\":\"#F0F0F0\",\"min_nozzle_temp\":190,\"max_nozzle_temp\":230}]}]}");
        Field canvasAt = PrinterService.class.getDeclaredField("canvasAt"); canvasAt.setAccessible(true); canvasAt.setLong(service, System.nanoTime());
        return activity;
    }

    private static void draw(View root, int width, int height, File file) throws Exception {
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(bitmap)); file.getParentFile().mkdirs();
        try (FileOutputStream stream = new FileOutputStream(file)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream); }
    }

    /** The printer-file dialog and Print setup, local idle, cloud idle and not usable (printing): -Dscreenshots=DIR writes dialog-<state>-<file|setup>-<theme>.png. */
    @Test public void renderDialogs() throws Exception {
        String out = System.getProperty("screenshots", "");
        Assume.assumeFalse("Screenshots are opt-in", out.isEmpty());
        for (String theme : new String[] {"light", "dark"}) for (String state : new String[] {"local-idle", "cloud-idle", "local-printing"}) {
            android.content.Context context = org.robolectric.RuntimeEnvironment.getApplication();
            MainActivity activity = prepare(state, theme);
            // Calibration cube carries a stored two-tool plan from the Slice screen; Benchy has none.
            context.getSharedPreferences(SliceActivity.TRAY_PLANS, 0).edit().putString("Calibration cube.gcode", new TrayPlan(2, java.util.Arrays.asList(new TrayPlan.Tool(0, 0, 1), new TrayPlan.Tool(1, 0, 0))).toJson()).commit();
            Method render = MainActivity.class.getDeclaredMethod("render"); render.setAccessible(true); render.invoke(activity);
            Method actions = MainActivity.class.getDeclaredMethod("fileActions", JSONObject.class, String.class); actions.setAccessible(true);
            Method setup = MainActivity.class.getDeclaredMethod("startDialog", JSONObject.class, String.class); setup.setAccessible(true);
            Field printer = MainActivity.class.getDeclaredField("printer"); printer.setAccessible(true); PrinterService service = (PrinterService) printer.get(activity);
            JSONObject file = service.filePage.getJSONArray("file_list").getJSONObject(1);
            actions.invoke(activity, file, "local");
            android.app.AlertDialog dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
            draw(dialog.getWindow().getDecorView(), 1080, 2200, new File(out, "dialog-" + state + "-file-" + theme + ".png"));
            dialog.dismiss();
            setup.invoke(activity, file, "local");
            dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
            if (dialog != null && dialog.isShowing()) draw(dialog.getWindow().getDecorView(), 1080, 4600, new File(out, "dialog-" + state + "-setup-" + theme + ".png"));
            activity.finish();
        }
    }

    /** The toolpath viewer's controls with the Benchy-sized fixture; the WebGL area (blank here) is recorded in viewer-<theme>.json. */
    @Test public void renderViewer() throws Exception {
        String out = System.getProperty("screenshots", "");
        Assume.assumeFalse("Screenshots are opt-in", out.isEmpty());
        android.content.Context context = org.robolectric.RuntimeEnvironment.getApplication();
        File gcode = new File(context.getCacheDir(), "Benchy_PLA_0.2mm.gcode");
        try (java.io.InputStream in = getClass().getResourceAsStream("/gcode/tolerance-cc2.gcode")) { java.nio.file.Files.copy(in, gcode.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
        for (String theme : new String[] {"light", "dark"}) {
            context.getSharedPreferences("workshop-settings", 0).edit().putInt("theme", theme.equals("dark") ? 2 : 1).commit();
            android.content.Intent intent = new android.content.Intent(context, GcodeViewerActivity.class)
                .putExtra(GcodeViewerActivity.EXTRA_FILE, gcode.getAbsolutePath()).putExtra(GcodeViewerActivity.EXTRA_NAME, "ElegooToleranceTest.gcode");
            GcodeViewerActivity activity = Robolectric.buildActivity(GcodeViewerActivity.class, intent).setup().get();
            long deadline = System.currentTimeMillis() + 30_000;
            java.lang.reflect.Field pathField = GcodeViewerActivity.class.getDeclaredField("path"); pathField.setAccessible(true);
            while (pathField.get(activity) == null && System.currentTimeMillis() < deadline) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); Thread.sleep(20); }
            java.lang.reflect.Method enable = GcodeViewerActivity.class.getDeclaredMethod("setControlsEnabled", boolean.class); enable.setAccessible(true); enable.invoke(activity, true);
            // Scrub to the middle of layer 20, as a user would.
            java.lang.reflect.Field layer = GcodeViewerActivity.class.getDeclaredField("layer"), move = GcodeViewerActivity.class.getDeclaredField("move");
            layer.setAccessible(true); move.setAccessible(true); layer.setInt(activity, 19);
            GcodeToolpath path = (GcodeToolpath) pathField.get(activity); move.setInt(activity, (path.layerEnd(19) - path.layerStart(19)) / 2);
            java.lang.reflect.Method sync = GcodeViewerActivity.class.getDeclaredMethod("syncBars"); sync.setAccessible(true); sync.invoke(activity);
            View root = activity.getWindow().getDecorView();
            int width = 1080, height = 2340;
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, width, height);
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            root.draw(new Canvas(bitmap));
            File file = new File(out, "viewer-" + theme + ".png"); file.getParentFile().mkdirs();
            try (FileOutputStream stream = new FileOutputStream(file)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream); }
            java.lang.reflect.Field webField = GcodeViewerActivity.class.getDeclaredField("web"); webField.setAccessible(true);
            View web = (View) webField.get(activity); int[] at = new int[2]; web.getLocationInWindow(at);
            java.nio.file.Files.write(new File(out, "viewer-" + theme + ".json").toPath(), new JSONObject().put("x", at[0]).put("y", at[1]).put("w", web.getWidth()).put("h", web.getHeight())
                .put("layerStart", path.layerStart(19)).put("layerEnd", path.layerEnd(19)).put("move", move.getInt(activity)).toString().getBytes("UTF-8"));
        }
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }

    private static void snapshot(android.app.Activity activity, File file) throws Exception {
        View root = activity.getWindow().getDecorView(); int width = 1080, height = 2340;
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888); root.draw(new Canvas(bitmap));
        file.getParentFile().mkdirs();
        try (FileOutputStream stream = new FileOutputStream(file)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream); }
    }

    /** The plate screen's native panel (the page above it is blank here): one model selected, then two with a problem. */
    @Test public void renderPlatePanel() throws Exception {
        String out = System.getProperty("screenshots", "");
        Assume.assumeFalse("Screenshots are opt-in", out.isEmpty());
        android.content.Context context = org.robolectric.RuntimeEnvironment.getApplication();
        JSONObject inspected = new JSONObject().put("files", new org.json.JSONArray().put(new JSONObject().put("objects", new org.json.JSONArray()
            .put(new JSONObject().put("name", "3DBenchy")).put(new JSONObject().put("name", "elegoo_cube")))));
        for (String theme : new String[] {"light", "dark"}) {
            context.getSharedPreferences("workshop-settings", 0).edit().putInt("theme", theme.equals("dark") ? 2 : 1).commit();
            android.content.Intent intent = new android.content.Intent(context, PlateActivity.class)
                .putExtra(PlateActivity.EXTRA_MODELS, new String[] {"/none/model.3mf"}).putExtra(PlateActivity.EXTRA_SELECTION, "{}").putExtra(PlateActivity.EXTRA_INSPECTED, inspected.toString());
            PlateActivity activity = Robolectric.buildActivity(PlateActivity.class, intent).setup().get();
            org.json.JSONArray placements = new org.json.JSONArray()
                .put(new JSONObject().put("file", 0).put("object", 0).put("x", 120.5).put("y", 98.0).put("rotation", 45).put("scale", 1))
                .put(new JSONObject().put("file", 0).put("object", 1).put("x", 130.0).put("y", 100.0).put("rotation", 0).put("scale", 1));
            setField(activity, "placements", placements); setField(activity, "selected", 0);
            Method shown = PlateActivity.class.getDeclaredMethod("showSelected"); shown.setAccessible(true); shown.invoke(activity);
            Method buttons = PlateActivity.class.getDeclaredMethod("setButtons"); buttons.setAccessible(true); buttons.invoke(activity);
            Field status = PlateActivity.class.getDeclaredField("status"); status.setAccessible(true);
            ((android.widget.TextView) status.get(activity)).setText("3DBenchy - Overlapping another model: drag them apart or tap Arrange\nelegoo_cube - Overlapping another model: drag them apart or tap Arrange");
            snapshot(activity, new File(out, "plate-panel-" + theme + ".png"));
        }
    }

    /** Writes the viewer page's data files (meta.json, segments.bin, travels.bin) for the Playwright harness. */
    @Test public void dumpViewerData() throws Exception {
        String out = System.getProperty("screenshots", "");
        Assume.assumeFalse("Screenshots are opt-in", out.isEmpty());
        File gcode = new File(org.robolectric.RuntimeEnvironment.getApplication().getCacheDir(), "dump.gcode");
        try (java.io.InputStream in = getClass().getResourceAsStream("/gcode/tolerance-cc2.gcode")) { java.nio.file.Files.copy(in, gcode.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
        // Some infill relabelled as support and prime tower, so the harness can show them.
        String text = new String(java.nio.file.Files.readAllBytes(gcode.toPath()), "UTF-8");
        int n = 0; StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n", -1)) { if (line.equals(";TYPE:Sparse infill")) line = n++ % 3 == 0 ? ";TYPE:Support" : n % 3 == 1 ? ";TYPE:Prime tower" : line; sb.append(line).append('\n'); }
        java.nio.file.Files.write(gcode.toPath(), sb.toString().getBytes("UTF-8"));
        GcodeToolpath path = GcodeToolpath.read(gcode);
        File dir = new File(out, "viewer-data/data"); dir.mkdirs();
        java.nio.file.Files.write(new File(dir, "meta.json").toPath(), path.layersJson().getBytes("UTF-8"));
        java.nio.file.Files.write(new File(dir, "segments.bin").toPath(), path.segmentsBinary());
        java.nio.file.Files.write(new File(dir, "travels.bin").toPath(), path.travelsBinary());
    }
}
