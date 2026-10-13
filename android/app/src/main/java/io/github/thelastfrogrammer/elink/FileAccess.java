package io.github.thelastfrogrammer.elink;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Why a printer file cannot be downloaded, from read-only evidence: whether the printer answers on this Wi-Fi (UDP
 * discovery), the mode it reports (cloud or LAN Only), its state, and which of its TCP ports accept a connection (opened
 * and closed, nothing sent). Every evidenced way to fetch a CC2's G-code — Elegoo's own LAN and cloud printer pages
 * included — is a GET to the printer's port 80, so the verdict is about that port. Independent of Android.
 */
final class FileAccess {
    /** The file server, the camera, local MQTT, two other services the printer is known to run, and 21 (seen open on a CC2 in a community report). */
    static final int[] PORTS = {80, 8080, 1883, 3030, 9001, 21};

    static final class Evidence {
        boolean wifi = true, found, savedCode;
        Boolean lanOnly, codeProtected;
        /** The printer's state in words ("printing", "idle", …), or "". */
        String state = "";
        final Map<Integer, String> ports = new LinkedHashMap<>();
        String port(int port) { String result = ports.get(port); return result == null ? "" : result; }
    }

    private static final Pattern PORT = Pattern.compile("(\\d+) (\\w+) \\(");
    /** Reads PrinterHttp.probePorts' "80 open (12 ms), 1883 refused (3 ms)". */
    static Map<Integer, String> parse(String probe) {
        Map<Integer, String> out = new LinkedHashMap<>();
        Matcher m = PORT.matcher(probe == null ? "" : probe);
        while (m.find()) out.put(Integer.parseInt(m.group(1)), m.group(2));
        return out;
    }

    static boolean open(Evidence e) { return "open".equals(e.port(80)); }

    /** What the evidence says, and what to try, in plain words. */
    static String verdict(Evidence e) {
        if (!e.wifi) return "The phone is not on Wi-Fi. Files come from the printer itself, so the phone must be on the printer's network.";
        if (!e.found) return "The printer did not answer on this Wi-Fi. Files come from the printer itself, so the phone must be on the same network as it (and the router must let devices see each other).";
        String mode = Boolean.FALSE.equals(e.lanOnly) ? "cloud mode" : Boolean.TRUE.equals(e.lanOnly) ? "LAN Only mode" : "an unknown mode";
        boolean printing = e.state.toLowerCase(java.util.Locale.ROOT).startsWith("print");
        String others = "open".equals(e.port(8080)) || "open".equals(e.port(1883))
            ? " Its " + ("open".equals(e.port(8080)) ? "camera port" : "local MQTT port") + " answers, so the network path to the printer works." : "";
        if (open(e)) {
            String code = Boolean.TRUE.equals(e.codeProtected) && !e.savedCode ? " It is protected by an access code that this phone has not saved: connect once locally with “Remember access code” on." : "";
            return "The printer's file server (port 80) is open in " + mode + (printing ? " while it prints" : "") + ": downloads should work now." + code;
        }
        String port = e.port(80);
        if ("refused".equals(port)) {
            StringBuilder out = new StringBuilder("The printer is on this Wi-Fi in " + mode + ", but its file server (port 80) refuses connections" + (printing ? " while it prints" : "") + "." + others);
            if (Boolean.FALSE.equals(e.lanOnly)) out.append(" This printer has kept it closed in cloud mode both while printing and while idle. Turning on LAN Only on the printer's screen should open it: "
                + "tap “Download when it opens” first, switch LAN Only on, and the app downloads as soon as the server answers; then switch LAN Only off again.");
            else if (printing) out.append(" It may open once the print ends: “Download when it opens” keeps checking and downloads then.");
            return out.toString();
        }
        if ("timeout".equals(port) || "unreachable".equals(port))
            return "The printer answered on this Wi-Fi, but its file server (port 80) does not (" + port + ")." + others + " A router setting can block it, or the printer has its file server off.";
        return "The printer answered on this Wi-Fi; its file server (port 80) could not be checked (" + (port.isEmpty() ? "no result" : port) + ").";
    }

    /** The evidence as one diagnostics line, without addresses, serials or codes. */
    static String report(Evidence e) {
        StringBuilder ports = new StringBuilder();
        for (Map.Entry<Integer, String> p : e.ports.entrySet()) ports.append(ports.length() > 0 ? ", " : "").append(p.getKey()).append(' ').append(p.getValue());
        return "file access: wifi " + e.wifi + ", found " + e.found + ", mode " + (e.lanOnly == null ? "unknown" : e.lanOnly ? "LAN Only" : "cloud")
            + ", code " + (e.codeProtected == null ? "unknown" : e.codeProtected ? "on" : "off") + ", saved code " + e.savedCode
            + ", state " + (e.state.isEmpty() ? "unknown" : e.state) + ", ports " + (ports.length() == 0 ? "not checked" : ports);
    }
}
