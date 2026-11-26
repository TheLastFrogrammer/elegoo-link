package io.github.thelastfrogrammer.elink;

import android.os.Handler;
import java.lang.reflect.Field;
import org.json.JSONObject;
import org.junit.*;
import static org.junit.Assert.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;

/** When the Bed mesh screen may ask the printer to level, through the cloud: never without the live updates the map needs. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class LevelForMeshTest {
    private ServiceController<PrinterService> controller;
    private PrinterService service;
    private static Field field(Object o, String name) throws Exception { Field f = o.getClass().getDeclaredField(name); f.setAccessible(true); return f; }
    private static void set(Object o, String name, Object value) throws Exception { field(o, name).set(o, value); }

    @Before public void open() throws Exception {
        controller = Robolectric.buildService(PrinterService.class).create(); service = controller.get();
        ((Handler) field(service, "main").get(service)).removeCallbacksAndMessages(null);   // no real cloud polls
        set(service, "cloudSignedIn", true); set(service, "cloudSerial", "CC2-A"); set(service, "cloudOnline", 1); set(service, "cloudOnlineSignal", true);
        idle(1);
    }
    @After public void close() { if (controller != null) controller.destroy(); }
    /** A fresh cloud status with the machine in `state` (1 idle). */
    private void idle(int state) throws Exception {
        set(service, "cloudStatus", new JSONObject().put("machine_status", new JSONObject().put("status", state)));
        set(service, "cloudCheckedAt", System.currentTimeMillis());
    }

    @Test public void throughTheCloudItWaitsForLiveUpdates() throws Exception {
        service.cloudLiveOn = false;
        assertTrue(service.levelBlocked(), service.levelBlocked().contains("live updates"));
        service.cloudLiveOn = true;
        assertEquals("", service.levelBlocked());
    }

    @Test public void onlyAnIdlePrinterWithFreshStatus() throws Exception {
        service.cloudLiveOn = true;
        idle(2);   // printing
        assertTrue(service.levelBlocked(), service.levelBlocked().contains("idle"));
        idle(1); set(service, "cloudCheckedAt", 0L);   // no fresh status
        assertTrue(service.levelBlocked(), service.levelBlocked().contains("No live status"));
    }

    @Test public void whileThePrinterPreparesToLevelItSaysSo() throws Exception {
        service.cloudLiveOn = true;
        idle(5);   // levelling: heating the bed and homing before it probes
        assertTrue(service.levelBlocked(), service.levelBlocked().contains("getting ready"));
    }

    @Test public void askedOnceItWaitsInsteadOfAskingAgain() throws Exception {
        service.cloudLiveOn = true;
        set(service, "levelAskedAt", System.currentTimeMillis());
        assertTrue(service.levelBlocked(), service.levelBlocked().contains("just asked"));
        set(service, "levelAskedAt", System.currentTimeMillis() - PrinterService.LEVEL_GUARD_MS - 1);
        assertEquals("", service.levelBlocked());
    }

    @Test public void notWhileALevellingIsBeingCaptured() throws Exception {
        service.cloudLiveOn = true;
        CloudBedMesh capture = (CloudBedMesh) field(service, "cloudBedMesh").get(service);
        capture.delta("CC2-A", new JSONObject().put("machine_status", new JSONObject().put("status", 5).put("sub_status", BedMesh.PROBING)), System.currentTimeMillis());
        assertTrue(capture.active());
        assertTrue(service.levelBlocked(), service.levelBlocked().contains("levelling now"));
    }
}
