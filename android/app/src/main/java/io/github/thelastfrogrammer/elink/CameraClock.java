package io.github.thelastfrogrammer.elink;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.SystemClock;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * The camera's delay, measured rather than guessed: during the self-test's position probe the head stops at known points
 * and then moves off. The printer's status reports each move the moment it starts (its position jumps to the next stop);
 * the camera shows it a little later. Recording the camera's frames as they arrive, the first motion after stillness is
 * matched with each reported move; the median of the gaps is what Live toolpath waits so its drawing and the picture move
 * together. Only reads the camera stream. The analysis is pure, for tests.
 */
final class CameraClock {
    /** The small grid frames are kept at (brightness only). */
    static final int W = 80, H = 45;
    /** A cell moved when its brightness changed by more than this (0-255) between frames. */
    static final int CELL = 20;
    /** Quiet this long before a motion counts as a move's start (ms). */
    static final long STILL_MS = 1200;

    /** What was measured: the delay (ms), how many moves it comes from, and how far they spread (ms). */
    static final class Result {
        final long delayMs, spreadMs; final int moves;
        Result(long delayMs, int moves, long spreadMs) { this.delayMs = delayMs; this.moves = moves; this.spreadMs = spreadMs; }
    }

    /** Share of cells that changed from each frame to the next (the first is 0). */
    static double[] motion(byte[][] frames) {
        double[] out = new double[frames.length];
        for (int i = 1; i < frames.length; i++) {
            byte[] a = frames[i - 1], b = frames[i]; long sa = 0, sb = 0;
            for (int k = 0; k < a.length; k++) { sa += a[k] & 255; sb += b[k] & 255; }
            double gain = sb <= 0 ? 1 : Math.max(0.5, Math.min(2, sa / (double) sb));
            int moved = 0;
            for (int k = 0; k < a.length; k++) if (Math.abs((b[k] & 255) * gain - (a[k] & 255)) > CELL) moved++;
            out[i] = moved / (double) a.length;
        }
        return out;
    }

    /** When motion starts after stillness (frame arrival times, ms), from the frames' motion shares. */
    static long[] starts(long[] at, double[] motion) {
        double[] sorted = motion.clone(); Arrays.sort(sorted);
        double quiet = sorted[sorted.length / 2], moving = Math.max(0.01, 4 * quiet);
        List<Long> out = new ArrayList<>(); long stillSince = -1;
        for (int i = 1; i < at.length; i++) {
            if (motion[i] <= moving) { if (stillSince < 0) stillSince = at[i - 1]; continue; }
            if (stillSince >= 0 && at[i - 1] - stillSince >= STILL_MS) out.add(at[i]);
            stillSince = -1;
        }
        long[] result = new long[out.size()]; for (int i = 0; i < result.length; i++) result[i] = out.get(i); return result;
    }

    /**
     * The delay from reported moves (status arrival times, ms) to the motion starts seen: each move takes the first start
     * within 0.3 s before it to 6 s after it. Null with fewer than two matched moves.
     */
    static Result delay(long[] moves, long[] starts) {
        List<Long> gaps = new ArrayList<>();
        for (long m : moves) for (long s : starts) if (s >= m - 300 && s <= m + 6000) { gaps.add(s - m); break; }
        if (gaps.size() < 2) return null;
        java.util.Collections.sort(gaps);
        int n = gaps.size(); long median = n % 2 == 1 ? gaps.get(n / 2) : (gaps.get(n / 2 - 1) + gaps.get(n / 2)) / 2;
        return new Result(Math.max(0, median), n, gaps.get(n - 1) - gaps.get(0));
    }

    /** A frame's brightness on the small grid. */
    static byte[] grid(Bitmap frame) {
        Bitmap small = Bitmap.createScaledBitmap(frame, W, H, true);
        int[] rgb = new int[W * H]; small.getPixels(rgb, 0, W, 0, 0, W, H);
        byte[] out = new byte[W * H];
        for (int i = 0; i < rgb.length; i++) { int p = rgb[i]; out[i] = (byte) ((299 * ((p >> 16) & 255) + 587 * ((p >> 8) & 255) + 114 * (p & 255)) / 1000); }
        if (small != frame) small.recycle();
        return out;
    }

    interface Done { void done(long[] at, byte[][] frames, String problem); }

    /**
     * Reads the camera's MJPEG stream for `ms`, every frame as it arrives (no pause, so none wait in the connection),
     * keeping each one's arrival time and small grid. `due` says when a print picture is wanted: that frame is also handed
     * to `picture` (on the reading thread). `done` is called once, on the reading thread.
     */
    static void capture(String url, PrinterHttp.ConnectionFactory factory, long ms, BooleanSupplier due, Consumer<Bitmap> picture, Done done) {
        ExecutorService reader = Executors.newSingleThreadExecutor();
        reader.execute(() -> {
            List<Long> at = new ArrayList<>(); List<byte[]> frames = new ArrayList<>(); String problem = null;
            HttpURLConnection stream = null;
            try {
                stream = factory.open(new URL(url));
                stream.setInstanceFollowRedirects(false); stream.setConnectTimeout(5000); stream.setReadTimeout(6000);
                if (stream.getResponseCode() != 200) throw new IOException("the camera refused the stream");
                long end = SystemClock.elapsedRealtime() + ms;
                try (InputStream input = new BufferedInputStream(stream.getInputStream(), 32768)) {
                    while (SystemClock.elapsedRealtime() < end && frames.size() < 1500) {
                        byte[] jpeg = JpegFrames.next(input);
                        long arrived = SystemClock.elapsedRealtime();
                        BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true; BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, bounds);
                        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) continue;
                        boolean wanted = due.getAsBoolean();
                        BitmapFactory.Options options = new BitmapFactory.Options(); options.inSampleSize = 1;
                        int limit = wanted ? 1920 : W * 4;   // a print picture at full size, otherwise small and quick
                        while (bounds.outWidth / (options.inSampleSize * 2) >= limit) options.inSampleSize *= 2;
                        Bitmap frame = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, options);
                        if (frame == null) continue;
                        at.add(arrived); frames.add(grid(frame));
                        if (wanted) picture.accept(frame); else frame.recycle();
                    }
                }
            } catch (Exception failed) { problem = failed.getClass().getSimpleName(); }
            finally { if (stream != null) stream.disconnect(); }
            long[] times = new long[at.size()]; for (int i = 0; i < times.length; i++) times[i] = at.get(i);
            done.done(times, frames.toArray(new byte[0][]), problem);
            reader.shutdown();
        });
    }

    private CameraClock() { }
}
