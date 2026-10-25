package io.github.thelastfrogrammer.elink;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A part of a long page that opens and closes: a header with its name and, while it is closed, a line saying what is inside,
 * then its body. The body is built the first time the part opens, so a closed part costs nothing when the page opens.
 * Whether a part is open is kept in the app's settings under its key, so a part closed on one print stays closed on the next.
 */
final class PageSection {
    /** Settings key of a part: "section." and the page's own name for it. */
    static final String KEY = "section.", TAG = "page-section";
    final String key, name;
    final LinearLayout block, header, body;
    final TextView title, summary, arrow;
    private final SharedPreferences prefs; private final Supplier<String> line; private Consumer<LinearLayout> fill;
    private boolean open, built;

    /** The hairline between parts: a little firmer than the charts' grid, so it reads on the page's background. */
    static int rule(boolean dark) { return dark ? 0xff2c3b41 : 0xffd3dcde; }
    /** The press ripple of the app's soft buttons. */
    static int ripple(boolean dark) { return dark ? 0x4463d5c7 : 0x33006b65; }

    /**
     * Adds a part to the end of `parent`. `forced` opens it without remembering that (the tour, or a page opened for
     * something inside it); otherwise it opens as last left, or as `openFirst` says the first time. `line` is read again
     * each time the part closes, so it can follow what changed inside.
     */
    PageSection(Context context, LinearLayout parent, SharedPreferences prefs, String key, String name, boolean openFirst, boolean forced,
                int ink, int muted, int rule, int ripple, Supplier<String> line, Consumer<LinearLayout> fill) {
        this.prefs = prefs; this.key = key; this.name = name; this.line = line; this.fill = fill;
        float d = context.getResources().getDisplayMetrics().density;
        block = new LinearLayout(context); block.setOrientation(LinearLayout.VERTICAL); block.setTag(TAG);
        // A hairline between parts, not above the first: the page's title is enough there.
        View last = parent.getChildCount() == 0 ? null : parent.getChildAt(parent.getChildCount() - 1);
        LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(-1, -2); at.topMargin = Math.round((last != null && TAG.equals(last.getTag()) ? 20 : 8) * d);
        if (last != null && TAG.equals(last.getTag())) { View hairline = new View(context); hairline.setBackgroundColor(rule); block.addView(hairline, new LinearLayout.LayoutParams(-1, Math.max(1, Math.round(d)))); }
        header = new LinearLayout(context); header.setOrientation(LinearLayout.HORIZONTAL); header.setGravity(Gravity.CENTER_VERTICAL);
        // Titles line up with the cards' edges; the header stays at least 52dp tall, above the 48dp a touch needs.
        header.setMinimumHeight(Math.round(52 * d)); header.setPadding(0, Math.round(6 * d), 0, Math.round(6 * d));
        GradientDrawable mask = new GradientDrawable(); mask.setColor(0xffffffff); mask.setCornerRadius(12 * d);
        header.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(ripple), null, mask));
        LinearLayout words = new LinearLayout(context); words.setOrientation(LinearLayout.VERTICAL); header.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        title = new TextView(context); title.setText(name); title.setTextSize(19); title.setTextColor(ink); title.setTypeface(Typeface.DEFAULT, Typeface.BOLD); words.addView(title);
        summary = new TextView(context); summary.setTextSize(13); summary.setTextColor(muted);   // wraps rather than cuts, at any font size
        summary.setPadding(0, Math.round(2 * d), 0, 0); words.addView(summary);
        arrow = new TextView(context); arrow.setTextSize(20); arrow.setTextColor(muted); arrow.setGravity(Gravity.CENTER); arrow.setMinWidth(Math.round(40 * d));
        header.addView(arrow, new LinearLayout.LayoutParams(-2, -2));
        // The header is one control: its own words are spoken from its description, not its children (nor the arrow).
        for (View v : new View[] {title, summary, arrow}) v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        header.setClickable(true); header.setFocusable(true); A11y.heading(header);
        header.setOnClickListener(v -> set(!open, true));
        block.addView(header, new LinearLayout.LayoutParams(-1, -2));
        body = new LinearLayout(context); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(0, 0, 0, Math.round(4 * d));
        block.addView(body, new LinearLayout.LayoutParams(-1, -2));
        parent.addView(block, at);
        open = forced || prefs.getBoolean(KEY + key, openFirst);
        set(open, false);
    }

    boolean isOpen() { return open; }

    /** Opens or closes the part, building its body the first time; `remember` keeps the choice for the next page. */
    void set(boolean opened, boolean remember) {
        open = opened;
        if (open && !built) { built = true; Consumer<LinearLayout> f = fill; fill = null; if (f != null) f.accept(body); }
        body.setVisibility(open ? View.VISIBLE : View.GONE);
        refresh();
        if (remember) prefs.edit().putBoolean(KEY + key, open).apply();
    }

    /** Reads the closed part's line again (after something inside changed, or a background read finished). */
    void refresh() {
        String text = open || line == null ? "" : line.get();
        summary.setText(text); summary.setVisibility(text == null || text.isEmpty() ? View.GONE : View.VISIBLE);
        arrow.setText(open ? "▴" : "▾");
        header.setContentDescription(name + (text == null || text.isEmpty() ? "" : ". " + text));
        A11y.expandable(header, open); A11y.state(header, open ? "Expanded" : "Collapsed");
    }

    /** A section's title where nothing opens or closes, in the same look (a hairline above it when it follows another part). */
    static TextView heading(Context context, LinearLayout parent, String name, int ink, int rule) {
        float d = context.getResources().getDisplayMetrics().density;
        LinearLayout block = new LinearLayout(context); block.setOrientation(LinearLayout.VERTICAL); block.setTag(TAG);
        LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(-1, -2); at.topMargin = Math.round(20 * d);
        View hairline = new View(context); hairline.setBackgroundColor(rule); block.addView(hairline, new LinearLayout.LayoutParams(-1, Math.max(1, Math.round(d))));
        TextView title = new TextView(context); title.setText(name); title.setTextSize(19); title.setTextColor(ink); title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setMinHeight(Math.round(52 * d)); title.setGravity(Gravity.CENTER_VERTICAL); title.setPadding(0, Math.round(6 * d), 0, Math.round(6 * d));
        A11y.heading(title); block.addView(title, new LinearLayout.LayoutParams(-1, -2));
        parent.addView(block, at);
        return title;
    }
}
