/* CC2 HTTP behavior derived from ELEGOO's Apache-2.0 adapter; Android port, 2026. */
package io.github.thelastfrogrammer.elink;

import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PrinterHttp {
    public interface Progress { void update(int percentage); }
    public interface ConnectionFactory { HttpURLConnection open(URL url) throws IOException; }
    private final String host, token;
    private final ConnectionFactory connections;
    private volatile HttpURLConnection active;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    public PrinterHttp(String host, String token) {
        this(host, token, url -> (HttpURLConnection) url.openConnection());
    }
    public PrinterHttp(String host, String token, ConnectionFactory connections) {
        // Keep credentials on the local network; do not accept arbitrary URLs, paths or redirects.
        if (!host.matches("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+")) throw new IllegalArgumentException("Enter the printer's IPv4 address");
        String[] octets = host.split("\\.");
        for (String octet : octets) if (Integer.parseInt(octet) > 255) throw new IllegalArgumentException("Invalid IPv4 address");
        int a = Integer.parseInt(octets[0]), b = Integer.parseInt(octets[1]);
        if (!(a == 10 || (a == 172 && b >= 16 && b <= 31) || (a == 192 && b == 168) || (a == 169 && b == 254)))
            throw new IllegalArgumentException("Use a local/private printer address");
        if (token.contains("\r") || token.contains("\n")) throw new IllegalArgumentException("Invalid access code");
        this.host = host; this.token = token.isEmpty() ? "123456" : token; this.connections = connections;
    }
    public String host() { return host; }
    public String token() { return token; }
    public void cancel() { cancelled.set(true); HttpURLConnection connection = active; if (connection != null) connection.disconnect(); }

    /** SDK storage paths; encode each query value separately and never follow redirects. */
    public void download(File destination, String storage, String filename, Progress progress) throws Exception {
        Cc2Codec.storage(storage); Cc2Codec.filename(filename);
        fetch(storage.equals("local") ? "/download" : "/download/udisk", filename, destination, progress, GcodeInspector.MAX_BYTES, true);
    }

    /** Largest timelapse video accepted. */
    static final long MAX_VIDEO_BYTES = 4L * 1024 * 1024 * 1024;

    /**
     * A print's timelapse video, by the time_lapse_video_url its history entry reports, through the same download
     * endpoint Elegoo's own printer page uses for it.
     */
    public void downloadTimelapse(File destination, String videoUrl, Progress progress) throws Exception {
        Cc2Codec.timelapse(videoUrl);
        fetch("/download", videoUrl, destination, progress, MAX_VIDEO_BYTES, false);
    }

    /** Query values encoded with %20 for spaces, as Elegoo's SDK sends them (form-style "+" is not decoded everywhere). */
    static String query(String value) throws UnsupportedEncodingException { return URLEncoder.encode(value, "UTF-8").replace("+", "%20"); }

    private void fetch(String path, String filename, File destination, Progress progress, long maxBytes, boolean gcode) throws Exception {
        HttpURLConnection connection = null; boolean success = false;
        String what = path + " " + filename, report = "";
        long copied = 0;
        try {
            checkCancelled();
            connection = open(path + "?X-Token=" + query(token) + "&file_name=" + query(filename));
            connection.setRequestMethod("GET"); connection.setReadTimeout(30000);
            connection.setRequestProperty("Accept", "application/octet-stream"); connection.setRequestProperty("Accept-Encoding", "identity");
            int code = connection.getResponseCode();
            String type = connection.getContentType(), encoding = connection.getContentEncoding();
            long total = connection.getContentLengthLong();
            report = "HTTP " + code + (type == null ? "" : ", " + type) + (encoding == null ? "" : ", " + encoding) + (total < 0 ? ", length not given" : ", " + total + " bytes");
            if (code == 401 || code == 403) throw new PrinterErrors.Rejected(1000);
            if (code != 200) throw new PrinterErrors.HttpStatus(code);
            // The body decides, not its labels: some firmware labels files oddly. Compressed bodies are unpacked.
            boolean gzip = encoding != null && encoding.toLowerCase(Locale.ROOT).contains("gzip");
            if (encoding != null && !gzip && !encoding.equalsIgnoreCase("identity")) throw new IOException("Printer sent the file in an unsupported encoding (" + encoding + ")");
            if (gzip) total = -1;
            if (total == 0 || total > maxBytes) throw new IOException("File is empty or exceeds " + maxBytes / (1024 * 1024) + " MiB");
            int last = -2; boolean first = true;
            try (InputStream raw = connection.getInputStream(); InputStream input = gzip ? new java.util.zip.GZIPInputStream(raw) : raw;
                 OutputStream output = new BufferedOutputStream(new FileOutputStream(destination))) {
                byte[] buffer = new byte[65536]; int count;
                while ((count = input.read(buffer)) != -1) {
                    checkCancelled(); if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Download cancelled");
                    if (first) for (int i = 0; i < count; i++) if (!Character.isWhitespace((char) (buffer[i] & 255))) {
                        if (errorDocument(buffer, i, count)) throw new IOException("Printer answered with an error instead of the " + (gcode ? "G-code" : "video") + ": " + new String(buffer, i, Math.min(count - i, 160), "UTF-8").trim());
                        first = false; break;
                    }
                    copied += count; if (copied > maxBytes || total > 0 && copied > total) throw new IOException("Download exceeded expected size");
                    output.write(buffer, 0, count);
                    int percent = total > 0 ? (int) Math.min(99, copied * 100 / total) : -1;
                    if (percent != last) { last = percent; progress.update(percent); }
                }
            }
            checkCancelled();
            if (copied == 0 || total > 0 && copied != total) throw new IOException("Download was empty or incomplete (" + copied + " of " + (total < 0 ? "?" : String.valueOf(total)) + " bytes)");
            progress.update(100); success = true;
            Diagnostics.note(Diagnostics.FILES, "downloaded " + what + " · " + report + " · " + copied + " bytes received");
        } catch (Exception failure) {
            Diagnostics.note(Diagnostics.FILES, "download failed " + what + " · " + (report.isEmpty() ? "no response" : report) + " · " + copied + " bytes · " + failure);
            throw failure;
        } finally {
            if (connection != null) connection.disconnect(); active = null;
            if (!success) destination.delete();
        }
    }

    public JSONObject systemInfo() throws Exception {
        HttpURLConnection connection = open("/system/info?X-Token=" + URLEncoder.encode(token, "UTF-8"));
        try {
            JSONObject response = readResponse(connection);
            if (response.optInt("error_code", 0) != 0) throw new PrinterErrors.Rejected(response.optInt("error_code", -1));
            JSONObject info = response.optJSONObject("system_info");
            if (info == null || info.optString("sn").isEmpty()) throw new PrinterErrors.MissingIdentity();
            return info;
        } finally { connection.disconnect(); active = null; }
    }
    public void upload(File file, String filename, Progress progress) throws Exception {
        if (!filename.toLowerCase(Locale.ROOT).endsWith(".gcode") || !filename.matches("[A-Za-z0-9 _.-]+") || filename.length() > 120)
            throw new IllegalArgumentException("Use a simple .gcode filename (letters, numbers, spaces, dash or underscore)");
        long size = file.length();
        if (size == 0) throw new IOException("File is empty");
        MessageDigest md5 = MessageDigest.getInstance("MD5");
        byte[] chunk = new byte[1024 * 1024];
        try (InputStream input = new FileInputStream(file)) {
            int read; while ((read = input.read(chunk)) != -1) { checkCancelled(); md5.update(chunk, 0, read); }
        }
        StringBuilder digest = new StringBuilder();
        for (byte value : md5.digest()) digest.append(String.format(Locale.ROOT, "%02x", value & 255));
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            long offset = 0;
            while (offset < size) {
                checkCancelled();
                int count = (int) Math.min(chunk.length, size - offset), got = 0;
                while (got < count) { int read = input.read(chunk, got, count - got); if (read == -1) throw new EOFException(); got += read; }
                HttpURLConnection connection = open("/upload");
                try {
                    connection.setReadTimeout(180000);
                    connection.setRequestMethod("PUT");
                    connection.setDoOutput(true);
                    connection.setFixedLengthStreamingMode(count);
                    connection.setRequestProperty("Content-Type", "application/octet-stream");
                    connection.setRequestProperty("Content-Range", "bytes " + offset + "-" + (offset + count - 1) + "/" + size);
                    connection.setRequestProperty("X-File-Name", filename);
                    connection.setRequestProperty("X-File-MD5", digest.toString());
                    try (OutputStream output = connection.getOutputStream()) { output.write(chunk, 0, count); }
                    JSONObject response = readResponse(connection);
                    if (response.optInt("error_code", -1) != 0) throw new PrinterErrors.Rejected(response.optInt("error_code", -1));
                } finally { connection.disconnect(); active = null; }
                offset += count;
                progress.update((int) (offset * 100 / size));
            }
        }
    }
    private void checkCancelled() throws IOException { if (cancelled.get()) throw new IOException("Operation cancelled"); }
    /** An error page or JSON error instead of the file: '{' (JSON) or an HTML/XML tag at the start. */
    static boolean errorDocument(byte[] buffer, int start, int end) {
        if (buffer[start] == '{') return true;
        if (buffer[start] != '<') return false;
        String head = new String(buffer, start, Math.min(end - start, 16), java.nio.charset.StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT);
        return head.startsWith("<html") || head.startsWith("<!doctype") || head.startsWith("<?xml") || head.startsWith("<body") || head.startsWith("<head");
    }

    private HttpURLConnection open(String path) throws Exception {
        checkCancelled();
        HttpURLConnection connection = connections.open(new URL("http://" + host + path));
        active = connection;
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(5000); connection.setReadTimeout(8000);
        connection.setRequestProperty("X-Token", token);
        connection.setRequestProperty("Accept", "application/json");
        return connection;
    }
    private JSONObject readResponse(HttpURLConnection connection) throws Exception {
        int code = connection.getResponseCode();
        if (code == 401 || code == 403) throw new PrinterErrors.Rejected(1000);
        if (code != 200) throw new PrinterErrors.HttpStatus(code);
        try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] bytes = new byte[4096]; int count;
            while ((count = input.read(bytes)) != -1) {
                checkCancelled();
                if (output.size() + count > 2 * 1024 * 1024) throw new IOException("Printer response too large");
                output.write(bytes, 0, count);
            }
            return new JSONObject(output.toString(StandardCharsets.UTF_8.name()));
        }
    }

    /** The exception and its causes as "Class: message" pairs, for diagnostics (no addresses beyond what the messages hold). */
    static String causes(Throwable error) {
        StringBuilder text = new StringBuilder();
        for (Throwable t = error; t != null && text.length() < 400; t = t.getCause()) {
            if (text.length() > 0) text.append(" <- ");
            text.append(t.getClass().getSimpleName()).append(t.getMessage() == null ? "" : ": " + t.getMessage().replaceAll("/?\\d+\\.\\d+\\.\\d+\\.\\d+", "<printer>"));
            if (t.getCause() == t) break;
        }
        return text.toString();
    }

    /**
     * Whether each TCP port on the printer accepts a connection: opened and closed at once, nothing sent. Distinguishes a
     * closed port ("refused") from a phone that cannot reach the printer at all ("unreachable" or "timeout").
     */
    static String probePorts(javax.net.SocketFactory sockets, String host, int[] ports) {
        StringBuilder text = new StringBuilder();
        for (int port : ports) {
            String result; long started = System.nanoTime();
            try (java.net.Socket socket = sockets.createSocket()) {
                socket.connect(new java.net.InetSocketAddress(host, port), 2000);
                result = "open";
            } catch (java.net.SocketTimeoutException timeout) { result = "timeout";
            } catch (java.net.ConnectException refused) {
                String message = String.valueOf(refused.getMessage());
                result = message.contains("ECONNREFUSED") || message.contains("refused") ? "refused" : message.contains("EHOSTUNREACH") || message.contains("ENETUNREACH") ? "unreachable" : "failed";
            } catch (Exception error) { result = error.getClass().getSimpleName(); }
            if (text.length() > 0) text.append(", ");
            text.append(port).append(' ').append(result).append(" (").append((System.nanoTime() - started) / 1_000_000).append(" ms)");
        }
        return text.toString();
    }
}
