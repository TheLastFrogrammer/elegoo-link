package io.github.thelastfrogrammer.elink;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/** The Projects screen: a project's parts and files show and survive the screen being made again; screenshots with -Dscreenshots. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 34, qualifiers = "w411dp-h891dp-notnight-xxhdpi")
public class ProjectsScreenTest {
    private static void settle() throws Exception { for (int i = 0; i < 20; i++) { Thread.sleep(50); org.robolectric.shadows.ShadowLooper.idleMainLooper(); } }
    private static List<String> texts(View view) {
        List<String> out = new ArrayList<>();
        if (view instanceof TextView) out.add(((TextView) view).getText().toString());
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) out.addAll(texts(((ViewGroup) view).getChildAt(i)));
        return out;
    }
    private static Button button(View view, String text) {
        if (view instanceof Button && ((Button) view).getText().toString().equals(text)) return (Button) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) { Button b = button(((ViewGroup) view).getChildAt(i), text); if (b != null) return b; }
        return null;
    }
    private static void shot(Activity activity, String name) throws Exception {
        String dir = System.getProperty("screenshots", ""); if (dir.isEmpty()) return;
        View root = activity.getWindow().getDecorView();
        root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2340, View.MeasureSpec.EXACTLY)); root.layout(0, 0, 1080, 2340);
        Bitmap bitmap = Bitmap.createBitmap(1080, 2340, Bitmap.Config.ARGB_8888); root.draw(new Canvas(bitmap));
        File file = new File(new File(dir, "projects"), name); file.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(file)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); }
    }

    @Test public void aPlannedProjectShowsItsPartsAndSharedFile() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        File dir = new File(context.getFilesDir(), "projects");
        Projects.Book book = new Projects.Book();
        Projects.Project lamp = book.create("Shadow-story lamp", 1); lamp.description = "A compact lamp with interchangeable scene cards.";
        Projects.Project shelf = book.create("Storage shelf", 1);
        Projects.Part body = book.addPart(lamp, "Main body", Projects.PRINTED, 1, 1);
        book.addPart(lamp, "Lid", Projects.PRINTED, 1, 1);
        Projects.Part clip = book.addPart(lamp, "Retaining clip", Projects.PRINTED, 4, 1);
        Projects.Part module = book.addPart(lamp, "Light module", Projects.PURCHASED, 1, 1); module.have = 1; module.notes = "Check the dimensions against the opening.";
        book.addPart(lamp, "Verify lid clearance", Projects.TASK, 1, 1);
        Projects.Asset clipModel = book.adopt(Projects.copyIn(dir, new ByteArrayInputStream("solid clip".getBytes()), "standard-clip-rev-c.stl", null, 1));
        Projects.Asset bodyModel = book.adopt(Projects.copyIn(dir, new ByteArrayInputStream("solid body".getBytes()), "lamp-body-rev-b.3mf", null, 1));
        book.attach(lamp, clipModel.id, clip.id); book.attach(shelf, clipModel.id, ""); book.attach(lamp, bodyModel.id, body.id);
        Projects.Asset page = new Projects.Asset(); page.storage = Projects.WEB; page.role = "link"; page.name = "Supplier page"; page.location = "https://example.com/module"; book.assets.add(page);
        book.attach(lamp, page.id, module.id);
        Projects.save(dir, book);

        org.robolectric.android.controller.ActivityController<ProjectsActivity> controller = Robolectric.buildActivity(ProjectsActivity.class).setup();
        settle();
        ProjectsActivity screen = controller.get();
        List<String> home = texts(screen.getWindow().getDecorView());
        assertTrue(home.toString(), home.contains("Shadow-story lamp"));
        assertTrue(home.toString(), home.contains("5 parts · 6 pieces to print (1 without a file yet) · 1 of 1 bought · 0 of 1 task done · 3 files"));
        shot(screen, "projects-home.png");
        button(screen.getWindow().getDecorView(), "Open").performClick(); settle();
        assertTrue(texts(screen.getWindow().getDecorView()).contains("• Lid: no model yet"));
        shot(screen, "project-overview.png");
        button(screen.getWindow().getDecorView(), "Parts (5)").performClick(); settle();
        List<String> parts = texts(screen.getWindow().getDecorView());
        assertTrue(parts.toString(), parts.contains("Retaining clip × 4"));
        assertTrue(parts.toString(), parts.contains("Purchased part · 1 of 1 at hand · 1 file"));
        shot(screen, "project-parts.png");
        button(screen.getWindow().getDecorView(), "Files (3)").performClick(); settle();
        List<String> files = texts(screen.getWindow().getDecorView());
        assertTrue(files.toString(), files.contains("In: Shadow-story lamp › Retaining clip; Storage shelf"));
        shot(screen, "project-files.png");

        // Made again (rotation): the same screen comes back.
        controller.recreate(); settle();
        assertTrue(texts(controller.get().getWindow().getDecorView()).contains("standard-clip-rev-c.stl"));
    }
}
