package io.github.thelastfrogrammer.elink;

import org.json.JSONException;
import org.json.JSONObject;

/** Reconstruct from live cloud reports only. Snapshots can end a run, but cannot supply probe points. */
final class CloudBedMesh {
    static final String SOURCE = "Elegoo cloud live";
    static final long SILENCE_MS = 45_000;
    private static final int CC2_POINTS_PER_AXIS = 11;
    private BedMesh.Capture capture = new BedMesh.Capture();
    private String printer = "", moveKey = "";
    private int phase = -1;
    private double x = Double.NaN, y = Double.NaN;
    private long lastLiveAt;
    String outcome = "";
    /**
     * The last run in counts, for the log: live updates during probing, those with a position, probe touches seen, the
     * places touched, and the longest wait between updates. No coordinates. Whether the cloud forwards the printer's
     * positions often enough to rebuild a grid is the open question this answers.
     */
    String stats = "";
    private int updates, positions, touches; private long lastUpdateAt, longestGapMs;
    private final java.util.Set<String> touched = new java.util.HashSet<>();

    boolean active() { return capture.active(); }
    long remaining(long now) { return Math.max(0, Math.min(SILENCE_MS, SILENCE_MS - (now - lastLiveAt))); }
    String message() {
        if (active()) return "Cloud leveling in progress · " + capture.points() + " distinct position reports received.";
        return outcome;
    }

    void reset() { stop(""); printer = ""; outcome = ""; stats = ""; }
    void interrupted() { stop("Cloud updates paused during leveling. Run Auto-level again while cloud live updates are connected."); }
    private void stop(String why) {
        if (active()) { outcome = why; stats = counts(); }
        capture = new BedMesh.Capture(); phase = -1; lastLiveAt = 0; clearPosition();
    }
    private void clearPosition() { x = y = Double.NaN; moveKey = ""; }
    /** Where the head stands (X and Y only) from the merged live status, when it reports a position. */
    private void seed(JSONObject known) {
        JSONObject at = known == null ? null : Cc2Codec.position(known);
        if (at == null) return;
        x = at.optDouble("x", Double.NaN); y = at.optDouble("y", Double.NaN);
    }

    private String counts() {
        return String.format(java.util.Locale.ROOT, "%d live updates while probing, %d with a position, %d probe touches at %d places, longest wait %.1f s",
            updates, positions, touches, touched.size(), longestGapMs / 1000.0);
    }
    private void startCounting(long now) {
        updates = positions = touches = 0; longestGapMs = 0; lastUpdateAt = now; touched.clear(); stats = "";
    }

    BedMesh.Mesh delta(String serial, JSONObject raw, long now) throws JSONException { return delta(serial, raw, null, now); }

    /**
     * One live update. `known` is the merged live status including this update: live updates carry only the fields that
     * changed, so where the head already stands when probing begins is known only from it. A probing run starts from the
     * X and Y it holds (an axis that never changes along a row is otherwise never reported); a Z is only ever taken from a
     * live update, never from the merged status.
     */
    BedMesh.Mesh delta(String serial, JSONObject raw, JSONObject known, long now) throws JSONException {
        if (!serial.equals(printer)) { reset(); printer = serial; }
        if (active() && remaining(now) == 0) interrupted();
        JSONObject machine = raw.optJSONObject("machine_status");
        int sub = machine == null ? -1 : machine.optInt("sub_status", -1);
        int state = machine == null ? -1 : machine.optInt("status", -1);
        if (sub == BedMesh.LEVELLED || (sub >= 0 && sub != BedMesh.PROBING)
                || (state >= 0 && state != 5 && state != 2)) return finish(now);
        if (sub == BedMesh.PROBING && phase != sub) { clearPosition(); seed(known); phase = sub; outcome = ""; startCounting(now); }
        if (phase != BedMesh.PROBING) return null;
        updates++; longestGapMs = Math.max(longestGapMs, now - lastUpdateAt); lastUpdateAt = now;

        JSONObject context = new JSONObject().put("machine_status", new JSONObject()
            .put("status", state < 0 ? 2 : state).put("sub_status", BedMesh.PROBING));
        // The bed's temperature and the print being started are state, not probe readings: from this update, else from the
        // merged live status (an update carries them only when they change), so a cloud mesh is kept with its temperature.
        for (String key : new String[]{"heater_bed", "print_status"}) {
            JSONObject v = raw.optJSONObject(key);
            if (v == null && known != null) v = known.optJSONObject(key);
            if (v != null) context.put(key, v);
        }

        String key = raw.optJSONObject("gcode_move") != null ? "gcode_move" : "gcode_move_inf";
        JSONObject p = raw.optJSONObject(key);
        if (p != null) {
            if (!moveKey.isEmpty() && !key.equals(moveKey)) clearPosition();   // another report of the position: start over
            moveKey = key;
            if (p.has("x")) x = p.optDouble("x", Double.NaN);
            if (p.has("y")) y = p.optDouble("y", Double.NaN);
            double z = p.optDouble("z", Double.NaN); // Never carry a touch forward to the next X/Y.
            positions++;
            if (Double.isFinite(z) && Math.abs(z) < BedMesh.CONTACT_MM) {
                touches++;
                if (Double.isFinite(x) && Double.isFinite(y)) touched.add(Math.round(x) + "," + Math.round(y));
            }
            if (Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z))
                context.put("gcode_move", new JSONObject().put("x", x).put("y", y).put("z", z));
        }
        if (sub == BedMesh.PROBING || p != null) lastLiveAt = now;
        capture.feed(context, now, SOURCE, CC2_POINTS_PER_AXIS * CC2_POINTS_PER_AXIS);
        return null;
    }

    BedMesh.Mesh snapshot(String serial, CloudApi.Snapshot report, long now) throws JSONException {
        if (!active() || !printer.equals(serial)) return null;
        if (remaining(now) == 0) { interrupted(); return null; }
        JSONObject machine = report.status.optJSONObject("machine_status");
        if (machine == null) return null;
        int sub = machine.optInt("sub_status", -1), state = machine.optInt("status", -1);
        // Per-field timestamps matter: a fresh temperature must not make a cached idle status look new.
        long phaseAt = CloudApi.seconds(report.machinePhaseAt) * 1000;
        long stateAt = CloudApi.seconds(report.machineStateAt) * 1000;
        if (phaseAt > lastLiveAt && phaseAt <= now && phaseAt >= stateAt && sub >= 0)
            return sub == BedMesh.PROBING ? null : finish(now);
        if (stateAt > lastLiveAt && stateAt <= now && state >= 0 && state != 2 && state != 5) return finish(now);
        return null;
    }

    private BedMesh.Mesh finish(long now) throws JSONException {
        if (!active()) return null;
        stats = counts();
        // Treat 2902 as the end of probing; any final positioning isn't another measurement.
        BedMesh.Mesh mesh = capture.feed(new JSONObject().put("machine_status", new JSONObject().put("status", 1)),
            now, SOURCE, CC2_POINTS_PER_AXIS * CC2_POINTS_PER_AXIS);
        stop("");
        if (mesh == null || mesh.xs.length != CC2_POINTS_PER_AXIS || mesh.ys.length != CC2_POINTS_PER_AXIS) {
            outcome = "No cloud mesh saved: not a complete 11 × 11 probe grid (" + stats + "). Try LAN if another run has the same result.";
            return null;
        }
        outcome = "Cloud bed mesh captured · " + mesh.count() + " probe points.";
        mesh.printer = PrintRecorder.digest(printer);
        return mesh;
    }
}
