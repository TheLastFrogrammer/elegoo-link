package io.github.thelastfrogrammer.elink;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.*;

/** A second Slice screen (opened from another app) must not delete the models the first one is still using. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SliceInputsTest {
    private static File model(String folder, String name) throws Exception {
        File file = new File(RuntimeEnvironment.getApplication().getCacheDir(), "slice-input/" + folder + "/" + name);
        file.getParentFile().mkdirs(); Files.write(file.toPath(), "solid x\nendsolid x\n".getBytes("UTF-8")); return file;
    }
    @SuppressWarnings("unchecked")
    @Test public void aScreenCleansOnlyItsOwnFolders() throws Exception {
        File other = model("111", "planet.3mf"), mine = model("222", "box.stl"), stale = model("333", "old.stl");
        SliceActivity screen = Robolectric.buildActivity(SliceActivity.class).create().get();
        Field own = SliceActivity.class.getDeclaredField("ownFolders"); own.setAccessible(true);
        Set<File> folders = (Set<File>) own.get(screen); folders.add(mine.getParentFile()); folders.add(stale.getParentFile());
        Method clean = SliceActivity.class.getDeclaredMethod("cleanOwn", List.class, File.class); clean.setAccessible(true);
        clean.invoke(screen, Collections.singletonList(mine), new File(RuntimeEnvironment.getApplication().getCacheDir(), "slice-meshes/9"));
        assertTrue("another screen's model stays", other.isFile());
        assertTrue(mine.isFile()); assertFalse("its own unused folder goes", stale.getParentFile().exists());
        File ancient = model("444", "ancient.stl"); ancient.getParentFile().setLastModified(System.currentTimeMillis() - 4 * 24 * 3600_000L);
        SliceActivity.sweepOldInputs(RuntimeEnvironment.getApplication().getCacheDir());
        assertFalse(ancient.getParentFile().exists()); assertTrue(other.isFile());
    }
}
