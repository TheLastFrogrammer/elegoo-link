package io.github.thelastfrogrammer.elink;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class ModelSitesTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test public void addressesAreEncodedAndHttpsOnly() {
        assertEquals("https://api.thingiverse.com/search/cable%20clip%20%26%20hook/?type=things&per_page=20&page=2", ModelSites.thingiverseSearch(" cable clip & hook ", 2));
        assertEquals("https://api.thingiverse.com/things/123/files", ModelSites.thingiverseFiles("123"));
        try { ModelSites.thingiverseFiles("12/../x"); fail(); } catch (IllegalArgumentException expected) { }
        assertTrue(ModelSites.mmfSearch("benchy", 1, "k e y").contains("key=k+e+y"));
        for (String site : ModelSites.BROWSER_SITES) assertTrue(ModelSites.webSearch(site, "a b").startsWith("https://"));
        assertTrue(ModelSites.webSearch("Printables", "a&b").endsWith("q=a%26b"));
    }

    @Test public void readsThingiverseSearchAndFiles() throws Exception {
        List<ModelSites.Model> found = ModelSites.parseThingiverseSearch("{\"total\":2,\"hits\":[{\"id\":763622,\"name\":\"Benchy\",\"thumbnail\":\"https://cdn.thingiverse.com/a.jpg\",\"public_url\":\"https://www.thingiverse.com/thing:763622\",\"creator\":{\"name\":\"CreativeTools\"}},"
            + "{\"id\":\"bad id\",\"name\":\"x\"},{\"id\":5,\"name\":\"Plain\",\"thumbnail\":\"http://insecure/a.jpg\"}]}");
        assertEquals(2, found.size());
        assertEquals("Benchy", found.get(0).name); assertEquals("CreativeTools", found.get(0).creator);
        assertEquals("", found.get(1).thumbnail);   // http thumbnails are dropped
        assertEquals("https://www.thingiverse.com/thing:5", found.get(1).page);
        List<ModelSites.ModelFile> files = ModelSites.parseThingiverseFiles("[{\"id\":1,\"name\":\"../../evil/benchy.stl\",\"size\":11285384,\"download_url\":\"https://api.thingiverse.com/files/1/download\"},"
            + "{\"id\":2,\"name\":\"readme.txt\",\"size\":10},{\"id\":\"x\",\"name\":\"no url\"}]");
        assertEquals(2, files.size());
        assertEquals("benchy.stl", files.get(0).name); assertTrue(files.get(0).printable());
        assertEquals("https://api.thingiverse.com/files/2/download", files.get(1).url); assertFalse(files.get(1).printable());
    }

    @Test public void readsMyMiniFactorySearch() throws Exception {
        List<ModelSites.Model> found = ModelSites.parseMmfSearch("{\"total_count\":1,\"items\":[{\"id\":42,\"name\":\"Dragon\",\"url\":\"https://www.myminifactory.com/object/3d-print-dragon-42\","
            + "\"designer\":{\"username\":\"maker\"},\"images\":[{\"thumbnail\":{\"url\":\"https://dl.myminifactory.com/t.jpg\"}}],\"license\":\"CC BY\"}]}");
        assertEquals(1, found.size());
        assertEquals("maker", found.get(0).creator); assertEquals("https://dl.myminifactory.com/t.jpg", found.get(0).thumbnail); assertEquals("CC BY", found.get(0).license);
    }

    /** Plays the network: records which host got a token and answers by host. */
    private static final class Net implements ModelSites.Http {
        final List<String> tokenSentTo = new ArrayList<>();
        final Map<String, String[]> answers = new HashMap<>();   // host -> {code, location or body}
        public HttpURLConnection open(URL url) {
            String[] answer = answers.get(url.getHost());
            return new HttpURLConnection(url) {
                final Map<String, String> headers = new HashMap<>();
                public void setRequestProperty(String key, String value) { headers.put(key, value); if (key.equals("Authorization")) tokenSentTo.add(url.getHost()); }
                public void connect() { }
                public void disconnect() { }
                public boolean usingProxy() { return false; }
                public int getResponseCode() { return Integer.parseInt(answer[0]); }
                public String getHeaderField(String name) { return "Location".equals(name) ? answer[1] : null; }
                public long getContentLengthLong() { return -1; }
                public InputStream getInputStream() { return new ByteArrayInputStream(answer[1].getBytes(StandardCharsets.UTF_8)); }
            };
        }
    }

    @Test public void theTokenNeverFollowsARedirect() throws Exception {
        Net net = new Net();
        net.answers.put("api.thingiverse.com", new String[] {"302", "https://cdn.thingiverse.com/files/benchy.stl"});
        net.answers.put("cdn.thingiverse.com", new String[] {"200", "solid benchy"});
        File target = folder.newFile("benchy.stl");
        ModelSites.download(net, "https://api.thingiverse.com/files/1/download", "secret-token", target, (done, total) -> true);
        assertEquals(Collections.singletonList("api.thingiverse.com"), net.tokenSentTo);
        assertEquals("solid benchy", new String(java.nio.file.Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8));
    }

    @Test public void insecureRedirectsAndOtherHostsGetNoToken() throws Exception {
        Net net = new Net();
        net.answers.put("api.thingiverse.com", new String[] {"302", "http://cdn.example/benchy.stl"});
        try { ModelSites.download(net, "https://api.thingiverse.com/files/1/download", "t", folder.newFile("a.stl"), (d, t) -> true); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("not secure")); }
        net.answers.put("www.myminifactory.com", new String[] {"200", "{\"items\":[]}"});
        ModelSites.getJson(net, "https://www.myminifactory.com/api/v2/search?q=a&key=k", "thingiverse-token");
        assertFalse("A Thingiverse token never goes to another site", net.tokenSentTo.contains("www.myminifactory.com"));
        try { ModelSites.getJson(net, "http://api.thingiverse.com/search/a", "t"); fail(); } catch (IOException expected) { }
    }

    @Test public void cancelledDownloadsStop() throws Exception {
        Net net = new Net(); net.answers.put("api.thingiverse.com", new String[] {"200", "data"});
        try { ModelSites.download(net, "https://api.thingiverse.com/files/1/download", "", folder.newFile("b.stl"), (d, t) -> false); fail(); }
        catch (InterruptedIOException expected) { }
    }

    @Test public void resultsFromEachSiteTakeTurns() {
        List<ModelSites.Model> all = new ArrayList<>();
        for (int i = 0; i < 3; i++) all.add(new ModelSites.Model("Thingiverse", "" + i, "T" + i, "", "", "", ""));
        all.add(new ModelSites.Model("MyMiniFactory", "9", "M0", "", "", "", ""));
        List<String> names = new ArrayList<>(); for (ModelSites.Model m : ModelSearchActivity.mixSources(all)) names.add(m.name);
        assertEquals(Arrays.asList("T0", "M0", "T1", "T2"), names);
        assertEquals("1.5 MB", ModelSearchActivity.megabytes(1572864).replace(',', '.'));
        assertEquals("2 KB", ModelSearchActivity.megabytes(2048));
    }
}
