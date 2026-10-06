package io.github.thelastfrogrammer.elink;

import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;

public class CloudLiveTest {
    private static final class Events implements CloudLive.Listener {
        final List<String> log = new ArrayList<>(); JSONObject partial;
        public void delta(String serial, JSONObject partial) { log.add("delta " + serial); this.partial = partial; }
        public void online(String serial, boolean online) { log.add("online " + serial + " " + online); }
        public void state(String text, boolean live) { log.add((live ? "live " : "off ") + text); }
    }
    private static final class FakeClient extends MqttClient {
        MqttConnectOptions options; String[] topics; boolean connected; MqttException refuse;
        FakeClient(String uri, String id) throws MqttException { super(uri, id, new MemoryPersistence()); }
        @Override public void setCallback(MqttCallback callback) { }
        @Override public void connect(MqttConnectOptions options) throws MqttException { if (refuse != null) throw refuse; this.options = options; connected = true; }
        @Override public void subscribe(String[] topics, int[] qos) { this.topics = topics; }
        @Override public boolean isConnected() { return connected; }
        @Override public void disconnectForcibly(long a, long b) { connected = false; }
        @Override public void close(boolean force) { }
    }

    @Test public void brokerAddressesFollowTheSdkRules() {
        assertEquals("wss://broker.example:8084/mqtt", CloudLive.brokerUri("wss://broker.example:8084"));
        assertEquals("wss://broker.example/custom", CloudLive.brokerUri("wss://broker.example/custom"));
        assertEquals("ssl://broker.example:8883", CloudLive.brokerUri("mqtts://broker.example:8883"));
        assertEquals("tcp://broker.example:1883", CloudLive.brokerUri("mqtt://broker.example:1883"));
        assertEquals("ws://broker.example:8083/mqtt", CloudLive.brokerUri("broker.example:8083"));
        assertEquals("elegooslicer_android_42", CloudLive.clientId("42"));
    }

    @Test public void connectsWithTheIssuedLoginAndSubscribesToTheAppTopics() throws Exception {
        Events events = new Events(); List<FakeClient> clients = new ArrayList<>();
        CloudLive live = new CloudLive(events, (uri, id) -> { assertEquals("wss://b.example/mqtt", uri); FakeClient c = new FakeClient("tcp://localhost:1", id); clients.add(c); return c; });
        live.connect(new CloudApi.MqttCredential("wss://b.example", "issued-id", "user", "pass"));
        FakeClient client = clients.get(0);
        assertEquals("user", client.options.getUserName()); assertEquals("pass", new String(client.options.getPassword()));
        assertArrayEquals(new String[] {"app/v1/issued-id/device/data", "app/v1/issued-id/device/onoffline", "app/v1/issued-id/event"}, client.topics);
        assertTrue(live.connected()); assertTrue(events.log.get(0).startsWith("live "));
        live.close(); assertFalse(live.connected());
    }

    @Test public void refusalFallsBackToPolling() throws Exception {
        Events events = new Events();
        CloudLive live = new CloudLive(events, (uri, id) -> { FakeClient c = new FakeClient("tcp://localhost:1", id); c.refuse = new MqttException(MqttException.REASON_CODE_NOT_AUTHORIZED); return c; });
        live.connect(new CloudApi.MqttCredential("ws://b.example", "id", "user", "pass"));
        assertFalse(live.connected()); assertEquals("off Live updates unavailable (MQTT 5); using periodic checks.", events.log.get(0));
    }

    @Test public void dataAndOnlinePushesAreParsed() throws Exception {
        Events events = new Events(); CloudLive live = new CloudLive(events, (uri, id) -> null);
        String report = new JSONObject().put("extruder", new JSONObject().put("temperature", 90)).put("meta_data", new JSONObject().put("id", 1)).toString();
        live.handle("app/v1/x/device/data", new JSONObject().put("pkey", "SN1").put("deviceCode", "SN1").put("reportValue", report).toString());
        assertEquals("delta SN1", events.log.get(0)); assertEquals(90, events.partial.getJSONObject("extruder").getInt("temperature")); assertFalse(events.partial.has("meta_data"));
        live.handle("app/v1/x/device/onoffline", new JSONObject().put("deviceCode", "SN1").put("onlineStatus", 0).toString());
        assertEquals("online SN1 false", events.log.get(1));
        live.handle("app/v1/x/device/data", "not json"); live.handle("app/v1/x/device/data", new JSONObject().put("reportValue", report).toString());
        assertEquals(2, events.log.size());
    }

    @Test public void deltasMergeIntoTheFullStatus() throws Exception {
        JSONObject full = new JSONObject().put("extruder", new JSONObject().put("temperature", 200).put("target", 220)).put("machine_status", new JSONObject().put("status", 2));
        Cc2Codec.merge(full, new JSONObject().put("extruder", new JSONObject().put("temperature", 215)));
        assertEquals(215, full.getJSONObject("extruder").getInt("temperature")); assertEquals(220, full.getJSONObject("extruder").getInt("target"));
    }
}
