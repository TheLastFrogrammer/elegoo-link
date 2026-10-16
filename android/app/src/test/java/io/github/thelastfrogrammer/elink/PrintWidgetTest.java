package io.github.thelastfrogrammer.elink;

import java.util.Locale;
import java.util.TimeZone;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class PrintWidgetTest {
    @Test public void showsTheRunningPrintAndTheIdlePrinter() throws Exception {
        JSONObject printing = new JSONObject()
            .put("machine_status", new JSONObject().put("status", 2).put("sub_status", 2075).put("progress", 41))
            .put("print_status", new JSONObject().put("filename", "Benchy_PLA.gcode").put("current_layer", 87).put("total_layer", 212).put("remaining_time_sec", 4520))
            .put("extruder", new JSONObject().put("temperature", 219.6).put("target", 220))
            .put("heater_bed", new JSONObject().put("temperature", 59.8)).put("ztemperature_sensor", new JSONObject().put("temperature", 31.0));
        TimeZone utc = TimeZone.getTimeZone("UTC");
        PrintWidget.Shown s = PrintWidget.shown(printing, "Bedroom", 0, Locale.ROOT, utc);
        assertEquals("Benchy_PLA", s.name); assertEquals("41%", s.percent); assertEquals(41, s.progress);
        assertEquals("1h 15m left · done 01:15 · layer 87/212", s.time);
        assertEquals("Nozzle 220° · Bed 60° · Chamber 31°", s.temps);
        PrintWidget.Shown idle = PrintWidget.shown(new JSONObject().put("machine_status", new JSONObject().put("status", 1)), "Bedroom", 0, Locale.ROOT, utc);
        assertEquals("Bedroom", idle.name); assertEquals(-1, idle.progress); assertEquals("", idle.percent);
        assertEquals("No printer status yet", PrintWidget.shown(null, "Bedroom", 0, Locale.ROOT, utc).state);
    }
}
