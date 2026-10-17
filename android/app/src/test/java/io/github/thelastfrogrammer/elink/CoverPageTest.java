package io.github.thelastfrogrammer.elink;

import static org.junit.Assert.*;

import android.graphics.Bitmap;
import android.graphics.Color;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/** Cover pages: what they say, every style and page drawn (PNGs with -Dscreenshots), the choices kept, and a PDF written. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 34)
public class CoverPageTest {
    static Projects.Book lamp(Projects.Project[] out) {
        Projects.Book book = new Projects.Book(); Projects.Project p = book.create("Shadow-story lamp", 1);
        p.description = "A compact lamp with interchangeable illustrated scene cards.";
        book.addPart(p, "Main body", Projects.PRINTED, 1, 1); book.addPart(p, "Lid", Projects.PRINTED, 1, 1);
        book.addPart(p, "Retaining clip", Projects.PRINTED, 4, 1); book.addPart(p, "Card frame", Projects.PRINTED, 2, 1);
        Projects.Part module = book.addPart(p, "Light module", Projects.PURCHASED, 1, 1); module.have = 1;
        book.addPart(p, "M3 × 8 screws", Projects.PURCHASED, 6, 1);
        Projects.Part check = book.addPart(p, "Verify lid clearance", Projects.TASK, 1, 1); check.done = false;
        Projects.Part measured = book.addPart(p, "Measure the light module", Projects.TASK, 1, 1); measured.done = true;
        out[0] = p; return book;
    }
    private static Bitmap picture() {
        Bitmap b = Bitmap.createBitmap(800, 500, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < 500; y++) for (int x = 0; x < 800; x += 4) { int c = Color.rgb(40 + x / 8, 80 + y / 8, 140); b.setPixel(x, y, c); b.setPixel(x + 1, y, c); b.setPixel(x + 2, y, c); b.setPixel(x + 3, y, c); }
        return b;
    }

    @Test public void theContentFollowsTheChosenSections() throws Exception {
        Projects.Project[] p = new Projects.Project[1]; Projects.Book book = lamp(p);
        CoverPage.Options o = new CoverPage.Options(); o.maker = "Sam";
        CoverPage.Content c = CoverPage.content(book, p[0], o, 0);
        assertEquals("A compact lamp with interchangeable illustrated scene cards.", c.subtitle);
        assertEquals("By Sam", c.maker);
        assertEquals(4, c.parts.size()); assertEquals("Retaining clip  × 4  (no model yet)", c.parts.get(2));
        assertEquals("Light module  × 1  (1 at hand)", c.purchased.get(0));
        assertEquals("☐ Verify lid clearance", c.tasks.get(0)); assertEquals("☑ Measure the light module", c.tasks.get(1));
        assertTrue(c.files.isEmpty());
        o.sections.remove("parts"); o.sections.remove("description"); o.subtitle = "Build notes";
        c = CoverPage.content(book, p[0], o, 0);
        assertTrue(c.parts.isEmpty()); assertEquals("Build notes", c.subtitle);
        // Kept with the project, unknown values replaced by defaults.
        CoverPage.Options back = CoverPage.Options.fromJson(o.toJson().put("style", "neon").put("accent", "#123456"));
        assertEquals("workshop", back.style); assertEquals("#00796B", back.accent); assertFalse(back.sections.contains("parts"));
    }

    @Test public void everyStyleAndPageDraws() throws Exception {
        Projects.Project[] p = new Projects.Project[1]; Projects.Book book = lamp(p);
        String dir = System.getProperty("screenshots", "");
        Bitmap photo = picture();
        for (String[] style : CoverPage.STYLES) for (Object[] page : CoverPage.PAGES) {
            CoverPage.Options o = new CoverPage.Options(); o.style = style[0]; o.page = (String) page[0]; o.maker = "Sam"; o.footer = "Files: example.com/lamp · CC BY 4.0";
            o.accent = CoverPage.ACCENTS[(style[0].hashCode() & 0x7fffffff) % CoverPage.ACCENTS.length][0];
            boolean withPicture = !page[0].equals("letter");
            Bitmap b = CoverPage.render(CoverPage.content(book, p[0], o, 0), o, withPicture ? photo : null);
            assertEquals(page[2], b.getWidth()); assertEquals(page[3], b.getHeight());
            if (!dir.isEmpty()) { File f = new File(new File(dir, "covers"), style[0] + "-" + page[0] + ".png"); f.getParentFile().mkdirs(); try (FileOutputStream out = new FileOutputStream(f)) { b.compress(Bitmap.CompressFormat.PNG, 100, out); } }
        }
        // A long plan still fits: the rest is summed up as "+N more".
        for (int i = 0; i < 60; i++) book.addPart(p[0], "Spare part number " + i, Projects.PRINTED, 2, 1);
        CoverPage.Options o = new CoverPage.Options();
        Bitmap b = CoverPage.render(CoverPage.content(book, p[0], o, 0), o, null);
        if (!dir.isEmpty()) try (FileOutputStream out = new FileOutputStream(new File(new File(dir, "covers"), "long-plan.png"))) { b.compress(Bitmap.CompressFormat.PNG, 100, out); }
        ByteArrayOutputStream pdf = new ByteArrayOutputStream();
        // Robolectric has no PDF writer (its PdfDocument is always closed); on a phone this writes the page.
        try { CoverPage.pdf(CoverPage.content(book, p[0], o, 0), o, null, pdf); }
        catch (IllegalStateException noPdfHere) { org.junit.Assume.assumeTrue("PdfDocument is not available under Robolectric", false); }
        assertTrue(new String(pdf.toByteArray(), 0, 5, "US-ASCII").startsWith("%PDF"));
    }
}
