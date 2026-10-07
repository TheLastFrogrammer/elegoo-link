package io.github.thelastfrogrammer.elink;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

public class PrinterDownloadTest {
    static class Connection extends HttpURLConnection {
        byte[] body = "G28\nT0\n".getBytes(StandardCharsets.UTF_8);
        int code = 200; long length = body.length; String type = "application/octet-stream", encoding;
        boolean disconnected;
        Connection(URL url) { super(url); }
        public void disconnect() { disconnected = true; } public boolean usingProxy() { return false; } public void connect() { }
        public int getResponseCode() { return code; } public long getContentLengthLong() { return length; }
        public String getContentType() { return type; } public String getContentEncoding() { return encoding; }
        public InputStream getInputStream() { return new ByteArrayInputStream(body); }
    }
    @Test public void internalAndUsbUseEncodedSeparateTokenFilenameQueryAndNoRedirects() throws Exception {
        for (String storage : Arrays.asList("local", "u-disk")) {
            List<Connection> opened = new ArrayList<>(); List<Integer> progress = new ArrayList<>(); File file = File.createTempFile("download-", ".gcode");
            try {
                new PrinterHttp("192.168.1.50", "a&? token", url -> { Connection c = new Connection(url); opened.add(c); return c; }).download(file, storage, "two words.gcode", progress::add);
                Connection c = opened.get(0); assertEquals(storage.equals("local") ? "/download" : "/download/udisk", c.getURL().getPath());
                assertEquals("X-Token=a%26%3F%20token&file_name=two%20words.gcode", c.getURL().getQuery()); assertEquals("a&? token", c.getRequestProperty("X-Token"));
                assertEquals("GET", c.getRequestMethod()); assertFalse(c.getInstanceFollowRedirects()); assertTrue(c.disconnected);
                assertArrayEquals(c.body, Files.readAllBytes(file.toPath())); assertEquals(Integer.valueOf(100), progress.get(progress.size() - 1));
            } finally { file.delete(); }
        }
    }
    private void rejected(int status, long length, String type, String encoding, byte[] body) throws Exception {
        File file = File.createTempFile("download-", ".gcode"); List<Connection> opened = new ArrayList<>();
        try {
            PrinterHttp http = new PrinterHttp("192.168.1.50", "code", url -> { Connection c = new Connection(url); c.code = status; c.length = length; c.type = type; c.encoding = encoding; c.body = body; opened.add(c); return c; });
            try { http.download(file, "local", "test.gcode", p -> { }); fail("Must reject"); } catch (IOException expected) { }
            assertFalse("Failed download must remove partial cache copy", file.exists()); assertTrue(opened.get(0).disconnected);
        } finally { file.delete(); }
    }
    @Test public void authorizationRedirectAndErrorResponsesNeverBecomeFiles() throws Exception {
        for (int code : new int[] {401, 403, 302, 404, 500}) rejected(code, 2, null, null, new byte[] {1, 2});
        rejected(200, -1, "application/json", null, "{\"error_code\":1003}".getBytes(StandardCharsets.UTF_8));
        rejected(200, -1, "text/html", null, "<html><body>Not found</body></html>".getBytes(StandardCharsets.UTF_8));
        rejected(200, 2, null, "br", new byte[] {1, 2});
        rejected(200, -1, null, null, " \n{\"error_code\":1000}".getBytes(StandardCharsets.UTF_8));
    }
    @Test public void oversizedEmptyAndTruncatedPayloadsDeleteCacheCopy() throws Exception {
        rejected(200, GcodeInspector.MAX_BYTES + 1, null, null, new byte[] {1});
        rejected(200, 0, null, null, new byte[0]); rejected(200, -1, null, null, new byte[0]);
        rejected(200, 9, null, null, "G28".getBytes(StandardCharsets.UTF_8));
        rejected(200, 2, null, null, "G28".getBytes(StandardCharsets.UTF_8));
    }
    @Test public void unknownLengthStreamsWithoutInventingPercentageThenCompletes() throws Exception {
        File file = File.createTempFile("download-", ".gcode"); List<Integer> progress = new ArrayList<>();
        try { new PrinterHttp("192.168.1.50", "", url -> { Connection c = new Connection(url); c.length = -1; return c; }).download(file, "local", "test.gcode", progress::add); assertEquals(Arrays.asList(-1, 100), progress); }
        finally { file.delete(); }
    }
    @Test public void cancellationMidStreamRemovesPartialAndDoesNotReportCompletion() throws Exception {
        File file = File.createTempFile("download-", ".gcode"); List<Integer> progress = new ArrayList<>();
        PrinterHttp http = new PrinterHttp("192.168.1.50", "code", url -> { Connection c = new Connection(url); c.body = new byte[140000]; Arrays.fill(c.body, (byte) 'G'); c.length = c.body.length; return c; });
        try { try { http.download(file, "local", "test.gcode", p -> { progress.add(p); http.cancel(); }); fail(); } catch (IOException expected) { } assertFalse(file.exists()); assertFalse(progress.contains(100)); }
        finally { file.delete(); }
    }
    @Test public void cancelledBeforeOpeningAndUnsafePathCannotCreateARequest() throws Exception {
        PrinterHttp http = new PrinterHttp("192.168.1.50", "code", url -> { throw new AssertionError("Must not open"); }); http.cancel();
        File file = File.createTempFile("download-", ".gcode");
        try {
            try { http.download(file, "local", "test.gcode", p -> { }); fail(); } catch (IOException expected) { } assertFalse(file.exists());
            for (String filename : Arrays.asList("../test.gcode", "test.gcode&other=1", "test.gcode\nX:secret")) try { http.download(file, "local", filename, p -> { }); fail(); } catch (IllegalArgumentException expected) { }
            try { http.download(file, "sdcard", "test.gcode", p -> { }); fail(); } catch (IllegalArgumentException expected) { }
        } finally { file.delete(); }
    }
    @Test public void timelapseVideosUseTheDownloadEndpointWithTheReportedName() throws Exception {
        File file = File.createTempFile("timelapse-", ".mp4"); List<Connection> opened = new ArrayList<>(); List<Integer> progress = new ArrayList<>();
        byte[] video = new byte[200000]; video[4] = 'f'; video[5] = 't'; video[6] = 'y'; video[7] = 'p';
        try {
            new PrinterHttp("192.168.1.50", "code", url -> { Connection c = new Connection(url); c.body = video; c.length = video.length; c.type = "video/mp4"; opened.add(c); return c; })
                .downloadTimelapse(file, "/user/timelapse/Benchy 1.mp4", progress::add);
            Connection c = opened.get(0);
            assertEquals("/download", c.getURL().getPath());
            assertEquals("X-Token=code&file_name=%2Fuser%2Ftimelapse%2FBenchy%201.mp4", c.getURL().getQuery());
            assertArrayEquals(video, Files.readAllBytes(file.toPath())); assertEquals(Integer.valueOf(100), progress.get(progress.size() - 1));
        } finally { file.delete(); }
        // Error documents and references that are not printer paths never become downloads.
        for (String bad : new String[] {"", " ", "http://elsewhere.example/v.mp4", "../../etc/passwd", "a\nb.mp4"}) {
            try { new PrinterHttp("192.168.1.50", "code", url -> new Connection(url)).downloadTimelapse(file, bad, p -> { }); fail("Must reject " + bad); }
            catch (IllegalArgumentException expected) { }
        }
        try {
            new PrinterHttp("192.168.1.50", "code", url -> { Connection c = new Connection(url); c.body = "{\"error_code\":1020}".getBytes(StandardCharsets.UTF_8); c.length = c.body.length; return c; })
                .downloadTimelapse(file, "v.mp4", p -> { });
            fail("Must reject an error document");
        } catch (IOException expected) { assertFalse(file.exists()); }
    }
    @Test public void theBodyDecidesNotItsLabelAndGzipIsUnpacked() throws Exception {
        File file = File.createTempFile("download-", ".gcode");
        try {
            // G-code labelled as JSON or HTML by odd firmware is still G-code.
            for (String type : new String[] {"application/json", "text/html", "text/plain"}) {
                new PrinterHttp("192.168.1.50", "code", url -> { Connection c = new Connection(url); c.type = type; return c; }).download(file, "local", "a.gcode", p -> { });
                assertEquals("G28\nT0\n", new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            }
            ByteArrayOutputStream packed = new ByteArrayOutputStream();
            try (java.util.zip.GZIPOutputStream gzip = new java.util.zip.GZIPOutputStream(packed)) { gzip.write("; sliced\nG1 X1\n".getBytes(StandardCharsets.UTF_8)); }
            new PrinterHttp("192.168.1.50", "code", url -> { Connection c = new Connection(url); c.encoding = "gzip"; c.body = packed.toByteArray(); c.length = c.body.length; return c; })
                .download(file, "local", "a.gcode", p -> { });
            assertEquals("; sliced\nG1 X1\n", new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            // Files in a folder, as the printer lists them.
            List<Connection> opened = new ArrayList<>();
            new PrinterHttp("192.168.1.50", "code", url -> { Connection c = new Connection(url); opened.add(c); return c; }).download(file, "local", "models/part one.gcode", p -> { });
            assertEquals("X-Token=code&file_name=models%2Fpart%20one.gcode", opened.get(0).getURL().getQuery());
            for (String bad : new String[] {"../x.gcode", "/abs.gcode", "a//b.gcode", "a\\b.gcode", "a/../b.gcode", "x.txt"})
                try { Cc2Codec.filename(bad); fail("must reject " + bad); } catch (IllegalArgumentException expected) { }
        } finally { file.delete(); }
    }
}
