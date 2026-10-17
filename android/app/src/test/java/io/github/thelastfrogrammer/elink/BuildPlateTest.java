package io.github.thelastfrogrammer.elink;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class BuildPlateTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test public void theSideAFileWasSlicedForIsReadFromItsStartOrItsSettings() throws Exception {
        assertEquals("A", GcodeInspector.plateSide(";printer_model:Elegoo Centauri Carbon 2\n;curr_bed_type:Textured PEI Plate\nG28\n"));
        assertEquals("B", GcodeInspector.plateSide("; curr_bed_type = High Temp Plate\n"));
        assertEquals("B", GcodeInspector.plateSide("; curr_bed_type = Cool Plate\r\n"));
        assertEquals("", GcodeInspector.plateSide(";curr_bed_type:{curr_bed_type}\n"));
        assertEquals("", GcodeInspector.plateSide("G1 X0\n"));
        // In a big file the settings block is at the end, past the first 512 KB.
        File big = folder.newFile("big.gcode");
        StringBuilder text = new StringBuilder(); while (text.length() < 1_200_000) text.append("G1 X10 Y10 E0.1\n");
        text.append("; CONFIG_BLOCK_START\n; curr_bed_type = High Temp Plate\n; CONFIG_BLOCK_END\n");
        Files.write(big.toPath(), text.toString().getBytes(StandardCharsets.UTF_8));
        assertEquals("B", GcodeInspector.plateSide(big));
        assertEquals("", GcodeInspector.plateSide(new File(folder.getRoot(), "missing.gcode")));
    }

    @Test public void onlyTheTwoSidesOfTheCc2PlateAreOffered() throws Exception {
        JSONObject definition = new JSONObject().put("type", "enum").put("enum", new JSONArray().put(new JSONArray().put("Cool Plate").put("Smooth Cool Plate")));
        SliceSettingsActivity.plateChoices(definition);
        JSONArray choices = definition.getJSONArray("enum");
        assertEquals(2, choices.length());
        assertEquals("Textured PEI Plate", choices.getJSONArray(0).getString(0)); assertEquals("High Temp Plate", choices.getJSONArray(1).getString(0));
        assertTrue(choices.getJSONArray(1).getString(1).contains("Smooth"));
    }

    @Test public void slicedFilamentsReadAsWords() throws Exception {
        JSONArray map = new JSONArray().put(new JSONObject().put("color", "#F88D36").put("t", 0).put("type", "PLA"));
        String text = FeatureData.slicedFilaments(map);
        assertTrue(text, text.startsWith("T1 PLA") && text.contains("#F88D36") && !text.contains("{"));
        assertEquals(text, FeatureData.slicedFilaments(map.toString()));
        assertEquals("", FeatureData.slicedFilaments(null));
    }
}
