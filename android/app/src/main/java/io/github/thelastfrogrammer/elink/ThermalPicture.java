package io.github.thelastfrogrammer.elink;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import java.io.ByteArrayOutputStream;

/**
 * A thermal picture drawn for people: the iron palette, a cross at the centre with its temperature, a mark on the hottest
 * point with its temperature, and a scale bar from the coolest to the hottest shown. Without temperatures (a picture-only
 * format, or values outside a believable range) it shows the picture and says so. Every view of the camera uses this, so
 * the Print tab, print pictures and other apps see the same figures.
 */
final class ThermalPicture {
    static Bitmap bitmap(ThermalImage raw, int rotation, boolean mirror) {
        ThermalImage image = raw.turned(rotation, mirror);
        ThermalImage.Stats s = image.stats();
        // Drawn at twice the camera's size at most 1280 wide, so the figures stay sharp on a phone and in a picture.
        int factor = Math.max(1, Math.min(2, 1280 / Math.max(1, image.width)));
        Bitmap small = Bitmap.createBitmap(image.colours(s), image.width, image.height, Bitmap.Config.ARGB_8888);
        int w = image.width * factor, h = image.height * factor, bar = Math.max(28, h / 14);
        Bitmap out = Bitmap.createBitmap(w, h + bar, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawColor(0xff101418);
        c.drawBitmap(small, null, new RectF(0, 0, w, h), new Paint(Paint.FILTER_BITMAP_FLAG));
        float text = Math.max(14, w / 36f);
        Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG); ink.setTextSize(text); ink.setTypeface(Typeface.DEFAULT_BOLD); ink.setColor(0xffffffff);
        Paint halo = new Paint(ink); halo.setStyle(Paint.Style.STROKE); halo.setStrokeWidth(text / 5); halo.setColor(0xcc000000);
        Paint line = new Paint(Paint.ANTI_ALIAS_FLAG); line.setStrokeWidth(Math.max(2, text / 8)); line.setStyle(Paint.Style.STROKE); line.setColor(0xffffffff);
        Paint shadow = new Paint(line); shadow.setStrokeWidth(line.getStrokeWidth() * 2.2f); shadow.setColor(0xaa000000);
        if (s.temperatures) {
            // The centre: a cross and its temperature.
            float cx = w / 2f, cy = h / 2f, arm = text * 0.9f;
            for (Paint p : new Paint[] {shadow, line}) { c.drawLine(cx - arm, cy, cx + arm, cy, p); c.drawLine(cx, cy - arm, cx, cy + arm, p); }
            label(c, image.words(s, s.centre), cx + arm * 0.6f, cy - arm * 0.6f, ink, halo, w);
            // The hottest point: a triangle pointing at it and its temperature.
            float hx = (s.hotX + 0.5f) * factor, hy = (s.hotY + 0.5f) * factor, size = text * 0.7f;
            Path tri = new Path(); tri.moveTo(hx, hy); tri.lineTo(hx - size / 2, hy + size); tri.lineTo(hx + size / 2, hy + size); tri.close();
            Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG); fill.setColor(0xff00e5ff);   // cyan: a colour the iron palette never uses
            c.drawPath(tri, shadow); c.drawPath(tri, fill);
            label(c, "max " + image.words(s, s.hottest), hx + size * 0.7f, hy + size * 1.8f, ink, halo, w);
        }
        // The scale: coolest to hottest shown, in the palette's colours.
        float top = h + bar * 0.2f, bottom = h + bar * 0.8f, left = w * 0.25f, right = w * 0.75f;
        Paint swatch = new Paint();
        for (int x = (int) left; x < right; x++) { swatch.setColor(ThermalImage.colour((x - left) / (right - left))); c.drawRect(x, top, x + 1, bottom, swatch); }
        Paint small2 = new Paint(ink); small2.setTypeface(Typeface.DEFAULT); small2.setTextSize(Math.min(text, bar * 0.6f));
        float base = h + bar / 2f + small2.getTextSize() / 3;
        if (s.temperatures) {
            String lo = image.words(s, s.low), hi = image.words(s, s.high);
            small2.setTextAlign(Paint.Align.RIGHT); c.drawText(lo, left - text / 2, base, small2);
            small2.setTextAlign(Paint.Align.LEFT); c.drawText(hi, right + text / 2, base, small2);
        } else {
            small2.setTextAlign(Paint.Align.LEFT); c.drawText("No temperatures", text / 2, base, small2);
        }
        return out;
    }
    /** Text with a dark edge, kept inside the picture. */
    private static void label(Canvas c, String text, float x, float y, Paint ink, Paint halo, int width) {
        if (text.isEmpty()) return;
        float tw = ink.measureText(text); if (x + tw > width - 4) x = Math.max(4, x - tw - ink.getTextSize() * 1.6f);
        y = Math.max(ink.getTextSize(), y);
        c.drawText(text, x, y, halo); c.drawText(text, x, y, ink);
    }
    static byte[] jpeg(Bitmap b) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); b.compress(Bitmap.CompressFormat.JPEG, 85, out); return out.toByteArray();
    }

    private ThermalPicture() { }
}
