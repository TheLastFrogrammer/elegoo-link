package io.github.thelastfrogrammer.elink;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * What each layer of a G-code file asks of the printer, read in one pass without keeping the toolpath: its cross section
 * by line type (area, mm²: the material of that type in the layer over the layer's height), the acceleration and speed its
 * extrusions run at, and the flow (mm³/s) they need. Layers are counted as GcodeToolpath counts them, so layer i is the
 * printer's current_layer i + 1. These are what the file asks for; the printer may run differently (speed modes, limits).
 */
final class LayerProfile {
    static final int TYPES = GcodeToolpath.FEATURES.length;
    int layers;
    /** [layer][type]: cross section (mm²). */
    double[][] area = new double[256][];
    /** Per layer: the height (mm), and over its extrusions (weighted by length) the mean acceleration (mm/s²), mean speed (mm/s) and highest flow (mm³/s). */
    double[] height = new double[256], accel = new double[256], speed = new double[256], flowMax = new double[256], accelMax = new double[256];
    private double[] length = new double[256], volume = new double[256];
    /** Per layer: the time its moves take at their feed rates (s, every move and dwell, without acceleration), and the filament it feeds (mm). */
    double[] moveSeconds = new double[256], filament = new double[256];
    /** The slicer's estimate of the whole print (s, from the file's "estimated printing time"), or 0. */
    long estimatedSeconds;
    /** The filament's highest flow from the file's settings (mm³/s), or 0 when it does not say. */
    double maxFlow;

    static LayerProfile read(File file) throws IOException { try (InputStream in = new FileInputStream(file)) { return read(in); } }

    static LayerProfile read(InputStream input) throws IOException {
        LayerProfile p = new LayerProfile();
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8), 1 << 16);
        double x = 0, y = 0, z = 0, e = 0, feed = 0, accel = 0, lastLayerZ = -1, diameter = 1.75, layerHeight = 0.2, typedHeight = Double.NaN;
        boolean absolute = true, absoluteE = true, extrudeAbsolute = true, pendingLayer = false, sawLayerComment = false;
        double pendingSeconds = 0;   // move time after a layer change comment, before that layer's first extrusion
        int type = GcodeToolpath.OTHER;
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) continue;
            if (line.charAt(0) == ';') {
                if (line.startsWith(";LAYER_CHANGE")) pendingLayer = true;
                else if (line.startsWith(";TYPE:")) type = GcodeToolpath.featureOf(line.substring(6).trim());
                else if (line.startsWith(";HEIGHT:")) typedHeight = number(line.substring(8), Double.NaN);
                else if (line.startsWith("; filament_diameter =")) diameter = number(first(line), 1.75);
                else if (line.startsWith("; filament_max_volumetric_speed =")) p.maxFlow = number(first(line), 0);
                else if (line.startsWith("; layer_height =")) layerHeight = number(first(line), 0.2);
                else if (p.estimatedSeconds == 0 && line.contains("estimated printing time") && line.indexOf('=') > 0) p.estimatedSeconds = LayerReach.seconds(line.substring(line.indexOf('=') + 1));
                continue;
            }
            int comment = line.indexOf(';');
            String code = (comment >= 0 ? line.substring(0, comment) : line).trim();
            if (code.length() < 2) continue;
            String upper = code.toUpperCase(java.util.Locale.ROOT);
            // Acceleration as Klipper sets it, and as M204 does (S, or P for printing moves).
            if (upper.startsWith("SET_VELOCITY_LIMIT")) { double a = word(upper, "ACCEL="); if (!Double.isNaN(a)) accel = a; continue; }
            char kind = upper.charAt(0); int n = command(upper);
            if (kind == 'G' && n == 4) { double ms = letter(upper, 'P'), sec = letter(upper, 'S'), wait = !Double.isNaN(ms) ? ms / 1000 : !Double.isNaN(sec) ? sec : 0; if (pendingLayer) pendingSeconds += wait; else if (p.layers > 0) p.moveSeconds[p.layers - 1] += wait; continue; }
            if (kind == 'M' && n == 204) { double a = letter(upper, 'S'); if (Double.isNaN(a)) a = letter(upper, 'P'); if (!Double.isNaN(a)) accel = a; continue; }
            if (kind == 'G' && n >= 0 && n <= 3) {
                double nx = x, ny = y, nz = z, ne = e; boolean hasE = false;
                for (String w : code.split("\\s+")) {
                    if (w.length() < 2) continue;
                    double v; try { v = Double.parseDouble(w.substring(1)); } catch (NumberFormatException bad) { continue; }
                    switch (Character.toUpperCase(w.charAt(0))) {
                        case 'X': nx = absolute ? v : x + v; break;
                        case 'Y': ny = absolute ? v : y + v; break;
                        case 'Z': nz = absolute ? v : z + v; break;
                        case 'E': ne = absoluteE ? v : e + v; hasE = true; break;
                        case 'F': feed = v / 60.0; break;
                        default: break;
                    }
                }
                double moved = Math.hypot(nx - x, ny - y), fed = ne - e;
                // Every move's time at its feed rate counts to the layer it is in (travel too); after a layer change comment, to the next layer.
                double took = 0;
                if (feed > 0) { double path = Math.hypot(moved, nz - z); took = (path > 1e-6 ? path : Math.abs(fed)) / feed; }
                if (hasE && fed > 1e-6 && moved > 1e-6) {
                    boolean newLayer = false;
                    if (pendingLayer) { newLayer = !(p.layers == 1 && !sawLayerComment); sawLayerComment = true; pendingLayer = false; lastLayerZ = nz; }
                    else if (p.layers == 0 || (!sawLayerComment && nz > lastLayerZ + 1e-4)) { newLayer = true; lastLayerZ = nz; }
                    if (newLayer) { p.open(Double.isNaN(typedHeight) ? layerHeight : typedHeight); p.moveSeconds[p.layers - 1] += pendingSeconds; pendingSeconds = 0; }
                    int at = p.layers - 1;
                    double cubic = fed * Math.PI * diameter * diameter / 4;   // the filament fed, as material laid down
                    p.area[at][type] += cubic;   // turned into mm² when the layer's height is known (close())
                    p.volume[at] += cubic; p.length[at] += moved; p.filament[at] += fed;
                    p.accel[at] += accel * moved; p.accelMax[at] = Math.max(p.accelMax[at], accel);
                    p.speed[at] += feed * moved;
                    if (feed > 0) p.flowMax[at] = Math.max(p.flowMax[at], cubic / (moved / feed));
                    if (!Double.isNaN(typedHeight)) p.height[at] = Math.max(p.height[at], typedHeight);
                }
                if (pendingLayer) pendingSeconds += took; else if (p.layers > 0) { p.moveSeconds[p.layers - 1] += took + pendingSeconds; pendingSeconds = 0; }
                x = nx; y = ny; z = nz; e = ne;
            }
            else if (kind == 'G' && n == 90) { absolute = true; absoluteE = extrudeAbsolute; }
            else if (kind == 'G' && n == 91) { absolute = false; absoluteE = false; }
            else if (kind == 'M' && n == 82) { extrudeAbsolute = true; absoluteE = absolute; }
            else if (kind == 'M' && n == 83) { extrudeAbsolute = false; absoluteE = false; }
            else if (kind == 'G' && n == 92) { double v = letter(upper, 'E'); if (!Double.isNaN(v)) e = v; }
        }
        p.close();
        return p;
    }

    private void open(double startHeight) {
        if (layers == area.length) {
            area = Arrays.copyOf(area, layers * 2); height = Arrays.copyOf(height, layers * 2); accel = Arrays.copyOf(accel, layers * 2); speed = Arrays.copyOf(speed, layers * 2);
            flowMax = Arrays.copyOf(flowMax, layers * 2); accelMax = Arrays.copyOf(accelMax, layers * 2); length = Arrays.copyOf(length, layers * 2); volume = Arrays.copyOf(volume, layers * 2);
            moveSeconds = Arrays.copyOf(moveSeconds, layers * 2); filament = Arrays.copyOf(filament, layers * 2);
        }
        area[layers] = new double[TYPES]; height[layers] = 0; layers++;
        if (startHeight > 0) height[layers - 1] = startHeight;
    }
    /** Volumes become areas, sums become means. */
    private void close() {
        for (int i = 0; i < layers; i++) {
            double h = height[i] > 0 ? height[i] : 0.2;
            for (int t = 0; t < TYPES; t++) area[i][t] /= h;
            if (length[i] > 0) { accel[i] /= length[i]; speed[i] /= length[i]; }
        }
    }

    /**
     * Each layer's estimated time (s): its moves' time at their feed rates, scaled so the layers add up to the slicer's own
     * estimate when the file gives one (which counts acceleration; the moves alone come out short).
     */
    double[] estimatedLayerSeconds() {
        double sum = 0; for (int i = 0; i < layers; i++) sum += moveSeconds[i];
        double scale = estimatedSeconds > 0 && sum > 0 ? estimatedSeconds / sum : 1;
        double[] out = new double[layers]; for (int i = 0; i < layers; i++) out[i] = moveSeconds[i] * scale;
        return out;
    }
    /** The layer's whole cross section (mm²). */
    double total(int layer) { double sum = 0; for (double a : area[layer]) sum += a; return sum; }
    /** One type's cross section per layer, as a series. */
    double[] series(int type) { double[] out = new double[layers]; for (int i = 0; i < layers; i++) out[i] = area[i][type]; return out; }
    /** Types with any material in the file, in the palette's order. */
    int[] typesUsed() {
        int count = 0; boolean[] used = new boolean[TYPES];
        for (int i = 0; i < layers; i++) for (int t = 0; t < TYPES; t++) if (area[i][t] > 1e-6 && !used[t]) { used[t] = true; count++; }
        int[] out = new int[count]; int k = 0; for (int t = 0; t < TYPES; t++) if (used[t]) out[k++] = t; return out;
    }
    /** One type's material printed by the end of each layer (cm³): its cross section × the layer's height, added up. */
    double[] cumulative(int type) {
        double[] out = new double[layers]; double sum = 0;
        for (int i = 0; i < layers; i++) { sum += area[i][type] * (height[i] > 0 ? height[i] : 0.2) / 1000; out[i] = sum; }
        return out;
    }
    double[] column(double[] perLayer) { return Arrays.copyOf(perLayer, layers); }

    private static String first(String line) { String v = line.substring(line.indexOf('=') + 1).trim(); int cut = v.indexOf(','); return cut >= 0 ? v.substring(0, cut) : v; }
    private static double number(String text, double fallback) { try { return Double.parseDouble(text.trim()); } catch (NumberFormatException e) { return fallback; } }
    private static double word(String code, String key) {
        int at = code.indexOf(key); if (at < 0) return Double.NaN;
        int end = at + key.length(); while (end < code.length() && " \t".indexOf(code.charAt(end)) < 0) end++;
        return number(code.substring(at + key.length(), end), Double.NaN);
    }
    private static double letter(String code, char letter) {
        for (String w : code.split("\\s+")) if (w.length() > 1 && w.charAt(0) == letter) return number(w.substring(1), Double.NaN);
        return Double.NaN;
    }
    private static int command(String code) {
        int end = 1; while (end < code.length() && Character.isDigit(code.charAt(end))) end++;
        if (end == 1 || end < code.length() && code.charAt(end) == '.') return -1;
        try { return Integer.parseInt(code.substring(1, end)); } catch (NumberFormatException bad) { return -1; }
    }
}
