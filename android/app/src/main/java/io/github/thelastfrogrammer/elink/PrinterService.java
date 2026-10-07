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
import java.util.*;
import java.util.concurrent.*;

/** User-started foreground connection, independent of Activity instances. Main thread owns UI state. */
public final class PrinterService extends Service {
    public static final String DISCONNECT = "io.github.thelastfrogrammer.elink.DISCONNECT";
    public static final String STOP_CLOUD = "io.github.thelastfrogrammer.elink.STOP_CLOUD";
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
    private NetworkRoute route;
    private AutoCloseable routeWatch;
    private boolean remote, pinProbe;
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
    /** Copies of uploaded, sliced and downloaded G-code, for the toolpath viewer to follow prints. */
    GcodeLibrary library;
    private String viewerDownload;
    public String selectedName;
    public GcodeInspector.Report selectedReport;
    public android.graphics.Bitmap selectedThumbnail;
    public boolean importing, exporting;
    private long canvasAt, lastNotification;
    private final Runnable reconnect = this::attempt;

    // Elegoo cloud monitoring and controls, used whenever there is no local session (see CLOUD_LOGIN.md).
    private static final long CLOUD_VISIBLE_POLL_MS = 15_000, CLOUD_BACKGROUND_POLL_MS = 30_000;
    private final ScheduledExecutorService cloudWorker = Executors.newSingleThreadScheduledExecutor();
    private CloudAccountStore cloudAccounts;
    private volatile CloudApi cloudApi;
    private CloudControl cloudControl;
    private PrintAlerts cloudAlerts = new PrintAlerts();
    /** Records each print's progress for graphs (see PrintRecorder); on by default, Settings can turn it off. */
    public PrintRecorder recorder;
    private void record(JSONObject value, String source, String name) {
        if (recorder != null && getSharedPreferences("workshop-settings", MODE_PRIVATE).getBoolean("recordPrints", true))
            recorder.update(System.currentTimeMillis(), value, source, name);
    }
    private boolean cloudVisible, cloudPolling;
    public JSONObject cloudStatus = new JSONObject();
    public String cloudName = "", cloudModel = "", cloudSerial = "", cloudMessage = "";
    public int cloudOnline = -1;
    public long cloudCheckedAt;
    public boolean cloudSignedIn, cloudCommandBusy;
    private final Runnable cloudPoll = new Runnable() { public void run() { pollCloud(); } };
    private final Runnable freshness = new Runnable() {
        public void run() { if (destroyed) return; checkRoute(); if (!fresh()) alerts.disconnected(); changed(); if (foreground) updateNotification(false); main.postDelayed(this, 2000); }
    };
    @Override public void onCreate() {
        library = new GcodeLibrary(new File(getFilesDir(), "gcode-library"));
        super.onCreate();
        File[] oldCopies = getCacheDir().listFiles();
        if (oldCopies != null) for (File file : oldCopies) if (file.isFile() && (file.getName().startsWith("upload-") || file.getName().startsWith("download-")) && file.getName().endsWith(".gcode")) file.delete();
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Printer connection", NotificationManager.IMPORTANCE_LOW));
        main.post(freshness);
        cloudAccounts = new CloudAccountStore(this);
        recorder = new PrintRecorder(RecordingsActivity.directory(this));
        if (cloudBackground()) main.post(cloudPoll);
    }
    @Override public IBinder onBind(Intent intent) { return binder; }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && DISCONNECT.equals(intent.getAction())) { disconnect(); return START_NOT_STICKY; }
        if (intent != null && STOP_CLOUD.equals(intent.getAction())) {
            getSharedPreferences("workshop-settings", MODE_PRIVATE).edit().putBoolean("cloudBackground", false).apply();
            cloudSettingsChanged(); changed(); return START_NOT_STICKY;
        }
        // An explicit UI action starts this service before connect(), satisfying Android's foreground deadline.
        startMonitoring(); if (!wanted) stopMonitoring(); return START_NOT_STICKY;
    }
    public void observe(Observer observer) { this.observer = observer; if (observer != null) observer.changed(); }
    // Other screens (the G-code viewer following a print) watch alongside MainActivity's observer, and keep the
    // cloud polling at the visible rate while they are shown.
    private final List<Observer> watchers = new ArrayList<>();
    public void watch(Observer watcher) { if (!watchers.contains(watcher)) watchers.add(watcher); watcher.changed(); main.removeCallbacks(cloudPoll); main.post(cloudPoll); }
    public void unwatch(Observer watcher) { watchers.remove(watcher); }
    public String host() { return host; }
    public String accessCode() { return code; }
    public String serial() { return serial; }
    public boolean connecting() { return wanted; }
    public boolean remote() { return remote; }
    public boolean pinProbe() { return pinProbe; }
    public boolean ready() { return session != null && session.ready() && (!remote || route != null && route.available()); }
    public boolean fresh() { return ready() && session.fresh(); }
    public boolean uploading() { return session != null && session.uploading(); }
    public boolean downloading() { return session != null && session.downloading(); }
    public boolean fileBusy() { return importing || exporting || uploading() || downloading(); }
    public boolean canvasFresh() { return liveFresh() && canvas != null && System.nanoTime() - canvasAt < TimeUnit.SECONDS.toNanos(45); }
    public void connect(String host, String code, String serial) { connect(host, code, serial, false); }
    public void connect(String host, String code, String serial, boolean remote) {
        connect(host, code, serial, remote, false);
    }
    public void connect(String host, String code, String serial, boolean remote, boolean pinProbe) {
        new PrinterAuthentication(pinProbe, code); new PrinterHttp(host, pinProbe ? "" : code);
        if (!serial.isEmpty() && !Cc2Discovery.validSerial(serial)) throw new IllegalArgumentException("Invalid serial number");
        generation++; main.removeCallbacks(reconnect); clearRoute(); if (session != null) session.close(); session = null;
        this.host = host; this.code = code; this.serial = serial; this.remote = remote; this.pinProbe = pinProbe; wanted = true; retries.connected(); alerts = new PrintAlerts();
        startMonitoring(); attempt();
    }
    private void attempt() {
        if (!wanted || destroyed) return;
        final NetworkRoute selected;
        try { selected = NetworkRoute.select(this, remote); }
        catch (IOException error) { failed(error.getMessage(), true); return; }
        long current = ++generation;
        clearRoute(); route = selected;
        try { routeWatch = selected.watch(() -> main.post(() -> { if (!destroyed && route == selected && wanted) failed(new VpnRouteGuard.Unavailable().getMessage(), true); })); }
        catch (Exception error) { failed("Android could not monitor the VPN route. Enable your home VPN and reconnect.", true); return; }
        resetData();
        session = new Cc2Session(host, code, new Cc2Session.Listener() {
            private void deliver(Runnable action) { main.post(() -> { if (!destroyed && generation == current) { action.run(); changed(); updateNotification(false); } }); }
            public void connection(String text, boolean registered) { deliver(() -> { connection = registered ? "Connected" + (remote ? " through VPN" : " locally") + (pinProbe ? " · read-only PIN probe" : "") + " · " + host : text; if (registered) retries.connected(); }); }
            public void status(JSONObject value) { status(value, false); }
            public void status(JSONObject value, boolean canvasUpdated) { deliver(() -> { status = value; JSONObject trays = value.optJSONObject("canvas_info"); if (canvasUpdated && trays != null) { canvas = trays; canvasAt = System.nanoTime(); } showAlert(alerts.update(value)); record(value, "local", attributes.optString("hostname", host)); }); }
            public void attributes(JSONObject value) { deliver(() -> attributes = value); }
            public void canvas(JSONObject value) { deliver(() -> { canvas = value; canvasAt = System.nanoTime(); }); }
            public void result(String text) { deliver(() -> feedback = text); }
            public void uploadProgress(int percent) { deliver(() -> feedback = "Uploading " + selectedName + ": " + percent + "%"); }
            public void failure(String text, boolean retryable) { deliver(() -> failed(text, retryable)); }
            public void query(int method, JSONObject params, JSONObject result) { deliver(() -> handleQuery(method, params, result)); }
            public void queryError(int method, String text) { deliver(() -> handleQueryError(method, text)); }
            public void uploaded(String name) { deliver(() -> {
                feedback = "Upload acknowledged: " + name + ". Refresh Files and choose Print setup to start it."; browse("local", 0);
                File copy = selectedFile; if (copy != null) files.execute(() -> keep(copy, name));
            }); }
            public void downloadProgress(int percent) { deliver(() -> feedback = (timelapseRequested != null ? "Downloading timelapse" : "Downloading G-code") + (percent < 0 ? "… size not reported" : ": " + percent + "%")); }
            public void timelapseDownloaded(File file, String url) { deliver(() -> {
                File previous = timelapseFile; if (previous != null && !previous.equals(file)) previous.delete();
                timelapseFile = file; timelapseName = timelapseRequested == null ? "timelapse.mp4" : timelapseRequested; timelapseRequested = null;
                feedback = "Timelapse downloaded. Save it with Save timelapse… on the Files tab.";
            }); }
            public void downloaded(File file, String name) { main.post(() -> {
                if (destroyed || generation != current) { file.delete(); return; }
                importing = true; feedback = "Download received. Inspecting phone copy…"; changed();
                files.execute(() -> {
                    keep(file, name);
                    GcodeInspector.Report report = null;
                    try { report = GcodeInspector.inspect(file); } catch (Exception ignored) { }
                    GcodeInspector.Report result = report;
                    android.graphics.Bitmap preview = report == null ? null : ThumbnailDecoder.decode(report.thumbnail);
                    main.post(() -> finishFile(file, name, result, preview, "Downloaded to the phone. Save copy… keeps it in your files; the printer's copy is unchanged."));
                });
            }); }
        }, selected.http(), selected.sockets(), new PrinterIdentity(serial, selected.discovery(), new PrinterAuthentication(pinProbe, code)));
        connection = "Identifying printer for MQTT…"; changed(); session.connect();
    }
    /** Query results from the local session or the cloud land here. */
    private void handleQuery(int method, JSONObject params, JSONObject result) {
        queryBusy.remove(method);
        if (method == Cc2Codec.FILES) { filePage = result; storage = params.optString("storage_media", "local"); fileOffset = params.optInt("offset"); filesAt = System.nanoTime(); fileMessage = "Files received from printer."; }
        if (method == Cc2Codec.FILES && viewerDownload != null) {
            String wanted = viewerDownload; viewerDownload = null;
            if (knownFile("local", wanted)) download("local", wanted);
            else feedback = StatusPresentation.clean(wanted) + " is not among the first printer files listed. Download it from the Files tab instead.";
        }
        if (method == Cc2Codec.HISTORY) { history = result; historyMessage = "History received from printer."; }
        if (method == Cc2Codec.DISK) disk = result;
        if (method == Cc2Codec.THUMBNAIL) {
            String name = params.optString("file_name").replaceFirst("^/", "");
            thumbnails.put(params.optString("storage_media", "local") + "/" + name, result.optString("thumbnail"));
        }
        if (method == Cc2Codec.CANVAS && result.optJSONObject("canvas_info") != null) { canvas = result.optJSONObject("canvas_info"); canvasAt = System.nanoTime(); }
        if (method == Cc2Codec.CAMERA) {
            try { cameraUrl = FeatureData.cameraUrl(host, result.getString("url")); feedback = "Camera address received. Open Camera to view it."; }
            catch (Exception error) { cameraUrl = ""; feedback = "Printer returned a camera URL outside the selected printer address; it was not opened."; }
        }
        if (method == Cc2Codec.DELETE) browse(storage, 0);
    }
    private void handleQueryError(int method, String text) { queryBusy.remove(method); if (method == Cc2Codec.FILES) fileMessage = text; if (method == Cc2Codec.HISTORY) historyMessage = text; feedback = text; }

    // Each feature uses the local session when connected, otherwise the cloud (same printer requests, see CloudControl).
    /** Status from the local session, else from the cloud. */
    public JSONObject liveStatus() { return ready() ? status : usingCloud() ? cloudStatus : new JSONObject(); }
    public boolean liveFresh() { return fresh() || viaCloud(); }
    /** Read requests (files, history, storage) can be made. */
    public boolean canQuery() { return ready() || viaCloud(); }
    private boolean viaCloud() { return !ready() && usingCloud() && cloudFresh(); }
    private void checkRoute() { if (wanted && remote && route != null && !route.available()) failed(new VpnRouteGuard.Unavailable().getMessage(), true); }
    private void clearRoute() { AutoCloseable watcher = routeWatch; routeWatch = null; route = null; if (watcher != null) try { watcher.close(); } catch (Exception ignored) { } }
    private void failed(String message, boolean retryable) {
        retryable = retryable && !pinProbe;
        clearRoute();
        alerts.disconnected();
        generation++; if (session != null) session.close(); session = null;
        resetData();
        int delay = wanted && retryable ? retries.nextDelaySeconds() : -1;
        if (delay >= 0) {
            connection = message + "\nRetry " + retries.attempts() + "/5 in " + delay + "s. Use Disconnect to cancel.";
            main.removeCallbacks(reconnect); main.postDelayed(reconnect, delay * 1000L);
        } else {
            wanted = false; code = "";
            connection = message + (retryable ? "\nAutomatic retries stopped. Run Check connection, then reconnect." : pinProbe ? "\nPIN probe stopped; reconnect explicitly when ready. No automatic retries." : "");
            stopMonitoring();
            main.removeCallbacks(cloudPoll); main.post(cloudPoll);
        }
        changed();
    }
    public void refresh() {
        if (ready()) session.refresh();
        else if (usingCloud()) { main.removeCallbacks(cloudPoll); main.post(cloudPoll); if (viaCloud()) cloudSafe(() -> Cc2Codec.request(0, Cc2Codec.CANVAS)); }
    }
    public void command(int method) { if (fresh()) session.command(method); }
    public void autoRefill(boolean enabled) {
        if (!canvasFresh()) return;
        if (ready() && session != null) session.autoRefill(enabled); else if (viaCloud()) cloudSafe(() -> Cc2Codec.autoRefillRequest(0, enabled));
    }
    public void upload() { if (ready() && selectedFile != null && !fileBusy()) { session.upload(selectedFile, selectedName); changed(); } }
    public void cancelUpload() { if (session != null) session.cancelUpload(); }
    public void cancelDownload() { if (session != null) session.cancelDownload(); }
    /** The last downloaded timelapse video on the phone (cache), and the name to save it under. */
    public File timelapseFile; public String timelapseName; private String timelapseRequested;
    public void downloadTimelapse(String videoUrl, String taskName) {
        if (!ready() || pinProbe) { feedback = "Timelapse videos download over the local connection (LAN Only, HTTP port 80)."; changed(); return; }
        if (fileBusy()) { feedback = "Wait for the current file transfer to finish."; changed(); return; }
        String base = taskName == null ? "" : taskName.replaceFirst("(?i)\\.gcode$", "").replaceAll("[^A-Za-z0-9 _.()+-]", "_").trim();
        String name = (base.isEmpty() ? "timelapse" : base + "_timelapse") + (videoUrl.toLowerCase(Locale.ROOT).matches(".*\\.(mp4|avi|mkv|mov)$") ? videoUrl.substring(videoUrl.lastIndexOf('.')) : ".mp4");
        File directory = new File(getCacheDir(), "timelapse");
        if (!directory.isDirectory() && !directory.mkdirs()) { feedback = "Cannot use the phone's cache for the timelapse."; changed(); return; }
        try {
            File destination = new File(directory, "download-" + System.currentTimeMillis() + ".part");
            timelapseRequested = name;
            if (!session.downloadTimelapse(destination, videoUrl)) { timelapseRequested = null; return; }
            feedback = "Downloading timelapse for " + StatusPresentation.clean(taskName == null ? "this print" : taskName) + "… HTTP port 80 is required."; changed();
        } catch (IllegalArgumentException invalid) { timelapseRequested = null; feedback = invalid.getMessage(); changed(); }
    }
    public void clearTimelapse() { File file = timelapseFile; timelapseFile = null; timelapseName = null; if (file != null) file.delete(); changed(); }
    public void download(String storage, String filename) {
        if (!ready() || pinProbe) { feedback = "Downloading from the printer needs the local connection (Settings → Local connection; LAN Only on the printer, HTTP port 80)."; changed(); return; }
        if (fileBusy()) { feedback = "Wait for the current file transfer to finish."; changed(); return; }
        if (!listed(storage, filename)) { feedback = "Refresh the printer's file list, then download from it."; changed(); return; }
        File local = null;
        try {
            local = File.createTempFile("download-", ".gcode", getCacheDir());
            if (!session.download(local, storage, filename)) { local.delete(); return; }
            feedback = "Downloading " + StatusPresentation.clean(filename) + "… HTTP port 80 is required."; changed();
        } catch (Exception error) { if (local != null) local.delete(); feedback = "Could not begin download. Use a listed .gcode file and check phone storage."; changed(); }
    }
    public boolean busy(int method) { return queryBusy.contains(method); }
    public boolean filesFresh() { return canQuery() && filesAt != 0 && System.nanoTime() - filesAt < TimeUnit.MINUTES.toNanos(2); }
    public void browse(String storage, int offset) {
        if (!canQuery() || busy(Cc2Codec.FILES)) return; Cc2Codec.storage(storage); if (offset < 0) throw new IllegalArgumentException("Invalid offset");
        queryBusy.add(Cc2Codec.FILES); fileMessage = "Loading printer files…";
        if (ready()) session.files(storage, offset); else cloudSafe(() -> Cc2Codec.filesRequest(0, storage, offset));
        changed();
    }
    public void loadHistory() {
        if (!canQuery() || busy(Cc2Codec.HISTORY)) return; queryBusy.add(Cc2Codec.HISTORY); historyMessage = "Loading history…";
        if (ready()) session.history(); else cloudSafe(() -> Cc2Codec.request(0, Cc2Codec.HISTORY));
        changed();
    }
    public void loadDisk() {
        if (!canQuery() || busy(Cc2Codec.DISK)) return; queryBusy.add(Cc2Codec.DISK);
        if (ready()) session.disk(); else cloudSafe(() -> Cc2Codec.request(0, Cc2Codec.DISK));
        changed();
    }
    public void camera() { if (ready() && !busy(Cc2Codec.CAMERA)) { queryBusy.add(Cc2Codec.CAMERA); session.camera(); changed(); } }
    private boolean knownFile(String storage, String filename) {
        return filesFresh() && listed(storage, filename);
    }
    /** In the last file list received for that storage, however old: downloading only reads, so it needs no fresh list. */
    private boolean listed(String storage, String filename) {
        if (filesAt == 0 || !this.storage.equals(storage)) return false;
        JSONArray files = filePage.optJSONArray("file_list"); if (files == null) return false;
        for (int i = 0; i < files.length(); i++) { JSONObject file = files.optJSONObject(i); if (file != null && filename.equals(file.optString("filename"))) return true; }
        return false;
    }
    /** Downloads the printer file being printed for the toolpath viewer, listing the printer's files first if needed. */
    public void downloadForViewer(String filename) {
        if (!ready() || pinProbe) { feedback = "Downloading from the printer needs the local connection (LAN Only, HTTP port 80)."; changed(); return; }
        if (fileBusy()) { feedback = "Wait for the current file transfer to finish."; changed(); return; }
        if (knownFile("local", filename)) { download("local", filename); return; }
        viewerDownload = filename; feedback = "Looking for " + StatusPresentation.clean(filename) + " on the printer…"; browse("local", 0); changed();
    }
    /** Keeps a copy in the viewer's library; failures only cost the viewer that file. */
    void keep(File file, String name) {
        try { if (library != null) library.put(file, name); } catch (IOException ignored) { }
    }
    public void start(String storage, String filename, boolean leveling, boolean force, boolean timelapse, String plate, JSONArray maps) {
        if (!liveFresh() || !Cc2Codec.idle(liveStatus()) || !knownFile(storage, filename) || maps.length() > 0 && (!canvasFresh() || !FeatureData.mappings(canvas, maps))) {
            feedback = "Refresh status, files and trays before starting. The printer must be idle and mappings must refer to reported trays."; changed(); return;
        }
        if (ready()) session.start(storage, filename, leveling, force, timelapse, plate, maps);
        else cloudSafe(() -> Cc2Codec.startRequest(0, storage, filename, leveling, force, timelapse, plate, maps));
    }
    public void delete(String storage, String filename) {
        if (!liveFresh() || !Cc2Codec.idle(liveStatus()) || !knownFile(storage, filename)) { feedback = "Refresh files and wait for the printer to be idle before deleting."; changed(); return; }
        if (ready()) session.delete(storage, filename); else cloudSafe(() -> Cc2Codec.deleteRequest(0, storage, filename));
    }
    public void light(boolean on) { if (fresh()) session.light(on); else if (viaCloud()) cloudSafe(() -> Cc2Codec.lightRequest(0, on)); }
    /** Maintenance and motion commands (see Cc2Codec), locally or through the cloud, with the same state checks as the local session. */
    public void maintenance(JSONObject request) {
        int method = request.optInt("method", -1);
        JSONObject current = liveStatus();
        if (!liveFresh() || !Cc2Codec.maintenance(method)) { feedback = "Refresh status first."; changed(); return; }
        if (method != Cc2Codec.URGENT_STOP && (!Cc2Codec.idle(current) || !StatusPresentation.faultCodes(current).isEmpty())) { feedback = "The printer must be idle without faults."; changed(); return; }
        if (method == Cc2Codec.MOVE && !Cc2Codec.homed(current, request.optJSONObject("params").optString("axes"))) { feedback = "Home that axis before moving it."; changed(); return; }
        if (fresh()) session.request(request); else cloudSafe(() -> request);
    }
    /** Thumbnail of a printer file, delivered to thumbnails by name. */
    public final Map<String, String> thumbnails = new HashMap<>();
    public void thumbnail(String storage, String filename) {
        if (!canQuery() || busy(Cc2Codec.THUMBNAIL) || thumbnails.containsKey(storage + "/" + filename)) return;
        queryBusy.add(Cc2Codec.THUMBNAIL);
        try {
            JSONObject request = Cc2Codec.thumbnailRequest(0, storage, filename);
            if (ready()) session.request(request); else cloudSafe(() -> request);
        } catch (Exception error) { queryBusy.remove(Cc2Codec.THUMBNAIL); }
    }
    public void temperatures(int nozzle, int bed) {
        if (!Cc2Codec.idle(liveStatus())) return;
        if (fresh()) session.temperatures(nozzle, bed); else if (viaCloud()) cloudSafe(() -> Cc2Codec.temperatureRequest(0, nozzle, bed));
    }
    public void fan(String name, int percent) { if (fresh()) session.fan(name, percent); else if (viaCloud()) cloudSafe(() -> Cc2Codec.fanRequest(0, name, percent)); }
    public void speed(int mode) {
        if (!Cc2Codec.canPause(liveStatus())) return;
        if (fresh()) session.speed(mode); else if (viaCloud()) cloudSafe(() -> Cc2Codec.speedRequest(0, mode));
    }
    private void resetData() {
        status = new JSONObject(); attributes = new JSONObject(); canvas = null; canvasAt = 0;
        filePage = new JSONObject(); disk = new JSONObject(); history = new JSONObject(); filesAt = 0; fileOffset = 0; cameraUrl = "";
        queryBusy.clear(); fileMessage = "Refresh to browse printer files."; historyMessage = "Refresh to load print history.";
    }
    public void disconnect() {
        wanted = false; generation++; main.removeCallbacks(reconnect); clearRoute();
        if (session != null) session.close(); session = null; code = "";
        resetData();
        connection = "Disconnected"; stopMonitoring(); changed();
        main.removeCallbacks(cloudPoll); main.post(cloudPoll);
    }
    public void select(Uri uri) {
        if (fileBusy()) return;
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
                File readyFile = local; String readyName = name; GcodeInspector.Report report = GcodeInspector.inspect(local);
                android.graphics.Bitmap preview = ThumbnailDecoder.decode(report.thumbnail);
                main.post(() -> finishFile(readyFile, readyName, report, preview, "Phone copy inspected. Uploading does not start a print."));
            } catch (Exception error) {
                if (local != null) local.delete();
                main.post(() -> { if (!destroyed) { importing = false; feedback = "File import failed. Choose a nonempty .gcode file (up to 512 MiB) with a simple filename."; changed(); } });
            } finally {
                if (releasePermission) try { getContentResolver().releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (SecurityException ignored) { }
            }
        });
    }
    /** Takes G-code sliced on this phone into the workspace, as select() does for a chosen file. */
    public void selectSliced(File source, String name) {
        if (fileBusy()) return;
        importing = true; feedback = "Inspecting sliced G-code…"; changed();
        files.execute(() -> {
            File local = null;
            try {
                local = File.createTempFile("upload-", ".gcode", getCacheDir());
                try (InputStream input = new FileInputStream(source); OutputStream output = new FileOutputStream(local)) {
                    byte[] buffer = new byte[65536]; int count;
                    while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                }
                File readyFile = local; GcodeInspector.Report report = GcodeInspector.inspect(local);
                android.graphics.Bitmap preview = ThumbnailDecoder.decode(report.thumbnail);
                main.post(() -> finishFile(readyFile, name, report, preview, "Sliced on this phone. Uploading does not start a print."));
            } catch (Exception error) {
                if (local != null) local.delete();
                main.post(() -> { if (!destroyed) { importing = false; feedback = "The sliced G-code could not be read."; changed(); } });
            }
        });
    }
    private void finishFile(File file, String name, GcodeInspector.Report report, android.graphics.Bitmap preview, String text) {
        if (destroyed) { file.delete(); return; }
        if (selectedFile != null) selectedFile.delete(); selectedFile = file; selectedName = name; selectedReport = report;
        selectedThumbnail = preview;
        importing = false; feedback = text; changed();
    }
    public void clearPhoneCopy() {
        if (fileBusy()) return;
        if (selectedFile != null) selectedFile.delete(); selectedFile = null; selectedName = null; selectedReport = null;
        selectedThumbnail = null;
        feedback = "Phone cache copy removed. Original documents and printer files are unchanged."; changed();
    }
    public void exportPhoneCopy(Uri uri, String expectedHash) {
        if (fileBusy() || selectedFile == null || selectedReport == null || !selectedReport.sha256.equals(expectedHash)) { feedback = "Phone copy changed or is unavailable. Choose Save phone copy again."; changed(); return; }
        File file = selectedFile; exporting = true; feedback = "Saving phone copy…"; changed();
        files.execute(() -> {
            String text;
            try (InputStream input = new FileInputStream(file); OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
                if (output == null) throw new IOException("Document unavailable");
                byte[] buffer = new byte[65536]; int count; long copied = 0;
                while ((count = input.read(buffer)) != -1) { if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException(); output.write(buffer, 0, count); copied += count; }
                if (copied != file.length()) throw new IOException("Phone copy changed");
                text = "Phone copy saved. Printer files are unchanged.";
            } catch (Exception error) { text = "Phone copy could not be saved. A partial destination document may remain; choose Save again."; }
            String done = text; main.post(() -> { if (!destroyed) { exporting = false; feedback = done; changed(); } });
        });
    }
    private void changed() {
        if (destroyed) return;
        if (observer != null) observer.changed();
        for (Observer watcher : new ArrayList<>(watchers)) watcher.changed();
    }

    /** Monitoring through the cloud while the app is visible; background watching is a separate opt-in setting. */
    public void cloudVisible(boolean visible) { cloudVisible = visible; if (visible) { main.removeCallbacks(cloudPoll); main.post(cloudPoll); } }
    public boolean cloudBackground() { return getSharedPreferences("workshop-settings", MODE_PRIVATE).getBoolean("cloudBackground", false) && cloudAccountPresent(); }
    /** Called after the setting changes or the account changes, while the app is visible. */
    public void cloudSettingsChanged() {
        cloudApi = null;
        if (cloudBackground() && !wanted) startMonitoring();
        else if (!wanted && foreground) { stopForeground(STOP_FOREGROUND_REMOVE); foreground = false; stopSelf(); }
        main.removeCallbacks(cloudPoll); main.post(cloudPoll);
    }
    private boolean cloudAccountPresent() { try { return cloudAccounts != null && cloudAccounts.load() != null; } catch (Exception error) { return false; } }
    /** A successful cloud read in the last minute, with the printer online. */
    public boolean cloudFresh() { return cloudOnline == 1 && cloudCheckedAt != 0 && System.currentTimeMillis() - cloudCheckedAt < 60_000 && cloudStatus.length() > 0; }
    /** Cloud data is shown only when there is no local session. */
    public boolean usingCloud() { return !wanted && cloudSignedIn && !cloudSerial.isEmpty(); }

    private void pollCloud() {
        main.removeCallbacks(cloudPoll);
        if (destroyed) return;
        boolean background = cloudBackground();
        boolean visible = cloudVisible || !watchers.isEmpty();
        if (wanted || !(visible || background)) { cloudPolling = false; stopLive(); return; }
        cloudPolling = true;
        // With live pushes, polling is only a consistency check.
        main.postDelayed(cloudPoll, cloudLiveOn ? 60_000 : visible ? CLOUD_VISIBLE_POLL_MS : CLOUD_BACKGROUND_POLL_MS);
        cloudWorker.execute(() -> {
            CloudLogin.Account account;
            try { account = cloudAccounts.load(); } catch (Exception error) { account = null; }
            if (account == null) { main.post(() -> { cloudSignedIn = false; cloudSerial = ""; cloudStatus = new JSONObject(); cloudMessage = "Sign in with Elegoo in Settings to monitor through the cloud."; changed(); }); return; }
            CloudApi api = cloudApi;
            if (api == null || !api.account().accessToken.equals(account.accessToken)) {
                api = new CloudApi(cloudAccounts.china(), account, CloudApi.agent(this), CloudApi::https); api.language(Locale.getDefault().getLanguage()); cloudApi = api;
            }
            String preferred = getSharedPreferences("workshop-settings", MODE_PRIVATE).getString("cloudSerial", "");
            CloudApi.Device device = null; int online = -1; CloudApi.Snapshot snapshot = null; String message = "";
            CloudLogin.Account before = api.account();
            try {
                List<CloudApi.Device> devices = api.devices();
                for (CloudApi.Device candidate : devices) if (candidate.serial.equals(preferred)) device = candidate;
                if (device == null && !devices.isEmpty()) device = devices.get(0);
                if (device == null) message = "No printers are bound to this Elegoo account.";
                else { online = api.online(device.serial); snapshot = api.status(device.serial); startLive(api); }
            } catch (Exception error) { message = error.getMessage() == null ? "Could not reach the Elegoo cloud." : error.getMessage(); }
            api.takeTrace();
            if (api.account() != before) try { if (cloudAccounts.load() != null) cloudAccounts.save(api.account()); } catch (Exception ignored) { }
            CloudApi.Device found = device; int reportedOnline = online; CloudApi.Snapshot reported = snapshot; String text = message;
            main.post(() -> {
                if (destroyed) return;
                cloudSignedIn = true; cloudMessage = text;
                if (found != null) {
                    if (!found.serial.equals(cloudSerial)) cloudAlerts = new PrintAlerts();
                    cloudName = found.name; cloudModel = found.model; cloudSerial = found.serial; cloudOnline = reportedOnline;
                }
                if (reported != null) {
                    cloudStatus = reported.status; cloudCheckedAt = System.currentTimeMillis();
                    // Local alerts take precedence while connected locally.
                    if (!wanted && reportedOnline == 1) { showAlert(cloudAlerts.update(reported.status), cloudName); record(reported.status, "cloud", cloudName); }
                } else if (found == null) { cloudSerial = ""; cloudStatus = new JSONObject(); }
                changed(); updateNotification(false);
            });
        });
    }

    // Live pushes over Elegoo's cloud MQTT (CloudLive); attempted at most every 5 minutes while the cloud is in use.
    private CloudLive cloudLive;
    public volatile boolean cloudLiveOn;
    public String cloudLiveState = "";
    private long cloudLiveAttempt;
    /** Runs on cloudWorker after a successful poll. */
    private void startLive(CloudApi api) {
        if (cloudLive != null && cloudLive.connected()) return;
        long now = System.currentTimeMillis(); if (now - cloudLiveAttempt < 5 * 60_000) return; cloudLiveAttempt = now;
        if (cloudLive == null) cloudLive = new CloudLive(new CloudLive.Listener() {
            public void delta(String serial, JSONObject partial) { main.post(() -> {
                if (destroyed || wanted || !serial.equals(cloudSerial) || cloudStatus.length() == 0) return;
                try {
                    JSONObject merged = new JSONObject(cloudStatus.toString()); Cc2Codec.merge(merged, partial);
                    cloudStatus = merged; cloudCheckedAt = System.currentTimeMillis();
                    if (cloudOnline == 1) { showAlert(cloudAlerts.update(merged), cloudName); record(merged, "cloud", cloudName); }
                    changed(); updateNotification(false);
                } catch (Exception ignored) { }
            }); }
            public void online(String serial, boolean online) { main.post(() -> { if (serial.equals(cloudSerial)) { cloudOnline = online ? 1 : 0; changed(); } }); }
            public void state(String text, boolean live) { main.post(() -> { cloudLiveOn = live; cloudLiveState = text; changed(); }); }
        });
        try { cloudLive.connect(api.mqttCredential(CloudLive.clientId(api.account().userId))); }
        catch (Exception error) { main.post(() -> { cloudLiveOn = false; cloudLiveState = "Live updates unavailable (" + (error.getMessage() == null ? "no credentials" : error.getMessage()) + "); using periodic checks."; changed(); }); }
    }
    private void stopLive() {
        CloudLive live = cloudLive; cloudLive = null; cloudLiveOn = false; cloudLiveAttempt = 0;
        if (live != null) cloudWorker.execute(live::close);
    }

    /** Sends one request through the cloud. Only requests CloudControl allows, with fresh cloud status; queries feed handleQuery. */
    public void cloudCommand(JSONObject request) {
        int method = request.optInt("method", -1);
        boolean query = Cc2Codec.isQuery(method) || method == Cc2Codec.CANVAS;
        CloudApi api = cloudApi;
        if (!usingCloud() || !cloudFresh() || api == null) { queryBusy.remove(method); return; }
        if (cloudCommandBusy) {
            queryBusy.remove(method);
            if (method != Cc2Codec.CANVAS) { feedback = "Waiting for the previous cloud command to finish."; changed(); }
            return;
        }
        if (cloudControl == null) cloudControl = new CloudControl(() -> {
            CloudApi current = cloudApi; if (current == null) throw new IOException("Sign in with Elegoo again.");
            return current.agoraCredential();
        }, AgoraLink::new, cloudWorker);
        cloudCommandBusy = true; if (!query) feedback = "Sending through the Elegoo cloud…"; changed();
        JSONObject params = request.optJSONObject("params") == null ? new JSONObject() : request.optJSONObject("params");
        cloudControl.send(cloudSerial, request, new CloudControl.Reply() {
            public void done(boolean acknowledged, String text) { done(acknowledged, text, new JSONObject()); }
            public void done(boolean acknowledged, String text, JSONObject result) {
                main.post(() -> {
                    cloudCommandBusy = false;
                    if (query) { if (acknowledged) handleQuery(method, params, result); else handleQueryError(method, text); changed(); return; }
                    feedback = text; changed();
                    if (method == Cc2Codec.DELETE && acknowledged) handleQuery(method, params, result);
                    // Refresh status soon so the result shows; nothing is retried.
                    main.removeCallbacks(cloudPoll); main.postDelayed(cloudPoll, 2000);
                });
            }
        });
    }
    public void cloudPause() { cloudCommandSafe(() -> Cc2Codec.request(0, Cc2Codec.PAUSE)); }
    public void cloudResume() { cloudCommandSafe(() -> Cc2Codec.request(0, Cc2Codec.RESUME)); }
    public void cloudStop() { cloudCommandSafe(() -> Cc2Codec.request(0, Cc2Codec.STOP)); }
    public void cloudLight(boolean on) { cloudCommandSafe(() -> Cc2Codec.lightRequest(0, on)); }
    private interface RequestBuilder { JSONObject build() throws Exception; }
    private void cloudCommandSafe(RequestBuilder builder) { cloudSafe(builder); }
    private void cloudSafe(RequestBuilder builder) { try { cloudCommand(builder.build()); } catch (Exception error) { feedback = "Could not prepare the cloud command."; changed(); } }
    private void showAlert(String text) { showAlert(text, host); }
    private void showAlert(String text, String title) {
        if (text.isEmpty() || !getSharedPreferences("workshop-settings", MODE_PRIVATE).getBoolean("alerts", true)) return;
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("printer-alerts", "Print completion and faults", NotificationManager.IMPORTANCE_DEFAULT));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        manager.notify(text.startsWith("Print complete") ? 2 : 3, new Notification.Builder(this, "printer-alerts").setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Link Workshop · " + StatusPresentation.clean(title)).setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text)).setContentIntent(open).setAutoCancel(true).build());
    }
    private Notification notification() {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent view = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent disconnect = PendingIntent.getService(this, 1, new Intent(this, PrinterService.class).setAction(DISCONNECT), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String text = !ready() ? connection.split("\n")[0] : !fresh() ? "Status stale · awaiting printer" : StatusPresentation.state(status);
        if (!wanted && cloudFresh()) text = "Cloud · " + StatusPresentation.overview(cloudStatus).split("\n")[0];
        if (ready() && fresh() && !StatusPresentation.faultCodes(status).isEmpty()) text += " · Printer reports a fault";
        if (uploading()) text = feedback;
        String title = wanted || cloudName.isEmpty() ? host : StatusPresentation.clean(cloudName);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_launcher).setContentTitle("Link Workshop · " + title)
            .setContentText(text).setContentIntent(view).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(wanted ? new Notification.Action.Builder(null, "Disconnect", disconnect).build()
                : new Notification.Action.Builder(null, "Stop watching", PendingIntent.getService(this, 2, new Intent(this, PrinterService.class).setAction(STOP_CLOUD), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)).build()).build();
    }
    private void startMonitoring() {
        if (android.os.Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        else startForeground(NOTIFICATION, notification());
        foreground = true;
    }
    private void stopMonitoring() {
        if (cloudBackground()) { updateNotification(true); return; }
        if (foreground) stopForeground(STOP_FOREGROUND_REMOVE); foreground = false; stopSelf();
    }
    private void updateNotification(boolean force) {
        if (!foreground) return;
        long now = System.nanoTime();
        if (!force && now - lastNotification < TimeUnit.SECONDS.toNanos(5)) return;
        lastNotification = now; getSystemService(NotificationManager.class).notify(NOTIFICATION, notification());
    }
    @Override public void onDestroy() {
        destroyed = true; observer = null; watchers.clear(); clearRoute(); main.removeCallbacksAndMessages(null);
        if (cloudControl != null) cloudControl.close(); CloudLive live = cloudLive; if (live != null) cloudWorker.execute(live::close); cloudWorker.shutdown();
        if (session != null) session.close(); session = null; code = "";
        files.shutdownNow(); if (selectedFile != null) selectedFile.delete();
        if (foreground) stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy();
    }
}
