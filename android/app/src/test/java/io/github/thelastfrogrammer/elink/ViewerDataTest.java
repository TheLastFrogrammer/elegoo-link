package io.github.thelastfrogrammer.elink;

import static org.junit.Assert.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;

/** Writes a real toolpath the way the viewer is served one, for tools/viewer-check.js (the viewer in a browser, run in CI). */
public class ViewerDataTest {
    @Test public void writesTheViewersData() throws Exception {
        GcodeToolpath path;
        try (java.io.InputStream in = getClass().getResourceAsStream("/gcode/tolerance-cc2.gcode")) { path = GcodeToolpath.read(in); }
        File out = new File("build/viewer-check/data"); out.mkdirs();
        Files.write(new File(out, "meta.json").toPath(), path.layersJson().getBytes(StandardCharsets.UTF_8));
        Files.write(new File(out, "segments.bin").toPath(), path.segmentsBinary());
        Files.write(new File(out, "travels.bin").toPath(), path.travelsBinary());
        assertTrue(path.count > 1000);
        assertEquals(path.count * 9 * 4, path.segmentsBinary().length);
    }
}
