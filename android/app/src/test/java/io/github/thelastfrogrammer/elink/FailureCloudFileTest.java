package io.github.thelastfrogrammer.elink;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Getting a printer file's G-code through Elegoo's cloud: record parsing, what is logged, links, and the download itself. */
public class FailureCloudFileTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    @Before public void clean() { Diagnostics.clear(); }

    private static final String SERIAL = "F01PLA1234567890";
    private final List<String> requests = new ArrayList<>();

    private CloudApi api(String body) {
        return new CloudApi(false, new CloudLogin.Account("42", "Maker", "access-secret-token", "refresh", 0, 0), "agent", (method, url, headers, text) -> {
            requests.add(method + " " + url);
            return new CloudApi.Response(200, body);
        });
    }

    private static final String RECORD = "{\"code\":0,\"data\":{\"filename\":\"part.gcode\",\"size\":987654,\"thumbnail\":\"thumb/SECRETTHUMB.png\","
        + "\"gcodeObject\":\"gcode/SECRETOBJ.gcode\",\"link\":\"https://oss.example.com/x?sig=SECRETSIG\",\"owner\":\"tok123\",\"colorMap\":\"[]\",\"nested\":{\"a\":1},\"flag\":true}}";

    @Test public void theRecordIsLoggedByFieldNameAndKindOnly() throws Exception {
        JSONObject record = api(RECORD).fileRecord(SERIAL, "part.gcode");
        assertEquals("part.gcode", record.getString("filename"));
        assertTrue(requests.get(0), requests.get(0).contains("local-file/filename?serialNo=" + SERIAL + "&filename=part.gcode"));
        String log = Diagnostics.report("");
        assertTrue(log, log.contains("filename:string") && log.contains("size:number") && log.contains("thumbnail:object-name-like")
            && log.contains("gcodeObject:object-name-like") && log.contains("link:url-like") && log.contains("nested:object") && log.contains("flag:bool"));
        for (String secret : new String[] {"SECRETTHUMB", "SECRETOBJ", "SECRETSIG", "tok123", "987654", "part.gcode", "access-secret-token", SERIAL, "oss.example.com"})
            assertFalse("the log must not hold " + secret, log.contains(secret));
    }

    @Test public void theFileListShapeIsLoggedFromTheFirstItemOnly() {
        api("{\"code\":0,\"data\":{\"total\":2,\"list\":[{\"filename\":\"a.gcode\",\"objectKey\":\"gcode/SECRETOBJ.gcode\"},{\"filename\":\"b.gcode\",\"second\":1}]}}").logFileListShape(SERIAL);
        assertTrue(requests.get(0), requests.get(0).contains("local-file/page?serialNo=" + SERIAL + "&pageNo=1&pageSize=1"));
        String log = Diagnostics.report("");
        assertTrue(log, log.contains("first item fields: filename:string, objectKey:object-name-like"));
        assertFalse(log.contains("second")); assertFalse(log.contains("SECRETOBJ")); assertFalse(log.contains("a.gcode"));
    }

    @Test public void onlyAFieldThatPlainlyNamesTheGcodeIsUsed() throws Exception {
        JSONObject plain = new JSONObject(RECORD).getJSONObject("data"); plain.remove("gcodeObject");
        assertNull("thumbnail, plain names and a foreign link are not a reference", CloudApi.gcodeReference(plain));
        CloudApi.Reference object = CloudApi.gcodeReference(new JSONObject().put("filename", "part.gcode").put("thumbnail", "t/x.gcode").put("gcodeObject", "gcode/o.gcode"));
        assertEquals("gcode/o.gcode", object.objectName);
        CloudApi.Reference link = CloudApi.gcodeReference(new JSONObject().put("u", "https://cdn.elegoo.com.cn/g/o.gcode?sig=1"));
        assertEquals("https://cdn.elegoo.com.cn/g/o.gcode?sig=1", link.url);
        assertNull("another company's host is not trusted from a record", CloudApi.gcodeReference(new JSONObject().put("u", "https://evil.example/o.gcode")));
        assertNull("http is not accepted", CloudApi.gcodeReference(new JSONObject().put("u", "http://cdn.elegoo.com/o.gcode")));
        assertNull("a look-alike host is not an Elegoo domain", CloudApi.gcodeReference(new JSONObject().put("u", "https://notelegoo.com/o.gcode")));
    }

    @Test public void aSignedLinkIsHttpsAndOnlyItsHostIsLogged() throws Exception {
        String link = api("{\"code\":0,\"data\":{\"accessUrl\":\"https://iot.example-oss.com/gcode/x.gcode?Signature=SECRETSIG\"}}").signedLink("gcode/x.gcode");
        assertTrue(requests.get(0), requests.get(0).contains("generate-pre-access-url?bucketAlias=iot-private&objectName=gcode%2Fx.gcode"));
        assertTrue(link.startsWith("https://"));
        String log = Diagnostics.report("");
        assertTrue(log, log.contains("host iot.example-oss.com")); assertFalse(log.contains("SECRETSIG")); assertFalse(log.contains("x.gcode"));
        try { api("{\"code\":0,\"data\":{\"accessUrl\":\"http://plain.example/x\"}}").signedLink("o"); fail(); } catch (IOException expected) { }
    }

    /** A canned HTTPS answer. */
    private static HttpURLConnection answer(URL url, int status, String type, byte[] body) {
        return new HttpURLConnection(url) {
            public void connect() { }
            public void disconnect() { }
            public boolean usingProxy() { return false; }
            public int getResponseCode() { return status; }
            public long getContentLengthLong() { return body.length; }
            public String getContentType() { return type; }
            public InputStream getInputStream() { return new ByteArrayInputStream(body); }
        };
    }
    private CloudFileRoute.Choice choice() { return new CloudFileRoute.Choice("gcode/x.gcode", "", "an upload from this app"); }

    @Test public void theLinkIsFetchedIntoTheDestinationWithProgress() throws Exception {
        File target = folder.newFile("download-1.gcode"); byte[] body = "; sliced\nG28\nG1 X10\n".getBytes(StandardCharsets.UTF_8);
        List<Integer> progress = new ArrayList<>();
        CloudFileRoute.download(choice(), name -> "https://oss.example.com/o?sig=1", target, progress::add, url -> answer(url, 200, "application/octet-stream", body), new AtomicBoolean());
        assertArrayEquals(body, java.nio.file.Files.readAllBytes(target.toPath()));
        assertEquals(100, (int) progress.get(progress.size() - 1));
        assertTrue(Diagnostics.report("").contains("downloading through the Elegoo cloud from oss.example.com"));
    }

    @Test public void errorPagesExpiredLinksAndPlainHttpNeverLeaveAFile() throws Exception {
        File target = folder.newFile("download-2.gcode");
        try { CloudFileRoute.download(choice(), n -> "https://oss.example.com/o", target, p -> { }, url -> answer(url, 200, "application/json", "{\"error\":\"expired\"}".getBytes(StandardCharsets.UTF_8)), new AtomicBoolean()); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage(), expected.getMessage().contains("error instead of the G-code")); }
        assertFalse(target.exists());
        File other = folder.newFile("download-3.gcode");
        try { CloudFileRoute.download(choice(), n -> "https://oss.example.com/o", other, p -> { }, url -> answer(url, 404, "text/html", new byte[0]), new AtomicBoolean()); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage(), expected.getMessage().contains("no longer has this file")); }
        assertFalse(other.exists());
        try { CloudFileRoute.download(choice(), n -> "http://oss.example.com/o", folder.newFile("download-4.gcode"), p -> { }, url -> { fail("no connection for plain http"); return null; }, new AtomicBoolean()); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("https")); }
    }
}
