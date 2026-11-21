package io.github.thelastfrogrammer.elink;

import java.util.Locale;

/**
 * A thermal picture: pixel values and, when the camera sends temperatures, how many kelvin one step is. Turned and
 * mirrored as the camera is set, coloured with an iron palette between the coolest and hottest parts of the scene (the
 * extreme 0.5 % at each end left out, so one hot pixel does not wash out the rest), with the hottest point, the centre
 * and the range in °C. Independent of Android.
 */
final class ThermalImage {
    /** Readings outside this range (°C) mean the scale is not what was assumed: the picture is shown without temperatures. */
    static final double LOWEST_C = -60, HIGHEST_C = 2000;
    static final double KELVIN = 273.15;

    final int width, height; final int[] values;
    /** Kelvin per step (0.01 for a 10 mK temperature-linear picture), or NaN when the values are not temperatures. */
    final double scale;
    ThermalImage(int width, int height, int[] values, double scale) { this.width = width; this.height = height; this.values = values; this.scale = scale; }

    /** °C at a pixel value; NaN without temperatures. */
    double celsius(double value) { return Double.isNaN(scale) ? Double.NaN : value * scale - KELVIN; }

    /** Turned clockwise by 0, 90, 180 or 270 degrees after mirroring left to right (as ExtraCameras.turn does a picture). */
    ThermalImage turned(int rotation, boolean mirror) {
        if (rotation == 0 && !mirror) return this;
        boolean swap = rotation == 90 || rotation == 270;
        int w = swap ? height : width, h = swap ? width : height; int[] out = new int[values.length];
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int sx = mirror ? width - 1 - x : x, nx, ny;
            switch (rotation) {
                case 90: nx = height - 1 - y; ny = sx; break;
                case 180: nx = width - 1 - sx; ny = height - 1 - y; break;
                case 270: nx = y; ny = width - 1 - sx; break;
                default: nx = sx; ny = y;
            }
            out[ny * w + nx] = values[y * width + x];
        }
        return new ThermalImage(w, h, out, scale);
    }

    /** What the picture shows: the colour range, the hottest point and the centre (a 3 × 3 average). */
    static final class Stats {
        int low, high, hottest, hotX, hotY; double centre, coolest;
        /** Whether the °C figures are trustworthy (temperatures sent, and in a believable range). */
        boolean temperatures;
    }
    Stats stats() {
        Stats s = new Stats();
        int[] histogram = new int[65536]; int min = Integer.MAX_VALUE, max = -1;
        for (int i = 0; i < values.length; i++) {
            int v = values[i] & 0xffff; histogram[v]++;
            if (v < min) min = v;
            if (v > max) { max = v; s.hotX = i % width; s.hotY = i / width; }
        }
        int cut = values.length / 200, seen = 0; s.low = min; s.high = max;
        for (int v = 0; v < histogram.length; v++) { seen += histogram[v]; if (seen > cut) { s.low = v; break; } }
        seen = 0;
        for (int v = histogram.length - 1; v >= 0; v--) { seen += histogram[v]; if (seen > cut) { s.high = v; break; } }
        if (s.high <= s.low) s.high = s.low + 1;
        s.hottest = max;
        double sum = 0; int n = 0;
        for (int y = height / 2 - 1; y <= height / 2 + 1; y++) for (int x = width / 2 - 1; x <= width / 2 + 1; x++)
            if (x >= 0 && y >= 0 && x < width && y < height) { sum += values[y * width + x]; n++; }
        s.centre = n == 0 ? Double.NaN : sum / n; s.coolest = min;
        double lo = celsius(min), hi = celsius(max);
        s.temperatures = !Double.isNaN(scale) && lo >= LOWEST_C && hi <= HIGHEST_C;
        return s;
    }

    /** Iron palette stops: position, colour. */
    private static final double[] STOPS = {0, 0.18, 0.36, 0.52, 0.66, 0.8, 0.92, 1};
    private static final int[] COLOURS = {0x000000, 0x1e0a6e, 0x7a0b8c, 0xc42a5a, 0xe8641e, 0xf8b020, 0xfce86a, 0xffffff};
    /** The palette colour (opaque ARGB) at 0–1. */
    static int colour(double t) {
        t = Math.max(0, Math.min(1, t));
        int k = 1; while (k < STOPS.length - 1 && t > STOPS[k]) k++;
        double f = (t - STOPS[k - 1]) / (STOPS[k] - STOPS[k - 1]);
        int a = COLOURS[k - 1], b = COLOURS[k];
        int r = (int) Math.round(((a >> 16) & 255) + f * (((b >> 16) & 255) - ((a >> 16) & 255)));
        int g = (int) Math.round(((a >> 8) & 255) + f * (((b >> 8) & 255) - ((a >> 8) & 255)));
        int bl = (int) Math.round((a & 255) + f * ((b & 255) - (a & 255)));
        return 0xff000000 | r << 16 | g << 8 | bl;
    }
    /** The picture in palette colours (ARGB, row by row), between the stats' low and high. */
    int[] colours(Stats s) {
        int[] lut = new int[256]; for (int i = 0; i < 256; i++) lut[i] = colour(i / 255.0);
        int[] out = new int[values.length]; double span = s.high - s.low;
        for (int i = 0; i < values.length; i++) {
            int k = (int) Math.round(((values[i] & 0xffff) - s.low) / span * 255);
            out[i] = lut[Math.max(0, Math.min(255, k))];
        }
        return out;
    }

    /** "236.4 °C" (or "" without temperatures). */
    String words(Stats s, double value) {
        if (!s.temperatures) return "";
        return String.format(Locale.getDefault(), "%.1f °C", celsius(value));
    }
}
