package io.github.thelastfrogrammer.elink;

import android.content.Intent;
import android.os.Handler;
import android.os.SystemClock;
import android.view.View;
import android.widget.TextView;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.*;
import org.json.JSONObject;
import org.junit.*;
import static org.junit.Assert.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

/** Exercises the actual viewer's file gate and worker result guards with a local, synthetic status source. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ViewerFileIdentityTest {
    private ActivityController<GcodeViewerActivity> screen;
    private ServiceController<PrinterService> serviceController;
    private GcodeViewerActivity activity;
    private PrinterService service;
    private GcodeLibrary library;
    private GcodeJobTracker tracker;
    private GcodeLibrary.Artifact artifact;
    private static Object get(Object instance, String name) throws Exception { Field f = instance.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(instance); }
    private static void set(Object instance, String name, Object value) throws Exception { Field f = instance.getClass().getDeclaredField(name); f.setAccessible(true); f.set(instance, value); }

    @Before public void open() throws Exception {
        serviceController = Robolectric.buildService(PrinterService.class).create(); service = serviceController.get();
        ((Handler) get(service, "main")).removeCallbacksAndMessages(null);
        set(service, "cloudSignedIn", true); set(service, "cloudSerial", "CC2-A"); set(service, "cloudOnline", 1); set(service, "cloudOnlineSignal", true);
        tracker = (GcodeJobTracker) get(service, "fileJobs");
        library = new GcodeLibrary(Files.createTempDirectory("viewer-artifacts").toFile());
        File source = File.createTempFile("toolpath-", ".gcode");
        try (java.io.InputStream input = getClass().getResourceAsStream("/gcode/tolerance-cc2.gcode")) { Files.copy(input, source.toPath(), StandardCopyOption.REPLACE_EXISTING); }
        artifact = library.retain(source, "part.gcode", "CC2-A", GcodeLibrary.Origin.UPLOADED);
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).setComponentNameAndServiceForBindService(
            new android.content.ComponentName(RuntimeEnvironment.getApplication(), PrinterService.class), service.onBind(null));
        screen = Robolectric.buildActivity(GcodeViewerActivity.class, new Intent(RuntimeEnvironment.getApplication(), GcodeViewerActivity.class).putExtra(GcodeViewerActivity.EXTRA_FOLLOW, true)).setup();
        activity = screen.get(); set(activity, "printer", service); set(activity, "library", library);
        report("CC2-A", 2, 10, 100); waitForWorker();
    }
    @After public void close() { if (screen != null) screen.pause().stop().destroy(); if (serviceController != null) serviceController.destroy(); }
    private void report(String printer, int state, int progress, long seconds) throws Exception {
        JSONObject value = new JSONObject().put("machine_status", new JSONObject().put("status", state).put("progress", progress))
            .put("print_status", new JSONObject().put("filename", "part.gcode").put("uuid", "same-file-uuid").put("print_duration", seconds).put("current_layer", 1));
        set(service, "cloudSignedIn", true); set(service, "cloudOnline", 1); set(service, "cloudOnlineSignal", true);
        set(service, "cloudSerial", printer); set(service, "cloudStatus", value); set(service, "cloudCheckedAt", System.currentTimeMillis());
        tracker.update(printer, "test-route", value, SystemClock.elapsedRealtime()); activity.changed();
    }
    private void waitForWorker() throws Exception {
        for (int i = 0; i < 3; i++) {
            ((ExecutorService) get(activity, "worker")).submit(() -> { }).get(15, TimeUnit.SECONDS);
            ShadowLooper.idleMainLooper();
        }
    }
    private void selectCurrent() throws Exception {
        GcodeJobTracker.Job job = service.fileJob(); library.selectForJob(artifact, job.printer, job.token); activity.changed(); waitForWorker();
    }
    @Test public void aSameNameRetainedCopyIsOfferedButNeverLoadedAutomatically() throws Exception {
        assertEquals(1, library.candidates("CC2-A", "part.gcode").size());
        assertNull(get(activity, "path")); assertEquals(View.VISIBLE, ((View) get(activity, "missingCard")).getVisibility());
    }
    @Test public void explicitChoiceLoadsWithItsProvenanceButANewPrintClearsIt() throws Exception {
        selectCurrent(); assertNotNull(get(activity, "path"));
        assertTrue(((TextView) get(activity, "fileSource")).getText().toString().contains("Selected by you"));
        ((View) get(activity, "details")).setVisibility(View.VISIBLE);
        report("CC2-A", 1, 100, 200); assertNull(get(activity, "path"));
        assertEquals(View.GONE, ((View) get(activity, "details")).getVisibility()); assertEquals("", activity.detailsText());
        report("CC2-A", 2, 0, 0); assertNull("same name and firmware UUID cannot restore the previous print's file", get(activity, "path"));
        assertEquals(View.INVISIBLE, ((View) get(activity, "web")).getVisibility());
    }
    @Test public void anOldWorkerCannotLoadItsFileAfterSwitchingPrinter() throws Exception {
        ExecutorService worker = (ExecutorService) get(activity, "worker"); CountDownLatch release = new CountDownLatch(1);
        worker.submit(() -> { try { release.await(); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); } });
        try {
            GcodeJobTracker.Job job = service.fileJob(); library.selectForJob(artifact, job.printer, job.token); activity.changed();
            report("CC2-B", 2, 10, 100);
        } finally { release.countDown(); }
        waitForWorker(); assertNull(get(activity, "path")); assertNull(get(activity, "loadedArtifact"));
    }
    @Test public void aQueuedWebViewCompletionCannotRevealThePreviousCopy() throws Exception {
        selectCurrent(); long oldTicket = (long) get(activity, "loadGeneration");
        report("CC2-A", 1, 100, 200); report("CC2-A", 2, 0, 0); waitForWorker();
        selectCurrent(); assertTrue((long) get(activity, "loadGeneration") > oldTicket);
        Object bridge = Shadows.shadowOf((android.webkit.WebView) get(activity, "web")).getJavascriptInterface("Android");
        java.lang.reflect.Method loaded = bridge.getClass().getDeclaredMethod("onLoaded", int.class, long.class); loaded.setAccessible(true);
        loaded.invoke(bridge, 100, oldTicket); ShadowLooper.idleMainLooper();
        assertEquals("only the current page load may reveal the scene", View.INVISIBLE, ((View) get(activity, "web")).getVisibility());
        loaded.invoke(bridge, 100, (long) get(activity, "loadGeneration")); ShadowLooper.idleMainLooper();
        assertEquals(View.VISIBLE, ((View) get(activity, "web")).getVisibility());
    }
    @Test public void aLostServiceImmediatelyClearsTheLiveScene() throws Exception {
        selectCurrent();
        ((android.content.ServiceConnection) get(activity, "connection")).onServiceDisconnected(new android.content.ComponentName(activity, PrinterService.class));
        assertNull(get(activity, "path")); assertEquals(View.INVISIBLE, ((View) get(activity, "web")).getVisibility());
    }
    @Test public void anIpOnlyLocalConnectionUsesItsResolvedSessionSerial() throws Exception {
        Cc2Session local = new Cc2Session("192.168.1.50", "code", null);
        set(local, "resolvedSerial", "LOCAL-CC2"); set(local, "ready", true); set(local, "statusAt", System.nanoTime());
        set(service, "session", local); set(service, "wanted", true); set(service, "serial", "");
        JSONObject status = new JSONObject().put("machine_status", new JSONObject().put("status", 2))
            .put("print_status", new JSONObject().put("filename", "part.gcode"));
        set(service, "status", status);
        java.lang.reflect.Method record = PrinterService.class.getDeclaredMethod("record", JSONObject.class, String.class, String.class); record.setAccessible(true);
        record.invoke(service, status, "local", "Test printer");
        assertEquals("LOCAL-CC2", service.filePrinterId()); assertEquals("LOCAL-CC2", service.fileJob().printer);
    }
}
