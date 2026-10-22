package io.github.thelastfrogrammer.elink;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.view.Surface;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A timelapse video (H.264 in MP4) made on the phone from a recording's camera pictures: each picture is drawn onto the
 * encoder's surface with its time into the print, and frames are stamped at a fixed rate so the whole print plays in about
 * ten seconds. Runs on the caller's (background) thread.
 */
final class TimelapseEncoder {
    interface Progress { void at(int done, int total); }

    /** Pictures a second: about ten seconds of video, at least 2 and at most 15 a second. */
    static int framesPerSecond(int pictures) { return Math.max(2, Math.min(15, Math.round(pictures / 10f))); }
    /** The finished print stays on screen this long at the end (s). */
    static final double HOLD_SECONDS = 1.5;
    /** Frames that repeat the last picture at the end. */
    static int holdFrames(int fps) { return (int) Math.round(fps * HOLD_SECONDS); }

    /**
     * Brightness evened out between pictures (the chamber light, auto exposure): each picture's gain toward the median of
     * its neighbours (seven pictures around it; the median, so one odd picture does not shift the ones beside it), at most a
     * quarter brighter or a fifth darker so real changes stay. Mean brightness per picture (0-255; 0 or less: unknown, left
     * as it is). Pure, for tests.
     */
    static double[] gains(double[] means) {
        double[] out = new double[means.length];
        for (int i = 0; i < means.length; i++) {
            if (means[i] <= 0) { out[i] = 1; continue; }
            List<Double> around = new java.util.ArrayList<>();
            for (int j = Math.max(0, i - 3); j <= Math.min(means.length - 1, i + 3); j++) if (means[j] > 0) around.add(means[j]);
            java.util.Collections.sort(around);
            int n = around.size(); double median = n % 2 == 1 ? around.get(n / 2) : (around.get(n / 2 - 1) + around.get(n / 2)) / 2;
            out[i] = Math.max(0.8, Math.min(1.25, median / means[i]));
        }
        return out;
    }
    /** A picture's mean brightness (0-255), from a small copy; -1 when unreadable. */
    static double brightness(File picture) {
        if (picture == null) return -1;
        BitmapFactory.Options small = new BitmapFactory.Options(); small.inSampleSize = 16;
        Bitmap image = BitmapFactory.decodeFile(picture.getPath(), small);
        if (image == null) return -1;
        long sum = 0; int n = 0;
        for (int y = 0; y < image.getHeight(); y += 2) for (int x = 0; x < image.getWidth(); x += 2) {
            int c = image.getPixel(x, y); sum += (299 * Color.red(c) + 587 * Color.green(c) + 114 * Color.blue(c)) / 1000; n++;
        }
        image.recycle();
        return n == 0 ? -1 : (double) sum / n;
    }
    /** Video size for pictures of this size: at most 1280 wide, both sides even multiples of 16 (what encoders accept everywhere). */
    static int[] size(int width, int height) { return size(width, height, 1280); }
    static int[] size(int width, int height, int maxWidth) {
        double scale = width > maxWidth ? (double) maxWidth / width : 1;
        int w = Math.max(160, (int) (width * scale) / 16 * 16), h = Math.max(128, (int) (height * scale) / 16 * 16);
        return new int[] {w, h};
    }

    static void encode(List<File> pictures, File out, AtomicBoolean cancelled, Progress progress) throws IOException { encode(pictures, null, out, cancelled, progress); }
    static void encode(List<File> pictures, java.util.function.Function<File, String> caption, File out, AtomicBoolean cancelled, Progress progress) throws IOException {
        List<File[]> frames = new java.util.ArrayList<>(); for (File picture : pictures) frames.add(new File[] {picture});
        encode(frames, null, caption, out, cancelled, progress);
    }

    /**
     * Two sets of one print side by side, a layer per frame: each parked picture with the far picture of the same layer (the
     * last far one before the next parked one; else the last before it). Picture times as seconds (their names), sorted.
     * Returns {left index, right index or -1} per frame. Pure, for tests.
     */
    static int[][] pair(double[] left, double[] right) {
        int[][] pairs = new int[left.length][];
        for (int i = 0; i < left.length; i++) {
            double until = i + 1 < left.length ? left[i + 1] : Double.MAX_VALUE;
            int match = -1;
            for (int j = 0; j < right.length; j++) if (right[j] < until) match = j;   // the latest one before the next parked picture
            pairs[i] = new int[] {i, match};
        }
        return pairs;
    }
    /** As `pair`, for picture files. */
    static List<File[]> pairFiles(List<File> left, List<File> right) {
        double[] l = new double[left.size()], r = new double[right.size()];
        for (int i = 0; i < l.length; i++) l[i] = seconds(left.get(i));
        for (int i = 0; i < r.length; i++) r[i] = seconds(right.get(i));
        List<File[]> frames = new java.util.ArrayList<>();
        for (int[] p : pair(l, r)) frames.add(new File[] {left.get(p[0]), p[1] < 0 ? null : right.get(p[1])});
        return frames;
    }

    /**
     * The video from frames of one picture, or of two side by side (`labels` names each side; a missing picture leaves its
     * side dark). Each panel keeps its picture's shape; the whole frame is at most 1920 wide with two.
     */
    static void encode(List<File[]> frames, String[] labels, File out, AtomicBoolean cancelled, Progress progress) throws IOException { encode(frames, labels, null, out, cancelled, progress); }
    /** As above, with `caption` (may be null, or answer null) adding words to a picture's lower right: the head's position. */
    static void encode(List<File[]> frames, String[] labels, java.util.function.Function<File, String> caption, File out, AtomicBoolean cancelled, Progress progress) throws IOException {
        if (frames.isEmpty()) throw new IOException("No pictures");
        int panels = frames.get(0).length;
        BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true; BitmapFactory.decodeFile(frames.get(0)[0].getPath(), bounds);
        if (bounds.outWidth <= 0) throw new IOException("Unreadable picture");
        int[] wh = size(bounds.outWidth * panels, bounds.outHeight, panels > 1 ? 1920 : 1280); int fps = framesPerSecond(frames.size());
        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, wh[0], wh[1]);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, wh[0] * wh[1] * 4); format.setInteger(MediaFormat.KEY_FRAME_RATE, fps); format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        if (android.os.Build.VERSION.SDK_INT >= 29) format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);   // output in input order, so stamps can follow it
        MediaCodec codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        MediaMuxer muxer = null; Surface input = null;
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            input = codec.createInputSurface(); codec.start();
            muxer = new MediaMuxer(out.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            Drain drain = new Drain(codec, muxer, fps);
            Paint picture = new Paint(Paint.FILTER_BITMAP_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG), shade = new Paint();
            text.setColor(Color.WHITE); text.setTextSize(wh[1] / 22f); shade.setColor(0x88000000);
            float panelWidth = (float) wh[0] / panels, pad = wh[1] / 40f;
            // Each side's brightness, evened out along the video.
            double[][] gain = new double[panels][];
            for (int k = 0; k < panels; k++) {
                double[] means = new double[frames.size()];
                for (int i = 0; i < means.length; i++) { if (cancelled.get()) throw new IOException("Cancelled"); means[i] = brightness(frames.get(i)[k]); }
                gain[k] = gains(means);
            }
            int last = frames.size() - 1, total = frames.size() + holdFrames(fps);
            for (int n = 0; n < total; n++) {
                if (cancelled.get()) throw new IOException("Cancelled");
                int i = Math.min(n, last);   // the last picture is held at the end
                Canvas canvas = input.lockHardwareCanvas();
                try {
                    canvas.drawColor(Color.BLACK);
                    for (int k = 0; k < panels; k++) {
                        File file = frames.get(i)[k]; float left = k * panelWidth;
                        Bitmap image = file == null ? null : BitmapFactory.decodeFile(file.getPath());
                        float g = (float) gain[k][i];
                        picture.setColorFilter(Math.abs(g - 1) < 0.01 ? null : new android.graphics.ColorMatrixColorFilter(
                            new android.graphics.ColorMatrix(new float[] {g, 0, 0, 0, 0, 0, g, 0, 0, 0, 0, 0, g, 0, 0, 0, 0, 0, 1, 0})));
                        if (image != null) {
                            float s = Math.min(panelWidth / image.getWidth(), (float) wh[1] / image.getHeight()), dw = image.getWidth() * s, dh = image.getHeight() * s;
                            canvas.drawBitmap(image, null, new RectF(left + (panelWidth - dw) / 2, (wh[1] - dh) / 2, left + (panelWidth + dw) / 2, (wh[1] + dh) / 2), picture);
                            image.recycle();
                        }
                        if (labels != null && k < labels.length) label(canvas, labels[k], left + pad, pad, text, shade, pad);
                        // The time into the print: each side's own (the far picture comes later in the layer).
                        if (file != null) label(canvas, ChartView.elapsed(seconds(file)), left + pad, wh[1] - pad * 3 - text.getTextSize(), text, shade, pad);
                        String words = file == null || caption == null ? null : caption.apply(file);
                        if (words != null) label(canvas, words, left + panelWidth - pad - (text.measureText(words) + pad * 2), wh[1] - pad * 3 - text.getTextSize(), text, shade, pad);
                    }
                } finally { input.unlockCanvasAndPost(canvas); }
                drain.until(n + 1, 1000);
                progress.at(Math.min(n + 1, frames.size()), frames.size());
            }
            codec.signalEndOfInputStream();
            drain.until(Integer.MAX_VALUE, 5000);
            if (drain.written == 0) throw new IOException("The phone's video encoder made no frames");
        } finally {
            try { codec.stop(); } catch (RuntimeException ignored) { }
            codec.release(); if (input != null) input.release();
            if (muxer != null) try { muxer.stop(); } catch (RuntimeException ignored) { } finally { muxer.release(); }
        }
    }
    /** The head's position at each picture, from the positions noted beside it (PrintRecorder.picturePositions); null where none was. */
    static java.util.function.Function<File, String> positions() {
        java.util.Map<File, java.util.Map<String, double[]>> folders = new java.util.HashMap<>();
        return file -> {
            double[] p = folders.computeIfAbsent(file.getParentFile(), PrintRecorder::picturePositions).get(file.getName());
            return p == null ? null : positionWords(p);
        };
    }
    /** "X 120.4  Y 30.2  Z 12.40  L 62" from {s, x, y, z, layer, age}. Pure. */
    static String positionWords(double[] p) {
        return String.format(java.util.Locale.ROOT, "X %.1f  Y %.1f  Z %.2f", p[1], p[2], p[3]) + (p[4] >= 1 ? "  L " + (int) p[4] : "");
    }
    /** Words on a dark band, its top left corner at x, y. */
    private static void label(Canvas canvas, String words, float x, float y, Paint text, Paint shade, float pad) {
        float tw = text.measureText(words);
        canvas.drawRect(x, y, x + tw + pad * 2, y + text.getTextSize() + pad * 1.4f, shade);
        canvas.drawText(words, x + pad, y + text.getTextSize() + pad * 0.4f, text);
    }
    /** Seconds into the print, from the picture's name ("000060.jpg"). */
    private static double seconds(File picture) { try { return Double.parseDouble(picture.getName().replace(".jpg", "")); } catch (NumberFormatException e) { return 0; } }

    /** Moves encoded frames into the file, stamping them one frame apart. */
    private static final class Drain {
        final MediaCodec codec; final MediaMuxer muxer; final long frameUs; int track = -1, written; boolean ended;
        final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        Drain(MediaCodec codec, MediaMuxer muxer, int fps) { this.codec = codec; this.muxer = muxer; this.frameUs = 1_000_000L / fps; }
        /** Writes frames until `count` are out (or the stream ends), waiting at most `waitMs` for each. */
        void until(int count, long waitMs) {
            long deadline = System.currentTimeMillis() + waitMs;
            while (written < count && !ended && System.currentTimeMillis() < deadline) {
                int index = codec.dequeueOutputBuffer(info, 10_000);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { track = muxer.addTrack(codec.getOutputFormat()); muxer.start(); continue; }
                if (index < 0) continue;
                ByteBuffer data = codec.getOutputBuffer(index);
                boolean config = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                if (!config && info.size > 0 && track >= 0 && data != null) {
                    info.presentationTimeUs = written * frameUs; data.position(info.offset); data.limit(info.offset + info.size);
                    muxer.writeSampleData(track, data, info); written++; deadline = System.currentTimeMillis() + waitMs;
                }
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) ended = true;
                codec.releaseOutputBuffer(index, false);
            }
        }
    }
}
