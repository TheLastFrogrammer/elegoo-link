package io.github.thelastfrogrammer.elink;

/**
 * Making a dim camera picture readable on the phone: how bright a frame is, and a gain that lifts dark frames (a bright frame
 * stays as it is). Only the picture shown on this phone changes; nothing is sent to the printer. Independent of Android.
 */
final class CameraTone {
    /** Below this mean brightness (0–1) the chamber is dark enough to suggest the printer's light. */
    static final double DARK = 0.12;
    private static final double TARGET = 0.42, MAX_GAIN = 3;

    /** Mean brightness 0–1 of ARGB pixels (Rec. 709 weights). */
    static double luminance(int[] argb) {
        if (argb == null || argb.length == 0) return 0;
        double sum = 0;
        for (int p : argb) sum += 0.2126 * ((p >> 16) & 255) + 0.7152 * ((p >> 8) & 255) + 0.0722 * (p & 255);
        return sum / argb.length / 255.0;
    }
    /** How much to lift a frame of this brightness: 1 for a well-lit frame, up to 3 for a dark one. */
    static float gain(double luminance) {
        if (!(luminance > 0.01)) return (float) MAX_GAIN;
        return (float) Math.max(1, Math.min(MAX_GAIN, TARGET / luminance));
    }
    /** A 4×5 colour matrix (android.graphics.ColorMatrix order) that scales RGB by `gain` and lifts the shadows a little. */
    static float[] matrix(float gain) {
        float lift = 6 * (gain - 1);
        return new float[] {gain, 0, 0, 0, lift, 0, gain, 0, 0, lift, 0, 0, gain, 0, lift, 0, 0, 0, 1, 0};
    }
}
