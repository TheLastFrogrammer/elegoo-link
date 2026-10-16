package io.github.thelastfrogrammer.elink;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.View;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;

/**
 * A picture of a print's recording so far, for automations to send: temperatures above, progress below, drawn with the same
 * chart (and validated palette, light theme) as the recording page. Draws on the calling thread (use the main thread).
 */
final class PrintChart {
    static final int WIDTH = 1080, HEIGHT = 1200;

    /** The chart as PNG, or null with fewer than two samples. */
    static byte[] png(Context context, PrintRecorder.Recording recording, List<double[]> rows) {
        Bitmap bitmap = bitmap(context, recording, rows);
        if (bitmap == null) return null;
        ByteArrayOutputStream out = new ByteArrayOutputStream(); bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); return out.toByteArray();
    }
    static Bitmap bitmap(Context context, PrintRecorder.Recording recording, List<double[]> rows) {
        if (rows == null || rows.size() < 2) return null;
        int ink = 0xff17252c, muted = 0xff5a6d76, grid = 0xffe2e8ea, surface = Color.WHITE;
        int blue = 0xff2a78d6, orange = 0xffeb6834, aqua = 0xff149a69;
        double[] t = RecordingsActivity.column(rows, "elapsed_s");
        ChartView temperatures = new ChartView(context, ink, muted, grid, surface);
        temperatures.set("Temperatures", " °C", t, Arrays.asList(
            new ChartView.Series("Nozzle", blue, RecordingsActivity.column(rows, "nozzle_c"), false), new ChartView.Series("Nozzle target", blue, RecordingsActivity.column(rows, "nozzle_target_c"), true),
            new ChartView.Series("Bed", orange, RecordingsActivity.column(rows, "bed_c"), false), new ChartView.Series("Bed target", orange, RecordingsActivity.column(rows, "bed_target_c"), true),
            new ChartView.Series("Chamber", aqua, RecordingsActivity.column(rows, "chamber_c"), false)), Double.NaN, Double.NaN);
        ChartView progress = new ChartView(context, ink, muted, grid, surface);
        progress.set("Progress", " %", t, Arrays.asList(new ChartView.Series("Progress", blue, RecordingsActivity.column(rows, "progress"), false)), 0, 100);
        if (recording != null && recording.start > 0) { temperatures.clock(recording.start); progress.clock(recording.start); }
        Bitmap bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap); canvas.drawColor(surface);
        int top = HEIGHT * 3 / 5, pad = 24;
        draw(temperatures, canvas, pad, pad, WIDTH - 2 * pad, top - 2 * pad);
        draw(progress, canvas, pad, top, WIDTH - 2 * pad, HEIGHT - top - pad);
        return bitmap;
    }
    private static void draw(View view, Canvas canvas, int x, int y, int width, int height) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
        canvas.save(); canvas.translate(x, y); view.draw(canvas); canvas.restore();
    }
}
