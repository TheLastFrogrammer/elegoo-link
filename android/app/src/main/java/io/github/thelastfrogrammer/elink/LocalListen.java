package io.github.thelastfrogrammer.elink;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import javax.net.SocketFactory;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.json.JSONObject;

/**
 * Listen-only experiment: does a printer in cloud mode still publish its status on the local broker? Connects to port 1883
 * with the pairing PIN (the SDK's local `pinCode` login: user `elegoo`), subscribes to the printer's own topics and counts
 * what arrives for a while, then disconnects. It publishes nothing: no registration, no requests. Topics are reported with
 * the serial and client parts masked, payloads only as field names. The PIN is used once and not kept.
 */
final class LocalListen {
    interface Clients { MqttClient create(String uri, String id) throws Exception; }

    static final class Result {
        boolean connected, subscribed; String failure = ""; int messages; String firstFields = "";
        final Map<String, Integer> topics = new TreeMap<>();
        String report() {
            if (!connected) return "local broker: login refused or unreachable (" + failure + ")";
            StringBuilder out = new StringBuilder("local broker: login accepted, subscription " + (subscribed ? "accepted" : "refused") + ", " + messages + " message" + (messages == 1 ? "" : "s"));
            if (!topics.isEmpty()) { out.append(" on "); boolean first = true; for (Map.Entry<String, Integer> t : topics.entrySet()) { out.append(first ? "" : ", ").append(t.getKey()).append(" ×").append(t.getValue()); first = false; } }
            if (!firstFields.isEmpty()) out.append("; first payload fields: ").append(firstFields);
            if (!failure.isEmpty()) out.append(" (").append(failure).append(')');
            return out.toString();
        }
        /** What it means, in words. */
        String verdict(int seconds) {
            if (!connected) return failure.contains("authorized") ? "The printer's local broker refused the login (not authorized): the PIN was not the current one, or this firmware no longer accepts a PIN on the local broker. Either way, local status is not available in cloud mode this way."
                : "The printer's local broker could not be reached (" + failure + "), so local status is not available in cloud mode this way.";
            if (!subscribed) return "The local broker accepted the PIN but refused to let the app listen to the printer's topics.";
            if (messages == 0) return "The local broker accepted the PIN and the subscription, but the printer published nothing locally in " + seconds + " seconds. In cloud mode its status goes only to the cloud.";
            return "The printer published " + messages + " message" + (messages == 1 ? "" : "s") + " on your Wi-Fi in " + seconds + " seconds while in cloud mode. Local status alongside the cloud looks possible; send the diagnostics so the app can use it.";
        }
    }

    /** "elegoo/<sn>/api_status" with the serial (and any client id) replaced, so topics can be reported. */
    static String mask(String topic, String serial) {
        String masked = serial.isEmpty() ? topic : topic.replace(serial, "<sn>");
        return masked.replaceAll("\\d+_PC_\\d+", "<client>").replaceAll("[0-9A-Fa-f]{12,}", "<id>");
    }

    static Result listen(String host, String serial, String pin, SocketFactory sockets, Clients clients, long millis) {
        Result result = new Result();
        MqttClient client = null;
        try {
            client = clients.create("tcp://" + host + ":1883", "1_PC_" + (1000 + new java.security.SecureRandom().nextInt(9000)));
            Object lock = new Object();
            client.setCallback(new MqttCallback() {
                public void connectionLost(Throwable cause) { synchronized (lock) { result.failure = "connection closed by the printer"; } }
                public void messageArrived(String topic, MqttMessage message) {
                    synchronized (lock) {
                        result.messages++; result.topics.merge(mask(topic, serial), 1, Integer::sum);
                        if (result.firstFields.isEmpty()) {
                            try { result.firstFields = CloudApi.shape(new JSONObject(new String(message.getPayload(), StandardCharsets.UTF_8))); }
                            catch (Exception notJson) { result.firstFields = "not JSON (" + message.getPayload().length + " bytes)"; }
                        }
                    }
                }
                public void deliveryComplete(IMqttDeliveryToken token) { }
            });
            MqttConnectOptions options = new MqttConnectOptions();
            options.setUserName("elegoo"); options.setPassword(pin.toCharArray());
            options.setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1_1);
            options.setCleanSession(true); options.setAutomaticReconnect(false); options.setConnectionTimeout(5); options.setKeepAliveInterval(20);
            if (sockets != null) options.setSocketFactory(sockets);
            client.connect(options);
            result.connected = true;
            String base = "elegoo/" + serial + "/";
            try {
                int[] granted = client.subscribeWithResponse(new String[] {base + "api_status", base + "#"}, new int[] {0, 0}).getGrantedQos();
                result.subscribed = granted != null && granted.length > 0 && granted[0] != 128;
            } catch (Exception refused) { result.failure = "subscription refused"; }
            if (result.subscribed) Thread.sleep(millis);
        } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); result.failure = "stopped";
        } catch (Exception error) { result.failure = error.getClass().getSimpleName() + (error.getMessage() == null ? "" : ": " + error.getMessage().replaceAll("\\d+\\.\\d+\\.\\d+\\.\\d+", "<printer>"));
        } finally {
            if (client != null) { try { if (client.isConnected()) client.disconnect(1000); } catch (Exception ignored) { } try { client.close(); } catch (Exception ignored) { } }
        }
        return result;
    }

    static Clients paho() { return (uri, id) -> new MqttClient(uri, id, new MemoryPersistence()); }
}
