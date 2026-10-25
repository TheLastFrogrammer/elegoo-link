package io.github.thelastfrogrammer.elink.tv;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The first screen: finds Link Workshop phones on the network (or takes a typed address) and remembers the one chosen.
 * A TV that is already paired goes straight on to the camera; Back from there comes here, to change phones.
 */
public final class FindActivity extends Activity {
    /** Off in tests: the screens are then driven by hand, without the network. */
    static boolean networking = true;
    static final long NOTHING_FOUND_MS = 15_000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private PhoneStore store;
    private PhoneFinder finder;
    private LinearLayout list;
    private Ui.Row last;
    private final Map<String, Ui.Row> rows = new LinkedHashMap<>();
    private final Map<String, String> bases = new LinkedHashMap<>();
    private TextView searching;
    private ProgressBar spinner;
    private Button address;
    private boolean checking, keyPressed, destroyed;
    private final Runnable nothingYet = () -> { if (rows.isEmpty()) searching(false, "No phone found yet. On the phone, check that Other apps is turned on and that it is on the same Wi-Fi as this TV. Or enter its address."); };

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        store = new PhoneStore(this);
        // Paired already: straight to the camera. This screen stays underneath for Back.
        if (saved == null && store.paired() && networking) startActivity(new Intent(this, WatchActivity.class));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL); root.setBackgroundColor(Ui.BG);
        root.setPadding(Ui.dp(this, Ui.SIDE + 16), Ui.dp(this, Ui.TOP + 16), Ui.dp(this, Ui.SIDE + 16), Ui.dp(this, Ui.TOP + 16));

        LinearLayout about = new LinearLayout(this);
        about.setOrientation(LinearLayout.VERTICAL); about.setGravity(Gravity.CENTER_VERTICAL);
        about.addView(Ui.label(this, "Printer on TV"));
        TextView heading = Ui.heading(this, "Find your phone", Ui.DISPLAY);
        about.addView(heading, Ui.gap(Ui.wide(), this, 10));
        about.addView(Ui.text(this, "This TV shows your printer through Link Workshop on your phone: the camera, the print's progress and the temperatures. It can watch, never control.", Ui.BODY, Ui.MUTED), Ui.gap(Ui.wide(), this, 18));
        about.addView(Ui.text(this, "On the phone, open Link Workshop and turn on Settings › Automations & integrations › Other apps. Keep the phone on the same Wi-Fi as this TV.", Ui.BODY, Ui.INK), Ui.gap(Ui.wide(), this, 18));
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        left.rightMargin = Ui.dp(this, 56);
        root.addView(about, left);

        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL); side.setGravity(Gravity.CENTER_VERTICAL); side.setClipChildren(false);
        side.addView(Ui.text(this, "Phones on this network", Ui.SMALL, Ui.MUTED));
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(false); scroll.setClipToPadding(false);
        scroll.setFocusable(false);   // an empty list must not take the focus itself (an invisible stop for the remote)
        scroll.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 8));   // room for the focus ring and lift
        scroll.setVerticalScrollBarEnabled(false);
        // The rows, the search's progress and the address button scroll together: as tall as they are (the group stays
        // centred), never taller than the screen, and the focused one is scrolled into view.
        LinearLayout column = new LinearLayout(this); column.setOrientation(LinearLayout.VERTICAL);
        list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL);
        column.addView(list, Ui.wide());
        scroll.addView(column, Ui.wide());
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        scrollParams.topMargin = Ui.dp(this, 4); scrollParams.leftMargin = -Ui.dp(this, 14);
        side.addView(scroll, scrollParams);

        LinearLayout status = new LinearLayout(this);
        status.setOrientation(LinearLayout.HORIZONTAL); status.setGravity(Gravity.CENTER_VERTICAL);
        spinner = new ProgressBar(this); spinner.setIndeterminate(true);
        spinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
        status.addView(spinner, new LinearLayout.LayoutParams(Ui.dp(this, 28), Ui.dp(this, 28)));
        searching = Ui.text(this, "Looking for Link Workshop on this network…", Ui.SMALL + 1, Ui.MUTED);
        LinearLayout.LayoutParams searchingParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        searchingParams.leftMargin = Ui.dp(this, 14);
        status.addView(searching, searchingParams);
        searching.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        column.addView(status, Ui.gap(Ui.wide(), this, 16));

        address = Ui.button(this, "Enter the address", v -> startActivity(new Intent(this, AddressActivity.class)));
        LinearLayout.LayoutParams addressParams = Ui.gap(Ui.wrap(), this, 20);
        addressParams.leftMargin = Ui.dp(this, 6); addressParams.bottomMargin = Ui.dp(this, 6);
        column.addView(address, addressParams);
        root.addView(side, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.1f));

        setContentView(root);
        showLast();
    }

    @Override protected void onStart() {
        super.onStart();
        if (!networking) return;
        finder = new PhoneFinder(this, new PhoneFinder.Listener() {
            @Override public void found(String name, String base) { if (!destroyed) phoneFound(name, base); }
            @Override public void lost(String name) { if (!destroyed) phoneLost(name); }
            @Override public void failed() { if (!destroyed) searching(false, "This TV could not look for phones on the network. Enter the phone's address instead."); }
        });
        finder.start();
        main.postDelayed(nothingYet, NOTHING_FOUND_MS);
    }
    @Override protected void onResume() {
        super.onResume();
        // The first focus once the screen is laid out (asked earlier, the window would give it to its first button).
        getWindow().getDecorView().post(() -> { if (keyPressed || destroyed) return; View first = last != null ? last : firstRow(); if (first != null) first.requestFocus(); });
        checking = false; showLast(); for (Map.Entry<String, Ui.Row> r : rows.entrySet()) r.getValue().detail(PhoneAddress.shown(bases.get(r.getKey())), Ui.MUTED); }
    @Override protected void onStop() { super.onStop(); main.removeCallbacks(nothingYet); if (finder != null) finder.stop(); finder = null; }
    @Override protected void onDestroy() { destroyed = true; main.removeCallbacksAndMessages(null); super.onDestroy(); }

    /** Remembers that the person has started moving around, so a phone found later does not take the focus away. */
    @Override public boolean dispatchKeyEvent(KeyEvent e) { keyPressed = true; return super.dispatchKeyEvent(e); }

    /** The phone used last, first in the list. */
    private void showLast() {
        String base = store.base();
        if (base == null) { if (last != null) { list.removeView(last); last = null; } return; }
        if (last == null) {
            last = new Ui.Row(this, "Last used phone", "");
            last.setOnClickListener(v -> choose(store.base(), last));
            list.addView(last, 0, rowParams());
            if (!keyPressed) last.requestFocus();
        }
        last.detail(PhoneAddress.shown(base) + (store.token() != null ? " · paired" : ""), Ui.MUTED);
        // The same phone found on the network is not listed twice.
        for (Map.Entry<String, String> b : bases.entrySet()) { Ui.Row r = rows.get(b.getKey()); if (r != null) r.setVisibility(b.getValue().equals(base) ? View.GONE : View.VISIBLE); }
    }

    void phoneFound(String name, String base) {
        bases.put(name, base);
        Ui.Row row = rows.get(name);
        if (row == null) {
            row = new Ui.Row(this, name, "");
            Ui.Row chosen = row;
            row.setOnClickListener(v -> choose(bases.get(name), chosen));
            rows.put(name, row); list.addView(row, rowParams());
            if (!keyPressed && last == null && rows.size() == 1) row.requestFocus();
        }
        row.detail(PhoneAddress.shown(base), Ui.MUTED);
        row.setVisibility(base.equals(store.base()) ? View.GONE : View.VISIBLE);
        if (base.equals(store.base()) && last != null) last.detail(PhoneAddress.shown(base) + (store.token() != null ? " · paired" : "") + " · on the network now", Ui.MUTED);
        searching(true, rows.size() == 1 ? "Found 1 phone. Still looking…" : "Found " + rows.size() + " phones. Still looking…");
    }
    void phoneLost(String name) {
        Ui.Row row = rows.remove(name); bases.remove(name);
        if (row != null) { if (row.hasFocus()) address.requestFocus(); list.removeView(row); }
        if (rows.isEmpty()) searching(true, "Looking for Link Workshop on this network…");
    }
    void searching(boolean looking, String words) { spinner.setVisibility(looking ? View.VISIBLE : View.GONE); searching.setText(words); }

    /** Checks the chosen address answers as Link Workshop, then goes on: to the camera if paired, else to pairing. */
    private void choose(String base, Ui.Row row) {
        if (checking || base == null) return;
        checking = true;
        row.detail("Checking " + PhoneAddress.shown(base) + "…", Ui.MUTED);
        Thread t = new Thread(() -> {
            String problem = null;
            try { new PhoneApi(base).info(); }
            catch (PhoneApi.Refused r) { problem = r.code == 200 ? r.getMessage() : "It answered, but not as Link Workshop does (" + r.code + ")."; }
            catch (IOException unreachable) { problem = "Could not reach it. Check that Link Workshop is open on the phone and Other apps is on."; }
            String said = problem;
            main.post(() -> {
                if (destroyed) return;
                checking = false;
                if (said != null) { row.detail(said, Ui.WARN); return; }
                store.phone(base);
                startActivity(new Intent(this, store.token() != null ? WatchActivity.class : PairActivity.class));
            });
        }, "tv-check");
        t.setDaemon(true); t.start();
    }

    private View firstRow() { for (Ui.Row r : rows.values()) if (r.getVisibility() == View.VISIBLE) return r; return null; }
    private LinearLayout.LayoutParams rowParams() { return Ui.gap(Ui.wide(), this, list.getChildCount() == 0 ? 0 : 12); }
}
