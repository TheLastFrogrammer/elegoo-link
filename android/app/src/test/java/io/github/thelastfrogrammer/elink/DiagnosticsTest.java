package io.github.thelastfrogrammer.elink;

import java.io.File;
import java.nio.file.Files;
import org.junit.Test;
import static org.junit.Assert.*;

public class DiagnosticsTest {
    @Test public void keepsTheLatestEntriesPerAreaAcrossRestarts() throws Exception {
        File directory = Files.createTempDirectory("diagnostics").toFile();
        Diagnostics.init(directory); Diagnostics.clear();
        assertTrue(Diagnostics.isEmpty());
        assertTrue(Diagnostics.report("App test").contains("Nothing recorded yet"));
        for (int i = 0; i < Diagnostics.KEEP_PER_AREA + 5; i++) Diagnostics.note(Diagnostics.FOLLOW, "printer layer " + i + "\nsecond line");
        Diagnostics.note(Diagnostics.SLICER, "sliced in 1.0 s");
        String report = Diagnostics.report("App test");
        assertTrue(report.startsWith("Link Workshop diagnostics\nApp test\n"));
        assertTrue(report.contains("## Live toolpath")); assertTrue(report.contains("## Slicer"));
        assertFalse("oldest entries dropped", report.contains("printer layer 4 "));
        assertTrue(report.contains("printer layer 5 second line"));
        assertTrue(report.contains("printer layer 44 second line"));

        // A new process reads the saved log back.
        Diagnostics.init(Files.createTempDirectory("other").toFile());
        assertTrue(Diagnostics.isEmpty());
        Diagnostics.init(directory);
        assertEquals(report, Diagnostics.report("App test"));
        Diagnostics.clear();
        Diagnostics.init(Files.createTempDirectory("other").toFile()); Diagnostics.init(directory);
        assertTrue(Diagnostics.isEmpty());
    }

    @Test public void readsProcessMemoryOnLinux() {
        if (!new File("/proc/self/status").isFile()) return;
        assertTrue(Diagnostics.memory(), Diagnostics.memory().matches("peak RSS \\d+ kB, now \\d+ kB"));
    }
    @Test public void cameraLogHoldsNoAddressesOrSerialsAndListsRecentEvents() throws Exception {
        assertEquals("failed to connect to <address> (port 8080)", Diagnostics.scrub("failed to connect to 192.168.1.50:8080 (port 8080)"));
        assertEquals("channel <id> left", Diagnostics.scrub("channel F01PLX9A3K2B7Q left"));
        assertEquals("Connected. Waiting for video", Diagnostics.scrub("Connected. Waiting for video"));
        assertEquals("", Diagnostics.scrub(null));
        Diagnostics.init(Files.createTempDirectory("camera").toFile()); Diagnostics.clear();
        for (int i = 0; i < 5; i++) Diagnostics.note(Diagnostics.CAMERA, "event " + i);
        java.util.List<String> recent = Diagnostics.recent(Diagnostics.CAMERA, 3);
        assertEquals(3, recent.size()); assertTrue(recent.get(0).endsWith("event 2")); assertTrue(recent.get(2).endsWith("event 4"));
        assertTrue(Diagnostics.recent("Nothing here", 3).isEmpty());
        // A picture names its source in the log; one without a print says why it was not kept.
        PrintRecorder recorder = new PrintRecorder(Files.createTempDirectory("recordings").toFile());
        assertFalse(recorder.savePicture(new byte[200], 1_000, "the Print tab's cloud video"));
        assertTrue(Diagnostics.recent(Diagnostics.CAMERA, 1).get(0).endsWith("picture from the Print tab's cloud video not kept: no print is being recorded"));
        assertEquals(-1, recorder.secondsSincePicture(1_000));
    }
    @Test public void logViewerShowsAllAreasNewestFirstAndFilters() {
        java.util.Map<String, java.util.List<String>> log = new java.util.LinkedHashMap<>();
        log.put("Camera", java.util.Arrays.asList("2026-10-15 10:00:01  stream opening", "2026-10-15 10:00:05  first picture"));
        log.put("Connection", java.util.Arrays.asList("2026-10-15 10:00:03  Connected to <address>"));
        java.util.List<String> areas = new java.util.ArrayList<>(log.keySet());
        java.util.List<String> all = LogsActivity.select(areas, log::get, "All", "");
        assertEquals(java.util.Arrays.asList("2026-10-15 10:00:05  [Camera] first picture", "2026-10-15 10:00:03  [Connection] Connected to <address>", "2026-10-15 10:00:01  [Camera] stream opening"), all);
        assertEquals(java.util.Arrays.asList("2026-10-15 10:00:03  Connected to <address>"), LogsActivity.select(areas, log::get, "Connection", ""));
        assertEquals(1, LogsActivity.select(areas, log::get, "All", "picture").size());
        assertTrue(LogsActivity.select(areas, log::get, "Camera", "connected").isEmpty());
        assertEquals("Pause", Cc2Codec.name(Cc2Codec.PAUSE)); assertEquals("File list", Cc2Codec.name(Cc2Codec.FILES)); assertEquals("Method 9999", Cc2Codec.name(9999));
    }
}
