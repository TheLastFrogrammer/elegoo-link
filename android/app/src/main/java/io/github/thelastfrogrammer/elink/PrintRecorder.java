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
        "nozzle_c", "nozzle_target_c", "bed_c", "bed_target_c", "chamber_c", "part_fan_pct", "aux_fan_pct", "chamber_fan_pct", "speed_mode", "sub_status", "source",
        "tray", "tray_rgb",   // the active CANVAS tray (1 = unit 0 tray 0) and its filament colour as 0xRRGGBB, when reported
        "extruded_mm",        // filament the G-code has fed so far (commanded, from the extruder position; not measured)
        "speed",              // the speed the printer reports for its current move, as it reports it
        "filament_detected",  // the filament sensor: 1 filament there, 0 none (runout), blank when not reported
        "x_mm", "y_mm"};      // the head's position as the printer reports it (with z_mm)

    public static final class Recording {
        public final File csv, meta;
        public String id = "", file = "", printer = "", outcome = "printing", source = "";
        /** Which printer (a one-way digest of its serial, "" when unknown): two printers can run files of the same name. */
        public String printerId = "";
        public long start, end, lastSample;
        /** Time the print has been recorded, from a monotonic clock (ms); 0 in recordings made before it existed. */
        public long elapsed;
        long monoStart;
        public int samples, layers;
        /** Camera pictures every this many minutes for this print (0 = none), and how many were taken. */
        public int pictureMinutes, pictures;
        /** Or a picture every this many layers (0 = by time): for timelapses where the print grows evenly between pictures. */
        public int pictureLayers;
        /** Smooth timelapse (the file parks the head at each layer change): the corner, or NaN; pictures are taken while the head is there. */
        public double parkX = Double.NaN, parkY = Double.NaN;
        public boolean smooth() { return !Double.isNaN(parkX) && !Double.isNaN(parkY); }
        /** Pictures when the head is far from the camera on its layer (every pictureLayers layers), not at a fixed time. */
        public boolean pictureFar;
        /** A smooth timelapse that also keeps a second set, taken with the head far from the camera, to compare the two. */
        public boolean compare;
        /** Pictures in the second (far from the camera) set. */
        public int farPictures;
        /**
         * The print guardian's checks, one entry per measure per picture: {printer layer, measure (GUARDIAN_*), value, limit,
         * over the limit 0/1, raised an alarm 0/1}; values and limits as shares (0-1). For the recording's guardian chart.
         */
        public final List<double[]> guardianChecks = new ArrayList<>();
        /** Measured while the print ran: status rate, parking, layer numbers, head position, picture timing (PrintChecks). */
        public PrintChecks checks = new PrintChecks();
        /** The timelapse video made when the print ended (in its picture folder), and the side-by-side one for comparing. */
        private org.json.JSONArray guardianJson() throws org.json.JSONException {
            org.json.JSONArray all = new org.json.JSONArray();
            for (double[] e : guardianChecks) { org.json.JSONArray one = new org.json.JSONArray(); for (double v : e) one.put(Double.isNaN(v) ? null : v); all.put(one); }
            return all;
        }
        public File video() { return new File(pictureFolder(), "timelapse.mp4"); }
        public File sideBySide() { return new File(pictureFolder(), "side-by-side.mp4"); }
        /** The folder of the second set, inside the picture folder. */
        public File farFolder() { return new File(pictureFolder(), "far"); }
        /** Pictures follow the head (parked, or far from the camera) rather than the clock. */
        public boolean followsHead() { return smooth() || pictureFar && pictureLayers > 0; }
        /** Filament fed so far (mm), summed from the extruder position (resets skipped), and the last position seen. */
        public double extrudedMm; double lastE = Double.NaN;
        /** The printer's own print time (s, print_duration) as last reported, or -1; progress when the recording began (-1 unknown). */
        public long printDuration = -1; public int startProgress = -1;
        /** The printer's estimate of the whole print (s) when it reached 10 %, or 0: compared with the real time when it ends. */
        public long estimate;
        /** The person's notes on the print (PrintJournal). */
        public PrintJournal journal = new PrintJournal();
        /** "Every 5 min", "Every layer", "Every 2 layers" or "No pictures". */
        public String pictureWords() { return smooth() ? (pictureLayers > 1 ? "Every " + pictureLayers + " layers, head parked" : "Every layer, head parked") + (compare ? ", and a set with the head far from the camera" : "")
            : pictureFar && pictureLayers > 0 ? (pictureLayers == 1 ? "Every layer" : "Every " + pictureLayers + " layers") + ", head far from the camera" : pictureLayers > 0 ? (pictureLayers == 1 ? "Every layer" : "Every " + pictureLayers + " layers") : pictureMinutes == 0 ? "No pictures" : pictureMinutes == 1 ? "Every minute" : "Every " + pictureMinutes + " minutes"; }
        Recording(File csv, File meta) { this.csv = csv; this.meta = meta; }
        JSONObject toJson() throws Exception {
            return new JSONObject().put("id", id).put("file", file).put("printer", printer).put("printer_id", printerId).put("outcome", outcome).put("source", source)
                .put("start", start).put("end", end).put("last_sample", lastSample).put("elapsed", elapsed).put("samples", samples).put("layers", layers)
                .put("picture_minutes", pictureMinutes).put("picture_layers", pictureLayers).put("pictures", pictures)
                .put("park_x", smooth() ? parkX : null).put("park_y", smooth() ? parkY : null).put("picture_far", pictureFar)
                .put("compare", compare && smooth()).put("far_pictures", farPictures).put("checks", checks.toJson())
                .put("guardian_checks", guardianJson())
                .put("extruded_mm", extrudedMm).put("last_e", Double.isNaN(lastE) ? null : lastE).put("print_duration", printDuration).put("start_progress", startProgress).put("estimate", estimate)
                .put("journal", journal.empty() ? null : journal.toJson()).put("spools", spools.length() == 0 ? null : spools);
        }
        static Recording read(File meta) throws Exception {
            String base = meta.getName().replaceFirst("\\.json$", "");
            Recording recording = new Recording(new File(meta.getParentFile(), base + ".csv"), meta);
            JSONObject json = new JSONObject(new String(java.nio.file.Files.readAllBytes(meta.toPath()), StandardCharsets.UTF_8));
            recording.id = json.optString("id"); recording.file = json.optString("file"); recording.printer = json.optString("printer"); recording.printerId = json.optString("printer_id");
            recording.outcome = json.optString("outcome", "unknown"); recording.source = json.optString("source");
            recording.start = json.optLong("start"); recording.end = json.optLong("end"); recording.lastSample = json.optLong("last_sample"); recording.elapsed = json.optLong("elapsed");
            recording.samples = json.optInt("samples"); recording.layers = json.optInt("layers");
            recording.pictureMinutes = json.optInt("picture_minutes"); recording.pictureLayers = json.optInt("picture_layers"); recording.pictures = json.optInt("pictures");
            recording.parkX = json.optDouble("park_x", Double.NaN); recording.parkY = json.optDouble("park_y", Double.NaN); recording.pictureFar = json.optBoolean("picture_far");
            recording.compare = json.optBoolean("compare"); recording.farPictures = json.optInt("far_pictures");
            recording.checks = PrintChecks.fromJson(json.optJSONObject("checks"));
            org.json.JSONArray g = json.optJSONArray("guardian_checks");
            for (int i = 0; g != null && i < g.length(); i++) {
                org.json.JSONArray one = g.optJSONArray(i); if (one == null || one.length() < 6) continue;
                double[] entry = new double[6]; for (int k = 0; k < 6; k++) entry[k] = one.optDouble(k, Double.NaN);
                recording.guardianChecks.add(entry);
            }
            recording.extrudedMm = json.optDouble("extruded_mm", 0); recording.lastE = json.optDouble("last_e", Double.NaN);
            recording.printDuration = json.optLong("print_duration", -1); recording.startProgress = json.optInt("start_progress", -1); recording.estimate = json.optLong("estimate", 0);
            recording.journal = PrintJournal.fromJson(json.optJSONObject("journal"));
            org.json.JSONArray used = json.optJSONArray("spools"); if (used != null) recording.spools = used;
            return recording;
        }
        /** The folder of this recording's camera pictures, named by elapsed seconds (e.g. 0600.jpg). */
        public File pictureFolder() { return new File(csv.getParentFile(), csv.getName().replaceFirst("\\.csv$", "") + "-pictures"); }
        /**
         * The spools that printed it, noted when it ended: {slot, id, material, dried, opened} per CANVAS tray it used (see
         * SpoolStore), so drying and the spool stay with the print after the spool is used up or moved.
         */
        public org.json.JSONArray spools = new org.json.JSONArray();
        public long duration() { return elapsed > 0 ? elapsed : Math.max(0, (end > 0 ? end : lastSample) - start); }
    }

    private final File directory;
    private Recording current;
    /** The print last closed as complete or stopped, until the printer goes idle. */
    private String endedKey = "", endedFile = "", endedPrinter = "";
    private int lastLayer = -1;
    private long sampledMono = Long.MIN_VALUE / 2, picturedMono = Long.MIN_VALUE / 2;
    private JSONObject canvas;
    /** Minutes between pictures for prints that start from now (0 = none); a print's own setting can differ. */
    private int defaultPictureMinutes, defaultPictureLayers;
    /** The layer of the running print's last picture (-1 none yet), for pictures every N layers. */
    private int picturedLayer = -1;

    public PrintRecorder(File directory) { this.directory = directory; }
    /** The recorder the printer service runs, so a journal saved from a screen reaches the running print's copy. */
    static volatile PrintRecorder active;

    /**
     * Saves a recording's journal. The running print's recording is changed in the recorder (which rewrites its file as it
     * samples), any other recording in its file.
     */
    static void saveJournal(File meta, PrintJournal journal) throws Exception { rewrite(meta, r -> r.journal = journal, "could not save the journal"); }
    /** Notes the spools that printed a recording (after it ended). */
    static void saveSpools(File meta, org.json.JSONArray spools) throws Exception { rewrite(meta, r -> r.spools = spools, "could not note the spools"); }
    private static void rewrite(File meta, java.util.function.Consumer<Recording> change, String failure) throws Exception {
        PrintRecorder recorder = active;
        if (recorder != null) {
            synchronized (recorder) { if (recorder.current != null && recorder.current.meta.equals(meta)) { change.accept(recorder.current); recorder.save(recorder.current); return; } }
            // A print that just ended still has its last write queued: it lands first, so it is neither read half-written
            // here nor written over this change afterwards (the spools are noted the moment a print ends).
            recorder.flush();
        }
        synchronized (PrintRecorder.class) {
            Recording recording = Recording.read(meta); change.accept(recording);
            File temp = new File(meta.getPath() + ".tmp");
            try (Writer writer = new OutputStreamWriter(new FileOutputStream(temp), StandardCharsets.UTF_8)) { writer.write(recording.toJson().toString()); }
            if (!temp.renameTo(meta)) throw new IOException(failure);
        }
    }
    /** Notes the guardian's alarm and the person's answer in the running print's journal. */
    public synchronized void noteGuardian(boolean fine, String said, long now) {
        if (current == null) return;
        try { if (current.journal.guardian.size() < 50) current.journal.guardian.add(new JSONObject().put("at", now).put("fine", fine).put("said", Automation.cap(said, 300))); save(current); }
        catch (Exception ignored) { }
    }
    /** The recording of the print that ended last (complete or stopped), or null; kept across app restarts. */
    public File lastFinished() {
        flush();
        File note = new File(directory, "last-finished.txt");
        try { String path = new String(java.nio.file.Files.readAllBytes(note.toPath()), StandardCharsets.UTF_8).trim(); File meta = new File(directory, path); return meta.isFile() ? meta : null; }
        catch (IOException none) { return null; }
    }
    public synchronized Recording current() { return current; }
    /** Seconds the running print has been going, on the monotonic clock `mono` (as passed to update), or -1 with no print. */
    public synchronized long elapsedSeconds(long mono) { return current == null ? -1 : Math.max(0, mono - current.monoStart) / 1000; }
    /** The latest CANVAS report, for the active tray and its colour in each sample. */
    public synchronized void setCanvas(JSONObject value) { canvas = value; }
    public synchronized void setDefaultPictureMinutes(int minutes) { defaultPictureMinutes = Math.max(0, Math.min(120, minutes)); }
    /** Pictures every N layers for prints that start from now (0 = by time, as setDefaultPictureMinutes says). */
    public synchronized void setDefaultPictureLayers(int layers) { defaultPictureLayers = Math.max(0, Math.min(50, layers)); }
    /** New prints take their every-N-layers pictures when the head is far from the camera (the picture camera's own setting). */
    private boolean defaultPictureFar;
    public synchronized void setDefaultPictureFar(boolean far) { defaultPictureFar = far; }
    /** Changes the running print's pictures: every N layers, with the head far from the camera or at any moment of the layer. */
    public synchronized void setPictureLayers(int layers, boolean far) { if (current != null) { current.pictureLayers = Math.max(0, Math.min(50, layers)); current.pictureFar = far && layers > 0; save(current); } }
    /** Changes the running print to pictures every N layers (0 goes back to its time interval). */
    public synchronized void setPictureLayers(int layers) { if (current != null) { current.pictureLayers = Math.max(0, Math.min(50, layers)); save(current); } }
    /** Changes the running print's picture interval (0 stops them). */
    public synchronized void setPictureMinutes(int minutes) { if (current != null) { current.pictureMinutes = Math.max(0, Math.min(120, minutes)); current.pictureLayers = 0; current.pictureFar = false; save(current); } }
    /** Whether a camera picture is due for the running print (`mono`: the monotonic clock). */
    /** The running print's file parks the head at this corner each layer (from its slice): pictures follow the head. */
    public synchronized void setPark(double x, double y) { setPark(x, y, false); }
    /** As above; `compare` also keeps a second set of pictures with the head far from the camera, one per layer, apart from the parked ones. */
    public synchronized void setPark(double x, double y, boolean compare) { if (current != null && !current.smooth()) { current.parkX = x; current.parkY = y; current.compare = compare; save(current); } }
    /** Set while the head is parked for a layer that has no picture yet: the next picture offered is kept. */
    private boolean parkedArmed, farArmed;
    /** What the last picture asked for was for (ASKED_PARKED or ASKED_FAR, 0 none) and when (ms, nanoTime): the picture that arrives goes to that set. */
    private int asked; private long askedAt;
    static final int ASKED_PARKED = 1, ASKED_FAR = 2;
    /** How long a parked picture is waited for before a far one may be asked (comparing: the two sets never swap pictures). */
    static final long PARKED_WAIT_MS = 15_000;
    /** The layer of the second set's last picture (-1 none yet). */
    private int farPicturedLayer = -1;
    private static long clockMs() { return System.nanoTime() / 1_000_000; }
    /**
     * Smooth timelapse: the head's reported position. At the park corner on a layer without a picture, a picture becomes due
     * (true: ask the camera now); away from it, nothing is due, so a picture never shows the head over the print.
     */
    public synchronized boolean headAt(double x, double y) {
        if (current == null || !current.smooth()) return false;
        boolean there = SmoothTimelapse.parked(x, y, current.parkX, current.parkY);
        int every = Math.max(1, current.pictureLayers);
        boolean wanted = there && lastLayer >= 1 && (picturedLayer < 0 || lastLayer - picturedLayer >= every);
        boolean ask = wanted && !parkedArmed; parkedArmed = wanted;
        if (ask) { asked = ASKED_PARKED; askedAt = clockMs(); }
        return ask;
    }
    /**
     * Far from the camera: how far the head got from the camera (mm, in the bed's plane) on this layer and the one before.
     * A layer's picture is due once the head is nearly as far as it got on the previous layer (they are much alike), and is
     * called off if the head comes back toward the camera before a picture arrives.
     */
    private double farThisLayer, farLastLayer; private int farLayer = -1;
    static final double FAR_SHARE = 0.85, FAR_GIVE_UP = 0.7, FILE_BAND = 0.8, FILE_GIVE_UP = 0.6;
    /** The running print's own reach per layer from its G-code (for the picture camera's position), when the phone has the file. */
    private LayerReach reach;
    public synchronized void setReach(LayerReach reach) { this.reach = reach; }
    public synchronized boolean hasReach() { return reach != null; }
    /** The guardian's measures, as kept in Recording.guardianChecks. */
    static final int GUARDIAN_OFF_PRINT = 0, GUARDIAN_COVERAGE = 1, GUARDIAN_SPREAD = 2, GUARDIAN_SUDDEN = 3;
    static final String[] GUARDIAN_NAMES = {"Filament off the print", "Print area covered", "Change away from the print", "Sudden change"};
    /** Keeps one guardian check with the print it was made for (`meta`), while that print is being recorded. */
    public synchronized void noteGuardianCheck(File meta, int layer, int measure, double value, double limit, boolean over, boolean alarm) {
        if (current == null || !current.meta.equals(meta) || Double.isNaN(value) || current.guardianChecks.size() >= 4000) return;
        current.guardianChecks.add(new double[] {layer, measure, value, limit, over ? 1 : 0, alarm ? 1 : 0});
        save(current);
    }
    /** The running print's moves reported after a stop, between two times (monotonic ms), for the camera clock. */
    public synchronized long[] departures(long from, long to) {
        if (current == null) return new long[0];
        List<Long> found = new ArrayList<>(); for (long d : current.checks.departures) if (d >= from && d <= to) found.add(d);
        long[] out = new long[found.size()]; for (int i = 0; i < out.length; i++) out[i] = found.get(i); return out;
    }
    /** The camera clock's answer for the print kept in `meta` (still running): the measured delay, or why there is none. */
    public synchronized void setCameraDelay(File meta, CameraClock.Result result, String problem) {
        if (current == null || !current.meta.equals(meta)) return;   // the print's own file: its id can change once its uuid is known
        if (result != null) { current.checks.cameraDelayMs = result.delayMs; current.checks.cameraMoves = result.moves; current.checks.cameraSpreadMs = result.spreadMs; current.checks.cameraProblem = ""; }
        else current.checks.cameraProblem = problem;
        save(current);
    }
    public synchronized boolean headFrom(double x, double y, double cameraX, double cameraY) {
        // A smooth timelapse uses this as its fallback: a layer whose parked moment was missed gets its picture with the head far away.
        if (current == null || !(current.smooth() || current.pictureFar && current.pictureLayers > 0) || Double.isNaN(x) || Double.isNaN(y)) return false;
        if (lastLayer != farLayer) { if (farLayer >= 0) farLastLayer = farThisLayer; farThisLayer = 0; farLayer = lastLayer; farArmed = false; }
        // The parked head is not printing: it says nothing about how far the layer reaches, and is the other set's moment.
        if (current.smooth() && SmoothTimelapse.parked(x, y, current.parkX, current.parkY)) return false;
        if (SelfTest.is(current.file) && SelfTest.atProbe(x, y)) return false;   // the self-test's probe stops are neither
        double d = Math.hypot(x - cameraX, y - cameraY);
        farThisLayer = Math.max(farThisLayer, d);
        boolean comparing = current.compare && current.smooth();
        int last = comparing ? farPicturedLayer : picturedLayer;
        boolean layerWanted = lastLayer >= 1 && (last < 0 || lastLayer - last >= Math.max(1, current.pictureLayers));
        if (!layerWanted) return false;
        // Comparing: while a parked picture is on its way, a far one waits, so each lands in its own set.
        if (comparing && asked == ASKED_PARKED && clockMs() - askedAt < PARKED_WAIT_MS) return false;
        // From the file: the far part of this very layer (its farthest 20 %, or the last 1.5 mm of a layer with little depth).
        // Without it: nearly as far as the head got on the layer before.
        float[] span = reach == null ? null : reach.forPrinterLayer(lastLayer);
        double due, giveUp;
        if (span != null) {
            double depth = span[1] - span[0];
            due = depth < 7.5 ? span[1] - 1.5 : span[0] + FILE_BAND * depth;
            giveUp = depth < 7.5 ? span[1] - 4 : span[0] + FILE_GIVE_UP * depth;
        } else if (farLastLayer > 0) { due = FAR_SHARE * farLastLayer; giveUp = FAR_GIVE_UP * farLastLayer; }
        else return false;
        if (farArmed && d < giveUp) { farArmed = false; return false; }
        boolean ask = !farArmed && d >= due; if (ask) { farArmed = true; asked = ASKED_FAR; askedAt = clockMs(); }
        return ask;
    }
    public synchronized boolean pictureDue(long mono) {
        if (current == null) return false;
        if (current.followsHead()) return parkedArmed || farArmed;
        if (current.pictureLayers > 0) return lastLayer >= 1 && (picturedLayer < 0 || lastLayer - picturedLayer >= current.pictureLayers);
        return current.pictureMinutes > 0 && mono - picturedMono >= current.pictureMinutes * 60_000L;
    }
    /** Keeps a JPEG camera picture with the running print, named by its elapsed time; false when there is no print to keep it with. */
    public synchronized boolean savePicture(byte[] jpeg, long mono) { return savePicture(jpeg, mono, "camera", PRINTER_CAMERA); }
    /** The printer's own camera's id in `savePicture`; an added camera is "added:" and its id. */
    static final String PRINTER_CAMERA = "printer";
    /** As above, naming where the picture came from for the camera log (the first picture of a print, every tenth, and every failure). */
    /** The head's last reported position {x, y, z, layer} and when it came (monotonic ms), for the pictures' positions. */
    private double[] head; private long headMono;
    /** Each picture's head position, in its folder: picture, s into the print, x, y, z (mm), layer, age of the position (s). */
    static final String POSITIONS = "positions.csv";
    public synchronized boolean savePicture(byte[] jpeg, long mono, String source, String cameraId) {
        if (current == null) { Diagnostics.note(Diagnostics.CAMERA, "picture from " + source + " not kept: no print is being recorded"); return false; }
        if (jpeg == null || jpeg.length < 100) { Diagnostics.note(Diagnostics.CAMERA, "picture from " + source + " not kept: empty picture (" + (jpeg == null ? 0 : jpeg.length) + " bytes)"); return false; }
        // Comparing, a picture asked for with the head far away goes to the second set (and one that arrives unasked, to the set that is due).
        boolean toFar = current.compare && current.smooth() && (asked == ASKED_FAR || asked == 0 && farArmed && !parkedArmed);
        File folder = toFar ? current.farFolder() : current.pictureFolder(); folder.mkdirs();
        long elapsed = Math.max(0, mono - current.monoStart) / 1000;
        try (OutputStream out = new FileOutputStream(new File(folder, String.format(Locale.ROOT, "%06d.jpg", elapsed)))) { out.write(jpeg); }
        catch (IOException failed) { Diagnostics.note(Diagnostics.CAMERA, "picture from " + source + " not kept: " + failed.getClass().getSimpleName()); return false; }
        current.checks.picture(toFar ? 1 : 0, asked == 0 ? -1 : clockMs() - askedAt);
        if (head != null) {
            String row = String.format(Locale.ROOT, "%06d.jpg,%d,%.2f,%.2f,%.3f,%d,%.1f\n", elapsed, elapsed, head[0], head[1], head[2], (int) head[3], Math.max(0, mono - headMono) / 1000.0);
            try (Writer out = new OutputStreamWriter(new FileOutputStream(new File(folder, POSITIONS), true), StandardCharsets.UTF_8)) { out.write(row); } catch (IOException ignored) { }
        }
        boolean parkedPicture = current.smooth() && (asked == ASKED_PARKED || asked == 0 && parkedArmed);
        asked = 0; picturedMono = mono;
        if (toFar) {
            farPicturedLayer = lastLayer; farArmed = false; current.farPictures++; save(current);
            if (current.farPictures == 1 || current.farPictures % 10 == 0) Diagnostics.note(Diagnostics.CAMERA, "far picture " + current.farPictures + " of this print kept from " + source);
            return true;   // the second set is for comparing only: the guardian and the previews follow the main set
        }
        picturedLayer = lastLayer; parkedArmed = false; if (!current.compare) farArmed = false; current.pictures++; save(current);
        if (current.pictures == 1 || current.pictures % 10 == 0) Diagnostics.note(Diagnostics.CAMERA, "picture " + current.pictures + " of this print kept from " + source + " (" + jpeg.length / 1024 + " KB)");
        PictureKept told = pictureKept;
        if (told != null) { String rgb = activeTray()[1]; told.kept(current, jpeg, lastLayer, rgb.isEmpty() ? -1 : Integer.parseInt(rgb), cameraId, parkedPicture); }
        return true;
    }
    /**
     * Told about each kept picture with the printer's layer then and the active tray's colour (-1 when unknown), e.g. for the
     * print guardian; `parked`: taken with the head parked (smooth timelapse). Called with the recorder locked: hand the work off.
     */
    interface PictureKept { void kept(Recording recording, byte[] jpeg, int layer, int trayRgb, String cameraId, boolean parked); }
    volatile PictureKept pictureKept;
    /** Told when a print's recording ends complete or stopped (not when it was only interrupted). Called with the recorder locked: hand the work off. */
    interface PrintEnded { void ended(Recording recording); }
    volatile PrintEnded printEnded;
    /** Seconds since the running print's last picture (monotonic `mono`), or -1 when none yet / no print. */
    public synchronized long secondsSincePicture(long mono) { return current == null || current.pictures == 0 || picturedMono < 0 ? -1 : Math.max(0, mono - picturedMono) / 1000; }
    /** A recording's pictures, oldest first, each with its elapsed seconds. */
    public static List<File> pictures(Recording recording) {
        File[] files = recording.pictureFolder().listFiles((dir, name) -> name.matches("\\d+\\.jpg"));
        List<File> list = files == null ? new ArrayList<>() : new ArrayList<>(Arrays.asList(files));
        list.sort(Comparator.comparing(File::getName)); return list;
    }

    /**
     * Where the head was for each picture in a folder, by picture name: {s into the print, x, y, z (mm), layer, age of the
     * position (s)}. The position is the last the printer reported before the picture was kept. Empty when none were noted.
     */
    public static Map<String, double[]> picturePositions(File folder) {
        Map<String, double[]> out = new LinkedHashMap<>();
        File file = new File(folder, POSITIONS);
        if (!file.isFile()) return out;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] cells = line.split(",", -1); if (cells.length < 7) continue;
                try { double[] v = new double[6]; for (int i = 0; i < 6; i++) v[i] = Double.parseDouble(cells[i + 1]); out.put(cells[0], v); }
                catch (NumberFormatException damaged) { }
            }
        } catch (IOException unreadable) { }
        return out;
    }
    /** A recording's second set (head far from the camera, when comparing), oldest first. */
    public static List<File> farPictures(Recording recording) {
        File[] files = recording.farFolder().listFiles((dir, name) -> name.matches("\\d+\\.jpg"));
        List<File> list = files == null ? new ArrayList<>() : new ArrayList<>(Arrays.asList(files));
        list.sort(Comparator.comparing(File::getName)); return list;
    }

    /** Wall time only: for callers (and tests) without a separate monotonic clock. */
    public synchronized boolean update(long now, JSONObject status, String source, String printer) { return update(now, now, status, source, printer); }

    /**
     * Feed one full status snapshot. Returns true when a sample was written. `now` is wall-clock time and is only written down as
     * labels (sample times, start, end); throttling and elapsed time use `mono`, a clock that never steps (SystemClock.elapsedRealtime),
     * so a clock change cannot stop the recording or stretch a print.
     */
    public synchronized boolean update(long now, long mono, JSONObject status, String source, String printer) { return update(now, mono, status, source, printer, ""); }
    /** As above, for the printer with this serial (kept only as a digest): a print is resumed only on the printer that ran it. */
    public synchronized boolean update(long now, long mono, JSONObject status, String source, String printer, String serial) {
        String printerId = digest(serial);
        JSONObject machine = status.optJSONObject("machine_status"), print = status.optJSONObject("print_status");
        if (machine == null) return false;
        int state = machine.optInt("status", -1), sub = machine.optInt("sub_status", -1);
        String file = print == null ? "" : print.optString("filename", "");
        String key = print == null ? "" : print.optString("uuid", ""); if (key.isEmpty()) key = file;
        boolean last = sub == 2077 || sub == 2504;
        // Another printer now: the print being recorded stays open (it may be resumed when its printer is back).
        if (current != null && !samePrinter(printerId, current.printerId)) { save(current); current = null; lastLayer = -1; }
        if (!samePrinter(printerId, endedPrinter)) { endedKey = ""; endedFile = ""; }
        if (state != 2 || key.isEmpty()) {
            if (current != null && state == 1) finish(now, mono, "ended");
            if (state == 1) { endedKey = ""; endedFile = ""; }
            return false;
        }
        // The printer keeps reporting a finished print until it goes idle: those reports belong to the recording just closed.
        if (current == null && last && samePrint(key, file, endedKey, endedFile)) return false;
        if (current != null && samePrint(key, file, current.id, current.file)) {
            // Named with its uuid now (the cloud's polled status and its live reports differ): the same print, kept by the uuid.
            if (!key.equals(file) && current.id.equals(current.file)) { current.id = key; save(current); }
        } else if (current != null) finish(now, mono, "interrupted");
        if (current == null && last) return false;   // a print seen only at its end is not recorded
        if (current == null) {
            current = resumable(key, file, printerId, now);
            if (current == null) { current = begin(now, key, file, printer, source); current.printerId = printerId; save(current); }
            // Elapsed time counts on from what was recorded before (a resumed print), measured with the monotonic clock from here.
            current.monoStart = mono - current.elapsed;
            if (current.elapsed == 0 && current.lastSample > current.start) current.monoStart = mono - (current.lastSample - current.start);
            lastLayer = -1; sampledMono = Long.MIN_VALUE / 2; picturedMono = Long.MIN_VALUE / 2; picturedLayer = -1; farPicturedLayer = -1; asked = 0; head = null;
        }
        int layer = print.optInt("current_layer", -1);
        // Every status counts for the measured checks, also the ones between samples.
        JSONObject at = Cc2Codec.position(status);
        double hx = at != null && at.has("x") ? at.optDouble("x") : Double.NaN, hy = at != null && at.has("y") ? at.optDouble("y") : Double.NaN, hz = at != null && at.has("z") ? at.optDouble("z") : Double.NaN;
        current.checks.selfTest = SelfTest.is(current.file);
        current.checks.faults(StatusPresentation.faultCodes(status));
        current.checks.status(mono, layer, hx, hy, hz, "local".equals(source) && current.followsHead(), current.parkX, current.parkY, reach);
        if (!Double.isNaN(hx) && !Double.isNaN(hy)) { head = new double[] {hx, hy, hz, layer}; headMono = mono; }
        JSONObject nozzle = status.optJSONObject("extruder"), bed = status.optJSONObject("heater_bed"), machineNow = status.optJSONObject("machine_status");
        current.checks.heat(mono, layer, nozzle == null ? Double.NaN : nozzle.optDouble("temperature", Double.NaN), nozzle == null ? -1 : nozzle.optDouble("target", -1),
            bed == null ? Double.NaN : bed.optDouble("temperature", Double.NaN), bed == null ? -1 : bed.optDouble("target", -1));
        if (machineNow != null) current.checks.progress(mono, machineNow.optInt("progress", -1), print.optLong("remaining_time_sec", -1));
        if (mono - sampledMono < MIN_INTERVAL_MS && layer == lastLayer && !last) return false;
        append(now, mono, status, source, sub);
        lastLayer = layer; sampledMono = mono;
        if (sub == 2077) finish(now, mono, "complete"); else if (sub == 2504) finish(now, mono, "stopped");
        return true;
    }

    /** Recordings, newest first. Unfinished ones from an earlier app run stay listed with outcome "printing" until resumed or closed. */
    public synchronized List<Recording> list() {
        flush();
        List<Recording> result = new ArrayList<>();
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".json"));
        if (files != null) for (File meta : files) try { result.add(Recording.read(meta)); } catch (Exception ignored) { }
        result.sort((a, b) -> Long.compare(b.start, a.start));
        return result;
    }

    public synchronized void delete(Recording recording) {
        flush();
        if (current != null && current.meta.equals(recording.meta)) current = null;
        deletePictures(recording); RecordingGcode.remove(recording); recording.csv.delete(); recording.meta.delete();
    }
    private static void deletePictures(Recording recording) {
        File[] far = recording.farFolder().listFiles(); if (far != null) for (File f : far) f.delete();
        recording.farFolder().delete();
        File[] files = recording.pictureFolder().listFiles(); if (files != null) for (File f : files) f.delete();
        recording.pictureFolder().delete();
    }

    /** Reads a recording's samples as rows of numbers keyed by column (NaN where missing). */
    public static List<double[]> samples(Recording recording) throws IOException {
        PrintRecorder running = active; if (running != null) running.flush();   // the rows still queued for the disk
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

    /**
     * Whether two reports name the same print: the same key, or the same file where one of them had no uuid (its key is then
     * the file name). Two different uuids are two prints, even of the same file.
     */
    static boolean samePrint(String key, String file, String otherKey, String otherFile) {
        if (key.isEmpty() || otherKey.isEmpty()) return false;
        if (key.equals(otherKey)) return true;
        return !file.isEmpty() && file.equals(otherFile) && (key.equals(file) || otherKey.equals(otherFile));
    }

    /** The same printer, or one side unknown (recordings from before printers were told apart). */
    static boolean samePrinter(String a, String b) { return a.isEmpty() || b.isEmpty() || a.equals(b); }
    static String digest(String serial) {
        if (serial == null || serial.isEmpty()) return "";
        try { byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(("printer:" + serial.trim().toUpperCase(java.util.Locale.ROOT)).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(); for (int i = 0; i < 8; i++) hex.append(String.format("%02x", d[i] & 0xff)); return hex.toString(); }
        catch (java.security.NoSuchAlgorithmException impossible) { return ""; }
    }

    private Recording resumable(String key, String file, String printerId, long now) {
        for (Recording recording : list())
            if (samePrint(key, file, recording.id, recording.file) && samePrinter(printerId, recording.printerId) && "printing".equals(recording.outcome) && now - recording.lastSample < RESUME_WINDOW_MS) return recording;
        return null;
    }

    private Recording begin(long now, String key, String file, String printer, String source) {
        directory.mkdirs();
        String base = now + "-" + file.replaceAll("[^A-Za-z0-9._-]", "_").replaceFirst("(?i)\\.gcode$", "");
        if (base.length() > 80) base = base.substring(0, 80);
        Recording recording = new Recording(new File(directory, base + ".csv"), new File(directory, base + ".json"));
        parkedArmed = farArmed = false; farThisLayer = farLastLayer = 0; farLayer = -1; reach = null; asked = 0; farPicturedLayer = -1;
        recording.pictureFar = defaultPictureFar && defaultPictureLayers > 0;
        recording.id = key; recording.file = file; recording.printer = printer; recording.source = source; recording.start = now; recording.pictureMinutes = defaultPictureMinutes; recording.pictureLayers = defaultPictureLayers;
        File csv = recording.csv;
        write(() -> { try (Writer out = new OutputStreamWriter(new FileOutputStream(csv), StandardCharsets.UTF_8)) { out.write(String.join(",", COLUMNS) + "\n"); } catch (IOException ignored) { } });
        save(recording); prune();
        return recording;
    }

    private void append(long now, long mono, JSONObject status, String source, int sub) {
        JSONObject machine = status.optJSONObject("machine_status"), print = status.optJSONObject("print_status");
        JSONObject position = Cc2Codec.position(status);
        String[] cells = {
            String.valueOf(now), String.valueOf(Math.max(0, mono - current.monoStart) / 1000), number(machine, "progress"), number(print, "current_layer"), number(print, "total_layer"),
            number(print, "remaining_time_sec"), number(position, "z"),
            number(status.optJSONObject("extruder"), "temperature"), number(status.optJSONObject("extruder"), "target"),
            number(status.optJSONObject("heater_bed"), "temperature"), number(status.optJSONObject("heater_bed"), "target"),
            number(status.optJSONObject("ztemperature_sensor"), "temperature"),
            fan(status, "fan"), fan(status, "aux_fan"), fan(status, "box_fan"),
            number(position, "speed_mode"), String.valueOf(sub), "cloud".equals(source) ? "cloud" : "local", activeTray()[0], activeTray()[1], "", number(position, "speed"),
            number(status.optJSONObject("extruder"), "filament_detected"), number(position, "x"), number(position, "y") };
        // Filament fed: the extruder position's steps; retractions cancel out. A step back further than any retraction is a
        // reset (many slicers put G92 E0 at every layer change), after which the position counts up from zero again.
        double e = Cc2Codec.extruderPosition(position);
        if (!Double.isNaN(e) && Math.abs(e) < 1e6) {   // a position past a kilometre of filament is a bad report: not a reference
            if (!Double.isNaN(current.lastE)) { double step = fedStep(current.lastE, e); if (step < 5000) current.extrudedMm = Math.max(0, current.extrudedMm + step); }
            current.lastE = e;
            cells[column("extruded_mm")] = String.format(Locale.ROOT, "%.1f", current.extrudedMm);
        }
        if (current.samples == 0 && current.startProgress < 0 && machine != null) current.startProgress = machine.optInt("progress", -1);
        if (print != null && print.has("print_duration")) current.printDuration = print.optLong("print_duration");
        int progressNow = machine == null ? -1 : machine.optInt("progress", -1); long remainingNow = print == null ? -1 : print.optLong("remaining_time_sec", -1);
        if (current.estimate == 0 && progressNow >= 10 && progressNow <= 20 && remainingNow > 0) {
            long ran = ranSeconds(current, mono);
            if (ran > 0) {
                current.estimate = ran + remainingNow;
                // For checking the printer's print_duration unit and meaning against this phone's clock.
                Diagnostics.note(Diagnostics.STATUS, "estimate at " + progressNow + " %: " + current.estimate + " s in all (printer print time " + current.printDuration + " s, this phone " + (Math.max(0, mono - current.monoStart) / 1000) + " s)");
            }
        }
        File csv = current.csv; String line = String.join(",", cells) + "\n";
        write(() -> { try (Writer out = new OutputStreamWriter(new FileOutputStream(csv, true), StandardCharsets.UTF_8)) { out.write(line); } catch (IOException ignored) { } });
        current.samples++; current.lastSample = now; current.elapsed = Math.max(0, mono - current.monoStart);
        if (print != null) current.layers = Math.max(current.layers, print.optInt("total_layer", 0));
        save(current);
    }

    /** The active tray's number (1 = unit 0 tray 0, 5 = unit 1 tray 0) and colour (0xRRGGBB, decimal), or blanks. */
    private String[] activeTray() {
        JSONObject c = canvas;
        if (c == null || !c.has("active_canvas_id") || !c.has("active_tray_id")) return new String[] {"", ""};
        int unit = c.optInt("active_canvas_id", -1), tray = c.optInt("active_tray_id", -1);
        if (unit < 0 || tray < 0) return new String[] {"", ""};
        String colour = "";
        org.json.JSONArray units = c.optJSONArray("canvas_list");
        for (int u = 0; units != null && u < units.length(); u++) {
            JSONObject one = units.optJSONObject(u); if (one == null || one.optInt("canvas_id", -1) != unit) continue;
            org.json.JSONArray trays = one.optJSONArray("tray_list");
            for (int t = 0; trays != null && t < trays.length(); t++) {
                JSONObject item = trays.optJSONObject(t);
                String hex = item == null || item.optInt("tray_id", -1) != tray ? null : TrayPlan.colour(item.optString("filament_color"));
                if (hex != null) colour = String.valueOf(Integer.parseInt(hex.substring(1), 16));
            }
        }
        return new String[] {String.valueOf(unit * 4 + tray + 1), colour};
    }

    private void finish(long now, long mono, String outcome) {
        if (current == null) return;
        // The time since the last sample counts only up to a minute: after that the app was not watching.
        long since = Math.max(0, Math.min(mono - current.monoStart - current.elapsed, MIN_INTERVAL_MS * 6));
        current.elapsed += since;
        current.outcome = outcome; current.end = current.lastSample + since;
        if (current.end == 0) current.end = now;
        if (!"interrupted".equals(outcome)) { byte[] name = current.meta.getName().getBytes(StandardCharsets.UTF_8); write(() -> { try { java.nio.file.Files.write(new File(directory, "last-finished.txt").toPath(), name); } catch (IOException ignored) { } }); }
        if (!"interrupted".equals(outcome)) { endedKey = current.id; endedFile = current.file; endedPrinter = current.printerId; }
        current.checks.ended(mono, "complete".equals(outcome));
        for (PrintChecks.Finding f : PrintChecks.of(current)) Diagnostics.note(Diagnostics.STATUS, "measured: " + PrintChecks.mark(f.verdict) + " " + f.title + " · " + f.evidence);
        save(current);
        PrintEnded told = printEnded; if (told != null && !"interrupted".equals(outcome)) told.ended(current);
        current = null; lastLayer = -1; learnedRead = false;
    }

    /** How long the print has run (s): this phone's own measure when it saw the start, else the printer's print time; -1 unknown. */
    static long ranSeconds(Recording r, long mono) {
        if (r.startProgress >= 0 && r.startProgress <= 2) return Math.max(0, mono - r.monoStart) / 1000;
        return r.printDuration > 0 ? r.printDuration : -1;
    }
    /** A finished print's real time over the printer's estimate at 10 %, or NaN (unknown, or outside 0.5–2: something else happened). */
    static double ratio(Recording r) {
        if (!"complete".equals(r.outcome) || r.estimate <= 0) return Double.NaN;
        long actual = r.startProgress >= 0 && r.startProgress <= 2 ? r.duration() / 1000 : r.printDuration;
        if (actual <= 0) return Double.NaN;
        double q = actual / (double) r.estimate;
        return q >= 0.5 && q <= 2 ? q : Double.NaN;
    }
    /** The middle ratio of the latest 10 finished prints that have one (`newestFirst`); NaN with fewer than 3. */
    static double learnedRatio(List<Recording> newestFirst) {
        List<Double> ratios = new ArrayList<>();
        for (Recording r : newestFirst) { double q = ratio(r); if (!Double.isNaN(q)) ratios.add(q); if (ratios.size() == 10) break; }
        if (ratios.size() < 3) return Double.NaN;
        java.util.Collections.sort(ratios);
        int n = ratios.size(); return n % 2 == 1 ? ratios.get(n / 2) : (ratios.get(n / 2 - 1) + ratios.get(n / 2)) / 2;
    }
    private volatile double learned = Double.NaN; private boolean learnedRead, learning;
    /** The learned ratio; the first call (and the first after a print ends) starts reading the recordings in the background and answers NaN until then. */
    public double learnedRatio() {
        synchronized (this) { if (learnedRead || learning) return learned; learning = true; }
        Thread reader = new Thread(() -> { double q = learnedRatio(list()); synchronized (PrintRecorder.this) { learned = q; learnedRead = true; learning = false; } }, "recordings-learn");
        reader.setDaemon(true); reader.start();
        return learned;
    }
    /** For tests and callers already off the main thread: reads now. */
    synchronized double learnedRatioNow() { learned = learnedRatio(list()); learnedRead = true; return learned; }
    /** Seconds left for the running print by what past prints took against the printer's estimate, or -1 when not known yet. */
    public synchronized long learnedRemaining(long mono) {
        double q = learned;
        if (!learnedRead) learnedRatio();
        if (current == null || current.estimate <= 0 || Double.isNaN(q)) return -1;
        long ran = ranSeconds(current, mono);
        return ran < 0 ? -1 : Math.max(0, Math.round(current.estimate * q) - ran);
    }
    /** Grams for `mm` of 1.75 mm filament of this type (density by family; PLA when unknown). */
    /** Further back than this (mm) the extruder position was reset, not retracted. */
    static final double RESET_MM = 10;
    /** Filament fed between two reported extruder positions: the difference, or after a reset the new position itself. Pure. */
    static double fedStep(double before, double now) { double step = now - before; return step < -RESET_MM ? Math.max(0, now) : step; }
    static double grams(double mm, String type) {
        String t = type == null ? "" : FilamentMatch.family(type);
        double density = t.equals("PETG") ? 1.27 : t.equals("ABS") ? 1.04 : t.equals("ASA") ? 1.07 : t.equals("TPU") ? 1.21 : t.equals("PC") ? 1.20 : t.startsWith("PA") ? 1.14 : 1.24;
        return mm * Math.PI * 0.875 * 0.875 * density / 1000;
    }

    private void save(Recording recording) {
        String json; try { json = recording.toJson().toString(); } catch (Exception unwritable) { return; }
        File meta = recording.meta;
        write(() -> { try (Writer out = new OutputStreamWriter(new FileOutputStream(meta), StandardCharsets.UTF_8)) { out.write(json); } catch (IOException ignored) { } });
    }

    /**
     * Where the recording's files are written: on the caller's thread (tests, and until the service asks), or in order on
     * one background thread, so statuses handled on the screen thread never wait for the disk.
     */
    private java.util.concurrent.ExecutorService writer;
    private static final String WRITER = "recorder-writes";
    public synchronized void writeInBackground() { if (writer == null) writer = java.util.concurrent.Executors.newSingleThreadExecutor(r -> new Thread(r, WRITER)); }
    private void write(Runnable task) { if (writer == null) task.run(); else writer.execute(task); }
    /** Waits (briefly) for the writes already queued, before files are read back or removed. */
    private void flush() {
        java.util.concurrent.ExecutorService w = writer;
        if (w == null || WRITER.equals(Thread.currentThread().getName())) return;
        try { w.submit(() -> { }).get(3, java.util.concurrent.TimeUnit.SECONDS); } catch (Exception late) { }
    }

    /** Keeps the newest KEEP recordings, and any older one a project links to (when the projects cannot be read, all of them). */
    private void prune() {
        List<Recording> all = list(); if (all.size() <= KEEP) return;
        java.util.Set<String> linked = Projects.referencedRecordings(new File(directory.getParentFile(), "projects"));
        if (linked == null) return;
        for (int i = KEEP; i < all.size(); i++) {
            if (linked.contains(all.get(i).meta.getName())) continue;
            deletePictures(all.get(i)); RecordingGcode.remove(all.get(i)); all.get(i).csv.delete(); all.get(i).meta.delete();
        }
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
