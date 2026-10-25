package io.github.thelastfrogrammer.elink.tv;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * The pictures in a camera stream (multipart/x-mixed-replace, one JPEG per part). Each part's Content-Length is used
 * when it has one: a JPEG can hold a smaller JPEG (an EXIF thumbnail) whose end marker would cut a scan short. Without a
 * length, a part runs from the FF D8 start marker to the FF D9 end marker. Pure Java.
 */
final class MjpegReader {
    static final int MAX_FRAME = 8 << 20, MAX_HEADER_LINE = 4096, MAX_HEADER_LINES = 64;

    private final InputStream in;

    MjpegReader(InputStream in) { this.in = in instanceof BufferedInputStream ? in : new BufferedInputStream(in, 1 << 16); }

    /** The next picture's JPEG bytes; EOFException when the stream ends. */
    byte[] next() throws IOException {
        int length = -1, lines = 0; boolean inPart = false;
        while (true) {
            String l = headerLine();
            if (l.isEmpty()) { if (inPart) break; continue; }   // the blank line after a part, or after its headers
            if (++lines > MAX_HEADER_LINES) throw new IOException("Not a camera stream");
            if (l.startsWith("--")) { if (l.endsWith("--") && l.length() > 4) throw new EOFException("The camera stream ended"); inPart = true; continue; }
            int colon = l.indexOf(':');
            if (colon <= 0) throw new IOException("Not a camera stream");
            inPart = true;
            if (l.substring(0, colon).trim().toLowerCase(Locale.ROOT).equals("content-length")) {
                try { length = Integer.parseInt(l.substring(colon + 1).trim()); } catch (NumberFormatException bad) { length = -1; }
            }
        }
        if (length > MAX_FRAME) throw new IOException("A picture was too large");
        return length > 0 ? exactly(length) : scan();
    }

    private String headerLine() throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream(64);
        while (true) {
            int b = in.read();
            if (b == -1) throw new EOFException("The camera stream ended");
            if (b == '\n') break;
            if (b != '\r') line.write(b);
            if (line.size() > MAX_HEADER_LINE) throw new IOException("Not a camera stream");
        }
        return new String(line.toByteArray(), StandardCharsets.ISO_8859_1).trim();
    }

    private byte[] exactly(int length) throws IOException {
        byte[] jpeg = new byte[length]; int got = 0;
        while (got < length) { int n = in.read(jpeg, got, length - got); if (n < 0) throw new EOFException("The camera stream ended"); got += n; }
        return jpeg;
    }

    /** From FF D8 to FF D9, whatever comes before. */
    private byte[] scan() throws IOException {
        ByteArrayOutputStream frame = null; int previous = -1, b;
        while ((b = in.read()) != -1) {
            if (frame == null) { if (previous == 0xff && b == 0xd8) { frame = new ByteArrayOutputStream(1 << 16); frame.write(0xff); frame.write(0xd8); } }
            else {
                frame.write(b);
                if (previous == 0xff && b == 0xd9) return frame.toByteArray();
                if (frame.size() > MAX_FRAME) throw new IOException("A picture was too large");
            }
            previous = b;
        }
        throw new EOFException("The camera stream ended");
    }
}
