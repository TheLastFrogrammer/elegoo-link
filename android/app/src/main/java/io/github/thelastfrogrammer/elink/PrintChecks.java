package io.github.thelastfrogrammer.elink;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONObject;

/**
 * Checks that measure themselves while a print runs, from the statuses the app receives anyway: nothing is sent to the
 * printer for them. Each answers a field-test question with evidence (how often status arrives, whether the smooth
 * timelapse parks and comes back, whether the printer counts layers as the G-code does, whether its reported head position
 * lies on the layer being printed, how long pictures take). Kept with the print's recording. Independent of Android.
 */
final class PrintChecks {
    enum Verdict { WORKS, PROBLEM, UNSURE }

    /** One measured answer: which question (an id), the verdict, its title and the numbers behind it. */
    static final class Finding {
        final String id, title, evidence; final Verdict verdict;
        Finding(String id, String title, Verdict verdict, String evidence) { this.id = id; this.title = title; this.verdict = verdict; this.evidence = evidence; }
    }

    /** The questions, in the order they are shown. */
    static final String[][] QUESTIONS = {
        {"statusRate", "Status every second on the local connection"},
        {"park", "Smooth timelapse parks the head"},
        {"parkReturn", "The head comes back down after parking"},
        {"layers", "Layer numbers match the G-code"},
        {"position", "Reported head position is on the layer"},
        {"pictures", "Pictures arrive in time"},
        {"heat", "Heaters hold their temperature"},
        {"filament", "Filament use matches the G-code"},
        {"estimate", "Slicer time estimate"},
        {"eta", "Printer's time left"},
    };

    /** Margin (mm) around a layer's printed area for a head position to count as on it (travel, wipes, the nozzle's own size). */
    static final double ON_LAYER_MM = 8;
    /** The lift the smooth timelapse G-code makes at the park, and how far a reading may be from it. */
    static final double LIFT_MM = 0.6, Z_TOLERANCE = 0.08;

    // Status arrival (local statuses only, while statuses are asked every second).
    int fastGaps, fastQuick, fastSlow; long lastFastAt = -1;
    // Park (smooth prints): layers with a parked reading, layers seen, lifts and returns in and out of tolerance.
    int parkLayers, smoothLayers, lifts, liftsOk, returns, returnsOk; long dwellMs; int dwells;
    private boolean inPark; private double parkZ = Double.NaN, beforeZ = Double.NaN; private long parkStart; private int parkLayer = -1, smoothLayer = -1;
    // Layers: the highest the printer reported, and the G-code's count (0 when the phone has no copy).
    int maxLayer, fileLayers;
    // Position against the G-code's printed area of the layer.
    int positions, positionsOn;
    // Pictures: how long from asking to keeping (ms), per set (0 the main set, 1 the far one).
    final long[] waitSum = new long[2], waitMax = new long[2]; final int[] waits = new int[2];
    // Heat while printing: readings within 4 °C of the target (once reached, and not just after the target changed), and the largest miss.
    int heatSamples, heatOk; double worstNozzle, worstBed;
    private boolean nozzleReached, bedReached; private double nozzleTarget = -1, bedTarget = -1; private long targetChangedAt = -1;
    // Time: the first layer's start, the end (complete prints), the file's estimate, and the printer's time left at half way.
    long firstLayerAt = -1, endedAt = -1, fileSeconds, etaAt = -1, etaRemaining; boolean complete;
    // Filament the file feeds (mm), for comparing with the extruder's count.
    double fileExtruded;

    /**
     * One status: the time (monotonic ms), the printer's layer, the head position (NaN when not reported), whether it came
     * over the local connection with statuses asked every second, the park corner (NaN: not a smooth print), and the G-code's
     * reach (null without a copy).
     */
    void status(long mono, int layer, double x, double y, double z, boolean fastLocal, double parkX, double parkY, LayerReach reach) {
        if (fastLocal) {
            if (lastFastAt >= 0) { long gap = mono - lastFastAt; if (gap > 0 && gap < 60_000) { fastGaps++; if (gap <= 1_600) fastQuick++; else if (gap > 3_000) fastSlow++; } }
            lastFastAt = mono;
        } else lastFastAt = -1;
        if (layer > maxLayer) maxLayer = layer;
        if (layer >= 1 && firstLayerAt < 0) firstLayerAt = mono;
        if (reach != null) { fileLayers = reach.layers; fileSeconds = reach.estimatedSeconds; fileExtruded = reach.extruded; }
        if (Double.isNaN(x) || Double.isNaN(y) || layer < 1) return;
        boolean smooth = !Double.isNaN(parkX) && !Double.isNaN(parkY);
        boolean parked = smooth && SmoothTimelapse.parked(x, y, parkX, parkY);
        if (smooth) {
            if (layer != smoothLayer) { smoothLayer = layer; smoothLayers++; }
            if (parked && !inPark) {
                inPark = true; parkStart = mono; parkZ = z;
                if (layer != parkLayer) { parkLayer = layer; parkLayers++; }
                // The lift is 0.6 mm above the layer; the reading before may still be the layer below (one layer height lower).
                if (!Double.isNaN(z) && !Double.isNaN(beforeZ)) { lifts++; double lift = z - beforeZ; if (lift >= LIFT_MM - Z_TOLERANCE && lift <= LIFT_MM + 0.5) liftsOk++; }
            } else if (!parked && inPark) {
                inPark = false; dwellMs += mono - parkStart; dwells++;
                // Back on the layer: 0.6 mm below the parked height (a z-hop on the way out would read higher, never lower).
                if (!Double.isNaN(z) && !Double.isNaN(parkZ)) { returns++; double drop = parkZ - z; if (Math.abs(drop - LIFT_MM) <= Z_TOLERANCE) returnsOk++; }
            }
            if (!parked) beforeZ = z;
        }
        if (!parked && reach != null) {
            float[] box = reach.boxForPrinterLayer(layer);
            if (box != null) {
                positions++;
                if (x >= box[0] - ON_LAYER_MM && x <= box[2] + ON_LAYER_MM && y >= box[1] - ON_LAYER_MM && y <= box[3] + ON_LAYER_MM) positionsOn++;
            }
        }
    }

    /** Heater readings while printing (layer 1 on): °C, NaN when not reported. */
    void heat(long mono, int layer, double nozzle, double nozzleSet, double bed, double bedSet) {
        if (nozzleSet != nozzleTarget || bedSet != bedTarget) { nozzleTarget = nozzleSet; bedTarget = bedSet; targetChangedAt = mono; nozzleReached = bedReached = false; }
        if (layer < 1 || mono - targetChangedAt < 60_000) return;   // a new target takes a while to reach: not counted for a minute
        boolean counted = false, ok = true;
        if (nozzleSet > 0 && !Double.isNaN(nozzle)) {
            double miss = Math.abs(nozzle - nozzleSet); if (miss <= 2) nozzleReached = true;
            if (nozzleReached) { counted = true; if (miss > 4) ok = false; worstNozzle = Math.max(worstNozzle, miss); }
        }
        if (bedSet > 0 && !Double.isNaN(bed)) {
            double miss = Math.abs(bed - bedSet); if (miss <= 2) bedReached = true;
            if (bedReached) { counted = true; if (miss > 4) ok = false; worstBed = Math.max(worstBed, miss); }
        }
        if (counted) { heatSamples++; if (ok) heatOk++; }
    }
    /** Progress (%) and the printer's time left (s): the time left at half way is compared with the real rest of the print. */
    void progress(long mono, int percent, long remaining) { if (etaAt < 0 && percent >= 50 && percent < 95 && remaining > 0) { etaAt = mono; etaRemaining = remaining; } }
    /** The print ended (complete, or stopped or interrupted). */
    void ended(long mono, boolean completed) { endedAt = mono; complete = completed; }

    /** A kept picture: its set (0 main, 1 far) and how long after it was asked for (ms; negative when it came unasked). */
    void picture(int set, long waitedMs) {
        if (waitedMs < 0 || set < 0 || set > 1) return;
        waits[set]++; waitSum[set] += waitedMs; waitMax[set] = Math.max(waitMax[set], waitedMs);
    }

    /** The answers so far; questions without enough evidence are left out. `pictures`: pictures kept (main, far). */
    List<Finding> findings(int picturesMain, int picturesFar, boolean comparing) { return findings(picturesMain, picturesFar, comparing, Double.NaN); }
    /** As above; `extrudedMm`: the extruder's own count for the whole print (NaN when the start was not seen). */
    List<Finding> findings(int picturesMain, int picturesFar, boolean comparing, double extrudedMm) {
        List<Finding> out = new ArrayList<>();
        if (fastGaps >= 30) {
            int share = percent(fastQuick, fastGaps);
            out.add(new Finding("statusRate", title("statusRate"), share >= 80 ? Verdict.WORKS : share >= 50 ? Verdict.UNSURE : Verdict.PROBLEM,
                share + "% of " + fastGaps + " statuses came within 1.6 s of the one before; " + fastSlow + " took over 3 s."));
        }
        if (smoothLayers >= 5) {
            int share = percent(parkLayers, smoothLayers);
            String dwell = dwells > 0 ? String.format(Locale.ROOT, " The head was seen there for at least %.1f s on average.", dwellMs / 1000.0 / dwells) : "";
            String lift = lifts > 0 ? " Lift " + liftsOk + " of " + lifts + " as planned (0.6 mm)." : "";
            out.add(new Finding("park", title("park"), share >= 80 ? Verdict.WORKS : parkLayers == 0 ? Verdict.PROBLEM : Verdict.UNSURE,
                "Parked on " + parkLayers + " of " + smoothLayers + " layers seen." + lift + dwell
                + (parkLayers == 0 ? " The head never reported the park corner: the printer may skip the layer-change G-code, or statuses are too far apart." : "")));
        }
        if (returns >= 5) {
            int share = percent(returnsOk, returns);
            out.add(new Finding("parkReturn", title("parkReturn"), share >= 90 ? Verdict.WORKS : share >= 60 ? Verdict.UNSURE : Verdict.PROBLEM,
                returnsOk + " of " + returns + " times the head was back at the layer height (0.6 mm below the park) right after leaving the corner."));
        }
        if (fileLayers > 0 && maxLayer >= fileLayers - 1 && maxLayer > 0) {
            int off = maxLayer - fileLayers;
            out.add(new Finding("layers", title("layers"), off == 0 ? Verdict.WORKS : Math.abs(off) == 1 ? Verdict.UNSURE : Verdict.PROBLEM,
                "The printer reached layer " + maxLayer + "; the G-code has " + fileLayers + " layers" + (off == 0 ? "." : " (off by " + off + ").")));
        }
        if (positions >= 30) {
            int share = percent(positionsOn, positions);
            out.add(new Finding("position", title("position"), share >= 90 ? Verdict.WORKS : share >= 70 ? Verdict.UNSURE : Verdict.PROBLEM,
                share + "% of " + positions + " positions were within " + (int) ON_LAYER_MM + " mm of the layer's printed area in the G-code"
                + (share < 70 ? ": the printer may report positions in another frame (offset or mirrored)." : ".")));
        }
        if (waits[0] + waits[1] >= 3) {
            StringBuilder words = new StringBuilder();
            for (int set = 0; set < 2; set++) if (waits[set] > 0)
                words.append(set == 0 ? (comparing ? "Parked set" : "Pictures") : " Far set").append(String.format(Locale.ROOT, ": %.1f s on average, %.1f s at most.", waitSum[set] / 1000.0 / waits[set], waitMax[set] / 1000.0));
            long worst = Math.max(waits[0] > 0 ? waitSum[0] / waits[0] : 0, waits[1] > 0 ? waitSum[1] / waits[1] : 0);
            words.append(" Kept: ").append(picturesMain).append(comparing ? " parked, " + picturesFar + " far." : ".");
            out.add(new Finding("pictures", title("pictures"), worst <= 3_000 ? Verdict.WORKS : worst <= 8_000 ? Verdict.UNSURE : Verdict.PROBLEM, words.toString().trim()));
        }
        if (heatSamples >= 30) {
            int share = percent(heatOk, heatSamples);
            out.add(new Finding("heat", title("heat"), share >= 95 ? Verdict.WORKS : share >= 80 ? Verdict.UNSURE : Verdict.PROBLEM,
                String.format(Locale.ROOT, "%d%% of %d readings within 4 °C of the target; largest miss %.1f °C at the nozzle, %.1f °C at the bed.", share, heatSamples, worstNozzle, worstBed)));
        }
        if (complete && fileExtruded > 100 && !Double.isNaN(extrudedMm) && extrudedMm > 0) {
            double off = (extrudedMm - fileExtruded) / fileExtruded * 100;
            // The printer's own start routine (its purge line) may feed some more than the file's moves.
            out.add(new Finding("filament", title("filament"), Math.abs(off) <= 5 ? Verdict.WORKS : Math.abs(off) <= 25 ? Verdict.UNSURE : Verdict.PROBLEM,
                String.format(Locale.ROOT, "The printer's extruder position moved %.0f mm; the G-code's moves feed %.0f mm (%+.1f%%). The recording's filament figures come from the printer's count.", extrudedMm, fileExtruded, off)));
        }
        if (complete && fileSeconds > 60 && firstLayerAt >= 0 && endedAt > firstLayerAt) {
            long took = (endedAt - firstLayerAt) / 1000; double off = (took - fileSeconds) * 100.0 / fileSeconds;
            out.add(new Finding("estimate", title("estimate"), Math.abs(off) <= 15 ? Verdict.WORKS : Math.abs(off) <= 30 ? Verdict.UNSURE : Verdict.PROBLEM,
                "The slicer estimated " + span(fileSeconds) + "; printing took " + span(took) + " from the first layer" + String.format(Locale.ROOT, " (%+.0f%%).", off)));
        }
        if (complete && etaAt >= 0 && endedAt > etaAt && etaRemaining > 0) {
            long took = (endedAt - etaAt) / 1000; double off = (took - etaRemaining) * 100.0 / etaRemaining;
            out.add(new Finding("eta", title("eta"), Math.abs(off) <= 10 ? Verdict.WORKS : Math.abs(off) <= 25 ? Verdict.UNSURE : Verdict.PROBLEM,
                "At half way the printer said " + span(etaRemaining) + " left; it took " + span(took) + String.format(Locale.ROOT, " (%+.0f%%). Notifications show the printer's figure.", off)));
        }
        return out;
    }

    /** A recording's answers so far. */
    static List<Finding> of(PrintRecorder.Recording r) {
        boolean sawStart = r.startProgress >= 0 && r.startProgress <= 1;
        return r.checks.findings(r.pictures, r.farPictures, r.compare && r.smooth(), sawStart ? r.extrudedMm : Double.NaN);
    }
    /** "✓", "✕" or "?" before a finding in plain text. */
    static String mark(Verdict v) { return v == Verdict.WORKS ? "✓" : v == Verdict.PROBLEM ? "✕" : "?"; }
    /**
     * The newest answer to each question across recordings (newest first), with the print it came from: what the measured
     * part of the field check shows. {finding, recording} per question that has one.
     */
    static List<Object[]> latest(List<PrintRecorder.Recording> newestFirst) {
        List<Object[]> out = new ArrayList<>();
        for (String[] q : QUESTIONS)
            for (PrintRecorder.Recording r : newestFirst) {
                Finding hit = null; for (Finding f : of(r)) if (f.id.equals(q[0])) hit = f;
                if (hit != null) { out.add(new Object[] {hit, r}); break; }
            }
        return out;
    }

    /** "7 min 41 s", "1 h 05 min". */
    static String span(long seconds) { return seconds >= 3600 ? seconds / 3600 + " h " + String.format(Locale.ROOT, "%02d", seconds % 3600 / 60) + " min" : seconds / 60 + " min " + seconds % 60 + " s"; }
    static String title(String id) { for (String[] q : QUESTIONS) if (q[0].equals(id)) return q[1]; return id; }
    private static int percent(int part, int whole) { return whole == 0 ? 0 : Math.round(100f * part / whole); }

    JSONObject toJson() throws org.json.JSONException {
        return new JSONObject().put("fast", new org.json.JSONArray(new int[] {fastGaps, fastQuick, fastSlow}))
            .put("park", new org.json.JSONArray(new long[] {parkLayers, smoothLayers, lifts, liftsOk, returns, returnsOk, dwellMs, dwells}))
            .put("layers", new org.json.JSONArray(new int[] {maxLayer, fileLayers})).put("positions", new org.json.JSONArray(new int[] {positions, positionsOn}))
            .put("waits", new org.json.JSONArray(new long[] {waits[0], waitSum[0], waitMax[0], waits[1], waitSum[1], waitMax[1]}))
            .put("heat", new org.json.JSONArray(new double[] {heatSamples, heatOk, worstNozzle, worstBed}))
            .put("time", new org.json.JSONArray(new long[] {firstLayerAt, endedAt, fileSeconds, etaAt, etaRemaining, complete ? 1 : 0}))
            .put("file_extruded", fileExtruded);
    }
    static PrintChecks fromJson(JSONObject json) {
        PrintChecks c = new PrintChecks();
        if (json == null) return c;
        org.json.JSONArray f = json.optJSONArray("fast"), p = json.optJSONArray("park"), l = json.optJSONArray("layers"), q = json.optJSONArray("positions"), w = json.optJSONArray("waits");
        if (f != null) { c.fastGaps = f.optInt(0); c.fastQuick = f.optInt(1); c.fastSlow = f.optInt(2); }
        if (p != null) { c.parkLayers = p.optInt(0); c.smoothLayers = p.optInt(1); c.lifts = p.optInt(2); c.liftsOk = p.optInt(3); c.returns = p.optInt(4); c.returnsOk = p.optInt(5); c.dwellMs = p.optLong(6); c.dwells = p.optInt(7); }
        if (l != null) { c.maxLayer = l.optInt(0); c.fileLayers = l.optInt(1); }
        if (q != null) { c.positions = q.optInt(0); c.positionsOn = q.optInt(1); }
        if (w != null) for (int set = 0; set < 2; set++) { c.waits[set] = w.optInt(set * 3); c.waitSum[set] = w.optLong(set * 3 + 1); c.waitMax[set] = w.optLong(set * 3 + 2); }
        org.json.JSONArray h = json.optJSONArray("heat"), t = json.optJSONArray("time");
        if (h != null) { c.heatSamples = h.optInt(0); c.heatOk = h.optInt(1); c.worstNozzle = h.optDouble(2, 0); c.worstBed = h.optDouble(3, 0); }
        if (t != null) { c.firstLayerAt = t.optLong(0, -1); c.endedAt = t.optLong(1, -1); c.fileSeconds = t.optLong(2); c.etaAt = t.optLong(3, -1); c.etaRemaining = t.optLong(4); c.complete = t.optInt(5) == 1; }
        c.fileExtruded = json.optDouble("file_extruded", 0);
        return c;
    }
}
