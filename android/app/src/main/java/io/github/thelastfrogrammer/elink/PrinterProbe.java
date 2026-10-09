package io.github.thelastfrogrammer.elink;

import java.util.*;
import java.util.concurrent.*;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Asks the printer, once each and one at a time, the read-only questions Elegoo's own printer page knows ("Get…" methods in
 * its method list), and records which ones it answers and with which field names. Never values, never an unknown method
 * number: several numbers next to these change the printer (1007 emergency stop, 1020 start, 1039 firmware update, 1047
 * delete), so nothing outside {@link #READS} is ever sent. Nothing is repeated. Independent of Android.
 */
final class PrinterProbe {
    /** Read-only methods from Elegoo's page (ElegooSlicer lan_service_web), with what each asks for. */
    static final int[] READS = {1001, 1002, 1003, 1004, 1005, 1006, 1036, 1037, 1042, 1044, 1045, 1046, 1048, 1051, 1061, 1062, 2005};
    static boolean readOnly(int method) { for (int read : READS) if (read == method) return true; return false; }
    static String name(int method) {
        switch (method) {
            case 1001: return "System info"; case 1002: return "Basic info (status)"; case 1003: return "Machine status";
            case 1004: return "Fans"; case 1005: return "Print info"; case 1006: return "Homing status";
            case 1036: return "Print history"; case 1037: return "One print's details"; case 1042: return "Camera address";
            case 1044: return "File list"; case 1045: return "File thumbnail"; case 1046: return "File details";
            case 1048: return "Storage capacity"; case 1051: return "Timelapse video"; case 1061: return "Filament info";
            case 1062: return "AI detection settings"; case 2005: return "CANVAS info";
            default: return "Method " + method;
        }
    }

    /** One way to reach the printer (local MQTT or the Elegoo cloud). Replies arrive on any thread, at most once. */
    interface Transport {
        String name();
        void send(JSONObject request, Answer answer);
    }
    interface Answer {
        /** result is the printer's "result" object (error_code included) or null when it did not answer; error then says why. */
        void reply(JSONObject result, String error);
    }
    interface Listener {
        void progress(String text);
        /** Once, with the full report (also safe to keep in diagnostics). */
        void finished(String report, List<Outcome> outcomes);
    }

    /** What the probe learnt about one method. */
    static final class Outcome {
        final int method; final String verdict, fields; final long millis;
        Outcome(int method, String verdict, String fields, long millis) { this.method = method; this.verdict = verdict; this.fields = fields; this.millis = millis; }
        boolean answered() { return verdict.startsWith("answered"); }
        String line() { return method + " " + name(method) + ": " + verdict + (millis >= 0 ? " (" + millis + " ms)" : "") + (fields.isEmpty() ? "" : " · " + fields); }
    }

    /** What the earlier replies gave us to ask the follow-up questions with; the values stay inside the probe. */
    static final class Context {
        String fileName = "", historyTask = "", timelapse = "";
    }

    private final Transport transport;
    private final ScheduledExecutorService scheduler;
    private final Listener listener;
    private final long timeoutMs, spacingMs;
    private final Context context = new Context();
    private final List<Outcome> outcomes = new ArrayList<>();
    private final Queue<Integer> remaining = new ArrayDeque<>();
    private volatile boolean cancelled;
    private boolean ended;
    private int step;

    PrinterProbe(Transport transport, ScheduledExecutorService scheduler, Listener listener, long timeoutMs, long spacingMs) {
        this.transport = transport; this.scheduler = scheduler; this.listener = listener; this.timeoutMs = timeoutMs; this.spacingMs = spacingMs;
        for (int read : READS) remaining.add(read);
    }

    void start() { scheduler.execute(this::next); }
    void cancel() { cancelled = true; scheduler.execute(() -> finish("Probe stopped before the end.")); }

    /** The request for a method, or null when an earlier reply did not give what it needs (it is then skipped). */
    static JSONObject request(int method, Context context) throws Exception {
        if (!readOnly(method)) throw new IllegalArgumentException("Not a read-only method");
        JSONObject params = new JSONObject();
        switch (method) {
            case 1037: if (context.historyTask.isEmpty()) return null; params.put("task_id", context.historyTask); break;
            case 1044: params.put("storage_media", "local").put("offset", 0).put("limit", 20); break;
            case 1045: if (context.fileName.isEmpty()) return null; params.put("storage_media", "local").put("file_name", context.fileName); break;
            case 1046: if (context.fileName.isEmpty()) return null; params.put("storage_media", "local").put("filename", context.fileName); break;
            case 1051: if (context.timelapse.isEmpty()) return null; params.put("url", context.timelapse); break;
            default: break;
        }
        return new JSONObject().put("id", 0).put("method", method).put("params", params);
    }

    private void next() {
        if (ended) return;
        if (cancelled) { finish("Probe stopped before the end."); return; }
        Integer method = remaining.poll();
        if (method == null) { finish(""); return; }
        JSONObject request;
        try { request = request(method, context); }
        catch (Exception invalid) { record(new Outcome(method, "not sent (" + invalid.getClass().getSimpleName() + ")", "", -1)); scheduler.execute(this::next); return; }
        if (request == null) { record(new Outcome(method, "skipped: " + skipReason(method), "", -1)); scheduler.execute(this::next); return; }
        int mine = ++step; long started = System.nanoTime();
        listener.progress("Asking " + name(method) + " (" + method + ")…");
        boolean[] done = {false};
        ScheduledFuture<?> timeout = scheduler.schedule(() -> {
            if (done[0] || step != mine || ended) return;
            done[0] = true; record(new Outcome(method, "no reply in " + timeoutMs / 1000 + " s", "", -1)); scheduler.schedule(this::next, spacingMs, TimeUnit.MILLISECONDS);
        }, timeoutMs, TimeUnit.MILLISECONDS);
        try {
            transport.send(request, (result, error) -> scheduler.execute(() -> {
                if (done[0] || step != mine || ended) return;
                done[0] = true; timeout.cancel(false);
                long millis = (System.nanoTime() - started) / 1_000_000;
                record(outcome(method, result, error, millis));
                if (result != null) learn(method, result);
                scheduler.schedule(this::next, spacingMs, TimeUnit.MILLISECONDS);
            }));
        } catch (Exception error) {
            done[0] = true; timeout.cancel(false);
            record(new Outcome(method, "not sent (" + error.getClass().getSimpleName() + ")", "", -1)); scheduler.execute(this::next);
        }
    }

    private static String skipReason(int method) {
        if (method == 1037) return "no print history entry to ask about";
        if (method == 1051) return "no timelapse in print history";
        return "no file on the printer to ask about";
    }

    static Outcome outcome(int method, JSONObject result, String error, long millis) {
        if (result == null) {
            java.util.regex.Matcher code = java.util.regex.Pattern.compile("\\(code (-?\\d+)\\)").matcher(String.valueOf(error));
            if (code.find()) return new Outcome(method, "refused with error code " + code.group(1), "", millis);
            return new Outcome(method, "failed: " + clean(error == null ? "no answer" : error), "", millis);
        }
        int code = result.optInt("error_code", 0);
        if (result.has("error_code") && code != 0) return new Outcome(method, "refused with error code " + code, shape(result), millis);
        return new Outcome(method, result.length() == 0 ? "answered (empty)" : "answered", shape(result), millis);
    }

    /** Remembers what follow-up questions need (a file name, a history task, a timelapse path); never logged. */
    private void learn(int method, JSONObject result) {
        if (method == 1044) {
            JSONArray files = result.optJSONArray("file_list");
            for (int i = 0; files != null && i < files.length() && context.fileName.isEmpty(); i++) {
                JSONObject file = files.optJSONObject(i); if (file == null) continue;
                String name = file.optString("filename", file.optString("file_name", file.optString("name", "")));
                try { Cc2Codec.filename(name); context.fileName = name; } catch (IllegalArgumentException notGcode) { }
            }
        }
        if (method == 1036) {
            JSONArray tasks = result.optJSONArray("history_task_list");
            for (int i = 0; tasks != null && i < tasks.length(); i++) {
                JSONObject task = tasks.optJSONObject(i); if (task == null) continue;
                String id = task.optString("task_id", "");
                if (context.historyTask.isEmpty() && id.matches("[A-Za-z0-9._-]{1,64}")) context.historyTask = id;
                String video = task.optString("time_lapse_video_url", "");
                if (context.timelapse.isEmpty() && !video.isEmpty()) try { Cc2Codec.timelapse(video); context.timelapse = video; } catch (IllegalArgumentException unusable) { }
            }
        }
    }

    private void record(Outcome outcome) { outcomes.add(outcome); }

    private void finish(String note) {
        if (ended) return;
        ended = true;
        StringBuilder report = new StringBuilder("read-only probe via ").append(transport.name()).append(": ");
        int answered = 0; for (Outcome outcome : outcomes) if (outcome.answered()) answered++;
        report.append(answered).append(" of ").append(outcomes.size()).append(" asked methods answered");
        if (!note.isEmpty()) report.append(". ").append(note);
        for (Outcome outcome : outcomes) report.append("\n  ").append(outcome.line());
        listener.finished(report.toString(), Collections.unmodifiableList(new ArrayList<>(outcomes)));
    }

    /**
     * Field names and kinds of a reply, nested two levels (arrays show their length and first item): never values. Keys that
     * look like serials or hashes are hidden, as in CloudApi.shape.
     */
    static String shape(JSONObject object) {
        StringBuilder text = new StringBuilder(); shape(object, text, 0);
        return text.length() > 900 ? text.substring(0, 900) + "…" : text.toString();
    }
    private static void shape(JSONObject object, StringBuilder text, int depth) {
        List<String> keys = new ArrayList<>();
        for (Iterator<String> it = object.keys(); it.hasNext(); ) keys.add(it.next());
        Collections.sort(keys);
        boolean first = true;
        for (String key : keys) {
            if (!first) text.append(", "); first = false;
            text.append(key.length() > 40 || key.matches(".*[0-9a-fA-F]{12,}.*") ? "(long key)" : key.replaceAll("[^A-Za-z0-9_.-]", "?"));
            Object value = object.opt(key);
            if (value instanceof JSONObject && depth < 2) { text.append('{'); shape((JSONObject) value, text, depth + 1); text.append('}'); }
            else if (value instanceof JSONArray) {
                JSONArray array = (JSONArray) value;
                text.append('[').append(array.length());
                Object item = array.length() == 0 ? null : array.opt(0);
                if (item instanceof JSONObject && depth < 2) { text.append(" × {"); shape((JSONObject) item, text, depth + 1); text.append('}'); }
                else if (item != null) text.append(" × ").append(CloudApi.kind(item));
                text.append(']');
            } else text.append(':').append(CloudApi.kind(value));
        }
    }

    /** Masks addresses in an error text; keeps it to one short line. */
    static String clean(String text) {
        String plain = String.valueOf(text).replaceAll("\\b\\d{1,3}(\\.\\d{1,3}){3}\\b", "<address>").replace('\n', ' ');
        return plain.length() > 160 ? plain.substring(0, 160) + "…" : plain;
    }

    /** Counts the printer's unasked messages by method and channel, so the report says what it sends on its own. */
    static final class Tally {
        private final Map<String, Integer> seen = new TreeMap<>();
        synchronized void saw(String channel, int method) { seen.merge(method + " on " + channel, 1, Integer::sum); }
        synchronized String summary() {
            if (seen.isEmpty()) return "none yet";
            StringBuilder text = new StringBuilder();
            for (Map.Entry<String, Integer> entry : seen.entrySet()) { if (text.length() > 0) text.append(", "); text.append(entry.getKey()).append(" ×").append(entry.getValue()); }
            return text.toString();
        }
    }
}
