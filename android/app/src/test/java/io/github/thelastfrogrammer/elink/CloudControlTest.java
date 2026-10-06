package io.github.thelastfrogrammer.elink;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

public class CloudControlTest {
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    @After public void stop() { worker.shutdownNow(); }

    /** Fake Agora: records calls and lets the test play the printer's replies. */
    private static final class FakeLink implements CloudControl.Link {
        final List<String> calls = new CopyOnWriteArrayList<>();
        final BlockingQueue<String[]> published = new LinkedBlockingQueue<>();
        volatile Events events; volatile boolean failLogin, closed;
        public void login(String appId, String rtmUserId, String token, Events events) throws IOException {
            calls.add("login " + appId + " " + rtmUserId + " " + token);
            if (failLogin) throw new IOException("Could not sign in to cloud control through the cloud (LOGIN_REJECTED).");
            this.events = events;
        }
        public void subscribe(String channel) { calls.add("subscribe " + channel); }
        public void publish(String channel, String text) { calls.add("publish " + channel); published.add(new String[] {channel, text}); }
        public void close() { closed = true; calls.add("close"); }
    }
    private static final class Replies implements CloudControl.Reply {
        final BlockingQueue<String> results = new LinkedBlockingQueue<>();
        public void done(boolean acknowledged, String message) { results.add((acknowledged ? "ok: " : "fail: ") + message); }
        String take() throws InterruptedException { String value = results.poll(5, TimeUnit.SECONDS); assertNotNull("Expected a reply", value); return value; }
    }
    private final List<FakeLink> links = new CopyOnWriteArrayList<>();
    private int credentialRequests;
    private CloudControl control(long timeoutMs, long idleMs) {
        return new CloudControl(() -> { credentialRequests++; return new CloudApi.AgoraCredential("12345", "rtm-user", "rtm-token"); },
            () -> { FakeLink link = new FakeLink(); links.add(link); return link; }, worker, timeoutMs, idleMs);
    }
    private static String[] take(FakeLink link) throws InterruptedException { String[] value = link.published.poll(5, TimeUnit.SECONDS); assertNotNull("Expected a publish", value); return value; }

    @Test public void pauseGoesToTheUserChannelAndTheMatchingReplyAcknowledges() throws Exception {
        CloudControl control = control(5000, 60000); Replies replies = new Replies();
        control.send("SN818", Cc2Codec.request(99, Cc2Codec.PAUSE), replies);
        String[] sent = take(waitForLink());
        FakeLink link = links.get(0);
        assertEquals(Arrays.asList("login " + CloudControl.AGORA_APP_ID + " rtm-user rtm-token", "subscribe 12345", "publish 12345SN818"), link.calls);
        assertEquals("12345SN818", sent[0]);
        JSONObject request = new JSONObject(sent[1]);
        assertEquals(Cc2Codec.PAUSE, request.getInt("method")); assertNotEquals(99, request.getInt("id"));
        // Replies from another publisher or with another id are ignored.
        link.events.message("12345OTHER", new JSONObject().put("id", request.getInt("id")).put("result", new JSONObject().put("error_code", 0)).toString());
        link.events.message("12345SN818", new JSONObject().put("id", request.getInt("id") + 1).put("result", new JSONObject().put("error_code", 0)).toString());
        link.events.message("12345SN818", "not json");
        link.events.message("12345SN818", new JSONObject().put("id", request.getInt("id")).put("method", Cc2Codec.PAUSE).put("result", new JSONObject().put("error_code", 0)).toString());
        assertTrue(replies.take().startsWith("ok: Printer acknowledged through the cloud"));
        // A second command reuses the session.
        control.send("SN818", Cc2Codec.request(1, Cc2Codec.RESUME), new Replies());
        take(link); assertEquals(1, links.size()); assertEquals(1, credentialRequests);
    }

    @Test public void printerErrorCodeIsReported() throws Exception {
        CloudControl control = control(5000, 60000); Replies replies = new Replies();
        control.send("SN818", Cc2Codec.request(1, Cc2Codec.STOP), replies);
        JSONObject request = new JSONObject(take(waitForLink())[1]);
        links.get(0).events.message("12345SN818", new JSONObject().put("id", request.getInt("id")).put("result", new JSONObject().put("error_code", 1010)).toString());
        assertEquals("fail: " + PrinterErrors.code(1010), replies.take());
    }

    @Test public void silenceTimesOutWithoutRepeatingAndAllowsTheNextCommand() throws Exception {
        CloudControl control = control(200, 60000); Replies replies = new Replies();
        control.send("SN818", Cc2Codec.request(1, Cc2Codec.PAUSE), replies);
        take(waitForLink());
        assertTrue(replies.take().startsWith("fail: No reply through the cloud"));
        Thread.sleep(300);
        assertTrue("The command must not be repeated", links.get(0).published.isEmpty());
        control.send("SN818", Cc2Codec.request(1, Cc2Codec.RESUME), new Replies());
        take(links.get(0));
    }

    @Test public void oneCommandAtATime() throws Exception {
        CloudControl control = control(5000, 60000); Replies first = new Replies(), second = new Replies();
        control.send("SN818", Cc2Codec.request(1, Cc2Codec.PAUSE), first);
        control.send("SN818", Cc2Codec.request(1, Cc2Codec.STOP), second);
        take(waitForLink());
        assertEquals("fail: Waiting for the previous cloud command to finish.", second.take());
        assertTrue(first.results.isEmpty());
    }

    @Test public void sessionTakenOverElsewhereFailsThePendingCommandAndReconnectsOnlyOnTheNextCommand() throws Exception {
        CloudControl control = control(5000, 60000); Replies replies = new Replies();
        control.send("SN818", Cc2Codec.request(1, Cc2Codec.PAUSE), replies);
        take(waitForLink());
        links.get(0).events.ended("Another Elegoo app signed in");
        assertEquals("fail: Another Elegoo app signed in", replies.take());
        waitFor(() -> links.get(0).closed);
        assertFalse(control.connected()); assertEquals("Another Elegoo app signed in", control.endedReason());
        Thread.sleep(200); assertEquals(1, links.size());
        control.send("SN818", Cc2Codec.request(1, Cc2Codec.RESUME), new Replies());
        waitFor(() -> links.size() == 2); assertEquals("", control.endedReason());
    }

    @Test public void unsupportedCommandsAndBadSerialsNeverConnect() throws Exception {
        CloudControl control = control(5000, 60000); Replies replies = new Replies();
        control.send("SN818", Cc2Codec.temperatureRequest(1, 200, 60), replies);
        assertEquals("fail: This command is not available through the cloud.", replies.take());
        control.send("SN 818\";", Cc2Codec.request(1, Cc2Codec.PAUSE), replies);
        assertEquals("fail: Unknown printer serial.", replies.take());
        assertTrue(links.isEmpty()); assertEquals(0, credentialRequests);
    }

    @Test public void failedLoginIsReportedAndClosed() throws Exception {
        CloudControl control = new CloudControl(() -> new CloudApi.AgoraCredential("12345", "rtm-user", "rtm-token"),
            () -> { FakeLink link = new FakeLink(); link.failLogin = true; links.add(link); return link; }, worker, 5000, 60000);
        Replies replies = new Replies();
        control.send("SN818", Cc2Codec.request(1, Cc2Codec.PAUSE), replies);
        assertEquals("fail: Could not sign in to cloud control through the cloud (LOGIN_REJECTED).", replies.take());
        waitFor(() -> links.get(0).closed); assertFalse(control.connected());
    }

    @Test public void idleSessionCloses() throws Exception {
        CloudControl control = control(5000, 150); Replies replies = new Replies();
        control.send("SN818", Cc2Codec.request(1, Cc2Codec.PAUSE), replies);
        JSONObject request = new JSONObject(take(waitForLink())[1]);
        links.get(0).events.message("12345SN818", new JSONObject().put("id", request.getInt("id")).put("result", new JSONObject().put("error_code", 0)).toString());
        replies.take();
        waitFor(() -> links.get(0).closed); assertFalse(control.connected());
    }

    private FakeLink waitForLink() throws InterruptedException { waitFor(() -> !links.isEmpty()); return links.get(0); }
    private interface Condition { boolean met(); }
    private static void waitFor(Condition condition) throws InterruptedException {
        for (int i = 0; i < 100 && !condition.met(); i++) Thread.sleep(50);
        assertTrue("Condition not met in time", condition.met());
    }
}
