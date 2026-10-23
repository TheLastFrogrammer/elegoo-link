package io.github.thelastfrogrammer.elink;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A recording's pictures set against its kept G-code: where in the file each picture was taken (from the head position and
 * layer the printer reported just before it, located the way Live toolpath follows a print, so the line is where the
 * nozzle was, not a line number the printer gave), and the toolpath up to that point drawn over the picture through the
 * camera as lined up in Live toolpath (CameraFit), for timelapse videos.
 */
final class PictureToolpath {
    /** Further than this from the layer's lines (mm), the head is taken to be away from the print: parked, or moving between parts. */
    static final double AWAY_MM = 3;
    final GcodeToolpath path;
    private final File gcode;
    private final Map<File, Map<String, double[]>> positions = new HashMap<>();

    PictureToolpath(GcodeToolpath path, File gcode) { this.path = path; this.gcode = gcode; }

    /** The recording's kept G-code read for its pictures, or null when it has none (or it cannot be read). Worker only. */
    static PictureToolpath of(PrintRecorder.Recording recording, File cacheDir) {
        File file = RecordingGcode.open(recording, cacheDir);
        if (file == null) return null;
        try { return new PictureToolpath(GcodeToolpath.read(file), file); } catch (IOException | RuntimeException | OutOfMemoryError unreadable) { return null; }
    }

    /** A picture's noted position {s, x, y, z, layer, age}, or null. */
    double[] position(File picture) { return positions.computeIfAbsent(picture.getParentFile(), PrintRecorder::picturePositions).get(picture.getName()); }

    /**
     * Where a picture falls in the file: {segments printed (0..count), the segment the nozzle was on (-1 away), the layer
     * index}. Away from the print (parked at a layer change, or between parts), the layer's start: the layers before are done.
     */
    int[] locate(double[] p) {
        int layer = (int) p[4];
        if (path.count == 0 || path.layerCount == 0 || layer < 1) return null;
        int index = Math.max(0, Math.min(path.layerCount - 1, layer - 1));
        int done = path.locate(layer, p[1], p[2], true, -1);
        int on = Math.min(done, path.count - 1);
        boolean onPrint = on >= path.layerStart(index) && on < path.layerEnd(index) && path.distance(on, p[1], p[2]) <= AWAY_MM;
        return onPrint ? new int[] {done, on, index} : new int[] {path.layerStart(index), -1, index};
    }

    /** "Line 48211: G1 X120.4 Y30.2 E0.0342" for a picture, or "Head away from the print (layer 62)"; null without a position. */
    String lineAt(File picture, GcodeToolpath.LineCursor cursor) {
        double[] p = position(picture); if (p == null) return null;
        int[] at = locate(p); if (at == null) return null;
        if (at[1] < 0) return "Head away from the print (parked, or between parts) on layer " + (at[2] + 1);
        int line = path.lineOf(at[1]);
        String text = line > 0 && cursor != null ? cursor.line(line).trim() : "";
        if (text.length() > 80) text = text.substring(0, 80) + "…";
        return String.format(Locale.getDefault(), "G-code line %,d", line) + (text.isEmpty() ? "" : ": " + text);
    }
    /** Each picture's line (as lineAt), by picture name, reading the file once in order. Worker only. */
    Map<String, String> lines(java.util.List<File> pictures) {
        Map<String, String> out = new HashMap<>();
        try (GcodeToolpath.LineCursor cursor = new GcodeToolpath.LineCursor(gcode)) {
            for (File f : pictures) { String words = lineAt(f, cursor); if (words != null) out.put(f.getName(), words); }
        }
        return out;
    }

    /** Whether the printer's own camera has been lined up in Live toolpath (without it the drawing would not sit on the print). */
    static boolean linedUp(Context context) { return GcodeViewerActivity.linedUp(hints(context), ""); }
    private static SharedPreferences hints(Context context) { return context.getSharedPreferences("viewer-hints", Context.MODE_PRIVATE); }

    /** Draws onto timelapse frames: the toolpath up to each picture's moment, through the lined-up camera. */
    static TimelapseEncoder.Overlay overlay(Context context, PictureToolpath toolpath) {
        SharedPreferences hints = hints(context);
        return (canvas, picture, where) -> {
            double[] p = toolpath.position(picture); if (p == null) return;
            int[] at = toolpath.locate(p); if (at == null) return;
            double[] camera = GcodeViewerActivity.cameraFor(hints, toolpath.path.layerZ(at[2]), "");
            toolpath.draw(canvas, where, camera, at);
        };
    }

    /**
     * The toolpath over a picture shown in `where`: the layers before in a calm teal, this layer's printed part bright, its
     * rest faint. Very large prints are thinned (every n-th line of the earlier layers) to keep each frame quick.
     */
    void draw(Canvas canvas, RectF where, double[] camera, int[] at) {
        double aspect = where.width() / Math.max(1, where.height());
        float width = Math.max(1.5f, where.height() / 360);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG); paint.setStyle(Paint.Style.STROKE); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeWidth(width);
        int layerStart = path.layerStart(at[2]), layerEnd = path.layerEnd(at[2]);
        stroke(canvas, where, camera, aspect, 0, layerStart, Math.max(1, layerStart / 40_000), paint, 0x7023c4b0);
        stroke(canvas, where, camera, aspect, at[0], layerEnd, 1, paint, 0x50ffffff);
        paint.setStrokeWidth(width * 1.6f);
        stroke(canvas, where, camera, aspect, layerStart, at[0], 1, paint, 0xff5fd4c4);
    }
    private void stroke(Canvas canvas, RectF where, double[] camera, double aspect, int from, int to, int stride, Paint paint, int colour) {
        if (to <= from) return;
        paint.setColor(colour); Path lines = new Path();
        for (int i = from; i < to; i += stride) {
            double[] a = CameraFit.project(camera, aspect, path.x0[i], path.y0[i], path.z0[i]), b = CameraFit.project(camera, aspect, path.x1[i], path.y1[i], path.z1[i]);
            if (a == null || b == null) continue;
            lines.moveTo(px(a, where, aspect), py(a, where)); lines.lineTo(px(b, where, aspect), py(b, where));
        }
        canvas.drawPath(lines, paint);
    }
    /** A projected point ({X in picture heights from the centre, Y up}) on the drawn picture. */
    static float px(double[] q, RectF where, double aspect) { return (float) (where.left + (q[0] / aspect + 1) / 2 * where.width()); }
    static float py(double[] q, RectF where) { return (float) (where.top + (1 - q[1]) / 2 * where.height()); }
}
