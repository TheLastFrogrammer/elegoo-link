package io.github.thelastfrogrammer.elink;

import java.util.Locale;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.json.JSONObject;

/**
 * Live status pushes from Elegoo's cloud MQTT broker, as the SDK's MqttService receives them: subscribe to
 * app/v1/&lt;clientId&gt;/device/data (partial status in "reportValue") and /device/onoffline. Uses this app's own client ID
 * (elegooslicer_android_&lt;userId&gt;) so it never collides with ElegooSlicer's. Polling stays as the fallback.
 */
public final class CloudLive implements AutoCloseable {
    public interface Listener {
        /** A partial status for one printer, in the LAN status format; merge it into the last full status. */
        void delta(String serial, JSONObject partial);
        void online(String serial, boolean online);
        void state(String text, boolean live);
    }
    interface ClientFactory { MqttClient create(String uri, String clientId) throws MqttException; }

    private final Listener listener;
    private final ClientFactory factory;
    private MqttClient client;
    private volatile boolean closed;

    CloudLive(Listener listener, ClientFactory factory) { this.listener = listener; this.factory = factory; }
    public CloudLive(Listener listener) { this(listener, (uri, id) -> new MqttClient(uri, id, new MemoryPersistence())); }

    public static String clientId(String userId) { return "elegooslicer_android_" + userId; }

    /**
     * The SDK's address rules: ws/wss get a "/mqtt" path when none is given, mqtt(s):// map to Paho's tcp/ssl, and an address
     * without a scheme becomes ws://host/mqtt.
     */
    static String brokerUri(String host) {
        String value = host.trim();
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.startsWith("ws://") || lower.startsWith("wss://")) {
            int path = value.indexOf('/', value.indexOf("://") + 3);
            return path < 0 ? value + "/mqtt" : value;
        }
        if (lower.startsWith("mqtts://")) return "ssl://" + value.substring(8);
        if (lower.startsWith("mqtt://")) return "tcp://" + value.substring(7);
        if (lower.startsWith("ssl://") || lower.startsWith("tcp://")) return value;
        return "ws://" + value + "/mqtt";
    }

    /** Connects and subscribes; blocking, call off the main thread. Failures are reported through Listener.state. */
    public synchronized void connect(CloudApi.MqttCredential credential) {
        if (closed) return;
        disconnect();
        try {
            String uri = brokerUri(credential.host);
            MqttClient created = factory.create(uri, credential.clientId);
            created.setCallback(new MqttCallback() {
                public void connectionLost(Throwable cause) { if (!closed) listener.state("Live updates not connected; using periodic checks.", false); }
                public void messageArrived(String topic, MqttMessage message) { handle(topic, new String(message.getPayload(), java.nio.charset.StandardCharsets.UTF_8)); }
                public void deliveryComplete(IMqttDeliveryToken token) { }
            });
            MqttConnectOptions options = new MqttConnectOptions();
            options.setUserName(credential.username); options.setPassword(credential.password.toCharArray());
            options.setCleanSession(true); options.setKeepAliveInterval(60); options.setAutomaticReconnect(false); options.setConnectionTimeout(15);
            options.setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1_1);
            created.connect(options);
            String base = "app/v1/" + credential.clientId;
            created.subscribe(new String[] {base + "/device/data", base + "/device/onoffline", base + "/event"}, new int[] {0, 0, 0});
            client = created;
            listener.state("Live updates through the Elegoo cloud" + (uri.startsWith("ws://") || uri.startsWith("tcp://") ? " (unencrypted connection chosen by Elegoo)" : "") + ".", true);
        } catch (MqttException error) {
            listener.state("Live updates unavailable (MQTT " + error.getReasonCode() + "); using periodic checks.", false);
        } catch (Exception error) {
            listener.state("Live updates unavailable; using periodic checks.", false);
        }
    }

    void handle(String topic, String payload) {
        JSONObject json;
        try { json = new JSONObject(payload); } catch (Exception error) { return; }
        String serial = json.optString("deviceCode", "");
        if (serial.isEmpty()) return;
        if (topic.endsWith("/device/onoffline")) { if (json.has("onlineStatus")) listener.online(serial, json.optInt("onlineStatus") == 1); return; }
        if (!topic.endsWith("/device/data")) return;
        // payload={"pkey":"SN","deviceCode":"SN","reportValue":"{\"extruder\":{\"temperature\":90},\"meta_data\":{...}}"}
        Object value = json.opt("reportValue");
        JSONObject partial;
        try { partial = value instanceof JSONObject ? (JSONObject) value : new JSONObject(String.valueOf(value)); } catch (Exception error) { return; }
        partial.remove("meta_data");
        if (partial.length() > 0) listener.delta(serial, partial);
    }

    public synchronized boolean connected() { return client != null && client.isConnected(); }

    private void disconnect() {
        MqttClient old = client; client = null;
        if (old == null) return;
        try { old.disconnectForcibly(1000, 1000); } catch (Exception ignored) { }
        try { old.close(true); } catch (Exception ignored) { }
    }

    @Override public synchronized void close() { closed = true; disconnect(); }
}
