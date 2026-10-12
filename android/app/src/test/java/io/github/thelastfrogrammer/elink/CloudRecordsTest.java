package io.github.thelastfrogrammer.elink;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class CloudRecordsTest {
    @Test public void cloudFileRecordsBecomeThePrinterFileList() throws Exception {
        JSONObject data = new JSONObject().put("total", 14).put("list", new JSONArray()
            .put(new JSONObject().put("filename", "Hook.gcode").put("size", 123456).put("printTime", 3600).put("layer", 80)
                .put("createTime", 1760000000000L).put("lastPrintTime", 1760000100).put("totalFilamentUsed", 12.5).put("totalPrintTimes", 2)
                .put("colorMap", "[{\"color\":\"#000000\",\"name\":\"PLA\",\"t\":0}]").put("thumbnail", "thumbs/abc.png").put("md5", JSONObject.NULL))
            .put(new JSONObject().put("filename", "").put("size", 1))
            .put(new JSONObject().put("filename", "Bare.gcode").put("thumbnail", "null")));
        JSONObject files = CloudRecords.files(data, 50);
        assertEquals(14, files.getInt("total")); assertEquals(50, files.getInt("offset"));
        JSONArray list = files.getJSONArray("file_list");
        assertEquals("an empty name is skipped", 2, list.length());
        JSONObject hook = list.getJSONObject(0);
        assertEquals("Hook.gcode", hook.getString("filename")); assertEquals(123456, hook.getLong("size")); assertEquals(3600, hook.getInt("print_time"));
        assertEquals(80, hook.getInt("layer")); assertEquals("milliseconds become seconds", 1760000000L, hook.getLong("create_time")); assertEquals(1760000100L, hook.getLong("last_print_time"));
        assertEquals(12.5, hook.getDouble("total_filament_used"), 1e-9); assertEquals("#000000", hook.getJSONArray("color_map").getJSONObject(0).getString("color"));
        assertEquals("thumbs/abc.png", hook.getString("cloud_thumbnail")); assertFalse(hook.has("md5"));
        assertFalse("the text null is no thumbnail", list.getJSONObject(1).has("cloud_thumbnail"));
        assertEquals("what the app shows reads it like a printer answer", 2, FeatureData.normalizeFiles(files).getJSONArray("file_list").length());
    }

    @Test public void cloudHistoryBecomesOldestFirstWithSecondTimes() throws Exception {
        JSONObject data = new JSONObject().put("total", 2).put("list", new JSONArray()
            .put(new JSONObject().put("id", "e2").put("eventValue", new JSONObject().put("task_name", "New.gcode").put("task_status", 1).put("begin_time", 1760000000000L).put("end_time", 1760003600000L).toString()))
            .put(new JSONObject().put("id", "e1").put("eventValue", new JSONObject().put("task_name", "Old.gcode").put("task_status", 2).put("begin_time", 1750000000).put("end_time", 1750000600).put("task_id", "t1")))
            .put(new JSONObject().put("eventValue", "not json")));
        JSONArray rows = CloudRecords.history(data).getJSONArray("history_task_list");
        assertEquals(2, rows.length());
        assertEquals("Old.gcode", rows.getJSONObject(0).getString("task_name")); assertEquals("t1", rows.getJSONObject(0).getString("task_id"));
        assertEquals("New.gcode", rows.getJSONObject(1).getString("task_name")); assertEquals("e2", rows.getJSONObject(1).getString("task_id"));
        assertEquals(1760003600L - 1760000000L, rows.getJSONObject(1).getLong("end_time") - rows.getJSONObject(1).getLong("begin_time"));
        assertEquals("newest first in what History shows", "New", FeatureData.historyEntries(CloudRecords.history(data)).get(0)[0]);
    }
}
