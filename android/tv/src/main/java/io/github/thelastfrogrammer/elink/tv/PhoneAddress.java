package io.github.thelastfrogrammer.elink.tv;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.Locale;

/**
 * The phone's base address ("http://192.168.1.20:8765"), from what a person types or what discovery finds, and back to
 * the short form a screen shows ("192.168.1.20:8765"). Pure Java.
 */
final class PhoneAddress {
    static final int DEFAULT_PORT = 8765;

    /** What was typed, checked: the base address, or the words saying what is wrong. */
    static final class Parsed {
        final String base, problem;
        private Parsed(String base, String problem) { this.base = base; this.problem = problem; }
    }

    /**
     * Reads a typed address and port. The address may carry its own port or the whole link the phone shows
     * ("http://192.168.1.20:8765/"); an empty port means 8765.
     */
    static Parsed parse(String typedHost, String typedPort) {
        String host = typedHost == null ? "" : typedHost.trim(), port = typedPort == null ? "" : typedPort.trim();
        host = host.replaceFirst("(?i)^https?://", "");
        int slash = host.indexOf('/'); if (slash >= 0) host = host.substring(0, slash);
        if (host.isEmpty()) return new Parsed(null, "Enter the phone's address, like 192.168.1.20.");
        if (host.startsWith("[")) {
            int end = host.indexOf(']');
            if (end < 0) return new Parsed(null, "That address is not complete.");
            String rest = host.substring(end + 1); host = host.substring(1, end);
            if (rest.startsWith(":") && port.isEmpty()) port = rest.substring(1); else if (!rest.isEmpty() && !rest.startsWith(":")) return new Parsed(null, "That address is not complete.");
        } else if (host.indexOf(':') >= 0 && host.indexOf(':') == host.lastIndexOf(':')) {
            int colon = host.indexOf(':');
            if (port.isEmpty() || port.equals(String.valueOf(DEFAULT_PORT))) port = host.substring(colon + 1);
            host = host.substring(0, colon);
        }
        int p = DEFAULT_PORT;
        if (!port.isEmpty()) {
            try { p = Integer.parseInt(port); } catch (NumberFormatException bad) { p = -1; }
            if (p < 1 || p > 65535) return new Parsed(null, "The port is a number from 1 to 65535 (usually 8765).");
        }
        if (host.indexOf(':') >= 0) {
            if (!host.matches("[0-9A-Fa-f:.]+(%[0-9A-Za-z_.-]+)?")) return new Parsed(null, "That is not an address the TV can use.");
            return new Parsed("http://[" + host.replace("%25", "%").replace("%", "%25") + "]:" + p, null);
        }
        if (host.matches("[0-9.]+")) {
            String[] parts = host.split("\\.", -1);
            if (parts.length != 4) return new Parsed(null, "An address has four numbers with dots between, like 192.168.1.20.");
            StringBuilder clean = new StringBuilder();
            for (String part : parts) {
                if (part.isEmpty() || part.length() > 3 || Integer.parseInt(part) > 255) return new Parsed(null, "Each number in the address is from 0 to 255, like 192.168.1.20.");
                clean.append(clean.length() > 0 ? "." : "").append(Integer.parseInt(part));   // "010" is 10, never octal
            }
            return new Parsed("http://" + clean + ":" + p, null);
        }
        if (!host.matches("[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)*")) return new Parsed(null, "That is not an address the TV can use.");
        return new Parsed("http://" + host.toLowerCase(Locale.ROOT) + ":" + p, null);
    }

    /** The base address for a discovered phone. */
    static String base(InetAddress address, int port) {
        String host = address.getHostAddress();
        if (address instanceof Inet6Address) {
            int scope = host.indexOf('%');   // a link-local address names its network: "%25" in a link
            host = "[" + (scope < 0 ? host : host.substring(0, scope) + "%25" + host.substring(scope + 1)) + "]";
        }
        return "http://" + host + ":" + port;
    }

    /** The short form a screen shows: "192.168.1.20:8765". */
    static String shown(String base) {
        if (base == null) return "";
        return base.replaceFirst("^https?://", "").replace("%25", "%");
    }
    /** The host part alone, for the address field ("192.168.1.20", "fe80::1%wlan0"). */
    static String host(String base) {
        String s = shown(base); int colon = s.lastIndexOf(':');
        if (s.startsWith("[")) { int end = s.indexOf(']'); return end > 0 ? s.substring(1, end) : s; }
        return colon > 0 ? s.substring(0, colon) : s;
    }
    static String port(String base) {
        String s = shown(base); int colon = s.lastIndexOf(':'), end = s.lastIndexOf(']');
        return colon > end ? s.substring(colon + 1) : String.valueOf(DEFAULT_PORT);
    }

    private PhoneAddress() { }
}
