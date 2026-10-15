package io.github.thelastfrogrammer.elink;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adds the Centauri Carbon 2's filament load to a G-code file that never loads filament (sliced for another printer, or with
 * a start G-code that skips it): the same lines the CC2 printer profile's start G-code uses, M6211 (load from a CANVAS tray)
 * and the tool select, at the nozzle temperature the file heats to. They go in once, just before the travel to the first
 * extrusion (usually the purge line), so the printer loads, then moves and purges as the file intended. The original file is
 * not changed. Independent of Android.
 */
final class GcodeRepair {
    static final class Result {
        /** 1-based line the added block was put before, the nozzle temperature used and the tray (0-3). */
        final int line, temperature, tray;
        Result(int line, int temperature, int tray) { this.line = line; this.temperature = temperature; this.tray = tray; }
    }

    static final String BEGIN = "; ===== Added by Link Workshop: load filament from the CANVAS tray, as the Centauri Carbon 2 profile's start G-code does =====";
    private static final Pattern TEMPERATURE = Pattern.compile("^M10[49]\\b.*?\\bS(\\d+(?:\\.\\d+)?)");
    private static final Pattern MOVE = Pattern.compile("^G[01]\\b");
    private static final Pattern EXTRUDE = Pattern.compile("\\bE(\\d*\\.?\\d+)");
    private static final Pattern XY = Pattern.compile("\\b[XY]-?\\d");

    /** The lines added for `tray` (0-3) at `temperature` °C. */
    static String block(int tray, int temperature) {
        return BEGIN + "\n"
            + "M109 S" + temperature + "\n"
            + String.format(Locale.ROOT, "M6211 A1 L200 T%d Q%d R%d S%d", tray, temperature, temperature, temperature) + "\n"
            + "T" + tray + "\n"
            + "; ===== end of added lines =====\n";
    }

    /** Where the block goes and at what temperature, from one pass over the file; null when it already loads or never extrudes. */
    static int[] plan(BufferedReader reader) throws IOException {
        String line; int number = 0, temperature = 0, lastTravel = -1;
        while ((line = reader.readLine()) != null) {
            number++;
            String code = line.contains(";") ? line.substring(0, line.indexOf(';')).trim() : line.trim();
            if (code.isEmpty()) continue;
            String upper = code.toUpperCase(Locale.ROOT);
            if (upper.startsWith("M6211") || upper.matches("^T\\d+\\b.*")) return null;   // it already loads or selects a tool
            Matcher heat = TEMPERATURE.matcher(upper);
            if (heat.find() && temperature == 0) { int value = (int) Math.round(Double.parseDouble(heat.group(1))); if (value >= 150) temperature = value; }
            if (!MOVE.matcher(upper).find()) continue;
            Matcher e = EXTRUDE.matcher(upper);
            boolean extrudes = e.find() && Double.parseDouble(e.group(1)) > 0;
            if (extrudes) return new int[] {lastTravel > 0 ? lastTravel : number, temperature};
            if (XY.matcher(upper).find()) lastTravel = number;
        }
        return null;
    }

    /**
     * Writes `in` to `out` with the load added for `tray` (0-3); the temperature is the file's own first nozzle temperature,
     * else `fallback`. Null (and nothing written) when the file already loads filament or never extrudes.
     */
    static Result addFilamentLoad(File in, File out, int tray, int fallback) throws IOException {
        if (tray < 0 || tray > 3) throw new IllegalArgumentException("tray");
        int[] where;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(in), StandardCharsets.UTF_8))) { where = plan(reader); }
        if (where == null) return null;
        int temperature = where[1] > 0 ? where[1] : fallback;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(in), StandardCharsets.UTF_8));
             Writer writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(out), StandardCharsets.UTF_8))) {
            String line; int number = 0;
            while ((line = reader.readLine()) != null) {
                number++;
                if (number == where[0]) writer.write(block(tray, temperature));
                writer.write(line); writer.write('\n');
            }
        }
        return new Result(where[0], temperature, tray);
    }
}
