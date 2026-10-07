package io.github.thelastfrogrammer.elink;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;

public class CloudUploadTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService blocking = Executors.newSingleThreadExecutor();
    @After public void stop() { scheduler.shutdownNow(); blocking.shutdownNow(); }

    private static final String SERIAL = "F01PLA1234567890", ENTRY = "https://oss.example/put?signature=abc", ACCESS = "https://oss.example/object.gcode?sig=x";
    private final List<String> requests = new CopyOnWriteArrayList<>();

    private CloudApi api(boolean grant) {
        return new CloudApi(false, new CloudLogin.Account("1234567890123", "Maker", "token", "refresh", 0, 0), "agent", (method, url, headers, body) -> {
            requests.add(method + " " + url);
            if (!grant) return new CloudApi.Response(200, "{\"code\":0,\"data\":{}}");
            return new CloudApi.Response(200, "{\"code\":0,\"data\":{\"entrypoint\":\"" + ENTRY + "\",\"accessUrl\":\"" + ACCESS + "\",\"objectName\":\"gcode/x\",\"expireTime\":1}}");
        });
    }

    /** Plays the printer: acknowledges commands and records them; transfer reports are pushed by the test. */
    private static final class Printer implements CloudUpload.Commands {
        final BlockingQueue<JSONObject> sent = new LinkedBlockingQueue<>();
        volatile CloudControl.Transfers watcher; volatile boolean refuseFetch;
        public void send(JSONObject request, CloudControl.Reply reply) {
            sent.add(request);
            boolean refuse = refuseFetch && request.optInt("method") == Cc2Codec.FETCH;
            reply.done(!refuse, refuse ? "Printer busy" : "ok");
        }
        public void watch(CloudControl.Transfers listener) { watcher = listener; }
        JSONObject take() throws InterruptedException { JSONObject value = sent.poll(5, TimeUnit.SECONDS); assertNotNull("Expected a command", value); return value; }
        void report(int progress, int status) throws InterruptedException {
            for (int i = 0; i < 100 && watcher == null; i++) Thread.sleep(20);
            assertNotNull("Transfer reports are watched", watcher);
            watcher.status(SERIAL, SERIAL, progress, status);
        }
    }
    private static final class Outcome implements CloudUpload.Listener {
        final List<Integer> progress = new CopyOnWriteArrayList<>();
        final BlockingQueue<String> result = new LinkedBlockingQueue<>();
        public void progress(int percent) { progress.add(percent); }
        public void finished(boolean done, String message) { result.add((done ? "done: " : "failed: ") + message); }
        String take() throws InterruptedException { String value = result.poll(5, TimeUnit.SECONDS); assertNotNull("Expected a result", value); return value; }
    }

    private File gcode() throws IOException {
        File file = folder.newFile("part.gcode"); Files.write(file.toPath(), "G28\nG1 X10\n".getBytes(StandardCharsets.UTF_8)); return file;
    }

    @Test public void storesTheFileThenThePrinterFetchesIt() throws Exception {
        File file = gcode(); String[] sums = CloudApi.md5(file);
        Map<String, String> putHeaders = new ConcurrentHashMap<>(); List<String> puts = new CopyOnWriteArrayList<>();
        Printer printer = new Printer(); Outcome outcome = new Outcome();
        CloudUpload upload = new CloudUpload(api(true), (url, headers, source, progress) -> { puts.add(url); putHeaders.putAll(headers); progress.update(50); progress.update(100); return 200; },
            printer, blocking, scheduler, SERIAL, file, "part.gcode", outcome, 5_000, 10);
        upload.start();

        JSONObject cancel = printer.take();
        assertEquals(Cc2Codec.FETCH_CANCEL, cancel.getInt("method"));
        assertEquals(SERIAL, cancel.getJSONObject("params").getString("taskID"));
        JSONObject fetch = printer.take();
        assertEquals(Cc2Codec.FETCH, fetch.getInt("method"));
        JSONObject params = fetch.getJSONObject("params");
        assertEquals("part.gcode", params.getString("filename"));
        assertEquals(ACCESS, params.getString("url"));
        assertEquals(sums[0], params.getString("md5"));
        assertEquals(SERIAL, params.getString("taskID"));

        // Storage name: last six of account and printer, then the MD5; the base64 MD5 goes to both the request and the PUT.
        String expectedName = "890123_567890_" + sums[0] + ".gcode";
        assertTrue(requests.get(0), requests.get(0).contains("oss/biz-entrypoint?filename=" + expectedName + "&bucketAlias=iot-private&module=gcode&fileMd5="));
        assertEquals(Collections.singletonList(ENTRY), puts);
        assertEquals(sums[1], putHeaders.get("Content-MD5"));
        assertEquals("application/octet-stream", putHeaders.get("Content-Type"));

        printer.report(40, 0);
        printer.report(100, 1);
        assertEquals("done: Uploaded part.gcode through the Elegoo cloud.", outcome.take());
        assertEquals(Arrays.asList(25, 50, 70, 100), outcome.progress);
        assertNull("Stops watching once done", printer.watcher);
    }

    @Test public void reportsPrinterFailureAndRefusals() throws Exception {
        Printer printer = new Printer(); Outcome outcome = new Outcome();
        new CloudUpload(api(true), (url, headers, source, progress) -> 200, printer, blocking, scheduler, SERIAL, gcode(), "part.gcode", outcome, 5_000, 10).start();
        printer.take(); printer.take();
        printer.report(10, 3);
        assertTrue(outcome.take().startsWith("failed: The printer could not fetch part.gcode"));

        Printer refusing = new Printer(); refusing.refuseFetch = true; Outcome refused = new Outcome();
        File other = folder.newFile("other.gcode"); Files.write(other.toPath(), "G28\n".getBytes(StandardCharsets.UTF_8));
        new CloudUpload(api(true), (url, headers, source, progress) -> 200, refusing, blocking, scheduler, SERIAL, other, "other.gcode", refused, 5_000, 10).start();
        assertEquals("failed: The printer did not accept the transfer: Printer busy", refused.take());
    }

    @Test public void storageFailuresNeverReachThePrinter() throws Exception {
        Printer printer = new Printer(); Outcome outcome = new Outcome();
        new CloudUpload(api(false), (url, headers, source, progress) -> 200, printer, blocking, scheduler, SERIAL, gcode(), "part.gcode", outcome).start();
        assertEquals("failed: Elegoo did not provide a place to upload the file.", outcome.take());

        Outcome rejected = new Outcome();
        File other = folder.newFile("b.gcode"); Files.write(other.toPath(), "G28\n".getBytes(StandardCharsets.UTF_8));
        new CloudUpload(api(true), (url, headers, source, progress) -> 403, printer, blocking, scheduler, SERIAL, other, "b.gcode", rejected).start();
        assertEquals("failed: Elegoo's storage refused the file (HTTP 403).", rejected.take());
        assertTrue(printer.sent.isEmpty());

        Outcome invalid = new Outcome();
        new CloudUpload(api(true), (url, headers, source, progress) -> 200, printer, blocking, scheduler, SERIAL, other, "../b.gcode", invalid).start();
        assertTrue(invalid.take().startsWith("failed: Select a G-code filename"));
    }

    @Test public void stalledTransferIsCancelledNotRepeated() throws Exception {
        Printer printer = new Printer(); Outcome outcome = new Outcome();
        new CloudUpload(api(true), (url, headers, source, progress) -> 200, printer, blocking, scheduler, SERIAL, gcode(), "part.gcode", outcome, 200, 10).start();
        printer.take(); assertEquals(Cc2Codec.FETCH, printer.take().getInt("method"));
        assertTrue(outcome.take().startsWith("failed: The printer stopped reporting"));
        assertEquals(Cc2Codec.FETCH_CANCEL, printer.take().getInt("method"));
        assertNull(printer.sent.poll(300, TimeUnit.MILLISECONDS));
    }

    @Test public void cancellingDuringStorageStopsThePut() throws Exception {
        Printer printer = new Printer(); Outcome outcome = new Outcome();
        CountDownLatch putting = new CountDownLatch(1), release = new CountDownLatch(1);
        CloudUpload upload = new CloudUpload(api(true), (url, headers, source, progress) -> {
            putting.countDown(); try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException stop) { throw new java.io.InterruptedIOException(); }
            if (!progress.update(60)) throw new java.io.InterruptedIOException("Upload cancelled.");
            return 200;
        }, printer, blocking, scheduler, SERIAL, gcode(), "part.gcode", outcome);
        upload.start();
        assertTrue(putting.await(5, TimeUnit.SECONDS));
        upload.cancel(); release.countDown();
        assertEquals("failed: Upload cancelled.", outcome.take());
        assertNull(printer.sent.poll(200, TimeUnit.MILLISECONDS));
    }

    @Test public void requestBuildersCheckTheirInput() throws Exception {
        JSONObject fetch = Cc2Codec.fetchRequest(3, "folder/part.gcode", ACCESS, "0123456789abcdef0123456789abcdef", SERIAL);
        assertEquals(1057, fetch.getInt("method"));
        assertEquals(1058, Cc2Codec.fetchCancelRequest(1, SERIAL).getInt("method"));
        for (String url : new String[] {"http://oss.example/x", "https://a b", "", null})
            try { Cc2Codec.fetchRequest(1, "a.gcode", url, "0123456789abcdef0123456789abcdef", SERIAL); fail(url); } catch (IllegalArgumentException expected) { }
        try { Cc2Codec.fetchRequest(1, "a.gcode", ACCESS, "XYZ", SERIAL); fail(); } catch (IllegalArgumentException expected) { }
        try { Cc2Codec.fetchCancelRequest(1, "bad serial"); fail(); } catch (IllegalArgumentException expected) { }
        assertTrue(CloudControl.allowed(Cc2Codec.FETCH)); assertTrue(CloudControl.allowed(Cc2Codec.FETCH_CANCEL));
        assertEquals("ab_cd_0f.3mf", CloudApi.storageName("ab", "cd", "0f", "x.3MF"));
    }

    @Test public void cloudControlPassesTransferReportsFromThePrinterOnly() throws Exception {
        BlockingQueue<String> reports = new LinkedBlockingQueue<>();
        AtomicEvents events = new AtomicEvents();
        CloudControl control = new CloudControl(() -> new CloudApi.AgoraCredential("777", "rtm", "token"), () -> new CloudControl.Link() {
            public void login(String appId, String user, String token, Events e) { events.value = e; }
            public void subscribe(String channel) { }
            public void publish(String channel, String text) { }
            public void close() { }
        }, scheduler, 1_000, 60_000);
        control.watchTransfers(SERIAL, (serial, task, progress, status) -> reports.add(serial + " " + task + " " + progress + " " + status));
        BlockingQueue<String> replies = new LinkedBlockingQueue<>();
        control.send(SERIAL, Cc2Codec.fetchCancelRequest(0, SERIAL), (ok, text) -> replies.add(text));
        for (int i = 0; i < 100 && events.value == null; i++) Thread.sleep(20);
        String report = "{\"method\":6006,\"result\":{\"taskID\":\"" + SERIAL + "\",\"progress\":42,\"status\":0}}";
        events.value.message("777OTHER", report);
        events.value.message("777" + SERIAL, report);
        assertEquals(SERIAL + " " + SERIAL + " 42 0", reports.poll(5, TimeUnit.SECONDS));
        assertNull(reports.poll(100, TimeUnit.MILLISECONDS));
        control.watchTransfers(null, null);
        events.value.message("777" + SERIAL, report);
        assertNull(reports.poll(100, TimeUnit.MILLISECONDS));
        control.close();
    }
    private static final class AtomicEvents { volatile CloudControl.Link.Events value; }
}
