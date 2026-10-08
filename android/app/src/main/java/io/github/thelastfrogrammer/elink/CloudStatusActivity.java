package io.github.thelastfrogrammer.elink;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Read-only view of the account's printers as the Elegoo cloud last saw them. Sends nothing to the printers.
 * Plain status first; the sign-in details and request trace sit behind "Show technical details".
 */
public final class CloudStatusActivity extends Activity {
    private static final long POLL_MS = 15_000;
    private static final String NOT_SIGNED_IN = "You are not signed in to Elegoo on this phone.\n\n"
        + "1. Go to Settings.\n2. Tap Sign in with Elegoo.\n3. Sign in on Elegoo's page. Then come back here.";
    private static final String REMOVE_HINT = "\n\nTo remove this account from this phone, go to Settings and tap Sign out on this phone.";
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private WorkshopUi ui;
    private TextView printers, details;
    private Button refresh, toggle, renew;
    private LinearLayout detailsCard;
    private CloudAccountStore store;
    private CloudApi api;
    private boolean busy, checked, showingDetails;
    private final Runnable poll = new Runnable() { public void run() { refresh(); main.postDelayed(this, POLL_MS); } };

    @Override protected void onCreate(Bundle saved) {
        ui = new WorkshopUi(this); // applies the app's Light/Dark setting before the window is created
        super.onCreate(saved);
        store = new CloudAccountStore(this);
        LinearLayout content = ui.page("Cloud printers", "What Elegoo's cloud last received from your printers. It updates every 15 seconds while this screen is open. Nothing is sent to your printers.");
        printers = ui.label(ui.card(content, "Your printers"), "Loading…", 15, ui.ink, false);
        refresh = ui.button(content, "Refresh now", this::refresh, true);
        toggle = ui.button(content, "Show technical details", this::toggleDetails, false);
        detailsCard = ui.card(content, "Technical details (no passwords or tokens)");
        details = ui.label(detailsCard, "", 13, ui.muted, false); details.setTextIsSelectable(true);
        renew = ui.button(detailsCard, "Test sign-in renewal…", this::confirmRenewal, false);
        detailsCard.setVisibility(View.GONE);
        try {
            CloudLogin.Account account = store.load();
            if (account == null) { showNotSignedIn(); return; }
            api = new CloudApi(store.china(), account, CloudApi.agent(this), CloudApi::https);
            api.language(Locale.getDefault().getLanguage());
        } catch (Exception error) {
            store.forget(); showNotSignedIn();
            printers.setText("Your saved Elegoo sign-in could not be opened, so it was removed from this phone.\n\n" + NOT_SIGNED_IN);
        }
    }

    private void showNotSignedIn() {
        printers.setText(NOT_SIGNED_IN);
        refresh.setVisibility(View.GONE); toggle.setVisibility(View.GONE);
    }

    private void toggleDetails() {
        showingDetails = !showingDetails;
        detailsCard.setVisibility(showingDetails ? View.VISIBLE : View.GONE);
        toggle.setText(showingDetails ? "Hide technical details" : "Show technical details");
    }

    @Override protected void onStart() { super.onStart(); if (api != null) main.post(poll); }
    @Override protected void onStop() { main.removeCallbacks(poll); super.onStop(); }
    @Override protected void onDestroy() { worker.shutdownNow(); main.removeCallbacksAndMessages(null); super.onDestroy(); }

    private void refresh() {
        if (api == null || busy) return;
        busy = true;
        worker.execute(() -> {
            String text;
            CloudLogin.Account before = api.account();
            boolean first = !checked; checked = true;
            if (first) api.accountCheck();
            try { text = describe(api); }
            catch (CloudApi.CloudException error) { text = error.getMessage(); }
            catch (Exception error) { text = "Could not reach the Elegoo cloud. Check the phone's internet connection, then tap Refresh now."; }
            // Keep tokens the server rotated, unless the user signed out meanwhile.
            if (api.account() != before) try { if (store.load() != null) store.save(api.account()); } catch (Exception ignored) { }
            CloudLogin.Account now = api.account();
            String who = "Signed in as " + (now.nickname.isEmpty() ? "your Elegoo account" : now.nickname) + ".\n\n";
            String result = who + text + REMOVE_HINT;
            StringBuilder technical = new StringBuilder("Access token valid until ").append(CloudApi.when(now.accessExpires))
                .append("\nRefresh token valid until ").append(CloudApi.when(now.refreshExpires)).append(now.refreshToken.isEmpty() ? " (none received)" : "")
                .append("\nAccess token: ").append(CloudApi.describe(now.accessToken))
                .append(now.signInNote.isEmpty() ? "" : "\n" + now.signInNote);
            for (String line : api.takeTrace()) technical.append("\n• ").append(line);
            String techText = technical.toString();
            main.post(() -> { busy = false; if (!isDestroyed()) { printers.setText(result); details.setText(techText); } });
        });
    }

    /** Renewal normally waits for expiry (days away); this lets it be tested now. A refusal may end the sign-in. */
    private void confirmRenewal() {
        if (api == null || busy) return;
        new android.app.AlertDialog.Builder(this).setTitle("Test sign-in renewal?")
            .setMessage("Asks Elegoo for new tokens now, the way Elegoo's account page does. If Elegoo refuses, this sign-in may stop working and you will need to sign in again.")
            .setNegativeButton("Cancel", null).setPositiveButton("Renew now", (dialog, which) -> renewNow()).show();
    }
    private void renewNow() {
        busy = true;
        worker.execute(() -> {
            String text;
            try {
                api.refresh();
                if (store.load() != null) store.save(api.account());
                text = "Renewal worked. New access token valid until " + CloudApi.when(api.account().accessExpires) + ".";
            } catch (Exception error) { text = error.getMessage() == null ? "Renewal failed." : error.getMessage(); }
            StringBuilder technical = new StringBuilder(text).append("\n\nTechnical details");
            for (String line : api.takeTrace()) technical.append("\n• ").append(line);
            String result = technical.toString();
            main.post(() -> { busy = false; if (!isDestroyed()) details.setText(result); });
        });
    }

    private static String describe(CloudApi api) throws Exception {
        List<CloudApi.Device> devices = api.devices();
        if (devices.isEmpty()) return "No printers are linked to this Elegoo account yet.\n\nAdd your printer in Elegoo's app while signed in with this same account, then tap Refresh now.";
        StringBuilder text = new StringBuilder();
        long now = System.currentTimeMillis();
        for (CloudApi.Device device : devices) {
            if (text.length() > 0) text.append("\n\n");
            String serial = device.serial.length() > 4 ? "…" + device.serial.substring(device.serial.length() - 4) : device.serial;
            text.append(device.name.isEmpty() ? "Printer" : StatusPresentation.clean(device.name)).append(" · ").append(StatusPresentation.clean(device.model)).append(" · Serial ").append(serial);
            int online = api.online(device.serial);
            text.append("\n").append(online == 1 ? "Printer online" : online == 0 ? "Printer offline" : "Printer status not known yet");
            CloudApi.Snapshot snapshot;
            try { snapshot = api.status(device.serial); }
            catch (CloudApi.CloudException error) { text.append("\nStatus unavailable: ").append(error.getMessage()); continue; }
            if (snapshot.status.length() == 0) { text.append("\nThe cloud has no status for this printer yet. It sends one once the printer is on and connected to the internet."); continue; }
            text.append("\n").append(StatusPresentation.overview(snapshot.status));
            if (snapshot.reportedAt > 0) text.append("\nLast report ").append(age(now - CloudApi.seconds(snapshot.reportedAt) * 1000)).append(" ago");
        }
        return text.toString();
    }
    static String age(long millis) {
        long seconds = Math.max(0, millis / 1000);
        if (seconds < 90) return seconds + " s";
        if (seconds < 90 * 60) return seconds / 60 + " min";
        return String.format(Locale.ROOT, "%.1f h", seconds / 3600.0);
    }
}
