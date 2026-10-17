package io.github.thelastfrogrammer.elink;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Gauntlet for the automation workshop, its values, the value watcher, the print guardian's maths, the chamber trend, the
 * recorder's new figures and the cloud listener: odd printer reports, hostile shared rules and edge times.
 */
public class FailureAutomationTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    private static JSONObject status(int state, int sub, String file, String uuid, int layer, int progress) throws Exception {
        JSONObject print = new JSONObject().put("filename", file).put("current_layer", layer).put("total_layer", 100);
        if (uuid != null) print.put("uuid", uuid);
        return new JSONObject().put("machine_status", new JSONObject().put("status", state).put("sub_status", sub).put("progress", progress)).put("print_status", print);
    }
    private static List<String> types(List<Automation.Event> events) { List<String> out = new ArrayList<>(); for (Automation.Event e : events) out.add(e.type); return out; }

    // ---- the event detector

    @Test public void oddReportsDoNotCrashTheDetector() throws Exception {
        Automation.Detector d = new Automation.Detector();
        assertTrue(d.update(null, "P").isEmpty());
        assertTrue(d.update(new JSONObject(), "P").isEmpty());
        assertTrue(d.update(new JSONObject().put("machine_status", new JSONObject()), "P").isEmpty());
        assertTrue(d.update(new JSONObject().put("machine_status", new JSONObject().put("status", 2)), null).isEmpty());
        assertTrue("printing with no file name", d.update(status(2, 2075, "", null, 3, 3), "P").isEmpty());
        assertTrue(d.update(new JSONObject().put("machine_status", "broken").put("print_status", 7), "P").isEmpty());
    }

    @Test public void theSameFilePrintedAgainWithoutAnIdleReportIsANewPrint() throws Exception {
        // Through the cloud the printer may go from "complete" straight into the next print between two polls.
        Automation.Detector d = new Automation.Detector();
        d.update(status(1, 0, "", null, 0, 0), "P");
        assertEquals(List.of(Automation.STARTED), types(d.update(status(2, 2075, "cube.gcode", null, 0, 0), "P")));
        d.update(status(2, 2075, "cube.gcode", null, 50, 60), "P");
        assertEquals(List.of(Automation.FINISHED), types(d.update(status(2, 2077, "cube.gcode", null, 100, 100), "P")));
        List<String> again = types(d.update(status(2, 2075, "cube.gcode", null, 0, 0), "P"));
        assertEquals("print again: started", List.of(Automation.STARTED), again);
        d.update(status(2, 2075, "cube.gcode", null, 40, 50), "P");
        assertEquals("and finished again", List.of(Automation.FINISHED), types(d.update(status(2, 2077, "cube.gcode", null, 100, 100), "P")));
    }

    @Test public void aLayerCountThatGoesBackIsNotACrossing() throws Exception {
        Automation.Detector d = new Automation.Detector();
        d.update(status(2, 2075, "a.gcode", "u1", 0, 0), "P");
        d.update(status(2, 2075, "a.gcode", "u1", 10, 10), "P");
        List<Automation.Event> back = d.update(status(2, 2075, "a.gcode", "u1", 4, 10), "P");   // a stale cloud report
        assertTrue(types(back).isEmpty());
        List<Automation.Event> on = d.update(status(2, 2075, "a.gcode", "u1", 11, 11), "P");
        assertEquals(1, on.size()); assertEquals(4, on.get(0).fromLayer);
    }

    // ---- matching and time windows

    @Test public void aWindowWithTheSameStartAndEndMeansAllDay() {
        assertTrue(Automation.inWindow(0, 600, 600)); assertTrue(Automation.inWindow(900, 600, 600));
        assertTrue(Automation.inWindow(1439, 22 * 60, 7 * 60)); assertFalse(Automation.inWindow(7 * 60, 22 * 60, 7 * 60));
    }

    @Test public void thisPrintsChoicesSurviveGarbage() {
        Automation.ThisPrint p = Automation.ThisPrint.fromJson("not json");
        assertEquals("", p.key); assertFalse(p.lastOfDay);
        assertTrue(Automation.ThisPrint.fromJson("{\"key\":5,\"muted\":\"x\"}").muted.isEmpty());
        assertNull(null, Automation.ThisPrint.fromJson(null).key.isEmpty() ? null : "x");
    }

    // ---- shared rules from strangers

    @Test public void hostileSharedRulesAreTrimmedOrRefused() throws Exception {
        StringBuilder huge = new StringBuilder(); for (int i = 0; i < 20_000; i++) huge.append('x');
        JSONObject rule = new JSONObject().put("name", huge.toString()).put("event", "print_finished")
            .put("actions", new JSONArray().put(new JSONObject().put("kind", "notify").put("title", huge.toString()).put("text", huge.toString())));
        Automation.Rule r = Automation.parseShared(rule.toString()).get(0);
        assertTrue("name capped", r.name.length() <= 80);
        assertTrue("texts capped", r.actions.get(0).title.length() <= 200 && r.actions.get(0).text.length() <= 2000);
        for (String bad : new String[] {
            "{\"event\": \"print_finished\", \"actions\": [{\"kind\": \"webhook\", \"url\": \"javascript:alert(1)\"}]}",
            "{\"event\": \"print_finished\", \"actions\": [{\"kind\": \"webhook\", \"url\": \"https://\"}]}",
            "{\"event\": \"print_finished\", \"delay\": 1e400, \"actions\": [{\"kind\": \"notify\"}]}",
            "{\"event\": \"value_crossed\", \"field\": \"chamber\", \"limit\": \"NaN\", \"actions\": [{\"kind\": \"notify\"}]}",
            "[" + "[".repeat(5000) + "]".repeat(5000) + "]"}) {
            try { Automation.parseShared(bad); fail("accepted " + bad.substring(0, Math.min(80, bad.length()))); } catch (Exception expected) { }
        }
        // A header name that would break the request is refused.
        try { Automation.parseShared("{\"event\": \"print_finished\", \"actions\": [{\"kind\": \"webhook\", \"url\": \"https://a.example/x\", \"header\": \"X-A\\r\\nHost: evil\"}]}"); fail("bad header name"); }
        catch (Exception expected) { }
        assertTrue("an upper-case scheme is still a web address", Automation.webhookUrlOk("HTTPS://hooks.example.com/x"));
    }

    @Test public void fillInsNeverExpandTwiceAndStayValidJson() throws Exception {
        Automation.Event e = new Automation.Event(Automation.FINISHED); e.file = "{printer}\"\n\\x\u0001.gcode"; e.printer = "SECRET";
        Map<String, String> v = e.values(0, java.util.TimeZone.getTimeZone("UTC"));
        String text = Automation.fill("{file} {", v, false);
        assertFalse("a value naming another value is not expanded", text.contains("SECRET"));
        assertTrue(text.endsWith(" {"));
        Automation.Action hook = new Automation.Action(); hook.kind = Automation.WEBHOOK; hook.body = "{\"f\": \"{file}\", \"x\": \"{nope}\", \"y\": \"{}\"}";
        JSONObject sent = new JSONObject(Automation.body(hook, v));
        assertTrue(sent.getString("f").startsWith("{printer}"));
        assertEquals("{nope}", sent.getString("x"));
        assertEquals("", Automation.fill("", v, true));
    }

    // ---- the value watcher and values

    @Test public void theWatcherIgnoresMissingAndWordValues() {
        Automation.Rule r = new Automation.Rule(); r.event = Automation.VALUE; r.field = "chamber"; r.limit = 40;
        Automation.Watcher w = new Automation.Watcher();
        assertTrue(w.check(List.of(r), Map.of(), 0, null).isEmpty());
        assertTrue(w.check(List.of(r), Map.of("chamber", "—"), 1, null).isEmpty());
        assertTrue(w.check(List.of(r), Map.of("chamber", "30"), 2, null).isEmpty());
        assertTrue(w.check(List.of(r), Map.of("chamber", "Infinity"), 3, null).isEmpty());
        assertEquals(1, w.check(List.of(r), Map.of("chamber", "41"), 4, null).size());
        Automation.Rule negative = new Automation.Rule(); negative.event = Automation.VALUE; negative.field = "z"; negative.above = false; negative.limit = -1;
        Automation.Watcher n = new Automation.Watcher();
        n.check(List.of(negative), Map.of("z", "0"), 0, null);
        assertEquals(1, n.check(List.of(negative), Map.of("z", "-1.5"), 1, null).size());
    }

    @Test public void deepOrHugeReportsAreWalkedSafely() throws Exception {
        JSONObject deep = new JSONObject(); JSONObject at = deep;
        for (int i = 0; i < 3000; i++) { JSONObject next = new JSONObject(); at.put("n", next); at = next; }
        Map<String, String> raw = AutomationValues.raw(deep);
        assertTrue(raw.size() < 10);
        JSONArray long_ = new JSONArray(); for (int i = 0; i < 10_000; i++) long_.put(i);
        assertTrue(AutomationValues.raw(new JSONObject().put("a", long_)).size() <= 32);
        Map<String, String> v = AutomationValues.from(new JSONObject().put("fans", "x").put("extruder", 5).put("led", new JSONObject().put("status", "on")), null, null, 0, java.util.Locale.ROOT, java.util.TimeZone.getTimeZone("UTC"));
        assertFalse(v.containsKey("nozzle"));
    }

    // ---- recorder figures

    @Test public void oddExtruderPositionsDoNotCorruptFilamentFed() throws Exception {
        PrintRecorder recorder = new PrintRecorder(folder.newFolder());
        double[] es = {0, 100, Double.NaN, 1e12, 120, -1e12, 140};
        long t = 0;
        for (int i = 0; i < es.length; i++) {
            JSONObject s = status(2, 2075, "e.gcode", "u", i + 1, i);
            JSONObject move = new JSONObject(); if (!Double.isNaN(es[i])) move.put("e", es[i]); s.put("gcode_move_inf", move);
            recorder.update(t += 20_000, s, "local", "CC2");
        }
        double fed = recorder.current().extrudedMm;
        assertTrue("fed " + fed, fed >= 100 && fed <= 160);
        JSONObject text = status(2, 2075, "e.gcode", "u", 20, 20).put("gcode_move_inf", new JSONObject().put("e", "not a number"));
        recorder.update(t + 20_000, text, "local", "CC2");
        assertEquals(fed, recorder.current().extrudedMm, 1e-9);
    }

    @Test public void estimatesFromNothingTeachNothing() {
        PrintRecorder.Recording r = new PrintRecorder.Recording(new java.io.File("x.csv"), new java.io.File("x.json"));
        r.outcome = "complete"; r.estimate = 1; r.startProgress = 0; r.elapsed = 3_600_000;
        assertTrue("an hour against a 1 s estimate is not learned", Double.isNaN(PrintRecorder.ratio(r)));
        r.estimate = 3600; r.outcome = "stopped";
        assertTrue("a stopped print teaches nothing", Double.isNaN(PrintRecorder.ratio(r)));
        assertEquals(0, PrintRecorder.grams(0, null), 0);
    }

    // ---- chamber trend, fans

    @Test public void aClockThatStepsBackStartsTheTrendAgain() {
        ChamberTrend t = new ChamberTrend();
        for (int i = 0; i <= 4; i++) t.add(1_000_000 + i * 60_000L, 30 + i);
        t.add(10, 50);
        assertTrue(Double.isNaN(t.perMinute()));
        t.add(20, Double.NaN);
        assertEquals(-1, t.secondsTo(Double.NaN, 30));
    }

    @Test public void fanReadingsAsTextOrNonsenseDoNotAlarm() throws Exception {
        PrintWatch w = new PrintWatch();
        JSONObject s = status(2, 2075, "a.gcode", "u", 5, 5).put("extruder", new JSONObject().put("temperature", 220).put("target", 220));
        s.put("fans", new JSONObject().put("heater_fan", new JSONObject().put("rpm", "lots")));
        for (int i = 0; i < 5; i++) assertFalse(w.update(s, i * 60_000L).contains("heatsink"));
        s.put("fans", new JSONObject().put("heater_fan", "off"));
        assertFalse(w.update(s, 6 * 60_000L).contains("heatsink"));
        w.canvas = new JSONObject().put("active_canvas_id", 0).put("active_tray_id", 0).put("canvas_list", "nope");
        assertFalse(w.update(s, 7 * 60_000L).contains("range"));
    }

    // ---- guardian maths

    @Test public void theGuardianCopesWithAnEmptyToolpathAndAFlatPicture() throws Exception {
        GcodeToolpath empty = GcodeToolpath.read(new java.io.ByteArrayInputStream(new byte[0]));
        double[] camera = {128, -120, 260, 90, 50, 60, 0, 0};
        boolean[] expected = PrintGuardian.expectedMask(empty, 5, camera, 16 / 9.0, 2);
        boolean[] bed = PrintGuardian.bedMask(camera, 16 / 9.0), head = PrintGuardian.headMask(camera, 16 / 9.0, Double.NaN, 0, 0);
        int[] grey = new int[PrintGuardian.W * PrintGuardian.H]; java.util.Arrays.fill(grey, 0x808080);
        PrintGuardian.Result r = PrintGuardian.analyse(grey, 0x808080, expected, bed, head);
        assertEquals(0, r.coverage, 0);
        PrintGuardian.Watch watch = new PrintGuardian.Watch();
        for (int i = 0; i < 10; i++) assertFalse("nothing to compare: no alarm", watch.add(r, i));
        // A camera looking away from the bed sees nothing of it.
        double[] away = {128, -120, 260, 270, -30, 60, 0, 0};
        boolean[] none = PrintGuardian.bedMask(away, 16 / 9.0); int n = 0; for (boolean b : none) if (b) n++;
        assertTrue(n < 50);
    }

    // ---- the cloud listener

    @Test public void heardReferencesMatchOnlyTheirOwnFile() {
        CloudFileRoute.heard("GAUNTLET1", new CloudApi.Reference("gcode/u/my-part.gcode", ""));
        assertNull("another file whose name ends the same", CloudFileRoute.heardFor("GAUNTLET1", "part.gcode"));
        assertEquals("gcode/u/my-part.gcode", CloudFileRoute.heardFor("GAUNTLET1", "MY-PART.GCODE").objectName);
        assertNull(CloudFileRoute.heardFor("GAUNTLET1", "")); assertNull(CloudFileRoute.heardFor("GAUNTLET1", null)); assertNull(CloudFileRoute.heardFor("NOBODY", "my-part.gcode"));
        for (int i = 0; i < 20; i++) CloudFileRoute.heard("GAUNTLET1", new CloudApi.Reference("gcode/u/f" + i + ".gcode", ""));
        assertEquals("kept to the latest 10", 10, CloudFileRoute.heardCount("GAUNTLET1"));
    }

    @Test public void aReferenceThatClimbsOutOfItsFolderIsNotTaken() throws Exception {
        assertNull(CloudApi.gcodeReference(new JSONObject().put("file", "gcode/../../other/x.gcode")));
        assertNull(CloudApi.gcodeReference(new JSONObject().put("url", "https://evil.example.com.attacker.net/x.gcode")));
        assertNull(CloudApi.gcodeReference(new JSONObject().put("url", "http://oss.elegoo.com/x.gcode")));
    }

    @Test public void aSavedRuleWithAnOldHeaderNameIsRepairedNotLost() throws Exception {
        String saved = new JSONObject().put("rules", new JSONArray().put(new JSONObject().put("id", "r1").put("name", "Hook").put("event", "print_finished").put("enabled", true)
            .put("actions", new JSONArray().put(new JSONObject().put("kind", "webhook").put("url", "https://a.example/x").put("header", "X Api Key"))))).toString();
        List<Automation.Rule> rules = Automation.load(saved);
        assertEquals(1, rules.size()); assertEquals("r1", rules.get(0).id); assertEquals("", rules.get(0).actions.get(0).header);
    }
}
