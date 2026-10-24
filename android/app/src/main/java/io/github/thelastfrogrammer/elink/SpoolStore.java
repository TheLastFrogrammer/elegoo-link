package io.github.thelastfrogrammer.elink;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/**
 * Spools and how much filament is left on each, kept on this phone. A spool sits in a CANVAS tray (or on the shelf); when a
 * tray reports a different material or colour, the spool that was there goes to the shelf. A print started from this app with
 * known grams per filament is remembered, and when it ends its use is taken off the spools it printed from (in part for a
 * stopped print). Only estimates: the printer does not weigh spools. Independent of Android; one instance per file.
 */
final class SpoolStore {
    static final double DEFAULT_SIZE = 1000;
    /** A started print is forgotten if it is not seen printing within this time. */
    static final long PENDING_MS = 6 * 3600_000L;

    static final class Spool {
        String id = "", type = "", name = "", colour = null, slot = "";
        double size = DEFAULT_SIZE, left = DEFAULT_SIZE;
        long updated;
        /** When it was last dried, and when it was opened (a new full spool, taken as sealed until then); 0 = not known. */
        long dried, opened;
        /** "PLA Matte · red". */
        String material() {
            String material = name.isEmpty() || name.equalsIgnoreCase(type) ? type : name.toUpperCase(Locale.ROOT).contains(type.toUpperCase(Locale.ROOT)) ? name : type + " " + name;
            String word = WorkshopUi.colourName(colour);
            return word == null ? material : material + " · " + word;
        }
        /** "dried 3 days ago", "opened 12 days ago, not marked dried", or "drying not noted". */
        String dryWords(long now) { return dryWords(dried, opened, now, "ago"); }
        /** The same counted up to a moment: `suffix` "ago" for now, "before this print" for a print's start. */
        static String dryWords(long dried, long opened, long at, String suffix) {
            if (dried > 0 && dried >= opened) return "dried " + since(dried, at, suffix);
            if (opened > 0) return "opened " + since(opened, at, suffix) + ", not marked dried";
            return "drying not noted";
        }
        /** Whole days from `from` to `at`, or -1 when neither date is known: the time since it was dried, or opened. */
        static long daysDry(long dried, long opened, long at) { long from = Math.max(dried, opened); return from <= 0 ? -1 : Math.max(0, (at - from) / 86_400_000L); }
        private static String since(long from, long at, String suffix) {
            long days = Math.max(0, (at - from) / 86_400_000L);
            return days == 0 ? ("ago".equals(suffix) ? "today" : "the same day") : days + (days == 1 ? " day " : " days ") + suffix;
        }
        boolean matches(TrayPlan.Tray tray) { return type.equalsIgnoreCase(tray.type) && Objects.equals(colour, tray.colour); }
        JSONObject json() throws Exception {
            return new JSONObject().put("id", id).put("type", type).put("name", name).put("colour", colour == null ? "" : colour).put("slot", slot)
                .put("size", size).put("left", left).put("updated", updated).put("dried", dried).put("opened", opened);
        }
        static Spool from(JSONObject o) {
            Spool s = new Spool(); s.id = o.optString("id"); s.type = o.optString("type"); s.name = o.optString("name"); s.colour = TrayPlan.colour(o.optString("colour"));
            s.slot = o.optString("slot"); s.size = o.optDouble("size", DEFAULT_SIZE); s.left = o.optDouble("left", s.size); s.updated = o.optLong("updated");
            s.dried = o.optLong("dried"); s.opened = o.optLong("opened"); return s;
        }
    }
    /** The slot key for a tray: "canvas:tray". */
    static String slot(int canvasId, int trayId) { return canvasId + ":" + trayId; }
    static String slot(TrayPlan.Tray tray) { return slot(tray.canvasId, tray.trayId); }

    private static final Map<String, SpoolStore> OPEN = new HashMap<>();
    /** The one store for this file, shared by the screen and the background service. */
    static synchronized SpoolStore at(File file) { return OPEN.computeIfAbsent(file.getAbsolutePath(), path -> new SpoolStore(file)); }

    private final File file;
    private final List<Spool> spools = new ArrayList<>();
    /** The print started from this app whose use is still to be taken off: file, uses (slot, grams), last progress, seen printing. */
    private JSONObject pending;
    /** Filament taken off by finished prints, newest last (at most USAGE_KEEP), for History's statistics. */
    private final List<Usage> usage = new ArrayList<>();
    static final int USAGE_KEEP = 2000;
    static final class Usage {
        final long at; final String file, type; final double grams;
        Usage(long at, String file, String type, double grams) { this.at = at; this.file = file; this.type = type; this.grams = grams; }
    }
    synchronized List<Usage> usage() { return new ArrayList<>(usage); }
    SpoolStore(File file) { this.file = file; load(); }

    synchronized List<Spool> all() { List<Spool> copy = new ArrayList<>(); for (Spool s : spools) copy.add(copy(s)); return copy; }
    synchronized Spool inSlot(String slot) { for (Spool s : spools) if (slot.equals(s.slot)) return copy(s); return null; }
    synchronized List<Spool> shelf() { List<Spool> out = new ArrayList<>(); for (Spool s : spools) if (s.slot.isEmpty()) out.add(copy(s)); return out; }

    /**
     * Follows a fresh tray report: a spool whose tray now reports another material or colour, or an empty tray, goes to the shelf.
     * A tray the report leaves out (a unit not answering) keeps its spool. Returns true when something changed.
     */
    synchronized boolean sync(JSONObject canvas, long now) {
        Map<String, String[]> reported = new HashMap<>();
        JSONArray units = canvas == null ? null : canvas.optJSONArray("canvas_list");
        for (int u = 0; units != null && u < units.length(); u++) {
            JSONObject unit = units.optJSONObject(u); JSONArray list = unit == null ? null : unit.optJSONArray("tray_list");
            for (int i = 0; list != null && i < list.length(); i++) {
                JSONObject tray = list.optJSONObject(i); if (tray == null || tray.optInt("tray_id", -1) < 0) continue;
                reported.put(slot(unit.optInt("canvas_id"), tray.optInt("tray_id")), new String[] {StatusPresentation.clean(tray.optString("filament_type")).trim(), TrayPlan.colour(tray.optString("filament_color"))});
            }
        }
        boolean changed = false;
        for (Spool s : spools) {
            if (s.slot.isEmpty() || !reported.containsKey(s.slot)) continue;
            String[] there = reported.get(s.slot);
            if (there[0].isEmpty() || !s.type.equalsIgnoreCase(there[0]) || !Objects.equals(s.colour, there[1])) { swapped(s.slot, ""); s.slot = ""; s.updated = now; changed = true; }
        }
        if (changed) save();
        return changed;
    }

    /** Sets the spool in a tray: a new spool of `size` grams with `left` on it (the old one, if different, goes to the shelf). */
    synchronized Spool put(TrayPlan.Tray tray, double size, double left, long now) {
        String key = slot(tray);
        for (Spool s : spools) if (key.equals(s.slot)) s.slot = "";
        Spool s = new Spool(); s.id = UUID.randomUUID().toString(); s.type = tray.type; s.name = tray.name; s.colour = tray.colour; s.slot = key;
        s.size = clamp(size, 1, 10_000); s.left = clamp(left, 0, s.size); s.updated = now; if (s.left >= s.size) s.opened = now; spools.add(s); swapped(key, s.id); save(); return copy(s);
    }
    /** Puts a shelf spool back into a tray. */
    synchronized void fromShelf(String id, TrayPlan.Tray tray, long now) {
        String key = slot(tray);
        for (Spool s : spools) if (key.equals(s.slot)) s.slot = "";
        for (Spool s : spools) if (s.id.equals(id)) { s.slot = key; s.updated = now; swapped(key, id); }
        save();
    }
    synchronized void setLeft(String id, double left, long now) { for (Spool s : spools) if (s.id.equals(id)) { s.left = clamp(left, 0, s.size); s.updated = now; } save(); }
    /** Notes that a spool was dried at `when`. */
    synchronized void setDried(String id, long when, long now) { for (Spool s : spools) if (s.id.equals(id)) { s.dried = when; s.updated = now; } save(); }
    synchronized void remove(String id) { spools.removeIf(s -> s.id.equals(id)); save(); }

    /** Shelf spools of the same material and colour as a tray, most filament first: the likely ones to put back. */
    synchronized List<Spool> candidates(TrayPlan.Tray tray) {
        List<Spool> out = new ArrayList<>(); for (Spool s : spools) if (s.slot.isEmpty() && s.matches(tray)) out.add(copy(s));
        out.sort((a, b) -> Double.compare(b.left, a.left)); return out;
    }

    /**
     * "Only about 40 g left on this spool; this filament needs about 55 g.", or null when the spool in `slot` has enough (or is
     * not known). A 5 % margin covers purge and estimate error.
     */
    synchronized String shortfall(String slot, double grams) {
        Spool s = null; for (Spool one : spools) if (slot.equals(one.slot)) s = one;
        if (s == null || grams <= 0) return null;
        if (s.left >= grams * 1.05) return null;
        return String.format(Locale.getDefault(), "Only about %.0f g left on this spool; this filament needs about %.0f g.", s.left, grams);
    }

    /** Remembers a print just started from this app: grams per tray. Uses without a known tray or grams are left out. */
    synchronized void started(String filename, Map<String, Double> gramsBySlot, long now) {
        try {
            JSONArray uses = new JSONArray();
            for (Map.Entry<String, Double> use : gramsBySlot.entrySet()) if (use.getValue() != null && use.getValue() > 0) {
                Spool there = null; for (Spool sp : spools) if (use.getKey().equals(sp.slot)) there = sp;
                // Which spool fed the tray from which progress: a spool changed mid-print takes only its share.
                JSONArray spans = new JSONArray().put(new JSONObject().put("spool", there == null ? "" : there.id).put("from", 0));
                uses.put(new JSONObject().put("slot", use.getKey()).put("grams", use.getValue()).put("spans", spans));
            }
            pending = uses.length() == 0 ? null : new JSONObject().put("file", filename).put("uses", uses).put("at", now).put("progress", 0).put("seen", false);
            save();
        } catch (Exception ignored) { }
    }

    /**
     * Follows the printer's status for the remembered print. When it ends, its use comes off the spools: all of it when complete,
     * the printed share when stopped. Returns a line saying what was taken off, or null.
     */
    synchronized String observe(JSONObject status, long now) {
        if (pending == null || status == null) return null;
        JSONObject machine = status.optJSONObject("machine_status"), print = status.optJSONObject("print_status");
        if (machine == null) return null;
        int state = machine.optInt("status", -1), sub = machine.optInt("sub_status", -1);
        String file = print == null ? "" : print.optString("filename", "");
        boolean ours = file.equals(pending.optString("file")) || file.endsWith("/" + pending.optString("file"));
        boolean seen = pending.optBoolean("seen");
        if (state == 2 && ours) {
            int progress = machine.optInt("progress", pending.optInt("progress"));
            try { pending.put("seen", true).put("progress", Math.max(pending.optInt("progress"), progress)); } catch (Exception ignored) { }
            if (sub == 2077) return settle(1.0, now);
            if (sub == 2504) return settle(pending.optInt("progress") / 100.0, now);
            return null;
        }
        if (seen && (state == 1 || state == 2 && !file.isEmpty() && !ours)) {
            int progress = pending.optInt("progress");
            return settle(progress >= 99 ? 1.0 : progress / 100.0, now);
        }
        if (!seen && (now - pending.optLong("at") > PENDING_MS || state == 2 && !file.isEmpty() && !ours)) { pending = null; save(); }
        return null;
    }
    /** A tray of the remembered print now holds another spool ("" when unknown): from the progress so far, its use is that spool's. */
    private void swapped(String slot, String spoolId) {
        JSONArray uses = pending == null ? null : pending.optJSONArray("uses");
        for (int i = 0; uses != null && i < uses.length(); i++) {
            JSONObject use = uses.optJSONObject(i); JSONArray spans = use == null ? null : use.optJSONArray("spans");
            if (spans == null || !slot.equals(use.optString("slot"))) continue;
            JSONObject last = spans.optJSONObject(spans.length() - 1);
            if (last != null && last.optString("spool").equals(spoolId)) continue;
            try { spans.put(new JSONObject().put("spool", spoolId).put("from", pending.optInt("progress"))); } catch (Exception ignored) { }
        }
    }
    private String settle(double share, long now) {
        double used = 0, unknown = 0; JSONArray uses = pending.optJSONArray("uses");
        double end = Math.max(0, Math.min(1, share)) * 100;
        for (int i = 0; uses != null && i < uses.length(); i++) {
            JSONObject use = uses.optJSONObject(i); if (use == null) continue;
            double grams = use.optDouble("grams", 0);
            JSONArray spans = use.optJSONArray("spans");
            if (spans == null) {   // remembered before spools were followed through a print: the tray's spool now
                double g = grams * end / 100;
                for (Spool s : spools) if (s.slot.equals(use.optString("slot"))) used += debit(s, g, now);
                continue;
            }
            // Each spool takes the share printed while it was in the tray (by progress, the only measure the printer gives).
            for (int k = 0; k < spans.length(); k++) {
                JSONObject span = spans.optJSONObject(k); if (span == null) continue;
                double from = Math.min(end, span.optInt("from")), to = k + 1 < spans.length() ? Math.min(end, spans.optJSONObject(k + 1).optInt("from")) : end;
                double g = grams * Math.max(0, to - from) / 100; if (g <= 0) continue;
                Spool owner = null; for (Spool s : spools) if (s.id.equals(span.optString("spool"))) owner = s;
                if (owner == null) unknown += g; else used += debit(owner, g, now);
            }
        }
        while (usage.size() > USAGE_KEEP) usage.remove(0);
        pending = null; save();
        String line = used <= 0 ? null : String.format(Locale.getDefault(), "About %.0f g of filament taken off the spools.", used);
        if (unknown >= 1) line = (line == null ? "" : line + " ") + String.format(Locale.getDefault(), "About %.0f g came from a spool the app does not know; check its weight.", unknown);
        return line;
    }
    private double debit(Spool s, double grams, long now) {
        s.left = Math.max(0, s.left - grams); s.updated = now;
        if (grams > 0) usage.add(new Usage(now, pending.optString("file"), s.type, grams));
        return grams;
    }
    synchronized boolean hasPending() { return pending != null; }

    private static double clamp(double v, double lo, double hi) { return Double.isFinite(v) ? Math.max(lo, Math.min(hi, v)) : lo; }
    private static Spool copy(Spool s) { return Spool.from(safe(s)); }
    private static JSONObject safe(Spool s) { try { return s.json(); } catch (Exception impossible) { return new JSONObject(); } }

    private void load() {
        spools.clear(); pending = null; usage.clear();
        try {
            if (!file.isFile()) return;
            JSONObject root = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            JSONArray list = root.optJSONArray("spools");
            for (int i = 0; list != null && i < list.length(); i++) { JSONObject o = list.optJSONObject(i); if (o != null) spools.add(Spool.from(o)); }
            pending = root.optJSONObject("pending");
            JSONArray used = root.optJSONArray("usage");
            for (int i = 0; used != null && i < used.length(); i++) { JSONObject o = used.optJSONObject(i); if (o != null) usage.add(new Usage(o.optLong("at"), o.optString("file"), o.optString("type"), o.optDouble("grams", 0))); }
        } catch (Exception unreadable) { spools.clear(); pending = null; }
    }
    private void save() {
        try {
            JSONArray list = new JSONArray(); for (Spool s : spools) list.put(s.json());
            JSONArray used = new JSONArray(); for (Usage u : usage) used.put(new JSONObject().put("at", u.at).put("file", u.file).put("type", u.type).put("grams", u.grams));
            JSONObject root = new JSONObject().put("spools", list).put("usage", used); if (pending != null) root.put("pending", pending);
            File parent = file.getParentFile(); if (parent != null) parent.mkdirs();
            File temp = new File(file.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(temp)) { out.write(root.toString().getBytes(StandardCharsets.UTF_8)); }
            if (!temp.renameTo(file)) { file.delete(); temp.renameTo(file); }
        } catch (Exception ignored) { }
    }
}
