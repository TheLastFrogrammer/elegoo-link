package io.github.thelastfrogrammer.elink;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import org.json.JSONObject;
import org.junit.Test;

public class AutomationTest {
    private static JSONObject status(int state, int sub, String file, int layer, int progress) throws Exception {
        return new JSONObject().put("machine_status", new JSONObject().put("status", state).put("sub_status", sub).put("progress", progress))
            .put("print_status", new JSONObject().put("filename", file).put("uuid", file.isEmpty() ? "" : "id-" + file).put("current_layer", layer).put("total_layer", 100).put("remaining_time_sec", 600));
    }
    private static List<String> types(List<Automation.Event> events) { List<String> out = new ArrayList<>(); for (Automation.Event e : events) out.add(e.type); return out; }

    @Test public void detectsWhatItSawHappen() throws Exception {
        Automation.Detector d = new Automation.Detector();
        assertTrue(d.update(status(1, 0, "", 0, 0), "P").isEmpty());
        assertEquals(List.of(Automation.STARTED), types(d.update(status(2, 2075, "cube.gcode", 0, 0), "P")));
        assertTrue(types(d.update(status(2, 2075, "cube.gcode", 1, 1), "P")).contains(Automation.LAYER));
        List<String> second = types(d.update(status(2, 2075, "cube.gcode", 2, 2), "P"));
        assertTrue(second.contains(Automation.FIRST_LAYER));
        assertEquals(List.of(Automation.PAUSED), types(d.update(status(2, 2502, "cube.gcode", 2, 2), "P")));
        assertTrue(d.update(status(2, 2502, "cube.gcode", 2, 2), "P").isEmpty());
        assertEquals(List.of(Automation.RESUMED), types(d.update(status(2, 2075, "cube.gcode", 2, 2), "P")));
        assertEquals(List.of(Automation.FINISHED), types(d.update(status(2, 2077, "cube.gcode", 100, 100), "P")));
        assertTrue("once", d.update(status(2, 2077, "cube.gcode", 100, 100), "P").isEmpty());
    }

    @Test public void aPrintFoundHalfwayIsNotReportedAsStarted() throws Exception {
        Automation.Detector d = new Automation.Detector();
        assertTrue(d.update(status(2, 2075, "big.gcode", 50, 40), "P").isEmpty());
        Automation.Event layer = d.update(status(2, 2075, "big.gcode", 51, 41), "P").get(0);
        assertEquals(Automation.LAYER, layer.type); assertEquals(50, layer.fromLayer); assertEquals(51, layer.layer);
        // ...but its end is, since it was seen printing.
        assertEquals(List.of(Automation.STOPPED), types(d.update(status(2, 2504, "big.gcode", 51, 41), "P")));
        // A print seen only once it is already complete is not reported.
        Automation.Detector late = new Automation.Detector();
        assertTrue(late.update(status(2, 2077, "done.gcode", 100, 100), "P").isEmpty());
    }

    @Test public void reportsNewFaultsOnce() throws Exception {
        Automation.Detector d = new Automation.Detector();
        JSONObject fault = status(2, 2075, "a.gcode", 3, 3).put("exception", new JSONObject().put("exception_code", new JSONObject().put("305", 1)));
        d.update(status(2, 2075, "a.gcode", 3, 3), "P");
        List<String> first = types(d.update(fault, "P"));
        assertFalse(StatusPresentation.faultCodes(fault).isEmpty());
        assertTrue(first.contains(Automation.FAULT)); assertFalse(types(d.update(fault, "P")).contains(Automation.FAULT));
    }

    @Test public void rulesMatchEventsTimesAndThisPrintsChoices() {
        Automation.Rule off = new Automation.Rule(); off.event = Automation.FINISHED; off.lastPrintOnly = true;
        Automation.Event done = new Automation.Event(Automation.FINISHED); done.key = "job1";
        Automation.ThisPrint print = new Automation.ThisPrint(); print.key = "job1";
        assertFalse("not marked as the last print", Automation.matches(off, done, 600, print));
        print.lastOfDay = true;
        assertTrue(Automation.matches(off, done, 600, print));
        Automation.Event other = new Automation.Event(Automation.FINISHED); other.key = "job2";
        assertFalse("the mark belongs to its own print", Automation.matches(off, other, 600, print));
        print.muted.add(off.id);
        assertFalse("muted for this print", Automation.matches(off, done, 600, print));
        off.enabled = false; print.muted.clear();
        assertFalse(Automation.matches(off, done, 600, print));

        Automation.Rule night = new Automation.Rule(); night.event = Automation.ANY; night.fromMinute = 22 * 60; night.toMinute = 7 * 60;
        assertTrue(Automation.matches(night, done, 23 * 60, null)); assertTrue(Automation.matches(night, done, 60, null));
        assertFalse(Automation.matches(night, done, 12 * 60, null));
        Automation.Event layer = new Automation.Event(Automation.LAYER); layer.fromLayer = 9; layer.layer = 10;
        assertFalse("any means milestones, not each layer", Automation.matches(night, layer, 23 * 60, null));

        Automation.Rule at = new Automation.Rule(); at.event = Automation.LAYER; at.layer = 12;
        Automation.Event jump = new Automation.Event(Automation.LAYER); jump.fromLayer = 10; jump.layer = 13;
        assertTrue("a skipped-over layer still counts", Automation.matches(at, jump, 0, null));
        jump.fromLayer = 12; assertFalse(Automation.matches(at, jump, 0, null));
    }

    @Test public void fillsTemplatesAndEscapesJson() throws Exception {
        Automation.Event e = new Automation.Event(Automation.FINISHED); e.file = "my \"best\" part.gcode"; e.layer = 5; e.total = 10; e.progress = 50; e.printer = "Shop";
        Map<String, String> v = e.values(0, TimeZone.getTimeZone("UTC"));
        assertEquals("Shop: my \"best\" part done {unknown}", Automation.fill("{printer}: {file} done {unknown}", v, false).replace(".gcode", ""));
        Automation.Action hook = new Automation.Action(); hook.kind = Automation.WEBHOOK; hook.body = "{\"content\": \"{file} at {layer}/{total_layers}\"}";
        JSONObject sent = new JSONObject(Automation.body(hook, v));
        assertTrue(sent.getString("content").contains("\"best\"")); assertTrue(sent.getString("content").endsWith("at 5/10"));
        hook.body = "";
        JSONObject all = new JSONObject(Automation.body(hook, v));
        assertEquals("print_finished", all.getString("event")); assertEquals("00:00", all.getString("time"));
    }

    @Test public void sharingLeavesOutSecretsAndImportsStartOff() throws Exception {
        Automation.Rule r = new Automation.Rule(); r.name = "Discord"; r.event = Automation.FINISHED;
        Automation.Action hook = new Automation.Action(); hook.kind = Automation.WEBHOOK; hook.url = "https://example.com/hook"; hook.header = "Authorization"; r.actions.add(hook);
        String shared = Automation.share(List.of(r));
        assertFalse(shared.contains(hook.secretId)); assertFalse(shared.contains(r.id)); assertFalse(shared.contains("enabled"));
        List<Automation.Rule> back = Automation.parseShared("Look at this:\n" + shared);
        assertEquals(1, back.size()); assertFalse(back.get(0).enabled); assertEquals("Authorization", back.get(0).actions.get(0).header);
        assertFalse("a new place for its secret", back.get(0).actions.get(0).secretId.equals(hook.secretId));
        // Saved rules keep everything.
        Automation.Rule kept = Automation.load(Automation.save(List.of(r))).get(0);
        assertEquals(r.id, kept.id); assertEquals(hook.secretId, kept.actions.get(0).secretId);
    }

    @Test public void refusesWhatItDoesNotKnow() {
        for (String bad : new String[] {"{\"event\": \"print_finished\", \"actions\": [{\"kind\": \"start_print\"}]}", "{\"event\": \"explode\", \"actions\": [{\"kind\": \"notify\"}]}",
            "{\"event\": \"print_finished\", \"actions\": []}", "{\"event\": \"print_finished\", \"actions\": [{\"kind\": \"webhook\", \"url\": \"file:///sdcard/x\"}]}",
            "{\"linkWorkshopAutomation\": 2, \"rules\": []}", "nothing here"}) {
            try { Automation.parseShared(bad); fail("accepted " + bad); } catch (org.json.JSONException expected) { }
        }
    }

    @Test public void defaultsAndTemplatesAreValidAndLightRulesStartOff() throws Exception {
        for (Automation.Rule r : Automation.defaults()) { assertTrue(r.builtIn); if (r.changesPrinter()) assertFalse(r.name, r.enabled); }
        for (Automation.Rule t : Automation.templates()) assertEquals(t.name, 1, Automation.parseShared(Automation.share(List.of(t))).size());
        Automation.Rule last = Automation.defaults().get(2);
        assertEquals("A print finishes · last print of the day · wait 10 min → Turn the light off", last.summary());
    }
}
