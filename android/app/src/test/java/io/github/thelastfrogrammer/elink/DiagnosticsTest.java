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
}
