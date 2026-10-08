package io.github.thelastfrogrammer.elink;

import android.app.Notification;
import android.os.Looper;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
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
        while (true) { Shadows.shadowOf(Looper.getMainLooper()).idle(); if (condition.call()) return; if (System.currentTimeMillis() > end) fail("timed out"); Thread.sleep(25); }
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
        Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS);
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
}
