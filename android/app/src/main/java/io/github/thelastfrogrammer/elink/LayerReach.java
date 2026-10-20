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
 * How near to and how far from a camera each layer of a G-code file reaches: the nearest and farthest extrusion point (mm,
 * in the bed's plane) per layer. Read in one pass without keeping the toolpath, so files of any size work. Layers are
 * counted exactly as GcodeToolpath counts them (layer 0 is the printer's current_layer 1).
 */
final class LayerReach {
    final float[] near, far;
    /** Each layer's printed area: its extrusions' smallest and largest X and Y. */
    final float[] minX, minY, maxX, maxY;
    final int layers;

    private LayerReach(float[] near, float[] far, float[] minX, float[] minY, float[] maxX, float[] maxY, int layers) {
        this.near = near; this.far = far; this.minX = minX; this.minY = minY; this.maxX = maxX; this.maxY = maxY; this.layers = layers;
    }

    /** The layer's printed area {min x, min y, max x, max y} for the printer's current_layer, or null. */
    float[] boxForPrinterLayer(int currentLayer) {
        int i = currentLayer - 1;
        return i < 0 || i >= layers || far[i] <= 0 ? null : new float[] {minX[i], minY[i], maxX[i], maxY[i]};
    }

    /** The layer's {nearest, farthest} for the printer's current_layer (1-based), or null when the file has no such layer. */
    float[] forPrinterLayer(int currentLayer) {
        int i = currentLayer - 1;
        return i < 0 || i >= layers || far[i] <= 0 ? null : new float[] {near[i], far[i]};
    }

    static LayerReach read(File file, double cameraX, double cameraY) throws IOException {
        try (InputStream in = new FileInputStream(file)) { return read(in, cameraX, cameraY); }
    }

    static LayerReach read(InputStream input, double cameraX, double cameraY) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8), 1 << 16);
        float[] near = new float[256], far = new float[256], minX = new float[256], minY = new float[256], maxX = new float[256], maxY = new float[256];
        int layers = 0;
        double x = 0, y = 0, z = 0, e = 0, lastLayerZ = -1;
        boolean absolute = true, absoluteE = true, pendingLayer = false, sawLayerComment = false;
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) continue;
            if (line.charAt(0) == ';') { if (line.startsWith(";LAYER_CHANGE")) pendingLayer = true; continue; }
            int comment = line.indexOf(';');
            String code = (comment >= 0 ? line.substring(0, comment) : line).trim();
            if (code.length() < 2) continue;
            char kind = Character.toUpperCase(code.charAt(0));
            int number = number(code);
            if (kind == 'G' && number >= 0 && number <= 3) {
                double nx = x, ny = y, nz = z, ne = e; boolean hasE = false;
                for (String word : code.split("\\s+")) {
                    if (word.length() < 2) continue;
                    double value; try { value = Double.parseDouble(word.substring(1)); } catch (NumberFormatException bad) { continue; }
                    switch (Character.toUpperCase(word.charAt(0))) {
                        case 'X': nx = absolute ? value : x + value; break;
                        case 'Y': ny = absolute ? value : y + value; break;
                        case 'Z': nz = absolute ? value : z + value; break;
                        case 'E': ne = absoluteE ? value : e + value; hasE = true; break;
                        default: break;
                    }
                }
                boolean moves = nx != x || ny != y;
                if (hasE && ne - e > 1e-6 && moves) {
                    // The same layer rules as GcodeToolpath, so the numbers match the printer's.
                    boolean newLayer = false;
                    if (pendingLayer) { newLayer = !(layers == 1 && !sawLayerComment); sawLayerComment = true; pendingLayer = false; lastLayerZ = nz; }
                    else if (layers == 0 || (!sawLayerComment && nz > lastLayerZ + 1e-4)) { newLayer = true; lastLayerZ = nz; }
                    if (newLayer) {
                        if (layers == near.length) {
                            near = Arrays.copyOf(near, layers * 2); far = Arrays.copyOf(far, layers * 2);
                            minX = Arrays.copyOf(minX, layers * 2); minY = Arrays.copyOf(minY, layers * 2); maxX = Arrays.copyOf(maxX, layers * 2); maxY = Arrays.copyOf(maxY, layers * 2);
                        }
                        near[layers] = Float.MAX_VALUE; far[layers] = 0;
                        minX[layers] = minY[layers] = Float.MAX_VALUE; maxX[layers] = maxY[layers] = -Float.MAX_VALUE; layers++;
                    }
                    int at = layers - 1;
                    for (double[] p : new double[][] {{x, y}, {nx, ny}}) {
                        float d = (float) Math.hypot(p[0] - cameraX, p[1] - cameraY);
                        if (d < near[at]) near[at] = d; if (d > far[at]) far[at] = d;
                        float px = (float) p[0], py = (float) p[1];
                        if (px < minX[at]) minX[at] = px; if (px > maxX[at]) maxX[at] = px; if (py < minY[at]) minY[at] = py; if (py > maxY[at]) maxY[at] = py;
                    }
                }
                x = nx; y = ny; z = nz; e = ne;
            }
            else if (kind == 'G' && number == 90) { absolute = true; absoluteE = true; }
            else if (kind == 'G' && number == 91) { absolute = false; absoluteE = false; }
            else if (kind == 'M' && number == 82) absoluteE = true;
            else if (kind == 'M' && number == 83) absoluteE = false;
            else if (kind == 'G' && number == 92) {
                for (String word : code.split("\\s+")) {
                    if (word.length() < 2) continue;
                    try {
                        double value = Double.parseDouble(word.substring(1));
                        switch (Character.toUpperCase(word.charAt(0))) { case 'E': e = value; break; case 'X': x = value; break; case 'Y': y = value; break; case 'Z': z = value; break; default: break; }
                    } catch (NumberFormatException ignored) { }
                }
            }
        }
        return new LayerReach(near, far, minX, minY, maxX, maxY, layers);
    }

    private static int number(String code) {
        int end = 1; while (end < code.length() && Character.isDigit(code.charAt(end))) end++;
        if (end == 1 || end < code.length() && code.charAt(end) == '.') return -1;   // G29.1 and similar are not moves
        try { return Integer.parseInt(code.substring(1, end)); } catch (NumberFormatException bad) { return -1; }
    }
}
