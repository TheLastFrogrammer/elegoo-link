package io.github.thelastfrogrammer.elink;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.json.JSONObject;
import org.junit.Test;

public class GuardianMemoryTest {
    private static final int OFF = PrintRecorder.GUARDIAN_OFF_PRINT, COVER = PrintRecorder.GUARDIAN_COVERAGE, SPREAD = PrintRecorder.GUARDIAN_SPREAD, SUDDEN = PrintRecorder.GUARDIAN_SUDDEN;
    private static final double[] FIXED = {1, 1, 1, 1};

    /** One recorded check: {layer, measure, value, limit, over, alarm, allowance}. */
    private static double[] c(int layer, int measure, double value, double limit, boolean over, boolean alarm, double allowance) {
        return new double[] {layer, measure, value, limit, over ? 1 : 0, alarm ? 1 : 0, allowance};
    }
    /** Off-print checks against a 3 % limit (allowance 1), alarm flagged where the third over in a row lands. */
    private static List<double[]> offPrint(double... values) {
        List<double[]> out = new ArrayList<>(); int run = 0; boolean on = false;
        for (int i = 0; i < values.length; i++) {
            boolean over = values[i] > 0.03; run = over ? run + 1 : 0;
            boolean raise = run >= 3 && !on; if (run >= 3) on = true; else on = false;
            out.add(c(i + 1, OFF, values[i], 0.03, over, raise, 1));
        }
        return out;
    }
    private static PrintRecorder.Recording recording(String key, List<double[]> checks, int[][] answers) throws Exception {
        PrintRecorder.Recording r = new PrintRecorder.Recording(new File("x.csv"), new File("x.json"));
        r.guardianCamera = key; r.guardianChecks.addAll(checks);
        for (int[] a : answers) r.journal.guardian.add(new JSONObject().put("at", a[0]).put("fine", a[1] == 1).put("check", a[0]));
        return r;
    }

    @Test public void lineUpFingerprintFollowsTheSavedLineUp() {
        List<double[]> points = new ArrayList<>(Collections.singletonList(new double[] {0, 128, -120, 260, 90, 50, 60, 0, 0}));
        String a = GuardianMemory.lineUp(true, GcodeViewerActivity.LINED_UP, points);
        assertEquals(a, GuardianMemory.lineUp(true, GcodeViewerActivity.LINED_UP, Collections.singletonList(new double[] {0, 128, -120, 260, 90, 50, 60, 0, 0})));
        assertEquals("tiny float noise is the same line-up", a, GuardianMemory.lineUp(true, GcodeViewerActivity.LINED_UP, Collections.singletonList(new double[] {0, 128.0000001, -120, 260, 90, 50, 60, 0, 0})));
        assertNotEquals("a moved camera", a, GuardianMemory.lineUp(true, GcodeViewerActivity.LINED_UP, Collections.singletonList(new double[] {0, 140, -120, 260, 90, 50, 60, 0, 0})));
        points.add(new double[] {100, 128, -120, 360, 90, 50, 60, 0, 0});
        assertNotEquals("lined up again at another height", a, GuardianMemory.lineUp(true, GcodeViewerActivity.LINED_UP, points));
        assertNotEquals("a preset spot", a, GuardianMemory.lineUp(false, 0, null));
        assertNotEquals(GuardianMemory.lineUp(false, 0, null), GuardianMemory.lineUp(false, 4, null));
        // Per camera: the same line-up on another camera is another memory, and the key gives nothing away.
        String printer = GuardianMemory.key(PrintRecorder.PRINTER_CAMERA, a), added = GuardianMemory.key("added:1234", a);
        assertNotEquals(printer, added);
        assertEquals(16, printer.length()); assertFalse(added.contains("1234"));
    }

    @Test public void needIsAMultipleOfTheFixedLimit() {
        // Off the print: 6 % against a limit of 4.5 % that already allowed 1.5×: the fixed limit was 3 %, so 2× was needed.
        assertEquals(2, GuardianMemory.need(c(1, OFF, 0.06, 0.045, true, false, 1.5)), 1e-9);
        assertEquals(0.03, GuardianMemory.base(c(1, OFF, 0.06, 0.045, true, false, 1.5)), 1e-12);
        // The print's area: limit 20 % allowing 2× was 40 % fixed; 10 % showing needs 4×.
        assertEquals(4, GuardianMemory.need(c(1, COVER, 0.10, 0.20, true, false, 2)), 1e-9);
        assertTrue(GuardianMemory.need(c(1, COVER, 0, 0.20, true, false, 1)) > GuardianMemory.CAP);
        assertTrue("older checks without the allowance", Double.isNaN(GuardianMemory.need(new double[] {1, OFF, 0.06, 0.03, 1, 1})));
        assertTrue("no limit yet", Double.isNaN(GuardianMemory.need(c(1, COVER, 0.5, Double.NaN, false, false, 1))));
    }

    @Test public void anAlarmNeedsTheWeakestOfItsThreeChecks() {
        List<double[]> checks = offPrint(0.01, 0.09, 0.045, 0.06, 0.2);
        int alarm = 3; assertEquals(1, checks.get(alarm)[5], 0);
        assertEquals(0.045 / 0.03, GuardianMemory.alarmNeed(checks, alarm), 1e-9);
        // A sudden change: the change itself (the check over before the alarm), not the calm picture the alarm lands on.
        List<double[]> sudden = Arrays.asList(c(1, SUDDEN, 0.30, 0.12, true, false, 1), c(2, SUDDEN, 0.01, 0.12, false, false, 1), c(3, SUDDEN, 0.01, 0.12, false, true, 1));
        assertEquals(2.5, GuardianMemory.alarmNeed(sudden, 2), 1e-9);
        List<GuardianMemory.Answer> answers = GuardianMemory.answered(checks, 0, checks.size(), true, 5);
        assertEquals(1, answers.size()); assertEquals(OFF, answers.get(0).measure); assertTrue(answers.get(0).fine);
        assertTrue("only alarms in the window", GuardianMemory.answered(checks, 4, checks.size(), true, 5).isEmpty());
    }

    @Test public void fineRaisesTheLimitJustEnoughUpToTheCap() {
        List<GuardianMemory.Answer> a = new ArrayList<>();
        assertEquals("nothing learned: the fixed rule", 1, GuardianMemory.allowance(a, OFF), 0);
        a.add(new GuardianMemory.Answer(OFF, 1.6, true, 1));
        assertEquals(1.6 * GuardianMemory.ABOVE, GuardianMemory.allowance(a, OFF), 1e-9);
        a.add(new GuardianMemory.Answer(OFF, 1.2, true, 2));
        assertEquals("the smallest that keeps every fine alarm quiet", 1.6 * GuardianMemory.ABOVE, GuardianMemory.allowance(a, OFF), 1e-9);
        assertEquals("other measures are untouched", 1, GuardianMemory.allowance(a, COVER), 0);
        a.add(new GuardianMemory.Answer(OFF, 9, true, 3));
        assertEquals("at most 3×", GuardianMemory.CAP, GuardianMemory.allowance(a, OFF), 0);
        assertEquals(1, GuardianMemory.heldBack(a, OFF));
    }

    @Test public void realProblemsHoldItBelowWhatWouldHideThemButNeverBelowTheFixedRule() {
        List<GuardianMemory.Answer> a = new ArrayList<>(Arrays.asList(new GuardianMemory.Answer(SPREAD, 2.5, true, 1), new GuardianMemory.Answer(SPREAD, 2.0, false, 2)));
        assertEquals(2.0 * GuardianMemory.REAL_MARGIN, GuardianMemory.allowance(a, SPREAD), 1e-9);
        assertTrue(GuardianMemory.allowance(a, SPREAD) < 2.0);
        assertEquals(2, GuardianMemory.heldBack(a, SPREAD));
        a.add(new GuardianMemory.Answer(SPREAD, 1.05, false, 3));
        assertEquals("never below the fixed rule", 1, GuardianMemory.allowance(a, SPREAD), 0);
        assertEquals("only real answers: the fixed rule", 1, GuardianMemory.allowance(Collections.singletonList(new GuardianMemory.Answer(OFF, 4, false, 1)), OFF), 0);
    }

    @Test public void eachCameraAndLineUpLearnsAloneAndForgets() {
        GuardianMemory memory = new GuardianMemory();
        String printer = GuardianMemory.key("printer", "aaaa"), moved = GuardianMemory.key("printer", "bbbb"), added = GuardianMemory.key("added:x", "aaaa");
        memory.learn(printer, Collections.singletonList(new GuardianMemory.Answer(OFF, 2, true, 1)));
        memory.learn(added, Collections.singletonList(new GuardianMemory.Answer(SPREAD, 1.5, true, 2)));
        assertEquals(2 * GuardianMemory.ABOVE, memory.allowances(printer)[OFF], 1e-9);
        assertEquals(1, memory.allowances(printer)[SPREAD], 0);
        assertEquals("a moved camera starts afresh", 1, memory.allowances(moved)[OFF], 0);
        assertEquals(1, memory.allowances(added)[OFF], 0);
        // Kept as text and read back the same; unreadable text is nothing learned.
        GuardianMemory back = GuardianMemory.fromJson(memory.toJson());
        assertEquals(memory.allowances(printer)[OFF], back.allowances(printer)[OFF], 1e-12);
        assertEquals(memory.allowances(added)[SPREAD], back.allowances(added)[SPREAD], 1e-12);
        assertTrue(GuardianMemory.fromJson("not json").empty());
        assertTrue(GuardianMemory.fromJson("").empty());
        // Only the newest answers are kept.
        for (int i = 0; i < GuardianMemory.KEEP + 10; i++) memory.learn(printer, Collections.singletonList(new GuardianMemory.Answer(COVER, 1.1, true, 10 + i)));
        assertEquals(GuardianMemory.KEEP, memory.answers(printer).size());
        memory.forget(added); assertEquals(1, memory.allowances(added)[SPREAD], 0); assertFalse(memory.empty());
        memory.forget(); assertTrue(memory.empty()); assertEquals(1, memory.allowances(printer)[COVER], 0);
    }

    @Test public void replayCountsAlarmsAndRealProblems() throws Exception {
        String key = GuardianMemory.key("printer", "aaaa");
        // A false alarm at about 1.7× (called fine), then a real one at 6×.
        List<double[]> checks = offPrint(0.01, 0.05, 0.05, 0.052, 0.01, 0.2, 0.2, 0.2);
        PrintRecorder.Recording r = recording(key, checks, new int[][] {{4, 1}, {8, 0}});
        GuardianMemory memory = new GuardianMemory();
        memory.learn(key, GuardianMemory.answered(checks, 0, 4, true, 4));
        memory.learn(key, GuardianMemory.answered(checks, 4, 8, false, 8));
        double[] m = memory.allowances(key);
        assertEquals(0.05 / 0.03 * GuardianMemory.ABOVE, m[OFF], 1e-9);
        GuardianMemory.Replay replay = GuardianMemory.replay(Arrays.asList(r, recording("other", checks, new int[0][])), key, m, 20);
        assertEquals("another camera's print is left out", 1, replay.prints);
        assertEquals(2, replay.fixedAlarms); assertEquals(1, replay.learnedAlarms);
        assertEquals(1, replay.real); assertEquals(0, replay.missed);
        // Too much allowed would hide the real one, and the replay says so.
        GuardianMemory.Replay greedy = GuardianMemory.replay(Collections.singletonList(r), key, new double[] {7, 1, 1, 1}, 20);
        assertEquals(0, greedy.learnedAlarms); assertEquals(1, greedy.missed);
        // Only the newest `last` prints.
        assertEquals(1, GuardianMemory.replay(Arrays.asList(r, r, r), key, m, 1).prints);
    }

    @Test public void learnedLimitsNeverMissAnAlarmCalledReal() throws Exception {
        Random random = new Random(7);
        for (int trial = 0; trial < 300; trial++) {
            String key = "k";
            GuardianMemory memory = new GuardianMemory(); List<PrintRecorder.Recording> prints = new ArrayList<>();
            for (int p = 0; p < 4; p++) {
                List<double[]> checks = new ArrayList<>(); int[] run = new int[4]; boolean[] on = new boolean[4];
                List<int[]> answers = new ArrayList<>(); int from = 0;
                for (int i = 0; i < 60; i++) {
                    int k = random.nextInt(4);
                    double limit = k == COVER ? 0.3 : k == SPREAD ? 0.015 : k == SUDDEN ? 0.12 : 0.03;
                    double value = k == COVER ? random.nextDouble() * 0.6 : limit * random.nextDouble() * 5;
                    boolean over = k == COVER ? value < limit : value > limit;
                    boolean raise;
                    if (k == SUDDEN) { raise = over && random.nextBoolean(); }   // a jump that stayed, or not
                    else { run[k] = over ? run[k] + 1 : 0; raise = run[k] >= 3 && !on[k]; on[k] = run[k] >= 3; }
                    checks.add(c(i, k, value, limit, over, raise, 1));
                    if (raise && random.nextInt(3) > 0) {
                        boolean fine = random.nextBoolean();
                        memory.learn(key, GuardianMemory.answered(checks, from, checks.size(), fine, i));
                        answers.add(new int[] {checks.size(), fine ? 1 : 0}); from = checks.size();
                    }
                }
                PrintRecorder.Recording r = recording(key, checks, new int[0][]);
                for (int[] a : answers) r.journal.guardian.add(new JSONObject().put("fine", a[1] == 1).put("check", a[0]));
                prints.add(r);
            }
            double[] m = memory.allowances(key);
            for (double v : m) assertTrue(v >= 1 && v <= GuardianMemory.CAP);
            GuardianMemory.Replay replay = GuardianMemory.replay(prints, key, m, 20);
            assertEquals("trial " + trial, 0, replay.missed);
        }
    }

    @Test public void watchesStartFromWhatWasLearned() {
        PrintGuardian.Result normal = new PrintGuardian.Result(), messy = new PrintGuardian.Result();
        normal.expectedPixels = messy.expectedPixels = 500; normal.bedPixels = messy.bedPixels = 5000; normal.coverage = messy.coverage = 0.8;
        normal.outside = 0.005; messy.outside = 0.05;   // 5 %: over the fixed 3 % limit, under 2 × 3 %
        PrintGuardian.Watch fixed = new PrintGuardian.Watch(), learned = new PrintGuardian.Watch(2, 1);
        boolean fixedAlarm = false, learnedAlarm = false;
        for (int i = 0; i < 2; i++) { fixed.add(normal, 6 + i); learned.add(normal, 6 + i); }
        assertFalse("learning is not judged", fixed.judged);
        fixed.add(normal, 8); learned.add(normal, 8);   // the third sets what is normal and is judged
        assertTrue(fixed.judged);
        PrintGuardian.Result hidden = new PrintGuardian.Result(); hidden.expectedPixels = 5; hidden.bedPixels = 5000;
        PrintGuardian.Watch other = new PrintGuardian.Watch(); for (int i = 0; i < 3; i++) other.add(normal, 6 + i); other.add(hidden, 9);
        assertFalse("too little in view is not judged, so its stale limits are not kept", other.judged);
        for (int i = 0; i < 4; i++) { fixedAlarm |= fixed.add(messy, 10 + i); learnedAlarm |= learned.add(messy, 10 + i); }
        assertTrue(fixed.judged);
        assertTrue(fixedAlarm); assertFalse(learnedAlarm);
        assertEquals(0.06, learned.outsideLimit, 1e-9); assertEquals(2, learned.outsideAllowance, 0);
        // "Looks fine" still works on top, for this print.
        learned.looksFine(); assertEquals(3, learned.outsideAllowance, 1e-9); assertEquals(1.5, learned.coverageAllowance, 1e-9);
        assertEquals("never below the fixed rule", 1, new PrintGuardian.Watch(0.5, 0.2).outsideAllowance, 0);
        PictureChange.Watch change = new PictureChange.Watch(1.8, 1.2);
        assertEquals(1.8, change.spreadAllowance, 0); assertEquals(1.2, change.jumpAllowance, 0);
        change.looksFine(); assertEquals(2.7, change.spreadAllowance, 1e-9);
    }

    @Test public void recordingsKeepTheAllowanceTheCameraAndWhichChecksAnAnswerFollows() throws Exception {
        File dir = java.nio.file.Files.createTempDirectory("guardian-memory").toFile();
        PrintRecorder recorder = new PrintRecorder(dir);
        JSONObject status = new JSONObject().put("machine_status", new JSONObject().put("status", 2).put("sub_status", 2075).put("progress", 5))
            .put("print_status", new JSONObject().put("filename", "Cube.gcode").put("uuid", "u1").put("current_layer", 3).put("total_layer", 50));
        recorder.update(1000, status, "local", "CC2");
        PrintRecorder.Recording now = recorder.current();
        recorder.noteGuardianCheck(now.meta, 7, OFF, 0.05, 0.045, true, true, 1.5, "key-a");
        recorder.noteGuardian(true, "filament off the print", 2000);
        recorder.noteGuardianCheck(now.meta, 8, OFF, 0.01, 0.045, false, false, 1.5, "key-a");
        PrintRecorder.Recording read = PrintRecorder.Recording.read(now.meta);
        assertEquals("key-a", read.guardianCamera);
        assertEquals(7, read.guardianChecks.get(0).length); assertEquals(1.5, read.guardianChecks.get(0)[6], 0);
        assertEquals(1, read.journal.guardian.get(0).getInt("check"));
        assertEquals(Collections.singletonList(1), Collections.singletonList(GuardianMemory.answerChecks(read).get(0)[0]));
        recorder.noteGuardianCheck(now.meta, 9, OFF, 0.01, 0.045, false, false, 1.5, "key-b");
        assertEquals("a camera changed mid-print is left out of the replay", "mixed", PrintRecorder.Recording.read(now.meta).guardianCamera);
    }

    @Test public void saysWhatItLearnedInPlainWords() {
        List<GuardianMemory.Answer> answers = new ArrayList<>();
        for (int i = 0; i < 7; i++) answers.add(new GuardianMemory.Answer(OFF, 1.85 / GuardianMemory.ABOVE, true, i));
        double[] m = GuardianMemory.allowances(answers);
        GuardianMemory.Replay r = new GuardianMemory.Replay(); r.prints = 12; r.fixedAlarms = 9; r.learnedAlarms = 2; r.real = 3;
        String text = GuardianMemory.describe("the printer's camera", answers, m, r);
        assertEquals("Learned from 7 answers on the printer's camera: allows 1.8× more filament off the print than at first. "
            + "On your last 12 recorded prints this would have raised 2 alarms instead of 9, and missed none of the 3 you called real.", text);
        GuardianMemory.Replay none = new GuardianMemory.Replay();
        assertTrue(GuardianMemory.describe("the printer's camera", answers, m, none).endsWith("No recorded prints with this camera and line-up to check it on yet."));
        List<GuardianMemory.Answer> real = Collections.singletonList(new GuardianMemory.Answer(OFF, 2, false, 1));
        assertTrue(GuardianMemory.describe("“Garage”", real, GuardianMemory.allowances(real), null).startsWith("Learned from 1 answer on “Garage”: you called every alarm real, so it keeps its first limits."));
        GuardianMemory.Replay missed = new GuardianMemory.Replay(); missed.prints = 2; missed.fixedAlarms = 3; missed.learnedAlarms = 1; missed.real = 2; missed.missed = 1;
        assertTrue(GuardianMemory.describe("the printer's camera", answers, m, missed).endsWith("but would have missed 1 of the 2 you called real."));
    }
}
