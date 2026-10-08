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

/**
 * Failure audit, cloud upload: what CloudUpload does when storage hangs, when another cloud command is pending, and when the
 * Agora link ends. Real CloudUpload and real CloudControl, with fake storage and a fake Agora link.
 */
public class FailureCloudUploadTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    // As in PrinterService: one scheduler for CloudControl and CloudUpload (cloudWorker), one single-thread executor for blocking work (files).
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService blocking = Executors.newSingleThreadExecutor();
    @After public void stop() { worker.shutdownNow(); blocking.shutdownNow(); }

    private static final String SERIAL = "F01PLA1234567890", ENTRY = "https://oss.example/put?signature=abc", ACCESS = "https://oss.example/object.gcode?sig=x";

    private static CloudApi api() {
        return new CloudApi(false, new CloudLogin.Account("1234567890123", "Maker", "token", "refresh", 0, 0), "agent",
            (method, url, headers, body) -> new CloudApi.Response(200, "{\"code\":0,\"data\":{\"entrypoint\":\"" + ENTRY + "\",\"accessUrl\":\"" + ACCESS + "\",\"objectName\":\"gcode/x\",\"expireTime\":1}}"));
    }
    private File gcode() throws IOException {
        File file = folder.newFile("part.gcode"); Files.write(file.toPath(), "G28\nG1 X10\n".getBytes(StandardCharsets.UTF_8)); return file;
    }
    private static final class Outcome implements CloudUpload.Listener {
        final BlockingQueue<String> result = new LinkedBlockingQueue<>();
        public void progress(int percent) { }
        final List<String> stored = new CopyOnWriteArrayList<>();
        public void stored(String objectName) { stored.add(objectName); }
        public void finished(boolean done, String message) { result.add((done ? "done: " : "failed: ") + message); }
    }

    /** Fake Agora link that acknowledges every request except those whose method is in `silent`. */
    private static final class FakeLink implements CloudControl.Link {
        final List<Integer> methods = new CopyOnWriteArrayList<>();
        final Map<Integer, Integer> ids = new ConcurrentHashMap<>();
        final Set<Integer> silent = ConcurrentHashMap.newKeySet();
        volatile Events events;
        public void login(String appId, String rtmUserId, String token, Events events) { this.events = events; }
        public void subscribe(String channel) { }
        public void publish(String channel, String text) {
            try {
                JSONObject request = new JSONObject(text); int method = request.getInt("method"); methods.add(method); ids.put(method, request.getInt("id"));
                if (silent.contains(method)) return;
                events.message(channel, new JSONObject().put("id", request.getInt("id")).put("method", method).put("result", new JSONObject().put("error_code", 0)).toString());
            } catch (Exception error) { throw new AssertionError(error); }
        }
        public void close() { }
    }
    private final List<FakeLink> links = new CopyOnWriteArrayList<>();
    private CloudControl control(Integer... silentMethods) {
        return new CloudControl(() -> new CloudApi.AgoraCredential("12345", "rtm", "tok"),
            () -> { FakeLink link = new FakeLink(); link.silent.addAll(Arrays.asList(silentMethods)); links.add(link); return link; }, worker, 30_000, 60_000);
    }
    private CloudUpload.Commands via(CloudControl control) {
        return new CloudUpload.Commands() {
            public void send(JSONObject request, CloudControl.Reply reply) { control.send(SERIAL, request, reply); }
            public void watch(CloudControl.Transfers listener) { control.watchTransfers(SERIAL, listener); }
            public boolean linkEnded() { return !control.endedReason().isEmpty(); }
        };
    }
    private static boolean eventually(java.util.function.BooleanSupplier condition, long ms) throws Exception {
        for (long waited = 0; waited < ms; waited += 25) { if (condition.getAsBoolean()) return true; Thread.sleep(25); }
        return condition.getAsBoolean();
    }

    /**
     * The storage PUT has no stall watchdog: restartStall() first runs in fetch(). A PUT whose socket write blocks (Wi-Fi gone; Java
     * socket writes have no timeout) shows a frozen percentage until the kernel gives up.
     */
    @Test public void aHungStoragePutIsEndedByTheStallTimer() throws Exception {
        CountDownLatch release = new CountDownLatch(1); Outcome outcome = new Outcome();
        new CloudUpload(api(), (url, headers, file, progress) -> { progress.update(30); try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { throw new IOException(e); } return 200; },
            via(control()), blocking, worker, SERIAL, gcode(), "part.gcode", outcome, 300, 10).start();
        try { assertNotNull("the user must hear within about the stall time", outcome.result.poll(1500, TimeUnit.MILLISECONDS)); } finally { release.countDown(); }
    }

    /**
     * Cancel reports "Upload cancelled." at once, but a PUT stuck in a socket write cannot see the flag, and CloudApi.put never
     * disconnects the connection. PrinterService passes its single-thread `files` executor as the blocking executor, so choosing
     * or inspecting a G-code file afterwards waits behind the stuck PUT.
     */
    @Test public void cancellingAHungPutFreesTheFileExecutor() throws Exception {
        CountDownLatch release = new CountDownLatch(1), putting = new CountDownLatch(1); Outcome outcome = new Outcome();
        CloudUpload upload = new CloudUpload(api(), (url, headers, file, progress) -> { putting.countDown(); try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { throw new java.io.InterruptedIOException(); } return 200; },
            via(control()), blocking, worker, SERIAL, gcode(), "part.gcode", outcome);
        try {
            upload.start(); assertTrue(putting.await(5, TimeUnit.SECONDS));
            upload.cancel();
            assertEquals("failed: Upload cancelled.", outcome.result.poll(2, TimeUnit.SECONDS));
            Future<String> next = blocking.submit(() -> "ran");
            assertEquals("a later file operation should run promptly", "ran", next.get(1, TimeUnit.SECONDS));
        } finally { release.countDown(); }
    }

    /**
     * The whole file is in Elegoo's storage; then some other cloud command (a Refresh, a thumbnail, a file list) is still waiting for
     * its reply when the 1057 fetch request is made. The fetch waits its turn instead of being refused, and nothing already sent is sent again.
     */
    @Test public void aPendingCloudCommandDoesNotFailAFinishedStoragePut() throws Exception {
        CloudControl control = control(Cc2Codec.STATUS); // the printer answers the status read late, so it is pending when the upload needs to send
        control.send(SERIAL, Cc2Codec.request(0, Cc2Codec.STATUS), (acknowledged, message) -> { });
        assertTrue(eventually(() -> !links.isEmpty() && links.get(0).methods.contains(Cc2Codec.STATUS), 3000));
        Outcome outcome = new Outcome();
        new CloudUpload(api(), (url, headers, file, progress) -> 200, via(control), blocking, worker, SERIAL, gcode(), "part.gcode", outcome, 5_000, 10).start();
        Thread.sleep(300);
        assertFalse("nothing is sent while the status read is pending", links.get(0).methods.contains(Cc2Codec.FETCH));
        FakeLink link = links.get(0);
        link.events.message("12345" + SERIAL, new JSONObject().put("id", link.ids.get(Cc2Codec.STATUS)).put("method", Cc2Codec.STATUS).put("result", new JSONObject().put("error_code", 0)).toString());
        assertTrue("the fetch request goes out right after", eventually(() -> link.methods.contains(Cc2Codec.FETCH), 3000));
        assertEquals("the status read was sent once", 1, Collections.frequency(link.methods, Cc2Codec.STATUS));
        assertNull("the upload has not failed", outcome.result.peek());
    }

    /** More waiting commands than the queue holds are refused, and the refusal says they were not sent. */
    @Test public void aFullQueueRefusesNewCommandsAsNotSent() throws Exception {
        CloudControl control = control(Cc2Codec.STATUS);
        BlockingQueue<String> replies = new LinkedBlockingQueue<>();
        control.send(SERIAL, Cc2Codec.request(0, Cc2Codec.STATUS), (acknowledged, message) -> replies.add("first: " + message));
        assertTrue(eventually(() -> !links.isEmpty() && links.get(0).methods.contains(Cc2Codec.STATUS), 3000));
        for (int i = 0; i < CloudControl.MAX_WAITING + 1; i++) control.send(SERIAL, Cc2Codec.request(0, Cc2Codec.DISK), (acknowledged, message) -> replies.add("queued: " + message));
        String refused = replies.poll(2, TimeUnit.SECONDS);
        assertNotNull(refused);
        assertTrue(refused, refused.startsWith("queued: Waiting for the previous cloud command") && refused.contains("not sent"));
        assertEquals("pending plus three waiting plus the refused one were all counted until answered", CloudControl.MAX_WAITING + 1, control.outstanding());
    }

    /**
     * ElegooSlicer logs in with the same identity: Agora ends this app's link while the printer is fetching. CloudControl only
     * finishes a pending reply; the upload (which watches 6006 reports) is never told, so it waits for the full stall time and then
     * reports "stopped reporting" without the real reason.
     */
    @Test public void linkEndedDuringTheFetchIsReportedAtOnceWithItsReason() throws Exception {
        CloudControl control = control(); Outcome outcome = new Outcome();
        new CloudUpload(api(), (url, headers, file, progress) -> 200, via(control), blocking, worker, SERIAL, gcode(), "part.gcode", outcome, 3_000, 10).start();
        assertTrue(eventually(() -> !links.isEmpty() && links.get(0).methods.contains(Cc2Codec.FETCH), 3000));
        links.get(0).events.ended("Another Elegoo app (such as ElegooSlicer) signed in to cloud control with this account, so this app's cloud control session ended.");
        String text = outcome.result.poll(800, TimeUnit.MILLISECONDS);
        assertNotNull("should not wait for the 3 s stall timer", text);
        assertTrue(text, text.contains("signed in"));
    }

    /** Same event; then the stall handler's cancelFetch() calls CloudControl.send(), which logs in again and so signs ElegooSlicer out. */
    @Test public void aStallAfterTheLinkEndedDoesNotLogInAgain() throws Exception {
        CloudControl control = control(); Outcome outcome = new Outcome();
        new CloudUpload(api(), (url, headers, file, progress) -> 200, via(control), blocking, worker, SERIAL, gcode(), "part.gcode", outcome, 400, 10).start();
        assertTrue(eventually(() -> !links.isEmpty() && links.get(0).methods.contains(Cc2Codec.FETCH), 3000));
        links.get(0).events.ended("Another Elegoo app signed in.");
        assertNotNull(outcome.result.poll(3, TimeUnit.SECONDS));
        Thread.sleep(200);
        assertEquals("one Agora login only", 1, links.size());
    }

    /** Works well: no 6006 reports at all (printer or its route gone) ends the upload after the stall time, sends one cancel, and never repeats the fetch. */
    @Test public void noTransferReportsEndsWithOneCancelAndNoSecondFetch() throws Exception {
        CloudControl control = control(); Outcome outcome = new Outcome();
        new CloudUpload(api(), (url, headers, file, progress) -> 200, via(control), blocking, worker, SERIAL, gcode(), "part.gcode", outcome, 300, 10).start();
        String text = outcome.result.poll(3, TimeUnit.SECONDS);
        assertNotNull(text);
        assertTrue(text, text.startsWith("failed: The printer stopped reporting the transfer of part.gcode"));
        Thread.sleep(300);
        List<Integer> methods = links.get(0).methods;
        assertEquals(1, Collections.frequency(methods, Cc2Codec.FETCH));
        assertEquals("the pre-fetch clear and the stall cancel", 2, Collections.frequency(methods, Cc2Codec.FETCH_CANCEL));
        assertTrue("the 1058 cancel is sent but the message says to check whether the file arrived", text.contains("Refresh Files"));
    }

    /** A finished upload reports where the file lives in Elegoo's storage (the object name only, never the link), so it can be fetched back. */
    @Test public void aFinishedUploadReportsOnlyTheObjectName() throws Exception {
        CloudControl control = control(); Outcome outcome = new Outcome();
        new CloudUpload(api(), (url, headers, file, progress) -> 200, via(control), blocking, worker, SERIAL, gcode(), "part.gcode", outcome, 5_000, 10).start();
        assertTrue(eventually(() -> !links.isEmpty() && links.get(0).methods.contains(Cc2Codec.FETCH), 3000));
        FakeLink link = links.get(0);
        link.events.message("12345" + SERIAL, new JSONObject().put("method", Cc2Codec.FETCH_STATUS).put("result", new JSONObject().put("taskID", SERIAL).put("progress", 100).put("status", 1)).toString());
        assertTrue(outcome.result.poll(3, TimeUnit.SECONDS).startsWith("done:"));
        assertEquals(Collections.singletonList("gcode/x"), outcome.stored);
        assertFalse(outcome.stored.toString().contains("https"));
    }
}
