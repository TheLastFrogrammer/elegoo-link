package io.github.thelastfrogrammer.elink;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class PrintWatchTest {
    private static final long MIN = 60_000L;
    private static JSONObject status(int sub, int layer, int progress, double nozzle, double nozzleTarget, double bed, double bedTarget) throws Exception {
        return new JSONObject().put("machine_status", new JSONObject().put("status", 2).put("sub_status", sub).put("progress", progress))
            .put("print_status", new JSONObject().put("uuid", "job").put("filename", "a.gcode").put("current_layer", layer).put("total_layer", 200).put("remaining_time_sec", 6000))
            .put("extruder", new JSONObject().put("temperature", nozzle).put("target", nozzleTarget)).put("heater_bed", new JSONObject().put("temperature", bed).put("target", bedTarget));
    }

    @Test public void aStalledPrintIsReportedOnceAndNotWhilePaused() throws Exception {
        PrintWatch w = new PrintWatch();
        assertEquals("", w.update(status(2075, 10, 5, 220, 220, 60, 60), 0));
        assertEquals("", w.update(status(2075, 10, 5, 220, 220, 60, 60), 19 * MIN));
        String stuck = w.update(status(2075, 10, 5, 220, 220, 60, 60), 21 * MIN);
        assertTrue(stuck, stuck.startsWith("Print may be stuck: still layer 10 at 5% after 21 min."));
        assertEquals("once", "", w.update(status(2075, 10, 5, 220, 220, 60, 60), 40 * MIN));
        PrintWatch paused = new PrintWatch();
        paused.update(status(2075, 10, 5, 220, 220, 60, 60), 0);
        assertEquals("paused for an hour: not stuck", "", paused.update(status(2502, 10, 5, 220, 220, 60, 60), 60 * MIN));
        assertEquals("printing again: the clock starts again", "", paused.update(status(2075, 10, 5, 220, 220, 60, 60), 61 * MIN));
        assertEquals("", paused.update(status(2075, 10, 5, 220, 220, 60, 60), 75 * MIN));
    }

    @Test public void slowLayersGetMoreTime() throws Exception {
        PrintWatch w = new PrintWatch();
        JSONObject print = status(2075, 10, 5, 220, 220, 60, 60).getJSONObject("print_status").put("remaining_time_sec", 191 * 600);   // 10 min a layer
        assertEquals(40 * MIN, w.stallLimit(print));
    }

    @Test public void aHeaterFarFromItsTargetIsReportedAfterAGraceAndHold() throws Exception {
        PrintWatch w = new PrintWatch();
        int layer = 1;
        assertEquals("", w.update(status(2075, layer++, 1, 150, 220, 60, 60), 0));                       // still heating: grace
        assertEquals("", w.update(status(2075, layer++, 2, 190, 220, 60, 60), 4 * MIN));                 // away, hold starts
        String cold = w.update(status(2075, layer++, 3, 190, 220, 60, 60), 8 * MIN);
        assertTrue(cold, cold.startsWith("Nozzle is 30 °C below its target (190 of 220 °C)"));
        assertEquals("once", "", w.update(status(2075, layer++, 4, 190, 220, 60, 60), 12 * MIN));
        assertEquals("a new target starts over, with a grace period", "", w.update(status(2075, layer++, 5, 190, 240, 60, 60), 13 * MIN));
        assertEquals("within 15 °C is fine", "", w.update(status(2075, layer, 6, 230, 240, 52, 60), 30 * MIN));
    }

    private static JSONObject withFans(JSONObject s, double heatsinkRpm, double boardSpeed, double boardRpm) throws Exception {
        return s.put("fans", new JSONObject().put("heater_fan", new JSONObject().put("speed", 255).put("rpm", heatsinkRpm))
            .put("controller_fan", new JSONObject().put("speed", boardSpeed).put("rpm", boardRpm)));
    }
    @Test public void aStoppedHeatsinkFanIsReportedOnlyOnceItWasSeenTurning() throws Exception {
        PrintWatch never = new PrintWatch();
        for (int i = 0; i <= 5; i++) assertEquals("a fan always at 0 rpm is not measured", "", never.update(withFans(status(2075, 10, 5, 220, 220, 60, 60), 0, 0, 0), i * MIN));
        PrintWatch w = new PrintWatch();
        assertEquals("", w.update(withFans(status(2075, 10, 5, 220, 220, 60, 60), 7400, 0, 0), 0));
        assertEquals("", w.update(withFans(status(2075, 10, 5, 220, 220, 60, 60), 0, 0, 0), MIN / 2));
        assertEquals("under a minute", "", w.update(withFans(status(2075, 10, 5, 220, 220, 60, 60), 0, 0, 0), MIN));
        String stopped = w.update(withFans(status(2075, 10, 5, 220, 220, 60, 60), 0, 0, 0), 2 * MIN);
        assertTrue(stopped, stopped.contains("heatsink fan") && stopped.contains("220"));
        assertEquals("once", "", w.update(withFans(status(2075, 10, 5, 220, 220, 60, 60), 0, 0, 0), 3 * MIN));
        PrintWatch cold = new PrintWatch();
        cold.update(withFans(status(2075, 10, 5, 220, 220, 60, 60), 7400, 0, 0), 0);
        for (int i = 1; i <= 5; i++) assertFalse(cold.update(withFans(status(2075, 10, 5, 90, 90, 60, 60), 0, 0, 0), i * MIN).contains("heatsink"));
    }
    @Test public void theElectronicsFanCountsOnlyWhenToldToRun() throws Exception {
        PrintWatch w = new PrintWatch();
        w.update(withFans(status(2075, 10, 5, 220, 220, 60, 60), 7400, 255, 6000), 0);
        assertEquals("off by command", "", w.update(withFans(status(2075, 10, 5, 220, 220, 60, 60), 7400, 0, 0), 5 * MIN));
        w.update(withFans(status(2075, 10, 5, 220, 220, 60, 60), 7400, 255, 0), 6 * MIN);
        assertTrue(w.update(withFans(status(2075, 10, 5, 220, 220, 60, 60), 7400, 255, 0), 9 * MIN).contains("electronics fan"));
    }
    @Test public void aNozzleTargetOutsideTheFilamentsRangeIsReportedOnce() throws Exception {
        PrintWatch w = new PrintWatch();
        w.canvas = new JSONObject().put("active_canvas_id", 0).put("active_tray_id", 2).put("canvas_list", new org.json.JSONArray().put(new JSONObject().put("canvas_id", 0)
            .put("tray_list", new org.json.JSONArray().put(new JSONObject().put("tray_id", 2).put("filament_type", "PLA").put("min_nozzle_temp", 190).put("max_nozzle_temp", 230)))));
        assertEquals("", w.update(status(2075, 10, 5, 220, 220, 60, 60), 0));
        assertEquals("within 5 °C", "", w.update(status(2075, 10, 5, 234, 234, 60, 60), MIN));
        String hot = w.update(status(2075, 10, 5, 250, 250, 60, 60), 2 * MIN);
        assertTrue(hot, hot.contains("250") && hot.contains("PLA") && hot.contains("190–230"));
        assertEquals("once", "", w.update(status(2075, 10, 5, 250, 250, 60, 60), 3 * MIN));
    }
}
