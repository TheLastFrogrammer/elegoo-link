package io.github.thelastfrogrammer.elink;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Files that the in-app model browser takes: names, what they really are, ZIPs, and which address gets which cookies. */
public class ModelBrowserFilesTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test public void namesComeFromTheAnswerThenTheAddress() {
        assertEquals("Benchy v2.stl", ModelSites.fileNameFor("https://x.example/d/123", "attachment; filename=\"Benchy v2.stl\"", null));
        assertEquals("Würfel.3mf", ModelSites.fileNameFor("https://x.example/d", "attachment; filename=\"x.bin\"; filename*=UTF-8''W%C3%BCrfel.3mf", null));
        assertEquals("clip.stl", ModelSites.fileNameFor("https://files.example/a/b/clip.stl?sig=abc", null, null));
        assertEquals("passwd", ModelSites.fileNameFor("https://x.example/d", "attachment; filename=\"../../etc/passwd\"", null));
        assertEquals("model.zip", ModelSites.fileNameFor(null, null, "application/zip"));
        assertEquals("model", ModelSites.fileNameFor("http://insecure.example/clip.stl", null, null));
    }

    @Test public void onlyModelsAndZipsAreTaken() {
        for (String ok : new String[] {"a.stl", "a.3MF", "a.obj", "a.step", "a.stp", "a.amf", "pack.zip", "model", "download.bin"}) assertTrue(ok, ModelSites.browserTakes(ok));
        for (String no : new String[] {"setup.exe", "app.apk", "page.html", "photo.jpg", "doc.pdf"}) assertFalse(no, ModelSites.browserTakes(no));
    }

    @Test public void sniffsWhatAFileReallyIs() throws Exception {
        assertEquals("stl", ModelSites.sniff(write("a", "solid cube\n facet normal 0 0 1\n".getBytes(StandardCharsets.US_ASCII))));
        byte[] binary = new byte[84 + 50]; binary[80] = 1;
        assertEquals("stl", ModelSites.sniff(write("b", binary)));
        assertEquals("step", ModelSites.sniff(write("c", "ISO-10303-21;\nHEADER;".getBytes(StandardCharsets.US_ASCII))));
        assertEquals("obj", ModelSites.sniff(write("d", "# made by hand\nv 0 0 0\nv 1 0 0\n".getBytes(StandardCharsets.US_ASCII))));
        assertEquals("3mf", ModelSites.sniff(zip("e", "3D/3dmodel.model", "[Content_Types].xml")));
        assertEquals("zip", ModelSites.sniff(zip("f", "parts/clip.stl")));
        assertEquals("", ModelSites.sniff(write("g", "<html><body>Sign in</body></html>".getBytes(StandardCharsets.US_ASCII))));
    }

    @Test public void zipsGiveTheirModelsFlattenedAndNothingElse() throws Exception {
        File out = folder.newFolder("out");
        List<File> models = ModelSites.unzipModels(zip("pack", "parts/clip.stl", "other/clip.stl", "../../escape.stl", "readme.txt", "__MACOSX/._clip.stl", "img/photo.jpg", "base.3mf"), out);
        List<String> names = new ArrayList<>(); for (File m : models) names.add(m.getName());
        assertEquals(Arrays.asList("clip.stl", "clip (2).stl", "escape.stl", "base.3mf"), names);
        for (File m : models) assertEquals("Everything lands in the folder itself", out.getCanonicalFile(), m.getCanonicalFile().getParentFile());
        assertFalse(new File(folder.getRoot(), "escape.stl").exists());
    }

    @Test public void eachHopGetsOnlyItsOwnCookies() throws Exception {
        Map<String, String> jar = new HashMap<>(); jar.put("www.printables.com", "session=signed-in"); jar.put("files.printables.com", "");
        List<String> sent = new ArrayList<>();
        ModelSites.Http net = url -> new HttpURLConnection(url) {
            public void setRequestProperty(String key, String value) { if (key.equals("Cookie")) sent.add(url.getHost() + ":" + value); }
            public void connect() { }
            public void disconnect() { }
            public boolean usingProxy() { return false; }
            public int getResponseCode() { return url.getHost().startsWith("www.") ? 302 : 200; }
            public String getHeaderField(String name) {
                if ("Location".equals(name)) return "https://files.printables.com/media/clip.zip?token=abc";
                return "Content-Disposition".equals(name) ? "attachment; filename=\"clip.zip\"" : null;
            }
            public long getContentLengthLong() { return 4; }
            public InputStream getInputStream() { return new ByteArrayInputStream("PK..".getBytes(StandardCharsets.US_ASCII)); }
        };
        String disposition = ModelSites.download(net, "https://www.printables.com/model/1/files/download", (url, connection) -> {
            String cookies = jar.get(url.getHost()); if (cookies != null && !cookies.isEmpty()) connection.setRequestProperty("Cookie", cookies);
        }, folder.newFile("clip.zip"), (d, t) -> true);
        assertEquals(Collections.singletonList("www.printables.com:session=signed-in"), sent);
        assertEquals("attachment; filename=\"clip.zip\"", disposition);
    }

    private File write(String name, byte[] bytes) throws IOException {
        File file = folder.newFile(name);
        try (OutputStream out = new FileOutputStream(file)) { out.write(bytes); }
        return file;
    }
    private File zip(String name, String... entries) throws IOException {
        File file = folder.newFile(name + ".zip");
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(file))) {
            for (String entry : entries) { out.putNextEntry(new ZipEntry(entry)); out.write(("data of " + entry).getBytes(StandardCharsets.UTF_8)); out.closeEntry(); }
        }
        return file;
    }
}
