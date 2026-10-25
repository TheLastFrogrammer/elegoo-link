package io.github.thelastfrogrammer.elink.tv;

import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The words and numbers the TV shows for a linkworkshop.status/1 (and a linkworkshop.event/1): what the printer is doing,
 * the print, its times in the TV's own clock, temperatures and fan, how fresh it is, faults. Only what the status says:
 * a missing field shows nothing, never a guess. Pure Java (org.json is part of Android).
 */
final class StatusText {
    /** A number tile: its label, the value as shown, and how a screen reader says it. */
    static final class Number {
        final String label, value, spoken;
        Number(String label, String value, String spoken) { this.label = label; this.value = value; this.spoken = spoken; }
    }

    /** One status, ready to show. Empty strings stand for "not reported". */
    static final class Shown {
        String printer = "", phase = "", headline = "", detail = "", file = "", progressText = "", layer = "", timeLeft = "", doneAt = "", faults = "", freshness = "";
        int progress = -1;
        long remainingS = -1;
        /** A print is under way (preparing, printing, paused...): the progress belongs on screen. */
        boolean active;
        /** The phone has a recent status from the printer. */
        boolean fresh;
        /** Something needs a look: paused, stopped, a fault, no status. */
        boolean attention;
        final List<Number> numbers = new ArrayList<>();
        /** The whole status in one sentence, for a screen reader. */
        String spoken = "";
    }

    static Shown of(JSONObject s, long receivedAtMs, long nowMs, DateFormat timeOfDay, TimeZone zone) {
        Shown out = new Shown();
        if (s == null) { out.headline = "Waiting for the phone"; out.spoken = out.headline; return out; }
        JSONObject printer = s.optJSONObject("printer"), connection = s.optJSONObject("connection"), state = s.optJSONObject("state");
        JSONObject print = s.optJSONObject("print"), temps = s.optJSONObject("temperatures"), fans = s.optJSONObject("fans");
        out.printer = text(printer, "name");
        out.phase = state == null ? "" : state.optString("phase", "");
        out.fresh = connection != null && connection.optBoolean("fresh", false) && !"offline".equals(out.phase);
        String route = text(connection, "route");

        if (!out.fresh) {
            boolean connected = !route.isEmpty() && !route.equals("not connected");
            out.headline = connected ? "Waiting for the printer" : "The phone is not connected to the printer";
            out.detail = connected ? "Link Workshop has no recent status from the printer. This screen updates as soon as it has."
                : "Link Workshop on the phone is not following the printer now. This screen updates as soon as it is.";
            out.attention = true;
        } else {
            out.headline = text(state, "words");
            if (out.headline.isEmpty()) out.headline = phaseWords(out.phase);
        }
        out.active = out.fresh && (state != null && state.optBoolean("printing", false) || isOneOf(out.phase, "preparing", "printing", "pausing", "paused", "resuming", "stopping"));
        out.attention |= isOneOf(out.phase, "pausing", "paused", "stopping", "stopped");

        if (print != null && out.fresh) {
            out.file = text(print, "file");
            if (print.has("progress")) { out.progress = Math.max(0, Math.min(100, print.optInt("progress"))); out.progressText = out.progress + " %"; }
            int layer = print.optInt("layer", -1), total = print.optInt("total_layers", -1);
            if (layer >= 0 && total > 0) out.layer = "Layer " + layer + " of " + total; else if (layer > 0) out.layer = "Layer " + layer;
            long remaining = print.optLong("remaining_s", -1);
            if (remaining >= 0 && out.active) { out.remainingS = remaining; out.timeLeft = remaining < 60 ? "under a minute left" : duration(remaining) + " left"; }
            // While paused or stopping the phone's "done at" stands still, so only a running print gets one.
            if (isOneOf(out.phase, "printing", "resuming", "preparing")) {
                long done = isoMs(print.optString("done_at", ""));
                if (done > 0 && timeOfDay != null) out.doneAt = "done about " + clock(done, nowMs, timeOfDay, zone);
            }
        }
        if (temps != null && out.fresh) {
            temperature(out, temps.optJSONObject("nozzle"), "Nozzle");
            temperature(out, temps.optJSONObject("bed"), "Bed");
            temperature(out, temps.optJSONObject("chamber"), "Chamber");
        }
        if (fans != null && out.fresh && fans.has("part")) {
            int part = fans.optInt("part");
            out.numbers.add(new Number("Part fan", part + " %", "Part fan " + part + " percent"));
        }
        JSONArray faults = state == null ? null : state.optJSONArray("faults");
        if (faults != null && faults.length() > 0 && out.fresh) {
            StringBuilder codes = new StringBuilder();
            for (int i = 0; i < Math.min(faults.length(), 8); i++) { if (i > 0) codes.append(", "); codes.append(faults.optString(i)); }
            if (faults.length() > 8) codes.append(", …");
            out.faults = (faults.length() == 1 ? "The printer reports fault " : "The printer reports faults ") + codes;
            out.attention = true;
        }
        out.freshness = freshness(connection, out.fresh, route, receivedAtMs, nowMs);
        out.spoken = spoken(out);
        return out;
    }

    /** How fresh the phone's status of the printer is, and through what. */
    static String freshness(JSONObject connection, boolean fresh, String route, long receivedAtMs, long nowMs) {
        if (fresh) return "Live" + (route.isEmpty() ? "" : " · " + route);
        long age = connection == null ? -1 : connection.optLong("age_s", -1);
        if (age < 0) return "No recent status from the printer";
        age += Math.max(0, (nowMs - receivedAtMs) / 1000);
        return "Last status from the printer " + ago(age);
    }

    /** "2 min ago", "1 h 5 min ago", "just now". */
    static String ago(long seconds) { return seconds < 60 ? "just now" : duration(seconds) + " ago"; }

    /** "45 min", "1 h 5 min", "26 h". Minutes are rounded down: a print never ends sooner than the screen says. */
    static String duration(long seconds) {
        long minutes = Math.max(0, seconds) / 60, h = minutes / 60, m = minutes % 60;
        if (h == 0) return m + " min";
        if (h >= 24) return h + " h";
        return m == 0 ? h + " h" : h + " h " + m + " min";
    }

    /** "1 hour 5 minutes", for a screen reader. */
    static String spokenDuration(long seconds) {
        long minutes = Math.max(0, seconds) / 60, h = minutes / 60, m = minutes % 60;
        String hours = h == 1 ? "1 hour" : h + " hours", mins = m == 1 ? "1 minute" : m + " minutes";
        return h == 0 ? mins : m == 0 || h >= 24 ? hours : hours + " " + mins;
    }

    /** The print's line under the progress bar: "Layer 51 of 120 · 1 h 13 min left · done about 14:47". */
    static String printLine(Shown s) {
        StringBuilder b = new StringBuilder();
        // Spaces inside a part do not break, so a long line wraps only between parts ("done about" stays with its time).
        for (String part : new String[] {s.layer, s.timeLeft, s.doneAt}) if (!part.isEmpty()) b.append(b.length() > 0 ? " · " : "").append(part.replace(' ', ' '));
        if (b.length() > 0) b.setCharAt(0, Character.toUpperCase(b.charAt(0)));
        return b.toString();
    }

    /** A time of day in the TV's clock and format: "14:47", "tomorrow 02:10", "Sat 09:30". */
    static String clock(long atMs, long nowMs, DateFormat timeOfDay, TimeZone zone) {
        DateFormat f = (DateFormat) timeOfDay.clone(); f.setTimeZone(zone);
        String time = f.format(new Date(atMs));
        long days = Math.round((midnight(atMs, zone) - midnight(nowMs, zone)) / 86_400_000.0);   // rounded: a day can be 23 or 25 hours
        if (days <= 0) return time;
        if (days == 1) return "tomorrow " + time;
        SimpleDateFormat weekday = new SimpleDateFormat("EEE", Locale.getDefault()); weekday.setTimeZone(zone);
        return weekday.format(new Date(atMs)) + " " + time;
    }
    private static long midnight(long ms, TimeZone zone) {
        Calendar c = Calendar.getInstance(zone); c.setTimeInMillis(ms);
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /** "2026-10-25T02:47:05Z" as ms, 0 when missing or unreadable. */
    static long isoMs(String iso) {
        if (iso == null || iso.isEmpty()) return 0;
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT); f.setTimeZone(TimeZone.getTimeZone("UTC"));
        try { return f.parse(iso).getTime(); } catch (ParseException unreadable) { return 0; }
    }

    static String phaseWords(String phase) {
        switch (phase) {
            case "idle": return "Idle";
            case "preparing": return "Getting ready to print";
            case "printing": return "Printing";
            case "pausing": return "Pausing";
            case "paused": return "Paused";
            case "resuming": return "Resuming";
            case "stopping": return "Stopping";
            case "stopped": return "Stopped";
            case "complete": return "Print complete";
            case "busy": return "Busy";
            default: return "No status";
        }
    }

    // ------------------------------------------------------------------ print events

    /** A print event as a short banner: its title, the print it is about, and whether it needs a look. */
    static final class Banner {
        final String title, detail; final boolean alert, good;
        Banner(String title, String detail, boolean alert, boolean good) { this.title = title; this.detail = detail; this.alert = alert; this.good = good; }
        /** How long it stays: longer for what needs a look. */
        long showMs() { return alert ? 15_000 : 8_000; }
    }

    static Banner banner(JSONObject event) {
        String type = event == null ? "" : event.optString("type", ""), file = event == null ? "" : text(event, "file");
        String title; boolean alert = false, good = false;
        switch (type) {
            case "print_started": title = "Print started"; break;
            case "first_layer": title = "First layer done"; good = true; break;
            case "paused": title = "Print paused"; alert = true; break;
            case "resumed": title = "Print resumed"; break;
            case "print_finished": title = "Print finished"; good = true; break;
            case "print_stopped": title = "Print stopped"; alert = true; break;
            case "fault": title = "The printer reports a fault"; alert = true; break;
            case "guardian_alarm": title = "The print guardian sees a problem"; alert = true; break;
            case "print_watch": title = "The print watch sees a stall or heater drift"; alert = true; break;
            case "contact_lost": title = "Contact with the printer lost"; alert = true; break;
            case "bed_levelled": title = "Bed levelling finished"; good = true; break;
            case "maintenance_finished": title = "Maintenance task finished"; good = true; break;
            default: title = event == null ? "" : text(event, "words"); if (title.isEmpty()) return null;
        }
        StringBuilder detail = new StringBuilder(file);
        int layer = event.optInt("layer", -1), total = event.optInt("total_layers", -1);
        if (!type.equals("print_finished") && !type.equals("print_started") && layer >= 0 && total > 0) detail.append(detail.length() > 0 ? " · " : "").append("layer ").append(layer).append(" of ").append(total);
        return new Banner(title, detail.toString(), alert, good);
    }

    // ------------------------------------------------------------------ helpers

    private static void temperature(Shown out, JSONObject t, String label) {
        if (t == null || !t.has("now")) return;
        long now = Math.round(t.optDouble("now")), target = t.has("target") ? Math.round(t.optDouble("target")) : 0;
        // A target of 0 is a heater that is off: only the reading.
        String value = target > 0 ? now + " / " + target + " °C" : now + " °C";
        String spoken = label + " " + now + " degrees" + (target > 0 ? ", heating to " + target : "");
        out.numbers.add(new Number(label, value, spoken));
    }
    private static String spoken(Shown s) {
        StringBuilder b = new StringBuilder(s.headline);
        if (!s.file.isEmpty()) b.append(", ").append(s.file);
        if (s.progress >= 0) b.append(", ").append(s.progress).append(" percent");
        if (!s.layer.isEmpty()) b.append(", ").append(s.layer.toLowerCase(Locale.ROOT));
        if (s.remainingS >= 0 && !s.timeLeft.isEmpty()) b.append(", ").append(s.remainingS < 60 ? "under a minute" : spokenDuration(s.remainingS)).append(" left");
        if (!s.doneAt.isEmpty()) b.append(", ").append(s.doneAt);
        for (Number n : s.numbers) b.append(". ").append(n.spoken);
        if (!s.faults.isEmpty()) b.append(". ").append(s.faults);
        return b.append('.').toString();
    }
    private static String text(JSONObject o, String key) {
        if (o == null || o.isNull(key)) return "";
        return o.optString(key, "").replaceAll("\\p{Cntrl}", " ").trim();
    }
    private static boolean isOneOf(String value, String... options) { for (String o : options) if (o.equals(value)) return true; return false; }

    private StatusText() { }
}
