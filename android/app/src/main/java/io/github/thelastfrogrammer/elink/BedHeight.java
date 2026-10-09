package io.github.thelastfrogrammer.elink;

import org.json.JSONObject;

/**
 * How far the bed is from its top position, as far as it can be trusted for lining up the camera. The printer reports a Z
 * (gcode_move.z) even when Z is not homed, and then it can be stale; after a move the reported Z only changes with the next
 * status, which through the cloud can take tens of seconds. So: the height is known only while Z is homed, and after a move
 * it is "moving" until two status reports newer than the move agree on it. Independent of Android.
 */
final class BedHeight {
    enum State { UNKNOWN, MOVING, UNCONFIRMED, KNOWN }
    static final long GIVE_UP_MS = 90_000;
    static final double SAME_MM = 0.05;
    /** Reports this soon after the move may still show where the bed was before the printer acted on it. */
    static final long LAG_MS = 3_000;

    private double z = Double.NaN;       // last height reported while homed
    private boolean homed;
    private Object lastReport;           // the status object last read: a new report is a new object
    private long reportAt, moveAt;
    private double target = Double.NaN, previous = Double.NaN;
    private int agreeing;                // reports since the move (and past the lag) that agree with the one before
    private boolean reached;

    /** Reads a status the printer reported; call with every status (the same object again is ignored). */
    void report(JSONObject status, long now) {
        if (status == null || status == lastReport) return;
        lastReport = status; reportAt = now;
        homed = Cc2Codec.homed(status, "z");
        JSONObject position = Cc2Codec.position(status);
        double reported = position == null ? Double.NaN : position.optDouble("z", Double.NaN);
        if (Double.isNaN(reported) || reported < -5 || reported > 400) return;
        if (homed) z = reported;
        if (moveAt > 0) {
            if (!Double.isNaN(target) && Math.abs(reported - target) < 0.5) reached = true;
            if (now - moveAt >= LAG_MS || reached) {
                agreeing = !Double.isNaN(previous) && Math.abs(reported - previous) < SAME_MM ? agreeing + 1 : 0;
                previous = reported;
            }
        }
    }

    /**
     * A move of `distance` mm was sent (0 for homing Z). The new height counts once two reports agree on it, and for a move
     * once the bed is near where it was sent (or, if it never gets there, two agreeing reports well after the move).
     */
    void moveSent(double distance, long now) {
        moveAt = now; previous = Double.NaN; agreeing = 0; reached = false;
        target = distance == 0 || Double.isNaN(z) ? Double.NaN : z + distance;
    }

    State state(long now) {
        if (!homed || Double.isNaN(z)) return State.UNKNOWN;
        if (moveAt > 0) {
            if (agreeing >= 1 && (reached || Double.isNaN(target) || now - moveAt > 3 * LAG_MS)) moveAt = 0;   // it has stopped
            else return now - moveAt > GIVE_UP_MS ? State.UNCONFIRMED : State.MOVING;
        }
        return State.KNOWN;
    }

    /** The last trusted height (NaN if Z was never homed while watching). */
    double z() { return z; }
    boolean homed() { return homed; }
    /** How old the last status report is, in ms (or -1 if none yet). */
    long age(long now) { return reportAt == 0 ? -1 : now - reportAt; }

    /** A line for the line-up panel. */
    String describe(long now) {
        switch (state(now)) {
            case UNKNOWN: return Double.isNaN(z) ? "Bed height unknown: Z is not homed. Press Home Z (Other bed heights) before tapping or saving."
                : String.format(java.util.Locale.getDefault(), "Z is not homed, so the bed's height (last seen Z %.1f mm) is not confirmed. Press Home Z before tapping or saving.", z);
            case MOVING: return "Bed moving… waiting for the printer to report where it stopped.";
            case UNCONFIRMED: return "The bed's new height has not been confirmed by the printer. Refresh status (Monitor) and wait, or move it again.";
            default: return String.format(java.util.Locale.getDefault(), "The bed is at Z %.1f mm (homed).", z);
        }
    }
}
