package io.github.thelastfrogrammer.elink;

import android.app.Activity;
import android.view.View;
import android.widget.FrameLayout;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.ShadowLooper;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 34)
public class ShowcaseTest {
    @After public void off() { Showcase.stop(); }

    @Test public void videoSizeIsEvenAndAtMost1080Wide() {
        assertArrayEquals(new int[] {1080, 2340}, ShowcaseService.videoSize(1080, 2340));
        assertArrayEquals(new int[] {1080, 2400}, ShowcaseService.videoSize(1440, 3200));
        assertArrayEquals(new int[] {720, 1600}, ShowcaseService.videoSize(721, 1601));
    }

    @Test public void stepsRunInOrderWaitForConditionsAndStopWithTheTour() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        Showcase.stage = Showcase.MAIN;
        List<String> ran = new ArrayList<>(); boolean[] ready = {false};
        new Showcase.Script(activity).then(100, () -> ran.add("a")).until(10_000, () -> ready[0]).then(100, () -> ran.add("b")).run(() -> ran.add("done"));
        ShowcaseLooper.advance(150); assertEquals(List.of("a"), ran);
        ShowcaseLooper.advance(1000); assertEquals("waits for the condition", List.of("a"), ran);
        ready[0] = true; ShowcaseLooper.advance(1000); assertEquals(List.of("a", "b", "done"), ran);

        List<String> stopped = new ArrayList<>();
        new Showcase.Script(activity).then(100, () -> stopped.add("a")).then(100, () -> stopped.add("b")).run(null);
        ShowcaseLooper.advance(150); Showcase.stop(); ShowcaseLooper.advance(1000);
        assertEquals("nothing runs once the tour is stopped", List.of("a"), stopped);
    }

    @Test public void captionsAndCardsGoOverTheScreenAndClear() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        Showcase.stage = Showcase.MAIN;
        FrameLayout root = activity.findViewById(android.R.id.content);
        Showcase.caption(activity, "Live status"); Showcase.card(activity, "Link Workshop", "A tour", 0);
        assertNotNull(Showcase.find(root, "Live status")); assertNotNull(Showcase.find(root, "Link Workshop"));
        Showcase.caption(activity, "Next"); ShowcaseLooper.advance(1000);
        assertNull("the old caption fades out", Showcase.find(root, "Live status")); assertNotNull(Showcase.find(root, "Next"));
        Showcase.clear(activity);
        assertNull(Showcase.find(root, "Next")); assertNull(Showcase.find(root, "Link Workshop"));
        View plain = new View(activity); boolean[] changed = {false}; Showcase.fade(plain, () -> changed[0] = true); ShowcaseLooper.advance(1000);
        assertTrue(changed[0]); assertEquals(1f, plain.getAlpha(), 1e-6);
    }

    /** Moves the main looper's clock forward, running what falls due. */
    static final class ShowcaseLooper { static void advance(long ms) { ShadowLooper.idleMainLooper(ms, java.util.concurrent.TimeUnit.MILLISECONDS); } }
}
