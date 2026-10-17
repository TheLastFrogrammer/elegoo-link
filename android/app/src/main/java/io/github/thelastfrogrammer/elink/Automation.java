package io.github.thelastfrogrammer.elink;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Automation workshop: rules of the form "when this happens (and these conditions hold), do these things". Events come from
 * observed printer status (Detector), the print guardian, the print watch and the connection. Actions tell people and other
 * apps (a notification, a webhook, Home Assistant, an Android broadcast); the only printer action is the chamber light, sent
 * once per event and never repeated (the next status shows whether it took). Rules and templates share as JSON text without
 * secrets; imported ones start turned off. Independent of Android.
 */
final class Automation {
    static final String ANY = "any", STARTED = "print_started", FIRST_LAYER = "first_layer", LAYER = "layer_reached", PAUSED = "paused", RESUMED = "resumed",
        FINISHED = "print_finished", STOPPED = "print_stopped", FAULT = "fault", GUARDIAN = "guardian_alarm", WATCH = "print_watch", LOST = "contact_lost",
        VALUE = "value_crossed", CHANGE = "value_changed";
    /** Event ids and what the workshop calls them, in the order offered. */
    static final Map<String, String> EVENTS = new LinkedHashMap<>();
    static {
        EVENTS.put(STARTED, "A print starts"); EVENTS.put(FIRST_LAYER, "The first layer is done"); EVENTS.put(LAYER, "A print reaches a layer");
        EVENTS.put(PAUSED, "A print pauses"); EVENTS.put(RESUMED, "A print resumes"); EVENTS.put(FINISHED, "A print finishes");
        EVENTS.put(STOPPED, "A print is stopped"); EVENTS.put(FAULT, "The printer reports a new fault"); EVENTS.put(GUARDIAN, "The print guardian sees a problem");
        EVENTS.put(WATCH, "The print watch sees a stall or heater drift"); EVENTS.put(LOST, "Contact with the printer is lost during a print");
        EVENTS.put(VALUE, "A value crosses a limit"); EVENTS.put(CHANGE, "A value changes"); EVENTS.put(ANY, "Any print milestone");
    }
    static final String NOTIFY = "notify", LIGHT = "light", WEBHOOK = "webhook", HA_EVENT = "ha_event", BROADCAST = "broadcast";
    static final Map<String, String> ACTIONS = new LinkedHashMap<>();
    static {
        ACTIONS.put(NOTIFY, "Show a notification"); ACTIONS.put(LIGHT, "Turn the printer's light on or off"); ACTIONS.put(WEBHOOK, "Call a webhook");
        ACTIONS.put(HA_EVENT, "Tell Home Assistant"); ACTIONS.put(BROADCAST, "Tell another app (Tasker, Automate)");
    }
    static final String FORMAT = "linkWorkshopAutomation";

    static final class Event {
        final String type; String printer = "", file = "", key = "", detail = ""; int layer = -1, total = -1, progress = -1; long remaining = -1;
        /** For layer events: the layers crossed, from (exclusive) to (inclusive). */
        int fromLayer = -1;
        /** For value events: the one rule it is for. */
        String ruleId = "";
        Event(String type) { this.type = type; }
        Map<String, String> values(long nowMs, java.util.TimeZone zone) {
            Map<String, String> v = new LinkedHashMap<>();
            java.text.SimpleDateFormat time = new java.text.SimpleDateFormat("HH:mm", Locale.ROOT), date = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
            time.setTimeZone(zone); date.setTimeZone(zone);
            v.put("event", type); v.put("event_name", EVENTS.containsKey(type) ? EVENTS.get(type) : type); v.put("printer", printer); v.put("file", StatusPresentation.clean(file));
            v.put("layer", layer < 0 ? "" : String.valueOf(layer)); v.put("total_layers", total < 0 ? "" : String.valueOf(total)); v.put("progress", progress < 0 ? "" : String.valueOf(progress));
            v.put("remaining", remaining < 0 ? "" : (remaining / 60) + " min"); v.put("detail", detail);
            v.put("time", time.format(new java.util.Date(nowMs))); v.put("date", date.format(new java.util.Date(nowMs)));
            return v;
        }
    }

    static final class Action {
        String kind = NOTIFY;
        /** light: on or off. */
        boolean on;
        /** notify: title and text; webhook: url, body (blank = a JSON summary), header name (its value is a secret kept on the phone). */
        String title = "", text = "", url = "", body = "", header = "";
        /** Attach the latest camera picture and/or a chart of this print (notification: one picture, the camera first). */
        boolean picture, chart;
        /** webhook with an attachment: send one picture as the whole body (ntfy and similar), the text in the header named by `messageHeader`, instead of a multipart form. */
        boolean rawImage; String messageHeader = "Message";
        /** Where the secret header value is kept (CredentialStore); never exported. */
        String secretId = UUID.randomUUID().toString();
        JSONObject toJson(boolean sharing) throws JSONException {
            JSONObject o = new JSONObject().put("kind", kind);
            if (kind.equals(LIGHT)) o.put("on", on);
            if (kind.equals(NOTIFY)) o.put("title", title).put("text", text);
            if (kind.equals(WEBHOOK)) { o.put("url", url).put("body", body).put("header", header); if (!sharing) o.put("secretId", secretId); if (rawImage) o.put("rawImage", true).put("messageHeader", messageHeader); }
            if (picture) o.put("picture", true); if (chart) o.put("chart", true);
            return o;
        }
        static Action fromJson(JSONObject o) throws JSONException {
            Action a = new Action(); a.kind = o.getString("kind");
            if (!ACTIONS.containsKey(a.kind)) throw new JSONException("unknown action " + a.kind);
            a.on = o.optBoolean("on"); a.title = cap(o.optString("title"), 200); a.text = cap(o.optString("text"), 2000); a.url = cap(o.optString("url"), 2000).trim();
            a.body = cap(o.optString("body"), 8000); a.header = o.optString("header").trim();
            if (!a.header.isEmpty() && !a.header.matches("[A-Za-z0-9-]{1,60}")) throw new JSONException("a header name has letters, digits and dashes only");
            if (o.has("secretId")) a.secretId = o.getString("secretId");
            a.picture = o.optBoolean("picture"); a.chart = o.optBoolean("chart"); a.rawImage = o.optBoolean("rawImage"); a.messageHeader = o.optString("messageHeader", "Message");
            if (!a.messageHeader.matches("[A-Za-z0-9-]{1,40}")) a.messageHeader = "Message";
            if (a.kind.equals(WEBHOOK) && !webhookUrlOk(a.url)) throw new JSONException("webhook address must start with https:// or http://");
            return a;
        }
        String describe() { String with = picture && chart ? " + picture and chart" : picture ? " + picture" : chart ? " + chart" : ""; return base() + with; }
        private String base() {
            switch (kind) {
                case LIGHT: return "Turn the light " + (on ? "on" : "off");
                case NOTIFY: return "Notify: " + (title.isEmpty() ? text : title);
                case WEBHOOK: return "Webhook: " + shortUrl(url) + (header.isEmpty() ? "" : " (with " + header + ")");
                case HA_EVENT: return "Tell Home Assistant";
                default: return "Tell another app";
            }
        }
    }

    static final class Rule {
        String id = UUID.randomUUID().toString(), name = "", event = FINISHED;
        boolean enabled = true;
        /** layer_reached: the layer. */
        int layer;
        /** Only between these times of day (minutes after midnight; -1 = any time). A window can run past midnight. */
        int fromMinute = -1, toMinute = -1;
        /** Only for a print marked "last print of the day" on the Print tab. */
        boolean lastPrintOnly;
        /** Wait this long after the event (the app must still be running). */
        int delayMinutes;
        /** value_crossed / value_changed: the value watched; crossed: reaching or going above `limit` (above) or going below it. */
        String field = ""; boolean above = true; double limit;
        /** Optional extra condition on any event: only while value `ifField` is at or above (ifAbove) / below `ifLimit`. */
        String ifField = ""; boolean ifAbove = true; double ifLimit;
        final List<Action> actions = new ArrayList<>();
        /** From the built-in set (not shared, not deletable, only turned on or off and copied). */
        boolean builtIn;

        boolean changesPrinter() { for (Action a : actions) if (a.kind.equals(LIGHT)) return true; return false; }
        JSONObject toJson(boolean sharing) throws JSONException {
            JSONObject o = new JSONObject().put("name", name).put("event", event).put("layer", layer).put("from", fromMinute).put("to", toMinute)
                .put("lastPrintOnly", lastPrintOnly).put("delay", delayMinutes);
            if (!field.isEmpty()) o.put("field", field).put("above", above).put("limit", limit);
            if (!ifField.isEmpty()) o.put("ifField", ifField).put("ifAbove", ifAbove).put("ifLimit", ifLimit);
            JSONArray list = new JSONArray(); for (Action a : actions) list.put(a.toJson(sharing)); o.put("actions", list);
            if (!sharing) o.put("id", id).put("enabled", enabled).put("builtIn", builtIn);
            return o;
        }
        static Rule fromJson(JSONObject o) throws JSONException {
            Rule r = new Rule();
            for (String key : new String[] {"layer", "from", "to", "delay", "limit", "ifLimit"})
                if (o.has(key) && (!(o.opt(key) instanceof Number) || Double.isNaN(o.optDouble(key)) || Double.isInfinite(o.optDouble(key)))) throw new JSONException(key + " must be a number");
            r.name = cap(o.optString("name", "").trim(), 80); r.event = o.getString("event");
            if (!EVENTS.containsKey(r.event)) throw new JSONException("unknown event " + r.event);
            r.layer = Math.max(0, o.optInt("layer")); r.fromMinute = minute(o.optInt("from", -1)); r.toMinute = minute(o.optInt("to", -1));
            r.lastPrintOnly = o.optBoolean("lastPrintOnly"); r.delayMinutes = Math.max(0, Math.min(240, o.optInt("delay")));
            r.field = o.optString("field", ""); r.above = o.optBoolean("above", true); r.limit = o.optDouble("limit", 0);
            r.ifField = o.optString("ifField", ""); r.ifAbove = o.optBoolean("ifAbove", true); r.ifLimit = o.optDouble("ifLimit", 0);
            if (!r.field.isEmpty() && !validField(r.field) || !r.ifField.isEmpty() && !validField(r.ifField)) throw new JSONException("unknown value " + r.field + r.ifField);
            if ((r.event.equals(VALUE) || r.event.equals(CHANGE)) && r.field.isEmpty()) throw new JSONException("a value rule needs the value it watches");
            if (Double.isNaN(r.limit) || Double.isInfinite(r.limit) || Double.isNaN(r.ifLimit) || Double.isInfinite(r.ifLimit)) throw new JSONException("limits must be numbers");
            JSONArray list = o.optJSONArray("actions");
            for (int i = 0; list != null && i < list.length(); i++) r.actions.add(Action.fromJson(list.getJSONObject(i)));
            if (r.actions.isEmpty()) throw new JSONException("a rule needs at least one action");
            if (r.actions.size() > 8) throw new JSONException("at most 8 actions per rule");
            if (o.has("id")) r.id = o.getString("id"); r.enabled = o.optBoolean("enabled", true); r.builtIn = o.optBoolean("builtIn");
            if (r.name.isEmpty()) r.name = EVENTS.get(r.event);
            return r;
        }
        Rule copy() throws JSONException { Rule r = fromJson(toJson(true)); r.builtIn = false; return r; }
        /** One line for the rule list: "A print finishes · after 18:00 · wait 10 min → Turn the light off". */
        String summary() {
            StringBuilder s = new StringBuilder(event.equals(LAYER) ? "Layer " + layer + " reached"
                : event.equals(VALUE) ? "{" + field + "} " + (above ? "reaches " : "goes below ") + number(limit)
                : event.equals(CHANGE) ? "{" + field + "} changes" : EVENTS.get(event));
            if (!ifField.isEmpty()) s.append(" · while {").append(ifField).append("} ").append(ifAbove ? "≥ " : "< ").append(number(ifLimit));
            if (fromMinute >= 0 || toMinute >= 0) s.append(" · ").append(window(fromMinute, toMinute));
            if (lastPrintOnly) s.append(" · last print of the day");
            if (delayMinutes > 0) s.append(" · wait ").append(delayMinutes).append(" min");
            s.append(" → ");
            for (int i = 0; i < actions.size(); i++) s.append(i > 0 ? "; " : "").append(actions.get(i).describe());
            return s.toString();
        }
    }

    /** The running print's own choices (Print tab, Automations…): marked as the day's last print, and rules muted for it. */
    static final class ThisPrint {
        String key = ""; boolean lastOfDay; final Set<String> muted = new HashSet<>();
        JSONObject toJson() throws JSONException { return new JSONObject().put("key", key).put("lastOfDay", lastOfDay).put("muted", new JSONArray(new ArrayList<>(muted))); }
        static ThisPrint fromJson(String text) {
            ThisPrint p = new ThisPrint();
            try { JSONObject o = new JSONObject(text); p.key = o.optString("key"); p.lastOfDay = o.optBoolean("lastOfDay"); JSONArray m = o.optJSONArray("muted");
                for (int i = 0; m != null && i < m.length(); i++) p.muted.add(m.getString(i)); } catch (Exception unreadable) { }
            return p;
        }
        ThisPrint forPrint(String printKey) { if (printKey.equals(key)) return this; ThisPrint fresh = new ThisPrint(); fresh.key = printKey; return fresh; }
    }

    static boolean matches(Rule rule, Event event, int minuteOfDay, ThisPrint print) { return matches(rule, event, minuteOfDay, print, null); }
    /** Whether `rule` runs for `event` at `minuteOfDay`, given the print's own choices and the values now (for its extra condition). */
    static boolean matches(Rule rule, Event event, int minuteOfDay, ThisPrint print, Map<String, String> values) {
        if (!rule.enabled) return false;
        if (!rule.event.equals(ANY) && !rule.event.equals(event.type)) return false;
        if (rule.event.equals(ANY) && (event.type.equals(LAYER) || event.type.equals(VALUE) || event.type.equals(CHANGE))) return false;   // "any" means the milestones
        if ((event.type.equals(VALUE) || event.type.equals(CHANGE)) && !rule.id.equals(event.ruleId)) return false;
        if (!rule.ifField.isEmpty()) {
            double v = AutomationValues.number(values, rule.ifField);
            if (Double.isNaN(v) || (rule.ifAbove ? v < rule.ifLimit : v >= rule.ifLimit)) return false;
        }
        if (rule.event.equals(LAYER) && !(rule.layer > 0 && event.fromLayer < rule.layer && event.layer >= rule.layer)) return false;
        if (!inWindow(minuteOfDay, rule.fromMinute, rule.toMinute)) return false;
        ThisPrint p = print == null ? new ThisPrint() : print.forPrint(event.key);
        if (rule.lastPrintOnly && !p.lastOfDay) return false;
        return !p.muted.contains(rule.id);
    }
    static boolean inWindow(int minute, int from, int to) {
        if (from < 0 && to < 0 || from == to) return true;   // the same start and end: all day
        if (from >= 0 && to < 0) return minute >= from;
        if (from < 0) return minute < to;
        return from <= to ? minute >= from && minute < to : minute >= from || minute < to;   // past midnight
    }
    static String window(int from, int to) {
        if (from >= 0 && to >= 0) return clock(from) + "–" + clock(to);
        return from >= 0 ? "after " + clock(from) : "before " + clock(to);
    }
    static String cap(String text, int length) { return text == null ? "" : text.length() <= length ? text : text.substring(0, length); }
    static boolean validField(String key) { return AutomationValues.find(key) != null || key.matches("status(\\.[A-Za-z0-9_]+){1,8}"); }
    static String number(double v) { return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v); }

    /**
     * Value rules: "crosses a limit" fires when the value goes from the safe side to the limit (reaching it, for "reaches"),
     * then waits until it is back on the safe side by a margin (2 % of the limit, at least 1) before it can fire again; the
     * first value seen only sets the side, so connecting while already past a limit does not fire. "Changes" fires when the
     * value differs from the last one seen, at most once a minute.
     */
    static final class Watcher {
        private final Map<String, Boolean> armed = new java.util.HashMap<>();
        private final Map<String, String> last = new java.util.HashMap<>();
        private final Map<String, Long> firedAt = new java.util.HashMap<>();
        List<Event> check(List<Rule> rules, Map<String, String> values, long nowMs, Event template) {
            List<Event> out = new ArrayList<>();
            for (Rule r : rules) {
                if (!r.enabled || r.field.isEmpty()) continue;
                String text = values.get(r.field);
                if (r.event.equals(CHANGE)) {
                    if (text == null) continue;
                    String before = last.put(r.id, text);
                    Long at = firedAt.get(r.id);
                    if (before != null && !before.equals(text) && (at == null || nowMs - at >= 60_000)) { firedAt.put(r.id, nowMs); out.add(valueEvent(r, template, "{" + r.field + "} " + before + " → " + text)); }
                } else if (r.event.equals(VALUE)) {
                    double v = AutomationValues.number(values, r.field); if (Double.isNaN(v)) continue;
                    boolean past = r.above ? v >= r.limit : v < r.limit;
                    double margin = Math.max(1, Math.abs(r.limit) * 0.02);
                    boolean safe = r.above ? v < r.limit - margin : v >= r.limit + margin;
                    Boolean was = armed.get(r.id);
                    if (was == null) { armed.put(r.id, !past); continue; }
                    if (was && past) { armed.put(r.id, false); out.add(valueEvent(r, template, "{" + r.field + "} " + text + (r.above ? ", reached " : ", below ") + number(r.limit))); }
                    else if (!was && safe) armed.put(r.id, true);
                }
            }
            return out;
        }
        private static Event valueEvent(Rule r, Event template, String detail) {
            Event e = new Event(r.event); e.ruleId = r.id; e.detail = detail;
            if (template != null) { e.printer = template.printer; e.file = template.file; e.key = template.key; e.layer = template.layer; e.total = template.total; e.progress = template.progress; e.remaining = template.remaining; }
            return e;
        }
    }
    static String clock(int minute) { return String.format(Locale.ROOT, "%02d:%02d", minute / 60, minute % 60); }
    private static int minute(int m) { return m < 0 || m >= 24 * 60 ? -1 : m; }

    /** Fills {name} placeholders; `json` escapes each value for a JSON string. Unknown placeholders stay as written. */
    static String fill(String template, Map<String, String> values, boolean json) {
        StringBuilder out = new StringBuilder(); int i = 0;
        while (i < template.length()) {
            int open = template.indexOf('{', i), close = open < 0 ? -1 : template.indexOf('}', open);
            if (open < 0 || close < 0) { out.append(template, i, template.length()); break; }
            String name = template.substring(open + 1, close);
            if (values.containsKey(name)) { out.append(template, i, open); String v = values.get(name); out.append(json ? escape(v) : v); i = close + 1; }
            else { out.append(template, i, open + 1); i = open + 1; }
        }
        return out.toString();
    }
    static String escape(String v) { String quoted = JSONObject.quote(v == null ? "" : v); return quoted.substring(1, quoted.length() - 1); }
    /** The webhook body: the filled template, or a JSON object of every value when the template is blank. */
    static String body(Action action, Map<String, String> values) {
        if (action.body.trim().isEmpty()) { JSONObject o = new JSONObject(); for (Map.Entry<String, String> e : values.entrySet()) if (!e.getKey().startsWith("status.")) try { o.put(e.getKey(), e.getValue()); } catch (JSONException impossible) { } o.remove("event_name"); return o.toString(); }
        return fill(action.body, values, looksLikeJson(action.body));
    }
    static boolean looksLikeJson(String body) { String t = body.trim(); return t.startsWith("{") || t.startsWith("["); }
    static boolean webhookUrlOk(String url) {
        try { java.net.URI u = new java.net.URI(url.trim()); return ("https".equalsIgnoreCase(u.getScheme()) || "http".equalsIgnoreCase(u.getScheme())) && u.getHost() != null && !u.getHost().isEmpty(); }
        catch (Exception bad) { return false; }
    }
    static String shortUrl(String url) {
        try { java.net.URI u = new java.net.URI(url.trim()); return u.getHost() == null ? url : u.getHost(); } catch (Exception bad) { return url; }
    }

    // Sharing: rules and templates as JSON text, without ids, on/off state or secrets.
    static String share(List<Rule> rules) throws JSONException {
        JSONArray list = new JSONArray(); for (Rule r : rules) list.put(r.toJson(true));
        return new JSONObject().put(FORMAT, 1).put("rules", list).toString(2);
    }
    /** Rules from shared text (a whole share, a single rule, or a list); every one starts turned off. */
    static List<Rule> parseShared(String text) throws JSONException {
        String t = text == null ? "" : text.trim();
        int start = t.indexOf('{'), startList = t.indexOf('[');
        if (start < 0 && startList < 0) throw new JSONException("no automation found in the text");
        List<Rule> rules = new ArrayList<>(); JSONArray list;
        if (startList >= 0 && (start < 0 || startList < start)) list = new JSONArray(t.substring(startList));
        else {
            JSONObject o = new JSONObject(t.substring(start, t.lastIndexOf('}') + 1));
            if (o.has(FORMAT) && o.optInt(FORMAT) > 1) throw new JSONException("made by a newer version of the app");
            list = o.has("rules") ? o.getJSONArray("rules") : new JSONArray().put(o);
        }
        if (list.length() > 50) throw new JSONException("too many rules (at most 50)");
        for (int i = 0; i < list.length(); i++) { JSONObject one = list.getJSONObject(i); one.remove("id"); one.remove("builtIn"); one.remove("enabled");
            JSONArray actions = one.optJSONArray("actions"); for (int k = 0; actions != null && k < actions.length(); k++) { JSONObject a = actions.optJSONObject(k); if (a != null) a.remove("secretId"); }
            Rule r = Rule.fromJson(one); r.enabled = false; rules.add(r); }
        return rules;
    }
    static String save(List<Rule> rules) throws JSONException {
        JSONArray list = new JSONArray(); for (Rule r : rules) list.put(r.toJson(false)); return new JSONObject().put(FORMAT, 1).put("rules", list).toString();
    }
    static List<Rule> load(String text) {
        List<Rule> rules = new ArrayList<>();
        try {
            JSONArray list = new JSONObject(text).getJSONArray("rules");
            for (int i = 0; i < list.length(); i++) {
                JSONObject one = list.optJSONObject(i); if (one == null) continue;
                try { rules.add(Rule.fromJson(one)); }
                catch (JSONException strict) {
                    // The person's own rule from an earlier version: drop only a header name that is no longer accepted, rather than the rule.
                    JSONArray actions = one.optJSONArray("actions");
                    for (int k = 0; actions != null && k < actions.length(); k++) { JSONObject a = actions.optJSONObject(k); if (a != null && !a.optString("header").trim().matches("|[A-Za-z0-9-]{1,60}")) a.remove("header"); }
                    try { rules.add(Rule.fromJson(one)); } catch (JSONException skipped) { Diagnostics.note(Diagnostics.AUTOMATIONS, "a saved rule could not be read: " + skipped.getMessage()); }
                }
            }
        }
        catch (Exception unreadable) { }
        return rules;
    }

    /** The rules a new install starts with: Home Assistant and a stopped-print notification on, the light ones off until chosen. */
    static List<Rule> defaults() {
        List<Rule> out = new ArrayList<>();
        Rule ha = new Rule(); ha.id = "default-ha"; ha.builtIn = true; ha.name = "Every print event to Home Assistant"; ha.event = ANY; ha.actions.add(action(HA_EVENT)); out.add(ha);
        Rule stopped = new Rule(); stopped.id = "default-stopped"; stopped.builtIn = true; stopped.name = "Tell me when a print is stopped"; stopped.event = STOPPED;
        Action note = action(NOTIFY); note.title = "Print stopped"; note.text = "{file} stopped at layer {layer} of {total_layers}."; stopped.actions.add(note); out.add(stopped);
        Rule off = new Rule(); off.id = "default-light-last"; off.builtIn = true; off.enabled = false; off.name = "Light off after the last print of the day"; off.event = FINISHED;
        off.lastPrintOnly = true; off.delayMinutes = 10; Action dark = action(LIGHT); dark.on = false; off.actions.add(dark); out.add(off);
        return out;
    }
    /** Ready-made rules to start from (each is copied, then edited). */
    static List<Rule> templates() {
        List<Rule> out = new ArrayList<>();
        out.add(template("Light off 10 minutes after a print finishes", FINISHED, 10, light(false)));
        Rule last = template("Light off after the last print of the day", FINISHED, 10, light(false)); last.lastPrintOnly = true; out.add(last);
        out.add(template("Light on when a print starts", STARTED, 0, light(true)));
        out.add(template("Light on when the guardian sees a problem (better pictures)", GUARDIAN, 0, light(true)));
        Rule night = template("Light off after prints that finish at night", FINISHED, 5, light(false)); night.fromMinute = 22 * 60; night.toMinute = 7 * 60; out.add(night);
        Action ntfy = action(WEBHOOK); ntfy.url = "https://ntfy.sh/your-topic"; ntfy.body = "{event_name}: {file} ({progress}%)";
        out.add(template("Push to ntfy on every milestone", ANY, 0, ntfy));
        Action discord = action(WEBHOOK); discord.url = "https://discord.com/api/webhooks/your-id/your-token"; discord.body = "{\"content\": \"{printer}: {event_name} · {file} · layer {layer}/{total_layers}\"}";
        out.add(template("Post to a Discord channel when a print finishes", FINISHED, 0, discord));
        Action haHook = action(WEBHOOK); haHook.url = "http://homeassistant.local:8123/api/webhook/your-webhook-id";
        out.add(template("Home Assistant webhook when a print stops or faults", ANY, 0, haHook));
        out.add(template("Tasker or Automate: every milestone", ANY, 0, action(BROADCAST)));
        Action first = action(NOTIFY); first.title = "First layer done"; first.text = "Have a look at {file}'s first layer."; first.picture = true;
        out.add(template("Remind me to check the first layer (with a picture)", FIRST_LAYER, 0, first));
        Action shot = action(WEBHOOK); shot.url = "https://discord.com/api/webhooks/your-id/your-token"; shot.picture = true; shot.chart = true;
        shot.body = "{\"content\": \"{printer}: {file} finished in {elapsed} · nozzle {nozzle} °C · bed {bed} °C · chamber max {chamber_max} °C\"}";
        out.add(template("Post the last picture and the print's chart to Discord", FINISHED, 0, shot));
        Action ntfyPicture = action(WEBHOOK); ntfyPicture.url = "https://ntfy.sh/your-topic"; ntfyPicture.picture = true; ntfyPicture.rawImage = true; ntfyPicture.body = "{event_name}: {file}, layer {layer}/{total_layers}";
        out.add(template("ntfy with the camera picture at each milestone", ANY, 0, ntfyPicture));
        Action warm = action(NOTIFY); warm.title = "Chamber is warm"; warm.text = "The chamber reached {chamber} °C."; Rule chamber = template("Chamber reached 40 °C (ready for ABS/ASA)", VALUE, 0, warm);
        chamber.field = "chamber"; chamber.above = true; chamber.limit = 40; out.add(chamber);
        Action fanNote = action(NOTIFY); fanNote.title = "Heatsink fan stopped?"; fanNote.text = "The hotend heatsink fan reports {heatsink_fan_rpm} rpm with the nozzle at {nozzle} °C. Check it before the hotend clogs."; fanNote.picture = true;
        Rule fan = template("Heatsink fan stopped while the nozzle is hot", VALUE, 0, fanNote); fan.field = "heatsink_fan_rpm"; fan.above = false; fan.limit = 500;
        fan.ifField = "nozzle"; fan.ifAbove = true; fan.ifLimit = 150; out.add(fan);
        Action half = action(NOTIFY); half.title = "Halfway"; half.text = "{file} is at {progress} %, done ≈ {done_at}."; half.chart = true;
        Rule halfway = template("Halfway there (with the chart)", VALUE, 0, half); halfway.field = "progress"; halfway.limit = 50; out.add(halfway);
        Action spool = action(NOTIFY); spool.title = "Filament changed"; spool.text = "Now printing from tray {tray}: {filament_type} {filament_colour}.";
        Rule tray = template("Tell me when the active filament tray changes", CHANGE, 0, spool); tray.field = "tray"; out.add(tray);
        return out;
    }
    private static Rule template(String name, String event, int delay, Action action) { Rule r = new Rule(); r.name = name; r.event = event; r.delayMinutes = delay; r.actions.add(action); return r; }
    private static Action action(String kind) { Action a = new Action(); a.kind = kind; return a; }
    private static Action light(boolean on) { Action a = action(LIGHT); a.on = on; return a; }

    /**
     * Turns printer status into events: only what was seen happening (an app that connects in the middle of a print does not
     * report it as started, and a print seen only at its end is not reported as finished).
     */
    static final class Detector {
        private String job = "", file = ""; private boolean watched, paused, ended; private int lastLayer = -1;
        private Set<String> faults = new HashSet<>();
        List<Event> update(JSONObject status, String printer) {
            List<Event> out = new ArrayList<>();
            JSONObject machine = status == null ? null : status.optJSONObject("machine_status"), print = status == null ? null : status.optJSONObject("print_status");
            String codes = status == null ? "" : StatusPresentation.faultCodes(status);
            Set<String> now = codes.isEmpty() ? new HashSet<>() : new HashSet<>(Arrays.asList(codes.split(", ")));
            Set<String> added = new HashSet<>(now); added.removeAll(faults); faults = now;
            if (machine == null) return out;
            int state = machine.optInt("status", -1), sub = machine.optInt("sub_status", -1);
            String name = print == null ? "" : print.optString("filename", ""), key = print == null ? "" : print.optString("uuid", ""); if (key.isEmpty()) key = name;
            int layer = print == null ? -1 : print.optInt("current_layer", -1);
            if (state == 2 && !key.isEmpty()) {
                // The same file printed again straight after it ended (no idle report between two cloud polls): a new print.
                boolean again = key.equals(job) && ended && sub != 2077 && sub != 2504 && layer >= 0 && layer <= 1;
                if (!key.equals(job) || again) {
                    job = key; file = name; paused = false; ended = false; lastLayer = -1;
                    watched = layer <= 1 && machine.optInt("progress", 0) <= 1 && sub != 2077 && sub != 2504;
                    if (watched) out.add(event(STARTED, status, printer));
                }
                boolean pausedNow = sub == 2501 || sub == 2502 || sub == 2505;
                if (pausedNow && !paused && !ended) out.add(event(PAUSED, status, printer));
                if (!pausedNow && paused && sub == 2075) out.add(event(RESUMED, status, printer));
                paused = pausedNow;
                if (!ended && sub == 2077) { ended = true; if (watched || lastLayer >= 0) out.add(event(FINISHED, status, printer)); }
                if (!ended && sub == 2504) { ended = true; out.add(event(STOPPED, status, printer)); }
                if (!ended && layer >= 0) {
                    if (lastLayer >= 0 && layer > lastLayer) {
                        if (lastLayer <= 1 && layer >= 2) out.add(event(FIRST_LAYER, status, printer));
                        Event crossed = event(LAYER, status, printer); crossed.fromLayer = lastLayer; out.add(crossed);
                    }
                    lastLayer = layer;
                }
            } else if (state == 1) { job = ""; watched = false; paused = false; lastLayer = -1; }
            if (!added.isEmpty()) { Event fault = event(FAULT, status, printer); fault.detail = "fault " + codes; out.add(fault); }
            return out;
        }
        /** An event from elsewhere (guardian, watch, lost contact) for the print being watched. */
        Event outside(String type, String detail, JSONObject status, String printer) { Event e = event(type, status, printer); e.detail = detail; if (e.key.isEmpty()) { e.key = job; e.file = file; } return e; }
        private static Event event(String type, JSONObject status, String printer) {
            Event e = new Event(type); e.printer = printer == null ? "" : printer;
            JSONObject machine = status == null ? null : status.optJSONObject("machine_status"), print = status == null ? null : status.optJSONObject("print_status");
            if (print != null) { e.file = print.optString("filename", ""); e.key = print.optString("uuid", ""); if (e.key.isEmpty()) e.key = e.file;
                e.layer = print.optInt("current_layer", -1); e.total = print.optInt("total_layer", -1); e.remaining = print.optLong("remaining_time_sec", -1); }
            if (machine != null) e.progress = machine.optInt("progress", -1);
            return e;
        }
    }
}
