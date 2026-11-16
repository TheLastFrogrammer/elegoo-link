package io.github.thelastfrogrammer.elink;

import static org.junit.Assert.*;

import java.util.Arrays;
import org.junit.Test;

/** Layers that push the hot end near the filament's flow limit, and whether a nozzle drop happened on one. */
public class FlowCheckTest {
    static LayerProfile profile(double limit, double... flows) {
        LayerProfile p = new LayerProfile(); p.layers = flows.length; p.maxFlow = limit;
        p.flowMax = Arrays.copyOf(flows, Math.max(256, flows.length));
        return p;
    }

    @Test public void layersNearTheLimitAreNamed() {
        LayerProfile p = profile(21, 5, 19.5, 20.1, 19.0, 8, 8, 20.5, 3);
        assertEquals(Arrays.asList(2, 3, 4, 7), FlowCheck.nearLimit(p));
        assertEquals("2–4, 7", FlowCheck.ranges(FlowCheck.nearLimit(p)));
        String w = FlowCheck.sliceWords(p);
        assertTrue(w, w.startsWith("Flow: 4 layers (2–4, 7) ask for up to 20.5 mm³/s, 98 % of this filament's 21 mm³/s limit."));
        assertEquals("", FlowCheck.sliceWords(profile(21, 5, 6, 7)));
        assertEquals("no limit in the file: nothing said", "", FlowCheck.sliceWords(profile(0, 50, 60)));
    }

    @Test public void aNozzleDropIsSetAgainstItsLayersFlow() {
        LayerProfile p = profile(21, 5, 19.5, 6);
        assertTrue(FlowCheck.dropWords(p, 2).startsWith("Layer 2 asks for up to 19.5 mm³/s, 93 % of the filament's 21 mm³/s limit: the heater most likely lost ground"));
        assertTrue(FlowCheck.dropWords(p, 3).contains("flow is not the likely cause"));
        assertEquals("", FlowCheck.dropWords(p, 9)); assertEquals("", FlowCheck.dropWords(profile(0, 5), 1));
        // The finding carries the layer of the worst reading.
        double[] t = {0, 10, 20, 30}, layer = {1, 2, 2, 3}, nozzle = {210, 209, 195, 208}, target = {210, 210, 210, 210}, sub = {2075, 2075, 2075, 2075};
        assertEquals(2, RecordingAnalysis.dropLayer(t, layer, nozzle, target, sub, 8));
    }
}
