package io.github.thelastfrogrammer.elink;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.net.SocketFactory;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Home Assistant link: publishes the printer's status to the user's own MQTT broker, with Home Assistant's MQTT discovery
 * so the sensors appear by themselves (progress, time left, layer, temperatures, fans, light, state, faults) plus a camera
 * entity with the print's latest picture. Read only: it subscribes to nothing and accepts no commands. The broker password
 * is kept encrypted by CredentialStore and never logged.
 */
final class HomeAssistant {
    /** One sensor: key in the state JSON, name, unit, device class, and component (sensor or binary_sensor). */
    static final class Entity {
        final String key, name, unit, deviceClass, component, icon;
        Entity(String key, String name, String unit, String deviceClass, String component, String icon) { this.key = key; this.name = name; this.unit = unit; this.deviceClass = deviceClass; this.component = component; this.icon = icon; }
    }
    static final List<Entity> ENTITIES = new ArrayList<>();
    static {
        ENTITIES.add(new Entity("state", "State", null, null, "sensor", "mdi:printer-3d"));
        ENTITIES.add(new Entity("progress", "Progress", "%", null, "sensor", "mdi:progress-clock"));
        ENTITIES.add(new Entity("file", "File", null, null, "sensor", "mdi:file-outline"));
        ENTITIES.add(new Entity("layer", "Layer", null, null, "sensor", "mdi:layers-outline"));
        ENTITIES.add(new Entity("total_layers", "Total layers", null, null, "sensor", "mdi:layers-triple-outline"));
        ENTITIES.add(new Entity("remaining", "Time left", "min", "duration", "sensor", null));
        ENTITIES.add(new Entity("done_at", "Done at", null, "timestamp", "sensor", null));
        ENTITIES.add(new Entity("nozzle", "Nozzle", "°C", "temperature", "sensor", null));
        ENTITIES.add(new Entity("nozzle_target", "Nozzle target", "°C", "temperature", "sensor", null));
        ENTITIES.add(new Entity("bed", "Bed", "°C", "temperature", "sensor", null));
        ENTITIES.add(new Entity("bed_target", "Bed target", "°C", "temperature", "sensor", null));
        ENTITIES.add(new Entity("chamber", "Chamber", "°C", "temperature", "sensor", null));
        ENTITIES.add(new Entity("part_fan", "Part fan", "%", null, "sensor", "mdi:fan"));
        ENTITIES.add(new Entity("aux_fan", "Aux fan", "%", null, "sensor", "mdi:fan"));
        ENTITIES.add(new Entity("chamber_fan", "Chamber fan", "%", null, "sensor", "mdi:fan"));
        ENTITIES.add(new Entity("faults", "Fault codes", null, null, "sensor", "mdi:alert-outline"));
        ENTITIES.add(new Entity("route", "Connection", null, null, "sensor", "mdi:lan-connect"));
        ENTITIES.add(new Entity("printing", "Printing", null, "running", "binary_sensor", null));
        ENTITIES.add(new Entity("light", "Light", null, "light", "binary_sensor", null));
    }

    /** A short id for this printer (from its serial, not the serial itself), used in topics and entity ids. */
    static String node(String serial) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(("link-workshop:" + serial).getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder("cc2_"); for (int i = 0; i < 4; i++) out.append(String.format(Locale.ROOT, "%02x", hash[i])); return out.toString();
        } catch (Exception impossible) { return "cc2_printer"; }
    }
    static String base(String node) { return "linkworkshop/" + node; }

    /** The retained discovery messages: topic → payload. */
    static List<String[]> discovery(String node, String printerName) throws Exception {
        List<String[]> out = new ArrayList<>();
        JSONObject device = new JSONObject().put("identifiers", new JSONArray().put(node)).put("name", printerName).put("model", "Centauri Carbon 2").put("sw_version", "via Link Workshop");
        for (Entity e : ENTITIES) {
            JSONObject c = new JSONObject().put("name", e.name).put("unique_id", node + "_" + e.key).put("object_id", node + "_" + e.key)
                .put("state_topic", base(node) + "/state").put("availability_topic", base(node) + "/availability").put("device", device);
            if (e.component.equals("binary_sensor")) c.put("value_template", "{{ 'ON' if value_json." + e.key + " else 'OFF' }}");
            else c.put("value_template", "{{ value_json." + e.key + " }}");
            if (e.unit != null) c.put("unit_of_measurement", e.unit);
            if (e.deviceClass != null) c.put("device_class", e.deviceClass);
            if (e.unit != null && !"timestamp".equals(e.deviceClass)) c.put("state_class", "measurement");
            if (e.icon != null) c.put("icon", e.icon);
            out.add(new String[] {"homeassistant/" + e.component + "/" + node + "/" + e.key + "/config", c.toString()});
        }
        JSONObject camera = new JSONObject().put("name", "Latest picture").put("unique_id", node + "_picture").put("object_id", node + "_picture")
            .put("topic", base(node) + "/picture").put("availability_topic", base(node) + "/availability").put("device", device);
        out.add(new String[] {"homeassistant/camera/" + node + "/picture/config", camera.toString()});
        return out;
    }

    /** The state JSON from a printer status (local or cloud); values the printer did not report are left out. */
    static JSONObject state(JSONObject status, String route, long nowMs) throws Exception {
        JSONObject out = new JSONObject().put("route", route);
        if (status == null || status.length() == 0) return out.put("state", "No status");
        JSONObject machine = status.optJSONObject("machine_status"), print = status.optJSONObject("print_status");
        boolean printing = machine != null && machine.optInt("status", -1) == 2;
        out.put("state", StatusPresentation.state(status)).put("printing", printing);
        if (machine != null && machine.has("progress")) out.put("progress", Math.max(0, Math.min(100, machine.optInt("progress"))));
        if (print != null) {
            String file = StatusPresentation.clean(print.optString("filename", "")).replaceFirst("(?i)\\.gcode$", "");
            out.put("file", printing ? file : "");
            if (print.has("current_layer")) out.put("layer", print.optInt("current_layer"));
            if (print.has("total_layer")) out.put("total_layers", print.optInt("total_layer"));
            long remaining = print.optLong("remaining_time_sec", -1);
            if (printing && remaining >= 0) {
                out.put("remaining", Math.round(remaining / 60.0));
                java.text.SimpleDateFormat iso = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT); iso.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                out.put("done_at", iso.format(new java.util.Date(nowMs + remaining * 1000)));
            }
        }
        temperature(out, "nozzle", status.optJSONObject("extruder")); temperature(out, "bed", status.optJSONObject("heater_bed"));
        JSONObject chamber = status.optJSONObject("ztemperature_sensor"); if (chamber != null && chamber.has("temperature")) out.put("chamber", round(chamber.optDouble("temperature")));
        JSONObject fans = status.optJSONObject("fans");
        fan(out, "part_fan", fans, "fan"); fan(out, "aux_fan", fans, "aux_fan"); fan(out, "chamber_fan", fans, "box_fan");
        JSONObject led = status.optJSONObject("led"); if (led != null && led.has("status")) out.put("light", led.optInt("status") != 0);
        String codes = StatusPresentation.faultCodes(status); out.put("faults", codes.isEmpty() ? "none" : codes);
        return out;
    }
    private static double round(double v) { return Math.round(v * 10) / 10.0; }
    private static void temperature(JSONObject out, String key, JSONObject value) throws Exception {
        if (value == null) return;
        if (value.has("temperature")) out.put(key, round(value.optDouble("temperature")));
        if (value.has("target")) out.put(key + "_target", round(value.optDouble("target")));
    }
    private static void fan(JSONObject out, String key, JSONObject fans, String name) throws Exception {
        JSONObject fan = fans == null ? null : fans.optJSONObject(name);
        if (fan != null && fan.has("speed")) out.put(key, Math.round(fan.optDouble("speed") * 100 / 255));
    }

    // ------------------------------------------------------------------ the connection (one per app run, on its own thread)

    interface Clients { MqttClient create(String uri, String id) throws Exception; }
    static Clients paho() { return (uri, id) -> new MqttClient(uri, id, new MemoryPersistence()); }

    private final Clients clients;
    private MqttClient client; private String connectedTo = ""; private String announced = ""; private String lastState = ""; private long lastStateAt; private String lastPicture = "";
    /** What happened last, in words, for Settings and the log. */
    volatile String lastResult = "Not connected yet.";
    HomeAssistant(Clients clients) { this.clients = clients; }

    /**
     * Publishes the state (and the discovery messages the first time, and the picture when it changed). Blocking: call it on
     * a background thread. Sends at most every 10 s unless the state changed.
     */
    synchronized void publish(String host, int port, boolean tls, String user, String password, SocketFactory sockets, String node, String printerName,
                              JSONObject state, byte[] picture, String pictureKey, long nowMs) {
        try {
            String target = (tls ? "ssl://" : "tcp://") + host + ":" + port + "|" + user;
            if (client == null || !client.isConnected() || !target.equals(connectedTo)) {
                close();
                client = clients.create((tls ? "ssl://" : "tcp://") + host + ":" + port, "link-workshop-" + node);
                MqttConnectOptions options = new MqttConnectOptions();
                options.setCleanSession(true); options.setAutomaticReconnect(false); options.setConnectionTimeout(8); options.setKeepAliveInterval(60);
                options.setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1_1);
                if (!user.isEmpty()) options.setUserName(user);
                if (!password.isEmpty()) options.setPassword(password.toCharArray());
                if (sockets != null && !tls) options.setSocketFactory(sockets);
                options.setWill(base(node) + "/availability", "offline".getBytes(StandardCharsets.UTF_8), 1, true);
                client.connect(options); connectedTo = target; announced = "";
                Diagnostics.note(Diagnostics.HOME_ASSISTANT, "connected to the broker" + (tls ? " (TLS)" : ""));
            }
            if (!announced.equals(node + "|" + printerName)) {
                for (String[] message : discovery(node, printerName)) send(message[0], message[1].getBytes(StandardCharsets.UTF_8), true);
                send(base(node) + "/availability", "online".getBytes(StandardCharsets.UTF_8), true);
                announced = node + "|" + printerName;
                Diagnostics.note(Diagnostics.HOME_ASSISTANT, "announced " + ENTITIES.size() + " sensors and a camera to Home Assistant");
            }
            String text = state.toString();
            if (!text.equals(lastState) || nowMs - lastStateAt > 60_000) { send(base(node) + "/state", text.getBytes(StandardCharsets.UTF_8), true); lastState = text; lastStateAt = nowMs; }
            if (picture != null && pictureKey != null && !pictureKey.equals(lastPicture)) { send(base(node) + "/picture", picture, true); lastPicture = pictureKey; }
            lastResult = "Sending to Home Assistant. Last update " + new java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new java.util.Date(nowMs)) + ".";
        } catch (Exception failed) {
            String why = failed.getClass().getSimpleName() + (failed.getMessage() == null ? "" : ": " + failed.getMessage());
            lastResult = "Could not reach the broker: " + Diagnostics.scrub(why);
            Diagnostics.note(Diagnostics.HOME_ASSISTANT, "could not publish: " + Diagnostics.scrub(why));
            close();
        }
    }
    private void send(String topic, byte[] payload, boolean retained) throws Exception {
        MqttMessage message = new MqttMessage(payload); message.setQos(0); message.setRetained(retained); client.publish(topic, message);
    }
    /** Marks the printer offline in Home Assistant and closes the connection. */
    synchronized void stop(String node) {
        try { if (client != null && client.isConnected() && node != null) send(base(node) + "/availability", "offline".getBytes(StandardCharsets.UTF_8), true); } catch (Exception ignored) { }
        close();
    }
    private void close() {
        MqttClient c = client; client = null; connectedTo = "";
        if (c != null) { try { if (c.isConnected()) c.disconnect(1000); } catch (Exception ignored) { } try { c.close(); } catch (Exception ignored) { } }
    }
}
