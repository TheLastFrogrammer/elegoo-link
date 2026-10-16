package io.github.thelastfrogrammer.elink;

import java.util.List;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class HomeAssistantTest {
    @Test public void theNodeIdIsStableAndDoesNotRevealTheSerial() {
        String node = HomeAssistant.node("F01PLX9A3K2B7Q");
        assertEquals(node, HomeAssistant.node("F01PLX9A3K2B7Q"));
        assertTrue(node, node.matches("cc2_[0-9a-f]{8}"));
        assertFalse(node.contains("F01P"));
        assertNotEquals(node, HomeAssistant.node("OTHER123456789"));
    }
    @Test public void discoveryAnnouncesEverySensorAndACameraUnderOneDevice() throws Exception {
        List<String[]> messages = HomeAssistant.discovery("cc2_12345678", "Bedroom");
        assertEquals(HomeAssistant.ENTITIES.size() + 3, messages.size());
        assertTrue(messages.stream().anyMatch(m -> m[0].equals("homeassistant/camera/cc2_12345678/chart/config")));
        String[] progress = messages.stream().filter(m -> m[0].equals("homeassistant/sensor/cc2_12345678/progress/config")).findFirst().get();
        JSONObject c = new JSONObject(progress[1]);
        assertEquals("linkworkshop/cc2_12345678/state", c.getString("state_topic"));
        assertEquals("{{ value_json.progress }}", c.getString("value_template"));
        assertEquals("%", c.getString("unit_of_measurement"));
        assertEquals("Bedroom", c.getJSONObject("device").getString("name"));
        assertTrue(messages.stream().anyMatch(m -> m[0].equals("homeassistant/binary_sensor/cc2_12345678/light/config")));
        assertTrue(messages.stream().anyMatch(m -> m[0].equals("homeassistant/camera/cc2_12345678/picture/config")));
        JSONObject event = new JSONObject(messages.stream().filter(m -> m[0].equals("homeassistant/event/cc2_12345678/event/config")).findFirst().get()[1]);
        assertEquals("linkworkshop/cc2_12345678/event", event.getString("state_topic"));
        assertTrue(event.getJSONArray("event_types").toString().contains("print_finished"));
        assertFalse("nothing listens for commands", messages.stream().anyMatch(m -> m[1].contains("command_topic")));
    }
    @Test public void theStateCarriesWhatThePrinterReported() throws Exception {
        JSONObject status = new JSONObject()
            .put("machine_status", new JSONObject().put("status", 2).put("sub_status", 2075).put("progress", 41))
            .put("print_status", new JSONObject().put("filename", "Benchy.gcode").put("current_layer", 87).put("total_layer", 212).put("remaining_time_sec", 4500))
            .put("extruder", new JSONObject().put("temperature", 219.64).put("target", 220)).put("heater_bed", new JSONObject().put("temperature", 59.8).put("target", 60))
            .put("ztemperature_sensor", new JSONObject().put("temperature", 31.0)).put("led", new JSONObject().put("status", 1))
            .put("fans", new JSONObject().put("fan", new JSONObject().put("speed", 255)));
        JSONObject state = HomeAssistant.state(status, "Elegoo cloud", 0);
        assertEquals(41, state.getInt("progress")); assertEquals("Benchy", state.getString("file")); assertEquals(87, state.getInt("layer"));
        assertEquals(75, state.getLong("remaining")); assertEquals("1970-01-01T01:15:00Z", state.getString("done_at"));
        assertEquals(219.6, state.getDouble("nozzle"), 0.001); assertEquals(220, state.getDouble("nozzle_target"), 0.001);
        assertTrue(state.getBoolean("printing")); assertTrue(state.getBoolean("light")); assertEquals(100, state.getInt("part_fan"));
        assertEquals("none", state.getString("faults")); assertEquals("Elegoo cloud", state.getString("route"));
        assertFalse("not reported, not invented", state.has("aux_fan"));
        assertEquals("No status", HomeAssistant.state(null, "not connected", 0).getString("state"));
    }
    /** Against a real MQTT listener when HA_TEST_PORT is set (tools: a minimal broker that records publishes); skipped otherwise. */
    @Test public void publishesDiscoveryStateAndPictureToABroker() throws Exception {
        String port = System.getenv("HA_TEST_PORT"); if (port == null) return;
        HomeAssistant ha = new HomeAssistant(HomeAssistant.paho());
        JSONObject state = HomeAssistant.state(new JSONObject().put("machine_status", new JSONObject().put("status", 1)), "local network", 0);
        ha.publish("127.0.0.1", Integer.parseInt(port), false, "user", "secret", null, "cc2_test0001", "Bedroom", state, new byte[] {1, 2, 3}, "p1", 1000);
        assertTrue(ha.lastResult, ha.lastResult.startsWith("Sending to Home Assistant"));
        ha.publish("127.0.0.1", Integer.parseInt(port), false, "user", "secret", null, "cc2_test0001", "Bedroom", state, new byte[] {1, 2, 3}, "p1", 2000);
        ha.stop("cc2_test0001");
    }
}
