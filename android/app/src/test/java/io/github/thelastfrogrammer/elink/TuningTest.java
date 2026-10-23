package io.github.thelastfrogrammer.elink;

import static org.junit.Assert.*;

import android.content.Context;
import android.os.Bundle;
import org.robolectric.RuntimeEnvironment;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** The tuning loop: settings read from a sliced file, what findings suggest, the kept tries, and what a slice was made from. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class TuningTest {
    private static File fixture() { return new File("src/test/resources/gcode/tolerance-cc2.gcode"); }
    private static RecordingAnalysis.Finding cooling(int layer) { return new RecordingAnalysis.Finding(RecordingAnalysis.Finding.COOLING, layer, "cooled"); }

    @Test public void theSettingsAreReadFromTheEndOfTheFile() {
        Map<String, String> c = Tuning.config(fixture());
        assertEquals("100", c.get("fan_max_speed")); assertEquals("50", c.get("fan_min_speed"));
        assertEquals("210", c.get("nozzle_temperature")); assertEquals("4", c.get("slow_down_layer_time"));
        assertEquals("5", c.get("top_shell_layers")); assertEquals("Elegoo PLA @ECC2", c.get("filament_settings_id"));
        assertFalse("only the offered keys and a few more", c.containsKey("travel_speed"));
        assertTrue(Tuning.config(new File("missing.gcode")).isEmpty());
    }

    @Test public void aLongCooledLayerSuggestsLessFanAndALittleMoreHeat() {
        List<Tuning.Change> changes = Tuning.suggest(Arrays.asList(cooling(68), cooling(70)), Tuning.config(fixture()));
        assertEquals(2, changes.size());   // fan_min_speed (50) is already below the new 70
        assertEquals("fan_max_speed", changes.get(0).key); assertEquals("70", changes.get(0).to); assertEquals("100", changes.get(0).from);
        assertEquals("nozzle_temperature", changes.get(1).key); assertEquals("215", changes.get(1).to);
        assertEquals("Part fan on short layers 100 → 70 %", changes.get(0).words());
        assertTrue(changes.get(0).why.contains("Layer 68"));
    }

    @Test public void suggestionsFollowEachFilamentAndItsLimits() {
        Map<String, String> c = new java.util.HashMap<>();
        c.put("fan_max_speed", "100,90"); c.put("fan_min_speed", "100,40"); c.put("nozzle_temperature", "220,238"); c.put("nozzle_temperature_range_high", "240,240");
        c.put("filament_max_volumetric_speed", "21,12");
        List<Tuning.Change> cool = Tuning.suggest(Collections.singletonList(cooling(10)), c);
        assertEquals("70,65", cool.get(0).to);
        assertEquals("fan_min_speed", cool.get(1).key); assertEquals("70,40", cool.get(1).to);   // never above the fan on short layers
        assertEquals("225,240", cool.get(2).to);                                                 // inside the filament's range
        List<Tuning.Change> drop = Tuning.suggest(Collections.singletonList(new RecordingAnalysis.Finding(RecordingAnalysis.Finding.NOZZLE_DROP, 0, "drop")), c);
        assertEquals("filament_max_volumetric_speed", drop.get(0).key); assertEquals("17,9.5", drop.get(0).to);
        assertTrue("a stall has no setting", Tuning.suggest(Collections.singletonList(new RecordingAnalysis.Finding(RecordingAnalysis.Finding.STALL, 4, "stall")), c).isEmpty());
        assertNotNull(Tuning.noSetting(RecordingAnalysis.Finding.BED_DROP)); assertNull(Tuning.noSetting(RecordingAnalysis.Finding.COOLING));
    }

    @Test public void typedValuesAreChecked() {
        assertNull(Tuning.check("fan_max_speed", "70")); assertNull(Tuning.check("nozzle_temperature", "215,220"));
        assertNotNull(Tuning.check("fan_max_speed", "120")); assertNotNull(Tuning.check("fan_max_speed", "7a"));
        assertNotNull(Tuning.check("top_shell_layers", "5.5")); assertNotNull(Tuning.check("travel_speed", "100"));
    }

    @Test public void aTryIsKeptAndTheNextPrintOfTheFileSaysWhetherItHelped() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        File dir = Files.createTempDirectory("tuning").toFile();
        PrintRecorder.Recording before = new PrintRecorder.Recording(new File(dir, "1.csv"), new File(dir, "1.json")); before.file = "mount.gcode"; before.start = 1000;
        PrintRecorder.Recording after = new PrintRecorder.Recording(new File(dir, "2.csv"), new File(dir, "2.json")); after.file = "mount.gcode"; after.start = 5000;
        PrintRecorder.Recording other = new PrintRecorder.Recording(new File(dir, "3.csv"), new File(dir, "3.json")); other.file = "other.gcode"; other.start = 6000;
        Tuning.Trial request = Tuning.parse(Tuning.request("mount.gcode", before, "mount · 1 Oct", Tuning.suggest(Collections.singletonList(cooling(68)), Tuning.config(fixture())),
            Collections.singletonList(RecordingAnalysis.Finding.COOLING)));
        request.at = 2000; Tuning.remember(context, request);
        List<Tuning.Trial> all = Tuning.all(context);
        assertNotNull(Tuning.trialFor(all, after)); assertNull("the print it came from", Tuning.trialFor(all, before)); assertNull(Tuning.trialFor(all, other));
        assertEquals(1, Tuning.triedFrom(all, before).size());
        Tuning.Trial trial = Tuning.trialFor(all, after);
        assertEquals("Part fan on short layers 100 → 70 %, Nozzle temperature 210 → 215 °C", trial.changeWords());
        assertTrue(Tuning.helped(trial, new ArrayList<>()));
        assertEquals("A layer cooling much longer than the rest: not seen this time.", Tuning.outcome(trial, new ArrayList<>(), true));
        assertFalse(Tuning.helped(trial, Collections.singletonList(cooling(71))));
        assertEquals("A layer cooling much longer than the rest: seen again on layer 71.", Tuning.outcome(trial, Collections.singletonList(cooling(71)), true));
        Tuning.result(context, trial, "Helped", after);
        assertEquals("Helped", Tuning.triedFrom(Tuning.all(context), before).get(0).result);
    }

    @Test public void whatASliceWasMadeFromIsKeptWithIt() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        File pick = new File(context.getCacheDir(), "slice-input/1"); pick.mkdirs();
        File model = new File(pick, "mount.stl"); Files.write(model.toPath(), "solid mount".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(SliceStore.directory(context), "mount.gcode").toPath(), "G1".getBytes(StandardCharsets.UTF_8));
        Bundle state = new Bundle();
        state.putStringArrayList("models", new ArrayList<>(Collections.singletonList(model.getPath()))); state.putString("process", "0.20mm Standard");
        state.putInt("support", 2); state.putBoolean("projectSettings", true); state.putIntArray("modelSlots", new int[] {1}); state.putDoubleArray("bandChanges", new double[] {3, 2});
        state.putString("sliced", "/old/result.gcode");
        SliceRecipe.keep(context, Collections.singletonList("mount.gcode"), state, Collections.singletonList(model));
        model.delete();   // the cache's pick is gone; the kept copy is not
        Bundle back = SliceRecipe.load(context, "mount.gcode");
        assertNotNull(back);
        File kept = new File(back.getStringArrayList("models").get(0));
        assertEquals("the slice is named after the model", "mount.stl", kept.getName()); assertTrue(kept.isFile());
        assertEquals("0.20mm Standard", back.getString("process")); assertEquals(2, back.getInt("support")); assertTrue(back.getBoolean("projectSettings"));
        assertArrayEquals(new int[] {1}, back.getIntArray("modelSlots")); assertArrayEquals(new double[] {3, 2}, back.getDoubleArray("bandChanges"), 0);
        assertNull("the result is not part of it", back.getString("sliced"));
        assertNull(SliceRecipe.load(context, "other.gcode"));
        // Once the slice is gone, so are its recipe and model copy.
        new File(SliceStore.directory(context), "mount.gcode").delete();
        SliceRecipe.prune(context);
        assertNull(SliceRecipe.load(context, "mount.gcode")); assertFalse(kept.exists());
    }
}
