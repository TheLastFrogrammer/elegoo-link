package io.github.thelastfrogrammer.elink;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.*;

/** Bounded, offline text inspection. Does not interpret motion or establish print compatibility. */
public final class GcodeInspector {
    public static final long MAX_BYTES = 512L * 1024 * 1024;
    private static final int MAX_LINE = 16384;
    private static final Pattern TOOL = Pattern.compile("^(?:N[0-9]+\\s*)?T([0-9]+)(?:\\s|\\*|$).*", Pattern.CASE_INSENSITIVE);
    private static final Map<String, String> FIELDS = new LinkedHashMap<>();
    static {
        FIELDS.put("estimated printing time (normal mode)", "Slicer estimated time");
        FIELDS.put("filament used [mm]", "Filament lengths [mm]");
        FIELDS.put("filament used [cm3]", "Filament volumes [cm³]");
        FIELDS.put("filament used [g]", "Filament masses [g]");
        FIELDS.put("total filament used [g]", "Total filament mass [g]");
        FIELDS.put("total layer number", "Reported layer count");
        FIELDS.put("layer_count", "Reported layer count");
        FIELDS.put("layer height", "Layer height [mm]");
        FIELDS.put("layer_height", "Layer height [mm]");
        FIELDS.put("filament_type", "Configured filament types");
        FIELDS.put("filament_colour", "Configured filament colors");
        FIELDS.put("printer_model", "Configured printer model");
        FIELDS.put("nozzle_diameter", "Configured nozzle diameter [mm]");
        FIELDS.put("curr_bed_type", "Sliced for build plate");
    }
    /**
     * The build plate side a file was sliced for, from its "curr_bed_type" (start G-code comment or settings block): "A" for the
     * textured PEI side, "B" for a smooth one, "" when the file does not say. Reads only the first 512 KB and the last 512 KB.
     */
    static String plateSide(java.io.File gcode) {
        if (gcode == null || !gcode.isFile()) return "";
        try (java.io.RandomAccessFile in = new java.io.RandomAccessFile(gcode, "r")) {
            long length = in.length();
            for (long start : new long[] {0, Math.max(0, length - 512 * 1024)}) {
                byte[] chunk = new byte[(int) Math.min(512 * 1024, length - start)];
                in.seek(start); in.readFully(chunk);
                String side = plateSide(new String(chunk, java.nio.charset.StandardCharsets.ISO_8859_1));
                if (!side.isEmpty()) return side;
            }
        } catch (java.io.IOException unreadable) { }
        return "";
    }
    static String plateSide(String text) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(";\\s*curr_bed_type\\s*[=:]\\s*([^\\r\\n]+)").matcher(text);
        if (!m.find()) return "";
        String type = m.group(1).trim().toLowerCase(Locale.ROOT);
        if (type.isEmpty() || type.startsWith("{")) return "";
        return type.contains("textured pei") ? "A" : "B";
    }
    public static final class Report {
        public final long bytes, lines;
        public final String sha256;
        public final boolean binary, longLines;
        public final SortedSet<Integer> tools;
        public final Map<String, String> metadata;
        public final EmbeddedThumbnail thumbnail;
        public final String thumbnailNote;
        public final SlicedMaterials materials;
        /** The file loads filament itself: an M6211 (Elegoo's CC2 load-from-tray step in its start G-code) was seen. */
        public final boolean loadsFilament;
        private Report(long bytes, long lines, String hash, boolean binary, boolean longLines, SortedSet<Integer> tools, Map<String, String> metadata, EmbeddedThumbnail thumbnail, String thumbnailNote, SlicedMaterials materials, boolean loadsFilament) {
            this.loadsFilament = loadsFilament;
            this.bytes = bytes; this.lines = lines; sha256 = hash; this.binary = binary; this.longLines = longLines;
            this.tools = Collections.unmodifiableSortedSet(new TreeSet<>(tools));
            this.metadata = Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
            this.thumbnail = thumbnail; this.thumbnailNote = thumbnailNote; this.materials = materials;
        }
        /**
         * A plain warning when the file looks made for another printer, or never loads filament: a CC2 start (Elegoo's CC2
         * profile) loads the tray's filament into the toolhead with M6211 and selects it with T0; a file without either can
         * print its purge line from what is left in the nozzle and then stop extruding. "" when nothing looks wrong.
         */
        /** True when the file never loads filament from the trays (no M6211, no tool select): GcodeRepair can add the load. */
        public boolean needsFilamentLoad() { return !binary && !loadsFilament && tools.isEmpty(); }
        public String compatibilityWarning() {
            if (binary) return "";
            String model = metadata.getOrDefault("Configured printer model", "");
            String lower = model.toLowerCase(Locale.ROOT);
            boolean otherPrinter = !model.isEmpty() && !(lower.contains("carbon 2") || lower.contains("cc2"));
            boolean noLoad = !loadsFilament && tools.isEmpty();
            if (otherPrinter) return "Sliced for " + model + ", not the Centauri Carbon 2." + (noLoad ? " It never loads filament from the trays, so the print may extrude its purge line and then stop." : "")
                + " Re-slice it with a Centauri Carbon 2 printer profile.";
            if (noLoad) return "This file never loads filament from the trays (no tool selection), so the print may extrude its purge line and then stop. Re-slice it with a Centauri Carbon 2 printer profile.";
            return "";
        }
        public String text() {
            StringBuilder s = new StringBuilder("Offline G-code inspection\n" + FeatureData.size(bytes) + " · " + lines + " line(s)\nSHA-256: " + sha256);
            String warning = compatibilityWarning(); if (!warning.isEmpty()) s.append("\nWarning: ").append(warning);
            if (binary) s.append("\nBinary/non-text bytes detected. Text metadata and tool selections are unavailable; use plain-text G-code.");
            else {
                s.append("\n").append(thumbnailNote);
                for (Map.Entry<String, String> field : metadata.entrySet()) s.append('\n').append(field.getKey()).append(": ").append(field.getValue());
                s.append("\nExplicit T selections seen (up to 32 distinct): ").append(tools.isEmpty() ? "none" : tools.toString());
                if (metadata.isEmpty()) s.append("\nNo supported slicer metadata comments found.");
                if (longLines) s.append("\nOverlong lines were skipped during text analysis.");
                if (tools.stream().anyMatch(t -> t > 7)) s.append("\nSelections outside T0–T7 were observed; these may be firmware-specific or sentinel commands.");
            }
            if (!binary) s.append("\n\n").append(materials.text());
            return s.append("\n\nSlicer comments are estimates/configuration, not measured usage. T selections are observations, not an exhaustive tool count: macros or firmware-specific commands may select tools. No tray mappings or print settings are changed. This report does not simulate motion or certify printer/material compatibility.").toString();
        }
    }
    public static Report inspect(File file) throws Exception {
        if (file.length() == 0 || file.length() > MAX_BYTES) throw new IOException("Choose a nonempty file up to 512 MiB.");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        Map<String, String> metadata = new LinkedHashMap<>(), rawMaterials = new LinkedHashMap<>(); EmbeddedThumbnail.Parser thumbnails = new EmbeddedThumbnail.Parser(); SortedSet<Integer> tools = new TreeSet<>();
        byte[] buffer = new byte[65536]; ByteArrayOutputStream line = new ByteArrayOutputStream();
        long bytes = 0, lines = 0; boolean binary = false, longLines = false, skip = false, pending = false; boolean[] loads = {false};
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Inspection cancelled");
                bytes += count; if (bytes > MAX_BYTES) throw new IOException("File exceeds inspection limit"); digest.update(buffer, 0, count);
                for (int i = 0; i < count; i++) {
                    int c = buffer[i] & 255;
                    if (c < 32 && c != 9 && c != 10 && c != 13) binary = true;
                    if (c == 10) {
                        lines++; if (!skip && !binary) accept(new String(line.toByteArray(), StandardCharsets.UTF_8), metadata, tools, rawMaterials, thumbnails, loads);
                        line.reset(); skip = false; pending = false;
                    } else {
                        pending = true;
                        if (!skip) { if (line.size() >= MAX_LINE) { skip = true; longLines = true; thumbnails.overlongLine(); line.reset(); } else line.write(c); }
                    }
                }
            }
        }
        if (pending) { lines++; if (!skip && !binary) accept(new String(line.toByteArray(), StandardCharsets.UTF_8), metadata, tools, rawMaterials, thumbnails, loads); }
        thumbnails.finish();
        if (binary) { metadata.clear(); tools.clear(); rawMaterials.clear(); }
        StringBuilder hash = new StringBuilder(); for (byte b : digest.digest()) hash.append(String.format(Locale.ROOT, "%02x", b & 255));
        return new Report(bytes, lines, hash.toString(), binary, longLines, tools, metadata, binary ? null : thumbnails.best(), binary ? "Text analysis unavailable." : thumbnails.note(), SlicedMaterials.from(rawMaterials, longLines || binary), !binary && loads[0]);
    }
    private static void accept(String supplied, Map<String, String> metadata, SortedSet<Integer> tools, Map<String,String> rawMaterials, EmbeddedThumbnail.Parser thumbnails, boolean[] loads) {
        String line = supplied.trim(); if (line.startsWith("\uFEFF")) line = line.substring(1).trim();
        if (thumbnails.accept(line)) return;
        if (line.startsWith(";")) {
            String comment = line.substring(1).trim();
            if (comment.toLowerCase(Locale.ROOT).startsWith("generated by ")) metadata.put("Generator", clean(comment.substring(13)));
            int separator = comment.indexOf('='); if (separator < 0) separator = comment.indexOf(':');
            if (separator > 0) { String key = comment.substring(0, separator).trim().toLowerCase(Locale.ROOT), value = comment.substring(separator + 1).trim(); String label = FIELDS.get(key); if (label != null) metadata.put(label, clean(value));
                if (key.equals(SlicedMaterials.TYPES) || key.equals(SlicedMaterials.COLORS) || key.equals(SlicedMaterials.LENGTHS) || key.equals(SlicedMaterials.MASSES)) rawMaterials.put(key, value);
            }
            return;
        }
        int semicolon = line.indexOf(';'); if (semicolon >= 0) line = line.substring(0, semicolon).trim();
        if (line.regionMatches(true, 0, "M6211", 0, 5) && (line.length() == 5 || line.charAt(5) == ' ')) loads[0] = true;
        // Ignore parenthetical text: a T command inside a comment is not an observed selection.
        if (line.indexOf('(') >= 0) line = line.replaceAll("\\([^)]*\\)", "").trim();
        if (line.isEmpty() || "TtNn".indexOf(line.charAt(0)) < 0) return;
        Matcher match = TOOL.matcher(line);
        if (match.matches()) try { int value = Integer.parseInt(match.group(1)); if (value <= 65535 && tools.size() < 32) tools.add(value); } catch (NumberFormatException ignored) { }
    }
    private static String clean(String value) { String s = value.trim().replaceAll("[\\p{Cntrl}]", " "); return s.length() > 240 ? s.substring(0, 240) + "…" : s; }
}
