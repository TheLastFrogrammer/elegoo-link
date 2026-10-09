package io.github.thelastfrogrammer.elink;

import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowAlertDialog;
import static org.junit.Assert.*;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Print again and the history detail on the Files tab, and the viewer without a G-code copy, driven as a user would see them. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 34, qualifiers = "w411dp-h891dp-xxhdpi")
public class FailureHistoryScreensTest {
    private static final String SERIAL = "F01PLA1234567890";
    private static final long BEGIN = 1_762_178_700L;

    private static JSONObject status(int state) throws Exception {
        return new JSONObject().put("machine_status", new JSONObject().put("status", state).put("sub_status", 0).put("progress", state == 2 ? 41 : 0))
            .put("print_status", new JSONObject().put("filename", "Benchy.gcode").put("current_layer", 87).put("total_layer", 212).put("remaining_time_sec", 4520))
            .put("extruder", new JSONObject().put("temperature", 24.5).put("target", 0)).put("heater_bed", new JSONObject().put("temperature", 23.9).put("target", 0))
            .put("ztemperature_sensor", new JSONObject().put("temperature", 24.0));
    }
    private static JSONObject history() throws Exception {
        JSONArray rows = new JSONArray()
            .put(new JSONObject().put("task_id", "old1").put("task_name", "Gone.gcode").put("begin_time", BEGIN - 90000).put("end_time", BEGIN - 86000).put("task_status", 2).put("time_lapse_video_status", 0))
            .put(new JSONObject().put("task_id", "t1").put("task_name", "Benchy.gcode").put("begin_time", BEGIN).put("end_time", BEGIN + 4800).put("task_status", 1).put("time_lapse_video_status", 1));
        return new JSONObject().put("history_task_list", rows);
    }
    private static JSONObject files() throws Exception {
        return new JSONObject().put("file_list", new JSONArray().put(new JSONObject().put("filename", "Benchy.gcode").put("size", 1234567).put("layer", 212))).put("total", 1);
    }

    /** A main screen watching the printer through the cloud with fresh data; `state` is the machine status (1 idle, 2 printing). */
    private static final class Rig { MainActivity activity; PrinterService service; }
    private Rig rig(String theme, int state) throws Exception {
        android.content.Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("workshop-settings", 0).edit().putInt("theme", theme.equals("dark") ? 2 : 1).putInt("page", 1).putBoolean("cloudControlUnderstood", true).commit();
        PrinterService service = Robolectric.setupService(PrinterService.class);
        ((org.robolectric.shadows.ShadowApplication) org.robolectric.shadow.api.Shadow.extract(context)).setComponentNameAndServiceForBindService(new ComponentName(context, PrinterService.class), service.onBind(null));
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        Field printer = MainActivity.class.getDeclaredField("printer"); printer.setAccessible(true); printer.set(activity, service);
        Thread.sleep(300); org.robolectric.shadows.ShadowLooper.idleMainLooper();
        long now = System.currentTimeMillis();
        service.cloudSignedIn = true; service.cloudSerial = SERIAL; service.cloudName = "Bedroom"; service.cloudModel = "Centauri Carbon 2";
        service.cloudOnline = 1; service.cloudCheckedAt = now; service.cloudReportedAt = now; service.cloudOnlineSignal = true; service.cloudStatus = status(state);
        service.filePage = files(); service.storage = "local"; service.filesAt = System.nanoTime(); service.history = history();
        Rig rig = new Rig(); rig.activity = activity; rig.service = service; return rig;
    }
    private static void call(Object target, String name, Class<?>[] types, Object... args) throws Exception { Method m = target.getClass().getDeclaredMethod(name, types); m.setAccessible(true); m.invoke(target, args); }
    private static void render(MainActivity activity) throws Exception { call(activity, "render", new Class<?>[0]); org.robolectric.shadows.ShadowLooper.idleMainLooper(); }
    private static void collect(View view, List<View> out) { out.add(view); if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) collect(((ViewGroup) view).getChildAt(i), out); }
    private static List<Button> buttons(View root, String text) { List<View> all = new ArrayList<>(); collect(root, all); List<Button> found = new ArrayList<>(); for (View v : all) if (v instanceof Button && text.contentEquals(((Button) v).getText())) found.add((Button) v); return found; }
    private static boolean hasText(View root, String text) { List<View> all = new ArrayList<>(); collect(root, all); for (View v : all) if (v instanceof TextView && ((TextView) v).getText().toString().contains(text)) return true; return false; }
    private JSONObject row(Rig rig, String id) throws Exception { JSONArray rows = rig.service.history.getJSONArray("history_task_list"); for (int i = 0; i < rows.length(); i++) if (rows.getJSONObject(i).getString("task_id").equals(id)) return rows.getJSONObject(i); throw new AssertionError(id); }
    private static void printAgain(MainActivity activity, JSONObject row) throws Exception { call(activity, "printAgain", new Class<?>[] {JSONObject.class}, row); org.robolectric.shadows.ShadowLooper.idleMainLooper(); }

    @Test public void printAgainIsOnlyEnabledForAFileTheListingHolds() throws Exception {
        Rig rig = rig("light", 1); render(rig.activity);
        Field list = MainActivity.class.getDeclaredField("historyList"); list.setAccessible(true); View historyList = (View) list.get(rig.activity);
        List<Button> again = buttons(historyList, "Print again");
        assertEquals(2, again.size());
        assertTrue("Benchy.gcode is listed (newest entry first)", again.get(0).isEnabled());
        assertFalse("Gone.gcode is not", again.get(1).isEnabled());
        assertTrue(hasText(historyList, "This file is no longer on the printer."));
        rig.service.filesAt = System.nanoTime() - 10L * 60_000_000_000L; render(rig.activity);
        assertEquals("a stale listing offers a refresh", 2, buttons(historyList, "Refresh files first").size());
    }

    @Test public void printAgainOpensTheNormalPrintSetupWhenEverythingChecksOut() throws Exception {
        Rig rig = rig("light", 1);
        printAgain(rig.activity, row(rig, "t1"));
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(dialog); assertTrue(dialog.isShowing());
        assertTrue(hasText(dialog.getWindow().getDecorView(), "Check the build plate, material and sliced printer profile before starting."));
        assertNull("nothing is started by opening it", rig.service.feedback.contains("Sending") ? "sent" : null);
    }

    private void assertRefused(Rig rig, String reason) throws Exception {
        ShadowAlertDialog.reset();
        printAgain(rig.activity, row(rig, "t1"));
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertTrue("Print setup must not open: " + (dialog == null ? "" : dialog.getWindow().getDecorView()), dialog == null || !dialog.isShowing());
        assertTrue(rig.service.feedback, rig.service.feedback.contains(reason));
    }
    @Test public void printAgainIsRefusedWhileThePrinterIsNotIdle() throws Exception {
        assertRefused(rig("light", 2), "wait for the printer to be idle");
    }
    @Test public void printAgainIsRefusedWhileAFileTransferRuns() throws Exception {
        Rig rig = rig("light", 1); rig.service.importing = true;
        assertRefused(rig, "Wait for it to finish before starting a print");
    }
    @Test public void printAgainIsRefusedWithoutFreshStatus() throws Exception {
        Rig rig = rig("light", 1); rig.service.cloudCheckedAt = System.currentTimeMillis() - 5 * 60_000;
        assertRefused(rig, "Refresh status and files");
    }
    @Test public void printAgainForAFileThatIsGoneSaysSoAndOpensNothing() throws Exception {
        Rig rig = rig("light", 1);
        ShadowAlertDialog.reset();
        printAgain(rig.activity, row(rig, "old1"));
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertTrue(dialog == null || !dialog.isShowing());
        assertEquals("This file is no longer on the printer.", rig.service.feedback);
    }

    // ---- screenshots (opt-in) ----
    private static void shoot(View root, int width, int height, File file) throws Exception {
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST));
        root.layout(0, 0, width, root.getMeasuredHeight());
        Bitmap bitmap = Bitmap.createBitmap(width, Math.max(1, root.getMeasuredHeight()), Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(bitmap));
        file.getParentFile().mkdirs();
        try (FileOutputStream stream = new FileOutputStream(file)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream); }
    }
    @Test public void renderTheHistoryList() throws Exception {
        String out = System.getProperty("screenshots", ""); Assume.assumeFalse("Screenshots are opt-in", out.isEmpty());
        for (String theme : new String[] {"light", "dark"}) {
            Rig rig = rig(theme, 1); render(rig.activity);
            Field list = MainActivity.class.getDeclaredField("historyList"); list.setAccessible(true);
            shoot((View) ((View) list.get(rig.activity)).getParent(), 1080, 2400, new File(out, "history-list-" + theme + ".png"));
        }
    }
    @Test public void renderTheHistoryDetail() throws Exception {
        String out = System.getProperty("screenshots", ""); Assume.assumeFalse("Screenshots are opt-in", out.isEmpty());
        for (String theme : new String[] {"light", "dark"}) {
            Rig rig = rig(theme, 1);
            // A made-up detail answer (its real layout is unknown) and a small picture, so every part of the dialog shows.
            rig.service.historyDetails.put("t1", new JSONObject("{\"error_code\":0,\"total_layer\":212,\"nozzle_diameter\":0.4,\"plate_type\":\"Smooth Build Plate (Side B)\","
                + "\"filament_list\":[{\"tray\":\"A1\",\"type\":\"PLA\",\"weight_g\":23.97},{\"tray\":\"A2\",\"type\":\"PETG\",\"weight_g\":4.2}]}"));
            Bitmap picture = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888); picture.eraseColor(theme.equals("dark") ? 0xff3d8b82 : 0xff7fc7bd);
            java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream(); picture.compress(Bitmap.CompressFormat.PNG, 100, png);
            rig.service.thumbnails.put("local/Benchy.gcode", android.util.Base64.encodeToString(png.toByteArray(), android.util.Base64.NO_WRAP));
            call(rig.activity, "historyDetail", new Class<?>[] {JSONObject.class}, row(rig, "t1"));
            org.robolectric.shadows.ShadowLooper.idleMainLooper();
            AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
            assertTrue(hasText(dialog.getWindow().getDecorView(), "Smooth Build Plate (Side B)"));
            assertTrue(hasText(dialog.getWindow().getDecorView(), "Tray A1 · Type PLA · Weight g 23.97"));
            assertEquals("Print again is offered inside the detail", 1, buttons(dialog.getWindow().getDecorView(), "Print again").size());
            shoot(dialog.getWindow().getDecorView(), 1000, 2400, new File(out, "history-detail-" + theme + ".png"));
        }
    }

    // ---- the viewer without the file ----
    private GcodeViewerActivity viewer(String theme) throws Exception {
        android.content.Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("workshop-settings", 0).edit().putInt("theme", theme.equals("dark") ? 2 : 1).commit();
        PrinterService service = Robolectric.setupService(PrinterService.class);
        ((org.robolectric.shadows.ShadowApplication) org.robolectric.shadow.api.Shadow.extract(context)).setComponentNameAndServiceForBindService(new ComponentName(context, PrinterService.class), service.onBind(null));
        Intent intent = new Intent(context, GcodeViewerActivity.class).putExtra(GcodeViewerActivity.EXTRA_FOLLOW, true);
        GcodeViewerActivity activity = Robolectric.buildActivity(GcodeViewerActivity.class, intent).setup().get();
        Thread.sleep(300); org.robolectric.shadows.ShadowLooper.idleMainLooper();
        long now = System.currentTimeMillis();
        service.cloudSignedIn = true; service.cloudSerial = SERIAL; service.cloudOnline = 1; service.cloudCheckedAt = now; service.cloudReportedAt = now; service.cloudOnlineSignal = true;
        service.cloudStatus = status(2);
        activity.changed(); org.robolectric.shadows.ShadowLooper.idleMainLooper();
        return activity;
    }
    @Test public void withoutAGcodeCopyTheViewerShowsProgressThePlainLineAndBothButtons() throws Exception {
        GcodeViewerActivity activity = viewer("light");
        View root = activity.getWindow().getDecorView();
        Field status = GcodeViewerActivity.class.getDeclaredField("status"); status.setAccessible(true);
        assertEquals("41% · layer 87 of 212 · 1h 15m left", ((TextView) status.get(activity)).getText().toString());
        assertTrue(hasText(root, StatusPresentation.NO_FILE_LINE));
        assertEquals(1, buttons(root, "Download from printer").size()); assertEquals(1, buttons(root, "Choose file…").size());
        assertFalse("the port-80 story is not the main message", hasText(root, "port 80"));
    }
    @Test public void renderTheViewerWithoutAFile() throws Exception {
        String out = System.getProperty("screenshots", ""); Assume.assumeFalse("Screenshots are opt-in", out.isEmpty());
        for (String theme : new String[] {"light", "dark"}) {
            GcodeViewerActivity activity = viewer(theme);
            View root = activity.getWindow().getDecorView();
            int width = 1080, height = 2340;
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, width, height);
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888); root.draw(new Canvas(bitmap));
            File file = new File(out, "viewer-nofile-" + theme + ".png"); file.getParentFile().mkdirs();
            try (FileOutputStream stream = new FileOutputStream(file)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream); }
        }
    }
}
