package io.github.thelastfrogrammer.elink;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.InputType;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Settings › Other apps: the local API for apps on the home network (a TV, a dashboard, a script), with pairing requests
 * to approve and paired devices to remove, and the relay to the person's own server on the internet. Read only, both.
 */
public final class IntegrationsActivity extends PlainScreen {
    static final String GUIDE = "https://github.com/TheLastFrogrammer/elegoo-link/blob/main/android/INTEGRATIONS.md";
    static final int[] PICTURE_MINUTES = {0, 1, 2, 5, 15};
    static final int[] STATUS_SECONDS = {15, 60, 300};
    private final Handler main = new Handler(Looper.getMainLooper());
    private PrinterService printer;
    private boolean bound;
    private String shown = "";
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) { if (binder instanceof PrinterService.LocalBinder) { printer = ((PrinterService.LocalBinder) binder).service(); render(true); } }
        @Override public void onServiceDisconnected(ComponentName name) { printer = null; }
    };
    private final Runnable refresh = new Runnable() { @Override public void run() { render(false); main.postDelayed(this, 2_000); } };

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        bound = bindService(new Intent(this, PrinterService.class), connection, BIND_AUTO_CREATE);
        render(true);
    }
    @Override protected void onResume() { super.onResume(); main.post(refresh); }
    @Override protected void onPause() { super.onPause(); main.removeCallbacks(refresh); }
    @Override protected void onDestroy() { if (bound) unbindService(connection); super.onDestroy(); }

    private Integrations integrations() { PrinterService p = printer; return p == null ? null : p.integrations; }
    private SharedPreferences prefs() { return Integrations.prefs(this); }

    /** Draws the page; a periodic refresh redraws only when something a person sees changed (a pairing request, a count). */
    private void render(boolean always) {
        Integrations in = integrations();
        String now = in == null ? "none" : in.running() + "|" + in.problem + "|" + in.port() + "|" + in.served() + "|" + in.watching() + "|" + in.relay.lastResult
            + "|" + in.auth.pending(System.currentTimeMillis()).size() + "|" + in.auth.devices().size();
        if (!always && now.equals(shown)) return;
        shown = now;
        content.removeAllViews();
        label(content, "Other apps", 22, ink, true);
        label(content, "Let other apps watch the printer through this phone: a TV or tablet on your Wi-Fi, a dashboard, a script, or your own server on the internet. "
            + "Read only: nothing can control the printer this way. They see what this app sees, while it follows the printer.", 14, muted, false);
        if (in == null) { label(content, "Starting…", 14, muted, false); return; }
        local(in);
        relay(in);
        LinearLayout more = card();
        label(more, "For developers", 15, ink, true);
        label(more, "The API, the status format, pairing, the event stream, camera streams and the relay are described in INTEGRATIONS.md, with a ready relay for Cloudflare and examples.", 13, muted, false);
        button(more, "Open the guide", () -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(GUIDE))));
    }

    // ------------------------------------------------------------------ on this network

    private void local(Integrations in) {
        SharedPreferences prefs = prefs(); long now = System.currentTimeMillis();
        LinearLayout card = card();
        label(card, "On this network", 15, ink, true);
        Switch on = new Switch(this); on.setText("Answer apps on this Wi-Fi"); on.setTextColor(ink); on.setTextSize(15); on.setChecked(prefs.getBoolean(Integrations.LOCAL, false));
        on.setMinHeight(dp(48)); card.addView(on);
        on.setOnCheckedChangeListener((v, checked) -> { prefs.edit().putBoolean(Integrations.LOCAL, checked).apply(); in.apply(); render(true); });
        if (!in.problem.isEmpty()) label(card, in.problem, 13, warning, false);
        if (in.running()) {
            String address = wifiAddress();
            TextView where = address == null ? label(card, "Running on port " + in.port() + ". Connect this phone to Wi-Fi to see its address.", 14, ink, false)
                : label(card, "http://" + address + ":" + in.port() + "/", 18, accent, true);
            where.setTextIsSelectable(true);
            label(card, "Open that address in a TV's or tablet's browser (Silk on a Fire TV), or use it from an app. Apps can also find this phone on the network as "
                + Integrations.SERVICE_TYPE.replaceAll("\\.$", "") + ". " + in.served() + " requests answered · " + in.watching() + " watching now.", 13, muted, false);
        }
        // Devices asking to pair.
        List<IntegrationAuth.Pairing> asking = in.auth.pending(now);
        for (IntegrationAuth.Pairing p : asking) {
            LinearLayout ask = new LinearLayout(this); ask.setOrientation(LinearLayout.VERTICAL); ask.setPadding(0, dp(10), 0, dp(4)); card.addView(ask);
            label(ask, "“" + p.name + "” asks to watch the printer", 15, ink, true);
            label(ask, "Approve only if the device shows the same code:", 13, muted, false);
            label(ask, p.code, 32, accent, true).setContentDescription("Code " + String.join(" ", p.code.split("")));
            LinearLayout choice = row(ask);
            button(choice, "Approve", () -> {
                // Approving can fail (the request ran out, or the phone has as many devices as it keeps): say so, never "approved".
                boolean full = in.auth.devices().size() >= IntegrationAuth.MAX_DEVICES;
                if (in.auth.approve(p.id, System.currentTimeMillis())) Diagnostics.note(Diagnostics.INTEGRATIONS, "a device was approved");
                else {
                    Diagnostics.note(Diagnostics.INTEGRATIONS, "a device could not be approved (" + (full ? "device limit" : "request ran out") + ")");
                    Toast.makeText(this, full ? "There are already " + IntegrationAuth.MAX_DEVICES + " paired devices: remove one, then ask again from the device."
                        : "That request ran out. Ask again from the device.", Toast.LENGTH_LONG).show();
                }
                cancelNotification(); render(true);
            });
            button(choice, "Refuse", () -> { in.auth.deny(p.id); cancelNotification(); render(true); });
        }
        // Paired devices.
        List<IntegrationAuth.Device> devices = in.auth.devices();
        label(card, devices.isEmpty() ? "No paired devices yet. A device asks from its own screen (the page at the address above has a Pair button), or add one here by hand."
            : "Paired devices (tap one to remove it):", 13, muted, false);
        SimpleDateFormat day = new SimpleDateFormat("d MMM HH:mm", Locale.getDefault());
        for (IntegrationAuth.Device d : devices) {
            TextView row = label(card, d.name + " · added " + day.format(new Date(d.created)) + (d.lastSeen > 0 ? " · last seen " + day.format(new Date(d.lastSeen)) : " · not used yet"), 14, ink, false);
            row.setMinHeight(dp(48)); row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("Remove “" + d.name + "”?").setMessage("It will need to pair again to watch the printer.")
                .setNegativeButton("Cancel", null).setPositiveButton("Remove", (x, w) -> { in.auth.revoke(d.id); render(true); }).show());
        }
        LinearLayout actions = row(card);
        button(actions, "Add a device by hand…", () -> addByHand(in));
        button(actions, "Port " + prefs.getInt(Integrations.PORT, IntegrationServer.DEFAULT_PORT) + "…", () -> port(in));
        CheckBox vpn = new CheckBox(this); vpn.setText("Also answer through the home VPN (100.64.x.x addresses)"); vpn.setTextColor(ink); vpn.setChecked(prefs.getBoolean(Integrations.VPN, false)); vpn.setMinHeight(dp(48));
        vpn.setOnCheckedChangeListener((v, checked) -> { prefs.edit().putBoolean(Integrations.VPN, checked).apply(); in.apply(); render(true); });
        card.addView(vpn);
    }
    private void addByHand(Integrations in) {
        EditText name = new EditText(this); name.setHint("Its name, such as Living room TV"); name.setSingleLine();
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(dp(20), dp(8), dp(20), 0); body.addView(name);
        new AlertDialog.Builder(this).setTitle("Add a device").setView(body).setNegativeButton("Cancel", null).setPositiveButton("Add", (d, w) -> {
            String token = in.auth.add(name.getText().toString(), System.currentTimeMillis());
            if (token == null) { Toast.makeText(this, "There are already " + IntegrationAuth.MAX_DEVICES + " devices: remove one first.", Toast.LENGTH_LONG).show(); return; }
            showOnce("The device's token", token, "Give this to the device (it sends it as Authorization: Bearer …). It is shown only now: the phone keeps only a fingerprint of it.");
            render(true);
        }).show();
    }
    private void port(Integrations in) {
        EditText field = new EditText(this); field.setInputType(InputType.TYPE_CLASS_NUMBER); field.setText(String.valueOf(prefs().getInt(Integrations.PORT, IntegrationServer.DEFAULT_PORT)));
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(dp(20), dp(8), dp(20), 0); body.addView(field);
        new AlertDialog.Builder(this).setTitle("Port").setMessage("1024 to 65535. Paired devices need the new address.").setView(body).setNegativeButton("Cancel", null).setPositiveButton("Save", (d, w) -> {
            int port; try { port = Integer.parseInt(field.getText().toString().trim()); } catch (NumberFormatException bad) { port = -1; }
            if (port < 1024 || port > 65535) { Toast.makeText(this, "Choose a port from 1024 to 65535.", Toast.LENGTH_LONG).show(); return; }
            prefs().edit().putInt(Integrations.PORT, port).apply(); in.apply(); render(true);
        }).show();
    }

    // ------------------------------------------------------------------ the relay

    private void relay(Integrations in) {
        SharedPreferences prefs = prefs();
        LinearLayout card = card();
        label(card, "Through the internet (relay)", 15, ink, true);
        label(card, "For apps that cannot reach your home network, such as a Reddit app: this phone pushes the status, print events, a picture now and then "
            + "and the finished timelapse to your own relay (the guide has a ready one for Cloudflare's free tier). Every push is signed with a secret only the phone and the relay know. "
            + "Pictures of your printer then leave your home: only turn them on if you mean to share them.", 13, muted, false);
        String url = Integrations.loadSecret(this, Integrations.RELAY_URL_KEY), secret = Integrations.loadSecret(this, Integrations.RELAY_SECRET_KEY);
        Switch on = new Switch(this); on.setText("Push to my relay"); on.setTextColor(ink); on.setTextSize(15); on.setChecked(prefs.getBoolean(Integrations.RELAY, false)); on.setMinHeight(dp(48));
        card.addView(on);
        on.setOnCheckedChangeListener((v, checked) -> {
            if (checked && (url.isEmpty() || secret.isEmpty())) { Toast.makeText(this, "Set the relay's address and secret first.", Toast.LENGTH_LONG).show(); v.setChecked(false); return; }
            prefs.edit().putBoolean(Integrations.RELAY, checked).apply(); in.apply(); render(true);
        });
        label(card, url.isEmpty() ? "No relay address yet." : "Relay: " + host(url), 13, ink, false);
        label(card, secret.isEmpty() ? "No secret yet." : "Secret set (kept encrypted on this phone).", 13, ink, false);
        LinearLayout setup = row(card);
        button(setup, "Relay address…", () -> relayAddress(in));
        button(setup, "Secret…", () -> relaySecret(in, secret));
        int minutes = prefs.getInt(Integrations.RELAY_PICTURES, 0), seconds = prefs.getInt(Integrations.RELAY_EVERY, 60);
        LinearLayout timing = row(card);
        button(timing, minutes == 0 ? "Pictures: none" : "A picture every " + minutes + " min", () -> choose("Pictures while printing", PICTURE_MINUTES, "min", "No pictures",
            v -> { prefs.edit().putInt(Integrations.RELAY_PICTURES, v).apply(); render(true); }));
        button(timing, "Status at most every " + (seconds >= 60 ? seconds / 60 + " min" : seconds + " s"), () -> choose("Status at most every", STATUS_SECONDS, "s", null,
            v -> { prefs.edit().putInt(Integrations.RELAY_EVERY, v).apply(); in.apply(); render(true); }));
        check(card, "Leave the file name out", Integrations.RELAY_HIDE_FILE);
        check(card, "Send the timelapse when a print ends (up to 24 MB)", Integrations.RELAY_TIMELAPSE);
        button(card, "Send a test now", () -> {
            PrinterService service = printer;
            if (!in.relay.ready() || service == null) { Toast.makeText(this, "Turn the relay on with an address and secret first.", Toast.LENGTH_LONG).show(); return; }
            in.relay.test(service.integrationStatus(prefs.getBoolean(Integrations.RELAY_HIDE_FILE, false))); Toast.makeText(this, "Sending…", Toast.LENGTH_SHORT).show();
        });
        label(card, in.relay.lastResult, 13, muted, false);
    }
    private void relayAddress(Integrations in) {
        EditText field = new EditText(this); field.setHint("https://printer-relay.example.workers.dev"); field.setSingleLine(); field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        field.setText(Integrations.loadSecret(this, Integrations.RELAY_URL_KEY));
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(dp(20), dp(8), dp(20), 0); body.addView(field);
        new AlertDialog.Builder(this).setTitle("Relay address").setView(body).setNegativeButton("Cancel", null).setPositiveButton("Save", (d, w) -> {
            String text = field.getText().toString().trim(); String why = text.isEmpty() ? "" : IntegrationRelay.problem(text);
            if (!why.isEmpty()) { Toast.makeText(this, why, Toast.LENGTH_LONG).show(); return; }
            try { Integrations.saveSecret(this, Integrations.RELAY_URL_KEY, text); } catch (Exception failed) { Toast.makeText(this, "Could not keep it.", Toast.LENGTH_LONG).show(); return; }
            if (text.isEmpty()) prefs().edit().putBoolean(Integrations.RELAY, false).apply();
            in.apply(); render(true);
        }).show();
    }
    private void relaySecret(Integrations in, String current) {
        new AlertDialog.Builder(this).setTitle("Relay secret").setItems(new String[] {"Make a new secret", "Type the relay's secret", current.isEmpty() ? "Remove (none set)" : "Remove the secret"}, (d, which) -> {
            if (which == 0) {
                String made = IntegrationRelay.newSecret();
                try { Integrations.saveSecret(this, Integrations.RELAY_SECRET_KEY, made); } catch (Exception failed) { Toast.makeText(this, "Could not keep it.", Toast.LENGTH_LONG).show(); return; }
                in.apply(); render(true);
                showOnce("The relay secret", made, "Put this on the relay as LW_SECRET (npx wrangler secret put LW_SECRET). It is shown only now.");
            } else if (which == 1) {
                EditText field = new EditText(this); field.setSingleLine(); field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(dp(20), dp(8), dp(20), 0); body.addView(field);
                new AlertDialog.Builder(this).setTitle("The relay's secret").setMessage("At least 16 characters, the same as LW_SECRET on the relay.").setView(body).setNegativeButton("Cancel", null)
                    .setPositiveButton("Save", (x, w) -> {
                        String text = field.getText().toString().trim();
                        if (text.length() < 16) { Toast.makeText(this, "Use at least 16 characters.", Toast.LENGTH_LONG).show(); return; }
                        try { Integrations.saveSecret(this, Integrations.RELAY_SECRET_KEY, text); } catch (Exception failed) { Toast.makeText(this, "Could not keep it.", Toast.LENGTH_LONG).show(); return; }
                        in.apply(); render(true);
                    }).show();
            } else {
                try { Integrations.saveSecret(this, Integrations.RELAY_SECRET_KEY, ""); } catch (Exception ignored) { }
                prefs().edit().putBoolean(Integrations.RELAY, false).apply(); in.apply(); render(true);
            }
        }).setNegativeButton("Cancel", null).show();
    }

    // ------------------------------------------------------------------ helpers

    private interface Chosen { void take(int value); }
    private void choose(String title, int[] values, String unit, String zero, Chosen chosen) {
        String[] names = new String[values.length];
        for (int i = 0; i < values.length; i++) names[i] = values[i] == 0 && zero != null ? zero : "s".equals(unit) && values[i] >= 60 ? values[i] / 60 + " min" : values[i] + " " + unit;
        new AlertDialog.Builder(this).setTitle(title).setItems(names, (d, which) -> chosen.take(values[which])).setNegativeButton("Cancel", null).show();
    }
    private void check(LinearLayout card, String text, String key) {
        CheckBox box = new CheckBox(this); box.setText(text); box.setTextColor(ink); box.setChecked(prefs().getBoolean(key, false)); box.setMinHeight(dp(48));
        box.setOnCheckedChangeListener((v, checked) -> prefs().edit().putBoolean(key, checked).apply()); card.addView(box);
    }
    /** A secret shown once, selectable, with a copy button. */
    private void showOnce(String title, String secret, String note) {
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(dp(20), dp(8), dp(20), 0);
        TextView text = new TextView(this); text.setText(secret); text.setTextIsSelectable(true); text.setTextSize(16); text.setTypeface(android.graphics.Typeface.MONOSPACE); body.addView(text);
        TextView words = new TextView(this); words.setText(note); words.setPadding(0, dp(12), 0, 0); body.addView(words);
        new AlertDialog.Builder(this).setTitle(title).setView(body).setPositiveButton("Done", null).setNeutralButton("Copy", (d, w) -> {
            ClipboardManager clip = getSystemService(ClipboardManager.class);
            ClipData data = ClipData.newPlainText(title, secret);
            if (android.os.Build.VERSION.SDK_INT >= 33) { android.os.PersistableBundle extras = new android.os.PersistableBundle(); extras.putBoolean("android.content.extra.IS_SENSITIVE", true); data.getDescription().setExtras(extras); }
            if (clip != null) clip.setPrimaryClip(data);
        }).show();
    }
    private void cancelNotification() { android.app.NotificationManager m = getSystemService(android.app.NotificationManager.class); if (m != null) m.cancel(Integrations.PAIRING_NOTIFICATION); }
    private static String host(String url) { try { return new java.net.URL(url).getHost(); } catch (java.net.MalformedURLException bad) { return "not a usable address"; } }
    /** This phone's IPv4 address on its current network (Wi-Fi), or null. */
    private String wifiAddress() {
        try {
            ConnectivityManager cm = getSystemService(ConnectivityManager.class);
            LinkProperties link = cm == null ? null : cm.getLinkProperties(cm.getActiveNetwork());
            if (link != null) for (LinkAddress a : link.getLinkAddresses()) if (a.getAddress() instanceof java.net.Inet4Address && a.getAddress().isSiteLocalAddress()) return a.getAddress().getHostAddress();
        } catch (RuntimeException unavailable) { }
        return null;
    }
}
