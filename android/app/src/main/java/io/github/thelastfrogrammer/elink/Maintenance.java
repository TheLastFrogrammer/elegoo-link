package io.github.thelastfrogrammer.elink;

import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Maintenance reminders counted from the prints the app recorded: print hours, filament used (kg), or days, since each task
 * was last marked done. The starting tasks and intervals are common starting points for a printer of this kind, not the
 * maker's schedule; every one can be changed, and new ones added. Only reminds: nothing is sent to the printer.
 */
final class Maintenance {
    static final String PREF = "maintenance", ENABLED = "maintenanceReminders";
    static final String HOURS = "hours", KG = "kg", DAYS = "days";

    static final class Task {
        String id, name, unit; double every; long doneAt;
        /** The doneAt this task was last reminded of, so each due period notifies once. */
        long remindedFor;
        Task(String id, String name, String unit, double every, long doneAt) { this.id = id; this.name = name; this.unit = unit; this.every = every; this.doneAt = doneAt; }
        JSONObject json() throws org.json.JSONException {
            return new JSONObject().put("id", id).put("name", name).put("unit", unit).put("every", every).put("doneAt", doneAt).put("remindedFor", remindedFor);
        }
        static Task of(JSONObject j) {
            Task t = new Task(j.optString("id"), j.optString("name"), j.optString("unit", HOURS), j.optDouble("every", 50), j.optLong("doneAt")); t.remindedFor = j.optLong("remindedFor"); return t;
        }
    }

    /** The starting list, each counted from `now` (so nothing is due the day reminders begin). */
    static List<Task> defaults(long now) {
        List<Task> out = new ArrayList<>();
        out.add(new Task("nozzle", "Clean the nozzle and the wiper", HOURS, 50, now));
        out.add(new Task("plate", "Wash the build plate (warm water and dish soap)", HOURS, 30, now));
        out.add(new Task("belts", "Check the belts' tension and wear", HOURS, 100, now));
        out.add(new Task("rods", "Clean and lubricate the motion rods or rails", HOURS, 200, now));
        out.add(new Task("fans", "Clean the fans and check they spin freely", HOURS, 300, now));
        out.add(new Task("extruder", "Check the extruder gears and the filament path for dust and wear", KG, 5, now));
        out.add(new Task("frame", "Check screws, the frame and the cables", DAYS, 90, now));
        return out;
    }

    static List<Task> load(SharedPreferences prefs, long now) {
        String raw = prefs.getString(PREF, "");
        if (raw.isEmpty()) { List<Task> fresh = defaults(now); save(prefs, fresh); return fresh; }
        List<Task> out = new ArrayList<>();
        try { JSONArray a = new JSONArray(raw); for (int i = 0; i < a.length(); i++) { JSONObject j = a.optJSONObject(i); if (j != null && !j.optString("id").isEmpty()) out.add(Task.of(j)); } }
        catch (org.json.JSONException damaged) { return defaults(now); }
        return out;
    }
    static void save(SharedPreferences prefs, List<Task> tasks) {
        JSONArray a = new JSONArray();
        try { for (Task t : tasks) a.put(t.json()); } catch (org.json.JSONException impossible) { return; }
        prefs.edit().putString(PREF, a.toString()).apply();
    }

    /**
     * How much of a task's interval has been used since it was last done: print hours (recordings started since), filament
     * (kg, as recorded), or days. Pure, for tests.
     */
    static double used(Task t, List<PrintRecorder.Recording> recordings, long now) {
        if (DAYS.equals(t.unit)) return Math.max(0, now - t.doneAt) / 86_400_000.0;
        double sum = 0;
        for (PrintRecorder.Recording r : recordings) {
            if (r.start < t.doneAt) continue;
            sum += HOURS.equals(t.unit) ? r.duration() / 3_600_000.0 : PrintRecorder.grams(r.extrudedMm, "PLA") / 1000;
        }
        return sum;
    }
    static boolean due(Task t, double used) { return t.every > 0 && used >= t.every; }
    static boolean soon(Task t, double used) { return t.every > 0 && !due(t, used) && used >= 0.9 * t.every; }

    /** "32 of 50 h since Oct 10" (days and kg alike). */
    static String progress(Task t, double used, Locale locale) {
        String unit = HOURS.equals(t.unit) ? " h" : KG.equals(t.unit) ? " kg" : " days";
        String amount = KG.equals(t.unit) ? String.format(locale, "%.1f of %s", used, trim(t.every)) : String.format(locale, "%.0f of %s", Math.floor(used), trim(t.every));
        return amount + unit + " since " + new java.text.SimpleDateFormat("MMM d", locale).format(new java.util.Date(t.doneAt));
    }
    static String unitWords(String unit) { return HOURS.equals(unit) ? "hours of printing" : KG.equals(unit) ? "kg of filament" : "days"; }
    private static String trim(double v) { return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v); }

    /** "2 due · 1 soon", "All up to date", for the History row. */
    static String summary(List<Task> tasks, List<PrintRecorder.Recording> recordings, long now) {
        int due = 0, soon = 0;
        for (Task t : tasks) { double u = used(t, recordings, now); if (due(t, u)) due++; else if (soon(t, u)) soon++; }
        if (due == 0 && soon == 0) return tasks.isEmpty() ? "No reminders set" : "All up to date";
        return (due > 0 ? due + " due" : "") + (due > 0 && soon > 0 ? " · " : "") + (soon > 0 ? soon + " soon" : "");
    }

    /** The tasks that have become due and were not yet reminded of in this period; marks them reminded. */
    static List<Task> newlyDue(List<Task> tasks, List<PrintRecorder.Recording> recordings, long now) {
        List<Task> out = new ArrayList<>();
        for (Task t : tasks) if (due(t, used(t, recordings, now)) && t.remindedFor != t.doneAt) { t.remindedFor = t.doneAt; out.add(t); }
        return out;
    }

    private Maintenance() { }
}
