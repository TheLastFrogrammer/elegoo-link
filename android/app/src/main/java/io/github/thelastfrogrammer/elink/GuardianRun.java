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
    private String printId = "", pathFor = "";
    private volatile GcodeToolpath path;
    private PrintGuardian.Watch watch = new PrintGuardian.Watch();
    /** The latest check: picture with the expected print in teal and filament-coloured pixels off the print in red. */
    volatile Bitmap overlay;
    volatile String verdict = "";
    volatile boolean alarmed;
    volatile int checks;

    GuardianRun(Context context) { this.context = context.getApplicationContext(); }

    /** Checks one kept picture. `layer`/`total`: the printer's layer report; `head`: nozzle x, y, z (NaN when not reported); `trayRgb`: active tray colour or -1. */
    void check(PrintRecorder.Recording recording, byte[] jpeg, int layer, int total, double[] head, int trayRgb, Alarm alarm, Runnable changed) {
        worker.execute(() -> {
            try { run(recording, jpeg, layer, total, head, trayRgb, alarm); }
            catch (Throwable failed) { verdict = "Could not check this picture."; Diagnostics.note(Diagnostics.GUARDIAN, "check failed: " + failed.getClass().getSimpleName()); }
            changed.run();
        });
    }

    private void run(PrintRecorder.Recording recording, byte[] jpeg, int layer, int total, double[] head, int trayRgb, Alarm alarm) throws Exception {
        if (!recording.id.equals(printId)) { printId = recording.id; watch = new PrintGuardian.Watch(); overlay = null; alarmed = false; checks = 0; verdict = ""; }
        File file = new GcodeLibrary(new File(context.getFilesDir(), "gcode-library")).find(recording.file);
        if (file == null) {
            if (!verdict.startsWith("No copy")) Diagnostics.note(Diagnostics.GUARDIAN, "no phone copy of this print's G-code; not checking");
            verdict = "No copy of this print's G-code on the phone, so there is nothing to compare with. Prints sent or sliced from this app have one.";
            return;
        }
        String key = file.getPath() + ":" + file.lastModified();
        if (!key.equals(pathFor)) {
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

        BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) { verdict = "Could not read the picture."; return; }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = Math.max(1, Integer.highestOneBit(Math.max(1, bounds.outWidth / (PrintGuardian.W * 2))));
        Bitmap picture = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, options);
        if (picture == null) { verdict = "Could not read the picture."; return; }
        double aspect = bounds.outWidth / (double) bounds.outHeight;
        Bitmap small = Bitmap.createScaledBitmap(picture, PrintGuardian.W, PrintGuardian.H, true);
        int[] rgb = new int[PrintGuardian.W * PrintGuardian.H];
        small.getPixels(rgb, 0, PrintGuardian.W, 0, 0, PrintGuardian.W, PrintGuardian.H);

        android.content.SharedPreferences hints = context.getSharedPreferences("viewer-hints", Context.MODE_PRIVATE);
        boolean linedUp = hints.getInt("cameraSpot", 0) == GcodeViewerActivity.LINED_UP && !GcodeViewerActivity.savedPoints(hints).isEmpty();
        double[] camera = GcodeViewerActivity.cameraFor(hints, path.layerZ(at));
        int filament = trayRgb;
        if (filament < 0) { String hex = path.filamentColour(0); if (hex != null) filament = Integer.parseInt(hex.substring(1), 16); }
        if (filament < 0) { verdict = "The filament's colour is not known, so the guardian cannot tell filament from background."; return; }

        boolean[] expected = PrintGuardian.expectedMask(path, at, camera, aspect, linedUp ? 2 : 4);
        boolean[] bed = PrintGuardian.bedMask(camera, aspect);
        boolean[] headMask = PrintGuardian.headMask(camera, aspect, head[0], head[1], head[2]);
        PrintGuardian.Result result = PrintGuardian.analyse(rgb, filament, expected, bed, headMask);
        boolean raise = watch.add(result, at + 1);
        checks++; alarmed = watch.alarmed;
        verdict = (watch.alarmed ? "Something looks wrong: " : "Layer " + layer + ": ") + watch.reason + (linedUp ? "" : ". The camera is not lined up in Live toolpath, so this is rough.");
        overlay = overlay(rgb, result);
        if (checks == 1 || checks % 10 == 0 || raise)
            Diagnostics.note(Diagnostics.GUARDIAN, "check " + checks + " at layer " + layer + ": print area " + Math.round(result.coverage * 100) + " % filament, bed " + Math.round(result.outside * 1000) / 10.0
                + " % (" + result.expectedPixels + "/" + result.bedPixels + " cells)" + (linedUp ? "" : ", camera not lined up") + (raise ? "; ALARM: " + watch.reason : ""));
        if (raise) alarm.raise(watch.reason, overlay);
    }

    /** The person's answer to the last alarm, for tuning (logged): "looks fine" also allows more before the next. */
    void feedback(boolean fine) {
        worker.execute(() -> {
            Diagnostics.note(Diagnostics.GUARDIAN, (fine ? "person says it looks fine" : "person says it is a real problem") + " after " + checks + " checks: " + watch.reason);
            if (fine) { watch.looksFine(); alarmed = false; verdict = "Thanks. The guardian allows more before the next alarm for this print."; }
            else verdict = "Noted as a real problem. The guardian keeps watching; what to do with the print is up to you.";
        });
    }

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
