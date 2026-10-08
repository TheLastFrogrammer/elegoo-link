package io.github.thelastfrogrammer.elink;

import org.json.JSONObject;

/** Why the Monitor controls can or cannot be used right now, so the screen can say so instead of showing dead buttons. */
final class ControlState {
    private ControlState() { }
    enum Block { NONE, PIN_PROBE, STALE, CONNECTING, CLOUD_AGREEMENT, CLOUD_BUSY, CLOUD_WAITING, CLOUD_OFFLINE, DISCONNECTED }

    /**
     * NONE exactly when PrinterService would accept a command: a fresh local session that is not the read-only PIN probe, or fresh cloud status
     * after the one-time cloud-control agreement with no cloud command in flight. Anything else names what is missing.
     */
    static Block block(boolean ready, boolean fresh, boolean pinProbe, boolean connecting, boolean cloud, boolean cloudFresh, boolean cloudOffline, boolean cloudOk, boolean cloudBusy) {
        if (ready) return pinProbe ? Block.PIN_PROBE : fresh ? Block.NONE : Block.STALE;
        if (connecting) return Block.CONNECTING;
        if (cloud) return cloudFresh ? (!cloudOk ? Block.CLOUD_AGREEMENT : cloudBusy ? Block.CLOUD_BUSY : Block.NONE) : cloudOffline ? Block.CLOUD_OFFLINE : Block.CLOUD_WAITING;
        return Block.DISCONNECTED;
    }

    /** Empty when maintenance can be started (idle, no faults); otherwise the reason. Only meaningful once controls are available. */
    static String upkeepReason(JSONObject snapshot) {
        if (!StatusPresentation.faultCodes(snapshot).isEmpty()) return "The printer reports a fault. Clear it on the printer before maintenance.";
        if (!Cc2Codec.idle(snapshot)) return "Maintenance needs an idle printer: wait for the print to finish, or stop it.";
        return "";
    }

    /** One line saying which print settings apply in the current state. */
    static String tuningNote(JSONObject snapshot) {
        if (Cc2Codec.idle(snapshot)) return "Speed modes apply during a print.";
        if (Cc2Codec.canPause(snapshot)) return "Temperature targets can only be set while the printer is idle.";
        return "Temperature targets need an idle printer; speed modes need a running print.";
    }
}
