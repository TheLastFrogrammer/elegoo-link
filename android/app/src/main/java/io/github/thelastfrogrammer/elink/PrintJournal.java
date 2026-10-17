package io.github.thelastfrogrammer.elink;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The person's own notes on one print, kept with its recording: stars, how it came out, what went wrong, notes and tags,
 * and the print guardian's alarms with the answer given ("Looks fine" or "Real problem"). Independent of Android.
 */
final class PrintJournal {
    static final String[][] RESULTS = {{"good", "Came out well"}, {"usable", "Usable, with flaws"}, {"failed", "Failed"}};
    static final String[][] REASONS = {
        {"spaghetti", "Spaghetti / came loose mid-print"}, {"warping", "Warping or lifted corners"}, {"adhesion", "First layer did not stick"},
        {"layer_shift", "Layer shift"}, {"under_extrusion", "Under-extrusion or gaps"}, {"clog", "Clog or filament stopped"},
        {"stringing", "Stringing or blobs"}, {"supports", "Supports failed or would not come off"}, {"surface", "Rough surface or artifacts"},
        {"dimensions", "Wrong size or fit"}, {"filament", "Wrong or run-out filament"}, {"other", "Something else"}};

    int stars;
    String result = "", notes = "";
    final Set<String> reasons = new LinkedHashSet<>(), tags = new LinkedHashSet<>();
    long ratedAt;
    /** The guardian's alarms and the answers to them: {at, fine, said}. */
    final List<JSONObject> guardian = new ArrayList<>();

    boolean rated() { return stars > 0 || !result.isEmpty(); }
    boolean empty() { return !rated() && reasons.isEmpty() && tags.isEmpty() && notes.isEmpty() && guardian.isEmpty(); }

    JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject().put("stars", stars).put("result", result).put("notes", notes).put("rated_at", ratedAt)
            .put("reasons", new JSONArray(new ArrayList<>(reasons))).put("tags", new JSONArray(new ArrayList<>(tags)));
        JSONArray g = new JSONArray(); for (JSONObject one : guardian) g.put(one); o.put("guardian", g);
        return o;
    }
    static PrintJournal fromJson(JSONObject o) {
        PrintJournal j = new PrintJournal();
        if (o == null) return j;
        j.stars = Math.max(0, Math.min(5, o.optInt("stars")));
        j.result = label(RESULTS, o.optString("result")) == null ? "" : o.optString("result");
        j.notes = Automation.cap(o.optString("notes"), 2000); j.ratedAt = o.optLong("rated_at");
        JSONArray r = o.optJSONArray("reasons"); for (int i = 0; r != null && i < r.length(); i++) if (label(REASONS, r.optString(i)) != null) j.reasons.add(r.optString(i));
        JSONArray t = o.optJSONArray("tags"); for (int i = 0; t != null && i < t.length(); i++) j.tags.addAll(tags(t.optString(i)));
        JSONArray g = o.optJSONArray("guardian"); for (int i = 0; g != null && i < g.length() && i < 50; i++) if (g.optJSONObject(i) != null) j.guardian.add(g.optJSONObject(i));
        return j;
    }

    /** Tags from typed text: split on commas, trimmed, lower case, at most 24 characters each and 12 in all. */
    static Set<String> tags(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null) return out;
        for (String part : text.split("[,;\\n]")) {
            String tag = part.trim().replaceFirst("^#", "").toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
            if (!tag.isEmpty() && out.size() < 12) out.add(Automation.cap(tag, 24));
        }
        return out;
    }
    static String label(String[][] list, String id) { for (String[] e : list) if (e[0].equals(id)) return e[1]; return null; }

    /** One line for lists: "★★★★☆ · Failed: Warping or lifted corners · #petg #functional". */
    String summary() {
        List<String> parts = new ArrayList<>();
        if (stars > 0) parts.add("★★★★★".substring(0, stars) + "☆☆☆☆☆".substring(0, 5 - stars));
        String outcome = label(RESULTS, result);
        if (outcome != null) {
            List<String> why = new ArrayList<>(); for (String r : reasons) why.add(label(REASONS, r));
            parts.add(outcome + (why.isEmpty() ? "" : ": " + String.join(", ", why)));
        } else if (!reasons.isEmpty()) { List<String> why = new ArrayList<>(); for (String r : reasons) why.add(label(REASONS, r)); parts.add(String.join(", ", why)); }
        if (!tags.isEmpty()) { StringBuilder t = new StringBuilder(); for (String tag : tags) t.append(t.length() > 0 ? " " : "").append('#').append(tag.replace(' ', '-')); parts.add(t.toString()); }
        return String.join(" · ", parts);
    }

    /** Whether a recording matches a search: its file name, notes, tags, outcome or problems contain every word. */
    static boolean matches(String fileName, PrintJournal j, String query) {
        if (query == null || query.trim().isEmpty()) return true;
        StringBuilder text = new StringBuilder(fileName == null ? "" : fileName).append(' ').append(j.notes).append(' ');
        for (String tag : j.tags) text.append('#').append(tag).append(' ').append(tag).append(' ');
        String outcome = label(RESULTS, j.result); if (outcome != null) text.append(outcome).append(' ');
        for (String r : j.reasons) text.append(label(REASONS, r)).append(' ').append(r).append(' ');
        if (!j.rated()) text.append(" unrated not rated ");
        String haystack = text.toString().toLowerCase(Locale.ROOT);
        for (String word : query.toLowerCase(Locale.ROOT).trim().split("\\s+")) if (!haystack.contains(word)) return false;
        return true;
    }

    /** What went wrong how often, most often first, over the journals given. */
    static Map<String, Integer> problemCounts(List<PrintJournal> journals) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String[] r : REASONS) counts.put(r[0], 0);
        for (PrintJournal j : journals) for (String r : j.reasons) counts.merge(r, 1, Integer::sum);
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort((a, b) -> b.getValue() - a.getValue());
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : sorted) if (e.getValue() > 0) out.put(e.getKey(), e.getValue());
        return out;
    }
}
