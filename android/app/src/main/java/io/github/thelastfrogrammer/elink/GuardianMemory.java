package io.github.thelastfrogrammer.elink;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * What the print guardian learns from the person's answers, across prints, per camera and line-up (a moved camera sees
 * everything differently, so a new line-up starts afresh). Each answered alarm keeps how much more each measure would have
 * had to allow to stay quiet (its "need", a multiple of the fixed rule's limit). A measure's allowance is then the smallest
 * that keeps every alarm called fine quiet, at most CAP, and always below what would have hidden an alarm called real; never
 * below the fixed rule. The replay checks it on recorded prints. Pure; independent of Android.
 */
final class GuardianMemory {
    static final int MEASURES = 4;
    /** Never more than this many times the fixed rule's limit, whatever was called fine. */
    static final double CAP = 3;
    /** The allowance stays this far below the weakest alarm called real, so a slightly milder repeat still raises one. */
    static final double REAL_MARGIN = 0.9;
    /** Just above the need of an alarm called fine, so the same picture is clearly quiet. */
    static final double ABOVE = 1.01;
    /** Answers kept per camera and line-up (the newest). */
    static final int KEEP = 60;
    /** A recorded check's columns past PrintRecorder's six: the allowance (multiple of the fixed rule) in effect. */
    static final int ALLOWANCE = 6;

    /** One answered alarm: which measure, how much more it would have had to allow to stay quiet, the answer, when (ms). */
    static final class Answer {
        final int measure; final double need; final boolean fine; final long at;
        Answer(int measure, double need, boolean fine, long at) { this.measure = measure; this.need = need; this.fine = fine; this.at = at; }
    }

    private final Map<String, List<Answer>> byKey = new LinkedHashMap<>();

    // ---- Which camera and line-up

    /**
     * A short fingerprint of a camera's line-up: the saved hand line-ups ({bed Z, x, y, height, turn, tilt, view, lens,
     * roll}, rounded to hundredths so the same saved values always give the same one) or, not lined up, the preset spot.
     */
    static String lineUp(boolean linedUp, int spot, List<double[]> points) {
        StringBuilder s = new StringBuilder();
        if (linedUp && points != null && !points.isEmpty()) {
            s.append("lined-up");
            for (double[] p : points) { s.append(';'); for (double v : p) s.append(String.format(Locale.ROOT, "%.2f,", v)); }
        } else s.append("spot ").append(spot);
        return hash(s.toString()).substring(0, 8);
    }
    /** The memory's key for a camera ("printer" or "added:" and its id) with a line-up fingerprint: opaque, so it says nothing of the camera. */
    static String key(String cameraId, String lineUp) { return hash(cameraId + "|" + lineUp).substring(0, 16); }
    private static String hash(String text) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(); for (byte b : d) hex.append(String.format(Locale.ROOT, "%02x", b & 255)); return hex.toString();
        } catch (java.security.NoSuchAlgorithmException none) { throw new IllegalStateException(none); }
    }

    // ---- From recorded checks to answers

    /** Filament off the print, change away from it and a sudden change alarm when high; the print's area when low. */
    static boolean lowIsBad(int measure) { return measure == PrintRecorder.GUARDIAN_COVERAGE; }
    /** A check's limit under the fixed rule (its limit without the allowance in effect), or NaN when unknown or not judged. */
    static double base(double[] check) {
        if (check.length <= ALLOWANCE || Double.isNaN(check[ALLOWANCE]) || check[ALLOWANCE] <= 0 || Double.isNaN(check[3])) return Double.NaN;
        return lowIsBad((int) check[1]) ? check[3] * check[ALLOWANCE] : check[3] / check[ALLOWANCE];
    }
    /** How many times the fixed rule's limit this check needed to stay within it (above 1: over the fixed limit). */
    static double need(double[] check) {
        double base = base(check), v = check[2];
        if (Double.isNaN(base) || Double.isNaN(v) || base <= 0) return Double.NaN;
        if (!lowIsBad((int) check[1])) return v / base;
        return v <= 0 ? CAP * 100 : base / v;   // none of the print showing: no allowance would keep it quiet
    }
    /**
     * The alarm at `alarm` (an index into `checks`) and how much more it would have had to allow to stay quiet: an alarm needs
     * three checks in a row over the limit, so the weakest of those three; for a sudden change, the change itself (the last
     * check over before it). NaN when the checks do not say.
     */
    static double alarmNeed(List<double[]> checks, int alarm) {
        int measure = (int) checks.get(alarm)[1];
        if (measure == PrintRecorder.GUARDIAN_SUDDEN) {
            for (int i = alarm; i >= 0; i--) { double[] c = checks.get(i); if ((int) c[1] == measure && c[4] > 0.5) return need(c); }
            return Double.NaN;
        }
        double weakest = Double.NaN; int seen = 0;
        for (int i = alarm; i >= 0 && seen < 3; i--) {
            double[] c = checks.get(i); if ((int) c[1] != measure) continue;
            if (c[4] < 0.5) break;
            double n = need(c); if (Double.isNaN(n)) return Double.NaN;
            weakest = Double.isNaN(weakest) ? n : Math.min(weakest, n); seen++;
        }
        return weakest;
    }
    /** The alarms among checks[from, to) with the person's one answer to them all (a real problem stands for each, to be safe). */
    static List<Answer> answered(List<double[]> checks, int from, int to, boolean fine, long at) {
        List<Answer> out = new ArrayList<>();
        for (int i = Math.max(0, from); i < Math.min(to, checks.size()); i++) {
            double[] c = checks.get(i); if (c.length < 6 || c[5] < 0.5) continue;
            int measure = (int) c[1]; if (measure < 0 || measure >= MEASURES) continue;
            double n = alarmNeed(checks, i); if (!Double.isNaN(n)) out.add(new Answer(measure, n, fine, at));
        }
        return out;
    }

    // ---- Learning

    void learn(String key, List<Answer> answers) {
        if (answers.isEmpty()) return;
        List<Answer> list = byKey.computeIfAbsent(key, k -> new ArrayList<>()); list.addAll(answers);
        while (list.size() > KEEP) list.remove(0);
    }
    List<Answer> answers(String key) { List<Answer> list = byKey.get(key); return list == null ? new ArrayList<>() : new ArrayList<>(list); }
    /** Every key with answers, for what is kept from earlier line-ups. */
    java.util.Set<String> keys() { return new java.util.LinkedHashSet<>(byKey.keySet()); }
    void forget() { byKey.clear(); }
    void forget(String key) { byKey.remove(key); }
    boolean empty() { return byKey.isEmpty(); }

    /** The allowance per measure for this camera and line-up (1 = the fixed rule). */
    double[] allowances(String key) { return allowances(answers(key)); }
    static double[] allowances(List<Answer> answers) { double[] m = new double[MEASURES]; for (int k = 0; k < MEASURES; k++) m[k] = allowance(answers, k); return m; }
    /**
     * The rule: just enough to keep every alarm called fine quiet, at most CAP, and below the weakest alarm called real by
     * REAL_MARGIN; never below 1, the fixed rule (which raised those real alarms).
     */
    static double allowance(List<Answer> answers, int measure) {
        double quiet = 1, real = Double.POSITIVE_INFINITY;
        for (Answer a : answers) {
            if (a.measure != measure || Double.isNaN(a.need)) continue;
            if (a.fine) quiet = Math.max(quiet, a.need * ABOVE); else real = Math.min(real, a.need * REAL_MARGIN);
        }
        return Math.max(1, Math.min(Math.min(quiet, CAP), real));
    }
    /** What held the allowance back: 0 nothing, 1 the cap, 2 an alarm called real. */
    static int heldBack(List<Answer> answers, int measure) {
        double quiet = 1, real = Double.POSITIVE_INFINITY;
        for (Answer a : answers) if (a.measure == measure && !Double.isNaN(a.need)) { if (a.fine) quiet = Math.max(quiet, a.need * ABOVE); else real = Math.min(real, a.need * REAL_MARGIN); }
        if (real < Math.min(quiet, CAP)) return 2;
        return quiet > CAP ? 1 : 0;
    }

    // ---- Kept in settings (SharedPreferences: one JSON text)

    String toJson() {
        JSONObject all = new JSONObject();
        try {
            for (Map.Entry<String, List<Answer>> e : byKey.entrySet()) {
                JSONArray list = new JSONArray();
                for (Answer a : e.getValue()) list.put(new JSONObject().put("m", a.measure).put("n", Math.min(a.need, CAP * 100)).put("f", a.fine).put("at", a.at));
                all.put(e.getKey(), list);
            }
        } catch (JSONException impossible) { }
        return all.toString();
    }
    static GuardianMemory fromJson(String text) {
        GuardianMemory memory = new GuardianMemory();
        if (text == null || text.isEmpty()) return memory;
        try {
            JSONObject all = new JSONObject(text);
            for (java.util.Iterator<String> it = all.keys(); it.hasNext(); ) {
                String key = it.next(); JSONArray list = all.optJSONArray(key); if (list == null) continue;
                List<Answer> answers = new ArrayList<>();
                for (int i = 0; i < list.length() && answers.size() < KEEP; i++) {
                    JSONObject a = list.optJSONObject(i); if (a == null) continue;
                    int measure = a.optInt("m", -1); double need = a.optDouble("n", Double.NaN);
                    if (measure < 0 || measure >= MEASURES || Double.isNaN(need) || need <= 0) continue;
                    answers.add(new Answer(measure, need, a.optBoolean("f"), a.optLong("at")));
                }
                if (!answers.isEmpty()) memory.byKey.put(key, answers);
            }
        } catch (JSONException unreadable) { memory.byKey.clear(); }
        return memory;
    }

    // ---- Checked on recorded prints

    static final class Replay {
        /** Recorded prints checked; alarms under the fixed rule and with the allowances; alarms called real, and how many of those would have stayed quiet. */
        int prints, fixedAlarms, learnedAlarms, real, missed;
    }
    /**
     * The checks of recorded prints made with this camera and line-up (the newest `last`), run again under the fixed rule and
     * under `allowances`: how many alarms each would have raised, and whether any alarm the person called real would have stayed
     * quiet. Answers are tied to the alarms before them by the check they were given at. The person's "Looks fine" during a print
     * is left out of both, so the two are compared alike. A sudden change cannot be re-run from the record (whether it stayed
     * is not kept): its recorded alarms count where they exceed the allowance.
     */
    static Replay replay(List<PrintRecorder.Recording> newestFirst, String key, double[] allowances, int last) {
        Replay r = new Replay();
        for (PrintRecorder.Recording rec : newestFirst) {
            if (r.prints >= last) break;
            if (!key.equals(rec.guardianCamera) || rec.guardianChecks.isEmpty()) continue;
            r.prints++;
            double[] fixed = {1, 1, 1, 1};
            r.fixedAlarms += simulate(rec.guardianChecks, fixed).alarms;
            Run run = simulate(rec.guardianChecks, allowances); boolean[] learned = run.on;
            r.learnedAlarms += run.alarms;
            int previous = 0;
            for (int[] answer : answerChecks(rec)) {
                int to = Math.min(answer[0], rec.guardianChecks.size());
                if (answer[1] == 0) {
                    boolean any = false, caught = false;
                    for (int i = previous; i < to; i++) if (rec.guardianChecks.get(i)[5] > 0.5) { any = true; caught |= learned[i]; }
                    if (any) { r.real++; if (!caught) r.missed++; }
                }
                previous = Math.max(previous, to);
            }
        }
        return r;
    }
    /** One print's checks run again: per check, whether an alarm is on for its measure after it, and the alarms raised. */
    static final class Run { boolean[] on; int alarms; }
    static Run simulate(List<double[]> checks, double[] m) {
        boolean[] on = new boolean[checks.size()]; int[] run = new int[MEASURES]; boolean[] alarmed = new boolean[MEASURES]; int alarms = 0;
        for (int i = 0; i < checks.size(); i++) {
            double[] c = checks.get(i); int k = (int) c[1]; if (k < 0 || k >= MEASURES) continue;
            if (k == PrintRecorder.GUARDIAN_SUDDEN) {
                if (c[5] > 0.5) { double n = alarmNeed(checks, i); if (!Double.isNaN(n) && n > m[k]) { alarms++; on[i] = true; } }
                continue;
            }
            double base = base(c);
            boolean bad = !Double.isNaN(base) && !Double.isNaN(c[2]) && (lowIsBad(k) ? c[2] < base / m[k] : c[2] > base * m[k]);
            run[k] = bad ? run[k] + 1 : 0;
            if (run[k] >= 3) { if (!alarmed[k]) { alarmed[k] = true; alarms++; } } else alarmed[k] = false;
            on[i] = alarmed[k];
        }
        Run out = new Run(); out.on = on; out.alarms = alarms; return out;
    }
    /** {check index when answered, 1 fine / 0 real} of a recording's answers, in order; answers from before checks were tied to them are left out. */
    static List<int[]> answerChecks(PrintRecorder.Recording rec) {
        List<int[]> out = new ArrayList<>();
        for (JSONObject a : rec.journal.guardian) if (a.has("check")) out.add(new int[] {a.optInt("check"), a.optBoolean("fine") ? 1 : 0});
        out.sort((x, y) -> Integer.compare(x[0], y[0]));
        return out;
    }

    // ---- In words, for the guardian's settings

    /** What each measure's allowance lets through, after "allows 1.8× " (coverage reads the other way round). */
    private static final String[] MORE = {"more filament off the print", "less of the print showing", "more change away from the print", "a bigger sudden change"};
    /**
     * "Learned from 7 answers on the printer's camera: allows 1.8× more filament off the print than at first. On your last 12
     * recorded prints this would have raised 2 alarms instead of 9, and missed none of the 3 you called real." `camera`: how
     * the camera is called ("the printer's camera"); `replay` null when the recordings were not read.
     */
    static String describe(String camera, List<Answer> answers, double[] m, Replay replay) {
        StringBuilder s = new StringBuilder();
        int fine = 0, real = 0; java.util.Set<Long> times = new java.util.HashSet<>();
        for (Answer a : answers) times.add(a.at);
        for (Answer a : answers) if (a.fine) fine++; else real++;
        int n = times.size();
        s.append("Learned from ").append(n).append(n == 1 ? " answer" : " answers").append(" on ").append(camera).append(": ");
        List<String> allows = new ArrayList<>(); boolean capped = false, heldByReal = false;
        for (int k = 0; k < MEASURES; k++) {
            if (m[k] > 1.005) allows.add((m[k] < 1.1 ? "a little" : times(m[k])) + " " + MORE[k]);
            int held = heldBack(answers, k); capped |= held == 1; heldByReal |= held == 2;
        }
        if (allows.isEmpty()) s.append(fine == 0 ? "you called every alarm real, so it keeps its first limits." : "it keeps its first limits, since allowing more would have hidden an alarm you called real.");
        else {
            s.append("allows ").append(join(allows)).append(" than at first");
            if (heldByReal) s.append(", held back so an alarm you called real would still be raised");
            else if (capped) s.append(" (at most 3×)");
            s.append('.');
        }
        if (replay == null) return s.toString();
        if (replay.prints == 0) { s.append(" No recorded prints with this camera and line-up to check it on yet."); return s.toString(); }
        s.append(replay.prints == 1 ? " On your last recorded print" : " On your last " + replay.prints + " recorded prints").append(" this would have raised ");
        s.append(replay.learnedAlarms == replay.fixedAlarms ? alarms(replay.fixedAlarms) + ", the same as at first" : alarms(replay.learnedAlarms) + " instead of " + replay.fixedAlarms);
        if (replay.real == 0) s.append(". You called none of them a real problem.");
        else if (replay.missed == 0) s.append(replay.real == 1 ? ", and would still have raised the one you called real." : ", and missed none of the " + replay.real + " you called real.");
        else s.append(", but would have missed ").append(replay.missed).append(" of the ").append(replay.real).append(" you called real.");
        return s.toString();
    }
    private static String alarms(int n) { return n == 1 ? "1 alarm" : n + " alarms"; }
    static String times(double m) { return String.format(Locale.getDefault(), "%.1f×", Math.floor(m * 10) / 10); }
    private static String join(List<String> parts) {
        if (parts.size() == 1) return parts.get(0);
        return String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.get(parts.size() - 1);
    }
}
