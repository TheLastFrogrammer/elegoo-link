package io.github.thelastfrogrammer.elink;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Other apps reading the printer through this phone (Settings › Other apps): the local API on the home network
 * (IntegrationServer, with its camera hub and pairing), announced on the network so apps find it, and the relay that
 * pushes to the person's own server on the internet (IntegrationRelay). Everything is read only and off until turned on.
 * The printer service owns one; it calls in on the main thread.
 */
final class Integrations {
    static final String PREFS = "integrations";
    static final String LOCAL = "localOn", PORT = "port", VPN = "allowVpn", DEVICES = "devices",
        RELAY = "relayOn", RELAY_EVERY = "relayStatusSeconds", RELAY_PICTURES = "relayPictureMinutes", RELAY_HIDE_FILE = "relayHideFile", RELAY_TIMELAPSE = "relayTimelapse";
    static final String RELAY_URL_KEY = "integration-relay-url", RELAY_SECRET_KEY = "integration-relay-secret";
    static final String SERVICE_TYPE = "_linkworkshop._tcp.";
    static final int PAIRING_NOTIFICATION = 7401;

    interface Printer {
        /** The status now, for other apps (main thread). */
        JSONObject integrationStatus(boolean hideFile);
        JSONArray integrationCameras();
        /** Opens a camera for the hub, or null when it cannot be opened now (any thread). */
        CameraHub.Source integrationCamera(String id, CameraHub.Sink sink);
        /** The newest kept picture of the print (JPEG), or null (not on the main thread). */
        byte[] latestPictureJpeg();
    }

    private final Context context;
    private final Printer printer;
    private final Handler main = new Handler(Looper.getMainLooper());
    final IntegrationAuth auth;
    final CameraHub hub;
    final IntegrationRelay relay = new IntegrationRelay(IntegrationRelay.https());
    private IntegrationServer server;
    private boolean serverVpn;
    private NsdManager.RegistrationListener announced;
    /** The status other apps read, and when it was built: kept together so its ages can be moved on (IntegrationStatus.aged). */
    private static final class Built { final String text; final long at; Built(String text, long at) { this.text = text; this.at = at; } }
    private volatile Built built = new Built("{}", 0);
    static final long REFRESH_MS = 15_000;
    /**
     * Built again every REFRESH_MS while the local API or the relay is on, not only when a status arrives: a printer that
     * goes quiet then shows as such, and a change the relay held back (too soon after the last push) still goes out.
     */
    private final Runnable refresh = new Runnable() { @Override public void run() { statusChanged(); main.postDelayed(this, REFRESH_MS); } };
    private long relayPictureAt;
    /** Why the local API is not running ("" when it is, or is off), for the settings screen. */
    volatile String problem = "";

    Integrations(Context context, Printer printer) {
        this.context = context.getApplicationContext(); this.printer = printer;
        SharedPreferences prefs = prefs(context);
        auth = new IntegrationAuth(new IntegrationAuth.Storage() {
            public String load() { return prefs.getString(DEVICES, "[]"); }
            public void save(String json) { prefs.edit().putString(DEVICES, json).apply(); }
        });
        auth.onRequest = pairing -> main.post(() -> askToPair(pairing));
        hub = new CameraHub(printer::integrationCamera, System::currentTimeMillis);
    }
    static SharedPreferences prefs(Context context) { return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    /** Starts or stops the local API and the relay as set. Main thread. */
    void apply() {
        SharedPreferences prefs = prefs(context);
        boolean on = prefs.getBoolean(LOCAL, false);
        int port = prefs.getInt(PORT, IntegrationServer.DEFAULT_PORT); boolean vpn = prefs.getBoolean(VPN, false);
        if (server != null && (!on || server.port() != port || serverVpn != vpn)) stopServer();
        if (on && server == null) {
            IntegrationServer s = new IntegrationServer(new IntegrationServer.Host() {
                public JSONObject status() { Built b = built; return IntegrationStatus.aged(b.text, b.at, System.currentTimeMillis()); }
                public JSONArray cameras() { return printer.integrationCameras(); }
                public byte[] asset(String name) { return Integrations.this.asset(name); }
                public String appVersion() { return version(); }
            }, auth, hub, port, vpn);
            built = new Built(printer.integrationStatus(false).toString(), System.currentTimeMillis());
            try { s.start(); server = s; serverVpn = vpn; problem = ""; announce(port); Diagnostics.note(Diagnostics.INTEGRATIONS, "local API on, port " + port + (vpn ? ", home VPN allowed" : "")); }
            catch (IOException failed) { s.close(); problem = "Could not open port " + port + " (" + failed.getClass().getSimpleName() + "). Another app may use it: choose another port."; Diagnostics.note(Diagnostics.INTEGRATIONS, "local API could not start: " + failed.getClass().getSimpleName()); }
        }
        relay.configure(prefs.getBoolean(RELAY, false) ? loadSecret(context, RELAY_URL_KEY) : "", IntegrationRelay.secretBytes(loadSecret(context, RELAY_SECRET_KEY)),
            Math.max(15, prefs.getInt(RELAY_EVERY, 60)) * 1000L);
        main.removeCallbacks(refresh);
        if (server != null || relay.ready()) main.postDelayed(refresh, REFRESH_MS);
    }
    private void stopServer() {
        IntegrationServer s = server; server = null;
        if (s != null) { s.close(); Diagnostics.note(Diagnostics.INTEGRATIONS, "local API off"); }
        unannounce();
    }
    boolean running() { return server != null && server.running(); }
    int port() { return server != null ? server.port() : prefs(context).getInt(PORT, IntegrationServer.DEFAULT_PORT); }
    int served() { return server == null ? 0 : server.served.get(); }
    int watching() { return hub.viewers() + (server == null ? 0 : server.listeners()); }

    /** A status arrived (main thread): kept for readers, sent to event listeners and the relay. */
    void statusChanged() {
        boolean local = server != null, toRelay = relay.ready();
        if (!local && !toRelay) return;
        JSONObject status = printer.integrationStatus(false);
        built = new Built(status.toString(), System.currentTimeMillis());
        if (local) server.statusChanged();
        if (toRelay) {
            SharedPreferences prefs = prefs(context); long now = System.currentTimeMillis();
            JSONObject shared = prefs.getBoolean(RELAY_HIDE_FILE, false) ? printer.integrationStatus(true) : status;
            relay.status(shared, now);
            int minutes = prefs.getInt(RELAY_PICTURES, 0);
            JSONObject state = status.optJSONObject("state");
            if (minutes > 0 && state != null && state.optBoolean("printing") && now - relayPictureAt >= minutes * 60_000L) { relayPictureAt = now; sendPicture(); }
        }
    }
    /** A print event (main thread). */
    void event(Automation.Event e) {
        if (Automation.LAYER.equals(e.type) || Automation.VALUE.equals(e.type) || Automation.CHANGE.equals(e.type)) return;
        String words = Automation.EVENTS.containsKey(e.type) ? Automation.EVENTS.get(e.type) : e.type;
        if (server != null) server.event(IntegrationStatus.event(e.type, words, e.file, e.layer, e.total, e.progress, false, System.currentTimeMillis()));
        if (relay.ready()) {
            relay.event(IntegrationStatus.event(e.type, words, e.file, e.layer, e.total, e.progress, prefs(context).getBoolean(RELAY_HIDE_FILE, false), System.currentTimeMillis()));
            if (prefs(context).getInt(RELAY_PICTURES, 0) > 0 && (Automation.STARTED.equals(e.type) || Automation.FINISHED.equals(e.type) || Automation.STOPPED.equals(e.type))) sendPicture();
        }
    }
    private void sendPicture() {
        new Thread(() -> {
            CameraHub.Frame live = hub.latest("printer");
            byte[] jpeg = live != null && System.currentTimeMillis() - live.at < 30_000 ? live.jpeg : printer.latestPictureJpeg();
            relay.picture(jpeg);
        }, "integration-relay-picture").start();
    }
    /** The print's timelapse was made: sent to the relay when chosen. */
    void timelapseMade(File mp4) { if (relay.ready() && prefs(context).getBoolean(RELAY_TIMELAPSE, false)) relay.timelapse(mp4); }

    /** The app's own camera view is about to open the printer's camera: the hub lets go of it. */
    void appCameraOpening() { hub.yield("printer"); }
    /** A frame of the app's own camera view (any thread): outside viewers see it too. */
    void appCameraFrame(byte[] jpeg) { hub.appFrame("printer", jpeg); }
    /** A printer camera frame no older than `maxAgeMs` that the hub holds, or null. */
    byte[] freshPrinterFrame(long maxAgeMs) { CameraHub.Frame f = hub.latest("printer"); return f != null && System.currentTimeMillis() - f.at <= maxAgeMs ? f.jpeg : null; }
    /** Whether an outside viewer has the hub holding the printer's camera now. */
    boolean holdsPrinterCamera() { return hub.pulling("printer"); }

    void close() { main.removeCallbacks(refresh); stopServer(); hub.close(); relay.close(); }

    // ------------------------------------------------------------------ pairing on the phone

    /** A device asks to pair: a notification that opens the approval (it never approves by itself). */
    private void askToPair(IntegrationAuth.Pairing pairing) {
        Diagnostics.note(Diagnostics.INTEGRATIONS, "a device asked to pair (code shown on the phone)");
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("integrations", "Other apps", NotificationManager.IMPORTANCE_HIGH));
        PendingIntent open = PendingIntent.getActivity(context, 7401, new Intent(context, IntegrationsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String text = "“" + pairing.name + "” asks to watch the printer (code " + pairing.code + "). Open to approve or refuse.";
        manager.notify(PAIRING_NOTIFICATION, new Notification.Builder(context, "integrations").setSmallIcon(R.drawable.ic_launcher).setContentTitle("A device asks to watch the printer")
            .setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text)).setContentIntent(open).setAutoCancel(true).build());
    }

    // ------------------------------------------------------------------ network discovery

    private void announce(int port) {
        try {
            NsdManager nsd = context.getSystemService(NsdManager.class); if (nsd == null) return;
            NsdServiceInfo info = new NsdServiceInfo(); info.setServiceName("Link Workshop"); info.setServiceType(SERVICE_TYPE); info.setPort(port);
            info.setAttribute("api", String.valueOf(IntegrationServer.API)); info.setAttribute("path", "/api/v1");
            NsdManager.RegistrationListener listener = new NsdManager.RegistrationListener() {
                public void onRegistrationFailed(NsdServiceInfo s, int code) { Diagnostics.note(Diagnostics.INTEGRATIONS, "network announcement failed (" + code + "); apps can still use the address"); }
                public void onUnregistrationFailed(NsdServiceInfo s, int code) { }
                public void onServiceRegistered(NsdServiceInfo s) { Diagnostics.note(Diagnostics.INTEGRATIONS, "announced on the network as " + SERVICE_TYPE); }
                public void onServiceUnregistered(NsdServiceInfo s) { }
            };
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener); announced = listener;
        } catch (RuntimeException unavailable) { Diagnostics.note(Diagnostics.INTEGRATIONS, "network announcement unavailable: " + unavailable.getClass().getSimpleName()); }
    }
    private void unannounce() {
        NsdManager.RegistrationListener listener = announced; announced = null;
        if (listener == null) return;
        try { NsdManager nsd = context.getSystemService(NsdManager.class); if (nsd != null) nsd.unregisterService(listener); } catch (RuntimeException ignored) { }
    }

    // ------------------------------------------------------------------ helpers

    private byte[] asset(String name) {
        try (InputStream in = context.getAssets().open("integrations/" + name)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(); byte[] b = new byte[8192]; int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            return out.toByteArray();
        } catch (IOException missing) { return null; }
    }
    private String version() { try { return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName; } catch (Exception unknown) { return "dev"; } }
    static void saveSecret(Context context, String key, String value) throws Exception {
        CredentialStore store = new CredentialStore(context);
        if (value == null || value.isEmpty()) store.forget(key); else store.saveSecret(key, value);
    }
    static String loadSecret(Context context, String key) {
        try { CredentialStore store = new CredentialStore(context); return store.remembers(key) ? store.load(key) : ""; } catch (Exception unreadable) { return ""; }
    }
}
