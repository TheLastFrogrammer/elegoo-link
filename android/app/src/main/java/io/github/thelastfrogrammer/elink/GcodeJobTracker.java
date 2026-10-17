package io.github.thelastfrogrammer.elink;

import java.util.Objects;
import java.util.UUID;
import org.json.JSONObject;

/** Short-lived observed job scope. A filename/firmware UUID alone never restores an exact-file association. */
final class GcodeJobTracker {
    static final long GAP_MS = 45_000;
    static final class Job {
        final String printer, filename, token;
        Job(String printer, String filename) { this.printer = printer; this.filename = filename; token = UUID.randomUUID().toString(); }
    }
    private Job current;
    private String route = "", reportedId = "";
    private long lastAt, duration = -1;
    private int progress = -1;
    synchronized Job update(String serial, String connection, JSONObject status, long now) {
        JSONObject machine = status == null ? null : status.optJSONObject("machine_status"), print = status == null ? null : status.optJSONObject("print_status");
        String printer = GcodeLibrary.printerId(serial), filename = print == null ? "" : print.optString("filename", "").trim();
        if (printer.isEmpty() || machine == null || machine.optInt("status", -1) != 2 || filename.length() > 512 || GcodeLibrary.baseName(filename) == null) { clear(); return null; }
        String id = print.optString("print_task_id", print.optString("task_id", print.optString("uuid", "")));
        long nextDuration = print.optLong("print_duration", -1); int nextProgress = machine.optInt("progress", -1);
        boolean restarted = duration >= 0 && nextDuration >= 0 && nextDuration < duration
            || progress > 1 && nextProgress >= 0 && nextProgress + 1 < progress;
        if (current == null || !current.printer.equals(printer) || !current.filename.equals(filename)
                || !Objects.equals(route, connection) || !reportedId.equals(id) || now - lastAt >= GAP_MS || restarted) current = new Job(printer, filename);
        route = connection; reportedId = id; lastAt = now; duration = nextDuration; progress = nextProgress;
        return current;
    }
    synchronized Job current(long now) { if (current != null && now - lastAt >= GAP_MS) clear(); return current; }
    synchronized void clear() { current = null; route = ""; reportedId = ""; lastAt = 0; duration = -1; progress = -1; }
}
