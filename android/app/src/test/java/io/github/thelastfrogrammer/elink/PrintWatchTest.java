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
}
