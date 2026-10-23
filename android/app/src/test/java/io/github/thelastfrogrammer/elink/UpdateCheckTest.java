package io.github.thelastfrogrammer.elink;

import static org.junit.Assert.*;

import org.junit.Test;

/** The newest signed release from GitHub's list, and versions compared as numbers. */
public class UpdateCheckTest {
    @Test public void versionsCompareAsNumbers() {
        assertTrue(UpdateCheck.compare("0.100.0", "0.99.2") > 0);
        assertTrue(UpdateCheck.compare("0.99.2", "0.99.10") < 0);
        assertEquals(0, UpdateCheck.compare("1.0", "1.0.0"));
        assertEquals("anything after the numbers is ignored", 0, UpdateCheck.compare("0.99.2-dev", "0.99.2"));
    }

    @Test public void theNewestPublishedReleaseWithAnApk() {
        String json = "[" +
            "{\"tag_name\":\"android-v0.101.0\",\"draft\":true,\"assets\":[{\"name\":\"a.apk\",\"browser_download_url\":\"https://github.com/x/a.apk\"}]}," +
            "{\"tag_name\":\"android-v0.100.1\",\"prerelease\":true,\"assets\":[{\"name\":\"a.apk\",\"browser_download_url\":\"https://github.com/x/a.apk\"}]}," +
            "{\"tag_name\":\"android-v0.100.0\",\"html_url\":\"https://github.com/x/releases/tag/android-v0.100.0\",\"body\":\"Signed release\"," +
            " \"assets\":[{\"name\":\"SHA256SUMS\",\"browser_download_url\":\"https://github.com/x/SHA256SUMS\"},{\"name\":\"Link-Workshop-v0.100.0.apk\",\"browser_download_url\":\"https://github.com/x/Link-Workshop-v0.100.0.apk\"}]}," +
            "{\"tag_name\":\"android-v0.99.0\",\"assets\":[{\"name\":\"old.apk\",\"browser_download_url\":\"https://github.com/x/old.apk\"}]}," +
            "{\"tag_name\":\"engine-v5\",\"assets\":[{\"name\":\"e.apk\",\"browser_download_url\":\"https://github.com/x/e.apk\"}]}," +
            "{\"tag_name\":\"android-v9.9.9\",\"assets\":[{\"name\":\"evil.apk\",\"browser_download_url\":\"https://elsewhere.example/evil.apk\"}]}]";
        UpdateCheck.Release r = UpdateCheck.newest(json);
        assertNotNull(r);
        assertEquals("0.100.0", r.version);
        assertEquals("https://github.com/x/Link-Workshop-v0.100.0.apk", r.apk);
        assertEquals("Signed release", r.notes);
        assertNull("nothing published", UpdateCheck.newest("[]"));
        assertNull("not a list", UpdateCheck.newest("{\"message\":\"Not Found\"}"));
    }
}
