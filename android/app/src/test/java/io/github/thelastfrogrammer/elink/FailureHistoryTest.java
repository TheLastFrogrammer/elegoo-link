package io.github.thelastfrogrammer.elink;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Print history: what the CC2 reports per entry (1036: task_id, task_name, begin_time, end_time, task_status, time_lapse_video_*),
 * the detail query (1037), the viewer's running-print line, and whether an entry can be printed again.
 */
public class FailureHistoryTest {
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");
    // 2025-11-03 14:05:00 UTC and 1 h 20 min later.
    private static final long BEGIN = 1_762_178_700L, END = BEGIN + 4800;

    private static JSONObject row(String id, String name) throws Exception {
        return new JSONObject().put("task_id", id).put("task_name", name).put("begin_time", BEGIN).put("end_time", END).put("task_status", 1)
            .put("time_lapse_video_status", 2).put("time_lapse_video_url", "/video/a.mp4").put("time_lapse_video_size", 5242880).put("time_lapse_video_duration", 45);
    }
    private static String value(List<String[]> lines, String label) { for (String[] line : lines) if (line[0].equals(label)) return line[1]; return null; }

    // ---- 1037 request ----
    @Test public void theDetailRequestIsAValidatedRead() throws Exception {
        JSONObject request = Cc2Codec.historyDetailRequest(7, "abc-123_x.1");
        assertEquals(1037, request.getInt("method")); assertEquals("abc-123_x.1", request.getJSONObject("params").getString("task_id"));
        for (String bad : new String[] {null, "", "../etc", "a b", "x/y", "é", new String(new char[65]).replace('\0', 'a')}) {
            try { Cc2Codec.historyDetailRequest(1, bad); fail("accepted " + bad); } catch (IllegalArgumentException expected) { }
        }
        assertTrue(Cc2Codec.isQuery(Cc2Codec.HISTORY_DETAIL));
        assertFalse("a read changes nothing", Cc2Codec.changing(Cc2Codec.HISTORY_DETAIL));
        assertTrue("allowed through the cloud like 1036", CloudControl.allowed(Cc2Codec.HISTORY_DETAIL));
        assertTrue(CloudControl.allowed(Cc2Codec.HISTORY));
    }
    @Test public void theDetailAnswerNeedsContent() throws Exception {
        assertFalse(Cc2Codec.queryShape(Cc2Codec.HISTORY_DETAIL, new JSONObject()));
        assertFalse(Cc2Codec.queryShape(Cc2Codec.HISTORY_DETAIL, new JSONObject().put("error_code", 0)));
        assertTrue(Cc2Codec.queryShape(Cc2Codec.HISTORY_DETAIL, new JSONObject().put("error_code", 0).put("layer", 5)));
    }

    // ---- 1036 entry fields ----
    @Test public void aCompletedEntryShowsResultTimesDurationAndTimelapse() throws Exception {
        List<String[]> lines = FeatureData.historyDetail(row("t1", "a.gcode"), null, Locale.ENGLISH, UTC);
        assertEquals("Completed", value(lines, "Result"));
        assertEquals("Mon 3 Nov, 14:05", value(lines, "Started"));
        assertEquals("Mon 3 Nov, 15:25", value(lines, "Ended"));
        assertEquals("1h 20m", value(lines, "Duration"));
        assertEquals("Video ready (5.0 MB, 0:45)", value(lines, "Timelapse"));
    }
    @Test public void cancelledUnknownAndMissingFieldsStayOutOrSayWhatWasReported() throws Exception {
        assertEquals("Cancelled", value(FeatureData.historyDetail(row("t", "a.gcode").put("task_status", 2), null, Locale.ENGLISH, UTC), "Result"));
        assertEquals("Reported state 9", value(FeatureData.historyDetail(row("t", "a.gcode").put("task_status", 9), null, Locale.ENGLISH, UTC), "Result"));
        List<String[]> bare = FeatureData.historyDetail(new JSONObject().put("task_name", "a.gcode"), null, Locale.ENGLISH, UTC);
        assertTrue("nothing is invented for fields the printer did not give: " + bare.size(), bare.isEmpty());
        List<String[]> noEnd = FeatureData.historyDetail(new JSONObject().put("begin_time", BEGIN).put("task_status", 2), null, Locale.ENGLISH, UTC);
        assertNull(value(noEnd, "Duration")); assertNull(value(noEnd, "Ended")); assertNotNull(value(noEnd, "Started"));
        JSONObject failedVideo = row("t", "a.gcode").put("time_lapse_video_status", 3);
        assertEquals("The printer could not make the video", value(FeatureData.historyDetail(failedVideo, null, Locale.ENGLISH, UTC), "Timelapse"));
    }
    @Test public void historyRowsComeNewestFirstLikeTheListedEntries() throws Exception {
        JSONObject result = new JSONObject().put("history_task_list", new JSONArray().put(row("old", "old.gcode")).put(row("new", "new.gcode")));
        List<JSONObject> rows = FeatureData.historyRows(result);
        assertEquals("new", rows.get(0).getString("task_id")); assertEquals("old", rows.get(1).getString("task_id"));
        assertEquals(FeatureData.historyEntries(result).size(), rows.size());
    }

    // ---- 1037 answer: layout not documented, so shown under the printer's own names. The fixture is a made-up shape. ----
    @Test public void whateverScalarFieldsTheDetailAnswerHasAreShownAndNothingElse() throws Exception {
        JSONObject detail = new JSONObject("{\"error_code\":0,\"task_id\":\"t1\",\"thumbnail\":\"AAAA\",\"total_layer\":212,\"nozzle_diameter\":0.4,"
            + "\"plate_type\":\"Smooth Build Plate (Side B)\",\"link\":\"https://example.test/x\",\"empty\":\"\","
            + "\"filament_list\":[{\"tray\":\"A1\",\"type\":\"PLA\",\"weight_g\":23.97},{\"tray\":\"A2\",\"type\":\"PETG\"}]}");
        List<String[]> lines = FeatureData.historyDetail(row("t1", "a.gcode"), detail, Locale.ENGLISH, UTC);
        assertEquals("212", value(lines, "Total layer")); assertEquals("0.4", value(lines, "Nozzle diameter"));
        assertEquals("Smooth Build Plate (Side B)", value(lines, "Plate type"));
        assertEquals("Tray A1 · Type PLA · Weight g 23.97", value(lines, "Filament list 1")); assertEquals("Tray A2 · Type PETG", value(lines, "Filament list 2"));
        for (String hidden : new String[] {"Error code", "Task id", "Thumbnail", "Link", "Empty"}) assertNull(hidden, value(lines, hidden));
    }

    // ---- viewer without the file ----
    @Test public void theViewerWithoutAFileShowsTheRunningPrintAndOnePlainLine() throws Exception {
        JSONObject machine = new JSONObject().put("progress", 41);
        JSONObject print = new JSONObject().put("current_layer", 87).put("total_layer", 212).put("remaining_time_sec", 4520);
        assertEquals("41% · layer 87 of 212 · 1h 15m left", StatusPresentation.runningLine(machine, print));
        assertEquals("only what is reported", "41%", StatusPresentation.runningLine(machine, new JSONObject()));
        assertEquals("Printing", StatusPresentation.runningLine(null, null));
        assertEquals("The toolpath needs the G-code file. Files sent from this phone, or downloaded over the local connection, show here.", StatusPresentation.NO_FILE_LINE);
    }

    // ---- Print again ----
    private static JSONObject page(int total, String... names) throws Exception {
        JSONArray list = new JSONArray(); for (String name : names) list.put(new JSONObject().put("filename", name).put("size", 100));
        return new JSONObject().put("file_list", list).put("total", total);
    }
    @Test public void aListedFileCanBePrintedAgainWhenTheListingIsFresh() throws Exception {
        PrintAgain ready = PrintAgain.check("a.gcode", page(2, "a.gcode", "b.gcode"), "local", 0, true);
        assertEquals(PrintAgain.State.READY, ready.state); assertEquals("a.gcode", ready.file.getString("filename"));
        PrintAgain stale = PrintAgain.check("a.gcode", page(2, "a.gcode", "b.gcode"), "local", 0, false);
        assertEquals(PrintAgain.State.REFRESH, stale.state); assertTrue(stale.reason, stale.reason.contains("Refresh files first"));
    }
    @Test public void aFileTheListingLacksIsGoneOnlyWhenTheListingIsFreshAndComplete() throws Exception {
        PrintAgain gone = PrintAgain.check("gone.gcode", page(2, "a.gcode", "b.gcode"), "local", 0, true);
        assertEquals(PrintAgain.State.MISSING, gone.state); assertEquals("This file is no longer on the printer.", gone.reason);
        assertEquals("a stale listing cannot say it is gone", PrintAgain.State.REFRESH, PrintAgain.check("gone.gcode", page(2, "a.gcode", "b.gcode"), "local", 0, false).state);
        assertEquals("only part of the files is loaded", PrintAgain.State.UNKNOWN, PrintAgain.check("gone.gcode", page(120, "a.gcode", "b.gcode"), "local", 0, true).state);
        assertEquals("a later page", PrintAgain.State.UNKNOWN, PrintAgain.check("gone.gcode", page(120, "a.gcode"), "local", 50, true).state);
        assertEquals("no listing yet", PrintAgain.State.UNKNOWN, PrintAgain.check("a.gcode", new JSONObject(), "local", 0, true).state);
        assertEquals("another storage is shown", PrintAgain.State.UNKNOWN, PrintAgain.check("a.gcode", page(1, "a.gcode"), "u-disk", 0, true).state);
        assertEquals(PrintAgain.State.MISSING, PrintAgain.check("", page(1, "a.gcode"), "local", 0, true).state);
    }
    @Test public void namesMustMatchExactlyAsThePrinterReportsThem() throws Exception {
        assertEquals(PrintAgain.State.MISSING, PrintAgain.check("A.gcode", page(1, "a.gcode"), "local", 0, true).state);
        assertEquals(PrintAgain.State.MISSING, PrintAgain.check("a.gcode", page(1, "dir/a.gcode"), "local", 0, true).state);
        assertEquals(PrintAgain.State.READY, PrintAgain.check("dir/a.gcode", page(1, "dir/a.gcode"), "local", 0, true).state);
    }
}
