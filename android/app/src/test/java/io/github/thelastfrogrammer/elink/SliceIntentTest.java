package io.github.thelastfrogrammer.elink;

import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import java.util.ArrayList;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SliceIntentTest {
    private static final Uri A = Uri.parse("content://files/a"), B = Uri.parse("content://files/b");

    @Test public void takesModelsFromOpenWithAndShare() {
        assertEquals(Arrays.asList(A), SliceActivity.incomingModels(new Intent(Intent.ACTION_VIEW, A)));
        assertEquals(Arrays.asList(A), SliceActivity.incomingModels(new Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, A)));
        assertEquals(Arrays.asList(A, B), SliceActivity.incomingModels(new Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, new ArrayList<>(Arrays.asList(A, B)))));
        Intent clip = new Intent(Intent.ACTION_SEND); clip.setClipData(ClipData.newRawUri("model", B));
        assertEquals(Arrays.asList(B), SliceActivity.incomingModels(clip));
        assertTrue(SliceActivity.incomingModels(new Intent(Intent.ACTION_MAIN)).isEmpty());
        assertTrue(SliceActivity.incomingModels(new Intent()).isEmpty());
        assertTrue(SliceActivity.incomingModels(null).isEmpty());
    }

    @Test public void findsTheModelTypeFromNameOrMimeType() {
        assertEquals("stl", SliceActivity.modelExtension("Benchy.STL", null));
        assertEquals("3mf", SliceActivity.modelExtension("plate.3mf", "application/octet-stream"));
        assertEquals("stl", SliceActivity.modelExtension("1234", "model/stl"));
        assertEquals("stl", SliceActivity.modelExtension("download", "application/vnd.ms-pki.stl"));
        assertEquals("3mf", SliceActivity.modelExtension("shared file", "application/vnd.ms-package.3dmanufacturing-3dmodel+xml"));
        assertEquals("step", SliceActivity.modelExtension("part", "model/step"));
        assertEquals("obj", SliceActivity.modelExtension("thing.tmp", "model/obj"));
        assertEquals("", SliceActivity.modelExtension("photo.jpg", "image/jpeg"));
        assertEquals("", SliceActivity.modelExtension(null, null));
    }
}
