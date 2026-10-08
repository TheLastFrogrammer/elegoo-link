package io.github.thelastfrogrammer.elink;

import static org.junit.Assert.*;
import org.junit.Test;

public class FileSummaryTest {
    @Test public void withoutAListTheMessageIsShownAsIs() {
        assertEquals("Refresh to browse printer files.", FeatureData.fileSummary("Refresh to browse printer files.", "local", false, 0, 0, false, false));
    }
    @Test public void aFreshListShowsStorageAndCount() {
        assertEquals("Internal storage · 3 files", FeatureData.fileSummary("Files received from printer.", "local", true, 3, 0, true, false));
        assertEquals("USB drive · 1 file", FeatureData.fileSummary("Files received from printer.", "u-disk", true, 1, 0, true, false));
    }
    @Test public void staleAndRefreshingListsSayWhy() {
        assertTrue(FeatureData.fileSummary("Files received from printer.", "local", true, 3, 0, false, false).endsWith("refresh before starting or deleting"));
        assertTrue(FeatureData.fileSummary("Loading printer files…", "local", true, 3, 0, false, true).endsWith("refreshing…"));
    }
    @Test public void errorsAndLaterPagesAreKept() {
        String text = FeatureData.fileSummary("The printer did not answer.", "local", true, 50, 50, true, false);
        assertTrue(text.startsWith("The printer did not answer.\n"));
        assertTrue(text.contains("from 51"));
    }
}
