package io.github.thelastfrogrammer.elink;

import java.util.*;

/**
 * History's statistics: prints and print time from the app's recordings, filament from what finished prints took off the
 * spools, and its cost from a price per kilogram (0 = not set). Only what this phone saw; independent of Android.
 */
final class PrintStats {
    static final long DAY_MS = 24 * 3600_000L;
    int prints, complete, stopped, other, prints30;
    double hours, hours30, grams, grams30;
    final Map<String, Double> gramsByType = new TreeMap<>();

    static PrintStats of(List<PrintRecorder.Recording> recordings, List<SpoolStore.Usage> usage, long now) {
        PrintStats s = new PrintStats();
        for (PrintRecorder.Recording r : recordings) {
            if ("printing".equals(r.outcome)) continue;   // still running (or never closed): not counted yet
            s.prints++;
            if ("complete".equals(r.outcome)) s.complete++; else if ("stopped".equals(r.outcome)) s.stopped++; else s.other++;
            long ms = r.elapsed > 0 ? r.elapsed : Math.max(0, r.end - r.start);
            s.hours += ms / 3600_000.0;
            if (now - r.start < 30 * DAY_MS) { s.prints30++; s.hours30 += ms / 3600_000.0; }
        }
        for (SpoolStore.Usage u : usage) {
            s.grams += u.grams; if (now - u.at < 30 * DAY_MS) s.grams30 += u.grams;
            String type = u.type == null || u.type.trim().isEmpty() ? "Other" : u.type.trim().toUpperCase(Locale.ROOT);
            s.gramsByType.merge(type, u.grams, Double::sum);
        }
        return s;
    }

    /** Share of finished prints that completed (prints the app saw end), or -1 with none. */
    int successPercent() { int ended = complete + stopped + other; return ended == 0 ? -1 : (int) Math.round(100.0 * complete / ended); }

    /** "about 1.25" in the phone's currency format, or "" without a price. */
    static String cost(double grams, double pricePerKg) {
        if (!(pricePerKg > 0) || !(grams > 0)) return "";
        java.text.NumberFormat money = java.text.NumberFormat.getCurrencyInstance(Locale.getDefault());
        return money.format(grams / 1000.0 * pricePerKg);
    }

    /** The lines History shows. */
    List<String> lines(double pricePerKg) {
        List<String> out = new ArrayList<>();
        if (prints == 0) { out.add("No finished prints recorded yet. Prints the app watches are counted here."); return out; }
        int success = successPercent();
        out.add(prints + (prints == 1 ? " print" : " prints") + " · " + hours(hours) + " printing" + (success >= 0 ? " · " + success + "% completed" : ""));
        out.add("Last 30 days: " + prints30 + (prints30 == 1 ? " print" : " prints") + " · " + hours(hours30));
        if (stopped + other > 0) out.add(complete + " completed, " + stopped + " stopped" + (other > 0 ? ", " + other + " ended without a finish report" : ""));
        if (grams > 0) {
            StringBuilder by = new StringBuilder();
            for (Map.Entry<String, Double> e : gramsByType.entrySet()) by.append(by.length() > 0 ? ", " : "").append(e.getKey()).append(" ").append(Math.round(e.getValue())).append(" g");
            String total = cost(grams, pricePerKg), month = cost(grams30, pricePerKg);
            out.add("Filament from tracked spools: " + Math.round(grams) + " g (" + by + ")" + (total.isEmpty() ? "" : " · about " + total)
                + (grams30 > 0 ? "; last 30 days " + Math.round(grams30) + " g" + (month.isEmpty() ? "" : ", about " + month) : ""));
        } else out.add("Filament use appears once spools are set on the Printer tab and a print started from this app ends.");
        return out;
    }
    static String hours(double h) { return h < 1 ? Math.round(h * 60) + " min" : String.format(Locale.getDefault(), "%.1f h", h); }
}
