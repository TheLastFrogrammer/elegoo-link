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

    /** Bed levelling (and other upkeep) finishing: reported once, by the printer's own done step or by it moving on. */
    @Test public void maintenanceTasksReportWhenTheyFinish() throws Exception {
        Automation.Detector d = new Automation.Detector();
        assertTrue(d.update(status(1, 0, "", 0, 0), "P").isEmpty());
        assertTrue("levelling starts: nothing yet", d.update(status(5, 2901, "", 0, 0), "P").isEmpty());
        List<Automation.Event> done = d.update(status(5, 2902, "", 0, 0), "P");
        assertEquals(List.of(Automation.MAINTENANCE, Automation.LEVELLED), types(done));
        assertEquals("Bed levelling done", done.get(1).detail);
        assertTrue("once", d.update(status(5, 2902, "", 0, 0), "P").isEmpty());
        assertTrue("back to idle: already reported", d.update(status(1, 0, "", 0, 0), "P").isEmpty());
        // Ended without a done step (straight back to idle): still reported, as finished.
        d.update(status(5, 2901, "", 0, 0), "P");
        List<Automation.Event> ended = d.update(status(1, 0, "", 0, 0), "P");
        assertEquals("Bed levelling finished", ended.get(1).detail);
        // A failed vibration test.
        d.update(status(7, 5934, "", 0, 0), "P");
        List<Automation.Event> failed = d.update(status(7, 5936, "", 0, 0), "P");
        assertEquals(List.of(Automation.MAINTENANCE), types(failed)); assertEquals("Vibration test failed", failed.get(0).detail);
        // Already done when first seen (the app opened afterwards): not reported.
        Automation.Detector late = new Automation.Detector();
        assertTrue(late.update(status(5, 2902, "", 0, 0), "P").isEmpty());
        assertTrue(late.update(status(1, 0, "", 0, 0), "P").isEmpty());
        // "Any print milestone" rules do not fire for upkeep; a levelling rule does.
        Automation.Rule any = new Automation.Rule(); any.event = Automation.ANY;
        Automation.Rule bed = new Automation.Rule(); bed.event = Automation.LEVELLED;
        assertFalse(Automation.matches(any, done.get(1), 600, null));
        assertTrue(Automation.matches(bed, done.get(1), 600, null));
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
        // The cloud's polled status and its live reports can name the same print with and without its uuid.
        Automation.ThisPrint saved = new Automation.ThisPrint(); saved.key = "Clip-1.gcode"; saved.file = "Clip-1.gcode"; saved.lastOfDay = true;
        Automation.Event withUuid = new Automation.Event(Automation.FINISHED); withUuid.key = "4f1c-uuid"; withUuid.file = "Clip-1.gcode";
        assertTrue("marked while the status had no uuid, finished with one", Automation.matches(off, withUuid, 600, saved));
        saved = Automation.ThisPrint.fromJson(new Automation.ThisPrint().forPrint("4f1c-uuid", "Clip-1.gcode").toJsonOrEmpty());
        saved.lastOfDay = true;
        Automation.Event withoutUuid = new Automation.Event(Automation.FINISHED); withoutUuid.key = "Clip-1.gcode"; withoutUuid.file = "Clip-1.gcode";
        assertTrue("marked with the uuid, finished without it", Automation.matches(off, withoutUuid, 600, saved));
        Automation.Event another = new Automation.Event(Automation.FINISHED); another.key = "9a9a-uuid"; another.file = "Lid.gcode";
        assertFalse(Automation.matches(off, another, 600, saved));
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

    private static JSONObject full() throws Exception {
        return status(2, 2075, "cube.gcode", 12, 30)
            .put("extruder", new JSONObject().put("temperature", 219.64).put("target", 220)).put("heater_bed", new JSONObject().put("temperature", 60).put("target", 60))
            .put("ztemperature_sensor", new JSONObject().put("temperature", 38.2).put("measured_max_temperature", 41.5).put("measured_min_temperature", 22))
            .put("fans", new JSONObject().put("fan", new JSONObject().put("speed", 255).put("rpm", 9000)).put("heater_fan", new JSONObject().put("speed", 255).put("rpm", 7400)))
            .put("gcode_move_inf", new JSONObject().put("x", 100.5).put("y", 80).put("z", 2.4).put("e", 1234.56).put("speed_mode", 1))
            .put("led", new JSONObject().put("status", 1)).put("external_device", new JSONObject().put("u_disk", true).put("camera", true))
            .put("system", new JSONObject().put("sn", "SECRET123").put("hostname", "my-printer").put("version", "1.2"));
    }

    @Test public void namedValuesComeFromTheStatus() throws Exception {
        JSONObject s = full(); s.getJSONObject("print_status").put("print_duration", 4320).put("total_duration", 4500);
        JSONObject canvas = new JSONObject().put("active_canvas_id", 0).put("active_tray_id", 1).put("canvas_list", new org.json.JSONArray().put(new JSONObject().put("canvas_id", 0)
            .put("tray_list", new org.json.JSONArray().put(new JSONObject().put("tray_id", 1).put("filament_type", "PETG").put("brand", "Acme").put("filament_color", "#FF0000").put("min_nozzle_temp", 230).put("max_nozzle_temp", 260)))));
        Map<String, String> v = AutomationValues.from(s, canvas, Map.of("pictures", "7"), 0, java.util.Locale.ROOT, TimeZone.getTimeZone("UTC"));
        assertEquals("219.6", v.get("nozzle")); assertEquals("41.5", v.get("chamber_max")); assertEquals("100", v.get("heatsink_fan")); assertEquals("7400", v.get("heatsink_fan_rpm"));
        assertEquals("1234.6", v.get("extruded_mm")); assertEquals("on", v.get("light")); assertEquals("yes", v.get("usb_stick")); assertEquals("4320", v.get("elapsed_s"));
        assertEquals("2", v.get("tray")); assertEquals("PETG", v.get("filament_type")); assertEquals("260", v.get("filament_max_nozzle")); assertEquals("7", v.get("pictures"));
        for (String key : v.keySet()) assertTrue("catalogued: " + key, AutomationValues.find(key) != null);
        Map<String, String> raw = AutomationValues.raw(s);
        assertEquals("1.2", raw.get("status.system.version")); assertEquals("7400", raw.get("status.fans.heater_fan.rpm"));
        assertFalse(raw.containsKey("status.system.sn")); assertFalse(raw.containsKey("status.system.hostname"));
        assertEquals(1, AutomationValues.number(v, "light"), 0); assertEquals(42, AutomationValues.number(Map.of("remaining", "42 min"), "remaining"), 0);
    }

    @Test public void valueRulesFireOnCrossingOnceUntilBackOnTheSafeSide() {
        Automation.Rule hot = new Automation.Rule(); hot.event = Automation.VALUE; hot.field = "chamber"; hot.above = true; hot.limit = 40;
        Automation.Watcher w = new Automation.Watcher(); List<Automation.Rule> rules = List.of(hot);
        assertTrue("already past when first seen: no event", w.check(rules, Map.of("chamber", "45"), 0, null).isEmpty());
        assertTrue(w.check(rules, Map.of("chamber", "38"), 1, null).isEmpty());
        List<Automation.Event> crossed = w.check(rules, Map.of("chamber", "40"), 2, null);
        assertEquals(1, crossed.size()); assertEquals(hot.id, crossed.get(0).ruleId); assertTrue(crossed.get(0).detail.contains("reached 40"));
        assertTrue("wobbling at the limit does not fire again", w.check(rules, Map.of("chamber", "39.5"), 3, null).isEmpty());
        assertTrue(w.check(rules, Map.of("chamber", "40.2"), 4, null).isEmpty());
        assertTrue(w.check(rules, Map.of("chamber", "38"), 5, null).isEmpty());
        assertEquals(1, w.check(rules, Map.of("chamber", "41"), 6, null).size());

        Automation.Rule tray = new Automation.Rule(); tray.event = Automation.CHANGE; tray.field = "tray";
        Automation.Watcher c = new Automation.Watcher();
        assertTrue(c.check(List.of(tray), Map.of("tray", "1"), 0, null).isEmpty());
        assertEquals("{tray} 1 → 2", c.check(List.of(tray), Map.of("tray", "2"), 1000, null).get(0).detail);
        assertTrue("at most once a minute", c.check(List.of(tray), Map.of("tray", "3"), 2000, null).isEmpty());
        assertEquals(1, c.check(List.of(tray), Map.of("tray", "4"), 70_000, null).size());
    }

    @Test public void theExtraConditionAndValueEventsMatchTheirOwnRule() {
        Automation.Rule fan = new Automation.Rule(); fan.event = Automation.VALUE; fan.field = "heatsink_fan_rpm"; fan.above = false; fan.limit = 500;
        fan.ifField = "nozzle"; fan.ifAbove = true; fan.ifLimit = 150;
        Automation.Event e = new Automation.Event(Automation.VALUE); e.ruleId = fan.id;
        assertTrue(Automation.matches(fan, e, 0, null, Map.of("nozzle", "210")));
        assertFalse("cold nozzle", Automation.matches(fan, e, 0, null, Map.of("nozzle", "40")));
        assertFalse("value missing", Automation.matches(fan, e, 0, null, Map.of()));
        Automation.Event other = new Automation.Event(Automation.VALUE); other.ruleId = "someone-else";
        assertFalse(Automation.matches(fan, other, 0, null, Map.of("nozzle", "210")));
        Automation.Rule any = new Automation.Rule(); any.event = Automation.ANY;
        assertFalse("any is milestones only", Automation.matches(any, e, 0, null, Map.of()));
    }

    @Test public void valueRulesAndAttachmentsShareAndCheckTheirFields() throws Exception {
        Automation.Rule r = new Automation.Rule(); r.event = Automation.VALUE; r.field = "status.fans.heater_fan.rpm"; r.above = false; r.limit = 500.5;
        Automation.Action a = new Automation.Action(); a.kind = Automation.WEBHOOK; a.url = "https://ntfy.sh/x"; a.picture = true; a.chart = true; a.rawImage = true; r.actions.add(a);
        Automation.Rule back = Automation.parseShared(Automation.share(List.of(r))).get(0);
        assertEquals("status.fans.heater_fan.rpm", back.field); assertEquals(500.5, back.limit, 0); assertFalse(back.above);
        assertTrue(back.actions.get(0).picture && back.actions.get(0).chart && back.actions.get(0).rawImage);
        assertTrue(back.summary().startsWith("{status.fans.heater_fan.rpm} goes below 500.5"));
        for (String bad : new String[] {"{\"event\": \"value_crossed\", \"field\": \"password\", \"actions\": [{\"kind\": \"notify\"}]}",
            "{\"event\": \"value_crossed\", \"actions\": [{\"kind\": \"notify\"}]}"})
            try { Automation.parseShared(bad); fail("accepted " + bad); } catch (org.json.JSONException expected) { }
    }

    @Test public void multipartCarriesTheTextAndBothImages() throws Exception {
        byte[] form = AutomationRun.Multipart.build("B", "{\"content\": \"hi\"}", true, new byte[] {1, 2}, new byte[] {3});
        String text = new String(form, java.nio.charset.StandardCharsets.ISO_8859_1);
        assertTrue(text.startsWith("--B\r\nContent-Disposition: form-data; name=\"payload_json\""));
        assertTrue(text.contains("name=\"files[0]\"; filename=\"picture.jpg\""));
        assertTrue(text.contains("name=\"files[1]\"; filename=\"chart.png\""));
        assertTrue(text.endsWith("--B--\r\n"));
        assertTrue(new String(AutomationRun.Multipart.build("B", "plain", false, null, new byte[] {3}), java.nio.charset.StandardCharsets.ISO_8859_1).contains("name=\"content\""));
    }
}
