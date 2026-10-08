package io.github.thelastfrogrammer.elink;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Whether a print-history entry can be printed again: only if the printer still holds its file. As on Elegoo's own printer page
 * (its Reprint button is disabled for a task whose name is not in the file list), the history entry's name must equal a name in
 * the printer's file list exactly. Pure logic; the Print setup dialog does the real start checks.
 */
final class PrintAgain {
    enum State {
        /** The file is in a fresh listing: Print setup can open. */
        READY,
        /** The listing is old: refresh files first, then decide. */
        REFRESH,
        /** The listing does not cover this file (not loaded, other storage, or only part of it is loaded). */
        UNKNOWN,
        /** A fresh, complete listing does not hold the file. */
        MISSING
    }
    final State state; final JSONObject file; final String reason;
    private PrintAgain(State state, JSONObject file, String reason) { this.state = state; this.file = file; this.reason = reason; }

    static final String GONE = "This file is no longer on the printer.";

    static PrintAgain check(String taskName, JSONObject filePage, String storage, int offset, boolean filesFresh) {
        if (taskName == null || taskName.isEmpty()) return new PrintAgain(State.MISSING, null, "The printer did not report a file name for this print.");
        if (!"local".equals(storage)) return new PrintAgain(State.UNKNOWN, null, "Show Internal storage in the file list to check whether this file is still on the printer.");
        JSONArray rows = filePage == null ? null : filePage.optJSONArray("file_list");
        if (rows == null) return new PrintAgain(State.UNKNOWN, null, "Load the file list to check whether this file is still on the printer.");
        JSONObject found = null;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row != null && taskName.equals(row.optString("filename"))) { found = row; break; }
        }
        if (found != null) return filesFresh ? new PrintAgain(State.READY, found, "") : new PrintAgain(State.REFRESH, found, "The file list is out of date. Refresh files first.");
        if (!filesFresh) return new PrintAgain(State.REFRESH, null, "The file list is out of date. Refresh files first.");
        int total = filePage.optInt("total", -1);
        if (offset > 0 || total > rows.length()) return new PrintAgain(State.UNKNOWN, null, "This file is not among the files loaded so far. Browse the file list to find it.");
        return new PrintAgain(State.MISSING, null, GONE);
    }
}
