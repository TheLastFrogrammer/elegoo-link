package io.github.thelastfrogrammer.elink;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * A small field-test log for the parts that still need checking on real hardware: on-phone slicing (time, memory),
 * following a print in the toolpath viewer (what the printer reports against the file's layers) and the CANVAS tray
 * plan at print start. Keeps the latest entries per area in memory and in one file, so a report survives the app
 * being closed. Holds no access codes, PINs, addresses or serial numbers. Independent of Android.
 */
final class Diagnostics {
    static final String SLICER = "Slicer", FOLLOW = "Live toolpath", TRAYS = "CANVAS tray plan", FILES = "Printer files", PROBE = "Printer probe", SITES = "Model sites", HYBRID = "Local and cloud", CAMERA = "Camera",
        CONNECTION = "Connection", COMMANDS = "Printer commands", MESSAGES = "Messages shown",
        NOTIFICATIONS = "Notifications", STATUS = "Printer status";
    static final int KEEP_PER_AREA = 100;
    private static final String SEPARATOR = "\u001f";
    private static final Map<String, Deque<String>> entries = new LinkedHashMap<>();
    private static File file;

    private Diagnostics() { }

    /** Loads the saved log; call once with the app's files directory. */
    static synchronized void init(File directory) {
        File target = new File(directory, "diagnostics.log");
        if (target.equals(file)) return;
        file = target; entries.clear();
        if (!file.isFile()) return;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int split = line.indexOf(SEPARATOR);
                if (split > 0) add(line.substring(0, split), line.substring(split + 1));
            }
        } catch (IOException ignored) { }
    }

    /** Adds a timestamped entry (one line) to an area. */
    static synchronized void note(String area, String text) {
        String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date());
        add(area, stamp + "  " + text.replace('\n', ' ').replace(SEPARATOR, " "));
        save();
    }

    static synchronized void clear() { entries.clear(); save(); }

    /** The areas with entries, in the order they first appeared. */
    static synchronized List<String> areas() { return new ArrayList<>(entries.keySet()); }
    /** Every entry of an area, oldest first. */
    static synchronized List<String> entries(String area) { Deque<String> list = entries.get(area); return list == null ? Collections.emptyList() : new ArrayList<>(list); }

    /** The newest `count` entries of an area, oldest first. */
    static synchronized List<String> recent(String area, int count) {
        Deque<String> list = entries.get(area);
        if (list == null) return Collections.emptyList();
        List<String> all = new ArrayList<>(list);
        return all.subList(Math.max(0, all.size() - count), all.size());
    }

    /** Text from a network error or a page, safe for the log: addresses and serial-like ids replaced. */
    static String scrub(String text) {
        if (text == null) return "";
        // An address part is 0-255 without a leading zero, so versions such as 01.03.02.15 stay readable.
        String part = "(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)";
        return text.replaceAll("(?<![\\d.])" + part + "(?:\\." + part + "){3}(?::\\d+)?(?![\\d.])", "<address>")
            .replaceAll("\\b(?=[A-Za-z0-9]*\\d)(?=[A-Za-z0-9]*[A-Za-z])[A-Za-z0-9]{12,}\\b", "<id>");
    }

    static synchronized boolean isEmpty() { return entries.isEmpty(); }

    /** The whole log under a header (app, device), oldest entries first in each area. */
    static synchronized String report(String header) {
        StringBuilder text = new StringBuilder("Link Workshop diagnostics\n").append(header.trim()).append("\n");
        if (entries.isEmpty()) text.append("\nNothing recorded yet. Slice a model, follow a print in Live toolpath or open Print setup, then share again.\n");
        for (Map.Entry<String, Deque<String>> area : entries.entrySet()) {
            text.append("\n## ").append(area.getKey()).append("\n");
            for (String line : area.getValue()) text.append(line).append("\n");
        }
        return text.toString();
    }

    /** Peak and current resident memory of this process from /proc (Linux, Android), or "" where unavailable. */
    static String memory() {
        File status = new File("/proc/self/status");
        if (!status.isFile()) return "";
        String peak = null, now = null;
        try (BufferedReader reader = new BufferedReader(new FileReader(status))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("VmHWM:")) peak = line.substring(6).trim();
                else if (line.startsWith("VmRSS:")) now = line.substring(6).trim();
            }
        } catch (IOException error) { return ""; }
        return peak == null ? "" : "peak RSS " + peak + (now == null ? "" : ", now " + now);
    }

    private static void add(String area, String line) {
        Deque<String> list = entries.computeIfAbsent(area, key -> new ArrayDeque<>());
        list.addLast(line);
        while (list.size() > KEEP_PER_AREA) list.removeFirst();
    }

    private static void save() {
        if (file == null) return;
        File temporary = new File(file.getPath() + ".part");
        try (Writer out = new OutputStreamWriter(new FileOutputStream(temporary), StandardCharsets.UTF_8)) {
            for (Map.Entry<String, Deque<String>> area : entries.entrySet())
                for (String line : area.getValue()) out.write(area.getKey() + SEPARATOR + line + "\n");
        } catch (IOException error) { temporary.delete(); return; }
        if (!temporary.renameTo(file)) temporary.delete();
    }
}
