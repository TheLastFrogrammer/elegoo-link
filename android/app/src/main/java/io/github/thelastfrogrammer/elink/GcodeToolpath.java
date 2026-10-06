package io.github.thelastfrogrammer.elink;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * The extrusion toolpath of a G-code file, for the viewer and for following a running print.
 * Reads ElegooSlicer/OrcaSlicer comments (;LAYER_CHANGE, ;Z:, ;TYPE:, ;WIDTH:, ;HEIGHT:) and moves (G0-G3, absolute or
 * relative, with G92 and M82/M83), and falls back to Z changes for other slicers. Independent of Android.
 */
public final class GcodeToolpath {
    /** Feature types, in the order the viewer's palette uses. */
    public static final String[] FEATURES = {"Outer wall", "Inner wall", "Overhang wall", "Sparse infill", "Internal solid infill",
        "Top surface", "Bottom surface", "Bridge", "Gap infill", "Skirt", "Brim", "Support", "Support interface",
        "Support transition", "Prime tower", "Ironing", "Custom", "Other"};
    public static final int OTHER = FEATURES.length - 1;
    /** Per segment in the binary export: x0 y0 z0 x1 y1 z1 width height type (float32, little-endian). */
    public static final int FLOATS_PER_SEGMENT = 9;
    static final int MAX_SEGMENTS = 6_000_000;

    // Extrusion segments, in file order.
    public int count;
    float[] x0 = new float[1024], y0 = new float[1024], z0 = new float[1024], x1 = new float[1024], y1 = new float[1024], z1 = new float[1024];
    float[] width = new float[1024], height = new float[1024];
    byte[] type = new byte[1024];
    // Travel moves (for display only).
    public int travelCount;
    float[] travel = new float[6 * 1024];
    // Layers: first segment index of each layer, and its Z.
    public int layerCount;
    int[] layerStart = new int[64], layerTravelStart = new int[64];
    float[] layerZ = new float[64];
    public float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ;
    /** Bed outline from "; printable_area = 0x0,256x0,..." when present. */
    public float[] bed;
    public boolean truncated;

    public static GcodeToolpath read(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) { return read(in); }
    }

    public static GcodeToolpath read(InputStream input) throws IOException {
        GcodeToolpath path = new GcodeToolpath();
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8), 1 << 16);
        double x = 0, y = 0, z = 0, e = 0;
        boolean absolute = true, absoluteE = true, pendingLayer = false;
        float lineWidth = 0.45f, layerHeight = 0.2f, lastLayerZ = -1;
        int feature = OTHER;
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) continue;
            if (line.charAt(0) == ';') {
                if (line.startsWith(";TYPE:")) feature = featureOf(line.substring(6).trim());
                else if (line.startsWith(";WIDTH:")) lineWidth = parse(line.substring(7), lineWidth);
                else if (line.startsWith(";HEIGHT:")) layerHeight = parse(line.substring(8), layerHeight);
                else if (line.startsWith(";LAYER_CHANGE")) pendingLayer = true;
                else if (line.startsWith("; printable_area = ")) path.bed = points(line.substring(19));
                continue;
            }
            int comment = line.indexOf(';');
            String code = (comment >= 0 ? line.substring(0, comment) : line).trim();
            if (code.isEmpty()) continue;
            char kind = Character.toUpperCase(code.charAt(0));
            int number = commandNumber(code);
            if (kind == 'G' && (number == 0 || number == 1 || number == 2 || number == 3)) {
                double nx = x, ny = y, nz = z, ne = e, i = 0, j = 0; boolean hasE = false;
                for (String word : code.split("\\s+")) {
                    if (word.length() < 2) continue;
                    char letter = Character.toUpperCase(word.charAt(0));
                    double value;
                    try { value = Double.parseDouble(word.substring(1)); } catch (NumberFormatException bad) { continue; }
                    switch (letter) {
                        case 'X': nx = absolute ? value : x + value; break;
                        case 'Y': ny = absolute ? value : y + value; break;
                        case 'Z': nz = absolute ? value : z + value; break;
                        case 'E': ne = absoluteE ? value : e + value; hasE = true; break;
                        case 'I': i = value; break;
                        case 'J': j = value; break;
                        default: break;
                    }
                }
                boolean moves = nx != x || ny != y;
                boolean extrudes = hasE && ne - e > 1e-6 && moves;
                if (extrudes) {
                    if (pendingLayer) {
                        // Extrusions before the first ;LAYER_CHANGE (the start G-code's purge line) belong to layer 1,
                        // so layer numbers match the printer's current_layer.
                        if (path.layerCount == 1 && !path.sawLayerComment) path.layerZ[0] = (float) nz; else path.addLayer((float) nz);
                        path.sawLayerComment = true; pendingLayer = false; lastLayerZ = (float) nz;
                    } else if (path.layerCount == 0 || (!path.sawLayerComment && nz > lastLayerZ + 1e-4)) {
                        path.addLayer((float) nz); lastLayerZ = (float) nz;
                    }
                    if (number == 2 || number == 3) path.addArc(x, y, nz, nx, ny, x + i, y + j, number == 2, lineWidth, layerHeight, feature);
                    else path.addSegment(x, y, z, nx, ny, nz, lineWidth, layerHeight, feature);
                } else if (moves || nz != z) {
                    path.addTravel(x, y, z, nx, ny, nz);
                }
                x = nx; y = ny; z = nz; e = ne;
                if (path.truncated) break;
            } else if (kind == 'G' && number == 90) { absolute = true; absoluteE = true; }
            else if (kind == 'G' && number == 91) { absolute = false; absoluteE = false; }
            else if (kind == 'M' && number == 82) absoluteE = true;
            else if (kind == 'M' && number == 83) absoluteE = false;
            else if (kind == 'G' && number == 92) {
                for (String word : code.split("\\s+")) {
                    if (word.length() < 2) continue;
                    try {
                        double value = Double.parseDouble(word.substring(1));
                        switch (Character.toUpperCase(word.charAt(0))) {
                            case 'E': e = value; break; case 'X': x = value; break; case 'Y': y = value; break; case 'Z': z = value; break; default: break;
                        }
                    } catch (NumberFormatException ignored) { }
                }
            }
        }
        return path;
    }

    /** Files from slicers that write ;LAYER_CHANGE use only those; other files start a layer whenever Z rises. */
    private boolean sawLayerComment;

    private void addLayer(float z) {
        if (layerCount == layerStart.length) {
            layerStart = Arrays.copyOf(layerStart, layerCount * 2); layerTravelStart = Arrays.copyOf(layerTravelStart, layerCount * 2); layerZ = Arrays.copyOf(layerZ, layerCount * 2);
        }
        layerStart[layerCount] = count; layerTravelStart[layerCount] = layerCount == 0 ? 0 : travelCount; layerZ[layerCount] = z; layerCount++;
    }

    private void addSegment(double ax, double ay, double az, double bx, double by, double bz, float w, float h, int feature) {
        if (count >= MAX_SEGMENTS) { truncated = true; return; }
        if (count == x0.length) grow();
        x0[count] = (float) ax; y0[count] = (float) ay; z0[count] = (float) bz; // extrusions are drawn at the layer they end on
        x1[count] = (float) bx; y1[count] = (float) by; z1[count] = (float) bz;
        width[count] = w; height[count] = h; type[count] = (byte) feature; count++;
        minX = Math.min(minX, (float) Math.min(ax, bx)); maxX = Math.max(maxX, (float) Math.max(ax, bx));
        minY = Math.min(minY, (float) Math.min(ay, by)); maxY = Math.max(maxY, (float) Math.max(ay, by));
        maxZ = Math.max(maxZ, (float) bz);
    }

    /** Arc from (ax,ay) to (bx,by) around (cx,cy), split into segments of at most 10 degrees and 1 mm. */
    private void addArc(double ax, double ay, double z, double bx, double by, double cx, double cy, boolean clockwise, float w, float h, int feature) {
        double start = Math.atan2(ay - cy, ax - cx), end = Math.atan2(by - cy, bx - cx), radius = Math.hypot(ax - cx, ay - cy);
        double sweep = end - start;
        if (clockwise) { if (sweep >= 0) sweep -= 2 * Math.PI; } else { if (sweep <= 0) sweep += 2 * Math.PI; }
        if (Math.abs(bx - ax) < 1e-9 && Math.abs(by - ay) < 1e-9) sweep = clockwise ? -2 * Math.PI : 2 * Math.PI;
        int steps = (int) Math.max(1, Math.ceil(Math.max(Math.abs(sweep) / Math.toRadians(10), Math.abs(sweep) * radius / 1.0)));
        double px = ax, py = ay;
        for (int step = 1; step <= steps; step++) {
            double angle = start + sweep * step / steps;
            double qx = step == steps ? bx : cx + radius * Math.cos(angle), qy = step == steps ? by : cy + radius * Math.sin(angle);
            addSegment(px, py, z, qx, qy, z, w, h, feature);
            px = qx; py = qy;
        }
    }

    private void addTravel(double ax, double ay, double az, double bx, double by, double bz) {
        if (travelCount >= MAX_SEGMENTS) return;
        if ((travelCount + 1) * 6 > travel.length) travel = Arrays.copyOf(travel, travel.length * 2);
        int o = travelCount * 6;
        travel[o] = (float) ax; travel[o + 1] = (float) ay; travel[o + 2] = (float) az;
        travel[o + 3] = (float) bx; travel[o + 4] = (float) by; travel[o + 5] = (float) bz;
        travelCount++;
    }

    private void grow() {
        int size = x0.length * 2;
        x0 = Arrays.copyOf(x0, size); y0 = Arrays.copyOf(y0, size); z0 = Arrays.copyOf(z0, size);
        x1 = Arrays.copyOf(x1, size); y1 = Arrays.copyOf(y1, size); z1 = Arrays.copyOf(z1, size);
        width = Arrays.copyOf(width, size); height = Arrays.copyOf(height, size); type = Arrays.copyOf(type, size);
    }

    static int featureOf(String name) {
        for (int i = 0; i < FEATURES.length - 1; i++) if (FEATURES[i].equalsIgnoreCase(name)) return i;
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.contains("bridge")) return 7;
        if (lower.contains("support")) return lower.contains("interface") ? 12 : 11;
        if (lower.contains("wall") || lower.contains("perimeter")) return lower.contains("external") || lower.contains("outer") ? 0 : 1;
        if (lower.contains("solid")) return 4;
        if (lower.contains("infill")) return 3;
        return OTHER;
    }

    private static int commandNumber(String code) {
        int i = 1, n = 0; boolean any = false;
        while (i < code.length() && Character.isDigit(code.charAt(i))) { n = n * 10 + (code.charAt(i) - '0'); i++; any = true; }
        if (i < code.length() && code.charAt(i) == '.') return -1; // G29.1 and similar are not moves
        return any ? n : -1;
    }

    private static float parse(String text, float fallback) { try { return Float.parseFloat(text.trim()); } catch (NumberFormatException e) { return fallback; } }

    private static float[] points(String text) {
        String[] pairs = text.trim().split(",");
        float[] result = new float[pairs.length * 2];
        try {
            for (int i = 0; i < pairs.length; i++) { String[] xy = pairs[i].trim().split("x"); result[2 * i] = Float.parseFloat(xy[0]); result[2 * i + 1] = Float.parseFloat(xy[1]); }
        } catch (RuntimeException bad) { return null; }
        return result.length >= 6 ? result : null;
    }

    public int layerStart(int layer) { return layer >= layerCount ? count : layerStart[Math.max(0, layer)]; }
    public int layerEnd(int layer) { return layer + 1 >= layerCount ? count : layerStart[layer + 1]; }
    public float layerZ(int layer) { return layerCount == 0 ? 0 : layerZ[Math.max(0, Math.min(layer, layerCount - 1))]; }
    public int featureOfSegment(int index) { return type[index]; }
    /** Layer containing segment `index`. */
    public int layerOf(int index) {
        int low = 0, high = layerCount - 1;
        while (low < high) { int mid = (low + high + 1) >>> 1; if (layerStart[mid] <= index) low = mid; else high = mid - 1; }
        return Math.max(0, low);
    }
    /** Extruded length per feature, for the legend. */
    public double[] featureLengths() {
        double[] lengths = new double[FEATURES.length];
        for (int i = 0; i < count; i++) lengths[type[i]] += Math.hypot(x1[i] - x0[i], y1[i] - y0[i]);
        return lengths;
    }

    /**
     * Where a running print is: the number of segments already printed, from the printer's reported layer (1-based,
     * as the CC2 reports current_layer) and nozzle position. Within the layer it takes the segment nearest the nozzle,
     * preferring positions at or after `previous` so the progress does not jump back on a wall the nozzle crosses.
     * Without a position it returns the start of the layer. Returns a value in [0, count].
     */
    public int locate(int currentLayer, double nozzleX, double nozzleY, boolean hasPosition, int previous) {
        if (count == 0 || layerCount == 0) return 0;
        int layer = Math.max(0, Math.min(layerCount - 1, currentLayer - 1));
        int start = layerStart(layer), end = layerEnd(layer);
        if (!hasPosition || end <= start) return start;
        int best = -1; double bestDistance = Double.MAX_VALUE;
        int from = previous >= start && previous < end ? previous : start;
        // Forward from the last position first: the nozzle only moves on through the layer.
        for (int i = from; i < end; i++) {
            double d = distanceToSegment(i, nozzleX, nozzleY);
            if (d < bestDistance) { bestDistance = d; best = i; }
            if (bestDistance < 0.05) break;
        }
        if (bestDistance > 2.0) {
            for (int i = start; i < from; i++) {
                double d = distanceToSegment(i, nozzleX, nozzleY);
                if (d < bestDistance) { bestDistance = d; best = i; }
            }
        }
        if (best < 0) return start;
        // The nozzle is on segment `best`; segments before it are printed. At its end point, that segment is done too.
        double toEnd = Math.hypot(x1[best] - nozzleX, y1[best] - nozzleY);
        return toEnd < 0.05 ? best + 1 : best;
    }

    private double distanceToSegment(int i, double px, double py) {
        double ax = x0[i], ay = y0[i], bx = x1[i], by = y1[i], dx = bx - ax, dy = by - ay, length = dx * dx + dy * dy;
        double t = length == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / length));
        return Math.hypot(ax + t * dx - px, ay + t * dy - py);
    }

    /** Segments for the viewer: FLOATS_PER_SEGMENT float32 values each, little-endian. */
    public byte[] segmentsBinary() {
        ByteBuffer buffer = ByteBuffer.allocate(count * FLOATS_PER_SEGMENT * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < count; i++)
            buffer.putFloat(x0[i]).putFloat(y0[i]).putFloat(z0[i]).putFloat(x1[i]).putFloat(y1[i]).putFloat(z1[i])
                .putFloat(width[i]).putFloat(height[i]).putFloat(type[i]);
        return buffer.array();
    }

    /** Travel moves for the viewer: 6 float32 values each. */
    public byte[] travelsBinary() {
        ByteBuffer buffer = ByteBuffer.allocate(travelCount * 6 * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < travelCount * 6; i++) buffer.putFloat(travel[i]);
        return buffer.array();
    }

    /** Layer starts and Zs, and the bed, as JSON for the viewer. */
    public String layersJson() {
        StringBuilder json = new StringBuilder("{\"segments\":").append(count).append(",\"travels\":").append(travelCount).append(",\"starts\":[");
        for (int i = 0; i < layerCount; i++) json.append(i > 0 ? "," : "").append(layerStart[i]);
        json.append("],\"travelStarts\":[");
        for (int i = 0; i < layerCount; i++) json.append(i > 0 ? "," : "").append(layerTravelStart[i]);
        json.append("],\"z\":[");
        for (int i = 0; i < layerCount; i++) json.append(i > 0 ? "," : "").append(String.format(Locale.ROOT, "%.3f", layerZ[i]));
        json.append("],\"bed\":[");
        float[] outline = bed != null ? bed : new float[] {0, 0, 256, 0, 256, 256, 0, 256};
        for (int i = 0; i < outline.length; i++) json.append(i > 0 ? "," : "").append(outline[i]);
        json.append("],\"features\":[");
        for (int i = 0; i < FEATURES.length; i++) json.append(i > 0 ? "," : "").append('"').append(FEATURES[i]).append('"');
        return json.append("]}").toString();
    }
}
