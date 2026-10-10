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
    public String connection = "Not connected", feedback = "";
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
    private CloudFileMemory cloudFiles;
    private volatile CloudApi cloudApi;
    private CloudControl cloudControl;
    private PrintAlerts cloudAlerts = new PrintAlerts();
    /** Records each print's progress for graphs (see PrintRecorder); on by default, Settings can turn it off. */
    public PrintRecorder recorder;
    private void record(JSONObject value, String source, String name) {
        if (recorder != null && getSharedPreferences("workshop-settings", MODE_PRIVATE).getBoolean("recordPrints", true))
            recorder.update(System.currentTimeMillis(), SystemClock.elapsedRealtime(), value, source, name);
    }
    private boolean cloudVisible, cloudPolling;
    public JSONObject cloudStatus = new JSONObject();
    public String cloudName = "", cloudModel = "", cloudSerial = "", cloudMessage = "";
    public int cloudOnline = -1;
    public long cloudCheckedAt;
    /** When the printer itself last reported (epoch ms): the cloud snapshot's newest field time, or the arrival of a live push. 0 = unknown. */
    public long cloudReportedAt;
    /** The online endpoint or a live online push said "online" at the last check. */
    public boolean cloudOnlineSignal;
    static final long REPORT_RECENT_MS = 120_000;
    public boolean cloudSignedIn;
    /** A cloud command is waiting for its reply or for its turn to be sent (derived from CloudControl, so it cannot drift). */
    public boolean cloudCommandBusy() { CloudControl control = cloudControl; return control != null && control.outstanding() > 0; }
    private final Runnable cloudPoll = new Runnable() { public void run() { pollCloud(); } };
    private final Runnable freshness = new Runnable() {
        public void run() { if (destroyed) return; checkRoute(); if (!fresh()) alerts.disconnected(); changed(); if (foreground) updateNotification(false); main.postDelayed(this, 2000); }
    };
    @Override public void onCreate() {
        library = new GcodeLibrary(new File(getFilesDir(), "gcode-library"));
        super.onCreate();
        SliceStore.sweepCache(this);
        File[] oldCopies = getCacheDir().listFiles();
        if (oldCopies != null) for (File file : oldCopies) if (file.isFile() && (file.getName().startsWith("upload-") || file.getName().startsWith("download-")) && file.getName().endsWith(".gcode")) file.delete();
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Printer connection", NotificationManager.IMPORTANCE_LOW));
        main.post(freshness);
        cloudAccounts = new CloudAccountStore(this);
        cloudFiles = new CloudFileMemory(this);
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
    public boolean uploading() { return session != null && session.uploading() || cloudUpload != null; }
    public boolean downloading() { return session != null && session.downloading(); }
    public boolean fileBusy() { return importing || exporting || uploading() || downloading() || directDownloading; }
    private volatile boolean directDownloading;
    private volatile PrinterHttp directHttp;
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
            public void uploadProgress(int percent) { deliver(() -> feedback = "Uploading " + (queuedName != null ? queuedName : selectedName) + ": " + percent + "%"); }
            public void uploadFailed(String name) { deliver(() -> { queueFailed(); loadDisk(); }); }
            public void failure(String text, boolean retryable) { deliver(() -> failed(text, retryable)); }
            public void query(int method, JSONObject params, JSONObject result) { deliver(() -> handleQuery(method, params, result)); }
            public void queryError(int method, String text) { deliver(() -> handleQueryError(method, text)); }
            public void uploaded(String name) { deliver(() -> uploadedFile(name, "Upload acknowledged: " + name + ". Refresh Files and choose Print setup to start it.")); }
            public void downloadProgress(int percent) { deliver(() -> feedback = (timelapseRequested != null ? "Downloading timelapse" : "Downloading G-code") + (percent < 0 ? "… size not reported" : ": " + percent + "%")); }
            public void timelapseDownloaded(File file, String url) { deliver(() -> {
                File previous = timelapseFile; if (previous != null && !previous.equals(file)) previous.delete();
                timelapseFile = file; timelapseName = timelapseRequested == null ? "timelapse.mp4" : timelapseRequested; timelapseRequested = null;
                feedback = "Timelapse downloaded. Save it with Save to phone… on the Files tab.";
            }); }
            public void downloaded(File file, String name) { main.post(() -> {
                if (destroyed || generation != current) { file.delete(); return; }
                received(file, name);
            }); }
        }, selected.http(), selected.sockets(), new PrinterIdentity(serial, selected.discovery(), new PrinterAuthentication(pinProbe, code)));
        connection = "Identifying printer for MQTT…"; changed(); session.connect();
    }
    /** Query results from the local session or the cloud land here. */
    private void handleQuery(int method, JSONObject params, JSONObject result) {
        queryBusy.remove(method);
        if (method == Cc2Codec.FILES) {
            filePage = result; storage = params.optString("storage_media", "local"); fileOffset = params.optInt("offset"); filesAt = System.nanoTime(); fileMessage = "Files received from printer.";
            org.json.JSONArray list = result.optJSONArray("file_list");
            // Field names only (never file names): an empty or differently shaped answer shows what the printer sent instead.
            Diagnostics.note(Diagnostics.FILES, "file list (" + storage + ", offset " + fileOffset + "): " + (list == null ? "no file_list; fields " + CloudApi.shape(result) : list.length() + " entr" + (list.length() == 1 ? "y" : "ies") + ", total " + result.optInt("total", -1)));
        }
        if (method == Cc2Codec.FILES && viewerDownload != null) {
            String wanted = viewerDownload; viewerDownload = null;
            if (knownFile("local", wanted)) download("local", wanted);
            else feedback = StatusPresentation.clean(wanted) + " is not among the first printer files listed. Download it from the Files tab instead.";
        }
        if (method == Cc2Codec.HISTORY) { history = result; historyMessage = "History received from printer."; }
        if (method == Cc2Codec.HISTORY_DETAIL) {
            String task = params.optString("task_id");
            historyDetails.put(task, result); historyDetailMessages.remove(task);
            Diagnostics.note(Diagnostics.FILES, "print history detail fields: " + CloudApi.shape(result));
        }
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
    private void handleQueryError(int method, String text) {
        queryBusy.remove(method);
        if (method == Cc2Codec.HISTORY_DETAIL) { historyDetailMessages.put(lastDetailTask, "The printer did not give more details for this print (" + StatusPresentation.clean(text) + ")"); changed(); return; }
        if (method == Cc2Codec.FILES) { fileMessage = text; Diagnostics.note(Diagnostics.FILES, "file list refused: " + StatusPresentation.clean(text)); }
        if (method == Cc2Codec.HISTORY) historyMessage = text; feedback = text;
    }
    /** What the printer answered to the detail query (1037), by history task id, and why it did not where it could not. */
    public final Map<String, JSONObject> historyDetails = new HashMap<>();
    public final Map<String, String> historyDetailMessages = new HashMap<>();
    private String lastDetailTask = "";
    /** Asks the printer for more about one history entry (a read, locally or through the cloud). */
    public void historyDetail(String taskId) {
        if (!canQuery() || busy(Cc2Codec.HISTORY_DETAIL) || taskId == null || historyDetails.containsKey(taskId)) return;
        try {
            JSONObject request = Cc2Codec.historyDetailRequest(0, taskId);
            lastDetailTask = taskId; queryBusy.add(Cc2Codec.HISTORY_DETAIL);
            if (ready()) session.request(request); else cloudSafe(() -> request);
        } catch (Exception invalid) { queryBusy.remove(Cc2Codec.HISTORY_DETAIL); }
    }

    // Each feature uses the local session when connected, otherwise the cloud (same printer requests, see CloudControl).
    /** Status from the local session, else from the cloud. */
    public JSONObject liveStatus() { return ready() ? status : usingCloud() ? cloudStatus : new JSONObject(); }
    public boolean liveFresh() { return fresh() || viaCloud(); }
    /** Read requests (files, history, storage) can be made. */
    public boolean canQuery() { return ready() || viaCloud(); }
    private boolean viaCloud() { return !ready() && usingCloud() && cloudFresh(); }
    private void checkRoute() { if (wanted && remote && route != null && !route.available()) failed(new VpnRouteGuard.Unavailable().getMessage(), true); }
    private void clearRoute() { AutoCloseable watcher = routeWatch; routeWatch = null; route = null; if (watcher != null) try { watcher.close(); } catch (Exception ignored) { } }
    static final String COMMAND_UNKNOWN = "Connection lost with a command pending. It may or may not have reached the printer; commands are never replayed. Check the printer's status after reconnecting.";
    static final String LOST_CONTACT = "Lost contact with the printer during a print";
    static final String UPLOAD_LOST = "Upload stopped: connection lost. A partial file may remain on the printer.";
    private void failed(String message, boolean retryable) {
        retryable = retryable && !pinProbe;
        // A print was running when contact was lost: say so now, since the notification below goes away if retries end.
        JSONObject lastMachine = status.optJSONObject("machine_status");
        if (wanted && lastMachine != null && lastMachine.optInt("status", -1) == 2 && !pinProbe) showAlert(LOST_CONTACT);
        boolean lostUpload = session != null && session.uploading() || queuedName != null && cloudUpload == null;
        boolean lostCommand = session != null && session.commandPending();
        if (lostUpload || lostCommand) feedback = (lostUpload ? UPLOAD_LOST + " " : "") + (lostCommand ? COMMAND_UNKNOWN : "");
        if (lostUpload) queueFailed();
        clearRoute();
        alerts.disconnected();
        generation++; if (session != null) session.close(); session = null;
        resetData();
        int delay = wanted && retryable ? retries.nextDelaySeconds() : -1;
        if (delay >= 0) {
            connection = message + "\n" + retries.describe(delay);
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
    // Files sent from the Slice screen, one after another.
    private final java.util.List<Object[]> uploadQueue = new java.util.ArrayList<>();
    private File queuedFile; private String queuedName; private int uploadedCount;
    /** Files can be sent: over the local connection, or through the Elegoo cloud while watching that way. */
    public boolean canUpload() { return ready() ? !pinProbe : cloudUploadReady(); }
    /** No local session, fresh cloud status and a signed-in account: files go through Elegoo's storage (CloudUpload). */
    public boolean cloudUploadReady() { return !ready() && viaCloud() && cloudApi != null; }
    /** Uploads the files under the given printer names, in order: locally when connected, otherwise through the cloud. */
    /** True when `name` is the file the printer is printing right now: replacing it mid-print could damage the print. */
    public boolean replacesActivePrint(String name) {
        JSONObject current = liveStatus();
        if (name == null || current.length() == 0 || Cc2Codec.idle(current)) return false;
        JSONObject print = current.optJSONObject("print_status");
        String active = print == null ? "" : print.optString("filename", "").replaceFirst("^/+", "");
        return !active.isEmpty() && active.equals(name.replaceFirst("^/+", ""));
    }
    /** The name is in the last file list received from the printer (internal storage). */
    public boolean nameOnPrinter(String name) { return name != null && listed("local", name); }
    public boolean uploadFiles(java.util.List<File> sources, java.util.List<String> names) {
        for (String name : names) if (replacesActivePrint(name)) { feedback = StatusPresentation.clean(name) + " is the file being printed. It cannot be replaced until the print ends."; changed(); return false; }
        if (!canUpload()) { Diagnostics.note(Diagnostics.FILES, "upload not started: local " + (ready() ? (pinProbe ? "read-only PIN probe" : "ready") : "not connected") + ", cloud " + (!usingCloud() ? "not in use" : "online=" + cloudOnline + ", status age " + (cloudCheckedAt == 0 ? "none" : ((System.currentTimeMillis() - cloudCheckedAt) / 1000) + " s") + (cloudApi == null ? ", no account session" : ""))); feedback = "Uploading needs the local connection, or the printer watched through the Elegoo cloud (Settings)."; changed(); return false; }
        for (int i = 0; i < sources.size(); i++) uploadQueue.add(new Object[] {sources.get(i), names.get(i)});
        uploadedCount = 0; pumpUploads(); return true;
    }
    public boolean uploadsPending() { return queuedName != null || !uploadQueue.isEmpty(); }
    private void pumpUploads() {
        if (uploadQueue.isEmpty() || queuedName != null || destroyed) return;
        if (!canUpload()) { uploadQueue.clear(); feedback = "Connection lost; uploads stopped."; changed(); return; }
        if (fileBusy() || cloudCommandBusy()) { main.postDelayed(this::pumpUploads, 500); return; }
        Object[] next = uploadQueue.remove(0);
        queuedFile = (File) next[0]; queuedName = (String) next[1];
        feedback = "Uploading " + queuedName + "…";
        if (ready()) session.upload(queuedFile, queuedName); else uploadThroughCloud(queuedFile, queuedName);
        changed();
    }
    public void upload() {
        if (selectedFile == null || fileBusy()) return;
        if (replacesActivePrint(selectedName)) { feedback = StatusPresentation.clean(selectedName) + " is the file being printed. It cannot be replaced until the print ends."; changed(); return; }
        if (ready()) { if (!pinProbe) session.upload(selectedFile, selectedName); changed(); }
        else if (cloudUploadReady()) uploadFiles(java.util.Collections.singletonList(selectedFile), java.util.Collections.singletonList(selectedName));
    }
    public void cancelUpload() { if (session != null) session.cancelUpload(); CloudUpload cloud = cloudUpload; if (cloud != null) cloud.cancel(); }
    private void uploadedFile(String name, String message) {
        feedback = message; browse("local", 0);
        File copy = queuedName != null && queuedName.equals(name) ? queuedFile : selectedFile;
        if (copy != null) files.execute(() -> keep(copy, name));
        if (queuedName != null) {
            queuedFile = null; queuedName = null; uploadedCount++;
            if (uploadQueue.isEmpty()) feedback = uploadedCount == 1 ? message : "Uploaded " + uploadedCount + " files.";
            main.postDelayed(PrinterService.this::pumpUploads, 300);
        }
    }
    private void queueFailed() {
        if (queuedName == null) return;
        File failedFile = queuedFile; String failedName = queuedName;
        int left = uploadQueue.size(); uploadQueue.clear(); queuedFile = null; queuedName = null;
        if (left > 0) feedback = feedback + " " + left + " more file(s) were not sent; they are kept under Recent slices on the Files tab.";
        // A sliced file sent with "Upload and print" has no other home once the Slice screen is closed: keep it one tap from a retry.
        if (failedFile != null && failedName != null && failedFile.isFile() && !failedFile.equals(selectedFile))
            selectSliced(failedFile, failedName, feedback + " The sliced file is kept in the Files tab: choose Upload to try again.", true);
    }

    /** The upload running through the Elegoo cloud, if any. */
    private CloudUpload cloudUpload;
    private void uploadThroughCloud(File file, String name) {
        CloudApi api = cloudApi; String serial = cloudSerial;
        ensureCloudControl();
        CloudControl control = cloudControl;
        Diagnostics.note(Diagnostics.FILES, "cloud upload started (" + (file.length() / 1024) + " KB)");
        CloudUpload.Commands commands = new CloudUpload.Commands() {
            public void send(JSONObject request, CloudControl.Reply reply) {
                control.send(serial, request, (acknowledged, text) -> { main.post(PrinterService.this::changed); reply.done(acknowledged, text); });
                main.post(PrinterService.this::changed);
            }
            public void watch(CloudControl.Transfers listener) { control.watchTransfers(serial, listener); }
            public boolean linkEnded() { return !control.endedReason().isEmpty(); }
        };
        CloudUpload[] self = new CloudUpload[1];
        self[0] = new CloudUpload(api, new CloudApi.HttpUploader(), commands, files, cloudWorker, serial, file, name, new CloudUpload.Listener() {
            public void progress(int percent) { main.post(() -> { if (cloudUpload != self[0]) return; feedback = "Uploading " + name + " through the Elegoo cloud: " + percent + "%" + (percent >= 50 ? " (printer fetching)" : ""); changed(); updateNotification(false); }); }
            public void stored(String objectName) { cloudFiles.remember(serial, name, objectName); }
            public void finished(boolean done, String message) { main.post(() -> {
                if (cloudUpload != self[0]) return;
                cloudUpload = null;
                Diagnostics.note(Diagnostics.FILES, "cloud upload " + (done ? "finished" : "ended: " + message));
                if (destroyed) return;
                if (done) uploadedFile(name, message + " Choose Print setup to start it.");
                else { feedback = message; queueFailed(); loadDisk(); }
                changed();
            }); }
        });
        cloudUpload = self[0];
        self[0].start();
    }
    private final java.util.concurrent.atomic.AtomicBoolean cloudCancel = new java.util.concurrent.atomic.AtomicBoolean();
    private boolean fileFieldsLogged;
    /** How the app reaches Elegoo's storage over https (replaceable in tests). */
    PrinterHttp.ConnectionFactory cloudConnections = url -> (java.net.HttpURLConnection) url.openConnection();
    public void cancelDownload() {
        cloudCancel.set(true);
        if (session != null) session.cancelDownload();
        PrinterHttp direct = directHttp; if (direct != null) direct.cancel();
    }

    /**
     * Downloads a printer file straight from its HTTP port, without the local session: for printers watched through
     * the Elegoo cloud, as Elegoo's own printer page does. The printer is found on this Wi-Fi by its serial; the token
     * is the saved access code for that address, or the printer default.
     */
    private void downloadDirect(String storage, String filename) {
        if (directDownloading) { feedback = "A download is already running."; changed(); return; }
        String serial = cloudSerial, knownHost = host;
        // Through the cloud first when Elegoo's storage is known to hold this G-code (our own upload, or a field in the cloud's record).
        CloudApi cloud = cloudApi;
        boolean cloudCapable = "local".equals(storage) && usingCloud() && cloud != null && !cloud.needsSignIn() && !serial.isEmpty();
        directDownloading = true; cloudCancel.set(false);
        feedback = cloudCapable ? "Checking Elegoo's cloud for this file…" : "Looking for the printer on this Wi-Fi…"; changed();
        files.execute(() -> {
            File local = null; String failure = null; String[] reached = {null}; NetworkRoute[] via = {null};
            boolean cloudDone = false, cloudUnknown = false; String cloudFailure = null;
            if (cloudCapable) {
                try {
                    CloudFileRoute.Choice choice = CloudFileRoute.choose(cloudFiles, serial, filename, cloud::fileRecord);
                    if (!fileFieldsLogged) { fileFieldsLogged = true; cloud.logFileListShape(serial); }
                    if (choice == null) { cloudUnknown = true; Diagnostics.note(Diagnostics.FILES, "cloud route: no G-code reference in our uploads or the cloud's record"); }
                    else {
                        main.post(() -> { feedback = "Downloading through the Elegoo cloud…"; changed(); });
                        File target = File.createTempFile("download-", ".gcode", getCacheDir());
                        try {
                            CloudFileRoute.download(choice, cloud::signedLink, target, percent -> main.post(() -> { feedback = "Downloading G-code through the Elegoo cloud" + (percent < 0 ? "…" : ": " + percent + "%"); changed(); }), cloudConnections, cloudCancel);
                            local = target; cloudDone = true;
                        } catch (Exception error) {
                            target.delete();
                            String plain = FriendlyErrors.describe(error, "The cloud download");
                            cloudFailure = plain != null ? plain : error.getMessage() == null ? "it failed" : error.getMessage();
                            Diagnostics.note(Diagnostics.FILES, "cloud route failed: " + StatusPresentation.clean(cloudFailure));
                        }
                    }
                } catch (Exception error) { Diagnostics.note(Diagnostics.FILES, "cloud route could not start: " + error.getClass().getSimpleName()); }
                if (cloudCancel.get()) cloudFailure = "Download cancelled.";
                if (!cloudDone && !cloudCancel.get()) main.post(() -> { feedback = "Looking for the printer on this Wi-Fi…"; changed(); });
            }
            if (!cloudDone && !cloudCancel.get()) try {
                NetworkRoute route = NetworkRoute.local(getApplicationContext()); via[0] = route;
                String address = null;
                if (!serial.isEmpty()) {
                    try (Cc2Discovery scanner = route.discovery()) {
                        for (Cc2Discovery.Found found : scanner.scan()) if (found.info.serial.equalsIgnoreCase(serial)) { address = found.host; break; }
                    }
                }
                if (address == null && !knownHost.isEmpty() && serial.isEmpty()) address = knownHost;
                if (address == null) throw new IOException("The printer did not answer on this Wi-Fi. Downloads come from the printer itself, so the phone must be on the same network.");
                reached[0] = address;
                String token = "";
                try { CredentialStore credentials = new CredentialStore(this); if (credentials.remembers(address)) token = credentials.load(address); } catch (Exception ignored) { }
                Diagnostics.note(Diagnostics.FILES, "direct download from " + (serial.isEmpty() ? "the last printer" : "cloud printer found on Wi-Fi") + (token.isEmpty() ? " with the default token" : " with the saved access code"));
                String where = address;
                main.post(() -> { feedback = "Downloading " + StatusPresentation.clean(filename) + " from " + where + "…"; changed(); });
                PrinterHttp http = new PrinterHttp(address, token, route.http()); directHttp = http;
                local = File.createTempFile("download-", ".gcode", getCacheDir());
                http.download(local, storage, filename, percent -> main.post(() -> { feedback = "Downloading G-code" + (percent < 0 ? "…" : ": " + percent + "%"); changed(); }));
            } catch (PrinterErrors.Rejected rejected) {
                failure = "The printer refused the download: its access code is needed. Connect once locally in Settings with \u201cRemember access code\u201d on, then try again.";
            } catch (java.net.ConnectException refused) {
                // Seen once on a CC2 in cloud mode while printing. Elegoo's own cloud printer page downloads from the same port,
                // so the cause is not known: the printer's state, the phone's network path, or the firmware.
                Diagnostics.note(Diagnostics.FILES, "direct download: connection to the printer's HTTP port refused while " + (usingCloud() ? "watching through the cloud" : "not connected") + ", printer state " + StatusPresentation.state(cloudStatus)
                    + " · reason: " + PrinterHttp.causes(refused));
                if (reached[0] != null && via[0] != null) Diagnostics.note(Diagnostics.FILES, "printer ports from this phone: " + PrinterHttp.probePorts(via[0].sockets(), reached[0], new int[] {80, 1883, 9001, 8080, 3030}));
                failure = "The printer was found on this Wi-Fi, but it refused the connection to its file server (port 80). Try again in a minute; if it keeps failing, "
                    + "open http://" + (reached[0] == null ? "<printer IP>" : reached[0]) + "/ in the phone's browser: if that also fails, the printer is not serving files right now. Downloading also works over the local connection (LAN Only).";
            } catch (Exception error) {
                String plain = FriendlyErrors.describe(error, "The download");
                failure = plain != null ? plain : error instanceof IOException && error.getMessage() != null ? error.getMessage() : PrinterErrors.describe(error, "Download");
            } finally { directHttp = null; }
            if (failure != null) {
                if (cloudUnknown) failure += " " + CloudFileRoute.NO_COPY;
                else if (cloudFailure != null) failure += " The Elegoo cloud route failed too: " + cloudFailure;
            }
            if (!cloudDone && cloudCancel.get()) failure = "Download cancelled.";
            File done = local; String problem = failure;
            main.post(() -> {
                directDownloading = false;
                if (problem != null || done == null) { if (done != null) done.delete(); feedback = problem; changed(); return; }
                received(done, filename);
            });
        });
    }
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
        if (pinProbe) { feedback = "The PIN probe cannot download files."; changed(); return; }
        if (!ready()) { if (fileBusy()) { feedback = "Wait for the current file transfer to finish."; changed(); return; } downloadDirect(storage, filename.replaceFirst("^/+", "")); return; }
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
    static final long FILES_WAIT_MS = 45_000;
    private long filesAsked;
    public boolean filesFresh() { return canQuery() && filesAt != 0 && System.nanoTime() - filesAt < TimeUnit.MINUTES.toNanos(2); }
    public void browse(String storage, int offset) {
        if (!canQuery() || busy(Cc2Codec.FILES)) return; Cc2Codec.storage(storage); if (offset < 0) throw new IllegalArgumentException("Invalid offset");
        queryBusy.add(Cc2Codec.FILES); fileMessage = "Loading printer files…";
        boolean local = ready();
        if (local) session.files(storage, offset); else cloudSafe(() -> Cc2Codec.filesRequest(0, storage, offset));
        // An answer that never comes (a reply lost on the way, mostly through the cloud) must not leave the list loading for ever.
        long asked = ++filesAsked;
        main.postDelayed(() -> {
            if (asked != filesAsked || !busy(Cc2Codec.FILES)) return;
            queryBusy.remove(Cc2Codec.FILES);
            fileMessage = "The printer did not send its file list" + (local ? "." : " through the Elegoo cloud.") + " Refresh to ask again.";
            Diagnostics.note(Diagnostics.FILES, "file list: no answer within " + FILES_WAIT_MS / 1000 + " s (" + (local ? "local" : "cloud") + ", " + storage + ")");
            changed();
        }, FILES_WAIT_MS);
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
        if (pinProbe) { feedback = "The PIN probe cannot download files."; changed(); return; }
        if (fileBusy()) { feedback = "Wait for the current file transfer to finish."; changed(); return; }
        // Watched through the cloud: straight from the printer's HTTP port, as Elegoo's printer page does.
        if (!ready()) { downloadDirect("local", filename.replaceFirst("^/+", "")); return; }
        if (knownFile("local", filename)) { download("local", filename); return; }
        viewerDownload = filename; feedback = "Looking for " + StatusPresentation.clean(filename) + " on the printer…"; browse("local", 0); changed();
    }
    /** A downloaded printer file: kept for the viewer, inspected and shown in the Files workspace. */
    private void received(File file, String name) {
        importing = true; feedback = "Download received. Inspecting kept copy…"; changed();
                files.execute(() -> {
                    keep(file, name);
                    GcodeInspector.Report report = null;
                    try { report = GcodeInspector.inspect(file); } catch (Exception ignored) { }
                    GcodeInspector.Report result = report;
                    android.graphics.Bitmap preview = report == null ? null : ThumbnailDecoder.decode(report.thumbnail);
                    main.post(() -> finishFile(file, name, result, preview, "Downloaded and kept in this app. Save to phone… exports a copy; the printer's copy is unchanged."));
                });
    }
    /** Keeps a copy in the viewer's library; failures only cost the viewer that file. */
    void keep(File file, String name) {
        try { if (library != null) library.put(file, name); } catch (IOException ignored) { }
    }
    public void start(String storage, String filename, boolean leveling, boolean force, boolean timelapse, String plate, JSONArray maps) {
        if (fileBusy()) { feedback = "A file is being sent or received. Wait for it to finish before starting a print."; changed(); return; }
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
        historyDetails.clear(); historyDetailMessages.clear(); queryBusy.clear(); fileMessage = "Refresh to browse printer files."; historyMessage = "Refresh to load print history.";
    }
    public void disconnect() {
        wanted = false; generation++; main.removeCallbacks(reconnect); clearRoute();
        if (session != null) session.close(); session = null; code = "";
        resetData();
        connection = "Not connected"; stopMonitoring(); changed();
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
                main.post(() -> finishFile(readyFile, readyName, report, preview, "Kept copy inspected. Uploading does not start a print."));
            } catch (Exception error) {
                if (local != null) local.delete();
                main.post(() -> { if (!destroyed) { importing = false; feedback = "File import failed. Choose a nonempty .gcode file (up to 512 MiB) with a simple filename."; changed(); } });
            } finally {
                if (releasePermission) try { getContentResolver().releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (SecurityException ignored) { }
            }
        });
    }
    /** Takes G-code sliced on this phone into the workspace, as select() does for a chosen file. */
    public void selectSliced(File source, String name) { selectSliced(source, name, "Sliced on this phone. Uploading does not start a print.", false); }
    private void selectSliced(File source, String name, String doneText, boolean afterFailure) {
        if (afterFailure ? importing || exporting : fileBusy()) return;
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
                main.post(() -> finishFile(readyFile, name, report, preview, doneText));
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
        if (fileBusy() || selectedFile == null || selectedReport == null || !selectedReport.sha256.equals(expectedHash)) { feedback = "The kept copy changed or is unavailable. Choose Save to phone… again."; changed(); return; }
        File file = selectedFile; exporting = true; feedback = "Saving to phone…"; changed();
        files.execute(() -> {
            String text;
            try (InputStream input = new FileInputStream(file); OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
                if (output == null) throw new IOException("Document unavailable");
                byte[] buffer = new byte[65536]; int count; long copied = 0;
                while ((count = input.read(buffer)) != -1) { if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException(); output.write(buffer, 0, count); copied += count; }
                if (copied != file.length()) throw new IOException("Kept copy changed");
                text = "Saved to phone. Printer files are unchanged.";
            } catch (Exception error) { text = "Could not save to phone. A partial destination document may remain; choose Save to phone… again."; }
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
    /**
     * The printer's cloud online state after a poll: the online endpoint's answer (1 online, 0 offline) when it gives one.
     * That endpoint refuses some accounts' tokens (answer -1, unknown), so an unknown answer keeps what live updates last
     * showed, or counts a status report from the last two minutes as online.
     */
    static int onlineState(int reported, int previous, long reportedAt, long nowMs) {
        if (reported == 0 || reported == 1) return reported;
        boolean recent = reportedAt > 0 && nowMs - CloudApi.seconds(reportedAt) * 1000 < REPORT_RECENT_MS;
        // "Offline" is kept; "online" is kept only while the printer itself keeps reporting, so a printer that lost power cannot stay live for ever.
        if (previous == 0) return 0;
        return recent ? 1 : -1;
    }
    /** The printer's own last report is recent (or a live push just arrived). */
    public boolean cloudReportRecent() { return cloudReportedAt > 0 && System.currentTimeMillis() - cloudReportedAt < REPORT_RECENT_MS; }
    public boolean cloudFresh() {
        return cloudOnline == 1 && cloudCheckedAt != 0 && System.currentTimeMillis() - cloudCheckedAt < 60_000 && cloudStatus.length() > 0
            && (cloudOnlineSignal || cloudReportRecent()) && cloudSettled(cloudCheckedAt, cloudAckAt);
    }
    /** After a command is acknowledged, status counts as fresh again only if it was fetched a few seconds after the acknowledgement. */
    static final long CLOUD_ACK_SETTLE_MS = 5_000;
    static boolean cloudSettled(long checkedAt, long ackAt) { return ackAt == 0 || checkedAt > ackAt + CLOUD_ACK_SETTLE_MS; }
    private long cloudAckAt;
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
                api = new CloudApi(cloudAccounts.china(), account, CloudApi.agent(this), CloudApi::https); api.language(Locale.getDefault().getLanguage());
                final CloudAccountStore store = cloudAccounts;
                api.onAccountChanged(renewed -> { try { if (store.load() != null) store.save(renewed); } catch (Exception ignored) { } });
                cloudApi = api;
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
            } catch (Exception error) { String plain = FriendlyErrors.describe(error, "The Elegoo cloud request"); message = plain != null ? plain : error.getMessage() == null ? "Could not reach the Elegoo cloud." : error.getMessage(); }
            api.takeTrace();
            if (api.account() != before) try { if (cloudAccounts.load() != null) cloudAccounts.save(api.account()); } catch (Exception ignored) { }
            CloudApi.Device found = device; int reportedOnline = online; CloudApi.Snapshot reported = snapshot; String text = message;
            main.post(() -> {
                if (destroyed) return;
                cloudSignedIn = true; cloudMessage = text;
                if (found != null) {
                    if (!found.serial.equals(cloudSerial)) cloudAlerts = new PrintAlerts();
                    boolean same = found.serial.equals(cloudSerial);
                    cloudName = found.name; cloudModel = found.model; cloudSerial = found.serial;
                    int onlineBefore = cloudOnline;
                    if (!same) { cloudReportedAt = 0; cloudOnlineSignal = false; }
                    if (reported != null && reported.reportedAt > 0) cloudReportedAt = Math.max(cloudReportedAt, CloudApi.seconds(reported.reportedAt) * 1000);
                    cloudOnline = onlineState(reportedOnline, same ? cloudOnline : -1, cloudReportedAt, System.currentTimeMillis());
                    cloudOnlineSignal = reportedOnline == 1 || reportedOnline == -1 && cloudOnlineSignal && cloudOnline == 1;
                    if (cloudOnline != onlineBefore) Diagnostics.note(Diagnostics.FILES, "cloud printer online state " + onlineBefore + " -> " + cloudOnline + " (online endpoint said " + reportedOnline + ")");
                }
                if (reported != null) {
                    cloudStatus = reported.status; cloudCheckedAt = System.currentTimeMillis();
                    // Local alerts take precedence while connected locally.
                    if (!wanted && cloudOnline == 1 && cloudReportRecent()) { showAlert(cloudAlerts.update(reported.status), cloudName); record(reported.status, "cloud", cloudName); }
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
                    cloudStatus = merged; cloudCheckedAt = System.currentTimeMillis(); cloudReportedAt = cloudCheckedAt; cloudOnlineSignal = true;
                    // A live report from the printer itself shows it is online.
                    cloudOnline = 1;
                    if (cloudOnline == 1) { showAlert(cloudAlerts.update(merged), cloudName); record(merged, "cloud", cloudName); }
                    changed(); updateNotification(false);
                } catch (Exception ignored) { }
            }); }
            public void online(String serial, boolean online) { main.post(() -> { if (serial.equals(cloudSerial)) { cloudOnline = online ? 1 : 0; cloudOnlineSignal = online; if (online) cloudReportedAt = System.currentTimeMillis(); changed(); } }); }
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
        if (cloudCommandBusy()) {
            queryBusy.remove(method);
            if (method != Cc2Codec.CANVAS) { feedback = "Waiting for the previous cloud command to finish."; changed(); }
            return;
        }
        ensureCloudControl();
        if (!query) feedback = "Sending through the Elegoo cloud…";
        JSONObject params = request.optJSONObject("params") == null ? new JSONObject() : request.optJSONObject("params");
        cloudControl.send(cloudSerial, request, new CloudControl.Reply() {
            public void done(boolean acknowledged, String text) { done(acknowledged, text, new JSONObject()); }
            public void done(boolean acknowledged, String text, JSONObject result) {
                main.post(() -> {
                    if (query) { if (acknowledged) handleQuery(method, params, result); else handleQueryError(method, text); changed(); return; }
                    feedback = text; changed();
                    if (method == Cc2Codec.DELETE && acknowledged) handleQuery(method, params, result);
                    if (acknowledged) cloudAckAt = System.currentTimeMillis();
                    // Refresh status once the acknowledgement has settled so the result shows; nothing is retried.
                    main.removeCallbacks(cloudPoll); main.postDelayed(cloudPoll, acknowledged ? CLOUD_ACK_SETTLE_MS + 500 : 2000);
                });
        changed();
            }
        });
    }
    // Read-only printer probe (Settings → Diagnostics): network ports, then Elegoo's "Get…" questions, once each. See PrinterProbe.
    static final int[] PROBE_PORTS = {80, 443, 554, 1883, 3030, 8080, 8554, 8883, 9001};
    public boolean probing;
    public String probeText = "";
    private PrinterProbe probe;
    private final ScheduledExecutorService probeWorker = Executors.newSingleThreadScheduledExecutor();
    public void probePrinter() {
        if (probing || destroyed) return;
        probing = true; probeText = "Checking the printer's network ports…"; changed();
        boolean local = ready();
        String wantedSerial = local ? serial : cloudSerial, knownHost = host;
        NetworkRoute current = local ? route : null;
        files.execute(() -> {
            String network;
            try { network = probeNetwork(local, wantedSerial, knownHost, current); }
            catch (Exception error) { network = "printer not found on this network (" + PrinterProbe.clean(error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()) + "); ports not checked"; }
            String found = network;
            main.post(() -> startProbeQueries(found));
        });
    }
    private String probeNetwork(boolean local, String wantedSerial, String knownHost, NetworkRoute current) throws Exception {
        NetworkRoute via = local && current != null ? current : NetworkRoute.local(getApplicationContext());
        String address = local ? knownHost : null;
        if (address == null && !wantedSerial.isEmpty()) try (Cc2Discovery scanner = via.discovery()) {
            for (Cc2Discovery.Found found : scanner.scan()) if (found.info.serial.equalsIgnoreCase(wantedSerial)) { address = found.host; break; }
        }
        if (address == null && wantedSerial.isEmpty() && !knownHost.isEmpty()) address = knownHost;
        if (address == null || address.isEmpty()) return "printer did not answer discovery on this Wi-Fi; ports not checked";
        main.post(() -> { probeText = "Checking ports " + java.util.Arrays.toString(PROBE_PORTS).replaceAll("[\\[\\]]", "") + "…"; changed(); });
        String ports = PrinterHttp.probePorts(via.sockets(), address, PROBE_PORTS);
        StringBuilder text = new StringBuilder(local ? "over the local connection's route" : "over this Wi-Fi").append(" · ports: ").append(ports);
        for (int port : new int[] {80, 8080, 3030})
            if ((", " + ports).contains(", " + port + " open")) text.append(" · HTTP ").append(port).append(": ").append(PrinterHttp.httpHead(via.sockets(), address, port));
        text.append(" · discovery reply fields: ").append(Cc2Discovery.lastFields.isEmpty() ? "not seen" : Cc2Discovery.lastFields);
        return text.toString();
    }
    private void startProbeQueries(String network) {
        if (destroyed) return;
        Diagnostics.note(Diagnostics.PROBE, "network " + network);
        PrinterProbe.Transport transport = null; PrinterProbe.Tally tally = null; boolean local = false;
        Cc2Session current = session;
        if (ready() && current != null) {
            local = true; tally = current.unasked;
            transport = new PrinterProbe.Transport() {
                public String name() { return "the local connection"; }
                public void send(JSONObject request, PrinterProbe.Answer answer) { current.probe(request, answer); }
            };
        } else if (usingCloud() && cloudFresh() && cloudApi != null) {
            ensureCloudControl(); CloudControl control = cloudControl; String serialNow = cloudSerial; tally = control.unasked;
            transport = new PrinterProbe.Transport() {
                public String name() { return "the Elegoo cloud"; }
                public void send(JSONObject request, PrinterProbe.Answer answer) {
                    control.send(serialNow, request, new CloudControl.Reply() {
                        public void done(boolean acknowledged, String message) { answer.reply(null, message); }
                        public void done(boolean acknowledged, String message, JSONObject result) { answer.reply(acknowledged ? result : null, acknowledged ? null : message); }
                    });
                }
            };
        }
        if (transport == null) {
            String text = "Questions not sent: connect locally, or watch the printer through the Elegoo cloud with fresh status (Settings).";
            Diagnostics.note(Diagnostics.PROBE, text);
            probing = false; probeText = "Network: " + network + "\n\n" + text; changed(); return;
        }
        PrinterProbe.Tally unasked = tally;
        probe = new PrinterProbe(transport, probeWorker, new PrinterProbe.Listener() {
            public void progress(String text) { main.post(() -> { probeText = text; changed(); }); }
            public void finished(String report, java.util.List<PrinterProbe.Outcome> outcomes) {
                String seen = unasked.summary();
                main.post(() -> {
                    for (String line : report.split("\n")) Diagnostics.note(Diagnostics.PROBE, line.trim());
                    Diagnostics.note(Diagnostics.PROBE, "messages the printer sent unasked: " + seen);
                    probing = false; probe = null;
                    StringBuilder shown = new StringBuilder(report.split("\n")[0]).append(".\n");
                    for (PrinterProbe.Outcome outcome : outcomes) shown.append("\n").append(outcome.method).append(' ').append(PrinterProbe.name(outcome.method)).append(": ").append(outcome.verdict);
                    shown.append("\n\nNetwork: ").append(network).append("\n\nUnasked messages: ").append(seen).append("\n\nField names are in Share diagnostics…");
                    probeText = shown.toString(); changed();
                });
            }
        }, local ? 20_000 : 30_000, local ? 0 : 500);
        probe.start();
    }
    public void cancelProbe() { PrinterProbe running = probe; if (running != null) running.cancel(); }
    private void ensureCloudControl() {
        if (cloudControl == null) cloudControl = new CloudControl(() -> {
            CloudApi current = cloudApi; if (current == null) throw new IOException("Sign in with Elegoo again.");
            return current.agoraCredential();
        }, AgoraLink::new, cloudWorker);
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
        String text = !ready() ? connection.split("\n")[0] : !fresh() ? "Waiting for printer" : StatusPresentation.state(status);
        if (!wanted && cloudFresh()) text = "Cloud · " + StatusPresentation.overview(cloudStatus).split("\n")[0];
        else if (!wanted && cloudBackground() && !cloudMessage.isEmpty()) text = "Cloud watching paused: " + cloudMessage.split("\n")[0];
        if (ready() && fresh() && !StatusPresentation.faultCodes(status).isEmpty()) text += " · Printer reports a fault";
        if (uploading()) text = feedback;
        String title = wanted || cloudName.isEmpty() ? host : StatusPresentation.clean(cloudName);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_launcher).setContentTitle("Link Workshop · " + title)
            .setContentText(text).setContentIntent(view).setOngoing(true).setOnlyAlertOnce(true);
        // While printing: shortcuts that only open the app at a feature; nothing here sends the printer a command.
        JSONObject shown = wanted ? (ready() && fresh() ? status : null) : (cloudFresh() ? cloudStatus : null);
        JSONObject machine = shown == null ? null : shown.optJSONObject("machine_status");
        if (machine != null && machine.optInt("status", -1) == 2 && !uploading()) {
            builder.addAction(new Notification.Action.Builder(null, "Live toolpath", openFeature("toolpath", 3)).build());
            builder.addAction(new Notification.Action.Builder(null, "Camera", openFeature("camera", 4)).build());
        }
        return builder.addAction(wanted ? new Notification.Action.Builder(null, "Disconnect", disconnect).build()
                : new Notification.Action.Builder(null, "Stop watching", PendingIntent.getService(this, 2, new Intent(this, PrinterService.class).setAction(STOP_CLOUD), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)).build()).build();
    }
    /** Opens the app at a "Find a feature" entry (navigation only). */
    private PendingIntent openFeature(String feature, int request) {
        Intent open = new Intent(this, MainActivity.class).putExtra(MainActivity.EXTRA_FEATURE, feature).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(this, request, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
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
        probeWorker.shutdownNow(); files.shutdownNow(); if (selectedFile != null) selectedFile.delete();
        if (foreground) stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy();
    }
}
