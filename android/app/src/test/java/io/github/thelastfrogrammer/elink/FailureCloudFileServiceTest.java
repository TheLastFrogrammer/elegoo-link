package io.github.thelastfrogrammer.elink;

import android.os.Looper;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/** Route choice for downloading a printer file when not connected locally: cloud first when Elegoo's storage is known to hold it. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 34, qualifiers = "w411dp-h891dp-xxhdpi")
public class FailureCloudFileServiceTest {
    private static final String SERIAL = "F01PLA1234567890";
    private static void set(Object target, String name, Object value) throws Exception { java.lang.reflect.Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); f.set(target, value); }
    private static void waitFor(java.util.concurrent.Callable<Boolean> condition) throws Exception {
        long end = System.currentTimeMillis() + 20_000;
        while (true) { org.robolectric.shadows.ShadowLooper.idleMainLooper(); if (condition.call()) return; if (System.currentTimeMillis() > end) fail("timed out"); Thread.sleep(25); }
    }
    private static HttpURLConnection answer(URL url, int status, byte[] body) {
        return new HttpURLConnection(url) {
            public void connect() { }
            public void disconnect() { }
            public boolean usingProxy() { return false; }
            public int getResponseCode() { return status; }
            public long getContentLengthLong() { return body.length; }
            public String getContentType() { return "application/octet-stream"; }
            public InputStream getInputStream() { return new ByteArrayInputStream(body); }
        };
    }

    /** A service watching through the cloud, with a fake cloud: `record` is the file-record body, links go to https://oss.example.com. */
    private PrinterService service(String recordBody, AtomicInteger recordCalls, PrinterHttp.ConnectionFactory storage) throws Exception {
        PrinterService service = Robolectric.buildService(PrinterService.class).create().get();
        CloudApi api = new CloudApi(false, new CloudLogin.Account("42", "Maker", "token", "refresh", 0, 0), "agent", (method, url, headers, body) -> {
            if (url.contains("local-file/filename")) { recordCalls.incrementAndGet(); return new CloudApi.Response(200, recordBody); }
            if (url.contains("local-file/page")) return new CloudApi.Response(200, "{\"code\":0,\"data\":{\"total\":0,\"list\":[]}}");
            if (url.contains("generate-pre-access-url")) return new CloudApi.Response(200, "{\"code\":0,\"data\":{\"accessUrl\":\"https://oss.example.com/o?sig=1\"}}");
            return new CloudApi.Response(404, "{}");
        });
        set(service, "cloudApi", api); set(service, "cloudSignedIn", true); set(service, "cloudSerial", SERIAL); set(service, "cloudConnections", storage);
        return service;
    }
    private static CloudFileMemory memory(PrinterService service) throws Exception { java.lang.reflect.Field f = PrinterService.class.getDeclaredField("cloudFiles"); f.setAccessible(true); return (CloudFileMemory) f.get(service); }

    @Test public void theMemoryKeepsObjectNamesPerPrinterAndNeverALink() throws Exception {
        CloudFileMemory memory = new CloudFileMemory(RuntimeEnvironment.getApplication());
        memory.remember(SERIAL, "a.gcode", "gcode/obj-a.gcode");
        assertEquals("gcode/obj-a.gcode", new CloudFileMemory(RuntimeEnvironment.getApplication()).objectNameFor(SERIAL, "a.gcode"));
        assertEquals("", memory.objectNameFor("OTHERPRINTER00001", "a.gcode"));
        assertEquals("", memory.objectNameFor(SERIAL, "b.gcode"));
        String stored = RuntimeEnvironment.getApplication().getSharedPreferences("cloud-files", 0).getString("files", "");
        assertFalse(stored, stored.contains("http"));
        memory.forget(SERIAL, "a.gcode"); assertEquals("", memory.objectNameFor(SERIAL, "a.gcode"));
        for (int i = 0; i < CloudFileMemory.KEEP + 5; i++) memory.remember(SERIAL, "f" + i + ".gcode", "gcode/o" + i);
        assertEquals("", memory.objectNameFor(SERIAL, "f0.gcode")); assertEquals("gcode/o" + (CloudFileMemory.KEEP + 4), memory.objectNameFor(SERIAL, "f" + (CloudFileMemory.KEEP + 4) + ".gcode"));
    }

    @Test public void ourOwnUploadIsAskedForFirstWithoutLookingAtTheCloudRecord() throws Exception {
        CloudFileMemory memory = new CloudFileMemory(RuntimeEnvironment.getApplication());
        memory.remember(SERIAL, "known.gcode", "gcode/known.gcode");
        AtomicInteger calls = new AtomicInteger();
        CloudFileRoute.Choice choice = CloudFileRoute.choose(memory, SERIAL, "known.gcode", (serial, name) -> { calls.incrementAndGet(); return new JSONObject(); });
        assertEquals("gcode/known.gcode", choice.objectName); assertEquals(0, calls.get());
        CloudFileRoute.Choice fromRecord = CloudFileRoute.choose(memory, SERIAL, "other.gcode", (serial, name) -> new JSONObject().put("gcodeObject", "gcode/rec.gcode"));
        assertEquals("gcode/rec.gcode", fromRecord.objectName);
        assertNull(CloudFileRoute.choose(memory, SERIAL, "none.gcode", (serial, name) -> new JSONObject().put("filename", "none.gcode").put("thumbnail", "t/x.png")));
        assertNull("a failing record request means unknown, not an error", CloudFileRoute.choose(memory, SERIAL, "none.gcode", (serial, name) -> { throw new IOException("offline"); }));
    }

    @Test public void aKnownFileComesThroughTheCloudAndTheWifiRouteIsNotTried() throws Exception {
        AtomicInteger recordCalls = new AtomicInteger();
        PrinterService service = service("{\"code\":0,\"data\":{}}", recordCalls, url -> answer(url, 200, "; sliced\nG28\nG1 X10\n".getBytes(StandardCharsets.UTF_8)));
        memory(service).remember(SERIAL, "known.gcode", "gcode/known.gcode");
        service.download("local", "known.gcode");
        waitFor(() -> service.selectedFile != null);
        assertEquals("known.gcode", service.selectedName);
        assertEquals("the record is not even requested", 0, recordCalls.get());
        String log = Diagnostics.report("");
        assertTrue(log, log.contains("downloading through the Elegoo cloud from oss.example.com"));
        assertFalse("no Wi-Fi search was needed", log.contains("direct download from"));
    }

    @Test public void anUnknownFileFallsBackToTheWifiRouteAndSaysThePlainThing() throws Exception {
        AtomicInteger recordCalls = new AtomicInteger();
        PrinterService service = service("{\"code\":0,\"data\":{\"filename\":\"mystery.gcode\",\"thumbnail\":\"t/x.png\"}}", recordCalls, url -> { fail("nothing to fetch from the cloud"); return null; });
        service.download("local", "mystery.gcode");
        waitFor(() -> service.feedback.contains(CloudFileRoute.NO_COPY));
        assertEquals(1, recordCalls.get());
        assertTrue(service.feedback, service.feedback.contains("Wi-Fi"));
        assertNull(service.selectedFile);
        String log = Diagnostics.report("");
        assertTrue("the owner's next report shows what the record holds", log.contains("cloud file record fields: filename:string, thumbnail:object-name-like"));
        assertTrue(log.contains("cloud file list:"));
    }

    @Test public void aCloudCopyThatCannotBeFetchedFallsBackAndNamesTheCloudFailure() throws Exception {
        AtomicInteger recordCalls = new AtomicInteger();
        PrinterService service = service("{\"code\":0,\"data\":{}}", recordCalls, url -> answer(url, 404, new byte[0]));
        memory(service).remember(SERIAL, "gone.gcode", "gcode/gone.gcode");
        service.download("local", "gone.gcode");
        waitFor(() -> service.feedback.contains("cloud route failed too"));
        assertTrue(service.feedback, service.feedback.contains("no longer has this file"));
        assertFalse(service.feedback.contains(CloudFileRoute.NO_COPY));
    }
}
