package io.github.thelastfrogrammer.elink;

import static org.junit.Assert.*;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;
import org.junit.Test;

/**
 * The October 2026 audit's proofs (F01–F15), turned round: each asserts the repaired behaviour. No printer or account is
 * contacted; network endpoints are fixtures.
 */
public class AuditAcceptanceTest {
    static Field field(Object object, String name) throws Exception { Field f = object.getClass().getDeclaredField(name); f.setAccessible(true); return f; }

    static Cc2Session session(BlockingQueue<String> results) throws Exception {
        Cc2Session.Listener listener = new Cc2Session.Listener() {
            public void connection(String s, boolean b) { }
            public void status(JSONObject s) { }
            public void result(String s) { results.add(s); }
            public void uploadProgress(int p) { }
            public void attributes(JSONObject o) { }
            public void canvas(JSONObject o) { }
            public void failure(String s, boolean b) { }
        };
        Cc2Session s = new Cc2Session("192.168.1.50", "fixture", listener, url -> { throw new AssertionError("No HTTP allowed"); }, null,
            (uri, id) -> { throw new AssertionError("No MQTT allowed"); });
        field(s, "ready").setBoolean(s, true);
        field(s, "statusAt").setLong(s, System.nanoTime());
        // Nothing is transmitted during the test: the next send slot is far away.
        field(s, "nextRequestAt").setLong(s, System.nanoTime() + TimeUnit.HOURS.toNanos(1));
        return s;
    }
    static void settle(Cc2Session s) throws Exception {
        ScheduledExecutorService worker = (ScheduledExecutorService) field(s, "worker").get(s);
        worker.submit(() -> { }).get(2, TimeUnit.SECONDS);
    }

    /** F01: emergency stop goes first, past a filament load awaiting its reply, and drops changing commands not yet sent. */
    @SuppressWarnings("unchecked")
    @Test public void emergencyStopIsQueuedFirstWhileAFilamentLoadIsPending() throws Exception {
        BlockingQueue<String> results = new LinkedBlockingQueue<>();
        try (Cc2Session s = session(results)) {
            Class<?> pending = Class.forName(Cc2Session.class.getName() + "$Pending");
            Constructor<?> ctor = pending.getDeclaredConstructor(int.class, JSONObject.class); ctor.setAccessible(true);
            ((Map<Integer, Object>) field(s, "pending").get(s)).put(77, ctor.newInstance(Cc2Codec.FEED, new JSONObject()));
            Deque<JSONObject> queue = (Deque<JSONObject>) field(s, "requestQueue").get(s);
            Set<Integer> queued = (Set<Integer>) field(s, "queued").get(s);
            // A light change and a status read are waiting to be sent.
            queue.addLast(Cc2Codec.lightRequest(80, true)); queued.add(Cc2Codec.LIGHT);
            queue.addLast(Cc2Codec.request(81, Cc2Codec.STATUS)); queued.add(Cc2Codec.STATUS);
            s.request(Cc2Codec.maintenanceRequest(0, Cc2Codec.URGENT_STOP)); settle(s);
            assertEquals(Cc2Codec.URGENT_STOP, queue.peekFirst().optInt("method"));
            assertEquals("the unsent light change is dropped, the read stays", 2, queue.size());
            assertEquals(Cc2Codec.STATUS, queue.peekLast().optInt("method"));
            assertTrue(results.poll(2, TimeUnit.SECONDS).contains("cancelled for the emergency stop"));
            assertEquals("the pending filament load is left to its own reply", 1, ((Map<?, ?>) field(s, "pending").get(s)).size());
            s.request(Cc2Codec.maintenanceRequest(0, Cc2Codec.URGENT_STOP)); settle(s);
            assertEquals("never sent twice", 2, queue.size());
            assertTrue(results.poll(2, TimeUnit.SECONDS).contains("already sent"));
        }
    }

    /** F07: the local network allows a heater change during a print, as the screens do. */
    @Test public void printingTemperatureIsAllowedOnTheLocalNetworkAsOnScreen() throws Exception {
        JSONObject status = new JSONObject("{\"machine_status\":{\"status\":2,\"sub_status\":2075}}");
        assertTrue(Cc2Codec.heatersAdjustable(status));
        try (Cc2Session s = session(new LinkedBlockingQueue<>())) {
            Cc2Codec codec = (Cc2Codec) field(s, "codec").get(s);
            codec.accept(new JSONObject().put("method", Cc2Codec.STATUS).put("result", new JSONObject(status.toString()).put("error_code", 0)));
            Method allowed = Cc2Session.class.getDeclaredMethod("allowed", JSONObject.class); allowed.setAccessible(true);
            assertTrue((Boolean) allowed.invoke(s, Cc2Codec.temperatureRequest(9, 215, 60)));
            // Homing or loading: still refused.
            codec.accept(new JSONObject().put("method", Cc2Codec.STATUS).put("result", new JSONObject("{\"machine_status\":{\"status\":3,\"sub_status\":0},\"error_code\":0}")));
            assertFalse((Boolean) allowed.invoke(s, Cc2Codec.temperatureRequest(10, 215, 60)));
        }
    }

    static class Link implements CloudControl.Link {
        Events events; final BlockingQueue<String[]> sent = new LinkedBlockingQueue<>();
        public void login(String a, String u, String t, Events e) { events = e; }
        public void subscribe(String c) { }
        public void publish(String c, String t) { sent.add(new String[] {c, t}); }
        public void close() { }
    }
    static CloudControl control(Link link, ScheduledExecutorService worker) {
        return new CloudControl(() -> new CloudApi.AgoraCredential("42", "fixture", "fixture"), () -> link, worker).spacing(0);
    }

    /** F06: a reply with the right id but another method, or without success evidence, does not acknowledge a command. */
    @Test public void cloudRepliesMustMatchTheMethodAndSaySuccess() throws Exception {
        ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(); Link link = new Link();
        CloudControl control = control(link, worker);
        try {
            BlockingQueue<Object[]> ack = new LinkedBlockingQueue<>();
            control.send("TESTSN", Cc2Codec.lightRequest(0, true), (ok, text) -> ack.add(new Object[] {ok, text}));
            String[] sent = link.sent.poll(2, TimeUnit.SECONDS); assertNotNull(sent);
            int id = new JSONObject(sent[1]).getInt("id");
            link.events.message(sent[0], new JSONObject().put("id", id).put("method", 6000).put("result", new JSONObject()).toString());
            link.events.message("someoneElse", new JSONObject().put("id", id).put("method", Cc2Codec.LIGHT).put("result", new JSONObject().put("error_code", 0)).toString());
            assertNull("wrong method or publisher leaves it waiting", ack.poll(300, TimeUnit.MILLISECONDS));
            link.events.message(sent[0], new JSONObject().put("id", id).put("method", Cc2Codec.LIGHT).put("result", new JSONObject()).toString());
            Object[] answer = ack.poll(2, TimeUnit.SECONDS);
            assertEquals(Boolean.FALSE, answer[0]); assertTrue((String) answer[1], ((String) answer[1]).contains("did not say"));

            control.send("TESTSN", Cc2Codec.lightRequest(0, false), (ok, text) -> ack.add(new Object[] {ok, text}));
            sent = link.sent.poll(2, TimeUnit.SECONDS); id = new JSONObject(sent[1]).getInt("id");
            link.events.message(sent[0], new JSONObject().put("id", id).put("method", Cc2Codec.LIGHT).put("result", new JSONObject().put("error_code", 0)).toString());
            assertEquals(Boolean.TRUE, ack.poll(2, TimeUnit.SECONDS)[0]);
        } finally { control.close(); worker.shutdownNow(); }
    }

    /** F01 on the cloud: the stop goes out while a filament load awaits its reply, and unsent changing commands are dropped. */
    @Test public void cloudEmergencyStopGoesOutBesideAPendingCommand() throws Exception {
        ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(); Link link = new Link();
        CloudControl control = control(link, worker);
        try {
            BlockingQueue<String> replies = new LinkedBlockingQueue<>();
            control.send("TESTSN", Cc2Codec.maintenanceRequest(0, Cc2Codec.FEED), (ok, text) -> replies.add("feed " + ok));
            String[] feed = link.sent.poll(2, TimeUnit.SECONDS); assertNotNull(feed);
            control.send("TESTSN", Cc2Codec.lightRequest(0, true), (ok, text) -> replies.add("light " + ok + " " + text));
            control.send("TESTSN", Cc2Codec.maintenanceRequest(0, Cc2Codec.URGENT_STOP), (ok, text) -> replies.add("stop " + ok));
            String[] stop = link.sent.poll(2, TimeUnit.SECONDS); assertNotNull("the stop is published at once", stop);
            assertEquals(Cc2Codec.URGENT_STOP, new JSONObject(stop[1]).getInt("method"));
            String light = replies.poll(2, TimeUnit.SECONDS);
            assertTrue(light, light.startsWith("light false") && light.contains("not sent"));
            link.events.message(stop[0], new JSONObject().put("id", new JSONObject(stop[1]).getInt("id")).put("method", Cc2Codec.URGENT_STOP).put("result", new JSONObject().put("error_code", 0)).toString());
            assertEquals("stop true", replies.poll(2, TimeUnit.SECONDS));
            assertNull("nothing else was published", link.sent.poll(300, TimeUnit.MILLISECONDS));
            link.events.message(feed[0], new JSONObject().put("id", new JSONObject(feed[1]).getInt("id")).put("method", Cc2Codec.FEED).put("result", new JSONObject().put("error_code", 0)).toString());
            assertEquals("the feed's own reply still matches it", "feed true", replies.poll(2, TimeUnit.SECONDS));
        } finally { control.close(); worker.shutdownNow(); }
    }

    /** F03: sharing keeps only a webhook's site, empties secret-looking body values, and the imported copy must be set up again. */
    @Test public void sharingCutsWebhookAddressesAndBodySecrets() throws Exception {
        Automation.Rule rule = new Automation.Rule(); rule.name = "Discord"; rule.event = Automation.FINISHED;
        Automation.Action action = new Automation.Action(); action.kind = Automation.WEBHOOK;
        action.url = "https://user:pw@discord.com/api/webhooks/123456/fake-private-webhook-token?api_key=k1";
        action.body = "{\"content\":\"{file} done\",\"api_key\":\"k2\",\"Token\":\"k3\"}";
        rule.actions.add(action);
        String shared = Automation.share(Collections.singletonList(rule));
        for (String secret : new String[] {"fake-private-webhook-token", "k1", "k2", "k3", "user:pw", action.secretId}) assertFalse(secret, shared.contains(secret));
        assertTrue(shared.contains("{file} done"));
        Automation.Rule imported = Automation.parseShared(shared).get(0);
        assertEquals("https://discord.com/", imported.actions.get(0).url);
        assertTrue(imported.actions.get(0).needsAddress); assertFalse(imported.enabled);
        assertEquals("a=1&token=&b=2", Automation.redactBody("a=1&token=abc&b=2"));
        // A Home Assistant webhook id is in the path.
        assertEquals("http://homeassistant.local:8123/", Automation.siteOf("http://homeassistant.local:8123/api/webhook/secret-id"));
    }
}
