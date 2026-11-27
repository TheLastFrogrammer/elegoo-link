package io.github.thelastfrogrammer.elink;

import android.os.Handler;
import android.os.SystemClock;
import java.lang.reflect.Field;
import org.json.JSONObject;
import org.junit.*;
import static org.junit.Assert.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

/** Asking the printer for its camera before a cloud video joins: only when agreed, never more than once in the gap, and the video always goes on. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class CloudCameraWakeTest {
    private ServiceController<PrinterService> controller;
    private PrinterService service;
    private static Field field(Object o, String name) throws Exception { Field f = o.getClass().getDeclaredField(name); f.setAccessible(true); return f; }
    private static void set(Object o, String name, Object value) throws Exception { field(o, name).set(o, value); }

    @Before public void open() throws Exception {
        controller = Robolectric.buildService(PrinterService.class).create(); service = controller.get();
        ((Handler) field(service, "main").get(service)).removeCallbacksAndMessages(null);
        set(service, "cloudSignedIn", true); set(service, "cloudSerial", "CC2-A"); set(service, "cloudOnline", 1); set(service, "cloudOnlineSignal", true);
        set(service, "cloudStatus", new JSONObject().put("machine_status", new JSONObject().put("status", 1)));
        set(service, "cloudCheckedAt", System.currentTimeMillis());
    }
    @After public void close() { if (controller != null) controller.destroy(); }
    private void agreed(boolean yes) { service.getSharedPreferences("workshop-settings", 0).edit().putBoolean(CloudConsent.KEY, yes).commit(); }

    @Test public void withoutTheCloudAgreementNothingIsAskedAndTheVideoGoesOn() throws Exception {
        agreed(false);
        int[] ran = {0};
        service.wakeCloudCamera(() -> ran[0]++);
        ShadowLooper.idleMainLooper();
        assertEquals(1, ran[0]);
        assertEquals(0L, field(service, "cameraAskedAt").get(service));
    }

    @Test public void askedMomentsAgoItIsNotAskedAgain() throws Exception {
        agreed(true);
        long asked = SystemClock.elapsedRealtime() - 10_000;
        set(service, "cameraAskedAt", asked);
        int[] ran = {0};
        service.wakeCloudCamera(() -> ran[0]++);
        ShadowLooper.idleMainLooper();
        assertEquals(1, ran[0]);
        assertEquals("not asked again within the gap", asked, field(service, "cameraAskedAt").get(service));
    }
}
