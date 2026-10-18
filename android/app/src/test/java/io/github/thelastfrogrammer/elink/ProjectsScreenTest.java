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
    /** The tappable view holding a text (the text itself or the nearest clickable parent). */
    private static View tappable(View root, String text) {
        View found = text(root, text); assertNotNull(text, found);
        for (View v = found; v != null; v = v.getParent() instanceof View ? (View) v.getParent() : null) if (v.isClickable()) return v;
        throw new AssertionError(text + " is not tappable");
    }
    private static View text(View view, String text) {
        if (view instanceof TextView && !(view instanceof Button) && ((TextView) view).getText().toString().equals(text)) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) { View v = text(((ViewGroup) view).getChildAt(i), text); if (v != null) return v; }
        return null;
    }
    @SuppressWarnings("unchecked") private static <T extends View> T find(View view, Class<T> type) {
        if (type.isInstance(view)) return (T) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) { T t = find(((ViewGroup) view).getChildAt(i), type); if (t != null) return t; }
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
        Projects.Project lamp = book.create("Shadow-story lamp", 1); lamp.description = "Interchangeable scenes · compact housing";
        Projects.Project shelf = book.create("Storage shelf", 1);
        Projects.Part body = book.addPart(lamp, "Main body", Projects.PRINTED, 1, 1); body.group = "Housing"; body.revision = "B"; body.design = "ready";
        Projects.Part lid = book.addPart(lamp, "Lid", Projects.PRINTED, 1, 1); lid.group = "Housing";
        Projects.Part clip = book.addPart(lamp, "Retaining clip", Projects.PRINTED, 4, 1); clip.group = "Mounting"; clip.revision = "C";
        Projects.Part frame = book.addPart(lamp, "Card frame", Projects.PRINTED, 2, 1); frame.group = "Scene"; frame.revision = "A"; frame.design = "revise"; frame.notes = "Previous attempt: card binds";
        Projects.Part module = book.addPart(lamp, "Light module", Projects.PURCHASED, 1, 1); module.have = 1; module.group = "Lighting";
        Projects.Asset clipModel = book.adopt(Projects.copyIn(dir, new ByteArrayInputStream("solid clip".getBytes()), "standard-clip-rev-c.stl", null, 1));
        Projects.Asset bodyModel = book.adopt(Projects.copyIn(dir, new ByteArrayInputStream("solid body".getBytes()), "lamp-body-rev-b.3mf", null, 1));
        Projects.Asset frameModel = book.adopt(Projects.copyIn(dir, new ByteArrayInputStream("solid frame".getBytes()), "card-frame-rev-a.3mf", null, 1));
        book.attach(lamp, clipModel.id, clip.id); book.attach(shelf, clipModel.id, ""); book.attach(lamp, bodyModel.id, body.id); book.attach(lamp, frameModel.id, frame.id);
        Projects.Asset page = new Projects.Asset(); page.storage = Projects.WEB; page.role = "link"; page.name = "Supplier page"; page.location = "https://example.com/module"; book.assets.add(page);
        book.attach(lamp, page.id, module.id);
        Projects.Attempt first = new Projects.Attempt(); first.title = "Housing"; first.set(body.id, 1, 1, 0); lamp.attempts.add(first);
        Projects.Attempt clips = new Projects.Attempt(); clips.title = "Retaining clips"; clips.note = "PETG · 0.20 mm · 4 walls"; clips.set(clip.id, 3, 2, 0); lamp.attempts.add(clips);
        Projects.save(dir, book);

        org.robolectric.android.controller.ActivityController<ProjectsActivity> controller = Robolectric.buildActivity(ProjectsActivity.class).setup();
        settle();
        ProjectsActivity screen = controller.get();
        List<String> home = texts(screen.getWindow().getDecorView());
        assertTrue(home.toString(), home.contains("Shadow-story lamp"));
        assertTrue(home.toString(), home.contains("In progress · 3 / 8 printed parts accepted\n5 parts · 8 pieces to print (1 without a file yet) · 1 of 1 bought · 4 files"));
        shot(screen, "projects-home.png");
        assertNull("the card opens the project; no separate Open button", button(screen.getWindow().getDecorView(), "Open"));
        tappable(screen.getWindow().getDecorView(), "Shadow-story lamp").performClick(); settle();
        List<String> overview = texts(screen.getWindow().getDecorView());
        assertTrue(overview.toString(), overview.contains("Review 1 printed retaining clip"));
        assertTrue(overview.toString(), overview.contains("5 still needed · 1 awaiting inspection · assembly not checked"));
        assertTrue(overview.toString(), overview.contains("Revise the card frame"));
        shot(screen, "project-overview.png");
        button(screen.getWindow().getDecorView(), "Parts").performClick(); settle();
        List<String> parts = texts(screen.getWindow().getDecorView());
        assertTrue(parts.toString(), parts.contains("Revision C · 1 awaiting inspection"));
        assertTrue(parts.toString(), parts.contains("ACQUIRED"));
        assertTrue(parts.toString(), parts.contains("2 / 4"));
        assertTrue(parts.toString(), parts.contains("Mounting · 1   ▾"));
        shot(screen, "project-parts.png");
        button(screen.getWindow().getDecorView(), "Prints").performClick(); settle();
        List<String> prints = texts(screen.getWindow().getDecorView());
        assertTrue(prints.toString(), prints.contains("Retaining clip: 2 accepted, 0 rejected of 3 (rev C)"));
        shot(screen, "project-prints.png");
        button(screen.getWindow().getDecorView(), "Files").performClick(); settle();
        List<String> files = texts(screen.getWindow().getDecorView());
        assertTrue(files.toString(), files.contains("In: Shadow-story lamp › Retaining clip; Storage shelf"));
        shot(screen, "project-files.png");

        // Cover page: drawn in the background and shown; choices kept with the project.
        button(screen.getWindow().getDecorView(), "Overview").performClick(); settle();
        button(screen.getWindow().getDecorView(), "Cover page…").performClick(); settle(); settle();
        android.widget.ImageView preview = find(screen.getWindow().getDecorView(), android.widget.ImageView.class);
        assertNotNull(preview); assertNotNull("the preview is drawn", preview.getDrawable());
        assertTrue(preview.getContentDescription().toString().startsWith("Cover page preview: Shadow-story lamp"));
        button(screen.getWindow().getDecorView(), "Blue").performClick(); settle(); settle();
        assertEquals("#1565C0", screen.bookForTest().project(lamp.id).cover.getString("accent"));
        shot(screen, "project-cover.png");
        button(screen.getWindow().getDecorView(), "‹ Shadow-story lamp").performClick(); settle();
        button(screen.getWindow().getDecorView(), "Share or export…").performClick(); settle();
        android.app.AlertDialog share = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(button(share.getWindow().getDecorView(), "Share the project file…"));
        assertNotNull(button(share.getWindow().getDecorView(), "Share as text (the outline)"));
        share.dismiss();
        button(screen.getWindow().getDecorView(), "Files").performClick(); settle();

        // Made again (rotation): the same screen comes back.
        controller.recreate(); settle();
        assertTrue(texts(controller.get().getWindow().getDecorView()).contains("standard-clip-rev-c.stl"));
    }
}
