package io.github.thelastfrogrammer.elink.tv;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Server-Sent Events, as the phone sends them on /api/v1/events: "event:" names a message, "data:" lines join with
 * newlines, a blank line ends it. Comments (": ping") and other fields are skipped; "retry:" is remembered. Lines end in
 * \n, \r\n or \r (the WHATWG rules). Sizes are capped, so a broken stream cannot fill the TV's memory. Pure Java.
 */
final class SseParser {
    static final int MAX_LINE = 1 << 20, MAX_MESSAGE = 2 << 20;

    interface Sink { void message(String event, String data) throws IOException; }

    private final InputStream in;
    private final ByteArrayOutputStream line = new ByteArrayOutputStream(512);
    private final StringBuilder data = new StringBuilder();
    private String event = "";
    private boolean hasData, afterCr;
    private long retryMs = -1;

    SseParser(InputStream in) { this.in = in; }

    /** Reads until the next message and hands it to the sink; EOFException when the stream ends. Comments are skipped. */
    void next(Sink sink) throws IOException {
        while (true) {
            String l = readLine();
            if (l == null) throw new EOFException("The event stream ended");
            if (line(l, sink)) return;
        }
    }

    /** One line; true when it ended a message the sink was given. */
    boolean line(String l, Sink sink) throws IOException {
        if (l.isEmpty()) {
            boolean had = hasData; String e = event.isEmpty() ? "message" : event, d = data.toString();
            event = ""; data.setLength(0); hasData = false;
            if (had) sink.message(e, d);
            return had;
        }
        if (l.charAt(0) == ':') return false;
        int colon = l.indexOf(':');
        String field = colon < 0 ? l : l.substring(0, colon), value = colon < 0 ? "" : l.substring(colon + 1);
        if (value.startsWith(" ")) value = value.substring(1);
        switch (field) {
            case "event": event = value; break;
            case "data":
                if (hasData) data.append('\n');
                data.append(value); hasData = true;
                if (data.length() > MAX_MESSAGE) throw new IOException("An event was too large");
                break;
            case "retry": try { retryMs = Long.parseLong(value); } catch (NumberFormatException ignored) { } break;
            default: break;   // "id" and unknown fields: nothing the TV needs
        }
        return false;
    }

    /** The phone's suggested reconnection delay (ms), -1 if it gave none. */
    long retryMs() { return retryMs; }

    /** The next line without its ending, null at the end of the stream. */
    String readLine() throws IOException {
        line.reset();
        while (true) {
            int b = in.read();
            if (b == -1) { if (line.size() == 0) return null; break; }
            if (afterCr) { afterCr = false; if (b == '\n') continue; }
            if (b == '\r') { afterCr = true; break; }
            if (b == '\n') break;
            line.write(b);
            if (line.size() > MAX_LINE) throw new IOException("An event line was too long");
        }
        String s = new String(line.toByteArray(), StandardCharsets.UTF_8);
        return !s.isEmpty() && s.charAt(0) == 0xFEFF ? s.substring(1) : s;   // a byte order mark at the start of the stream
    }
}
