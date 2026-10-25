package io.github.thelastfrogrammer.elink;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs the automation workshop's rules (Automation) inside the printer service. Rules are kept in automations.json, the
 * person's own templates in automation-templates.json, the running print's choices in the settings. The printer's light is
 * the only printer action: it is sent once, never repeated, only on a live connection (through the cloud only after the cloud
 * control agreement), and the next status is checked to say whether it took.
 */
final class AutomationRun {
    static final String BROADCAST_ACTION = "io.github.thelastfrogrammer.elink.AUTOMATION";
    static final String SETTINGS_KEY = "automationThisPrint";

    interface Host {
        /** Why the light cannot be sent now, or "" when it can. */
        String lightBlocked();
        void light(boolean on);
        JSONObject liveStatus();
        /** The active CANVAS trays as last reported, or null. */
        JSONObject canvas();
        /** The app's own values (pictures kept, guardian verdict). */
        Map<String, String> appValues();
        void notifyPerson(int id, String title, String text, android.graphics.Bitmap picture);
        /** Tells Home Assistant (no-op when the link is off), with a chart PNG for its chart camera when given; returns what happened for the log. */
        String homeAssistantEvent(JSONObject event, byte[] chart);
        /** Tells other apps (the local API's event stream, the relay); no-op when they are off. */
        void integrationEvent(Automation.Event event);
        /** The print being recorded now, or null. Main thread; reads nothing from disk. */
        PrintRecorder.Recording recordingNow();
        /** Which printer over which connection events come from now ("" when none): a delayed action checks it is still the same. */
        String printerKey();
        /** That print's newest camera picture as JPEG (null: the print being recorded, or else the last finished one), or null. Not on the main thread. */
        byte[] latestPicture(PrintRecorder.Recording recording);
        /** That print's recorded samples for a chart (null as above), or null when there is none. Reads the disk: not on the main thread. */
        List<double[]> chartRows(PrintRecorder.Recording recording);
        /** Those samples drawn as a PNG chart, or null with too few. Main thread. */
        byte[] chartPng(PrintRecorder.Recording recording, List<double[]> rows);
    }

    private final Context context;
    private final Host host;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService web = Executors.newSingleThreadExecutor();
    final Automation.Detector local = new Automation.Detector(), cloud = new Automation.Detector();
    private final Automation.Watcher watcher = new Automation.Watcher();
    private List<Automation.Rule> rules;
    private long rulesRead = -1;

    AutomationRun(Context context, Host host) { this.context = context.getApplicationContext(); this.host = host; }

    static File rulesFile(Context context) { return new File(context.getFilesDir(), "automations.json"); }
    static File templatesFile(Context context) { return new File(context.getFilesDir(), "automation-templates.json"); }

    /** The saved rules; the defaults the first time. */
    static synchronized List<Automation.Rule> rules(Context context) {
        File file = rulesFile(context);
        if (!file.isFile()) { List<Automation.Rule> start = Automation.defaults(); save(context, start); return start; }
        try { return Automation.load(new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)); }
        catch (IOException unreadable) { return new ArrayList<>(); }
    }
    static synchronized void save(Context context, List<Automation.Rule> rules) { write(rulesFile(context), rules); }
    static synchronized List<Automation.Rule> myTemplates(Context context) {
        File file = templatesFile(context);
        try { return file.isFile() ? Automation.load(new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)) : new ArrayList<>(); }
        catch (IOException unreadable) { return new ArrayList<>(); }
    }
    static synchronized void saveTemplates(Context context, List<Automation.Rule> templates) { write(templatesFile(context), templates); }
    private static void write(File file, List<Automation.Rule> rules) {
        try { File temp = new File(file.getPath() + ".tmp"); java.nio.file.Files.write(temp.toPath(), Automation.save(rules).getBytes(StandardCharsets.UTF_8));
            if (!temp.renameTo(file)) throw new IOException("rename"); }
        catch (Exception failed) { Diagnostics.note(Diagnostics.AUTOMATIONS, "could not save: " + failed.getClass().getSimpleName()); }
    }
    static Automation.ThisPrint thisPrint(Context context) {
        return Automation.ThisPrint.fromJson(context.getSharedPreferences("workshop-settings", Context.MODE_PRIVATE).getString(SETTINGS_KEY, "{}"));
    }
    static void saveThisPrint(Context context, Automation.ThisPrint print) {
        try { context.getSharedPreferences("workshop-settings", Context.MODE_PRIVATE).edit().putString(SETTINGS_KEY, print.toJson().toString()).apply(); } catch (Exception impossible) { }
    }

    private List<Automation.Rule> current() {
        long stamp = rulesFile(context).lastModified();
        if (rules == null || stamp != rulesRead) { rules = rules(context); rulesRead = stamp; }
        return rules;
    }

    /** A status from the printer (local or cloud detector). Call on the main thread. */
    void status(Automation.Detector detector, JSONObject status, String printer) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post(() -> status(detector, status, printer)); return; }
        for (Automation.Event e : detector.update(status, printer)) fire(e);
        List<Automation.Rule> valueRules = new ArrayList<>();
        for (Automation.Rule r : current()) if (r.enabled && (r.event.equals(Automation.VALUE) || r.event.equals(Automation.CHANGE))) valueRules.add(r);
        if (valueRules.isEmpty()) return;
        Map<String, String> values = values(status);
        for (Automation.Event e : watcher.check(valueRules, values, System.currentTimeMillis(), detector.outside(Automation.VALUE, "", status, printer))) fire(e);
    }
    /** Every value now: the named ones and the raw fields. */
    Map<String, String> values(JSONObject status) {
        Map<String, String> v = AutomationValues.from(status, host.canvas(), host.appValues(), System.currentTimeMillis(), java.util.Locale.getDefault(), java.util.TimeZone.getDefault());
        v.putAll(AutomationValues.raw(status)); return v;
    }
    /** An event the service noticed itself (guardian, print watch, lost contact). */
    void outside(Automation.Detector detector, String type, String detail, JSONObject status, String printer) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post(() -> outside(detector, type, detail, status, printer)); return; }
        fire(detector.outside(type, detail, status, printer)); }

    void fire(Automation.Event event) {
        host.integrationEvent(event);
        if (!event.type.equals(Automation.LAYER)) Diagnostics.note(Diagnostics.AUTOMATIONS, "event: " + event.type + (event.layer >= 0 ? " at layer " + event.layer : ""));
        java.util.Calendar now = java.util.Calendar.getInstance();
        int minute = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE);
        Automation.ThisPrint print = thisPrint(context);
        // A new print: choices kept for an earlier print of the same file no longer apply (made more than 10 minutes before).
        if (event.type.equals(Automation.STARTED) && (print.lastOfDay || !print.muted.isEmpty()) && print.isFor(event.key, event.file)
                && System.currentTimeMillis() - print.savedAt > 10 * 60_000L) {
            Diagnostics.note(Diagnostics.AUTOMATIONS, "a new print started: the earlier print's choices (last of the day, skipped rules) were cleared");
            print = new Automation.ThisPrint(); saveThisPrint(context, print);
        }
        Map<String, String> valuesNow = null;
        // What the event belongs to, fixed now: the print (its pictures and chart) and the printer. A delayed rule uses these,
        // not whatever is current when it runs.
        PrintRecorder.Recording recording = host.recordingNow(); String printerKey = host.printerKey();
        for (Automation.Rule rule : current()) {
            if (!rule.ifField.isEmpty() && valuesNow == null) valuesNow = values(host.liveStatus());
            if (!Automation.matches(rule, event, minute, print, valuesNow)) {
                if (rule.enabled && rule.lastPrintOnly && rule.event.equals(event.type) && !print.forPrint(event.key, event.file).lastOfDay)
                    Diagnostics.note(Diagnostics.AUTOMATIONS, "\"" + rule.name + "\" did not run: this print was not marked as the last of the day (Print tab, Automations…)");
                continue;
            }
            if (rule.delayMinutes > 0) {
                Diagnostics.note(Diagnostics.AUTOMATIONS, "\"" + rule.name + "\" runs in " + rule.delayMinutes + " min (" + event.type + ")");
                String id = rule.id;
                main.postDelayed(() -> {
                    // Turned off, deleted or changed meanwhile: the rule as it is now decides.
                    Automation.Rule saved = null; for (Automation.Rule r : current()) if (r.id.equals(id)) saved = r;
                    if (saved == null || !saved.enabled) { Diagnostics.note(Diagnostics.AUTOMATIONS, "\"" + rule.name + "\" did not run: it was turned off or deleted while waiting"); return; }
                    run(saved, event, false, recording, printerKey);
                }, rule.delayMinutes * 60_000L);
            } else run(rule, event, false, recording, printerKey);
        }
    }

    /** Runs a rule's actions once (main thread). `test`: from the workshop's Test button, with a sample event. */
    void run(Automation.Rule rule, Automation.Event event, boolean test) { run(rule, event, test, host.recordingNow(), host.printerKey()); }
    private void run(Automation.Rule rule, Automation.Event event, boolean test, PrintRecorder.Recording recording, String printerKey) {
        // The status values now, with the event's own on top (its file and layer are those of the moment it happened).
        Map<String, String> values = values(host.liveStatus());
        for (Map.Entry<String, String> e : event.values(System.currentTimeMillis(), java.util.TimeZone.getDefault()).entrySet()) if (!e.getValue().isEmpty() || !values.containsKey(e.getKey())) values.put(e.getKey(), e.getValue());
        String label = "\"" + rule.name + "\"" + (test ? " (test)" : "");
        boolean wantPicture = false, wantChart = false; for (Automation.Action a : rule.actions) { wantPicture |= a.picture; wantChart |= a.chart; }
        if (!wantPicture && !wantChart) { actions(rule, event, values, label, null, null, false, false, printerKey); return; }
        // Pictures and recordings are read off the main thread; the chart is drawn back on it, then the actions run.
        boolean picturesWanted = wantPicture, chartWanted = wantChart;
        web.execute(() -> {
            byte[] picture = picturesWanted ? host.latestPicture(recording) : null;
            List<double[]> rows = chartWanted ? host.chartRows(recording) : null;
            main.post(() -> actions(rule, event, values, label, picture, rows == null ? null : host.chartPng(recording, rows), picturesWanted, chartWanted, printerKey));
        });
    }
    private void actions(Automation.Rule rule, Automation.Event event, Map<String, String> values, String label, byte[] picture, byte[] chart, boolean wantPicture, boolean wantChart, String printerKey) {
        if (wantPicture && picture == null) Diagnostics.note(Diagnostics.AUTOMATIONS, label + ": no camera picture of this print yet; sent without it");
        if (wantChart && chart == null) Diagnostics.note(Diagnostics.AUTOMATIONS, label + ": no recording to chart yet; sent without it");
        for (Automation.Action a : rule.actions) {
            try {
                switch (a.kind) {
                    case Automation.NOTIFY:
                        byte[] shown = a.picture && picture != null ? picture : a.chart ? chart : null;
                        host.notifyPerson(100 + Math.abs(rule.id.hashCode() % 1000), Automation.fill(a.title.isEmpty() ? "{event_name}" : a.title, values, false), Automation.fill(a.text.isEmpty() ? "{file}" : a.text, values, false),
                            shown == null ? null : android.graphics.BitmapFactory.decodeByteArray(shown, 0, shown.length));
                        Diagnostics.note(Diagnostics.AUTOMATIONS, label + ": notification shown"); break;
                    case Automation.LIGHT:
                        // Only on the printer the event came from: never redirected to another printer or connection.
                        if (printerKey != null && !printerKey.equals(host.printerKey())) { Diagnostics.note(Diagnostics.AUTOMATIONS, label + ": light not sent: the printer or connection changed since the event"); break; }
                        light(label, a.on); break;
                    case Automation.WEBHOOK:
                        if (a.needsAddress) { Diagnostics.note(Diagnostics.AUTOMATIONS, label + ": webhook not sent: its address must be entered again (it came from a share or a backup)"); break; }
                        webhook(label, a, values, a.picture ? picture : null, a.chart ? chart : null, true); break;
                    case Automation.HA_EVENT: {
                        JSONObject o = new JSONObject().put("event_type", event.type);
                        for (Map.Entry<String, String> v : values.entrySet()) if (!v.getKey().equals("event") && !v.getKey().startsWith("status.")) o.put(v.getKey(), v.getValue());
                        Diagnostics.note(Diagnostics.AUTOMATIONS, label + ": Home Assistant " + host.homeAssistantEvent(o, a.chart ? chart : null)); break;
                    }
                    case Automation.BROADCAST: {
                        Intent intent = new Intent(BROADCAST_ACTION);
                        for (Map.Entry<String, String> v : values.entrySet()) if (!v.getKey().startsWith("status.")) intent.putExtra(v.getKey(), v.getValue());
                        context.sendBroadcast(intent);
                        Diagnostics.note(Diagnostics.AUTOMATIONS, label + ": broadcast " + BROADCAST_ACTION + " sent"); break;
                    }
                    default: break;
                }
            } catch (Exception failed) { Diagnostics.note(Diagnostics.AUTOMATIONS, label + ": " + a.kind + " failed: " + failed.getClass().getSimpleName()); }
        }
    }

    private void light(String label, boolean on) {
        String blocked = host.lightBlocked();
        if (!blocked.isEmpty()) { Diagnostics.note(Diagnostics.AUTOMATIONS, label + ": light " + (on ? "on" : "off") + " not sent: " + blocked); return; }
        JSONObject led = host.liveStatus().optJSONObject("led");
        if (led != null && led.has("status") && (led.optInt("status") != 0) == on) { Diagnostics.note(Diagnostics.AUTOMATIONS, label + ": light is already " + (on ? "on" : "off") + "; nothing sent"); return; }
        host.light(on);
        Diagnostics.note(Diagnostics.AUTOMATIONS, label + ": light " + (on ? "on" : "off") + " sent once");
        // The acknowledgement is not the result: look at what the printer reports a little later, and never send again.
        main.postDelayed(() -> {
            JSONObject after = host.liveStatus().optJSONObject("led");
            boolean known = after != null && after.has("status");
            Diagnostics.note(Diagnostics.AUTOMATIONS, label + ": " + (!known ? "the printer has not reported its light since; not sent again"
                : (after.optInt("status") != 0) == on ? "the printer now reports the light " + (on ? "on" : "off")
                : "the printer still reports the light " + (on ? "off" : "on") + "; not sent again"));
        }, 15_000);
    }

    private void webhook(String label, Automation.Action a, Map<String, String> values, byte[] picture, byte[] chart, boolean first) {
        String body = Automation.body(a, values);
        web.execute(() -> {
            String result;
            try {
                HttpURLConnection http = (HttpURLConnection) new URL(a.url.trim()).openConnection();
                http.setConnectTimeout(10_000); http.setReadTimeout(20_000); http.setRequestMethod("POST"); http.setDoOutput(true); http.setInstanceFollowRedirects(false);
                http.setRequestProperty("User-Agent", "LinkWorkshop-Automation");
                if (!a.header.trim().isEmpty()) {
                    String secret = ""; try { CredentialStore store = new CredentialStore(context); if (store.remembers("automation:" + a.secretId)) secret = store.load("automation:" + a.secretId); } catch (Exception unreadable) { }
                    http.setRequestProperty(a.header.trim(), secret);
                }
                byte[] payload; String sent;
                byte[] image = picture != null ? picture : chart;
                if (image != null && a.rawImage) {
                    // One picture as the whole body; the text goes in a header (ntfy: Message, with a file name).
                    http.setRequestProperty("Content-Type", picture != null ? "image/jpeg" : "image/png");
                    http.setRequestProperty(a.messageHeader, body.replaceAll("[\\r\\n]+", " "));
                    http.setRequestProperty("Filename", picture != null ? "picture.jpg" : "chart.png");
                    payload = image; sent = picture != null ? "picture" : "chart";
                } else if (picture != null || chart != null) {
                    String boundary = "linkworkshop" + Long.toHexString(System.nanoTime());
                    http.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                    payload = Multipart.build(boundary, body, Automation.looksLikeJson(body), picture, chart);
                    sent = "multipart" + (picture != null ? " + picture" : "") + (chart != null ? " + chart" : "");
                } else {
                    http.setRequestProperty("Content-Type", Automation.looksLikeJson(body) ? "application/json; charset=utf-8" : "text/plain; charset=utf-8");
                    payload = body.getBytes(StandardCharsets.UTF_8); sent = "text";
                }
                http.setFixedLengthStreamingMode(payload.length);
                try (OutputStream out = http.getOutputStream()) { out.write(payload); }
                int code = http.getResponseCode(); http.disconnect();
                result = "HTTP " + code + " (" + sent + ", " + Math.max(1, payload.length / 1024) + " KB)";
                if (code >= 500 && first) { Diagnostics.note(Diagnostics.AUTOMATIONS, label + ": webhook to " + Automation.shortUrl(a.url) + ": " + result + "; trying once more in 30 s"); main.postDelayed(() -> webhook(label, a, values, picture, chart, false), 30_000); return; }
            } catch (Exception failed) {
                result = failed.getClass().getSimpleName();
                if (first) { Diagnostics.note(Diagnostics.AUTOMATIONS, label + ": webhook to " + Automation.shortUrl(a.url) + " failed (" + result + "); trying once more in 30 s"); main.postDelayed(() -> webhook(label, a, values, picture, chart, false), 30_000); return; }
            }
            Diagnostics.note(Diagnostics.AUTOMATIONS, label + ": webhook to " + Automation.shortUrl(a.url) + ": " + result + (first ? "" : " (second try; not tried again)"));
        });
    }

    /** A multipart form as Discord and most services take it: the text as payload_json (JSON) or content (text), then files[0], files[1]. */
    static final class Multipart {
        static byte[] build(String boundary, String body, boolean json, byte[] picture, byte[] chart) throws IOException {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            String field = json ? "payload_json" : "content";
            part(out, boundary, "Content-Disposition: form-data; name=\"" + field + "\"\r\nContent-Type: " + (json ? "application/json" : "text/plain") + "; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
            int n = 0;
            if (picture != null) part(out, boundary, "Content-Disposition: form-data; name=\"files[" + n++ + "]\"; filename=\"picture.jpg\"\r\nContent-Type: image/jpeg", picture);
            if (chart != null) part(out, boundary, "Content-Disposition: form-data; name=\"files[" + n + "]\"; filename=\"chart.png\"\r\nContent-Type: image/png", chart);
            out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            return out.toByteArray();
        }
        private static void part(java.io.ByteArrayOutputStream out, String boundary, String headers, byte[] content) throws IOException {
            out.write(("--" + boundary + "\r\n" + headers + "\r\n\r\n").getBytes(StandardCharsets.UTF_8)); out.write(content); out.write("\r\n".getBytes(StandardCharsets.UTF_8));
        }
    }

    /** A sample event for the Test button: the running print's values when there is one. */
    static Automation.Event sample(String type, JSONObject status, String printer) {
        Automation.Event e = new Automation.Detector().outside(type.equals(Automation.ANY) ? Automation.FINISHED : type, "test", status, printer);
        if (e.file.isEmpty()) { e.file = "test-print.gcode"; e.layer = 120; e.total = 240; e.progress = 50; e.remaining = 3600; }
        return e;
    }

    void shutdown() { main.removeCallbacksAndMessages(null); web.shutdownNow(); }
}
