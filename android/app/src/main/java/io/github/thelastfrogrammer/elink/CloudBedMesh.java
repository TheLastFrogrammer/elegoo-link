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

    boolean active() { return capture.active(); }
    long remaining(long now) { return Math.max(0, Math.min(SILENCE_MS, SILENCE_MS - (now - lastLiveAt))); }
    String message() {
        if (active()) return "Cloud leveling in progress · " + capture.points() + " distinct position reports received.";
        return outcome;
    }

    void reset() { stop(""); printer = ""; outcome = ""; }
    void interrupted() { stop("Cloud updates paused during leveling. Run Auto-level again while cloud live updates are connected."); }
    private void stop(String why) {
        if (active()) outcome = why;
        capture = new BedMesh.Capture(); phase = -1; lastLiveAt = 0; clearPosition();
    }
    private void clearPosition() { x = y = Double.NaN; moveKey = ""; }

    BedMesh.Mesh delta(String serial, JSONObject raw, long now) throws JSONException {
        if (!serial.equals(printer)) { reset(); printer = serial; }
        if (active() && remaining(now) == 0) interrupted();
        JSONObject machine = raw.optJSONObject("machine_status");
        int sub = machine == null ? -1 : machine.optInt("sub_status", -1);
        int state = machine == null ? -1 : machine.optInt("status", -1);
        if (sub == BedMesh.LEVELLED || (sub >= 0 && sub != BedMesh.PROBING)
                || (state >= 0 && state != 5 && state != 2)) return finish(now);
        if (sub == BedMesh.PROBING && phase != sub) { clearPosition(); phase = sub; outcome = ""; }
        if (phase != BedMesh.PROBING) return null;

        JSONObject context = new JSONObject().put("machine_status", new JSONObject()
            .put("status", state < 0 ? 2 : state).put("sub_status", BedMesh.PROBING));
        for (String key : new String[]{"heater_bed", "print_status"}) {
            JSONObject v = raw.optJSONObject(key); if (v != null) context.put(key, v);
        }

        String key = raw.optJSONObject("gcode_move") != null ? "gcode_move" : "gcode_move_inf";
        JSONObject p = raw.optJSONObject(key);
        if (p != null) {
            if (!key.equals(moveKey)) { clearPosition(); moveKey = key; }
            if (p.has("x")) x = p.optDouble("x", Double.NaN);
            if (p.has("y")) y = p.optDouble("y", Double.NaN);
            double z = p.optDouble("z", Double.NaN); // Never carry a touch forward to the next X/Y.
            if (Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z))
                context.put("gcode_move", new JSONObject().put("x", x).put("y", y).put("z", z));
        }
        if (sub == BedMesh.PROBING || p != null) lastLiveAt = now;
        capture.feed(context, now, SOURCE, CC2_POINTS_PER_AXIS * CC2_POINTS_PER_AXIS);
        return null;
    }

    BedMesh.Mesh snapshot(String serial, JSONObject status, long reportedAtMs, long now) throws JSONException {
        // The cloud status endpoint can return old data. A snapshot cannot start or contribute to a capture.
        if (!active() || !printer.equals(serial) || reportedAtMs <= lastLiveAt || reportedAtMs > now) return null;
        if (remaining(now) == 0) { interrupted(); return null; }
        JSONObject machine = status.optJSONObject("machine_status");
        if (machine == null) return null;
        int sub = machine.optInt("sub_status", -1), state = machine.optInt("status", -1);
        if (sub == BedMesh.PROBING || (sub < 0 && (state == 2 || state == 5 || state < 0))) return null;
        return finish(now);
    }

    private BedMesh.Mesh finish(long now) throws JSONException {
        if (!active()) return null;
        int reports = capture.points();
        // Treat 2902 as the end of probing; any final positioning isn't another measurement.
        BedMesh.Mesh mesh = capture.feed(new JSONObject().put("machine_status", new JSONObject().put("status", 1)),
            now, SOURCE, CC2_POINTS_PER_AXIS * CC2_POINTS_PER_AXIS);
        stop("");
        if (mesh == null || mesh.xs.length != CC2_POINTS_PER_AXIS || mesh.ys.length != CC2_POINTS_PER_AXIS) {
            outcome = "No cloud mesh saved: received " + reports + " distinct position reports, but not a complete 11 × 11 probe grid. Try LAN if another run has the same result.";
            return null;
        }
        outcome = "Cloud bed mesh captured · " + mesh.count() + " probe points.";
        mesh.printer = PrintRecorder.digest(printer);
        return mesh;
    }
}
