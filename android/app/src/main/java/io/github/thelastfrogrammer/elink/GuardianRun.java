package io.github.thelastfrogrammer.elink;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs the print guardian (PrintGuardian) on each picture the print's recording keeps, on its own thread: loads the print's
 * G-code from the phone's copy, projects it through the lined-up camera and compares. It only looks and tells; it never sends
 * the printer anything. The latest verdict and overlay are kept for the Print tab.
 */
final class GuardianRun {
    interface Alarm { void raise(String reason, Bitmap overlay); }

    private final Context context;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private String printId = "", pathFor = "", watchedCamera = "";
    private final java.util.Set<Integer> printedColours = new java.util.LinkedHashSet<>();
    private volatile GcodeToolpath path;
    private PrintGuardian.Watch watch = new PrintGuardian.Watch();
    /** The latest check: picture with the expected print in teal and filament-coloured pixels off the print in red. */
    volatile Bitmap overlay;
    volatile String verdict = "";
    volatile boolean alarmed;
    volatile int checks;

    GuardianRun(Context context) { this.context = context.getApplicationContext(); }

    /** Checks one kept picture. `layer`/`total`: the printer's layer report; `head`: nozzle x, y, z (NaN when not reported); `trayRgb`: active tray colour or -1. */
    /** `cameraId`: the camera the picture came from (PrintRecorder.PRINTER_CAMERA or "added:" and its id), fixed when it was kept. */
    /** `job`: the print as the service observes it (printer, file name, continuity token), or null when there is none. */
    void check(PrintRecorder.Recording recording, byte[] jpeg, int layer, int total, double[] head, int trayRgb, String cameraId, boolean parked, GcodeJobTracker.Job job, Alarm alarm, Runnable changed) {
        worker.execute(() -> {
            try {
                double[] aspect = new double[1];
                int[] rgb = grid(jpeg, aspect);
                if (rgb == null) verdict = "Could not read the picture.";
                else {
                    // The change check first: it needs no G-code, line-up or colour, so it runs where the colour check cannot.
                    changeCheck(recording, rgb, aspect[0], layer, head, cameraId, parked, alarm);
                    run(recording, rgb, aspect[0], layer, total, head, trayRgb, cameraId, job, alarm);
                    if (!changeVerdict.isEmpty()) verdict = verdict + (verdict.isEmpty() ? "" : "\n") + "Changes between pictures: " + changeVerdict;
                }
            }
            catch (Throwable failed) { verdict = "Could not check this picture."; Diagnostics.note(Diagnostics.GUARDIAN, "check failed: " + failed.getClass().getSimpleName()); }
            changed.run();
        });
    }

    /** The picture on the guardian's grid (RGB), and its width over height; null when unreadable. */
    private static int[] grid(byte[] jpeg, double[] aspect) {
        BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = Math.max(1, Integer.highestOneBit(Math.max(1, bounds.outWidth / (PrintGuardian.W * 2))));
        Bitmap picture = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, options);
        if (picture == null) return null;
        aspect[0] = bounds.outWidth / (double) bounds.outHeight;
        Bitmap small = Bitmap.createScaledBitmap(picture, PrintGuardian.W, PrintGuardian.H, true);
        int[] rgb = new int[PrintGuardian.W * PrintGuardian.H];
        small.getPixels(rgb, 0, PrintGuardian.W, 0, 0, PrintGuardian.W, PrintGuardian.H);
        return rgb;
    }

    /** The change check (PictureChange) for this print and camera, and what it last said. */
    private PictureChange.Watch change = new PictureChange.Watch();
    private String changePrint = "", changeCamera = "";
    volatile String changeVerdict = "";
    private int changeChecks;
    /**
     * Each picture against the ones before it. Parked pictures (smooth timelapse) show the bed alike each time; otherwise
     * the head moves about the picture, so it is masked out with the lined-up camera, or the check waits.
     */
    private void changeCheck(PrintRecorder.Recording recording, int[] rgb, double aspect, int layer, double[] head, String cameraId, boolean parked, Alarm alarm) {
        if (!recording.id.equals(changePrint) || !cameraId.equals(changeCamera)) { changePrint = recording.id; changeCamera = cameraId; change = new PictureChange.Watch(); changeVerdict = ""; changeChecks = 0; }
        boolean[] ignore = null;
        // A smooth print's picture taken when the park was missed shows the head: it is left out rather than compared.
        if (recording.smooth() && !parked) return;
        if (!recording.smooth()) {
            String id = cameraId.startsWith("added:") ? cameraId.substring(6) : "";
            android.content.SharedPreferences hints = context.getSharedPreferences("viewer-hints", Context.MODE_PRIVATE);
            if (!GcodeViewerActivity.linedUp(hints, id) || Double.isNaN(head[0]) || Double.isNaN(head[1]) || Double.isNaN(head[2])) {
                changeVerdict = "needs parked pictures (Smooth timelapse) or the camera lined up in Live toolpath, so the head can be left out.";
                return;
            }
            ignore = PrintGuardian.headMask(GcodeViewerActivity.cameraFor(hints, head[2], id), aspect, head[0], head[1], head[2]);
        }
        boolean raise = change.add(PictureChange.luminance(rgb), ignore);
        changeChecks++;
        PrintRecorder recorder = PrintRecorder.active;
        if (recorder != null) {
            recorder.noteGuardianCheck(recording.meta, layer, PrintRecorder.GUARDIAN_SPREAD, change.spreadNow, change.spreadLimit, change.spreadOver, raise && change.reason.startsWith("changes spreading"));
            recorder.noteGuardianCheck(recording.meta, layer, PrintRecorder.GUARDIAN_SUDDEN, change.jumpNow, change.jumpLimit, change.jumpOver, raise && change.reason.contains("come loose"));
        }
        changeVerdict = change.alarmed ? "something looks wrong: " + change.reason : change.reason;
        if (changeChecks == 1 || changeChecks % 10 == 0 || raise) Diagnostics.note(Diagnostics.GUARDIAN, "change check " + changeChecks + ": " + change.reason + (raise ? "; ALARM" : ""));
        if (raise) { alarmed = true; overlay = changeOverlay(rgb, change); alarm.raise(change.reason, overlay); }
    }
    /** The picture dimmed, where the print has been changing in teal, and the last picture's changes away from it in red. */
    static Bitmap changeOverlay(int[] rgb, PictureChange.Watch watch) {
        boolean[] region = watch.region(), near = PrintGuardian.grow(region, 3); int[] out = new int[rgb.length];
        for (int i = 0; i < rgb.length; i++) {
            int p = rgb[i], red = ((p >> 16) & 255) * 6 / 10, green = ((p >> 8) & 255) * 6 / 10, blue = (p & 255) * 6 / 10;
            if (watch.last[i] && !near[i]) { red = 255; green = 40; blue = 40; }
            else if (region[i]) { red = red / 2; green = (green + 200) / 2; blue = (blue + 180) / 2; }
            out[i] = 0xff000000 | red << 16 | green << 8 | blue;
        }
        Bitmap small = Bitmap.createBitmap(out, PrintGuardian.W, PrintGuardian.H, Bitmap.Config.ARGB_8888);
        return Bitmap.createScaledBitmap(small, PrintGuardian.W * 3, PrintGuardian.H * 3, false);
    }

    /**
     * Which phone copy of the print's G-code to compare with: the copy the person chose for this print in Live toolpath, or
     * else the only copy kept for this printer under the print's name (assumed, and said so). Several different copies with
     * that name, or none, and nothing is checked: a name alone never picks between versions.
     */
    static final class Source {
        final File file; final GcodeLibrary.Artifact artifact; final boolean chosen; final String problem;
        Source(File file, GcodeLibrary.Artifact artifact, boolean chosen, String problem) { this.file = file; this.artifact = artifact; this.chosen = chosen; this.problem = problem; }
        String key() { return artifact != null ? artifact.sha256 : file == null ? "" : file.getPath() + ":" + file.lastModified(); }
    }
    static Source source(GcodeLibrary library, GcodeJobTracker.Job job, String recordedName) {
        String none = "No copy of this print's G-code on the phone, so there is nothing to compare with. Prints sent or sliced from this app have one.";
        if (job == null) {
            File unscoped = library.find(recordedName);
            return unscoped == null ? new Source(null, null, false, none) : new Source(unscoped, null, false, null);
        }
        GcodeLibrary.Artifact chosen = library.selected(job.printer, job.token);
        if (chosen != null) return new Source(chosen.file, chosen, true, null);
        if (library.selectionEvicted(job.printer, job.token)) return new Source(null, null, false, "The copy you chose for this print is no longer on the phone. Choose it again in Live toolpath.");
        java.util.List<GcodeLibrary.Artifact> copies;
        try { copies = library.candidates(job.printer, job.filename); } catch (java.io.IOException unreadable) { copies = java.util.Collections.emptyList(); }
        if (copies.size() == 1) return new Source(copies.get(0).file, copies.get(0), false, null);
        if (copies.size() > 1) return new Source(null, null, false, copies.size() + " different copies named " + GcodeLibrary.baseName(job.filename)
            + " are on the phone. Choose the one this print uses in Live toolpath, and the guardian checks against it.");
        return new Source(null, null, false, none);
    }

    /**
     * The Print tab's File source line: whether the phone holds the exact G-code of the running print (chosen in Live toolpath),
     * a copy that only matches by name, or none. A matching name is never called the exact file.
     */
    static String fileSourceLine(Source source) {
        if (source.chosen && source.artifact != null) return "File source: the exact copy you chose · " + source.artifact.sha256.substring(0, Math.min(8, source.artifact.sha256.length()));
        if (source.file != null) return "File source: a copy with the same name on this phone (not confirmed). Tap to choose it in Live toolpath.";
        if (source.problem != null && source.problem.startsWith("No copy")) return "File source: no copy of this print's G-code on this phone. Prints sent or sliced from this app keep one.";
        return "File source: " + (source.problem == null ? "unknown" : source.problem);
    }

    private void run(PrintRecorder.Recording recording, int[] rgb, double aspect, int layer, int total, double[] head, int trayRgb, String cameraId, GcodeJobTracker.Job job, Alarm alarm) throws Exception {
        if (!recording.id.equals(printId)) { printId = recording.id; watch = new PrintGuardian.Watch(); overlay = null; alarmed = false; checks = 0; verdict = ""; watchedCamera = cameraId; printedColours.clear(); }
        // Coverage learned from one camera means nothing for another: a different camera starts the comparison again.
        if (!cameraId.equals(watchedCamera)) { watchedCamera = cameraId; watch = new PrintGuardian.Watch(); alarmed = false; }
        GcodeLibrary library = new GcodeLibrary(new File(context.getFilesDir(), "gcode-library"));
        Source source = source(library, job, recording.file);
        if (source.problem != null) {
            if (!source.problem.equals(verdict)) Diagnostics.note(Diagnostics.GUARDIAN, "not checking: " + source.problem);
            verdict = source.problem; return;
        }
        File file = source.file;
        String key = source.key();
        if (!key.equals(pathFor)) {
            // A different copy than before: what was learned against the old one does not carry over.
            if (!pathFor.isEmpty()) { watch = new PrintGuardian.Watch(); alarmed = false; }
            if (source.artifact != null && !library.verify(source.artifact)) { verdict = "The phone copy of this print's G-code changed or is damaged, so it is not checked."; path = null; pathFor = ""; return; }
            path = null; pathFor = "";
            // The toolpath takes about three times the file's size in memory while the print runs: skip files that would crowd the app.
            Runtime runtime = Runtime.getRuntime();
            long free = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory());
            if (file.length() * 3 > free / 2) {
                if (!verdict.startsWith("This print's G-code is too big")) Diagnostics.note(Diagnostics.GUARDIAN, "G-code of " + file.length() / (1024 * 1024) + " MB too big to keep in memory; not checking");
                verdict = "This print's G-code is too big for the guardian to keep in memory, so it is not checked.";
                return;
            }
            path = GcodeToolpath.read(file); pathFor = key;
        }
        if (path.layerCount == 0 || layer < 1) { verdict = "Waiting for the first layer."; return; }
        int at = total > 0 ? (int) Math.round(layer / (double) total * path.layerCount) - 1 : layer - 1;
        at = Math.max(0, Math.min(path.layerCount - 1, at));

        android.content.SharedPreferences hints = context.getSharedPreferences("viewer-hints", Context.MODE_PRIVATE);
        // The camera this picture came from (not whichever is chosen now) and its own line-up.
        String id = cameraId.startsWith("added:") ? cameraId.substring(6) : "";
        ExtraCameras.Camera added = id.isEmpty() ? null : ExtraCameras.find(context, id);
        if (!id.isEmpty() && added == null) { verdict = "The camera that took this picture was removed."; return; }
        boolean linedUp = GcodeViewerActivity.linedUp(hints, id);
        if (added != null && !linedUp) {
            verdict = "Print pictures come from “" + added.name + "”. Line it up in Live toolpath (More…, Camera: " + added.name + ", then Line up the camera by hand) so the guardian can check them.";
            return;
        }
        double[] camera = GcodeViewerActivity.cameraFor(hints, path.layerZ(at), id);
        // Every colour printed so far in this print (the active tray's at each check), so a colour change does not make the
        // parts in the earlier colour look gone.
        if (trayRgb >= 0) printedColours.add(trayRgb);
        if (printedColours.isEmpty()) { String hex = path.filamentColour(0); if (hex != null) printedColours.add(Integer.parseInt(hex.substring(1), 16)); }
        if (printedColours.isEmpty()) { verdict = "The filament's colour is not known, so the guardian cannot tell filament from background."; return; }
        int[] colours = new int[printedColours.size()]; int k = 0; for (int c : printedColours) colours[k++] = c;

        boolean[] expected = PrintGuardian.expectedMask(path, at, camera, aspect, linedUp ? 2 : 4);
        boolean[] bed = PrintGuardian.bedMask(camera, aspect);
        boolean[] headMask = PrintGuardian.headMask(camera, aspect, head[0], head[1], head[2]);
        PrintGuardian.Result result = PrintGuardian.analyse(rgb, colours, expected, bed, headMask);
        if (result.undecided) { verdict = "The filament looks too much like the bed in this picture, so it is not checked."; overlay = null; return; }
        boolean raise = watch.add(result, at + 1);
        PrintRecorder recorder = PrintRecorder.active;
        if (recorder != null && !Double.isNaN(watch.outsideLimit)) {
            recorder.noteGuardianCheck(recording.meta, layer, PrintRecorder.GUARDIAN_OFF_PRINT, result.outside, watch.outsideLimit, watch.outsideBad, raise && watch.outsideBad);
            recorder.noteGuardianCheck(recording.meta, layer, PrintRecorder.GUARDIAN_COVERAGE, result.coverage, watch.coverageLimit, watch.coverageBad, raise && watch.coverageBad);
        }
        checks++; alarmed = watch.alarmed || change.alarmed;
        verdict = (watch.alarmed ? "Something looks wrong: " : "Layer " + layer + ": ") + watch.reason + (linedUp ? "" : ". The camera is not lined up in Live toolpath, so this is rough.") + (added == null ? "" : " (camera: " + added.name + ")")
            + (source.chosen ? "" : ". Compared with the only phone copy of " + GcodeLibrary.baseName(recording.file) + " (assumed; choose it in Live toolpath to confirm)");
        if (!change.alarmed) overlay = overlay(rgb, result);   // the change check's alarm keeps its own picture
        if (checks == 1 || checks % 10 == 0 || raise)
            Diagnostics.note(Diagnostics.GUARDIAN, "check " + checks + " at layer " + layer + ": print area " + Math.round(result.coverage * 100) + " % filament, bed " + Math.round(result.outside * 1000) / 10.0
                + " % (" + result.expectedPixels + "/" + result.bedPixels + " cells)" + (linedUp ? "" : ", camera not lined up") + (raise ? "; ALARM: " + watch.reason : ""));
        if (raise) alarm.raise(watch.reason, overlay);
    }

    /** The person's answer to the last alarm, for tuning (logged): "looks fine" also allows more before the next. */
    void feedback(boolean fine) {
        worker.execute(() -> {
            Diagnostics.note(Diagnostics.GUARDIAN, (fine ? "person says it looks fine" : "person says it is a real problem") + " after " + checks + " checks: " + watch.reason + "; changes: " + change.reason);
            if (fine) { watch.looksFine(); change.looksFine(); alarmed = false; verdict = "Thanks. The guardian allows more before the next alarm for this print."; }
            else verdict = "Noted as a real problem. The guardian keeps watching; what to do with the print is up to you.";
        });
    }

    /** Pictures come from a camera the person added: the line-up (and so the guardian) is for the printer's own camera. */
    void otherCamera() { verdict = "Print pictures come from an added camera, so the guardian does not check them: it is lined up for the printer's own camera."; alarmed = false; overlay = null; }
    /** Lets go of the toolpath once nothing prints. */
    void release() { if (path != null) worker.execute(() -> { path = null; pathFor = ""; }); }
    void shutdown() { worker.shutdownNow(); }

    static Bitmap overlay(int[] rgb, PrintGuardian.Result r) {
        int[] out = new int[rgb.length];
        for (int i = 0; i < rgb.length; i++) {
            int p = rgb[i], red = (p >> 16) & 255, green = (p >> 8) & 255, blue = p & 255;
            red = red * 6 / 10; green = green * 6 / 10; blue = blue * 6 / 10;   // dimmed so the marks stand out
            if (!r.head[i] && r.material[i] && !r.expected[i] && r.bed[i]) { red = 255; green = 40; blue = 40; }
            else if (r.expected[i]) { red = red / 2; green = (green + 200) / 2; blue = (blue + 180) / 2; }
            out[i] = 0xff000000 | red << 16 | green << 8 | blue;
        }
        Bitmap small = Bitmap.createBitmap(out, PrintGuardian.W, PrintGuardian.H, Bitmap.Config.ARGB_8888);
        return Bitmap.createScaledBitmap(small, PrintGuardian.W * 3, PrintGuardian.H * 3, false);
    }
}
