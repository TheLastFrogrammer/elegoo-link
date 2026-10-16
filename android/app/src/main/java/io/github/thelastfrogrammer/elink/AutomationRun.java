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
        void notifyPerson(int id, String title, String text);
        /** Tells Home Assistant (no-op when the link is off); returns what happened for the log. */
        String homeAssistantEvent(JSONObject event);
    }

    private final Context context;
    private final Host host;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService web = Executors.newSingleThreadExecutor();
    final Automation.Detector local = new Automation.Detector(), cloud = new Automation.Detector();
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
    }
    /** An event the service noticed itself (guardian, print watch, lost contact). */
    void outside(Automation.Detector detector, String type, String detail, JSONObject status, String printer) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post(() -> outside(detector, type, detail, status, printer)); return; }
        fire(detector.outside(type, detail, status, printer)); }

    void fire(Automation.Event event) {
        if (!event.type.equals(Automation.LAYER)) Diagnostics.note(Diagnostics.AUTOMATIONS, "event: " + event.type + (event.layer >= 0 ? " at layer " + event.layer : ""));
        java.util.Calendar now = java.util.Calendar.getInstance();
        int minute = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE);
        Automation.ThisPrint print = thisPrint(context);
        for (Automation.Rule rule : current()) {
            if (!Automation.matches(rule, event, minute, print)) continue;
            if (rule.delayMinutes > 0) {
                Diagnostics.note(Diagnostics.AUTOMATIONS, "\"" + rule.name + "\" runs in " + rule.delayMinutes + " min (" + event.type + ")");
                main.postDelayed(() -> run(rule, event, false), rule.delayMinutes * 60_000L);
            } else run(rule, event, false);
        }
    }

    /** Runs a rule's actions once (main thread). `test`: from the workshop's Test button, with a sample event. */
    void run(Automation.Rule rule, Automation.Event event, boolean test) {
        Map<String, String> values = event.values(System.currentTimeMillis(), java.util.TimeZone.getDefault());
        String label = "\"" + rule.name + "\"" + (test ? " (test)" : "");
        for (Automation.Action a : rule.actions) {
            try {
                switch (a.kind) {
                    case Automation.NOTIFY:
                        host.notifyPerson(100 + Math.abs(rule.id.hashCode() % 1000), Automation.fill(a.title.isEmpty() ? "{event_name}" : a.title, values, false), Automation.fill(a.text.isEmpty() ? "{file}" : a.text, values, false));
                        Diagnostics.note(Diagnostics.AUTOMATIONS, label + ": notification shown"); break;
                    case Automation.LIGHT: light(label, a.on); break;
                    case Automation.WEBHOOK: webhook(label, a, values, true); break;
                    case Automation.HA_EVENT: {
                        JSONObject o = new JSONObject().put("event_type", event.type);
                        for (Map.Entry<String, String> v : values.entrySet()) if (!v.getKey().equals("event")) o.put(v.getKey(), v.getValue());
                        Diagnostics.note(Diagnostics.AUTOMATIONS, label + ": Home Assistant " + host.homeAssistantEvent(o)); break;
                    }
                    case Automation.BROADCAST: {
                        Intent intent = new Intent(BROADCAST_ACTION);
                        for (Map.Entry<String, String> v : values.entrySet()) intent.putExtra(v.getKey(), v.getValue());
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

    private void webhook(String label, Automation.Action a, Map<String, String> values, boolean first) {
        String body = Automation.body(a, values);
        web.execute(() -> {
            String result;
            try {
                HttpURLConnection http = (HttpURLConnection) new URL(a.url.trim()).openConnection();
                http.setConnectTimeout(10_000); http.setReadTimeout(10_000); http.setRequestMethod("POST"); http.setDoOutput(true); http.setInstanceFollowRedirects(false);
                http.setRequestProperty("Content-Type", Automation.looksLikeJson(body) ? "application/json; charset=utf-8" : "text/plain; charset=utf-8");
                http.setRequestProperty("User-Agent", "LinkWorkshop-Automation");
                if (!a.header.trim().isEmpty()) {
                    String secret = ""; try { CredentialStore store = new CredentialStore(context); if (store.remembers("automation:" + a.secretId)) secret = store.load("automation:" + a.secretId); } catch (Exception unreadable) { }
                    http.setRequestProperty(a.header.trim(), secret);
                }
                try (OutputStream out = http.getOutputStream()) { out.write(body.getBytes(StandardCharsets.UTF_8)); }
                int code = http.getResponseCode(); http.disconnect();
                result = "HTTP " + code;
                if (code >= 500 && first) { Diagnostics.note(Diagnostics.AUTOMATIONS, label + ": webhook to " + Automation.shortUrl(a.url) + ": " + result + "; trying once more in 30 s"); main.postDelayed(() -> webhook(label, a, values, false), 30_000); return; }
            } catch (Exception failed) {
                result = failed.getClass().getSimpleName();
                if (first) { Diagnostics.note(Diagnostics.AUTOMATIONS, label + ": webhook to " + Automation.shortUrl(a.url) + " failed (" + result + "); trying once more in 30 s"); main.postDelayed(() -> webhook(label, a, values, false), 30_000); return; }
            }
            Diagnostics.note(Diagnostics.AUTOMATIONS, label + ": webhook to " + Automation.shortUrl(a.url) + ": " + result + (first ? "" : " (second try; not tried again)"));
        });
    }

    /** A sample event for the Test button: the running print's values when there is one. */
    static Automation.Event sample(String type, JSONObject status, String printer) {
        Automation.Event e = new Automation.Detector().outside(type.equals(Automation.ANY) ? Automation.FINISHED : type, "test", status, printer);
        if (e.file.isEmpty()) { e.file = "test-print.gcode"; e.layer = 120; e.total = 240; e.progress = 50; e.remaining = 3600; }
        return e;
    }

    void shutdown() { main.removeCallbacksAndMessages(null); web.shutdownNow(); }
}
