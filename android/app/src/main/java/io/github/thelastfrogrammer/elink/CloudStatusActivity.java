package io.github.thelastfrogrammer.elink;

import android.app.Activity;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Read-only view of the account's printers as the Elegoo cloud last saw them. Sends nothing to the printers. */
public final class CloudStatusActivity extends Activity {
    private static final long POLL_MS = 15_000;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView header, body;
    private CloudAccountStore store;
    private CloudApi api;
    private boolean visible, busy;
    private final Runnable poll = new Runnable() { public void run() { refresh(); main.postDelayed(this, POLL_MS); } };

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        store = new CloudAccountStore(this);
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int ink = dark ? 0xffe4eff2 : 0xff142c3b, muted = dark ? 0xffa7bec6 : 0xff536976, pad = Math.round(16 * getResources().getDisplayMetrics().density);
        ScrollView scroll = new ScrollView(this); scroll.setBackgroundColor(dark ? 0xff10191d : 0xffedf3f4); scroll.setFitsSystemWindows(true);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(pad, pad, pad, pad); scroll.addView(root);
        TextView title = new TextView(this); title.setText("Cloud printers (read-only)"); title.setTextSize(22); title.setTextColor(ink); root.addView(title);
        header = new TextView(this); header.setTextSize(13); header.setTextColor(muted); header.setPadding(0, pad / 2, 0, pad / 2);
        header.setText("Shows what the Elegoo cloud last received from your printers, refreshed every 15 seconds while this screen is open. Nothing is sent to the printers.");
        root.addView(header);
        Button refresh = new Button(this); refresh.setText("Refresh now"); refresh.setAllCaps(false); refresh.setOnClickListener(v -> refresh()); root.addView(refresh);
        body = new TextView(this); body.setTextSize(15); body.setTextColor(ink); body.setTextIsSelectable(true); body.setPadding(0, pad, 0, 0);
        body.setText("Loading…"); root.addView(body);
        setContentView(scroll);
        try {
            CloudLogin.Account account = store.load();
            if (account == null) { body.setText("Not signed in. Use Settings → Elegoo account → Sign in with Elegoo first."); return; }
            String agent = CloudLogin.SLICER_AGENT + " (Android " + Build.VERSION.RELEASE + "; " + (Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "unknown") + ") LinkWorkshop/" + version();
            api = new CloudApi(store.china(), account, agent, CloudApi::https);
        } catch (Exception error) { store.forget(); body.setText("Saved Elegoo sign-in could not be decrypted. Sign in again."); }
    }
    private String version() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception error) { return "dev"; }
    }

    @Override protected void onStart() { super.onStart(); visible = true; if (api != null) main.post(poll); }
    @Override protected void onStop() { visible = false; main.removeCallbacks(poll); super.onStop(); }
    @Override protected void onDestroy() { worker.shutdownNow(); main.removeCallbacksAndMessages(null); super.onDestroy(); }

    private void refresh() {
        if (api == null || busy) return;
        busy = true;
        worker.execute(() -> {
            String text;
            CloudLogin.Account before = api.account();
            try { text = describe(api); }
            catch (CloudApi.CloudException error) {
                text = error.getMessage();
                if (error.unauthorized) text += "\nIf this keeps happening, sign out and sign in again in Settings.";
            } catch (Exception error) { text = "Could not reach the Elegoo cloud. Check the phone's internet connection."; }
            // Keep tokens the server rotated, unless the user signed out meanwhile.
            if (api.account() != before) try { if (store.load() != null) store.save(api.account()); } catch (Exception ignored) { }
            String result = text;
            main.post(() -> { busy = false; if (!isDestroyed()) body.setText(result); });
        });
    }

    private static String describe(CloudApi api) throws Exception {
        List<CloudApi.Device> devices = api.devices();
        if (devices.isEmpty()) return "No printers are bound to this Elegoo account.";
        StringBuilder text = new StringBuilder();
        long now = System.currentTimeMillis();
        for (CloudApi.Device device : devices) {
            if (text.length() > 0) text.append("\n\n");
            String serial = device.serial.length() > 4 ? "…" + device.serial.substring(device.serial.length() - 4) : device.serial;
            text.append(device.name.isEmpty() ? "Printer" : StatusPresentation.clean(device.name)).append(" · ").append(StatusPresentation.clean(device.model)).append(" · SN ").append(serial);
            int online = api.online(device.serial);
            text.append("\nCloud connection: ").append(online == 1 ? "online" : online == 0 ? "offline" : "unknown (" + online + ")");
            CloudApi.Snapshot snapshot = api.status(device.serial);
            if (snapshot.status.length() == 0) { text.append("\nNo status reported to the cloud yet."); continue; }
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
