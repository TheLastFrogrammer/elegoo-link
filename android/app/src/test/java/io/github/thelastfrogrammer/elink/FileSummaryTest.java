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
    @Test public void historyEntriesAreNewestFirstWithResultDurationAndTimelapse() throws Exception {
        java.util.List<String[]> list = FeatureData.historyEntries(new org.json.JSONObject("{\"history_task_list\":["
            + "{\"task_name\":\"old.gcode\",\"task_status\":2},"
            + "{\"task_name\":\"Benchy.gcode\",\"task_status\":1,\"begin_time\":100,\"end_time\":9100,\"time_lapse_video_status\":2,\"time_lapse_video_size\":1048576,\"time_lapse_video_duration\":45}]}"));
        assertEquals(2, list.size());
        assertEquals("Benchy", list.get(0)[0]); assertEquals("Completed · 2h 30m · Timelapse ready (1.0 MB, 0:45)", list.get(0)[1]);
        assertEquals("old", list.get(1)[0]); assertEquals("Cancelled", list.get(1)[1]);
        assertTrue(FeatureData.historyEntries(new org.json.JSONObject()).isEmpty());
    }
    @Test public void joinPartsLeavesNoDanglingSeparator() {
        assertEquals("Centauri Carbon 2 · firmware 1.2", StatusPresentation.joinParts("Centauri Carbon 2", "firmware 1.2", ""));
        assertEquals("A · C", StatusPresentation.joinParts("A", null, " ", "C"));
        assertEquals("", StatusPresentation.joinParts("", null));
    }
}
