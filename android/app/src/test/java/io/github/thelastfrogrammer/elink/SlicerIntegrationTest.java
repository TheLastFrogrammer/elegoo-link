package io.github.thelastfrogrammer.elink;

import android.content.Context;
import org.robolectric.RuntimeEnvironment;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.view.View;
import android.widget.Spinner;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

/**
 * Drives the real slicer through NativeSlicer: asset unpacking, the JNI calls, progress, cancellation and errors.
 * Opt-in, since it needs a host build of the JNI library and the slicer assets in the app:
 *   slicer/scripts/build-linux.sh && slicer/scripts/install-into-app.sh
 *   ./gradlew testDebugUnitTest --tests '*SlicerIntegrationTest*' -DslicerLib=<slicer work>/build-linux
 */
// One configuration for every test: the JVM loads a native library into a single classloader, so all tests must
// share one Robolectric sandbox.
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 34, qualifiers = "w411dp-h891dp-xxhdpi")
public class SlicerIntegrationTest {
    private static final String PRINTER = "Elegoo Centauri Carbon 2 0.4 nozzle", PROCESS = "0.20mm Standard @Elegoo CC2 0.4 nozzle", PLA = "Elegoo PLA @ECC2";

    @Before public void optIn() { // per test: RobolectricTestRunner does not skip on an assumption failing in @BeforeClass
        Assume.assumeFalse("Needs -DslicerLib", System.getProperty("slicerLib", "").isEmpty()); }

    private static Context context() { return RuntimeEnvironment.getApplication(); }

    /** An ASCII STL box, size in mm, standing at the origin. */
    private static File box(double x, double y, double z) throws IOException {
        double[][] v = {{0,0,0},{x,0,0},{x,y,0},{0,y,0},{0,0,z},{x,0,z},{x,y,z},{0,y,z}};
        int[][] faces = {{0,2,1},{0,3,2},{4,5,6},{4,6,7},{0,1,5},{0,5,4},{1,2,6},{1,6,5},{2,3,7},{2,7,6},{3,0,4},{3,4,7}};
        StringBuilder stl = new StringBuilder("solid box\n");
        for (int[] f : faces) {
            stl.append("facet normal 0 0 0\nouter loop\n");
            for (int i : f) stl.append(String.format(Locale.ROOT, "vertex %f %f %f\n", v[i][0], v[i][1], v[i][2]));
            stl.append("endloop\nendfacet\n");
        }
        stl.append("endsolid box\n");
        File file = File.createTempFile("box", ".stl");
        Files.write(file.toPath(), stl.toString().getBytes(StandardCharsets.US_ASCII));
        return file;
    }

    @Test public void listsTheCentauriCarbon2Presets() throws Exception {
        assertTrue(NativeSlicer.available(context()));
        try (NativeSlicer slicer = NativeSlicer.open(context(), "Elegoo")) {
            assertTrue(Arrays.asList(slicer.presets(NativeSlicer.PRINTER, null)).contains(PRINTER));
            List<String> processes = Arrays.asList(slicer.presets(NativeSlicer.PROCESS, PRINTER));
            assertTrue(processes.contains(PROCESS));
            for (String process : processes) assertTrue(process, process.contains("CC2 0.4"));
            assertTrue(Arrays.asList(slicer.presets(NativeSlicer.FILAMENT, PRINTER)).contains(PLA));
        }
        assertTrue(new File(context().getFilesDir(), "slicer/profiles/Elegoo.json").isFile());
    }

    @Test public void slicesABoxWithProgressAndOverrides() throws Exception {
        File output = new File(context().getCacheDir(), "box.gcode");
        AtomicInteger calls = new AtomicInteger(), last = new AtomicInteger(-1);
        NativeSlicer.Result result;
        try (NativeSlicer slicer = NativeSlicer.open(context(), "Elegoo")) {
            result = slicer.slice(Collections.singletonList(box(20, 20, 10)), PRINTER, PROCESS, Collections.singletonList(PLA),
                Collections.singletonList(new String[] {"sparse_infill_density", "40%"}), output, (percent, text) -> { calls.incrementAndGet(); last.set(percent); return true; });
        }
        assertTrue(result.gcode.isFile());
        assertEquals(output.getCanonicalFile(), result.gcode.getCanonicalFile());
        assertTrue(result.printSeconds > 60); assertTrue(result.filamentGrams > 1);
        assertTrue(calls.get() > 5); assertEquals(100, last.get());
        String gcode = new String(Files.readAllBytes(output.toPath()), StandardCharsets.UTF_8);
        assertTrue(gcode.contains("; total layers count = 50"));
        assertTrue(gcode.contains("; sparse_infill_density = 40%"));
        assertTrue(gcode.contains("; curr_bed_type = Textured PEI Plate"));
        // The engine renders the 144x144 PNG preview the CC2 profile asks for, and the app's inspector finds it.
        assertTrue(gcode.contains("; thumbnail begin 144x144 "));
        GcodeInspector.Report report = GcodeInspector.inspect(output);
        assertNotNull(report.thumbnail);
        android.graphics.Bitmap preview = ThumbnailDecoder.decode(report.thumbnail);
        assertNotNull(preview);
        assertEquals(144, preview.getWidth());
        // Placed on the CC2's 256 mm bed: the first layer's moves stay inside it.
        assertFalse(gcode.matches("(?s).*\\nG1 X(-|2[6-9]\\d|[3-9]\\d\\d).*"));
    }

    @Test public void slicesTwoFilamentsWithTrayColoursAndAssignment() throws Exception {
        File output = new File(context().getCacheDir(), "two.gcode");
        List<File> models = Arrays.asList(box(20, 20, 10), box(15, 15, 10));
        try (NativeSlicer slicer = NativeSlicer.open(context(), "Elegoo")) {
            NativeSlicer.Result result = slicer.slice(models, PRINTER, PROCESS, Arrays.asList(PLA, "Elegoo PLA Matte @ECC2"), Arrays.asList("#D02828", "#F0F0F0"),
                new int[] {1, 2}, Collections.emptyList(), output, null);
            assertTrue(result.gcode.isFile());
            String gcode = new String(Files.readAllBytes(output.toPath()), StandardCharsets.UTF_8);
            assertTrue(gcode.contains("; filament_colour = #D02828;#F0F0F0"));
            assertTrue(gcode.contains("; filament_settings_id = \"Elegoo PLA @ECC2\";\"Elegoo PLA Matte @ECC2\""));
            assertTrue("switches to the second filament", gcode.contains("\nM6211 T1 ") && gcode.contains("\nT1\n"));
            // Flushing volumes from the two colours (white after red needs more than nothing), and a prime tower.
            java.util.regex.Matcher flush = java.util.regex.Pattern.compile("; flush_volumes_matrix = 0,(\\d+),(\\d+),0").matcher(gcode);
            assertTrue(flush.find()); assertTrue(Integer.parseInt(flush.group(1)) > 0);
            assertTrue(gcode.contains("; enable_prime_tower = 1"));
            // A slot the plate does not have.
            try {
                slicer.slice(models, PRINTER, PROCESS, Collections.singletonList(PLA), null, new int[] {1, 2}, Collections.emptyList(), output, null);
                fail("expected an error for filament slot 2 of 1");
            } catch (IOException expected) { assertTrue(expected.getMessage(), expected.getMessage().contains("2")); }
            try {
                slicer.slice(models, PRINTER, PROCESS, Arrays.asList(PLA, PLA), Arrays.asList("red", ""), null, Collections.emptyList(), output, null);
                fail("expected an error for a malformed colour");
            } catch (IOException expected) { assertTrue(expected.getMessage(), expected.getMessage().contains("#RRGGBB")); }
        }
    }

    private static File fixture(String name) throws IOException {
        File file = new File(context().getCacheDir(), name);
        try (InputStream in = SlicerIntegrationTest.class.getResourceAsStream("/" + name)) { Files.copy(in, file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
        return file;
    }

    /** A two-plate project saved by the official ElegooSlicer, with 0.28 mm layers and 35% infill in its settings. */
    @Test public void inspectsArrangesAndSlicesAProjectPlate() throws Exception {
        List<File> project = Collections.singletonList(fixture("twoplate_project.3mf"));
        File meshes = new File(context().getCacheDir(), "meshes");
        try (NativeSlicer slicer = NativeSlicer.open(context(), "Elegoo")) {
            org.json.JSONObject inspected = slicer.inspect(project, meshes, 5000);
            org.json.JSONObject file = inspected.getJSONArray("files").getJSONObject(0);
            assertEquals(2, file.getJSONArray("objects").length());
            assertEquals(2, file.getJSONArray("plates").length());
            assertEquals("[[1,0]]", file.getJSONArray("plates").getJSONObject(1).getJSONArray("objects").toString());
            assertEquals(PROCESS, file.getJSONObject("project").getString("process"));
            assertEquals(0.28, file.getJSONObject("project").getDouble("layer_height"), 1e-9);
            assertEquals(1920, inspected.getLong("triangles")); // 968 each in the STLs, less the degenerate triangles at the poles
            byte[] mesh = Files.readAllBytes(new File(file.getJSONArray("objects").getJSONObject(0).getString("mesh")).toPath());
            assertEquals("LKM1", new String(mesh, 0, 4, StandardCharsets.US_ASCII));
            int triangles = java.nio.ByteBuffer.wrap(mesh, 4, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt();
            assertEquals(8 + triangles * 37, mesh.length);

            NativeSlicer.Selection selection = new NativeSlicer.Selection(PRINTER, PROCESS, Collections.singletonList(PLA));
            selection.plate = 2;
            org.json.JSONObject layout = slicer.arrange(project, selection);
            assertTrue(layout.getBoolean("kept_layout"));
            org.json.JSONArray placed = layout.getJSONArray("placements");
            assertEquals(1, placed.length());
            assertEquals(1, placed.getJSONObject(0).getInt("object"));
            assertEquals(128, placed.getJSONObject(0).getDouble("x"), 0.5);
            assertEquals(128, placed.getJSONObject(0).getDouble("y"), 0.5);

            File output = new File(context().getCacheDir(), "plate2.gcode");
            selection.projectSettings = true;
            slicer.slice(project, selection, output, null);
            String gcode = new String(Files.readAllBytes(output.toPath()), StandardCharsets.UTF_8);
            assertTrue(gcode.contains("; layer_height = 0.28")); assertTrue(gcode.contains("; sparse_infill_density = 35%"));
            assertTrue(gcode.contains("; total layers count = 22"));
            selection.projectSettings = false;
            selection.overrides.put("sparse_infill_density", "10%");
            slicer.slice(project, selection, output, null);
            gcode = new String(Files.readAllBytes(output.toPath()), StandardCharsets.UTF_8);
            assertTrue(gcode.contains("; layer_height = 0.2\n")); assertTrue(gcode.contains("; sparse_infill_density = 10%"));
            try {
                selection.plate = 3; slicer.slice(project, selection, output, null); fail("expected an error for plate 3");
            } catch (IOException expected) { assertTrue(expected.getMessage(), expected.getMessage().contains("2 plate")); }
        }
    }

    @Test public void placesTurnsScalesAndCopies() throws Exception {
        List<File> model = Collections.singletonList(box(20, 20, 10));
        try (NativeSlicer slicer = NativeSlicer.open(context(), "Elegoo")) {
            NativeSlicer.Selection selection = new NativeSlicer.Selection(PRINTER, PROCESS, Collections.singletonList(PLA));
            selection.overrides.put("brim_type", "no_brim"); selection.overrides.put("skirt_loops", "0");
            selection.placements.add(new double[] {0, 0, 60, 70, 45, 2});
            File output = new File(context().getCacheDir(), "placed.gcode");
            slicer.slice(model, selection, output, null);
            // Layer 5's extrusions: a 40 mm square turned 45 degrees, centered on (60, 70); the CC2's extruder_offset
            // (0, 1.5) shifts G-code Y by -1.5 from bed coordinates.
            double minX = 1e9, maxX = -1e9, minY = 1e9, maxY = -1e9; int layer = 0;
            for (String line : Files.readAllLines(output.toPath())) {
                if (line.startsWith(";LAYER_CHANGE")) layer++;
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("^G1 X([\\d.]+) Y([\\d.]+) E").matcher(line);
                if (layer == 5 && m.find()) { double x = Double.parseDouble(m.group(1)), y = Double.parseDouble(m.group(2)); minX = Math.min(minX, x); maxX = Math.max(maxX, x); minY = Math.min(minY, y); maxY = Math.max(maxY, y); }
            }
            assertEquals(60, (minX + maxX) / 2, 0.1); assertEquals(68.5, (minY + maxY) / 2, 0.1);
            assertEquals(40 * Math.sqrt(2), maxX - minX, 1.0);

            // Arranging two copies (one turned) keeps both, apart from each other.
            selection.placements.clear();
            selection.placements.add(new double[] {0, 0, 128, 128, 0, 1});
            selection.placements.add(new double[] {0, 0, 128, 128, 30, 1});
            org.json.JSONArray arranged = slicer.arrange(model, selection).getJSONArray("placements");
            assertEquals(2, arranged.length());
            assertEquals(30, Math.abs(arranged.getJSONObject(1).getDouble("rotation")), 0.01);
            double gap = Math.hypot(arranged.getJSONObject(0).getDouble("x") - arranged.getJSONObject(1).getDouble("x"), arranged.getJSONObject(0).getDouble("y") - arranged.getJSONObject(1).getDouble("y"));
            assertTrue("copies apart: " + gap, gap > 20);
        }
    }

    @Test public void describesSettings() throws Exception {
        try (NativeSlicer slicer = NativeSlicer.open(context(), "Elegoo")) {
            NativeSlicer.Selection selection = new NativeSlicer.Selection(PRINTER, PROCESS, Collections.singletonList(PLA));
            selection.overrides.put("sparse_infill_density", "40%");
            org.json.JSONObject described = slicer.describe(Collections.emptyList(), selection, Arrays.asList("sparse_infill_density", "seam_position", "enable_support", "no_such_key"));
            org.json.JSONObject infill = described.getJSONObject("sparse_infill_density");
            assertEquals("percent", infill.getString("type")); assertEquals("15%", infill.getString("preset")); assertEquals("40%", infill.getString("value"));
            assertEquals(100, infill.getDouble("max"), 0);
            assertEquals("enum", described.getJSONObject("seam_position").getString("type"));
            assertTrue(described.getJSONObject("seam_position").getJSONArray("enum").length() >= 4);
            assertEquals("bool", described.getJSONObject("enable_support").getString("type"));
            assertFalse(described.has("no_such_key"));
            for (String[] group : SliceSettingsActivity.GROUPS) {
                org.json.JSONObject all = slicer.describe(Collections.emptyList(), selection, Arrays.asList(group).subList(1, group.length));
                for (int i = 1; i < group.length; i++) assertTrue("unknown setting " + group[i], all.has(group[i]));
            }
        }
    }

    @Test public void cancellingStopsTheSlice() throws Exception {
        try (NativeSlicer slicer = NativeSlicer.open(context(), "Elegoo")) {
            slicer.slice(Collections.singletonList(box(20, 20, 10)), PRINTER, PROCESS, Collections.singletonList(PLA), Collections.emptyList(),
                new File(context().getCacheDir(), "cancelled.gcode"), (percent, text) -> percent < 20);
            fail("expected cancellation");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().toLowerCase(Locale.ROOT).contains("cancel"));
        }
    }

    @Test public void engineErrorsBecomeReadableExceptions() throws Exception {
        try (NativeSlicer slicer = NativeSlicer.open(context(), "Elegoo")) {
            try {
                slicer.slice(Collections.singletonList(box(20, 20, 10)), PRINTER, PROCESS, Collections.singletonList(PLA),
                    Collections.singletonList(new String[] {"no_such_setting", "1"}), new File(context().getCacheDir(), "x.gcode"), null);
                fail("expected an error");
            } catch (IOException expected) { assertEquals("Unknown setting: no_such_setting", expected.getMessage()); }
            try {
                slicer.slice(Collections.singletonList(box(400, 20, 10)), PRINTER, PROCESS, Collections.singletonList(PLA), Collections.emptyList(),
                    new File(context().getCacheDir(), "y.gcode"), null);
                fail("expected an error for a part larger than the bed");
            } catch (IOException expected) { assertFalse(expected.getMessage().isEmpty()); }
        }
    }

    /** Recreating the Slice screen (theme change, process restore) keeps models, filament slots, settings and the result. */
    @Test public void sliceScreenSurvivesRecreation() throws Exception {
        org.robolectric.android.controller.ActivityController<SliceActivity> controller = Robolectric.buildActivity(SliceActivity.class).setup();
        SliceActivity activity = controller.get();
        waitFor(() -> spinnerFilled(activity, "processSpinner") && firstSlotFilled(activity));
        File imported = new File(context().getCacheDir(), "slice-input/keep_box.stl"); imported.getParentFile().mkdirs();
        Files.copy(box(20, 20, 10).toPath(), imported.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        @SuppressWarnings("unchecked") List<File> models = (List<File>) field(activity, "models"); models.clear(); models.add(imported);
        java.lang.reflect.Method addSlot = SliceActivity.class.getDeclaredMethod("addSlot", TrayPlan.Tray.class); addSlot.setAccessible(true);
        addSlot.invoke(activity, new TrayPlan.Tray(0, 2, "PLA", "PLA Matte", "ELEGOO", "#1E5AA8"));
        @SuppressWarnings("unchecked") List<Object> slots = (List<Object>) field(activity, "slots");
        java.lang.reflect.Field colour = slots.get(0).getClass().getDeclaredField("colour"); colour.setAccessible(true); colour.set(slots.get(0), "#D02828");
        ((android.widget.EditText) field(activity, "infill")).setText("35");
        ((Spinner) field(activity, "supportSpinner")).setSelection(3);
        invoke(activity, "showModels"); invoke(activity, "updateButtons");
        invoke(activity, "startSlice");
        waitFor(() -> ((View) field(activity, "resultCard")).getVisibility() == View.VISIBLE);

        controller.recreate();
        SliceActivity again = controller.get();
        assertNotSame(activity, again);
        waitFor(() -> spinnerFilled(again, "processSpinner") && firstSlotFilled(again));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        @SuppressWarnings("unchecked") List<File> kept = (List<File>) field(again, "models");
        assertEquals(Collections.singletonList(imported), kept);
        @SuppressWarnings("unchecked") List<Object> keptSlots = (List<Object>) field(again, "slots");
        assertEquals(2, keptSlots.size());
        assertEquals("#D02828", colour.get(keptSlots.get(0)));
        assertEquals("#1E5AA8", colour.get(keptSlots.get(1)));
        assertEquals("Elegoo PLA Matte @ECC2", ((Spinner) field(keptSlots.get(1), "preset")).getSelectedItem());
        java.lang.reflect.Field source = keptSlots.get(1).getClass().getDeclaredField("source"); source.setAccessible(true);
        assertEquals(2, ((TrayPlan.Tray) source.get(keptSlots.get(1))).trayId);
        assertEquals("35", ((android.widget.EditText) field(again, "infill")).getText().toString());
        assertEquals(3, ((Spinner) field(again, "supportSpinner")).getSelectedItemPosition());
        assertEquals(View.VISIBLE, ((View) field(again, "resultCard")).getVisibility());
        assertTrue(((android.widget.TextView) field(again, "resultText")).getText().toString().contains("keep_box.gcode"));
        assertEquals(PROCESS, ((Spinner) field(again, "processSpinner")).getSelectedItem());
    }

    /** With -Dscreenshots=<dir> as well: the Slice screen after a real slice, light and dark. */
    @Test public void renderSliceScreen() throws Exception {
        String out = System.getProperty("screenshots", "");
        Assume.assumeFalse("Screenshots are opt-in", out.isEmpty());
        File model = box(20, 20, 10);
        for (String theme : new String[] {"light", "dark"}) {
            context().getSharedPreferences("workshop-settings", 0).edit().putInt("theme", theme.equals("dark") ? 2 : 1).commit();
            SliceActivity activity = Robolectric.buildActivity(SliceActivity.class).setup().get();
            waitFor(() -> spinnerFilled(activity, "processSpinner") && firstSlotFilled(activity));
            @SuppressWarnings("unchecked") List<File> models = (List<File>) field(activity, "models"); models.clear();
            for (String name : new String[] {"calibration_box.stl", "small_box.stl"}) {
                File imported = new File(context().getCacheDir(), "slice-input/" + name); imported.getParentFile().mkdirs();
                Files.copy(model.toPath(), imported.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING); models.add(imported);
            }
            // Two slots from CANVAS trays, as "Fill from CANVAS trays" makes them (no printer in this test).
            java.lang.reflect.Method addSlot = SliceActivity.class.getDeclaredMethod("addSlot", TrayPlan.Tray.class); addSlot.setAccessible(true);
            java.lang.reflect.Method removeLast = SliceActivity.class.getDeclaredMethod("removeLastSlot"); removeLast.setAccessible(true);
            removeLast.invoke(activity);
            @SuppressWarnings("unchecked") List<Object> slots = (List<Object>) field(activity, "slots");
            java.lang.reflect.Method applyTray = SliceActivity.class.getDeclaredMethod("applyTray", slots.get(0).getClass(), TrayPlan.Tray.class); applyTray.setAccessible(true);
            applyTray.invoke(activity, slots.get(0), new TrayPlan.Tray(0, 0, "PLA", "PLA Matte", "ELEGOO", "#D02828"));
            addSlot.invoke(activity, new TrayPlan.Tray(0, 1, "PLA", "", "ELEGOO", "#F0F0F0"));
            invoke(activity, "showModels"); invoke(activity, "updateButtons");
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals("Elegoo PLA Matte @ECC2", ((Spinner) field(slots.get(0), "preset")).getSelectedItem());
            ((android.widget.EditText) field(activity, "infill")).setText("20");
            invoke(activity, "startSlice");
            waitFor(() -> ((View) field(activity, "resultCard")).getVisibility() == View.VISIBLE);
            View root = activity.getWindow().getDecorView();
            String plan = context().getSharedPreferences(SliceActivity.TRAY_PLANS, 0).getString("calibration_box_plate.gcode", null);
            assertEquals("{\"count\":2,\"tools\":[{\"t\":0,\"canvas_id\":0,\"tray_id\":0},{\"t\":1,\"canvas_id\":0,\"tray_id\":1}]}", plan);
            int width = 1080, height = 6200;
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, width, height);
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            root.draw(new Canvas(bitmap));
            File file = new File(out, "slice-" + theme + ".png"); file.getParentFile().mkdirs();
            try (FileOutputStream stream = new FileOutputStream(file)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream); }
        }
    }

    private interface Condition { boolean met() throws Exception; }
    private static void waitFor(Condition condition) throws Exception {
        long deadline = System.currentTimeMillis() + 60_000;
        while (true) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            if (condition.met()) return;
            if (System.currentTimeMillis() > deadline) fail("timed out");
            Thread.sleep(50);
        }
    }
    private static boolean spinnerFilled(Object activity, String name) throws Exception {
        Spinner spinner = (Spinner) field(activity, name); return spinner.getAdapter() != null && spinner.getAdapter().getCount() > 0;
    }
    private static boolean firstSlotFilled(Object activity) throws Exception {
        @SuppressWarnings("unchecked") List<Object> slots = (List<Object>) field(activity, "slots");
        Spinner spinner = (Spinner) field(slots.get(0), "preset"); return spinner.getAdapter() != null && spinner.getAdapter().getCount() > 0;
    }
    private static Object field(Object target, String name) throws Exception {
        java.lang.reflect.Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
    private static void invoke(Object target, String name) throws Exception {
        java.lang.reflect.Method method = target.getClass().getDeclaredMethod(name); method.setAccessible(true); method.invoke(target);
    }
}
