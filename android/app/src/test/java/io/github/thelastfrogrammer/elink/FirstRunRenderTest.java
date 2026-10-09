package io.github.thelastfrogrammer.elink;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.widget.LinearLayout;
import org.junit.After;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Opt-in: renders a brand-new install (no saved printer, account, files, recordings or sliced models) on every tab,
 * Recordings and Slice, in light and dark, plus the Monitor and Files tabs at font scale 2.0.
 * Run with -Dscreenshots=DIR; writes DIR/fresh-<screen>-<theme>[-fs200].png. Slice needs -DslicerLib=....
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 34, qualifiers = "w411dp-h891dp-xxhdpi")
public class FirstRunRenderTest {
    private static final String[] TABS = {"monitor", "files", "camera", "settings"};

    private static File out() {
        String dir = System.getProperty("screenshots", "");
        Assume.assumeFalse("Screenshots are opt-in", dir.isEmpty());
        return new File(dir);
    }

    @After public void restoreFontScale() { RuntimeEnvironment.setFontScale(1.0f); }

    /** Fresh preferences: nothing saved, the theme chosen for this render, the page the app opens on. */
    private static void freshPreferences(String theme) {
        android.content.Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("workshop-settings", 0).edit().clear().putInt("theme", theme.equals("dark") ? 2 : 1).putInt("page", 0).commit();
    }

    /** A MainActivity with a service bound and nothing connected, saved or cloud-signed-in: what a new install shows. */
    private static MainActivity freshMain() throws Exception {
        android.content.Context context = RuntimeEnvironment.getApplication();
        PrinterService service = Robolectric.setupService(PrinterService.class);
        ((org.robolectric.shadows.ShadowApplication) org.robolectric.shadow.api.Shadow.extract(context)).setComponentNameAndServiceForBindService(
            new android.content.ComponentName(context, PrinterService.class), service.onBind(null));
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        Field printer = MainActivity.class.getDeclaredField("printer"); printer.setAccessible(true); printer.set(activity, service);
        Thread.sleep(500); org.robolectric.shadows.ShadowLooper.idleMainLooper();
        return activity;
    }

    /** Saves the view at its full height (the page's own height plus a margin), like the other opt-in renders. */
    private static void save(View root, View shown, int width, int limit, File file) throws Exception {
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(limit, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, limit);
        int height = Math.min(limit, ((View) shown.getParent()).getTop() + shown.getBottom() + 600);
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(bitmap));
        file.getParentFile().mkdirs();
        try (FileOutputStream stream = new FileOutputStream(file)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream); }
    }

    /** Every Monitor/Files/Camera/Settings tab of a fresh install, in one theme and font scale. */
    private static void tabs(String theme, float scale, String[] which) throws Exception {
        RuntimeEnvironment.setFontScale(scale);
        freshPreferences(theme);
        MainActivity activity = freshMain();
        Method render = MainActivity.class.getDeclaredMethod("render"); render.setAccessible(true);
        Method page = MainActivity.class.getDeclaredMethod("selectPage", int.class); page.setAccessible(true);
        Field pagesField = MainActivity.class.getDeclaredField("pages"); pagesField.setAccessible(true);
        LinearLayout[] pages = (LinearLayout[]) pagesField.get(activity);
        String suffix = "-" + theme + (scale == 1.0f ? "" : "-fs" + Math.round(scale * 100));
        for (int i = 0; i < 4; i++) {
            if (!java.util.Arrays.asList(which).contains(TABS[i])) continue;
            page.invoke(activity, i); render.invoke(activity);
            View root = activity.getWindow().getDecorView();
            save(root, pages[i], 1080, 7000, new File(out(), "fresh-" + TABS[i] + suffix + ".png"));
        }
        activity.finish();
    }

    @Test public void freshTabsLight() throws Exception { tabs("light", 1.0f, new String[] {"monitor", "files", "camera", "settings"}); }
    @Test public void freshTabsDark() throws Exception { tabs("dark", 1.0f, new String[] {"monitor", "files", "camera", "settings"}); }

    /** The two tabs a newcomer reads first, at the largest font scale the app is tested at. */
    @Test public void freshTabsLargeFontLight() throws Exception { tabs("light", 2.0f, new String[] {"monitor", "files"}); }
    @Test public void freshTabsLargeFontDark() throws Exception { tabs("dark", 2.0f, new String[] {"monitor", "files"}); }

    private static void recordings(String theme) throws Exception {
        freshPreferences(theme);
        RecordingsActivity list = Robolectric.buildActivity(RecordingsActivity.class).setup().get();
        View root = list.getWindow().getDecorView();
        root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2340, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 1080, 2340);
        Bitmap bitmap = Bitmap.createBitmap(1080, 2340, Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(bitmap));
        File file = new File(out(), "fresh-recordings-" + theme + ".png"); file.getParentFile().mkdirs();
        try (FileOutputStream stream = new FileOutputStream(file)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream); }
        list.finish();
    }
    @Test public void freshRecordingsLight() throws Exception { recordings("light"); }
    @Test public void freshRecordingsDark() throws Exception { recordings("dark"); }

    /** The Slice screen with no model chosen. Needs the native slicer for its profile lists. */
    private static void slice(String theme) throws Exception {
        Assume.assumeFalse("Needs -DslicerLib", System.getProperty("slicerLib", "").isEmpty());
        freshPreferences(theme);
        SliceActivity activity = Robolectric.buildActivity(SliceActivity.class).setup().get();
        for (int i = 0; i < 400; i++) { org.robolectric.shadows.ShadowLooper.idleMainLooper(); Thread.sleep(50); }
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
        View root = activity.getWindow().getDecorView();
        View form = (View) field(activity, "content"), bar = (View) field(activity, "actionBar");
        form.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED);
        bar.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED);
        int height = form.getMeasuredHeight() + bar.getMeasuredHeight();
        root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 1080, height);
        Bitmap bitmap = Bitmap.createBitmap(1080, height, Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(bitmap));
        File file = new File(out(), "fresh-slice-" + theme + ".png"); file.getParentFile().mkdirs();
        try (FileOutputStream stream = new FileOutputStream(file)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream); }
        activity.finish();
    }
    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
    @Test public void freshSliceLight() throws Exception { slice("light"); }
    @Test public void freshSliceDark() throws Exception { slice("dark"); }
}
