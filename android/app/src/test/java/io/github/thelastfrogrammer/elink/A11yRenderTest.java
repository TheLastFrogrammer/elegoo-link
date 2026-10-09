package io.github.thelastfrogrammer.elink;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.text.Layout;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Opt-in accessibility audit renders: key screens at Android font scale 1.0, 1.3 and 2.0, as PNGs plus a text audit
 * (touch targets under 48dp, clipped or ellipsized text, controls with no accessible name) per screen.
 * Run with -Dscreenshots=DIR (writes DIR/a11y-*.png and DIR/a11y-*.txt); the Slice screens also need -DslicerLib=....
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 34, qualifiers = "w411dp-h891dp-notnight-xxhdpi")
public class A11yRenderTest {
    private static final float[] SCALES = {1.0f, 1.3f, 2.0f};
    private static final String PRINTER = "Elegoo Centauri Carbon 2 0.4 nozzle", PROCESS = "0.20mm Standard @Elegoo CC2 0.4 nozzle", PLA = "Elegoo PLA @ECC2";

    private static File out() {
        String dir = System.getProperty("screenshots", "");
        Assume.assumeFalse("Screenshots are opt-in", dir.isEmpty());
        return new File(dir);
    }
    private static android.content.Context context() { return RuntimeEnvironment.getApplication(); }
    private static void theme(boolean dark) { context().getSharedPreferences("workshop-settings", 0).edit().clear().putInt("theme", dark ? 2 : 1).putInt("page", 0).commit(); }
    private static String tag(float scale) { return "fs" + (int) (scale * 100); }

    // ------------------------------------------------------------------ rendering and audit

    private static void shot(View root, int height, String name, float scale) throws Exception {
        int width = 1080;
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(bitmap));
        File file = new File(out(), "a11y-" + name + "-" + tag(scale) + ".png"); file.getParentFile().mkdirs();
        try (FileOutputStream stream = new FileOutputStream(file)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream); }
        audit(root, name, scale);
    }

    private static boolean shown(View view) {
        for (View v = view; ; ) {
            if (v.getVisibility() != View.VISIBLE) return false;
            if (!(v.getParent() instanceof View)) return true;
            v = (View) v.getParent();
        }
    }
    private static void walk(View view, List<View> all) {
        all.add(view);
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) walk(((ViewGroup) view).getChildAt(i), all);
    }
    private static String describe(View v) {
        StringBuilder s = new StringBuilder(v.getClass().getSimpleName());
        if (v instanceof TextView && ((TextView) v).getText().length() > 0) s.append(" text=\"").append(((TextView) v).getText().toString().replace('\n', ' ')).append('"');
        if (v instanceof TextView && ((TextView) v).getHint() != null) s.append(" hint=\"").append(((TextView) v).getHint()).append('"');
        if (v.getContentDescription() != null) s.append(" desc=\"").append(v.getContentDescription()).append('"');
        return s.toString();
    }

    /** Writes a-11y-<name>-<scale>.txt: small touch targets, text that does not fit, unnamed controls. */
    private static void audit(View root, String name, float scale) throws Exception {
        float density = context().getResources().getDisplayMetrics().density;
        List<View> all = new ArrayList<>(); walk(root, all);
        StringBuilder log = new StringBuilder("# " + name + " " + tag(scale) + " density=" + density + " fontScale=" + context().getResources().getConfiguration().fontScale + "\n");
        for (View v : all) {
            if (!shown(v) || v.getWidth() == 0 || v.getHeight() == 0) continue;
            boolean control = v.isClickable() || v.isLongClickable() || v instanceof CompoundButton || v instanceof SeekBar || v instanceof Spinner || v instanceof EditText;
            float w = v.getWidth() / density, h = v.getHeight() / density;
            if (control && (h < 48 || w < 48) && !(v instanceof ScrollView)) log.append(String.format(Locale.ROOT, "SMALL-TARGET %.0fx%.0fdp %s\n", w, h, describe(v)));
            if (control) {
                // What a screen reader gets: the real node, with the name from description, text or a label pointing at it.
                android.view.accessibility.AccessibilityNodeInfo info = v.createAccessibilityNodeInfo();
                boolean labelled = false; for (View other : all) if (v.getId() != View.NO_ID && other.getLabelFor() == v.getId()) labelled = true; // a view pointing at this one as its label
                boolean named = info.getContentDescription() != null && info.getContentDescription().length() > 0 || info.getText() != null && info.getText().length() > 0
                    || labelled || v instanceof ViewGroup && hasText((ViewGroup) v);
                if (v instanceof CompoundButton && ((CompoundButton) v).getText().length() == 0 && !(info.getContentDescription() != null && info.getContentDescription().length() > 0) && !labelled) named = false;
                if (v instanceof EditText && !labelled && (info.getContentDescription() == null)) { CharSequence hint = ((EditText) v).getHint(); if (hint == null || hint.length() == 0) named = false; }
                if (!named) log.append(String.format(Locale.ROOT, "UNNAMED %.0fx%.0fdp %s\n", w, h, describe(v)));
                if (v instanceof Spinner && (info.getContentDescription() == null || info.getContentDescription().toString().indexOf(',') < 0) && !labelled) log.append("SPINNER-NO-OWN-LABEL " + describe(v) + " selected=" + ((Spinner) v).getSelectedItem() + "\n");
                if (v instanceof EditText && !labelled && info.getContentDescription() == null) log.append("INPUT-NAMED-ONLY-BY-HINT hint=\"" + ((EditText) v).getHint() + "\"\n");
                if (v instanceof SeekBar && (info.getStateDescription() == null)) log.append(String.format(Locale.ROOT, "SEEKBAR-NO-STATE-DESC progress=%d/%d\n", ((SeekBar) v).getProgress(), ((SeekBar) v).getMax()));
                info.recycle();
            }
            if (v instanceof TextView && !(v instanceof EditText)) {
                TextView t = (TextView) v; Layout layout = t.getLayout();
                if (layout == null || t.getText().length() == 0) continue;
                int need = layout.getHeight() + t.getCompoundPaddingTop() + t.getCompoundPaddingBottom(), have = t.getHeight();
                boolean ellipsized = false; for (int i = 0; i < layout.getLineCount(); i++) if (layout.getEllipsisCount(i) > 0) ellipsized = true;
                float sp = t.getTextSize() / (density * context().getResources().getConfiguration().fontScale);
                if (need > have + 1) log.append(String.format(Locale.ROOT, "CLIPPED-HEIGHT need %.0fdp have %.0fdp (%.1fsp) %s\n", need / density, have / density, sp, describe(v)));
                if (ellipsized) log.append(String.format(Locale.ROOT, "ELLIPSIZED lines=%d maxLines=%d (%.1fsp) %s\n", layout.getLineCount(), t.getMaxLines(), sp, describe(v)));
                if (t.getMaxLines() < Integer.MAX_VALUE && layout.getLineCount() >= t.getMaxLines() && t.getEllipsize() == null && layout.getLineCount() > 1 && layout.getLineEnd(layout.getLineCount() - 1) < t.getText().length())
                    log.append("TRUNCATED-BY-MAXLINES " + describe(v) + "\n");
                if (t.isSingleLine() && layout.getLineWidth(0) > t.getWidth() - t.getCompoundPaddingLeft() - t.getCompoundPaddingRight() + 1) log.append("SINGLELINE-OVERFLOW " + describe(v) + "\n");
                if (t.getHeight() > 0 && t.getLayoutParams() != null && t.getLayoutParams().height > 0 && need > t.getLayoutParams().height) log.append("FIXED-HEIGHT-TEXT " + describe(v) + "\n");
            } else if (v.getLayoutParams() != null && v.getLayoutParams().height > 0 && v instanceof ViewGroup && hasText((ViewGroup) v)) {
                // A fixed-height container that holds text: does its text still fit?
                List<View> kids = new ArrayList<>(); walk(v, kids);
                for (View k : kids) if (k instanceof TextView && k.getHeight() > 0 && ((TextView) k).getLayout() != null) {
                    int bottom = location(k)[1] + k.getHeight(), limit = location(v)[1] + v.getHeight();
                    if (bottom > limit + 1) log.append(String.format(Locale.ROOT, "TEXT-OUTSIDE-FIXED-CONTAINER container=%.0fdp %s\n", v.getHeight() / density, describe(k)));
                }
            }
        }
        String[] kinds = {"UNNAMED", "SPINNER-NO-OWN-LABEL", "CLIPPED-HEIGHT", "SMALL-TARGET", "SEEKBAR-NO-STATE-DESC", "INPUT-NAMED-ONLY-BY-HINT", "ELLIPSIZED"};
        StringBuilder counts = new StringBuilder("COUNTS");
        for (String kind : kinds) { int n = 0; for (String l : log.toString().split("\n")) if (l.startsWith(kind)) n++; counts.append(" ").append(kind).append("=").append(n); }
        log.append(counts).append("\n");
        File file = new File(out(), "a11y-" + name + "-" + tag(scale) + ".txt");
        try (FileOutputStream stream = new FileOutputStream(file)) { stream.write(log.toString().getBytes("UTF-8")); }
    }
    private static int[] location(View v) { int[] at = new int[2]; v.getLocationInWindow(at); return at; }
    private static boolean hasText(ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View c = group.getChildAt(i);
            if (c instanceof TextView && ((TextView) c).getText().length() > 0) return true;
            if (c instanceof ViewGroup && hasText((ViewGroup) c)) return true;
        }
        return false;
    }

    private static void dialogShot(String name, float scale, int height) throws Exception {
        android.app.AlertDialog d = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
        if (d != null && d.isShowing()) shot(d.getWindow().getDecorView(), height, name, scale);
    }

    // ------------------------------------------------------------------ main screen

    private static JSONObject printing() throws Exception {
        return new JSONObject()
            .put("machine_status", new JSONObject().put("status", 2).put("sub_status", 2075).put("progress", 41))
            .put("print_status", new JSONObject().put("filename", "Benchy_PLA_0.2mm.gcode").put("current_layer", 87).put("total_layer", 212).put("remaining_time_sec", 4520))
            .put("extruder", new JSONObject().put("temperature", 219.6).put("target", 220))
            .put("heater_bed", new JSONObject().put("temperature", 59.8).put("target", 60))
            .put("ztemperature_sensor", new JSONObject().put("temperature", 31.0));
    }
    private static JSONObject idle() throws Exception {
        return new JSONObject().put("machine_status", new JSONObject().put("status", 1).put("sub_status", 0).put("progress", 0))
            .put("extruder", new JSONObject().put("temperature", 24.5).put("target", 0)).put("heater_bed", new JSONObject().put("temperature", 23.9).put("target", 0))
            .put("ztemperature_sensor", new JSONObject().put("temperature", 24.0));
    }

    /** MainActivity connected locally with sample files, trays and a feedback message. */
    private static MainActivity local(boolean busy) throws Exception {
        PrinterService service = Robolectric.setupService(PrinterService.class);
        Shadows.shadowOf((android.app.Application) context()).setComponentNameAndServiceForBindService(
            new android.content.ComponentName(context(), PrinterService.class), service.onBind(null));
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        Field printer = MainActivity.class.getDeclaredField("printer"); printer.setAccessible(true); printer.set(activity, service);
        Thread.sleep(500); Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        Field wanted = PrinterService.class.getDeclaredField("wanted"); wanted.setAccessible(true); wanted.setBoolean(service, true);
        Cc2Session session = new Cc2Session("192.168.1.50", "123456", null);
        Field ready = Cc2Session.class.getDeclaredField("ready"); ready.setAccessible(true); ready.setBoolean(session, true);
        Field at = Cc2Session.class.getDeclaredField("statusAt"); at.setAccessible(true); at.setLong(session, System.nanoTime());
        Field sessionField = PrinterService.class.getDeclaredField("session"); sessionField.setAccessible(true); sessionField.set(service, session);
        service.status = busy ? printing() : idle(); service.connection = "Connected to 192.168.1.50";
        service.attributes = new JSONObject().put("hostname", "Bedroom printer with a rather long name").put("machine_model", "Centauri Carbon 2").put("software_version", new JSONObject().put("ota_version", "01.03.02.15"));
        service.filePage = new JSONObject("{\"total\":3,\"file_list\":[{\"filename\":\"Benchy_PLA_0.2mm_with_a_long_file_name_for_testing.gcode\",\"size\":4823044,\"layer\":212},{\"filename\":\"Calibration cube.gcode\",\"size\":912331,\"layer\":150},{\"filename\":\"Phone stand.gcode\",\"size\":2000000,\"layer\":90}]}");
        service.filesAt = System.nanoTime(); service.fileMessage = "Files received from printer.";
        service.disk = new JSONObject().put("used_bytes", 3_200_000_000L).put("total_bytes", 8_000_000_000L);
        service.history = new JSONObject("{\"history_task_list\":[{\"task_name\":\"Benchy.gcode\",\"task_status\":1,\"begin_time\":1,\"end_time\":9000,\"time_lapse_video_status\":2}]}");
        service.feedback = "Printer acknowledged the command. Waiting for its status to update.";
        service.canvas = new JSONObject("{\"auto_refill\":true,\"active_canvas_id\":0,\"active_tray_id\":1,\"canvas_list\":[{\"canvas_id\":0,\"connected\":1,\"tray_list\":["
            + "{\"tray_id\":0,\"filament_type\":\"PLA\",\"filament_name\":\"PLA Matte\",\"filament_color\":\"#D02828\",\"min_nozzle_temp\":190,\"max_nozzle_temp\":230},"
            + "{\"tray_id\":1,\"filament_type\":\"PLA\",\"filament_name\":\"PLA\",\"filament_color\":\"#F0F0F0\",\"min_nozzle_temp\":190,\"max_nozzle_temp\":230}]}]}");
        Field canvasAt = PrinterService.class.getDeclaredField("canvasAt"); canvasAt.setAccessible(true); canvasAt.setLong(service, System.nanoTime());
        return activity;
    }

    @Test public void mainTabs() throws Exception {
        out();
        for (float scale : SCALES) {
            RuntimeEnvironment.setFontScale(scale);
            theme(false);
            MainActivity activity = local(true);
            Method render = MainActivity.class.getDeclaredMethod("render"); render.setAccessible(true);
            Method page = MainActivity.class.getDeclaredMethod("selectPage", int.class); page.setAccessible(true);
            String[] names = {"monitor", "files", "camera", "settings"};
            for (int i : new int[] {0, 1, 3}) {
                page.invoke(activity, i); render.invoke(activity);
                shot(activity.getWindow().getDecorView(), i == 3 ? 6500 : 4200, "main-" + names[i], scale);
            }
            activity.finish();
        }
    }

    @Test public void mainDialogs() throws Exception {
        out();
        for (float scale : SCALES) {
            RuntimeEnvironment.setFontScale(scale);
            theme(false);
            MainActivity activity = local(false);
            Method render = MainActivity.class.getDeclaredMethod("render"); render.setAccessible(true); render.invoke(activity);
            Method actions = MainActivity.class.getDeclaredMethod("fileActions", JSONObject.class, String.class); actions.setAccessible(true);
            Method setup = MainActivity.class.getDeclaredMethod("startDialog", JSONObject.class, String.class); setup.setAccessible(true);
            Field printer = MainActivity.class.getDeclaredField("printer"); printer.setAccessible(true); PrinterService service = (PrinterService) printer.get(activity);
            JSONObject file = service.filePage.getJSONArray("file_list").getJSONObject(1);
            actions.invoke(activity, file, "local");
            dialogShot("dialog-file", scale, 3000);
            org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog().dismiss();
            setup.invoke(activity, file, "local");
            dialogShot("dialog-printsetup", scale, 6500);
            activity.finish();
        }
    }

    // ------------------------------------------------------------------ plate and viewer (native panels; the WebGL page is blank here)

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
    private static void invoke(Object target, String name) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name); method.setAccessible(true); method.invoke(target);
    }

    @Test public void plate() throws Exception {
        out();
        JSONObject inspected = new JSONObject().put("files", new org.json.JSONArray().put(new JSONObject().put("objects", new org.json.JSONArray()
            .put(new JSONObject().put("name", "3DBenchy")).put(new JSONObject().put("name", "elegoo_cube_with_a_long_name")))));
        for (float scale : SCALES) {
            RuntimeEnvironment.setFontScale(scale);
            theme(false);
            android.content.Intent intent = new android.content.Intent(context(), PlateActivity.class)
                .putExtra(PlateActivity.EXTRA_MODELS, new String[] {"/none/model.3mf"}).putExtra(PlateActivity.EXTRA_SELECTION, "{}").putExtra(PlateActivity.EXTRA_INSPECTED, inspected.toString());
            PlateActivity.prepareEnabled = false;
            try {
                PlateActivity activity = Robolectric.buildActivity(PlateActivity.class, intent).setup().get();
                setField(activity, "placements", new org.json.JSONArray()
                    .put(new JSONObject().put("file", 0).put("object", 0).put("x", 120.5).put("y", 98.0).put("rotation", 45).put("scale", 1))
                    .put(new JSONObject().put("file", 0).put("object", 1).put("x", 130.0).put("y", 100.0).put("rotation", 0).put("scale", 1)));
                setField(activity, "selected", 0); setField(activity, "pageReady", true);
                invoke(activity, "showSelected"); invoke(activity, "setButtons");
                ((TextView) field(activity, "status")).setText(PlateActivity.summarize(java.util.Arrays.asList(java.util.Arrays.asList("touching another copy"), java.util.Arrays.asList("touching another copy"))));
                shot(activity.getWindow().getDecorView(), 2340, "plate-panel", scale);
                invoke(activity, "moreDialog");
                dialogShot("plate-more", scale, 3000);
                activity.finish();
            } finally { PlateActivity.prepareEnabled = true; }
        }
    }

    @Test public void viewer() throws Exception {
        out();
        File gcode = new File(context().getCacheDir(), "Benchy_PLA_0.2mm.gcode");
        try (java.io.InputStream in = getClass().getResourceAsStream("/gcode/tolerance-cc2.gcode")) { java.nio.file.Files.copy(in, gcode.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
        for (float scale : SCALES) {
            RuntimeEnvironment.setFontScale(scale);
            theme(false);
            android.content.Intent intent = new android.content.Intent(context(), GcodeViewerActivity.class)
                .putExtra(GcodeViewerActivity.EXTRA_FILE, gcode.getAbsolutePath()).putExtra(GcodeViewerActivity.EXTRA_NAME, "ElegooToleranceTest_with_a_long_file_name.gcode");
            GcodeViewerActivity activity = Robolectric.buildActivity(GcodeViewerActivity.class, intent).setup().get();
            long deadline = System.currentTimeMillis() + 30_000;
            while (field(activity, "path") == null && System.currentTimeMillis() < deadline) { Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); Thread.sleep(20); }
            Method enable = GcodeViewerActivity.class.getDeclaredMethod("setControlsEnabled", boolean.class); enable.setAccessible(true); enable.invoke(activity, true);
            setField(activity, "layer", 19); invoke(activity, "syncBars");
            shot(activity.getWindow().getDecorView(), 2340, "viewer", scale);
            invoke(activity, "moreDialog");
            dialogShot("viewer-more", scale, 3000);
            activity.finish();
        }
    }

    /** Find models (Slice > Find models online), before any search. */
    @Test public void modelSearch() throws Exception {
        out();
        for (float scale : SCALES) {
            RuntimeEnvironment.setFontScale(scale);
            theme(false);
            ModelSearchActivity activity = Robolectric.buildActivity(ModelSearchActivity.class).setup().get();
            shot(activity.getWindow().getDecorView(), 2340, "find-models", scale);
            activity.finish();
        }
    }

    /** The camera line-up panel (Camera tab > Line up the camera in 3D), as it opens and with every section expanded. */
    @Test public void viewerLineUp() throws Exception {
        out();
        File gcode = new File(context().getCacheDir(), "Benchy_PLA_0.2mm.gcode");
        try (java.io.InputStream in = getClass().getResourceAsStream("/gcode/tolerance-cc2.gcode")) { java.nio.file.Files.copy(in, gcode.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
        for (float scale : SCALES) {
            RuntimeEnvironment.setFontScale(scale);
            theme(false);
            android.content.Intent intent = new android.content.Intent(context(), GcodeViewerActivity.class)
                .putExtra(GcodeViewerActivity.EXTRA_FILE, gcode.getAbsolutePath()).putExtra(GcodeViewerActivity.EXTRA_NAME, "Line-up.gcode");
            GcodeViewerActivity activity = Robolectric.buildActivity(GcodeViewerActivity.class, intent).setup().get();
            long deadline = System.currentTimeMillis() + 30_000;
            while (field(activity, "path") == null && System.currentTimeMillis() < deadline) { Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); Thread.sleep(20); }
            setField(activity, "pageReady", true);
            invoke(activity, "startAligning");
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            shot(activity.getWindow().getDecorView(), 2340, "lineup", scale);
            // Open every collapsed section (their headers end in an arrow) and capture the whole sheet.
            java.util.List<View> all = new java.util.ArrayList<>(); collect(activity.getWindow().getDecorView(), all);
            for (View view : all) if (view instanceof android.widget.Button && ((android.widget.Button) view).getText().toString().startsWith("►")) view.performClick();
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            View sheet = (View) field(activity, "alignPanel");
            shot(sheet, 4200, "lineup-open", scale);
            activity.finish();
        }
    }
    private static void collect(View view, java.util.List<View> into) {
        into.add(view);
        if (view instanceof android.view.ViewGroup) for (int i = 0; i < ((android.view.ViewGroup) view).getChildCount(); i++) collect(((android.view.ViewGroup) view).getChildAt(i), into);
    }

    // ------------------------------------------------------------------ Slice screen and settings editor (need the real engine)

    private interface Condition { boolean met() throws Exception; }
    private static void waitFor(Condition condition) throws Exception {
        long deadline = System.currentTimeMillis() + 60_000;
        while (true) {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            if (condition.met()) return;
            if (System.currentTimeMillis() > deadline) throw new AssertionError("timed out");
            Thread.sleep(50);
        }
    }
    private static File box(double x, double y, double z) throws java.io.IOException {
        double[][] v = {{0,0,0},{x,0,0},{x,y,0},{0,y,0},{0,0,z},{x,0,z},{x,y,z},{0,y,z}};
        int[][] faces = {{0,2,1},{0,3,2},{4,5,6},{4,6,7},{0,1,5},{0,5,4},{1,2,6},{1,6,5},{2,3,7},{2,7,6},{3,0,4},{3,4,7}};
        StringBuilder stl = new StringBuilder("solid box\n");
        for (int[] f : faces) { stl.append("facet normal 0 0 0\nouter loop\n"); for (int i : f) stl.append(String.format(Locale.ROOT, "vertex %f %f %f\n", v[i][0], v[i][1], v[i][2])); stl.append("endloop\nendfacet\n"); }
        stl.append("endsolid box\n");
        File file = File.createTempFile("box", ".stl");
        java.nio.file.Files.write(file.toPath(), stl.toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        return file;
    }

    @Test public void sliceScreen() throws Exception {
        out(); Assume.assumeFalse("Needs -DslicerLib", System.getProperty("slicerLib", "").isEmpty());
        File model = box(20, 20, 10);
        for (float scale : SCALES) {
            RuntimeEnvironment.setFontScale(scale);
            theme(false);
            SliceActivity activity = Robolectric.buildActivity(SliceActivity.class).setup().get();
            waitFor(() -> { Spinner s = (Spinner) field(activity, "processSpinner"); return s.getAdapter() != null && s.getAdapter().getCount() > 0; });
            File picks = new File(context().getCacheDir(), "slice-picks-" + tag(scale)); picks.mkdirs();
            File[] picked = new File[2];
            for (int i = 0; i < 2; i++) { picked[i] = new File(picks, new String[] {"calibration_box_with_a_long_name.stl", "small_box.stl"}[i]); java.nio.file.Files.copy(model.toPath(), picked[i].toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
            List<android.net.Uri> uris = new ArrayList<>(); for (File f : picked) uris.add(android.net.Uri.fromFile(f));
            Method importModels = SliceActivity.class.getDeclaredMethod("importModels", List.class); importModels.setAccessible(true); importModels.invoke(activity, uris);
            waitFor(() -> !((List<?>) field(activity, "models")).isEmpty() && field(activity, "inspected") != null);
            Method addSlot = SliceActivity.class.getDeclaredMethod("addSlot", TrayPlan.Tray.class); addSlot.setAccessible(true);
            addSlot.invoke(activity, new TrayPlan.Tray(0, 1, "PLA", "", "ELEGOO", "#F0F0F0"));
            invoke(activity, "showModels"); invoke(activity, "updateButtons");
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            View root = activity.getWindow().getDecorView();
            View form = (View) field(activity, "content"), bar = (View) field(activity, "actionBar");
            form.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED);
            bar.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED);
            shot(root, form.getMeasuredHeight() + bar.getMeasuredHeight(), "slice", scale);
            // The same screen at a normal phone height, to see how much is left above the sticky action bar.
            shot(root, 2340, "slice-phone", scale);
            activity.finish();
        }
    }

    @Test public void settingsEditor() throws Exception {
        out(); Assume.assumeFalse("Needs -DslicerLib", System.getProperty("slicerLib", "").isEmpty());
        for (float scale : SCALES) {
            RuntimeEnvironment.setFontScale(scale);
            theme(false);
            NativeSlicer.Selection selection = new NativeSlicer.Selection(PRINTER, PROCESS, java.util.Collections.singletonList(PLA));
            selection.overrides.put("wall_loops", "5");
            android.content.Intent intent = new android.content.Intent(context(), SliceSettingsActivity.class).putExtra(SliceSettingsActivity.EXTRA_SELECTION, selection.toJson());
            SliceSettingsActivity screen = Robolectric.buildActivity(SliceSettingsActivity.class, intent).setup().get();
            waitFor(() -> field(screen, "definitions") != null);
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            @SuppressWarnings("unchecked") java.util.Set<String> expanded = (java.util.Set<String>) field(screen, "expanded");
            @SuppressWarnings("unchecked") List<Object> sections = (List<Object>) field(screen, "sections");
            for (Object section : sections) expanded.add((String) field(section, "name"));
            invoke(screen, "filter");
            shot(screen.getWindow().getDecorView(), 9000, "settings-editor", scale);
            screen.finish();
        }
    }
}
