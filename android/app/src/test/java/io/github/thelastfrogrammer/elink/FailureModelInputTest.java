package io.github.thelastfrogrammer.elink;

import android.content.Context;
import android.os.Looper;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Failure audit, model files: corrupt input through the real engine and the Slice screen. Opt-in like SlicerIntegrationTest
 * (needs -DslicerLib) and in the same Robolectric configuration, since the JVM loads the native library once.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 34, qualifiers = "w411dp-h891dp-xxhdpi")
public class FailureModelInputTest {
    @Before public void optIn() { Assume.assumeFalse("Needs -DslicerLib", System.getProperty("slicerLib", "").isEmpty()); }
    private static Context context() { return RuntimeEnvironment.getApplication(); }

    private static File write(String name, byte[] bytes) throws IOException {
        File dir = new File(context().getCacheDir(), "failure-models"); dir.mkdirs();
        File file = new File(dir, name); Files.write(file.toPath(), bytes); return file;
    }
    private static File box() throws IOException {
        double[][] v = {{0,0,0},{20,0,0},{20,20,0},{0,20,0},{0,0,10},{20,0,10},{20,20,10},{0,20,10}};
        int[][] faces = {{0,2,1},{0,3,2},{4,5,6},{4,6,7},{0,1,5},{0,5,4},{1,2,6},{1,6,5},{2,3,7},{2,7,6},{3,0,4},{3,4,7}};
        StringBuilder stl = new StringBuilder("solid box\n");
        for (int[] f : faces) { stl.append("facet normal 0 0 0\nouter loop\n"); for (int i : f) stl.append(String.format(Locale.ROOT, "vertex %f %f %f\n", v[i][0], v[i][1], v[i][2])); stl.append("endloop\nendfacet\n"); }
        return write("good_box.stl", stl.append("endsolid box\n").toString().getBytes(StandardCharsets.US_ASCII));
    }
    /** Binary STL whose header claims 1000 triangles but holds 3: what a cut-off download or a full phone leaves behind. */
    private static File truncatedStl() throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(80 + 4 + 3 * 50).order(ByteOrder.LITTLE_ENDIAN);
        buffer.position(80); buffer.putInt(1000);
        for (int t = 0; t < 3; t++) { buffer.position(84 + t * 50 + 12); for (float f : new float[] {0,0,0, 10,0,0, 0,10,0}) buffer.putFloat(f); }
        return write("truncated.stl", buffer.array());
    }
    private static File threeMfWithoutObjects() throws IOException {
        File file = new File(write("seed.tmp", new byte[0]).getParentFile(), "no_objects.3mf");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file))) {
            String[][] parts = {
                {"[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"model\" ContentType=\"application/vnd.ms-package.3dmanufacturing-3dmodel+xml\"/></Types>"},
                {"_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Target=\"/3D/3dmodel.model\" Id=\"rel0\" Type=\"http://schemas.microsoft.com/3dmanufacturing/2013/01/3dmodel\"/></Relationships>"},
                {"3D/3dmodel.model", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><model unit=\"millimeter\" xmlns=\"http://schemas.microsoft.com/3dmanufacturing/core/2015/02\"><resources/><build/></model>"}};
            for (String[] part : parts) { zip.putNextEntry(new ZipEntry(part[0])); zip.write(part[1].getBytes(StandardCharsets.UTF_8)); zip.closeEntry(); }
        }
        return file;
    }

    /** Works well: each bad file is a readable IOException from the engine (checked out of process with the CLI: no abort), and the same engine slices a good model afterwards. */
    @Test public void corruptModelsFailCleanlyAndTheEngineKeepsWorking() throws Exception {
        Map<String, File> bad = new LinkedHashMap<>();
        bad.put("truncated binary STL", truncatedStl());
        bad.put("empty STL", write("empty.stl", "solid x\nendsolid x\n".getBytes(StandardCharsets.US_ASCII)));
        bad.put("zero-byte STL", write("zero.stl", new byte[0]));
        bad.put("random bytes named .stl", write("random.stl", new Random(1).ints(4000, 0, 256).collect(ByteArrayOutputStream::new, (out, b) -> out.write(b), (a, b) -> { }).toByteArray()));
        bad.put("3MF without objects", threeMfWithoutObjects());
        bad.put("garbage named .3mf", write("garbage.3mf", new byte[300]));
        try (NativeSlicer slicer = NativeSlicer.open(context(), "Elegoo")) {
            for (Map.Entry<String, File> entry : bad.entrySet()) {
                try { slicer.inspect(Collections.singletonList(entry.getValue()), null, 1000); fail(entry.getKey() + " should be refused"); }
                catch (IOException expected) { assertNotNull(entry.getKey(), expected.getMessage()); assertFalse(entry.getKey(), expected.getMessage().isEmpty()); }
            }
            assertEquals(1, slicer.inspect(Collections.singletonList(box()), null, 1000).getJSONArray("files").length());
        }
    }

    /**
     * Choosing a new model replaces the selection: importModels() clears `models` and per-object settings even when the new file
     * cannot be read, so one bad pick throws away a good selection (and with it the per-object settings and layout).
     */
    @Test public void aRejectedModelPickKeepsThePreviousSelection() throws Exception {
        SliceActivity activity = Robolectric.buildActivity(SliceActivity.class).setup().get();
        waitFor(() -> filled(activity, "processSpinner"));
        File good = box(); File kept = new File(context().getCacheDir(), "slice-input/good_box.stl"); kept.getParentFile().mkdirs();
        Files.copy(good.toPath(), kept.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        @SuppressWarnings("unchecked") List<File> models = (List<File>) field(activity, "models"); models.add(kept);
        java.lang.reflect.Method importModels = SliceActivity.class.getDeclaredMethod("importModels", List.class); importModels.setAccessible(true);
        importModels.invoke(activity, Collections.singletonList(android.net.Uri.fromFile(truncatedStl())));
        waitFor(() -> ((android.widget.TextView) field(activity, "status")).getText().toString().contains("could not be read"));
        assertEquals("the earlier model should still be selected", 1, models.size());
        assertTrue("and its copy must not be deleted", kept.isFile());
        assertTrue("the message names the failing file", ((android.widget.TextView) field(activity, "status")).getText().toString().contains("truncated.stl"));
    }

    private interface Condition { boolean met() throws Exception; }
    private static void waitFor(Condition condition) throws Exception {
        long deadline = System.currentTimeMillis() + 60_000;
        while (true) { Shadows.shadowOf(Looper.getMainLooper()).idle(); if (condition.met()) return; if (System.currentTimeMillis() > deadline) fail("timed out"); Thread.sleep(50); }
    }
    private static boolean filled(Object activity, String name) throws Exception {
        android.widget.Spinner spinner = (android.widget.Spinner) field(activity, name); return spinner.getAdapter() != null && spinner.getAdapter().getCount() > 0;
    }
    private static Object field(Object target, String name) throws Exception {
        java.lang.reflect.Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
}
