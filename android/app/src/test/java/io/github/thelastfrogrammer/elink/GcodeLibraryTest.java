package io.github.thelastfrogrammer.elink;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class GcodeLibraryTest {
    private static File temp(String content) throws Exception {
        File file = File.createTempFile("source", ".gcode"); Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8)); return file;
    }

    @Test public void storesAndFindsByThePrinterFileName() throws Exception {
        File dir = Files.createTempDirectory("library").toFile();
        GcodeLibrary library = new GcodeLibrary(dir);
        library.put(temp("first"), "Benchy_PLA.gcode");
        File replaced = library.put(temp("second"), "Benchy_PLA.gcode");
        assertEquals("second", new String(Files.readAllBytes(replaced.toPath()), StandardCharsets.UTF_8));
        assertEquals(replaced, library.find("Benchy_PLA.gcode"));
        assertEquals("the printer may report a path", replaced, library.find("/local/Benchy_PLA.gcode"));
        assertNull(library.find("Other.gcode"));
        assertNull(library.find(null));
        assertEquals(1, library.list().size());
    }

    @Test public void namesCannotEscapeTheDirectory() throws Exception {
        File dir = Files.createTempDirectory("library").toFile();
        GcodeLibrary library = new GcodeLibrary(dir);
        File stored = library.put(temp("x"), "../../evil name?.gcode");
        assertEquals(dir.getCanonicalFile(), stored.getCanonicalFile().getParentFile());
        assertEquals("evil name_.gcode", stored.getName());
        assertNull(GcodeLibrary.safeName(".."));
        try { library.put(temp("x"), "dir/"); fail(); } catch (java.io.IOException expected) { }
    }

    @Test public void keepsOnlyTheMostRecentFiles() throws Exception {
        File dir = Files.createTempDirectory("library").toFile();
        GcodeLibrary library = new GcodeLibrary(dir);
        for (int i = 0; i < GcodeLibrary.KEEP_FILES + 3; i++) {
            File stored = library.put(temp("g" + i), "part" + i + ".gcode");
            stored.setLastModified(1_000_000_000_000L + i * 1000L);
        }
        library.put(temp("again"), "part0.gcode"); // touching a file makes it the newest
        assertEquals(GcodeLibrary.KEEP_FILES, library.list().size());
        assertNotNull(library.find("part0.gcode"));
        assertNotNull(library.find("part" + (GcodeLibrary.KEEP_FILES + 2) + ".gcode"));
        assertNull(library.find("part1.gcode"));
    }
}
