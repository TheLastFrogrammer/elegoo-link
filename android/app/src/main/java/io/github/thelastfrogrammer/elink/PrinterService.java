package io.github.thelastfrogrammer.elink;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import org.json.JSONObject;
import org.json.JSONArray;
import java.io.*;
import java.util.Locale;
import java.util.*;
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
    private String host = "", code = "", serial = "";
    public String connection = "Disconnected", feedback = "Development build: printer behavior still needs hardware testing.";
    public JSONObject status = new JSONObject(), attributes = new JSONObject(), canvas;
    public JSONObject filePage = new JSONObject(), disk = new JSONObject(), history = new JSONObject();
    public String storage = "local", cameraUrl = "", fileMessage = "Refresh to browse printer files.", historyMessage = "Refresh to load print history.";
    public int fileOffset;
    public long filesAt;
    private final Set<Integer> queryBusy = new HashSet<>();
    private PrintAlerts alerts = new PrintAlerts();
    public File selectedFile;
    public String selectedName;
    public boolean importing;
    private long canvasAt, lastNotification;
    private final Runnable reconnect = this::attempt;
    private final Runnable freshness = new Runnable() {
        public void run() { if (destroyed) return; if (!fresh()) alerts.disconnected(); changed(); if (foreground) updateNotification(false); main.postDelayed(this, 2000); }
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
    public String serial() { return serial; }
    public boolean connecting() { return wanted; }
    public boolean ready() { return session != null && session.ready(); }
    public boolean fresh() { return session != null && session.fresh(); }
    public boolean uploading() { return session != null && session.uploading(); }
    public boolean canvasFresh() { return fresh() && canvas != null && System.nanoTime() - canvasAt < TimeUnit.SECONDS.toNanos(45); }
    public void connect(String host, String code, String serial) {
        new PrinterHttp(host, code);
        if (!serial.isEmpty() && !Cc2Discovery.validSerial(serial)) throw new IllegalArgumentException("Invalid serial number");
        generation++; main.removeCallbacks(reconnect); if (session != null) session.close(); session = null;
        this.host = host; this.code = code; this.serial = serial; wanted = true; retries.connected(); alerts = new PrintAlerts();
        startMonitoring(); attempt();
    }
    private void attempt() {
        if (!wanted || destroyed) return;
        final NetworkRoute route;
        try { route = NetworkRoute.local(this); }
        catch (IOException error) { failed(error.getMessage(), true); return; }
        long current = ++generation;
        resetData();
        session = new Cc2Session(host, code, new Cc2Session.Listener() {
            private void deliver(Runnable action) { main.post(() -> { if (!destroyed && generation == current) { action.run(); changed(); updateNotification(false); } }); }
            public void connection(String text, boolean registered) { deliver(() -> { connection = text; if (registered) retries.connected(); }); }
            public void status(JSONObject value) { status(value, false); }
            public void status(JSONObject value, boolean canvasUpdated) { deliver(() -> { status = value; JSONObject trays = value.optJSONObject("canvas_info"); if (canvasUpdated && trays != null) { canvas = trays; canvasAt = System.nanoTime(); } showAlert(alerts.update(value)); }); }
            public void attributes(JSONObject value) { deliver(() -> attributes = value); }
            public void canvas(JSONObject value) { deliver(() -> { canvas = value; canvasAt = System.nanoTime(); }); }
            public void result(String text) { deliver(() -> feedback = text); }
            public void uploadProgress(int percent) { deliver(() -> feedback = "Uploading " + selectedName + ": " + percent + "%"); }
            public void failure(String text, boolean retryable) { deliver(() -> failed(text, retryable)); }
            public void query(int method, JSONObject params, JSONObject result) { deliver(() -> {
                queryBusy.remove(method);
                if (method == Cc2Codec.FILES) { filePage = result; storage = params.optString("storage_media", "local"); fileOffset = params.optInt("offset"); filesAt = System.nanoTime(); fileMessage = "Files received from printer."; }
                if (method == Cc2Codec.HISTORY) { history = result; historyMessage = "History received from printer."; }
                if (method == Cc2Codec.DISK) disk = result;
                if (method == Cc2Codec.CAMERA) {
                    try { cameraUrl = FeatureData.cameraUrl(host, result.getString("url")); feedback = "Camera address received. Open Camera to view it."; }
                    catch (Exception error) { cameraUrl = ""; feedback = "Printer returned a camera URL outside the selected printer address; it was not opened."; }
                }
                if (method == Cc2Codec.DELETE) browse(storage, 0);
            }); }
            public void queryError(int method, String text) { deliver(() -> { queryBusy.remove(method); if (method == Cc2Codec.FILES) fileMessage = text; if (method == Cc2Codec.HISTORY) historyMessage = text; feedback = text; }); }
            public void uploaded(String name) { deliver(() -> { feedback = "Upload acknowledged: " + name + ". Refresh Files and choose Print setup to start it."; browse("local", 0); }); }
        }, route.http(), route.sockets(), new PrinterIdentity(serial, route.discovery()));
        connection = "Identifying printer for MQTT…"; changed(); session.connect();
    }
    private void failed(String message, boolean retryable) {
        alerts.disconnected();
        generation++; if (session != null) session.close(); session = null;
        resetData();
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
    public void cancelUpload() { if (session != null) session.cancelUpload(); }
    public boolean busy(int method) { return queryBusy.contains(method); }
    public boolean filesFresh() { return ready() && filesAt != 0 && System.nanoTime() - filesAt < TimeUnit.MINUTES.toNanos(2); }
    public void browse(String storage, int offset) { if (!ready() || busy(Cc2Codec.FILES)) return; Cc2Codec.storage(storage); if (offset < 0) throw new IllegalArgumentException("Invalid offset"); queryBusy.add(Cc2Codec.FILES); fileMessage = "Loading printer files…"; session.files(storage, offset); changed(); }
    public void loadHistory() { if (ready() && !busy(Cc2Codec.HISTORY)) { queryBusy.add(Cc2Codec.HISTORY); historyMessage = "Loading history…"; session.history(); changed(); } }
    public void loadDisk() { if (ready() && !busy(Cc2Codec.DISK)) { queryBusy.add(Cc2Codec.DISK); session.disk(); changed(); } }
    public void camera() { if (ready() && !busy(Cc2Codec.CAMERA)) { queryBusy.add(Cc2Codec.CAMERA); session.camera(); changed(); } }
    private boolean knownFile(String storage, String filename) {
        if (!filesFresh() || !this.storage.equals(storage)) return false;
        JSONArray files = filePage.optJSONArray("file_list"); if (files == null) return false;
        for (int i = 0; i < files.length(); i++) { JSONObject file = files.optJSONObject(i); if (file != null && filename.equals(file.optString("filename"))) return true; }
        return false;
    }
    public void start(String storage, String filename, boolean leveling, boolean force, boolean timelapse, String plate, JSONArray maps) {
        if (!fresh() || !Cc2Codec.idle(status) || !knownFile(storage, filename) || maps.length() > 0 && (!canvasFresh() || !FeatureData.mappings(canvas, maps))) {
            feedback = "Refresh status, files and trays before starting. The printer must be idle and mappings must refer to reported trays."; changed(); return;
        }
        session.start(storage, filename, leveling, force, timelapse, plate, maps);
    }
    public void delete(String storage, String filename) { if (fresh() && Cc2Codec.idle(status) && knownFile(storage, filename)) session.delete(storage, filename); else { feedback = "Refresh files and wait for the printer to be idle before deleting."; changed(); } }
    public void light(boolean on) { if (fresh()) session.light(on); }
    public void temperatures(int nozzle, int bed) { if (fresh() && Cc2Codec.idle(status)) session.temperatures(nozzle, bed); }
    public void fan(String name, int percent) { if (fresh()) session.fan(name, percent); }
    public void speed(int mode) { if (fresh() && Cc2Codec.canPause(status)) session.speed(mode); }
    private void resetData() {
        status = new JSONObject(); attributes = new JSONObject(); canvas = null; canvasAt = 0;
        filePage = new JSONObject(); disk = new JSONObject(); history = new JSONObject(); filesAt = 0; fileOffset = 0; cameraUrl = "";
        queryBusy.clear(); fileMessage = "Refresh to browse printer files."; historyMessage = "Refresh to load print history.";
    }
    public void disconnect() {
        wanted = false; generation++; main.removeCallbacks(reconnect);
        if (session != null) session.close(); session = null; code = "";
        resetData();
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
    private void showAlert(String text) {
        if (text.isEmpty() || !getSharedPreferences("workshop-settings", MODE_PRIVATE).getBoolean("alerts", true)) return;
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("printer-alerts", "Print completion and faults", NotificationManager.IMPORTANCE_DEFAULT));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        manager.notify(text.startsWith("Print complete") ? 2 : 3, new Notification.Builder(this, "printer-alerts").setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Link Workshop · " + host).setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text)).setContentIntent(open).setAutoCancel(true).build());
    }
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
