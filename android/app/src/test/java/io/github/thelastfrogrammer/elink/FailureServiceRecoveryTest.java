package io.github.thelastfrogrammer.elink;

import android.app.Notification;
import android.os.Looper;
import org.json.JSONObject;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Recovery behaviour of PrinterService and the Slice screen's Back handling, driven on the JVM. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 34, qualifiers = "w411dp-h891dp-xxhdpi")
public class FailureServiceRecoveryTest {
    private static Object get(Object target, String name) throws Exception { java.lang.reflect.Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target); }
    private static void set(Object target, String name, Object value) throws Exception { java.lang.reflect.Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); f.set(target, value); }
    private static void call(Object target, String name, Class<?>[] types, Object... args) throws Exception { java.lang.reflect.Method m = target.getClass().getDeclaredMethod(name, types); m.setAccessible(true); m.invoke(target, args); }
    private static void waitFor(java.util.concurrent.Callable<Boolean> condition) throws Exception {
        long end = System.currentTimeMillis() + 20_000;
        while (true) { org.robolectric.shadows.ShadowLooper.idleMainLooper(); if (condition.call()) return; if (System.currentTimeMillis() > end) fail("timed out"); Thread.sleep(25); }
    }

    /** Connection lost while a queued (Upload and print) file is on its way: the queue clears and the sliced file is kept in the Files tab. */
    @Test public void aLostConnectionClearsTheUploadQueueAndKeepsTheSlicedFile() throws Exception {
        PrinterService service = Robolectric.buildService(PrinterService.class).create().get();
        File sliced = new File(RuntimeEnvironment.getApplication().getCacheDir(), "lost.gcode");
        Files.write(sliced.toPath(), "; generated\nG28\nG1 X10 Y10\n".getBytes(StandardCharsets.UTF_8));
        set(service, "queuedFile", sliced); set(service, "queuedName", "lost.gcode");
        assertTrue(service.uploadsPending());
        call(service, "failed", new Class<?>[] {String.class, boolean.class}, "Wi-Fi lost", false);
        assertFalse("nothing is left waiting for an upload that can no longer finish", service.uploadsPending());
        waitFor(() -> service.selectedFile != null);
        assertEquals("lost.gcode", service.selectedName);
        assertTrue(service.feedback, service.feedback.contains("Upload stopped: connection lost"));
        assertTrue(service.feedback, service.feedback.contains("Files tab"));
    }

    /** A print was running when the local connection failed: the user is told, not just shown a changed notification. */
    @Test public void losingContactMidPrintRaisesAnAlert() throws Exception {
        android.app.Application app = RuntimeEnvironment.getApplication();
        ((org.robolectric.shadows.ShadowApplication) org.robolectric.shadow.api.Shadow.extract(app)).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS);
        PrinterService service = Robolectric.buildService(PrinterService.class).create().get();
        set(service, "wanted", true);
        service.status = new JSONObject().put("machine_status", new JSONObject().put("status", 2));
        call(service, "failed", new Class<?>[] {String.class, boolean.class}, "MQTT connection lost.", false);
        boolean alerted = false;
        for (android.service.notification.StatusBarNotification n : app.getSystemService(android.app.NotificationManager.class).getActiveNotifications())
            if (String.valueOf(n.getNotification().extras.getCharSequence(Notification.EXTRA_TEXT)).contains("Lost contact with the printer during a print")) alerted = true;
        assertTrue(alerted);
    }

    @Test public void backWhileSlicingAsksFirstAndOtherwiseLeaves() throws Exception {
        SliceActivity activity = Robolectric.buildActivity(SliceActivity.class).setup().get();
        java.lang.reflect.Field slicing = SliceActivity.class.getDeclaredField("slicing"); slicing.setAccessible(true);
        try {
            slicing.set(null, true);
            activity.leave();
            android.app.AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull(dialog); assertTrue(dialog.isShowing());
            assertFalse("the slice is not abandoned by a single Back", activity.isFinishing());
            dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick();
            assertFalse(activity.isFinishing());
            slicing.set(null, false);
            activity.leave();
            assertTrue(activity.isFinishing());
        } finally { slicing.set(null, false); }
    }

    @Test public void slicesAreKeptOutsideTheCacheNewestFirstAndTrimmed() throws Exception {
        android.content.Context context = RuntimeEnvironment.getApplication();
        for (int i = 0; i < SliceStore.KEEP + 3; i++) {
            File cached = new File(context.getCacheDir(), "sliced/plate" + i + ".gcode"); cached.getParentFile().mkdirs();
            Files.write(cached.toPath(), ("G28 ; " + i + "\n").getBytes(StandardCharsets.UTF_8));
            File kept = SliceStore.keep(context, cached, "plate" + i + ".gcode");
            kept.setLastModified(1_000_000L + i * 1000L);
            assertFalse("moved out of the cache", cached.exists());
            assertTrue(kept.getAbsolutePath().startsWith(context.getFilesDir().getAbsolutePath()));
        }
        java.util.List<File> listed = SliceStore.list(context);
        assertEquals(SliceStore.KEEP, listed.size());
        assertEquals("plate" + (SliceStore.KEEP + 2) + ".gcode", listed.get(0).getName());
    }

    @Test public void startUpSweepsOldSlicesAndUnfinishedTimelapseParts() throws Exception {
        android.content.Context context = RuntimeEnvironment.getApplication();
        File oldSlice = new File(context.getCacheDir(), "sliced/old.gcode"), freshSlice = new File(context.getCacheDir(), "sliced/fresh.gcode"), part = new File(context.getCacheDir(), "timelapse/download-1.part"), video = new File(context.getCacheDir(), "timelapse/done.mp4");
        for (File f : new File[] {oldSlice, freshSlice, part, video}) { f.getParentFile().mkdirs(); Files.write(f.toPath(), new byte[] {1}); }
        oldSlice.setLastModified(System.currentTimeMillis() - 2 * 3_600_000L);
        SliceStore.sweepCache(context);
        assertFalse(oldSlice.exists()); assertTrue("a slice that may be running is left", freshSlice.exists());
        assertFalse(part.exists()); assertTrue(video.exists());
    }

    /** Several plates are sent; the first fails. The failed one goes to the Files tab, and every plate is still listed under Recent slices. */
    @Test public void everyPlateStaysReachableAfterAFailedUpload() throws Exception {
        PrinterService service = Robolectric.buildService(PrinterService.class).create().get();
        android.content.Context context = RuntimeEnvironment.getApplication();
        File[] plates = new File[3];
        for (int i = 0; i < 3; i++) {
            File cached = new File(context.getCacheDir(), "sliced/p" + i + ".gcode"); cached.getParentFile().mkdirs();
            Files.write(cached.toPath(), "G28\n".getBytes(StandardCharsets.UTF_8)); plates[i] = SliceStore.keep(context, cached, "p" + i + ".gcode");
        }
        set(service, "queuedFile", plates[0]); set(service, "queuedName", "p0.gcode");
        @SuppressWarnings("unchecked") java.util.List<Object[]> queue = (java.util.List<Object[]>) get(service, "uploadQueue");
        queue.add(new Object[] {plates[1], "p1.gcode"}); queue.add(new Object[] {plates[2], "p2.gcode"});
        call(service, "failed", new Class<?>[] {String.class, boolean.class}, "Wi-Fi lost", false);
        waitFor(() -> service.selectedFile != null);
        assertEquals("p0.gcode", service.selectedName);
        assertTrue(service.feedback, service.feedback.contains("2 more file(s) were not sent") && service.feedback.contains("Recent slices"));
        for (File plate : plates) assertTrue(plate.getName(), SliceStore.list(context).contains(plate) && plate.isFile());
    }

    /** The printer is printing a.gcode: uploading a file of that name is refused; other names and an idle printer are fine. */
    @Test public void aFileBeingPrintedCannotBeReplaced() throws Exception {
        PrinterService service = Robolectric.buildService(PrinterService.class).create().get();
        set(service, "cloudSignedIn", true); set(service, "cloudSerial", "F01PLA1234567890");
        set(service, "cloudStatus", new JSONObject().put("machine_status", new JSONObject().put("status", 2))
            .put("print_status", new JSONObject().put("filename", "a.gcode")));
        assertTrue(service.replacesActivePrint("a.gcode")); assertTrue(service.replacesActivePrint("/a.gcode"));
        assertFalse(service.replacesActivePrint("b.gcode"));
        set(service, "cloudStatus", new JSONObject().put("machine_status", new JSONObject().put("status", 1)).put("print_status", new JSONObject().put("filename", "a.gcode")));
        assertFalse("an idle printer is not printing it", service.replacesActivePrint("a.gcode"));
    }
}
