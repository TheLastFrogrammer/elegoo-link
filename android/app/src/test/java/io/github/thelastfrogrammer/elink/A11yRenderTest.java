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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
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

    /** How tall the main screen's scrolling content is, in phone screens (a 1080 x 2340 screen, less the header-to-tab-bar space). */
    private static void noteHeight(MainActivity activity, String name, float scale) throws Exception {
        View content = (View) field(activity, "content");
        content.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        String line = String.format(java.util.Locale.ROOT, "%-55s %s %6d px  %.1f screens%n", name, tag(scale), content.getMeasuredHeight(), content.getMeasuredHeight() / 2100.0);
        java.nio.file.Files.write(new File(out(), "page-heights.txt").toPath(), line.getBytes(java.nio.charset.StandardCharsets.UTF_8),
            java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
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
            .put("ztemperature_sensor", new JSONObject().put("temperature", 31.0))
            .put("led", new JSONObject().put("status", 0))
            .put("fans", new JSONObject().put("fan", new JSONObject().put("speed", 173)).put("aux_fan", new JSONObject().put("speed", 0)).put("box_fan", new JSONObject().put("speed", 77)));
    }
    private static JSONObject idle() throws Exception {
        return new JSONObject().put("machine_status", new JSONObject().put("status", 1).put("sub_status", 0).put("progress", 0))
            .put("extruder", new JSONObject().put("temperature", 24.5).put("target", 0)).put("heater_bed", new JSONObject().put("temperature", 23.9).put("target", 0))
            .put("ztemperature_sensor", new JSONObject().put("temperature", 24.0));
    }

    /** MainActivity connected locally with sample files, trays and a feedback message. */
    private static MainActivity local(boolean busy) throws Exception {
        PrinterService service = Robolectric.setupService(PrinterService.class);
        ((org.robolectric.shadows.ShadowApplication) org.robolectric.shadow.api.Shadow.extract(context())).setComponentNameAndServiceForBindService(
            new android.content.ComponentName(context(), PrinterService.class), service.onBind(null));
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        Field printer = MainActivity.class.getDeclaredField("printer"); printer.setAccessible(true); printer.set(activity, service);
        Thread.sleep(500); org.robolectric.shadows.ShadowLooper.idleMainLooper();
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

    /** The field checklist, with one check marked so its note box shows. */
    /** The recording's per-layer cross section: line types stacked in the viewer's colours, the total on top, a layer read out. */
    @Test public void layerCrossSection() throws Exception {
        int[] types = {0, 1, 3, 4, 5};
        double[] layer = new double[60]; for (int i = 0; i < 60; i++) layer[i] = i + 1;
        java.util.List<ChartView.Series> parts = new java.util.ArrayList<>();
        for (int type : types) {
            double[] v = new double[60];
            for (int i = 0; i < 60; i++) v[i] = type == 0 ? 40 : type == 1 ? 70 : type == 3 ? (i < 5 || i > 54 ? 0 : 120 + 30 * Math.sin(i / 6.0)) : type == 4 ? (i < 5 ? 300 : i > 54 ? 0 : 20) : (i > 54 ? 300 : 0);
            parts.add(new ChartView.Series(GcodeToolpath.FEATURES[type], android.graphics.Color.parseColor(GcodeViewerActivity.PALETTE[type]), v, false));
        }
        ChartView chart = new ChartView(context(), 0xff17252c, 0xff5a6d76, 0xffdfe6e8, 0xfff2f5f6).layers().stacked();
        chart.set("Cross section by line type", " mm²", layer, parts, 0, Double.NaN);
        java.util.List<double[]> checks = java.util.Arrays.asList(new double[] {22, PrintRecorder.GUARDIAN_SPREAD, 0.03, 0.015, 1, 0}, new double[] {38, PrintRecorder.GUARDIAN_OFF_PRINT, 0.06, 0.03, 1, 1});
        java.util.List<double[]> answers = new java.util.ArrayList<>(); answers.add(new double[] {40, 1});
        for (Object[] m : LayerCharts.marks(new LayerCharts.Guardian(checks, answers), 0xff00796b)) chart.mark((Double) m[0], (Integer) m[1], (String) m[2]);
        android.widget.FrameLayout frame = new android.widget.FrameLayout(context()); frame.setBackgroundColor(0xfff2f5f6); frame.addView(chart);
        frame.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(780, View.MeasureSpec.EXACTLY));
        frame.layout(0, 0, 1080, 780);
        chart.onTouchEvent(android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_MOVE, 704, 400, 0));
        shot(frame, 780, "layer-cross-section", 1.0f);
    }

    /** A recording's chart with the camera pictures' ticks, one touched: its picture in the readout. */
    @Test public void picturesOnTheCharts() throws Exception {
        int n = 120; double[] t = new double[n], progress = new double[n];
        for (int i = 0; i < n; i++) { t[i] = i * 60; progress[i] = Math.min(100, i * 0.9); }
        double[] pictures = new double[24]; for (int i = 0; i < pictures.length; i++) pictures[i] = 300 + i * 290;
        android.graphics.Bitmap frame = android.graphics.Bitmap.createBitmap(160, 120, android.graphics.Bitmap.Config.ARGB_8888);
        new android.graphics.Canvas(frame).drawColor(0xff40505a);
        ChartView chart = new ChartView(context(), 0xff17252c, 0xff5a6d76, 0xffdfe6e8, 0xfff2f5f6);
        chart.set("Progress", " %", t, java.util.Collections.singletonList(new ChartView.Series("Progress", 0xff2a78d6, progress, false)), 0, 100);
        chart.pictures(pictures, i -> frame, i -> { });
        android.widget.FrameLayout box = new android.widget.FrameLayout(context()); box.setBackgroundColor(0xfff2f5f6); box.addView(chart);
        box.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(780, View.MeasureSpec.EXACTLY));
        box.layout(0, 0, 1080, 780);
        float left = 44 * context().getResources().getDisplayMetrics().density, right = 1080 - 12 * context().getResources().getDisplayMetrics().density;
        float x = (float) (left + (pictures[8] - t[0]) / (t[n - 1] - t[0]) * (right - left));
        chart.onTouchEvent(android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_DOWN, x, 500, 0));
        chart.onTouchEvent(android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_MOVE, x, 500, 0));
        shot(box, 780, "pictures-on-charts", 1.0f);
    }

    /** A recording's temperatures with its warm-up phases shaded, one touched. */
    @Test public void warmUpOnTheCharts() throws Exception {
        int n = 90; double[] t = new double[n], nozzle = new double[n], bed = new double[n], nozzleTarget = new double[n], bedTarget = new double[n];
        for (int i = 0; i < n; i++) {
            t[i] = i * 20; bedTarget[i] = 60; nozzleTarget[i] = i < 12 ? 140 : 220;
            bed[i] = Math.min(60, 25 + i * 4); nozzle[i] = i < 12 ? Math.min(140, 25 + i * 15) : Math.min(220, 140 + (i - 12) * 12);
        }
        double[] sub = new double[n], layer = new double[n];
        for (int i = 0; i < n; i++) { sub[i] = i < 2 ? 1081 : i < 10 ? 1405 : i < 16 ? 2901 : i < 20 ? 1045 : 2075; layer[i] = i < 22 ? 0 : 1 + (i - 22) / 4; }
        java.util.List<ChartView.Series> lines = java.util.Arrays.asList(new ChartView.Series("Nozzle", 0xff2a78d6, nozzle, false), new ChartView.Series("Nozzle target", 0xff2a78d6, nozzleTarget, true),
            new ChartView.Series("Bed", 0xffeb6834, bed, false), new ChartView.Series("Bed target", 0xffeb6834, bedTarget, true));
        ChartView chart = new ChartView(context(), 0xff17252c, 0xff5a6d76, 0xffdfe6e8, 0xfff2f5f6);
        chart.set("Temperatures", " °C", t, lines, Double.NaN, Double.NaN);
        for (Object[] p : RecordingsActivity.warmUpPhases(sub, layer, t)) chart.span((Double) p[0], (Double) p[1], (String) p[2]);
        android.widget.FrameLayout frame = new android.widget.FrameLayout(context()); frame.setBackgroundColor(0xfff2f5f6); frame.addView(chart);
        frame.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(780, View.MeasureSpec.EXACTLY));
        frame.layout(0, 0, 1080, 780);
        chart.onTouchEvent(android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_MOVE, 260, 400, 0));
        shot(frame, 780, "warm-up", 1.0f);
    }

    /** A recording's volume printed so far over time, stacked by line type. */
    @Test public void volumeOverTime() throws Exception {
        int n = 120; double[] t = new double[n]; double[][] at = new double[n][GcodeToolpath.FEATURES.length];
        for (int i = 0; i < n; i++) {
            t[i] = i * 60; double f = i / (double) (n - 1);
            at[i][6] = Math.min(f, 0.05) * 20 * 2000; at[i][0] = f * 4000; at[i][1] = f * 6000; at[i][3] = Math.max(0, Math.min(f, 0.9) - 0.05) * 9000; at[i][5] = Math.max(0, f - 0.9) * 10 * 2500;
            if (i == 40) java.util.Arrays.fill(at[i], Double.NaN);
        }
        double[][] soFar = RecordingsActivity.soFarByType(at);
        java.util.List<ChartView.Series> parts = new java.util.ArrayList<>();
        for (int type : new int[] {0, 1, 3, 5, 6}) parts.add(new ChartView.Series(GcodeToolpath.FEATURES[type], android.graphics.Color.parseColor(GcodeViewerActivity.PALETTE[type]), soFar[type], false));
        ChartView chart = new ChartView(context(), 0xff17252c, 0xff5a6d76, 0xffdfe6e8, 0xfff2f5f6).stacked();
        chart.set("Printed so far, by line type", " cm³", t, parts, 0, Double.NaN);
        android.widget.FrameLayout frame = new android.widget.FrameLayout(context()); frame.setBackgroundColor(0xfff2f5f6); frame.addView(chart);
        frame.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(780, View.MeasureSpec.EXACTLY));
        frame.layout(0, 0, 1080, 780);
        chart.onTouchEvent(android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_MOVE, 704, 400, 0));
        shot(frame, 780, "volume-over-time", 1.0f);
    }

    @Test public void fieldCheck() throws Exception {
        out();
        for (float scale : SCALES) {
            RuntimeEnvironment.setFontScale(scale);
            theme(false);
            context().getSharedPreferences(FieldCheckActivity.PREFS, 0).edit().putString(FieldCheckActivity.KEY, "{\"files\":{\"result\":\"FAILS\",\"note\":\"List stays blank\",\"at\":1}}").commit();
            FieldCheckActivity activity = Robolectric.buildActivity(FieldCheckActivity.class).setup().get();
            shot(activity.getWindow().getDecorView(), 9000, "fieldcheck", scale);
            activity.finish();
        }
    }

    @Test public void mainTabs() throws Exception {
        out();
        for (float scale : SCALES) {
            RuntimeEnvironment.setFontScale(scale);
            theme(false);
            MainActivity activity = local(true);
            Method render = MainActivity.class.getDeclaredMethod("render"); render.setAccessible(true);
            Method page = MainActivity.class.getDeclaredMethod("selectPage", int.class); page.setAccessible(true);
            String[] names = {"print", "printer", "files", "history", "settings"};
            for (int i = 0; i < names.length; i++) {
                page.invoke(activity, i); render.invoke(activity);
                shot(activity.getWindow().getDecorView(), 4200, "main-" + names[i], scale);
                noteHeight(activity, "main-" + names[i], scale);
            }
            // The dashboard hero: finish time beside the percentage, Elapsed / Remaining / Layer tiles, Light beside Pause and Stop,
            // and the camera picture with its badge and overlay buttons.
            if (scale == 1f) {
                Method close = MainActivity.class.getDeclaredMethod("closeSection", int.class); close.setAccessible(true);
                page.invoke(activity, 0); close.invoke(activity, 0); render.invoke(activity);
                assertTrue(((TextView) field(activity, "doneText")).getText().toString().startsWith("Done at "));
                assertEquals("87 / 212", ((TextView) field(activity, "statLayer")).getText().toString());
                assertEquals("1h 15m", ((TextView) field(activity, "statRemaining")).getText().toString());
                android.widget.Button light = (android.widget.Button) field(activity, "printLight");
                assertEquals(android.view.View.VISIBLE, light.getVisibility()); assertEquals("Light on", light.getText().toString());
                assertEquals("68\u00a0%", ((TextView) field(activity, "tunePartFan")).getText().toString());
                assertEquals("off", ((TextView) field(activity, "tuneAuxFan")).getText().toString());
                assertEquals("the light switch is not shown twice", android.view.View.GONE, ((android.view.View) field(activity, "lightRow")).getVisibility());
                android.graphics.Bitmap frame = android.graphics.Bitmap.createBitmap(64, 36, android.graphics.Bitmap.Config.ARGB_8888); frame.eraseColor(0xff334048);
                Field last = MainActivity.class.getDeclaredField("lastFrame"); last.setAccessible(true); last.set(activity, frame);
                android.widget.ImageView monitor = (android.widget.ImageView) field(activity, "monitorImage"); monitor.setImageBitmap(frame); render.invoke(activity);
                assertEquals(android.view.View.VISIBLE, ((android.view.View) monitor.getParent()).getVisibility());
                shot(activity.getWindow().getDecorView(), 2600, "main-print-hero-camera", scale);
                // Hide camera folds it to one Show camera button, and Show camera brings it back.
                Method hide = MainActivity.class.getDeclaredMethod("hideMonitorCamera", boolean.class); hide.setAccessible(true);
                hide.invoke(activity, true);
                assertEquals(android.view.View.GONE, ((android.view.View) field(activity, "monitorBody")).getVisibility());
                assertEquals(android.view.View.VISIBLE, ((android.view.View) ((android.view.View) field(activity, "monitorShow")).getParent()).getVisibility());
                hide.invoke(activity, false);
                assertEquals(android.view.View.VISIBLE, ((android.view.View) field(activity, "monitorBody")).getVisibility());
                android.view.View stopButton = (android.view.View) field(activity, "stop");
                assertEquals("Stop stays beside Pause and the light", android.view.View.VISIBLE, stopButton.getVisibility());
                last.set(activity, null); render.invoke(activity);
            }
            // The showcase tour: a caption over the screen, the title card, and no printer address in the header while it records.
            if (scale == 1f) {
                page.invoke(activity, 0); Showcase.stage = Showcase.MAIN; render.invoke(activity);
                String header = ((TextView) field(activity, "identity")).getText().toString();
                assertTrue("no address while recording: " + header, !header.matches(".*\\d+\\.\\d+\\.\\d+\\.\\d+.*"));
                Showcase.caption(activity, "Live status: progress, layer, temperatures and time left");
                org.robolectric.shadows.ShadowLooper.idleMainLooper(1, java.util.concurrent.TimeUnit.SECONDS);
                shot(activity.getWindow().getDecorView(), 2340, "showcase-caption", scale);
                Showcase.card(activity, "Link Workshop", "A tour on a real print", 0);
                org.robolectric.shadows.ShadowLooper.idleMainLooper(2, java.util.concurrent.TimeUnit.SECONDS);
                shot(activity.getWindow().getDecorView(), 2340, "showcase-card", scale);
                Showcase.clear(activity); Showcase.stop(); render.invoke(activity);
            }
            // Every section of every tab, opened as from its row.
            Method open = null; for (Method m : MainActivity.class.getDeclaredMethods()) if (m.getName().equals("openSection")) open = m; open.setAccessible(true);
            for (Object section : (List<?>) field(activity, "sections")) {
                Field tab = section.getClass().getDeclaredField("tab"), title = section.getClass().getDeclaredField("title"); tab.setAccessible(true); title.setAccessible(true);
                page.invoke(activity, tab.getInt(section)); open.invoke(activity, section); render.invoke(activity);
                String slug = ((String) title.get(section)).toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z]+", "-");
                shot(activity.getWindow().getDecorView(), 6500, "main-" + names[tab.getInt(section)] + "-" + slug, scale);
                noteHeight(activity, "main-" + names[tab.getInt(section)] + "-" + slug, scale);
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
            org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog().dismiss();
            // The review page before a print starts, with one filament to check and a short spool.
            Class<?> rowType = Class.forName("io.github.thelastfrogrammer.elink.MainActivity$ReviewRow");
            java.lang.reflect.Constructor<?> make = rowType.getDeclaredConstructor(); make.setAccessible(true);
            Object row = make.newInstance();
            for (String[] kv : new String[][] {{"title", "Filament 1"}, {"wants", "PLA Matte · red"}, {"wantsColour", "#D02828"}, {"tray", "PLA · white · CANVAS 0 tray 1"}, {"trayColour", "#F0F0F0"},
                    {"problem", "The colour differs from what the file was sliced for."}, {"low", "Only about 40 g left on this spool; this filament needs about 55 g."}}) {
                Field field = rowType.getDeclaredField(kv[0]); field.setAccessible(true); field.set(row, kv[1]);
            }
            Field level = rowType.getDeclaredField("level"); level.setAccessible(true); level.set(row, FilamentMatch.Level.CHECK);
            Method review = null; for (Method m : MainActivity.class.getDeclaredMethods()) if (m.getName().equals("printReview")) review = m; review.setAccessible(true);
            List<String[]> settingsShown = java.util.Arrays.asList(new String[] {"Build plate", "A"}, new String[] {"Printer / bed check", "On"}, new String[] {"Force bed leveling", "Off"}, new String[] {"Timelapse on the printer", "Off"});
            review.invoke(activity, file, "local", settingsShown, java.util.Collections.singletonList(row), 1, 0, (Runnable) () -> { }, (Runnable) () -> { });
            android.app.Dialog page = org.robolectric.shadows.ShadowDialog.getLatestDialog();
            shot(page.getWindow().getDecorView(), 2340, "print-review", scale);
            page.dismiss();
            // The page that follows the start: waiting for the printer (no service here), then a refused start.
            new PrintStartSteps(activity, () -> null, "Calibration cube.gcode", "On your network", false, 0xff17252c, 0xff5a6d76, 0xff00796b, 0xfff2f5f6, 0xffffffff, false).show();
            android.app.Dialog steps = org.robolectric.shadows.ShadowDialog.getLatestDialog();
            shot(steps.getWindow().getDecorView(), 2340, "print-steps", scale);
            steps.dismiss();
            invoke(activity, "temperatureDialog");
            dialogShot("dialog-temperature", scale, 3000);
            org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog().dismiss();
            Method pictures = MainActivity.class.getDeclaredMethod("pictureDialog", boolean.class); pictures.setAccessible(true);
            pictures.invoke(activity, true);
            dialogShot("dialog-pictures", scale, 3000);
            org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog().dismiss();
            // A file sliced on the phone for red PLA Matte and white PETG: the first is suggested, the second has no PETG tray.
            context().getSharedPreferences(SliceActivity.TRAY_PLANS, android.content.Context.MODE_PRIVATE).edit().putString(GcodeLibrary.safeName("Phone stand.gcode"),
                new TrayPlan(2, new java.util.ArrayList<>()).withNeeds(java.util.Arrays.asList("Elegoo PLA Matte @ECC2", "Elegoo PETG @ECC2"), java.util.Arrays.asList("#D02828", "#FFFFFF")).toJson()).commit();
            setup.invoke(activity, service.filePage.getJSONArray("file_list").getJSONObject(2), "local");
            org.robolectric.shadows.ShadowLooper.idleMainLooper();
            dialogShot("dialog-printsetup-trays", scale, 6500);
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
            while (field(activity, "path") == null && System.currentTimeMillis() < deadline) { org.robolectric.shadows.ShadowLooper.idleMainLooper(); Thread.sleep(20); }
            Method enable = GcodeViewerActivity.class.getDeclaredMethod("setControlsEnabled", boolean.class); enable.setAccessible(true); enable.invoke(activity, true);
            setField(activity, "layer", 19); invoke(activity, "syncBars");
            shot(activity.getWindow().getDecorView(), 2340, "viewer", scale);
            // With Details on: the line, the file's time, the camera and the filament over the picture.
            context().getSharedPreferences("viewer-hints", 0).edit().putBoolean("details", true).commit();
            Method details = GcodeViewerActivity.class.getDeclaredMethod("showDetails", int.class, boolean.class); details.setAccessible(true); details.invoke(activity, 500, true);
            long until = System.currentTimeMillis() + 5_000;
            TextView code = ((TextView[]) field(activity, "detailValues"))[0];
            while (code.getText().length() == 0 && System.currentTimeMillis() < until) { org.robolectric.shadows.ShadowLooper.idleMainLooper(); Thread.sleep(20); }
            assertTrue(activity.detailsText(), code.getText().toString().startsWith("G1"));
            shot(activity.getWindow().getDecorView(), 2340, "viewer-details", scale);
            context().getSharedPreferences("viewer-hints", 0).edit().remove("details").commit();
            invoke(activity, "moreDialog");
            dialogShot("viewer-more", scale, 3000);
            activity.finish();
        }
    }

    /** Every "Find a feature" entry leads to something that exists and shows on its tab (labels drift; this catches it). */
    @Test public void everyFeatureCanBeFound() throws Exception {
        RuntimeEnvironment.setFontScale(1f); theme(false);
        MainActivity activity = local(false);   // idle: printer controls are shown
        Method render = MainActivity.class.getDeclaredMethod("render"); render.setAccessible(true);
        Method page = MainActivity.class.getDeclaredMethod("selectPage", int.class); page.setAccessible(true);
        Method prepare = MainActivity.class.getDeclaredMethod("prepareFeature", FeatureIndex.Feature.class); prepare.setAccessible(true);
        Method reveal = MainActivity.class.getDeclaredMethod("reveal", int.class, String.class); reveal.setAccessible(true);
        // Idle and connected, so these printer controls must be there themselves, not only the section that explains when.
        List<String> mustShow = java.util.Arrays.asList("load", "tray", "move", "level", "vibration", "emergency", "light");
        List<String> missing = new java.util.ArrayList<>();
        for (FeatureIndex.Feature f : FeatureIndex.ALL) {
            if (f.id.equals("toolpath")) continue;   // shown only while printing
            page.invoke(activity, f.tab); prepare.invoke(activity, f); render.invoke(activity);
            android.view.View found = (android.view.View) reveal.invoke(activity, f.tab, f.target);
            if (found != null && !found.isShown()) found = null;
            if (found == null && !f.fallback.isEmpty() && !mustShow.contains(f.id)) found = (android.view.View) reveal.invoke(activity, f.tab, f.fallback);   // e.g. speed mode only while printing
            if (found == null) missing.add(f.id + " (\"" + f.target + "\" on " + f.where() + ")");
        }
        assertTrue("Features whose button or heading was not found: " + missing, missing.isEmpty());
        activity.finish();
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

    /** The in-app model browser's own controls, with a model downloaded and ready for the slicer. */
    @Test public void modelBrowser() throws Exception {
        out();
        for (float scale : SCALES) {
            RuntimeEnvironment.setFontScale(scale);
            theme(false);
            android.content.Intent intent = new android.content.Intent(context(), ModelBrowserActivity.class)
                .putExtra(ModelBrowserActivity.EXTRA_SITE, "Printables").putExtra(ModelBrowserActivity.EXTRA_TERM, "cable clip");
            ModelBrowserActivity activity = Robolectric.buildActivity(ModelBrowserActivity.class, intent).setup().get();
            @SuppressWarnings("unchecked") java.util.List<String> ready = (java.util.List<String>) field(activity, "ready");
            ready.add(new File(context().getCacheDir(), "Cable clip remix.stl").getAbsolutePath());
            invoke(activity, "showReady");
            shot(activity.getWindow().getDecorView(), 2340, "model-browser", scale);
            activity.finish();
        }
    }

    /** The camera line-up panel (Camera tab > Line up the camera in 3D), as it opens and with every section expanded. */
    /** Live toolpath's option dialogs: Layers, transparency and nozzle dot, and Printhead size (fan block on). */
    @Test public void viewerOptions() throws Exception {
        out();
        File gcode = new File(context().getCacheDir(), "Benchy_PLA_0.2mm.gcode");
        try (java.io.InputStream in = getClass().getResourceAsStream("/gcode/tolerance-cc2.gcode")) { java.nio.file.Files.copy(in, gcode.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
        for (float scale : SCALES) {
            RuntimeEnvironment.setFontScale(scale);
            theme(false);
            context().getSharedPreferences("viewer-hints", 0).edit().putBoolean("fanOn", true).commit();
            PrinterService service = Robolectric.setupService(PrinterService.class);
            ((org.robolectric.shadows.ShadowApplication) org.robolectric.shadow.api.Shadow.extract(context())).setComponentNameAndServiceForBindService(
                new android.content.ComponentName(context(), PrinterService.class), service.onBind(null));
            android.content.Intent intent = new android.content.Intent(context(), GcodeViewerActivity.class)
                .putExtra(GcodeViewerActivity.EXTRA_FILE, gcode.getAbsolutePath()).putExtra(GcodeViewerActivity.EXTRA_NAME, "Options.gcode").putExtra(GcodeViewerActivity.EXTRA_FOLLOW, true);
            GcodeViewerActivity activity = Robolectric.buildActivity(GcodeViewerActivity.class, intent).setup().get();
            long deadline = System.currentTimeMillis() + 30_000;
            while (field(activity, "path") == null && System.currentTimeMillis() < deadline) { org.robolectric.shadows.ShadowLooper.idleMainLooper(); Thread.sleep(20); }
            invoke(activity, "nozzleDialog"); org.robolectric.shadows.ShadowLooper.idleMainLooper();
            dialogShot("viewer-options", scale, 4200);
            org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog().dismiss();
            invoke(activity, "headSizeDialog"); org.robolectric.shadows.ShadowLooper.idleMainLooper();
            dialogShot("viewer-headsize", scale, 4200);
            activity.finish();
        }
    }

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
            while (field(activity, "path") == null && System.currentTimeMillis() < deadline) { org.robolectric.shadows.ShadowLooper.idleMainLooper(); Thread.sleep(20); }
            setField(activity, "pageReady", true);
            invoke(activity, "startAligning");
            org.robolectric.shadows.ShadowLooper.idleMainLooper();
            shot(activity.getWindow().getDecorView(), 2340, "lineup", scale);
            // Open every collapsed section (their headers end in an arrow) and capture the whole sheet.
            java.util.List<View> all = new java.util.ArrayList<>(); collect(activity.getWindow().getDecorView(), all);
            for (View view : all) if (view instanceof android.widget.Button && ((android.widget.Button) view).getText().toString().startsWith("►")) view.performClick();
            org.robolectric.shadows.ShadowLooper.idleMainLooper();
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
            org.robolectric.shadows.ShadowLooper.idleMainLooper();
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
            org.robolectric.shadows.ShadowLooper.idleMainLooper();
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
            org.robolectric.shadows.ShadowLooper.idleMainLooper();
            @SuppressWarnings("unchecked") java.util.Set<String> expanded = (java.util.Set<String>) field(screen, "expanded");
            @SuppressWarnings("unchecked") List<Object> sections = (List<Object>) field(screen, "sections");
            for (Object section : sections) expanded.add((String) field(section, "name"));
            invoke(screen, "filter");
            shot(screen.getWindow().getDecorView(), 9000, "settings-editor", scale);
            screen.finish();
        }
    }
}
