package io.github.thelastfrogrammer.elink;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.JSONObject;

/**
 * Records how each print progresses: one CSV of samples plus a small JSON summary per print, in a directory on the phone.
 * Fed with every status update (local or cloud); samples at most every {@link #MIN_INTERVAL_MS} unless the layer changes.
 * Independent of Android so it can be tested on the JVM.
 */
public final class PrintRecorder {
    static final long MIN_INTERVAL_MS = 10_000;
    /** A print that has had no sample for this long is not resumed by a matching status; a new recording starts. */
    static final long RESUME_WINDOW_MS = 2 * 60 * 60 * 1000L;
    static final int KEEP = 100;
    public static final String[] COLUMNS = {"time_ms", "elapsed_s", "progress", "layer", "total_layers", "remaining_s", "z_mm",
        "nozzle_c", "nozzle_target_c", "bed_c", "bed_target_c", "chamber_c", "part_fan_pct", "aux_fan_pct", "chamber_fan_pct", "speed_mode", "sub_status", "source"};

    public static final class Recording {
        public final File csv, meta;
        public String id = "", file = "", printer = "", outcome = "printing", source = "";
        public long start, end, lastSample;
        public int samples, layers;
        Recording(File csv, File meta) { this.csv = csv; this.meta = meta; }
        JSONObject toJson() throws Exception {
            return new JSONObject().put("id", id).put("file", file).put("printer", printer).put("outcome", outcome).put("source", source)
                .put("start", start).put("end", end).put("last_sample", lastSample).put("samples", samples).put("layers", layers);
        }
        static Recording read(File meta) throws Exception {
            String base = meta.getName().replaceFirst("\\.json$", "");
            Recording recording = new Recording(new File(meta.getParentFile(), base + ".csv"), meta);
            JSONObject json = new JSONObject(new String(java.nio.file.Files.readAllBytes(meta.toPath()), StandardCharsets.UTF_8));
            recording.id = json.optString("id"); recording.file = json.optString("file"); recording.printer = json.optString("printer");
            recording.outcome = json.optString("outcome", "unknown"); recording.source = json.optString("source");
            recording.start = json.optLong("start"); recording.end = json.optLong("end"); recording.lastSample = json.optLong("last_sample");
            recording.samples = json.optInt("samples"); recording.layers = json.optInt("layers");
            return recording;
        }
        public long duration() { return Math.max(0, (end > 0 ? end : lastSample) - start); }
    }

    private final File directory;
    private Recording current;
    private int lastLayer = -1;

    public PrintRecorder(File directory) { this.directory = directory; }
    public synchronized Recording current() { return current; }

    /** Feed one full status snapshot. Returns true when a sample was written. */
    public synchronized boolean update(long now, JSONObject status, String source, String printer) {
        JSONObject machine = status.optJSONObject("machine_status"), print = status.optJSONObject("print_status");
        if (machine == null) return false;
        int state = machine.optInt("status", -1), sub = machine.optInt("sub_status", -1);
        String file = print == null ? "" : print.optString("filename", "");
        String key = print == null ? "" : print.optString("uuid", ""); if (key.isEmpty()) key = file;
        if (state != 2 || key.isEmpty()) {
            if (current != null && state == 1) finish(now, "ended");
            return false;
        }
        if (current != null && !current.id.equals(key)) finish(now, "interrupted");
        if (current == null) {
            current = resumable(key, now);
            if (current == null) current = begin(now, key, file, printer, source);
            lastLayer = -1;
        }
        int layer = print.optInt("current_layer", -1);
        boolean last = sub == 2077 || sub == 2504;
        if (now - current.lastSample < MIN_INTERVAL_MS && layer == lastLayer && !last) return false;
        append(now, status, source, sub);
        lastLayer = layer;
        if (sub == 2077) finish(now, "complete"); else if (sub == 2504) finish(now, "stopped");
        return true;
    }

    /** Recordings, newest first. Unfinished ones from an earlier app run stay listed with outcome "printing" until resumed or closed. */
    public synchronized List<Recording> list() {
        List<Recording> result = new ArrayList<>();
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".json"));
        if (files != null) for (File meta : files) try { result.add(Recording.read(meta)); } catch (Exception ignored) { }
        result.sort((a, b) -> Long.compare(b.start, a.start));
        return result;
    }

    public synchronized void delete(Recording recording) {
        if (current != null && current.meta.equals(recording.meta)) current = null;
        recording.csv.delete(); recording.meta.delete();
    }

    /** Reads a recording's samples as rows of numbers keyed by column (NaN where missing). */
    public static List<double[]> samples(Recording recording) throws IOException {
        List<double[]> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(recording.csv), StandardCharsets.UTF_8))) {
            String line = reader.readLine(); // header
            while ((line = reader.readLine()) != null) {
                String[] cells = line.split(",", -1); double[] row = new double[COLUMNS.length];
                for (int i = 0; i < COLUMNS.length; i++) {
                    String cell = i < cells.length ? cells[i] : "";
                    try { row[i] = cell.isEmpty() ? Double.NaN : "source".equals(COLUMNS[i]) ? ("cloud".equals(cell) ? 1 : 0) : Double.parseDouble(cell); }
                    catch (NumberFormatException error) { row[i] = Double.NaN; }
                }
                rows.add(row);
            }
        }
        return rows;
    }
    public static int column(String name) { return Arrays.asList(COLUMNS).indexOf(name); }

    private Recording resumable(String key, long now) {
        for (Recording recording : list())
            if (key.equals(recording.id) && "printing".equals(recording.outcome) && now - recording.lastSample < RESUME_WINDOW_MS) return recording;
        return null;
    }

    private Recording begin(long now, String key, String file, String printer, String source) {
        directory.mkdirs();
        String base = now + "-" + file.replaceAll("[^A-Za-z0-9._-]", "_").replaceFirst("(?i)\\.gcode$", "");
        if (base.length() > 80) base = base.substring(0, 80);
        Recording recording = new Recording(new File(directory, base + ".csv"), new File(directory, base + ".json"));
        recording.id = key; recording.file = file; recording.printer = printer; recording.source = source; recording.start = now;
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(recording.csv), StandardCharsets.UTF_8)) { writer.write(String.join(",", COLUMNS) + "\n"); }
        catch (IOException ignored) { }
        save(recording); prune();
        return recording;
    }

    private void append(long now, JSONObject status, String source, int sub) {
        JSONObject machine = status.optJSONObject("machine_status"), print = status.optJSONObject("print_status");
        JSONObject position = Cc2Codec.position(status);
        String[] cells = {
            String.valueOf(now), String.valueOf((now - current.start) / 1000), number(machine, "progress"), number(print, "current_layer"), number(print, "total_layer"),
            number(print, "remaining_time_sec"), number(position, "z"),
            number(status.optJSONObject("extruder"), "temperature"), number(status.optJSONObject("extruder"), "target"),
            number(status.optJSONObject("heater_bed"), "temperature"), number(status.optJSONObject("heater_bed"), "target"),
            number(status.optJSONObject("ztemperature_sensor"), "temperature"),
            fan(status, "fan"), fan(status, "aux_fan"), fan(status, "box_fan"),
            number(position, "speed_mode"), String.valueOf(sub), "cloud".equals(source) ? "cloud" : "local" };
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(current.csv, true), StandardCharsets.UTF_8)) { writer.write(String.join(",", cells) + "\n"); }
        catch (IOException ignored) { return; }
        current.samples++; current.lastSample = now;
        if (print != null) current.layers = Math.max(current.layers, print.optInt("total_layer", 0));
        save(current);
    }

    private void finish(long now, String outcome) {
        if (current == null) return;
        current.outcome = outcome; current.end = Math.max(current.lastSample, Math.min(now, current.lastSample + MIN_INTERVAL_MS * 6));
        if (current.end == 0) current.end = now;
        save(current); current = null; lastLayer = -1;
    }

    private void save(Recording recording) {
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(recording.meta), StandardCharsets.UTF_8)) { writer.write(recording.toJson().toString()); }
        catch (Exception ignored) { }
    }

    private void prune() {
        List<Recording> all = list();
        for (int i = KEEP; i < all.size(); i++) { all.get(i).csv.delete(); all.get(i).meta.delete(); }
    }

    private static String number(JSONObject object, String key) {
        if (object == null || !object.has(key)) return "";
        double value = object.optDouble(key, Double.NaN);
        if (Double.isNaN(value)) return "";
        return value == Math.rint(value) && Math.abs(value) < 1e12 ? String.valueOf((long) value) : String.format(Locale.ROOT, "%.2f", value);
    }
    /** Fan speed as a percentage of the printer's 0-255 scale. */
    private static String fan(JSONObject status, String name) {
        JSONObject fans = status.optJSONObject("fans"), fan = fans == null ? null : fans.optJSONObject(name);
        if (fan == null || !fan.has("speed")) return "";
        return String.valueOf(Math.round(fan.optDouble("speed", 0) * 100 / 255));
    }
}
