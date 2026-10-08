package io.github.thelastfrogrammer.elink;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

/** Run on the file worker; verify native dimensions before allocating preview pixels. */
public final class ThumbnailDecoder {
    private ThumbnailDecoder() { }
    /** A preview the printer sent as base64 (with or without a data: prefix), or null. Checks the size before decoding. */
    public static Bitmap decodeBase64(String data) {
        if (data == null || data.isEmpty()) return null;
        try {
            byte[] bytes = android.util.Base64.decode(data.contains(",") ? data.substring(data.indexOf(',') + 1) : data, android.util.Base64.DEFAULT);
            BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            if (options.outWidth <= 0 || options.outHeight <= 0 || options.outWidth > 4096 || options.outHeight > 4096) return null;
            options.inJustDecodeBounds = false; options.inSampleSize = 1;
            while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 512) options.inSampleSize *= 2;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        } catch (RuntimeException invalid) { return null; }
    }
    public static Bitmap decode(EmbeddedThumbnail thumbnail) {
        if (thumbnail == null) return null;
        try {
            byte[] bytes = thumbnail.data(); BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            if (!EmbeddedThumbnail.validDimensions(options.outWidth,options.outHeight) || options.outWidth != thumbnail.width || options.outHeight != thumbnail.height) return null;
            options.inJustDecodeBounds = false; options.inSampleSize = 1;
            while (Math.max(options.outWidth,options.outHeight) / options.inSampleSize > 512) options.inSampleSize *= 2;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            return BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);
        } catch (RuntimeException error) { return null; }
    }
}
