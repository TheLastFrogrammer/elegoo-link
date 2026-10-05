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
    private final String host, token;
    private volatile HttpURLConnection active;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    public PrinterHttp(String host, String token) {
        // Keep credentials on the local network; do not accept arbitrary URLs, paths or redirects.
        if (!host.matches("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+")) throw new IllegalArgumentException("Enter the printer's IPv4 address");
        String[] octets = host.split("\\.");
        for (String octet : octets) if (Integer.parseInt(octet) > 255) throw new IllegalArgumentException("Invalid IPv4 address");
        int a = Integer.parseInt(octets[0]), b = Integer.parseInt(octets[1]);
        if (!(a == 10 || (a == 172 && b >= 16 && b <= 31) || (a == 192 && b == 168) || (a == 169 && b == 254)))
            throw new IllegalArgumentException("Use a local/private printer address");
        if (token.contains("\r") || token.contains("\n")) throw new IllegalArgumentException("Invalid access code");
        this.host = host; this.token = token.isEmpty() ? "123456" : token;
    }
    public String host() { return host; }
    public String token() { return token; }
    public void cancel() { cancelled.set(true); HttpURLConnection connection = active; if (connection != null) connection.disconnect(); }

    public JSONObject systemInfo() throws Exception {
        HttpURLConnection connection = open("/system/info?X-Token=" + URLEncoder.encode(token, "UTF-8"));
        try {
            JSONObject response = readResponse(connection);
            if (response.optInt("error_code", 0) != 0) throw new IOException("Printer rejected system information request");
            JSONObject info = response.optJSONObject("system_info");
            if (info == null || info.optString("sn").isEmpty()) throw new IOException("Printer did not return a serial number");
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
                    if (response.optInt("error_code", -1) != 0) throw new IOException("Upload rejected (code " + response.optInt("error_code", -1) + ")");
                } finally { connection.disconnect(); active = null; }
                offset += count;
                progress.update((int) (offset * 100 / size));
            }
        }
    }
    private void checkCancelled() throws IOException { if (cancelled.get()) throw new IOException("Operation cancelled"); }
    private HttpURLConnection open(String path) throws Exception {
        checkCancelled();
        HttpURLConnection connection = (HttpURLConnection) new URL("http://" + host + path).openConnection();
        active = connection;
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(5000); connection.setReadTimeout(8000);
        connection.setRequestProperty("X-Token", token);
        connection.setRequestProperty("Accept", "application/json");
        return connection;
    }
    private JSONObject readResponse(HttpURLConnection connection) throws Exception {
        int code = connection.getResponseCode();
        if (code != 200) throw new IOException(code == 401 ? "Access code rejected" : "Printer HTTP error " + code);
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
}
