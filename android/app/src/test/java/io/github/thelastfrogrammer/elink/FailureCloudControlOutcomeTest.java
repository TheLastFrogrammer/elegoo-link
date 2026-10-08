package io.github.thelastfrogrammer.elink;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.concurrent.*;

/** A printer-changing cloud command whose session ends before the reply must not read as "not sent". */
public class FailureCloudControlOutcomeTest {
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    @After public void stop() { worker.shutdownNow(); }

    private static final class SilentLink implements CloudControl.Link {
        final BlockingQueue<String> published = new LinkedBlockingQueue<>(); volatile Events events;
        public void login(String appId, String rtmUserId, String token, Events events) { this.events = events; }
        public void subscribe(String channel) { }
        public void publish(String channel, String text) { published.add(text); }
        public void close() { }
    }

    private String endWhilePending(int method) throws Exception {
        SilentLink link = new SilentLink(); BlockingQueue<String> replies = new LinkedBlockingQueue<>();
        CloudControl control = new CloudControl(() -> new CloudApi.AgoraCredential("12345", "rtm", "tok"), () -> link, worker, 30_000, 60_000);
        control.send("SN818", Cc2Codec.request(0, method), (acknowledged, message) -> replies.add(message));
        assertNotNull(link.published.poll(5, TimeUnit.SECONDS));
        link.events.ended("Another Elegoo app signed in.");
        return replies.poll(5, TimeUnit.SECONDS);
    }

    @Test public void endingTheLinkDuringAStopSaysTheOutcomeIsUnknown() throws Exception {
        String text = endWhilePending(Cc2Codec.STOP);
        assertTrue(text, text.contains("may or may not have reached the printer"));
        assertTrue(text, text.contains("not repeated"));
    }

    @Test public void endingTheLinkDuringAReadDoesNotClaimAnyCommandMayHaveRun() throws Exception {
        assertFalse(endWhilePending(Cc2Codec.STATUS).contains("may or may not"));
    }
}
