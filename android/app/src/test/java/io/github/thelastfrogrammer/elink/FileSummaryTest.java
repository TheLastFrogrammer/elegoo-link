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

    @Test public void fileListsAreReadWhateverTheyCallTheName() throws Exception {
        org.json.JSONObject raw = new org.json.JSONObject("{\"total\":5,\"file_list\":[{\"filename\":\"a.gcode\",\"size\":1},{\"file_name\":\"/local/b.gcode\",\"file_size\":2},"
            + "{\"name\":\"c.gcode\",\"total_layer\":9},\"d.gcode\",{\"name\":\"folder\",\"is_dir\":true},{\"size\":3},7]}");
        org.json.JSONArray list = FeatureData.normalizeFiles(raw).getJSONArray("file_list");
        org.junit.Assert.assertEquals(4, list.length());
        org.junit.Assert.assertEquals("a.gcode", list.getJSONObject(0).getString("filename"));
        org.junit.Assert.assertEquals("b.gcode", list.getJSONObject(1).getString("filename")); org.junit.Assert.assertEquals(2, list.getJSONObject(1).getInt("size"));
        org.junit.Assert.assertEquals(9, list.getJSONObject(2).getInt("layer"));
        org.junit.Assert.assertEquals("d.gcode", list.getJSONObject(3).getString("filename"));
        org.junit.Assert.assertEquals(5, FeatureData.normalizeFiles(raw).getInt("total"));
        org.junit.Assert.assertEquals("objects with [filename, size]", FeatureData.fileEntryShape(raw));
        org.junit.Assert.assertEquals("String entries", FeatureData.fileEntryShape(new org.json.JSONObject("{\"file_list\":[\"x\"]}")));
        org.junit.Assert.assertEquals(0, FeatureData.normalizeFiles(new org.json.JSONObject()).length());
    }
}
