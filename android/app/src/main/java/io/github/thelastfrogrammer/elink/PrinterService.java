package io.github.thelastfrogrammer.elink;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import org.json.JSONObject;
import java.io.*;
import java.util.Locale;
import java.util.concurrent.*;

/** User-started foreground connection, independent of Activity instances. Main thread owns UI state. */
public final class PrinterService extends Service {
    public static final String DISCONNECT = "io.github.thelastfrogrammer.elink.DISCONNECT";
    private static final int NOTIFICATION = 1;
    private static final String CHANNEL = "printer-connection";
    public interface Observer { void changed(); }
    public final class LocalBinder extends Binder { public PrinterService service() { return PrinterService.this; } }
    private final LocalBinder binder = new LocalBinder();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService files = Executors.newSingleThreadExecutor();
    private final ReconnectPolicy retries = new ReconnectPolicy();
    private Observer observer;
    private Cc2Session session;
    private boolean wanted, foreground, destroyed;
    private long generation;
    private String host = "", code = "";
    public String connection = "Disconnected", feedback = "Development build: printer behavior still needs hardware testing.";
    public JSONObject status = new JSONObject(), attributes = new JSONObject(), canvas;
    public File selectedFile;
    public String selectedName;
    public boolean importing;
    private long canvasAt, lastNotification;
    private final Runnable reconnect = this::attempt;
    private final Runnable freshness = new Runnable() {
        public void run() { if (destroyed) return; changed(); if (foreground) updateNotification(false); main.postDelayed(this, 2000); }
    };
    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Printer connection", NotificationManager.IMPORTANCE_LOW));
        main.post(freshness);
    }
    @Override public IBinder onBind(Intent intent) { return binder; }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && DISCONNECT.equals(intent.getAction())) { disconnect(); return START_NOT_STICKY; }
        // An explicit UI action starts this service before connect(), satisfying Android's foreground deadline.
        startMonitoring(); if (!wanted) stopMonitoring(); return START_NOT_STICKY;
    }
    public void observe(Observer observer) { this.observer = observer; if (observer != null) observer.changed(); }
    public String host() { return host; }
    public String accessCode() { return code; }
    public boolean connecting() { return wanted; }
    public boolean ready() { return session != null && session.ready(); }
    public boolean fresh() { return session != null && session.fresh(); }
    public boolean uploading() { return session != null && session.uploading(); }
    public boolean canvasFresh() { return fresh() && canvas != null && System.nanoTime() - canvasAt < TimeUnit.SECONDS.toNanos(45); }
    public void connect(String host, String code) {
        new PrinterHttp(host, code);
        generation++; main.removeCallbacks(reconnect); if (session != null) session.close(); session = null;
        this.host = host; this.code = code; wanted = true; retries.connected();
        startMonitoring(); attempt();
    }
    private void attempt() {
        if (!wanted || destroyed) return;
        final NetworkRoute route;
        try { route = NetworkRoute.local(this); }
        catch (IOException error) { failed(error.getMessage(), true); return; }
        long current = ++generation;
        status = new JSONObject(); attributes = new JSONObject(); canvas = null; canvasAt = 0;
        session = new Cc2Session(host, code, new Cc2Session.Listener() {
            private void deliver(Runnable action) { main.post(() -> { if (!destroyed && generation == current) { action.run(); changed(); updateNotification(false); } }); }
            public void connection(String text, boolean registered) { deliver(() -> { connection = text; if (registered) retries.connected(); }); }
            public void status(JSONObject value) { status(value, false); }
            public void status(JSONObject value, boolean canvasUpdated) { deliver(() -> { status = value; JSONObject trays = value.optJSONObject("canvas_info"); if (canvasUpdated && trays != null) { canvas = trays; canvasAt = System.nanoTime(); } }); }
            public void attributes(JSONObject value) { deliver(() -> attributes = value); }
            public void canvas(JSONObject value) { deliver(() -> { canvas = value; canvasAt = System.nanoTime(); }); }
            public void result(String text) { deliver(() -> feedback = text); }
            public void uploadProgress(int percent) { deliver(() -> feedback = "Uploading " + selectedName + ": " + percent + "%"); }
            public void failure(String text, boolean retryable) { deliver(() -> failed(text, retryable)); }
        }, route.http(), route.sockets());
        connection = "Reading printer information…"; changed(); session.connect();
    }
    private void failed(String message, boolean retryable) {
        generation++; if (session != null) session.close(); session = null;
        status = new JSONObject(); attributes = new JSONObject(); canvas = null; canvasAt = 0;
        int delay = wanted && retryable ? retries.nextDelaySeconds() : -1;
        if (delay >= 0) {
            connection = message + "\nRetry " + retries.attempts() + "/5 in " + delay + "s. Use Disconnect to cancel.";
            main.removeCallbacks(reconnect); main.postDelayed(reconnect, delay * 1000L);
        } else {
            wanted = false; code = "";
            connection = message + (retryable ? "\nAutomatic retries stopped. Run Check connection, then reconnect." : "");
            stopMonitoring();
        }
        changed();
    }
    public void refresh() { if (session != null) session.refresh(); }
    public void command(int method) { if (session != null) session.command(method); }
    public void autoRefill(boolean enabled) { if (canvasFresh() && session != null) session.autoRefill(enabled); }
    public void upload() { if (session != null && selectedFile != null && !importing) { session.upload(selectedFile, selectedName); changed(); } }
    public void disconnect() {
        wanted = false; generation++; main.removeCallbacks(reconnect);
        if (session != null) session.close(); session = null; code = "";
        status = new JSONObject(); attributes = new JSONObject(); canvas = null; canvasAt = 0;
        connection = "Disconnected"; stopMonitoring(); changed();
    }
    public void select(Uri uri) {
        if (importing || uploading()) return;
        importing = true; feedback = "Importing selected G-code…"; changed();
        boolean persisted;
        try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); persisted = true; }
        catch (SecurityException ignored) { persisted = false; }
        final boolean releasePermission = persisted;
        files.execute(() -> {
            File local = null;
            try {
                String name = null;
                try (Cursor cursor = getContentResolver().query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
                }
                if (name == null || !name.toLowerCase(Locale.ROOT).endsWith(".gcode") || !name.matches("[A-Za-z0-9 _.-]+") || name.length() > 120)
                    throw new IOException("Choose a .gcode file with a simple filename.");
                local = File.createTempFile("upload-", ".gcode", getCacheDir());
                try (InputStream input = getContentResolver().openInputStream(uri); OutputStream output = new FileOutputStream(local)) {
                    if (input == null) throw new IOException("Cannot open selected file");
                    byte[] buffer = new byte[65536]; int count; long copied = 0;
                    while ((count = input.read(buffer)) != -1) {
                        if (Thread.currentThread().isInterrupted()) throw new IOException("File import cancelled");
                        copied += count; if (copied > 512L * 1024 * 1024) throw new IOException("Development build supports files up to 512 MiB");
                        output.write(buffer, 0, count);
                    }
                }
                if (local.length() == 0) throw new IOException("File is empty");
                File readyFile = local; String readyName = name;
                main.post(() -> {
                    if (destroyed) { readyFile.delete(); return; }
                    if (selectedFile != null) selectedFile.delete(); selectedFile = readyFile; selectedName = readyName;
                    importing = false; feedback = "File ready to upload. Uploading does not start a print."; changed();
                });
            } catch (Exception error) {
                if (local != null) local.delete();
                main.post(() -> { if (!destroyed) { importing = false; feedback = "File import failed. Choose a nonempty .gcode file (up to 512 MiB) with a simple filename."; changed(); } });
            } finally {
                if (releasePermission) try { getContentResolver().releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (SecurityException ignored) { }
            }
        });
    }
    private void changed() { if (observer != null && !destroyed) observer.changed(); }
    private Notification notification() {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent view = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent disconnect = PendingIntent.getService(this, 1, new Intent(this, PrinterService.class).setAction(DISCONNECT), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String text = !ready() ? connection.split("\n")[0] : !fresh() ? "Status stale · awaiting printer" : StatusPresentation.state(status);
        if (ready() && fresh() && !StatusPresentation.faultCodes(status).isEmpty()) text += " · Printer reports a fault";
        if (uploading()) text = feedback;
        return new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_launcher).setContentTitle("Link Workshop · " + host)
            .setContentText(text).setContentIntent(view).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(new Notification.Action.Builder(null, "Disconnect", disconnect).build()).build();
    }
    private void startMonitoring() {
        if (android.os.Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        else startForeground(NOTIFICATION, notification());
        foreground = true;
    }
    private void stopMonitoring() { if (foreground) stopForeground(STOP_FOREGROUND_REMOVE); foreground = false; stopSelf(); }
    private void updateNotification(boolean force) {
        if (!foreground) return;
        long now = System.nanoTime();
        if (!force && now - lastNotification < TimeUnit.SECONDS.toNanos(5)) return;
        lastNotification = now; getSystemService(NotificationManager.class).notify(NOTIFICATION, notification());
    }
    @Override public void onDestroy() {
        destroyed = true; observer = null; main.removeCallbacksAndMessages(null);
        if (session != null) session.close(); session = null; code = "";
        files.shutdownNow(); if (selectedFile != null) selectedFile.delete();
        if (foreground) stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy();
    }
}
