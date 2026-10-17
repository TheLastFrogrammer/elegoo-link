package io.github.thelastfrogrammer.elink;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * A project's cover page: title, purpose, a picture, and the parts, purchases, tasks and files, drawn as a picture (PNG) or a
 * PDF page. The look is chosen in Options (style, accent colour, page size, which sections, title size, a subtitle, a maker's
 * name and a footer) and kept with the project. Text shrinks to fit the page; what still does not fit ends in "+N more".
 */
final class CoverPage {
    static final String[][] STYLES = {{"workshop", "Workshop: paper with a colour band"}, {"blueprint", "Blueprint: white on blue with a grid"},
        {"minimal", "Minimal: white, a thin rule"}, {"poster", "Poster: dark, big title"}};
    /** Accent colours, each dark enough for headings on white (at least 3:1, the large-text minimum) and light enough to read on the dark styles' tint. */
    static final String[][] ACCENTS = {{"#00796B", "Teal"}, {"#D9480F", "Orange"}, {"#C62828", "Red"}, {"#1565C0", "Blue"},
        {"#6A1B9A", "Purple"}, {"#2E7D32", "Green"}, {"#A15C00", "Amber"}, {"#37474F", "Graphite"}};
    /** Page sizes: id, name, width and height in pixels (150 dpi for paper) and in PDF points. */
    static final Object[][] PAGES = {{"a4", "A4 (portrait)", 1240, 1754, 595, 842}, {"letter", "US Letter (portrait)", 1275, 1650, 612, 792},
        {"square", "Square (for posts)", 1600, 1600, 600, 600}, {"phone", "Phone screen (tall)", 1080, 1920, 540, 960}};
    static final String[][] SECTIONS = {{"description", "What it is for"}, {"summary", "Progress line"}, {"parts", "Printed parts"},
        {"purchased", "Purchased parts"}, {"tasks", "Tasks"}, {"files", "Files and links"}, {"date", "Date"}};

    static final class Options {
        String style = "workshop", accent = "#00796B", page = "a4", picture = "", subtitle = "", maker = "", footer = "";
        boolean largeTitle = true;
        final java.util.Set<String> sections = new java.util.LinkedHashSet<>(java.util.Arrays.asList("description", "summary", "parts", "purchased", "tasks", "date"));

        JSONObject toJson() throws JSONException {
            return new JSONObject().put("style", style).put("accent", accent).put("page", page).put("picture", picture).put("subtitle", subtitle)
                .put("maker", maker).put("footer", footer).put("large_title", largeTitle).put("sections", new org.json.JSONArray(new ArrayList<>(sections)));
        }
        static Options fromJson(JSONObject o) {
            Options out = new Options(); if (o == null || o.length() == 0) return out;
            out.style = pick(STYLES, o.optString("style"), out.style); out.accent = pick(ACCENTS, o.optString("accent"), out.accent);
            boolean known = false; for (Object[] p : PAGES) known |= p[0].equals(o.optString("page")); if (known) out.page = o.optString("page");
            out.picture = Automation.cap(o.optString("picture"), 80); out.subtitle = Automation.cap(o.optString("subtitle"), 200);
            out.maker = Automation.cap(o.optString("maker"), 80); out.footer = Automation.cap(o.optString("footer"), 200); out.largeTitle = o.optBoolean("large_title", true);
            org.json.JSONArray s = o.optJSONArray("sections");
            if (s != null) { out.sections.clear(); for (int i = 0; i < s.length(); i++) for (String[] e : SECTIONS) if (e[0].equals(s.optString(i))) out.sections.add(e[0]); }
            return out;
        }
        int width() { return (int) page()[2]; } int height() { return (int) page()[3]; }
        Object[] page() { for (Object[] p : PAGES) if (p[0].equals(page)) return p; return PAGES[0]; }
    }
    private static String pick(String[][] list, String id, String fallback) { for (String[] e : list) if (e[0].equalsIgnoreCase(id)) return e[0]; return fallback; }

    /** What the page says, gathered from the project (no drawing): tested on the JVM. */
    static final class Content {
        String title = "", subtitle = "", maker = "", date = "", summary = "", footer = "";
        final List<String> parts = new ArrayList<>(), purchased = new ArrayList<>(), tasks = new ArrayList<>(), files = new ArrayList<>();
    }
    static Content content(Projects.Book book, Projects.Project p, Options o, long now) {
        Content c = new Content();
        c.title = p.title;
        String sub = o.subtitle.trim();
        c.subtitle = !sub.isEmpty() ? sub : o.sections.contains("description") ? p.description.trim() : "";
        c.maker = o.maker.trim().isEmpty() ? "" : "By " + o.maker.trim();
        c.date = o.sections.contains("date") ? DateFormat.getDateInstance(DateFormat.LONG).format(new Date(now)) : "";
        c.summary = o.sections.contains("summary") ? ProjectsActivity.summary(p) : "";
        c.footer = o.footer.trim();
        for (Projects.Part part : p.parts) {
            switch (part.kind) {
                case Projects.PRINTED: if (o.sections.contains("parts")) c.parts.add(part.name + "  × " + part.quantity + (p.assetsOn(part.id).isEmpty() ? "  (no model yet)" : "")); break;
                case Projects.PURCHASED: if (o.sections.contains("purchased")) c.purchased.add(part.name + "  × " + part.quantity + "  (" + Math.min(part.have, part.quantity) + " at hand)"); break;
                case Projects.TASK: if (o.sections.contains("tasks")) c.tasks.add((part.done ? "☑ " : "☐ ") + part.name); break;
                default: break;
            }
        }
        if (o.sections.contains("files")) for (String id : p.allAssets()) { Projects.Asset a = book.asset(id); if (a != null) c.files.add(a.name + "  · " + Projects.label(Projects.ROLES, a.role)); }
        return c;
    }

    /** Draws the page at its pixel size. `picture` may be null. */
    static Bitmap render(Content c, Options o, Bitmap picture) {
        Bitmap bitmap = Bitmap.createBitmap(o.width(), o.height(), Bitmap.Config.ARGB_8888);
        draw(new Canvas(bitmap), c, o, picture);
        return bitmap;
    }
    static byte[] png(Content c, Options o, Bitmap picture) {
        Bitmap b = render(c, o, picture); ByteArrayOutputStream out = new ByteArrayOutputStream(); b.compress(Bitmap.CompressFormat.PNG, 100, out); b.recycle(); return out.toByteArray();
    }
    static void pdf(Content c, Options o, Bitmap picture, OutputStream target) throws IOException {
        Object[] page = o.page(); int pw = (int) page[4], ph = (int) page[5];
        PdfDocument document = new PdfDocument();
        PdfDocument.Page sheet = document.startPage(new PdfDocument.PageInfo.Builder(pw, ph, 1).create());
        Canvas canvas = sheet.getCanvas(); canvas.scale(pw / (float) o.width(), ph / (float) o.height());
        draw(canvas, c, o, picture);
        document.finishPage(sheet);
        try { document.writeTo(target); } finally { document.close(); }
    }

    static void draw(Canvas canvas, Content c, Options o, Bitmap picture) {
        int w = o.width(), h = o.height(); float u = Math.min(w, h) / 100f;   // one unit: 1 % of the short side
        int accent = Color.parseColor(o.accent);
        boolean dark = o.style.equals("blueprint") || o.style.equals("poster");
        int paper = o.style.equals("blueprint") ? mix(accent, Color.rgb(10, 40, 90), 0.55f) : o.style.equals("poster") ? Color.rgb(18, 22, 26) : o.style.equals("minimal") ? Color.WHITE : Color.rgb(250, 248, 243);
        int ink = dark ? Color.rgb(245, 247, 250) : Color.rgb(23, 30, 36), soft = dark ? Color.argb(200, 235, 240, 245) : Color.rgb(84, 96, 104);
        int heading = dark ? (o.style.equals("poster") ? lighten(accent, 0.55f) : Color.WHITE) : accent;
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        canvas.drawColor(paper);
        if (o.style.equals("blueprint")) {
            fill.setColor(Color.argb(40, 255, 255, 255)); fill.setStrokeWidth(Math.max(1, u * 0.12f));
            for (float x = 0; x < w; x += u * 5) canvas.drawLine(x, 0, x, h, fill);
            for (float y = 0; y < h; y += u * 5) canvas.drawLine(0, y, w, y, fill);
        }
        float margin = u * 7, top = margin;
        // The picture: a band across the top (workshop, minimal, blueprint) or the whole page under a dark veil (poster).
        if (picture != null) {
            if (o.style.equals("poster")) {
                drawCropped(canvas, picture, new RectF(0, 0, w, h), fill);
                fill.setColor(Color.argb(170, 10, 12, 14)); canvas.drawRect(0, 0, w, h, fill);
            } else {
                float band = h * (w > h ? 0.42f : 0.36f);
                drawCropped(canvas, picture, new RectF(0, 0, w, band), fill);
                top = band + margin * 0.8f;
            }
        } else if (o.style.equals("workshop")) {
            fill.setColor(accent); canvas.drawRect(0, 0, w, u * 3.2f, fill); top = u * 3.2f + margin;
        }
        if (o.style.equals("poster") && picture == null) { fill.setColor(accent); canvas.drawRect(margin, margin, margin + u * 12, margin + u * 1.4f, fill); top = margin * 2; }
        float bottomReserve = (c.footer.isEmpty() ? 0 : u * 6) + margin;
        float columnWidth = w - 2 * margin;

        // Title block, then the lists, shrinking everything together until it fits.
        float scale = 1f;
        for (int attempt = 0; attempt < 12; attempt++) {
            float used = layoutAndMaybeDraw(null, c, o, margin, top, columnWidth, w, u * scale, ink, soft, heading, accent, dark, h - bottomReserve);
            if (used <= h - bottomReserve || scale < 0.55f) break;
            scale *= 0.92f;
        }
        layoutAndMaybeDraw(canvas, c, o, margin, top, columnWidth, w, u * scale, ink, soft, heading, accent, dark, h - bottomReserve);
        if (!c.footer.isEmpty()) {
            fill.setColor(dark ? Color.argb(90, 255, 255, 255) : mix(accent, Color.WHITE, 0.6f)); canvas.drawRect(margin, h - margin - u * 4.6f, w - margin, h - margin - u * 4.4f, fill);
            TextPaint foot = text(soft, u * 1.9f, false);
            StaticLayout line = layout(c.footer, foot, (int) columnWidth, 2);
            canvas.save(); canvas.translate(margin, h - margin - u * 3.6f); line.draw(canvas); canvas.restore();
        }
    }

    /** Lays out (and, with a canvas, draws) the title block and the lists from `top`; returns where it ended. */
    private static float layoutAndMaybeDraw(Canvas canvas, Content c, Options o, float x, float top, float width, int pageWidth, float u,
                                            int ink, int soft, int heading, int accent, boolean dark, float bottom) {
        float y = top;
        TextPaint title = text(o.style.equals("poster") ? Color.WHITE : ink, u * (o.largeTitle ? (o.style.equals("poster") ? 9.5f : 7.4f) : 5.4f), true);
        y = block(canvas, c.title, title, x, y, width, 4) + u * 1.2f;
        if (!c.subtitle.isEmpty()) y = block(canvas, c.subtitle, text(soft, u * 2.6f, false), x, y, width, 5) + u * 0.8f;
        String byline = c.maker.isEmpty() ? c.date : c.date.isEmpty() ? c.maker : c.maker + "  ·  " + c.date;
        if (!byline.isEmpty()) y = block(canvas, byline, text(heading, u * 2.2f, true), x, y, width, 2) + u * 0.6f;
        if (!c.summary.isEmpty()) {
            TextPaint chip = text(dark ? Color.WHITE : ink, u * 2.0f, false);
            StaticLayout l = layout(c.summary, chip, (int) (width - u * 3), 3);
            if (canvas != null) {
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); p.setColor(dark ? Color.argb(55, 255, 255, 255) : mix(accent, Color.WHITE, 0.86f));
                canvas.drawRoundRect(new RectF(x, y + u * 0.6f, x + width, y + u * 0.6f + l.getHeight() + u * 2), u * 1.4f, u * 1.4f, p);
                canvas.save(); canvas.translate(x + u * 1.5f, y + u * 1.6f); l.draw(canvas); canvas.restore();
            }
            y += l.getHeight() + u * 3.6f;
        }
        // The lists: one column, or two side by side on a wide page.
        List<Object[]> sections = new ArrayList<>();
        if (!c.parts.isEmpty()) sections.add(new Object[] {"Parts to print", c.parts});
        if (!c.purchased.isEmpty()) sections.add(new Object[] {"To buy", c.purchased});
        if (!c.tasks.isEmpty()) sections.add(new Object[] {"Tasks", c.tasks});
        if (!c.files.isEmpty()) sections.add(new Object[] {"Files and links", c.files});
        boolean twoColumns = sections.size() > 1 && width / u > 120;
        float colWidth = twoColumns ? (width - u * 4) / 2 : width, left = y, right = y;
        for (int i = 0; i < sections.size(); i++) {
            boolean second = twoColumns && right < left;   // the shorter column takes the next section
            float cx = second ? x + colWidth + u * 4 : x, cy = second ? right : left;
            @SuppressWarnings("unchecked") List<String> items = (List<String>) sections.get(i)[1];
            cy = list(canvas, (String) sections.get(i)[0], items, cx, cy, colWidth, u, ink, heading, accent, dark, bottom);
            if (second) right = cy; else left = cy;
        }
        return Math.max(y, Math.max(left, right));
    }

    /** A heading and its items with accent markers; items past the bottom become "+N more". */
    private static float list(Canvas canvas, String name, List<String> items, float x, float y, float width, float u, int ink, int heading, int accent, boolean dark, float bottom) {
        y = block(canvas, name.toUpperCase(java.util.Locale.getDefault()), text(heading, u * 2.1f, true), x, y, width, 1) + u * 0.6f;
        TextPaint item = text(ink, u * 2.3f, false); Paint marker = new Paint(Paint.ANTI_ALIAS_FLAG); marker.setColor(dark ? Color.WHITE : accent);
        for (int i = 0; i < items.size(); i++) {
            StaticLayout l = layout(items.get(i), item, (int) (width - u * 3), 3);
            float lineSpace = u * 0.9f;
            boolean last = i == items.size() - 1;
            float need = l.getHeight() + lineSpace + (last ? 0 : u * 3.4f);
            if (canvas != null && y + need > bottom && !last) {
                block(canvas, "+" + (items.size() - i) + " more", text(ink, u * 2.1f, true), x + u * 3, y, width - u * 3, 1);
                return y + u * 3.4f;
            }
            if (canvas != null) {
                // Tasks carry their own box (☐ or ☑); other items get the accent marker.
                if (!items.get(i).startsWith("☐") && !items.get(i).startsWith("☑")) canvas.drawRoundRect(new RectF(x, y + u * 0.9f, x + u * 1.3f, y + u * 2.2f), u * 0.3f, u * 0.3f, marker);
                canvas.save(); canvas.translate(x + u * 3, y); l.draw(canvas); canvas.restore();
            }
            y += l.getHeight() + lineSpace;
        }
        return y + u * 2.2f;
    }

    private static float block(Canvas canvas, String text, TextPaint paint, float x, float y, float width, int maxLines) {
        StaticLayout l = layout(text, paint, (int) width, maxLines);
        if (canvas != null) { canvas.save(); canvas.translate(x, y); l.draw(canvas); canvas.restore(); }
        return y + l.getHeight();
    }
    private static StaticLayout layout(String text, TextPaint paint, int width, int maxLines) {
        return StaticLayout.Builder.obtain(text, 0, text.length(), paint, Math.max(1, width)).setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0, 1.12f).setMaxLines(maxLines).setEllipsize(TextUtils.TruncateAt.END).build();
    }
    private static TextPaint text(int color, float size, boolean bold) {
        TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG); p.setColor(color); p.setTextSize(size); p.setTypeface(Typeface.create(Typeface.SANS_SERIF, bold ? Typeface.BOLD : Typeface.NORMAL)); return p;
    }
    /** Fills `area` with the picture, cropped from its middle to keep its proportions. */
    private static void drawCropped(Canvas canvas, Bitmap picture, RectF area, Paint paint) {
        float scale = Math.max(area.width() / picture.getWidth(), area.height() / picture.getHeight());
        float sw = area.width() / scale, sh = area.height() / scale, sx = (picture.getWidth() - sw) / 2, sy = (picture.getHeight() - sh) / 2;
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        canvas.drawBitmap(picture, new Rect((int) sx, (int) sy, (int) (sx + sw), (int) (sy + sh)), area, p);
    }
    static int mix(int a, int b, float t) {
        return Color.rgb((int) (Color.red(a) * (1 - t) + Color.red(b) * t), (int) (Color.green(a) * (1 - t) + Color.green(b) * t), (int) (Color.blue(a) * (1 - t) + Color.blue(b) * t));
    }
    private static int lighten(int c, float t) { return mix(c, Color.WHITE, t); }
}
