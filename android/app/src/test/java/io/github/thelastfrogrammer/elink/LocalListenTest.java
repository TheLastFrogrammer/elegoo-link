package io.github.thelastfrogrammer.elink;

import org.junit.Test;
import static org.junit.Assert.*;

public class LocalListenTest {
    @Test public void topicsAreMaskedAndResultsSaidPlainly() {
        assertEquals("elegoo/<sn>/api_status", LocalListen.mask("elegoo/F01ABC23/api_status", "F01ABC23"));
        assertEquals("elegoo/<sn>/<client>/api_response", LocalListen.mask("elegoo/F01ABC23/1_PC_1234/api_response", "F01ABC23"));
        LocalListen.Result r = new LocalListen.Result(); r.failure = "MqttException: Not authorized";
        assertTrue(r.verdict(30).contains("refused the login (not authorized)")); assertTrue(r.report().contains("login refused"));
        r = new LocalListen.Result(); r.connected = true; r.subscribed = true;
        assertTrue(r.verdict(30).contains("published nothing locally in 30 seconds"));
        r.messages = 3; r.topics.put("elegoo/<sn>/api_status", 3); r.firstFields = "result:object";
        assertTrue(r.verdict(30).contains("published 3 messages"));
        assertEquals("local broker: login accepted, subscription accepted, 3 messages on elegoo/<sn>/api_status ×3; first payload fields: result:object", r.report());
        assertTrue(FolderMatch.sameName("Hook.GCODE", " hook.gcode")); assertFalse(FolderMatch.sameName("a.gcode", null));
    }
}
