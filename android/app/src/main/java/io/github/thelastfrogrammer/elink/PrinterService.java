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
    /** Print watch (stalls, heaters off target), one per route like the alerts; Settings can turn it off. */
    private final PrintWatch watch = new PrintWatch(), cloudWatch = new PrintWatch();
    private String watched(PrintWatch which, JSONObject value) {
        which.canvas = canvas;
        String text = which.update(value, SystemClock.elapsedRealtime());
        boolean on = getSharedPreferences("workshop-settings", MODE_PRIVATE).getBoolean("printWatch", true);
        if (on && !text.isEmpty() && automations != null) automations.outside(which == watch ? automations.local : automations.cloud, Automation.WATCH, text, value, which == watch ? host : cloudName);
        return on ? text : "";
    }
    public File selectedFile;
    /** Copies of uploaded, sliced and downloaded G-code, for the toolpath viewer to follow prints. */
    GcodeLibrary library;
    private final GcodeJobTracker fileJobs = new GcodeJobTracker();
    String filePrinterId() {
        if (!wanted) return usingCloud() ? cloudSerial : "";
        Cc2Session current = session;
        return current != null && !current.printerSerial().isEmpty() ? current.printerSerial() : serial;
    }
    GcodeJobTracker.Job fileJob() {
        if (!liveFresh()) { fileJobs.clear(); return null; }
        return fileJobs.current(SystemClock.elapsedRealtime());
    }
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
    /** Spools and their filament left (see SpoolStore): follows fresh tray reports, and takes a finished print's use off. */
    public SpoolStore spools() { return SpoolStore.at(new java.io.File(getFilesDir(), "spools.json")); }
    private int loggedSpeedMode = Integer.MIN_VALUE; private int loggedMachineState = Integer.MIN_VALUE; private String loggedAssistant = "";
    /** The chamber's warming or cooling, for the Print tab's countdown and automations. */
    public final ChamberTrend chamberTrend = new ChamberTrend();
    private void record(JSONObject value, String source, String name) {
        fileJobs.update("local".equals(source) ? filePrinterId() : cloudSerial,
            source + ":" + ("local".equals(source) ? generation : System.identityHashCode(cloudApi)), value, SystemClock.elapsedRealtime());
        // The log notes the printer's state and speed-mode number when they change (the speed names are not confirmed yet).
        JSONObject move = value.optJSONObject("gcode_move_inf"), machine = value.optJSONObject("machine_status");
        if (move != null && move.has("speed_mode") && move.optInt("speed_mode") != loggedSpeedMode) { loggedSpeedMode = move.optInt("speed_mode"); Diagnostics.note(Diagnostics.STATUS, "speed mode number now " + loggedSpeedMode + " (" + source + ")"); }
        if (machine != null && machine.has("status") && machine.optInt("status") != loggedMachineState) { loggedMachineState = machine.optInt("status"); Diagnostics.note(Diagnostics.STATUS, "state now " + StatusPresentation.state(value) + " (status " + loggedMachineState + ", sub_status " + machine.optInt("sub_status", -1) + ", " + source + ")"); }
        JSONObject chamberNow = value.optJSONObject("ztemperature_sensor");
        if (chamberNow != null && chamberNow.has("temperature")) chamberTrend.add(SystemClock.elapsedRealtime(), chamberNow.optDouble("temperature", Double.NaN));
        // The printer's own AI detection: what it reports is not documented, so the log keeps each change with the print's state, to learn it.
        JSONObject assistant = value.optJSONObject("device_assistant");
        if (assistant != null && !assistant.toString().equals(loggedAssistant)) {
            loggedAssistant = assistant.toString();
            JSONObject print = value.optJSONObject("print_status");
            Diagnostics.note(Diagnostics.STATUS, "printer AI detection (device_assistant) now " + Diagnostics.scrub(loggedAssistant) + " · " + StatusPresentation.state(value)
                + (print != null && print.has("current_layer") ? " · layer " + print.optInt("current_layer") : "") + " (" + source + ")");
        }
        JSONObject machineNow = value.optJSONObject("machine_status");
        if (guardian != null && machineNow != null && machineNow.optInt("status", -1) == 1) guardian.release();
        checkWaiting(value); checkFolder(value);
        if (automations != null) automations.status("cloud".equals(source) ? automations.cloud : automations.local, value, name);
        SpoolStore spools = spools(); long now = System.currentTimeMillis();
        if (canvasFresh() && canvas != null) spools.sync(canvas, now);
        String used = spools.observe(value, now); if (used != null) feedback = used;
        android.content.SharedPreferences settings = getSharedPreferences("workshop-settings", MODE_PRIVATE);
        if (recorder == null || !settings.getBoolean("recordPrints", true)) return;
        recorder.setCanvas(canvas); recorder.setDefaultPictureMinutes(settings.getInt("pictureMinutes", 5)); recorder.setDefaultPictureLayers(settings.getInt("pictureLayers", 0));
        long mono = SystemClock.elapsedRealtime();
        recorder.update(System.currentTimeMillis(), mono, value, source, name);
        if (recorder.pictureDue(mono)) {
            // Through the cloud with no local camera, only an open cloud video (Live toolpath or the cloud camera) can give pictures: say so once per print.
            PrintRecorder.Recording running = recorder.current();
            if (!ready() && !localCamera() && ExtraCameras.forPictures(this) == null && running != null && !running.id.equals(noPictureNoted)) { noPictureNoted = running.id; Diagnostics.note(Diagnostics.CAMERA, "picture due, but in cloud mode pictures come only while a cloud video is open (Print tab, cloud camera or Live toolpath)"); }
            grab(null);
        }
    }
    /**
     * The printer's camera, for pictures: one connection at a time (the printer's own stream may serve only one), opened for a
     * single frame and closed again. Every frame the app gets, here or from a camera on screen (offerFrame), goes to the running
     * print's recording when a picture is due and to the progress notification. A failed try waits 30 s before the next.
     */
    private volatile boolean grabbing;
    private long grabRetryAt;
    private final List<Frame> grabWaiting = new ArrayList<>();
    private void grab(Frame then) {
        if (then != null) synchronized (grabWaiting) { grabWaiting.add(then); }
        long now = SystemClock.elapsedRealtime();
        // A camera the person added and chose for print pictures takes them instead of the printer's own, locally or through the cloud.
        ExtraCameras.Camera extra = ExtraCameras.forPictures(this);
        if (extra != null) {
            if (grabbing || now < grabRetryAt) { if (!grabbing) synchronized (grabWaiting) { grabWaiting.clear(); } return; }
            grabbing = true; long started = now;
            ExtraCameras.grab(this, extra, new MjpegPlayer.Listener() {
                public void frame(android.graphics.Bitmap image) {
                    grabbing = false;
                    if (!grabOk) { grabOk = true; Diagnostics.note(Diagnostics.CAMERA, "picture from the added camera “" + extra.name + "”: OK after " + (SystemClock.elapsedRealtime() - started) + " ms, " + image.getWidth() + "x" + image.getHeight()); }
                    if (destroyed) return;
                    offerFrame(image, "added:" + extra.id, "the added camera “" + extra.name + "”");
                    List<Frame> waiting; synchronized (grabWaiting) { waiting = new ArrayList<>(grabWaiting); grabWaiting.clear(); }
                    for (Frame f : waiting) f.take(image);
                }
                public void error(String message) {
                    grabbing = false; grabOk = false; grabRetryAt = SystemClock.elapsedRealtime() + 30_000; synchronized (grabWaiting) { grabWaiting.clear(); }
                    Diagnostics.note(Diagnostics.CAMERA, "picture from the added camera “" + extra.name + "” failed: " + Diagnostics.scrub(message) + "; next try in 30 s");
                }
            });
            return;
        }
        boolean viaLocal = ready();
        if (grabbing || !(viaLocal ? route != null : localCamera()) || now < grabRetryAt) {
            String why = grabbing ? "" : !(viaLocal ? route != null : localCamera()) ? (viaLocal ? "no network route to the printer" : "no local camera in cloud mode") : "waiting " + (grabRetryAt - now) / 1000 + " s after a failure";
            if (!why.isEmpty() && !why.startsWith("waiting") && !why.equals(grabSkipNoted)) { grabSkipNoted = why; Diagnostics.note(Diagnostics.CAMERA, "single picture skipped: " + why); }
            if (!grabbing) synchronized (grabWaiting) { grabWaiting.clear(); } return;
        }
        grabSkipNoted = ""; long grabStart = now;
        String url; PrinterHttp.ConnectionFactory http;
        try {
            if (viaLocal) { url = FeatureData.cameraUrl(host, cameraUrl.isEmpty() ? "http://" + host + ":8080/?action=stream" : cameraUrl); http = route.http(); }
            else { url = localCameraUrl(); http = NetworkRoute.local(getApplicationContext()).http(); }
        } catch (Exception invalid) { return; }
        grabbing = true;
        MjpegPlayer[] player = new MjpegPlayer[1];
        Runnable finish = () -> { MjpegPlayer p = player[0]; player[0] = null; grabbing = false; if (p != null) p.close(); };
        player[0] = new MjpegPlayer(url, http, new MjpegPlayer.Listener() {
            public void frame(android.graphics.Bitmap image) {
                if (player[0] == null) return;
                finish.run();
                if (!grabOk) { grabOk = true; Diagnostics.note(Diagnostics.CAMERA, "single picture from the printer's camera: OK after " + (SystemClock.elapsedRealtime() - grabStart) + " ms, " + image.getWidth() + "x" + image.getHeight() + (viaLocal ? "" : " (alongside the cloud)")); }
                main.post(() -> {
                    if (destroyed) return;
                    offerFrame(image, PrintRecorder.PRINTER_CAMERA, "a single picture from the printer's camera" + (viaLocal ? "" : " (alongside the cloud)"));
                    List<Frame> waiting; synchronized (grabWaiting) { waiting = new ArrayList<>(grabWaiting); grabWaiting.clear(); }
                    for (Frame f : waiting) f.take(image);
                });
            }
            public void error(String message) {
                finish.run(); grabRetryAt = SystemClock.elapsedRealtime() + 30_000; synchronized (grabWaiting) { grabWaiting.clear(); }
                grabOk = false; Diagnostics.note(Diagnostics.CAMERA, "single picture from the printer's camera failed after " + (SystemClock.elapsedRealtime() - grabStart) + " ms: " + Diagnostics.scrub(message) + "; next try in 30 s");
                // Alongside the cloud, a failure turns the local camera off rather than retrying (the port can lock up after a few tries).
                if (!viaLocal) main.post(() -> { workshop().edit().putBoolean("localCameraInCloud", false).apply(); localCameraText = "The printer's own camera stopped answering. The cloud video is back."; Diagnostics.note(Diagnostics.HYBRID, "local camera alongside the cloud failed; turned off"); changed(); });
            }
        });
        main.postDelayed(() -> { if (player[0] != null) { finish.run(); grabRetryAt = SystemClock.elapsedRealtime() + 30_000; grabOk = false; Diagnostics.note(Diagnostics.CAMERA, "single picture from the printer's camera: no picture within 15 s; next try in 30 s"); } }, 15_000);   // never left open
    }
    private long offeredAt; private String noPictureNoted = "";
    /** Camera log: the last skip reason noted (each noted once until a grab runs), and whether the last grab worked (success is noted once per run of successes). */
    private String grabSkipNoted = ""; private boolean grabOk;
    /** A camera frame from anywhere in the app: kept as the print's picture when one is due, and shown in the progress notification. */
    public void offerFrame(android.graphics.Bitmap image) { offerFrame(image, PrintRecorder.PRINTER_CAMERA, "the camera stream on screen"); }
    /** `cameraId`: PrintRecorder.PRINTER_CAMERA, or "added:" and the added camera's id. */
    void offerFrame(android.graphics.Bitmap image, String cameraId, String source) {
        if (image == null) return;
        long mono = SystemClock.elapsedRealtime();
        if (takesPictures(cameraId) && recorder != null && recorder.pictureDue(mono)) {
            java.io.ByteArrayOutputStream jpeg = new java.io.ByteArrayOutputStream(); image.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, jpeg);
            recorder.savePicture(jpeg.toByteArray(), mono, source, cameraId);
        }
        if (offeredAt == 0 || mono - offeredAt > 30_000) { offeredAt = mono; progressPicture = scaled(image, 640); progressPictureKey = "live"; progressGrabAt = mono; }
    }
    /** Whether this camera takes the print's pictures: the added one chosen for them, or else the printer's own. Every kept picture goes through here, so a timelapse stays one view. */
    boolean takesPictures(String cameraId) {
        ExtraCameras.Camera chosen = ExtraCameras.forPictures(this);
        return chosen == null ? PrintRecorder.PRINTER_CAMERA.equals(cameraId) : ("added:" + chosen.id).equals(cameraId);
    }
    /** A JPEG for the print's recording from any screen (cloud video, the toolpath viewer): kept only from the camera that takes the pictures. */
    public boolean keepPicture(byte[] jpeg, long mono, String cameraId, String source) {
        if (recorder == null || !takesPictures(cameraId)) return false;
        return recorder.savePicture(jpeg, mono, source, cameraId);
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
        Crashes.install(this);   // the service can run without any screen open
        library = new GcodeLibrary(new File(getFilesDir(), "gcode-library"));
        files.execute(() -> {
            try { library.migrateLegacy(); }
            catch (IOException error) { Diagnostics.note(Diagnostics.FILES, "Earlier G-code copies could not all be migrated: " + error.getClass().getSimpleName()); }
            main.post(() -> { if (!destroyed) changed(); });
        });
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
        guardian = new GuardianRun(this); recorder.pictureKept = this::guard; PrintRecorder.active = recorder;
        automations = new AutomationRun(this, automationHost);
        if (cloudBackground()) main.post(cloudPoll);
        main.postDelayed(followTick, 20_000);
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
        fileJobs.clear();
        new PrinterAuthentication(pinProbe, code); new PrinterHttp(host, pinProbe ? "" : code);
        if (!serial.isEmpty() && !Cc2Discovery.validSerial(serial)) throw new IllegalArgumentException("Invalid serial number");
        followed = false;   // a connection made here is the user's own (follow mode marks its own afterwards)
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
            public void status(JSONObject value, boolean canvasUpdated) { deliver(() -> { status = value; JSONObject trays = value.optJSONObject("canvas_info"); if (canvasUpdated && trays != null) { canvas = trays; canvasAt = System.nanoTime(); } alertChoices(); showAlert(alerts.update(value)); showAlert(watched(watch, value)); record(value, "local", attributes.optString("hostname", host)); }); }
            public void attributes(JSONObject value) { deliver(() -> attributes = value); }
            public void canvas(JSONObject value) { deliver(() -> { canvas = value; canvasAt = System.nanoTime(); }); }
            public void result(String text) { deliver(() -> feedback = text); }
            public void uploadProgress(int percent) { deliver(() -> feedback = "Uploading " + (queuedName != null ? queuedName : selectedName) + ": " + percent + "%"); }
            public void uploadFailed(String name) { deliver(() -> { queueFailed(); loadDisk(); }); }
            public void failure(String text, boolean retryable) { deliver(() -> failed(text, retryable)); }
            public void query(int method, JSONObject params, JSONObject result) { deliver(() -> handleQuery(method, params, result)); }
            public void queryError(int method, String text) { deliver(() -> handleQueryError(method, text)); }
            public void uploaded(File file, String name) { String forPrinter = filePrinterId(); deliver(() -> uploadedFile(file, name, "Upload acknowledged: " + name + ". Refresh Files and choose Print setup to start it.", forPrinter)); }
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
            String shape = FeatureData.fileEntryShape(result);
            filePage = FeatureData.normalizeFiles(result); storage = params.optString("storage_media", "local"); fileOffset = params.optInt("offset"); filesAt = System.nanoTime(); fileMessage = "Files received from printer.";
            org.json.JSONArray list = result.optJSONArray("file_list");
            // Field names only (never file names): an empty or differently shaped answer shows what the printer sent instead.
            Diagnostics.note(Diagnostics.FILES, "file list (" + storage + ", offset " + fileOffset + "): " + (list == null ? "no file_list; fields " + CloudApi.shape(result) : list.length() + " entr" + (list.length() == 1 ? "y" : "ies") + " (" + shape + "), " + filePage.optJSONArray("file_list").length() + " shown, total " + result.optInt("total", -1)));
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
        if (wanted && lastMachine != null && lastMachine.optInt("status", -1) == 2 && !pinProbe) {
            showAlert(LOST_CONTACT);
            if (automations != null) automations.outside(automations.local, Automation.LOST, "local connection lost", status, host);
        }
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
    /** Where the queued files go: the printer and connection they were queued for (uploadTarget()); a change stops the queue. */
    private String queueTarget;
    /** This printer over this connection: the local session (serial and session generation) or the cloud (serial and account session). */
    String uploadTarget() { return ready() ? "local:" + serial + ":" + generation : "cloud:" + cloudSerial + ":" + System.identityHashCode(cloudApi); }
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
        return sameFile(print == null ? "" : print.optString("filename", ""), name);
    }
    /** Whether two printer file names are the same file: compared by the name itself, whatever folder or leading slash. */
    static boolean sameFile(String active, String name) {
        String a = active == null ? "" : active.substring(active.lastIndexOf('/') + 1), b = name == null ? "" : name.substring(name.lastIndexOf('/') + 1);
        return !a.isEmpty() && a.equals(b);
    }
    /** The name is in the last file list received from the printer (internal storage). */
    public boolean nameOnPrinter(String name) { return name != null && listed("local", name); }
    public boolean uploadFiles(java.util.List<File> sources, java.util.List<String> names) {
        for (String name : names) if (replacesActivePrint(name)) { feedback = StatusPresentation.clean(name) + " is the file being printed. It cannot be replaced until the print ends."; changed(); return false; }
        if (!canUpload()) { Diagnostics.note(Diagnostics.FILES, "upload not started: local " + (ready() ? (pinProbe ? "read-only PIN probe" : "ready") : "not connected") + ", cloud " + (!usingCloud() ? "not in use" : "online=" + cloudOnline + ", status age " + (cloudCheckedAt == 0 ? "none" : ((System.currentTimeMillis() - cloudCheckedAt) / 1000) + " s") + (cloudApi == null ? ", no account session" : ""))); feedback = "Uploading needs the local connection, or the printer watched through the Elegoo cloud (Settings)."; changed(); return false; }
        if (!uploadQueue.isEmpty() && !uploadTarget().equals(queueTarget)) { feedback = "Files are still being sent to another printer or connection. Wait for them first."; changed(); return false; }
        for (int i = 0; i < sources.size(); i++) uploadQueue.add(new Object[] {sources.get(i), names.get(i)});
        if (queuedName == null) { uploadedCount = 0; queueTarget = uploadTarget(); }
        pumpUploads(); return true;
    }
    public boolean uploadsPending() { return queuedName != null || !uploadQueue.isEmpty(); }
    private void pumpUploads() {
        if (uploadQueue.isEmpty() || queuedName != null || destroyed) return;
        if (!canUpload()) { stopQueue("Connection lost; uploads stopped."); return; }
        if (!uploadTarget().equals(queueTarget)) { stopQueue("The printer or connection changed, so the remaining uploads stopped."); return; }
        if (fileBusy() || cloudCommandBusy() || !liveFresh()) { main.postDelayed(this::pumpUploads, 500); return; }
        // Checked again for every file with fresh status: the printer may have started printing one of them meanwhile.
        String nextName = (String) uploadQueue.get(0)[1];
        if (replacesActivePrint(nextName)) { stopQueue(StatusPresentation.clean(nextName) + " is now being printed, so it was not replaced; the remaining uploads stopped."); return; }
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
    private void uploadedFile(File copy, String name, String message, String forPrinter) {
        feedback = message; browse("local", 0);
        if (copy != null) files.execute(() -> keep(copy, name, forPrinter, GcodeLibrary.Origin.UPLOADED));
        if (queuedName != null) {
            queuedFile = null; queuedName = null; uploadedCount++;
            if (uploadQueue.isEmpty()) feedback = uploadedCount == 1 ? message : "Uploaded " + uploadedCount + " files.";
            main.postDelayed(PrinterService.this::pumpUploads, 300);
        }
    }
    /** Stops the queue before its next file: nothing more is sent, and the reason is shown. */
    private void stopQueue(String why) {
        int left = uploadQueue.size(); uploadQueue.clear();
        Diagnostics.note(Diagnostics.FILES, "upload queue stopped with " + left + " file(s) left: " + why);
        feedback = why + (left > 0 ? " " + left + " file(s) were not sent; they are kept under Recent slices on the Files tab." : ""); changed();
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
                if (done) uploadedFile(file, name, message + " Choose Print setup to start it.", serial);
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
        refusedAt = null;
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
                refusedAt = reached[0];
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
                if (waitingFile != null && waitingFile.equals(filename)) {
                    if (done != null && problem == null) { stopWaiting(null); if (followed || lanOnlySeen) followText = "Downloaded " + StatusPresentation.clean(filename) + ". You can switch LAN Only off; the app goes back to the cloud by itself."; }
                    else if (refusedAt == null) stopWaiting("Stopped waiting for " + StatusPresentation.clean(filename) + ": " + problem);
                    else { feedback = "Still waiting for the printer to serve " + StatusPresentation.clean(filename) + "."; changed(); return; }
                }
                if (problem != null || done == null) { if (done != null) done.delete(); feedback = problem; changed(); return; }
                received(done, filename, serial);
            });
        });
    }
    // ------------------------------------------------------------------ file access: why downloads fail, and waiting for them to work

    /** The address whose file server refused the last direct download (null when it did not refuse). */
    private volatile String refusedAt;
    public boolean fileServerRefused() { return refusedAt != null; }
    /** The last file access check, in words (null before any). */
    public String fileAccessText; public boolean fileAccessRunning;

    /** Finds the printer on this Wi-Fi: by the cloud serial when watching through the cloud, else the connected address. */
    private Cc2Discovery.Found locate(NetworkRoute route) throws IOException {
        String serial = cloudSerial, knownHost = host;
        try (Cc2Discovery scanner = route.discovery()) {
            for (Cc2Discovery.Found found : scanner.scan())
                if (!serial.isEmpty() ? found.info.serial.equalsIgnoreCase(serial) : found.host.equals(knownHost)) return found;
        }
        return null;
    }

    /**
     * Read-only: finds the printer on this Wi-Fi, notes the mode it reports and which ports accept a connection (opened and
     * closed, nothing sent), and says in words why a download would or would not work. Changes nothing on the printer.
     */
    public void checkFileAccess() {
        if (fileAccessRunning) return;
        fileAccessRunning = true; fileAccessText = "Checking the printer's file server…"; changed();
        String state = StatusPresentation.state(liveStatus());
        files.execute(() -> {
            FileAccess.Evidence e = new FileAccess.Evidence(); e.state = state;
            try {
                NetworkRoute route = NetworkRoute.local(getApplicationContext());
                Cc2Discovery.Found found = null;
                try { found = locate(route); } catch (IOException ignored) { }
                String address = found != null ? found.host : ready() ? host : null;
                if (address != null) {
                    e.found = true;
                    if (found != null) { e.lanOnly = found.info.lanOnly; e.codeProtected = found.info.codeProtected; }
                    try { e.savedCode = new CredentialStore(this).remembers(address); } catch (Exception ignored) { }
                    int[] ports = ready() ? FileAccess.PORTS : java.util.Arrays.stream(FileAccess.PORTS).filter(p -> p != 8080).toArray();   // see probeNetwork
                    e.ports.putAll(FileAccess.parse(PrinterHttp.probePorts(route.sockets(), address, ports)));
                    if (FileAccess.open(e)) refusedAt = null;
                }
            } catch (IOException noWifi) { e.wifi = false; }
            Diagnostics.note(Diagnostics.FILES, FileAccess.report(e));
            String text = FileAccess.verdict(e);
            main.post(() -> { fileAccessRunning = false; fileAccessText = text; changed(); });
        });
    }

    /** A download waiting for the printer's file server to open, and when it was asked for. */
    private String waitingFile, waitingStorage, waitingState = ""; private long waitingSince, nextWaitProbe; private boolean waitProbing;
    static final long WAIT_PROBE_MS = 60_000L, WAIT_LIMIT_MS = 12 * 3600_000L;
    /** Checks again every minute even when no status arrives (a printer switched to LAN Only goes quiet on the cloud). */
    private final Runnable waitTick = new Runnable() { @Override public void run() { if (waitingFile == null) return; checkWaiting(liveStatus()); main.postDelayed(this, WAIT_PROBE_MS); } };
    private volatile String waitAddress, lastWaitResult = "";
    public String waitingFor() { return waitingFile; }
    /**
     * Downloads `filename` once the printer's file server accepts connections: checked (a TCP connect to port 80, nothing sent)
     * when the printer's state changes and every three minutes, for up to twelve hours. Downloading only reads from the printer.
     */
    public void downloadWhenOpen(String storage, String filename) {
        waitingFile = filename.replaceFirst("^/+", ""); waitingStorage = storage; waitingSince = SystemClock.elapsedRealtime(); nextWaitProbe = 0; waitingState = "";
        feedback = "Waiting for the printer to serve " + StatusPresentation.clean(waitingFile) + ". The app checks every minute and downloads it as soon as the printer's file server answers. "
            + "In cloud mode the printer keeps that server closed: turning on LAN Only on the printer's screen should open it, and once the file is in, LAN Only can go off again.";
        Diagnostics.note(Diagnostics.FILES, "waiting for the file server to open");
        main.removeCallbacks(waitTick); main.postDelayed(waitTick, WAIT_PROBE_MS);
        changed(); checkWaiting(liveStatus());
    }
    public void stopWaiting(String why) {
        if (waitingFile == null) return;
        waitingFile = null; main.removeCallbacks(waitTick); if (why != null) { feedback = why; Diagnostics.note(Diagnostics.FILES, "stopped waiting for the file server"); } changed();
    }
    private void checkWaiting(JSONObject status) {
        if (waitingFile == null || waitProbing || directDownloading) return;
        long now = SystemClock.elapsedRealtime();
        if (now - waitingSince > WAIT_LIMIT_MS) { stopWaiting("Stopped waiting: the printer did not serve " + StatusPresentation.clean(waitingFile) + " within 12 hours."); return; }
        String state = StatusPresentation.state(status == null ? new JSONObject() : status);
        if (state.equals(waitingState) && now < nextWaitProbe) return;
        boolean changedState = !state.equals(waitingState); waitingState = state; nextWaitProbe = now + WAIT_PROBE_MS; waitProbing = true;
        String file = waitingFile, storage = waitingStorage;
        files.execute(() -> {
            String result;
            try {
                NetworkRoute route = NetworkRoute.local(getApplicationContext());
                String address = waitAddress;
                if (address == null) { Cc2Discovery.Found found = null; try { found = locate(route); } catch (IOException ignored) { } address = found != null ? found.host : ready() ? host : null; }
                result = address == null ? "not found" : FileAccess.parse(PrinterHttp.probePorts(route.sockets(), address, new int[] {80})).getOrDefault(80, "failed");
                // A refusal proves the address is the printer's; anything else finds it again next time (its address may change).
                waitAddress = "open".equals(result) || "refused".equals(result) ? address : null;
            } catch (IOException noWifi) { result = "no wifi"; }
            String port = result;
            if (changedState || !port.equals(lastWaitResult)) Diagnostics.note(Diagnostics.FILES, "waiting: port 80 " + port + " · printer " + (state.isEmpty() ? "state unknown" : state) + (changedState ? " (state changed)" : ""));
            lastWaitResult = port;
            main.post(() -> {
                waitProbing = false;
                if (waitingFile == null || !waitingFile.equals(file)) return;
                if ("open".equals(port)) { feedback = "The printer's file server is open. Downloading " + StatusPresentation.clean(file) + "…"; changed(); downloadDirect(storage, file); }
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
    // ------------------------------------------------------------------ following the printer's mode (LAN Only or cloud)

    /** The local connection was made by follow mode (not by the user); follow mode is paused after a disconnect by hand. */
    private boolean followed, followPaused, followChecking; private volatile boolean lanOnlySeen; private String followSeenMode = "";
    /** What follow mode did last, in words (null before anything). */
    public String followText;
    public boolean followMode() { return workshop().getBoolean("followMode", true); }
    public void setFollowMode(boolean on) { workshop().edit().putBoolean("followMode", on).apply(); followPaused = false; if (on) main.post(this::followCheck); changed(); }
    private final Runnable followTick = new Runnable() { @Override public void run() { followCheck(); main.postDelayed(this, 60_000); } };
    /**
     * Watching through the cloud (or connected by follow mode): once a minute, while the app is open or watching in the
     * background, the printer is looked for on this Wi-Fi (read-only UDP discovery). LAN Only with a saved access code (or no
     * code protection) connects locally; back in cloud mode, a connection follow mode made is closed and the cloud takes over.
     */
    private void followCheck() {
        if (followChecking || !followMode() || cloudSerial.isEmpty() || !cloudSignedIn || !(cloudVisible || foreground)) return;
        if (wanted && !followed) return;   // the user's own local connection is left alone
        followChecking = true;
        cloudReads.execute(() -> {
            Cc2Discovery.Found found = null;
            try { found = locate(NetworkRoute.local(getApplicationContext())); } catch (IOException ignored) { }
            Cc2Discovery.Found seen = found;
            main.post(() -> { followChecking = false; if (!destroyed) applyFollow(seen); });
        });
    }
    private void applyFollow(Cc2Discovery.Found found) {
        if (found == null) return;
        localHost = found.host;
        boolean lan = Boolean.TRUE.equals(found.info.lanOnly), cloud = Boolean.FALSE.equals(found.info.lanOnly);
        // Each change of the reported mode goes to the diagnostics, with what follow mode will do about it.
        String mode = lan ? "LAN Only" : cloud ? "cloud mode" : "unknown mode";
        if (!mode.equals(followSeenMode)) {
            Diagnostics.note(Diagnostics.HYBRID, "follow: printer reports " + mode + (wanted ? (followed ? "; local connection made by follow mode" : "; a local connection made by hand is left alone") : followPaused ? "; following paused after a disconnect by hand" : ""));
            followSeenMode = mode;
        }
        if (cloud) { followPaused = false; lanOnlySeen = false; }
        if (lan) lanOnlySeen = true;
        if (lan && !wanted && !followPaused) {
            String code = null;
            if (Boolean.FALSE.equals(found.info.codeProtected)) code = "";
            else try { CredentialStore credentials = new CredentialStore(this); if (credentials.remembers(found.host)) code = credentials.load(found.host); } catch (Exception ignored) { }
            if (code == null) {
                String text = "The printer is in LAN Only mode, but no access code is saved for it. Connect once in Settings with \u201cRemember access code\u201d on, and the app will switch by itself next time.";
                if (!text.equals(followText)) { followText = text; Diagnostics.note(Diagnostics.HYBRID, "follow: LAN Only seen, no saved access code"); changed(); }
                return;
            }
            Diagnostics.note(Diagnostics.HYBRID, "follow: LAN Only seen, connecting locally");
            try { connect(found.host, code, found.info.serial); } catch (IllegalArgumentException invalid) { followText = "The printer is in LAN Only mode, but the local connection could not start: " + invalid.getMessage(); changed(); return; }
            followed = true;
            followText = "Connected over this Wi-Fi in LAN Only mode (files, camera and controls). Switch LAN Only off to go back to the cloud.";
            feedback = followText; changed();
        } else if (cloud && wanted && followed) {
            Diagnostics.note(Diagnostics.HYBRID, "follow: cloud mode seen, back to the cloud");
            disconnect(); followPaused = false;
            followText = "Back in cloud mode: watching through the cloud again.";
            feedback = followText; changed();
        }
    }

    // ------------------------------------------------------------------ experiment: does cloud mode publish status on the local broker?

    public String listenText; public boolean listening;
    /** Listen-only, once, for 30 seconds, with the pairing PIN the user typed (kept for this call only). Publishes nothing. */
    public void tryListen(String pin) {
        if (listening || pin == null || pin.trim().isEmpty()) return;
        listening = true; listenText = "Looking for the printer on this Wi-Fi…"; changed();
        String secret = pin.trim();
        Thread thread = new Thread(() -> {
            String text;
            try {
                NetworkRoute route = NetworkRoute.local(getApplicationContext());
                Cc2Discovery.Found found = locate(route);
                if (found == null) text = "The printer did not answer on this Wi-Fi.";
                else if (Boolean.TRUE.equals(found.info.lanOnly)) text = "The printer is in LAN Only mode now. This test is for cloud mode.";
                else {
                    main.post(() -> { listenText = "Listening on your Wi-Fi for 30 seconds…"; changed(); });
                    LocalListen.Result result = LocalListen.listen(found.host, found.info.serial, secret, route.sockets(), LocalListen.paho(), 30_000);
                    Diagnostics.note(Diagnostics.HYBRID, "listen-only test: " + result.report());
                    text = result.verdict(30);
                }
            } catch (IOException noWifi) { text = "The phone is not on Wi-Fi."; }
            String said = text; main.post(() -> { listening = false; listenText = said; changed(); });
        }, "listen-test");
        thread.setDaemon(true); thread.start();
    }

    // ------------------------------------------------------------------ G-code from a folder of the user's (Storage Access Framework)

    /** The running print's file last looked for in the folder, and what came of it in words (null before any). */
    private volatile String folderTried = ""; public String folderText;
    public boolean hasGcodeFolder() { return !workshop().getString("gcodeFolder", "").isEmpty(); }
    public void setGcodeFolder(Uri tree) {
        workshop().edit().putString("gcodeFolder", tree == null ? "" : tree.toString()).apply();
        folderTried = ""; folderText = tree == null ? null : "Folder chosen. When a print starts with a file that is not on this phone, the app looks there."; changed();
        if (tree != null) checkFolder(liveStatus());
    }
    /** Looks for `filename` in the folder now, even if it was looked for before (from the live toolpath's button). */
    public void searchFolder(String filename) { folderTried = ""; checkFolderFor(filename); }
    private void checkFolder(JSONObject status) {
        JSONObject print = status == null ? null : status.optJSONObject("print_status");
        String filename = print == null ? "" : print.optString("filename", "");
        if (!filename.isEmpty() && !filename.equals(folderTried)) checkFolderFor(filename);
    }
    private void checkFolderFor(String filename) {
        String tree = workshop().getString("gcodeFolder", "");
        if (tree.isEmpty() || filename.isEmpty()) return;
        folderTried = filename;
        String forPrinter = filePrinterId();
        String base = filename.substring(filename.lastIndexOf('/') + 1);
        long expected = reportedSize(base);
        cloudReads.execute(() -> {
            String text; File kept = null;
            try {
                if (library != null && !library.candidates(forPrinter, filename).isEmpty()) return;
                FolderMatch match = FolderMatch.search(getContentResolver(), Uri.parse(tree), base);
                if (match == null) text = StatusPresentation.clean(base) + " is not in your G-code folder.";
                else if (expected > 0 && match.size > 0 && match.size != expected) text = "Your G-code folder has a " + StatusPresentation.clean(base) + ", but its size (" + match.size + " bytes) differs from the printer's (" + expected + "). It was not used.";
                else {
                    File copy = File.createTempFile("folder-", ".gcode", getCacheDir());
                    try (InputStream in = getContentResolver().openInputStream(match.uri); OutputStream out = new FileOutputStream(copy)) {
                        if (in == null) throw new IOException("unreadable");
                        byte[] buffer = new byte[65536]; int n; long total = 0;
                        while ((n = in.read(buffer)) != -1) { total += n; if (total > GcodeInspector.MAX_BYTES) throw new IOException("too large"); out.write(buffer, 0, n); }
                    }
                    keep(copy, base, forPrinter, GcodeLibrary.Origin.FOLDER); copy.delete(); kept = copy;
                    text = "Found " + StatusPresentation.clean(base) + " in your G-code folder" + (expected > 0 ? " (same size as the printer's)" : "") + ".";
                }
            } catch (SecurityException lost) { text = "The app can no longer read your G-code folder. Choose it again."; }
            catch (Exception error) { text = "Your G-code folder could not be read (" + error.getClass().getSimpleName() + ")."; }
            Diagnostics.note(Diagnostics.HYBRID, "G-code folder: " + (kept != null ? "match found" + (expected > 0 ? ", size agrees" : ", size not reported") : text.contains("differs") ? "name found, size differs" : text.contains("not in your") ? "no match" : "folder unreadable"));
            String said = text; main.post(() -> { folderText = said; changed(); });
        });
    }
    /** The size the printer (or the cloud's record of it) reports for a file, or -1. */
    private long reportedSize(String base) {
        org.json.JSONArray list = filePage.optJSONArray("file_list");
        for (int i = 0; list != null && i < list.length(); i++) { JSONObject file = list.optJSONObject(i); if (file != null && base.equals(file.optString("filename"))) return file.optLong("size", -1); }
        return -1;
    }

    // ------------------------------------------------------------------ the printer's own camera while watching through the cloud

    /** The printer's address on this Wi-Fi, found by its cloud serial (UDP discovery); null until found. */
    public volatile String localHost;
    /** The last "Try the local camera" result, in words (null before any). */
    public String localCameraText; public boolean localCameraTrying;
    private android.content.SharedPreferences workshop() { return getSharedPreferences("workshop-settings", MODE_PRIVATE); }
    /** Watched through the cloud, with the local camera chosen and the printer found on this Wi-Fi: the picture comes from the printer's own stream. */
    public boolean localCamera() { return !ready() && usingCloud() && localHost != null && workshop().getBoolean("localCameraInCloud", false); }
    public String localCameraUrl() { return "http://" + localHost + ":8080/?action=stream"; }
    public void useLocalCamera(boolean on) { workshop().edit().putBoolean("localCameraInCloud", on).apply(); if (on) findLocalHost(); changed(); }
    /** Finds the printer on this Wi-Fi by its cloud serial, in the background (read-only UDP discovery). */
    public void findLocalHost() {
        if (cloudSerial.isEmpty()) return;
        cloudReads.execute(() -> {
            try { Cc2Discovery.Found found = locate(NetworkRoute.local(getApplicationContext())); if (found != null) { localHost = found.host; main.post(this::changed); } }
            catch (IOException ignored) { }
        });
    }
    /**
     * Read-only: finds the printer on this Wi-Fi and opens its MJPEG stream (port 8080, as in LAN mode) for one picture, at most
     * 12 seconds, then closes it. On success the local camera can be used alongside the cloud.
     */
    private boolean cameraTried;
    /** When the cloud camera question (1042) was last answered, for the camera log of the Wi-Fi try after it. */
    private volatile long cameraQuestionAt;
    public boolean localCameraTried() { return cameraTried && !ready(); }
    public void tryLocalCamera() {
        if (localCameraTrying) return;
        // One try per app run in cloud mode: the port has been seen to lock up after a few connections there (see probeNetwork).
        if (cameraTried && !ready()) { localCameraText = "Already tried since the app started. In cloud mode the printer's camera port can stop answering after a few tries until the printer restarts, so the app does not try again."; changed(); return; }
        cameraTried = true;
        localCameraTrying = true; localCameraText = "Looking for the printer on this Wi-Fi…"; changed();
        cloudReads.execute(() -> {
            String host = null; NetworkRoute route = null; Boolean lanOnly = null;
            try { route = NetworkRoute.local(getApplicationContext()); Cc2Discovery.Found found = locate(route); if (found != null) { host = found.host; lanOnly = found.info.lanOnly; } else if (ready()) host = this.host; }
            catch (IOException noWifi) { }
            if (host == null || route == null) { finishCameraTry(false, route == null ? "The phone is not on Wi-Fi, so the printer's own camera cannot be reached." : "The printer did not answer on this Wi-Fi.", "not found"); return; }
            String address = host, mode = Boolean.FALSE.equals(lanOnly) ? "cloud mode" : Boolean.TRUE.equals(lanOnly) ? "LAN Only" : "unknown mode";
            main.post(() -> { localCameraText = "Opening the printer's camera stream…"; changed(); });
            MjpegPlayer[] player = new MjpegPlayer[1]; boolean[] done = {false};
            Runnable close = () -> { MjpegPlayer p = player[0]; player[0] = null; if (p != null) p.close(); };
            player[0] = new MjpegPlayer("http://" + address + ":8080/?action=stream", route.http(), new MjpegPlayer.Listener() {
                public void frame(android.graphics.Bitmap image) {
                    synchronized (done) { if (done[0]) return; done[0] = true; }
                    close.run(); localHost = address;
                    finishCameraTry(true, "The printer's own camera works on this Wi-Fi in " + mode + " (" + image.getWidth() + "×" + image.getHeight() + "). It replaces the cloud video while you are on this Wi-Fi. Untick the box below to go back.", "picture received, " + image.getWidth() + "x" + image.getHeight() + ", " + mode);
                }
                public void error(String message) {
                    synchronized (done) { if (done[0]) return; done[0] = true; }
                    close.run(); finishCameraTry(false, "The printer's camera stream did not open in " + mode + ": " + StatusPresentation.clean(message) + " The cloud camera stays in use.", "failed in " + mode + ": " + Diagnostics.scrub(StatusPresentation.clean(message)));
                }
            });
            main.postDelayed(() -> {
                synchronized (done) { if (done[0]) return; done[0] = true; }
                close.run(); finishCameraTry(false, "The printer's camera sent no picture within 12 seconds in " + mode + ". The cloud camera stays in use.", "no picture in 12 s, " + mode);
            }, 12_000);
        });
    }
    private void finishCameraTry(boolean ok, String text, String note) {
        Diagnostics.note(Diagnostics.HYBRID, "try local camera: " + note);
        long sinceQuestion = cameraQuestionAt == 0 ? -1 : (SystemClock.elapsedRealtime() - cameraQuestionAt) / 1000;
        Diagnostics.note(Diagnostics.CAMERA, "printer's own camera on Wi-Fi (one try this app run): " + note + (sinceQuestion >= 0 && sinceQuestion < 600 ? ", " + sinceQuestion + " s after the camera question" : ""));
        main.post(() -> { localCameraTrying = false; localCameraText = text; if (ok) workshop().edit().putBoolean("localCameraInCloud", true).apply(); changed(); });
    }

    // ------------------------------------------------------------------ the cloud's records of the printer (cloud mode)

    /** Reads that go to Elegoo's cloud records, apart from file transfers. */
    private final ExecutorService cloudReads = Executors.newSingleThreadExecutor();
    /** Watched through the cloud and signed in: the printer's files and history come from the cloud's records, as in the SDK. */
    private boolean cloudRecords() { CloudApi cloud = cloudApi; return !ready() && usingCloud() && cloud != null && !cloud.needsSignIn() && !cloudSerial.isEmpty(); }

    public void browse(String storage, int offset) {
        if (!canQuery() || busy(Cc2Codec.FILES)) return; Cc2Codec.storage(storage); if (offset < 0) throw new IllegalArgumentException("Invalid offset");
        if (cloudRecords() && "local".equals(storage)) {
            queryBusy.add(Cc2Codec.FILES); fileMessage = "Loading printer files from Elegoo's cloud…"; changed();
            CloudApi cloud = cloudApi; String serial = cloudSerial;
            cloudReads.execute(() -> {
                JSONObject result = null; String why = null;
                try { result = CloudRecords.files(cloud.filePage(serial, offset / CloudRecords.PAGE + 1, CloudRecords.PAGE), offset); }
                catch (IOException error) { why = StatusPresentation.clean(String.valueOf(error.getMessage())); }
                JSONObject answer = result; String problem = why;
                main.post(() -> {
                    queryBusy.remove(Cc2Codec.FILES);
                    if (!cloudStill(cloud, serial)) { changed(); return; }
                    // Not asked through the cloud channel instead: large file-list answers have taken the printer's cloud link offline.
                    if (answer == null) { Diagnostics.note(Diagnostics.FILES, "cloud file records unavailable (" + problem + ")"); fileMessage = "Elegoo's cloud did not send the file list (" + problem + "). Refresh to try again."; changed(); return; }
                    filePage = FeatureData.normalizeFiles(answer); this.storage = "local"; fileOffset = offset; filesAt = System.nanoTime();
                    fileMessage = "Files from Elegoo's cloud records (the printer reports each file there).";
                    Diagnostics.note(Diagnostics.FILES, "file list from cloud records: " + filePage.optJSONArray("file_list").length() + " shown, total " + answer.optInt("total", -1));
                    changed();
                });
            });
            return;
        }
        askFiles(storage, offset);
    }
    /** The printer's own file list (1044), locally or through the cloud channel. */
    private void askFiles(String storage, int offset) {
        if (busy(Cc2Codec.FILES)) return;
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
        if (!canQuery() || busy(Cc2Codec.HISTORY)) return;
        if (cloudRecords()) {
            queryBusy.add(Cc2Codec.HISTORY); historyMessage = "Loading history from Elegoo's cloud…"; changed();
            CloudApi cloud = cloudApi; String serial = cloudSerial;
            cloudReads.execute(() -> {
                JSONObject result = null;
                try { result = CloudRecords.history(cloud.historyPage(serial, 1, CloudRecords.PAGE)); } catch (IOException error) { Diagnostics.note(Diagnostics.FILES, "cloud history unavailable: " + StatusPresentation.clean(String.valueOf(error.getMessage()))); }
                JSONObject answer = result;
                main.post(() -> {
                    queryBusy.remove(Cc2Codec.HISTORY);
                    if (!cloudStill(cloud, serial)) { changed(); return; }
                    if (answer == null) { historyMessage = "Elegoo's cloud did not send the print history. Refresh to try again."; changed(); return; }
                    history = answer; historyMessage = "History from Elegoo's cloud records."; changed();
                });
            });
            return;
        }
        askHistory();
    }
    private void askHistory() {
        if (busy(Cc2Codec.HISTORY)) return; queryBusy.add(Cc2Codec.HISTORY); historyMessage = "Loading history…";
        if (ready()) session.history(); else cloudSafe(() -> Cc2Codec.request(0, Cc2Codec.HISTORY));
        changed();
    }
    public void loadDisk() {
        if (!canQuery() || busy(Cc2Codec.DISK)) return; queryBusy.add(Cc2Codec.DISK);
        if (ready()) session.disk(); else cloudSafe(() -> Cc2Codec.request(0, Cc2Codec.DISK));
        changed();
    }
    /** The printer's own AI detection settings (method 1062), read once in a while over the local connection; null until answered. */
    public volatile JSONObject aiDetection;
    private long aiAskedAt;
    public void readAiDetection() {
        long now = SystemClock.elapsedRealtime();
        if (!ready() || session == null || now - aiAskedAt < 60_000 && aiAskedAt > 0) return;
        aiAskedAt = now;
        try {
            session.probe(new JSONObject().put("method", 1062).put("params", new JSONObject()), (result, error) -> main.post(() -> { if (result != null && result.optInt("error_code", 0) == 0) { aiDetection = result; changed(); } }));
        } catch (org.json.JSONException impossible) { }
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
        received(file, name, filePrinterId());
    }
    private void received(File file, String name, String forPrinter) {
        importing = true; feedback = "Download received. Inspecting kept copy…"; changed();
                files.execute(() -> {
                    keep(file, name, forPrinter, GcodeLibrary.Origin.DOWNLOADED);
                    GcodeInspector.Report report = null;
                    try { report = GcodeInspector.inspect(file); } catch (Exception ignored) { }
                    GcodeInspector.Report result = report;
                    android.graphics.Bitmap preview = report == null ? null : ThumbnailDecoder.decode(report.thumbnail);
                    main.post(() -> finishFile(file, name, result, preview, "Downloaded and kept in this app. Save to phone… exports a copy; the printer's copy is unchanged."));
                });
    }
    /** Retains input bytes as an unverified candidate; selecting the live job's copy is separate. */
    void keep(File file, String name) {
        keep(file, name, "", GcodeLibrary.Origin.IMPORTED);
    }
    private void keep(File file, String name, String forPrinter, GcodeLibrary.Origin origin) {
        try { if (library != null) library.retain(file, name, forPrinter, origin); }
        catch (IOException error) { Diagnostics.note(Diagnostics.FILES, "G-code archive copy unavailable: " + error.getClass().getSimpleName()); }
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
        String stored = cloudRecords() && "local".equals(storage) ? cloudThumbnail(filename) : null;
        if (stored != null) {
            // The picture the printer stored with Elegoo, by the name the cloud's record gives; fetched once.
            queryBusy.add(Cc2Codec.THUMBNAIL); String key = storage + "/" + filename; CloudApi cloud = cloudApi; String forPrinter = cloudSerial;
            cloudReads.execute(() -> {
                String data = "";
                try {
                    // A link in the record itself is followed only on Elegoo's own hosts; otherwise the cloud signs one for the named object.
                    String host = stored.startsWith("https://") ? CloudApi.hostOf(stored) : null;
                    if (stored.contains("://") && (host == null || !(host.equals("elegoo.com") || host.endsWith(".elegoo.com") || host.equals("elegoo.com.cn") || host.endsWith(".elegoo.com.cn")))) throw new IOException("unexpected host");
                    String link = stored.contains("://") ? stored : cloud.signedLink(stored);
                    java.net.HttpURLConnection connection = cloudConnections.open(new java.net.URL(link));
                    connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(10_000); connection.setReadTimeout(15_000);
                    try (InputStream in = connection.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                        if (connection.getResponseCode() != 200) throw new IOException("HTTP " + connection.getResponseCode());
                        byte[] buffer = new byte[16384]; int n; while ((n = in.read(buffer)) != -1) { bytes.write(buffer, 0, n); if (bytes.size() > 1 << 20) throw new IOException("too large"); }
                        data = android.util.Base64.encodeToString(bytes.toByteArray(), android.util.Base64.NO_WRAP);
                    } finally { connection.disconnect(); }
                } catch (Exception error) { Diagnostics.note(Diagnostics.FILES, "cloud thumbnail unavailable: " + error.getClass().getSimpleName()); }
                String picture = data;
                main.post(() -> { queryBusy.remove(Cc2Codec.THUMBNAIL); if (cloudStill(cloud, forPrinter)) thumbnails.put(key, picture); changed(); });
            });
            return;
        }
        queryBusy.add(Cc2Codec.THUMBNAIL);
        try {
            JSONObject request = Cc2Codec.thumbnailRequest(0, storage, filename);
            if (ready()) session.request(request); else cloudSafe(() -> request);
        } catch (Exception error) { queryBusy.remove(Cc2Codec.THUMBNAIL); }
    }
    /** The thumbnail object the cloud's record names for this file, or null. */
    private String cloudThumbnail(String filename) {
        org.json.JSONArray list = filePage.optJSONArray("file_list");
        for (int i = 0; list != null && i < list.length(); i++) { JSONObject file = list.optJSONObject(i); if (file != null && filename.equals(file.optString("filename"))) { String t = file.optString("cloud_thumbnail", ""); return t.isEmpty() ? null : t; } }
        return null;
    }
    public void temperatures(int nozzle, int bed) {
        if (!Cc2Codec.heatersAdjustable(liveStatus())) return;
        if (fresh()) session.temperatures(nozzle, bed); else if (viaCloud()) cloudSafe(() -> Cc2Codec.temperatureRequest(0, nozzle, bed));
    }
    /** Asks for the CANVAS trays again (a read): with the status locally, on its own through the cloud. */
    public void refreshTrays() {
        if (ready()) session.refresh();
        else if (viaCloud()) cloudSafe(() -> Cc2Codec.request(0, Cc2Codec.CANVAS));
    }
    public void fan(String name, int percent) { if (fresh()) session.fan(name, percent); else if (viaCloud()) cloudSafe(() -> Cc2Codec.fanRequest(0, name, percent)); }
    public void speed(int mode) {
        if (!Cc2Codec.canPause(liveStatus())) return;
        if (fresh()) session.speed(mode); else if (viaCloud()) cloudSafe(() -> Cc2Codec.speedRequest(0, mode));
    }
    private void resetData() {
        status = new JSONObject(); attributes = new JSONObject(); canvas = null; canvasAt = 0; aiDetection = null; aiAskedAt = 0;
        filePage = new JSONObject(); disk = new JSONObject(); history = new JSONObject(); filesAt = 0; fileOffset = 0; cameraUrl = "";
        historyDetails.clear(); historyDetailMessages.clear(); queryBusy.clear(); fileMessage = "Refresh to browse printer files."; historyMessage = "Refresh to load print history.";
    }
    public void disconnect() {
        fileJobs.clear();
        if (followed) followPaused = true;   // disconnected by hand: no automatic local connection until the printer is seen in cloud mode again
        followed = false;
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
                keep(local, name);   // retained candidate; the filename alone does not identify the live job
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
    /**
     * The chosen file with the CC2 filament load added for CANVAS `tray` (0-3), as a new phone copy named "…-load.gcode"; the
     * original stays as it was. Nothing is sent to the printer.
     */
    public void addFilamentLoad(int tray) {
        if (selectedFile == null || selectedName == null || fileBusy()) return;
        File source = selectedFile; String name = selectedName.replaceFirst("(?i)\\.gcode$", "") + "-load.gcode";
        importing = true; feedback = "Adding the filament load…"; changed();
        files.execute(() -> {
            String text; File repaired = new File(getCacheDir(), "repaired.gcode");
            try {
                GcodeRepair.Result result = GcodeRepair.addFilamentLoad(source, repaired, tray, 220);
                if (result == null) text = "This file already loads filament or never extrudes; nothing was added.";
                else {
                    Diagnostics.note(Diagnostics.FILES, "filament load added: tray " + (tray + 1) + ", " + result.temperature + " °C, before line " + result.line);
                    main.post(() -> { importing = false; selectSliced(repaired, name, "Filament load added for tray " + (tray + 1) + " at " + result.temperature + " °C, before line " + result.line
                        + ". This is a new copy; check it in Preview, and keep an eye on the first layer.", false); });
                    return;
                }
            } catch (Exception error) { text = "The filament load could not be added (" + error.getClass().getSimpleName() + ")."; }
            String done = text; main.post(() -> { importing = false; feedback = done; changed(); });
        });
    }
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
                keep(local, name, "", GcodeLibrary.Origin.SLICED);
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
    private String loggedConnection = "", loggedFeedback = ""; private int loggedOnline = -2;
    // ------------------------------------------------------------------ Home Assistant link (Settings › Home Assistant)
    final HomeAssistant ha = new HomeAssistant(HomeAssistant.paho());
    private final java.util.concurrent.ExecutorService haWorker = java.util.concurrent.Executors.newSingleThreadExecutor();
    private volatile boolean haBusy; private long haAt; private String haNode;
    /** On a status change: sends the shown status to the user's broker (read only), at most every 10 s, on its own thread. */
    private void homeAssistant() {
        android.content.SharedPreferences settings = workshop();
        if (!settings.getBoolean("haEnabled", false)) { if (haNode != null) { String node = haNode; haNode = null; haWorker.execute(() -> ha.stop(node)); } return; }
        long now = SystemClock.elapsedRealtime(); if (haBusy || now - haAt < 10_000) return;
        String id = wanted ? serial : cloudSerial; if (id == null || id.isEmpty()) id = host == null ? "" : host;
        if (id.isEmpty()) return;
        JSONObject shownNow = wanted ? (ready() && fresh() ? status : null) : (cloudFresh() ? cloudStatus : null);
        String route = wanted ? (ready() ? "local network" : "not connected") : cloudFresh() ? "Elegoo cloud" : "not connected";
        String host = settings.getString("haHost", "").trim(), user = settings.getString("haUser", "").trim(); int port = settings.getInt("haPort", 1883); boolean tls = settings.getBoolean("haTls", false);
        if (host.isEmpty()) return;
        String node = HomeAssistant.node(id), name = wanted || cloudName.isEmpty() ? "Centauri Carbon 2" : StatusPresentation.clean(cloudName);
        PrintRecorder.Recording recording = recorder == null ? null : recorder.current();
        haBusy = true; haAt = now; haNode = node;
        haWorker.execute(() -> {
            try {
                String password = ""; try { CredentialStore store = new CredentialStore(this); if (store.remembers("homeassistant:" + host)) password = store.load("homeassistant:" + host); } catch (Exception unreadable) { }
                JSONObject state; try { state = HomeAssistant.state(shownNow, route, System.currentTimeMillis()); } catch (Exception impossible) { return; }
                byte[] picture = null; String pictureKey = null;
                List<java.io.File> pictures = recording == null ? null : PrintRecorder.pictures(recording);
                if (pictures != null && !pictures.isEmpty()) { java.io.File newest = pictures.get(pictures.size() - 1); pictureKey = newest.getPath();
                    try { picture = java.nio.file.Files.readAllBytes(newest.toPath()); } catch (Exception unreadable) { } }
                ha.publish(host, port, tls, user, password, null, node, name, state, picture, pictureKey, System.currentTimeMillis());
            } finally { haBusy = false; }
        });
    }
    private static volatile boolean offThreadNoted;
    private void changed() {
        if (destroyed) return;
        // Screens are only ever told on the screen thread: a change noticed by a background task is handed over, so no
        // screen updates its views from another thread (that left stale layouts, such as a button row measured for
        // other buttons).
        if (Looper.myLooper() != Looper.getMainLooper()) {
            if (!offThreadNoted) { offThreadNoted = true; Diagnostics.note(MainThreadWatch.AREA, "a status change came from a background thread (" + Thread.currentThread().getName() + ", in: " + MainThreadWatch.frames(Thread.currentThread().getStackTrace()) + "); handed to the screen thread"); }
            main.post(this::changed); return;
        }
        // The log keeps every change of the connection line, the cloud's online state and each message shown to the user.
        if (!connection.equals(loggedConnection)) { loggedConnection = connection; Diagnostics.note(Diagnostics.CONNECTION, Diagnostics.scrub(connection)); }
        if (cloudOnline != loggedOnline && usingCloud()) { loggedOnline = cloudOnline; Diagnostics.note(Diagnostics.CONNECTION, "Elegoo cloud says the printer is " + (cloudOnline == 1 ? "online" : cloudOnline == 0 ? "offline" : "of unknown state")); }
        if (feedback != null && !feedback.isEmpty() && !feedback.equals(loggedFeedback)) { loggedFeedback = feedback; Diagnostics.note(Diagnostics.MESSAGES, Diagnostics.scrub(feedback)); }
        if (observer != null) observer.changed();
        for (Observer watcher : new ArrayList<>(watchers)) watcher.changed();
        homeAssistant();
        // Home-screen widget: the status shown here, locally or through the cloud (throttled inside).
        try {
            JSONObject shownNow = wanted ? (ready() && fresh() ? status : null) : (cloudFresh() ? cloudStatus : null);
            PrintWidget.update(this, shownNow, wanted || cloudName.isEmpty() ? "Centauri Carbon 2" : StatusPresentation.clean(cloudName), progressPicture);
        } catch (RuntimeException widgetTrouble) { }
    }

    /** Monitoring through the cloud while the app is visible; background watching is a separate opt-in setting. */
    public void cloudVisible(boolean visible) {
        cloudVisible = visible; if (visible) { main.removeCallbacks(cloudPoll); main.post(cloudPoll); }
        if (visible && localHost == null && usingCloud() && workshop().getBoolean("localCameraInCloud", false)) findLocalHost();
    }
    public boolean cloudBackground() { return getSharedPreferences("workshop-settings", MODE_PRIVATE).getBoolean("cloudBackground", false) && cloudAccountPresent(); }
    /** Called after the setting changes or the account changes, while the app is visible. */
    public void cloudSettingsChanged() {
        if (!ready()) fileJobs.clear();
        // A new sign-in or printer: answers still on their way from the old one are dropped (they check cloudApi), its
        // command channel is closed, and nothing it reported counts as fresh any more.
        cloudApi = null;
        if (cloudControl != null) { cloudControl.close(); cloudControl = null; }
        cloudStatus = new JSONObject(); cloudCheckedAt = 0; if (!ready()) filesAt = 0;
        if (cloudBackground() && !wanted) startMonitoring();
        else if (!wanted && foreground) { stopForeground(STOP_FOREGROUND_REMOVE); foreground = false; stopSelf(); }
        main.removeCallbacks(cloudPoll); main.post(cloudPoll);
    }
    /**
     * Saves a renewed sign-in only when the stored account is still the one that was renewed (same user, same refresh
     * token): a renewal that finishes after signing out, or after signing in as someone else, is dropped.
     */
    static void saveRenewed(CloudAccountStore store, CloudLogin.Account from, CloudLogin.Account renewed) {
        try {
            synchronized (CloudAccountStore.class) {
                CloudLogin.Account stored = store.load();
                if (stored != null && from != null && stored.userId.equals(from.userId) && stored.refreshToken.equals(from.refreshToken)) store.save(renewed);
                else Diagnostics.note(Diagnostics.FILES, "a renewed Elegoo sign-in was not saved: the account changed meanwhile");
            }
        } catch (Exception ignored) { }
    }
    /** An answer read through `api` for `serial` still belongs to what is shown: same sign-in and printer, still watched through the cloud. */
    private boolean cloudStill(CloudApi api, String serial) { return !destroyed && api != null && cloudApi == api && serial.equals(cloudSerial) && cloudRecords(); }
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
                api.onAccountChanged((from, renewed) -> saveRenewed(store, from, renewed));
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
            if (api.account() != before) saveRenewed(cloudAccounts, before, api.account());
            CloudApi.Device found = device; CloudApi polled = api; int reportedOnline = online; CloudApi.Snapshot reported = snapshot; String text = message;
            main.post(() -> {
                if (destroyed || cloudApi != polled) return;   // signed out, or another account or printer meanwhile: this answer is not theirs
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
                    if (!wanted && cloudOnline == 1 && cloudReportRecent()) { alertChoices(); showAlert(cloudAlerts.update(reported.status), cloudName); showAlert(watched(cloudWatch, reported.status), cloudName); record(reported.status, "cloud", cloudName); }
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
                    if (cloudOnline == 1) { alertChoices(); showAlert(cloudAlerts.update(merged), cloudName); showAlert(watched(cloudWatch, merged), cloudName); record(merged, "cloud", cloudName); }
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
    /**
     * Camera check's "Ask the printer for its camera": Elegoo's read-only 1042 question, once, through the cloud. The answer is
     * described (fields, and the kind of address without the address itself) and logged; nothing is opened from it.
     */
    public boolean askCloudCamera(java.util.function.Consumer<String> done) {
        CloudApi api = cloudApi;
        if (!usingCloud() || !cloudFresh() || api == null || cloudSerial.isEmpty()) return false;
        if (cloudCommandBusy()) return false;
        ensureCloudControl();
        Diagnostics.note(Diagnostics.CAMERA, "asked the printer for its camera (1042) through the cloud");
        cloudControl.askCamera(cloudSerial, new CloudControl.Reply() {
            public void done(boolean acknowledged, String text) { done(acknowledged, text, new JSONObject()); }
            public void done(boolean acknowledged, String text, JSONObject result) {
                String summary = CloudControl.describeCameraAnswer(acknowledged, text, result);
                if (acknowledged) cameraQuestionAt = SystemClock.elapsedRealtime();
                Diagnostics.note(Diagnostics.CAMERA, "camera question answer: " + summary);
                main.post(() -> done.accept(summary));
            }
        });
        return true;
    }
    public void cloudCommand(JSONObject request) {
        int method = request.optInt("method", -1);
        boolean query = Cc2Codec.isQuery(method) || method == Cc2Codec.CANVAS;
        CloudApi api = cloudApi;
        if (!usingCloud() || !cloudFresh() || api == null) { queryBusy.remove(method); return; }
        if (cloudCommandBusy() && method != Cc2Codec.URGENT_STOP) {   // emergency stop is never held back (CloudControl.urgentStop)
            queryBusy.remove(method);
            if (method != Cc2Codec.CANVAS) { feedback = "Waiting for the previous cloud command to finish."; changed(); }
            return;
        }
        ensureCloudControl();
        if (!query) { feedback = "Sending through the Elegoo cloud…"; Diagnostics.note(Diagnostics.COMMANDS, "Elegoo cloud: " + Cc2Codec.name(method) + " sent"); }
        JSONObject params = request.optJSONObject("params") == null ? new JSONObject() : request.optJSONObject("params");
        cloudControl.send(cloudSerial, request, new CloudControl.Reply() {
            public void done(boolean acknowledged, String text) { done(acknowledged, text, new JSONObject()); }
            public void done(boolean acknowledged, String text, JSONObject result) {
                main.post(() -> {
                    if (query) { if (acknowledged) handleQuery(method, params, result); else handleQueryError(method, text); changed(); return; }
                    Diagnostics.note(Diagnostics.COMMANDS, "Elegoo cloud: " + Cc2Codec.name(method) + (acknowledged ? " acknowledged" : " not acknowledged") + ": " + Diagnostics.scrub(text));
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
    static final int[] PROBE_PORTS = {21, 22, 23, 80, 443, 554, 1883, 3030, 7125, 8080, 8554, 8883, 9001};
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
            // What Elegoo's cloud keeps about the printer's files and prints (the SDK reads these there, not from the printer).
            CloudApi cloud = cloudApi; String cloudPrinter = cloudSerial;
            if (cloud != null && !cloud.needsSignIn() && !cloudPrinter.isEmpty()) {
                main.post(() -> { probeText = "Reading what Elegoo's cloud keeps about the printer's files…"; changed(); });
                for (String line : cloud.probeFiles(cloudPrinter)) Diagnostics.note(Diagnostics.PROBE, line);
            }
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
        main.post(() -> { probeText = "Checking the printer's network ports…"; changed(); });
        // In cloud mode port 8080 has been seen to stop accepting connections after a few probes until a power cycle
        // (dnviti/ha-generic-3dprinter-controller docs/protocol-elegoo-cc2.md), so it is left alone there.
        int[] checked = local ? PROBE_PORTS : java.util.Arrays.stream(PROBE_PORTS).filter(p -> p != 8080).toArray();
        String ports = PrinterHttp.probePorts(via.sockets(), address, checked) + (local ? "" : ", 8080 not checked in cloud mode");
        StringBuilder text = new StringBuilder(local ? "over the local connection's route" : "over this Wi-Fi").append(" · ports: ").append(ports);
        for (int port : new int[] {80, 8080, 3030})
            if ((", " + ports).contains(", " + port + " open")) text.append(" · HTTP ").append(port).append(": ").append(PrinterHttp.httpHead(via.sockets(), address, port));
        // Services that greet first (FTP, SSH, Telnet): their greeting only; nothing is sent and no login is tried.
        for (int port : new int[] {21, 22, 23})
            if ((", " + ports).contains(", " + port + " open")) text.append(" · greeting ").append(port).append(": ").append(PrinterHttp.greeting(via.sockets(), address, port));
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
    /** "Listen for a print sent from Elegoo's app": the cloud control link stays up 15 minutes, sending nothing (CloudControl.heard). */
    public volatile String otherAppListen = "";
    public void listenForOtherApps() {
        if (cloudApi == null || cloudSerial.isEmpty()) { otherAppListen = "Sign in with Elegoo and choose the printer first."; changed(); return; }
        ensureCloudControl();
        String serialNow = cloudSerial; int before = CloudFileRoute.heardCount(serialNow);
        otherAppListen = "Listening for 15 minutes. Start a print from Elegoo's app now; nothing is sent to the printer."; changed();
        cloudControl.listen(15 * 60_000L, error -> main.post(() -> { if (!error.isEmpty()) { otherAppListen = "Could not listen: " + StatusPresentation.clean(error); changed(); } }));
        main.postDelayed(() -> {
            otherAppListen = CloudFileRoute.heardCount(serialNow) > before ? "The printer named the G-code it fetched: the live toolpath can download it through the cloud."
                : "Done listening. The printer did not name a G-code. The log (Local and cloud) lists what it sent, without values: please share it.";
            changed();
        }, 15 * 60_000L);
    }
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
    /** The layer alerts the user asked for (Settings, and a per-print layer from the Print tab), handed to both alert trackers. */
    private void alertChoices() {
        android.content.SharedPreferences settings = getSharedPreferences("workshop-settings", MODE_PRIVATE);
        for (PrintAlerts a : new PrintAlerts[] {alerts, cloudAlerts}) {
            a.firstLayer = settings.getBoolean("alertFirstLayer", false); a.layer = settings.getInt("alertLayer", 0); a.layerKey = settings.getString("alertLayerKey", "");
        }
    }
    private void showAlert(String text, String title) {
        if (text.isEmpty() || !getSharedPreferences("workshop-settings", MODE_PRIVATE).getBoolean("alerts", true)) return;
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("printer-alerts", "Print completion and faults", NotificationManager.IMPORTANCE_DEFAULT));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        int id = text.startsWith("Print complete") ? 2 : text.startsWith("Printer fault") ? 3 : text.startsWith("First layer") || text.startsWith("Layer ") ? 4 : 5;
        Notification.Builder builder = new Notification.Builder(this, "printer-alerts").setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Link Workshop · " + StatusPresentation.clean(title)).setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text)).setContentIntent(open).setAutoCancel(true)
            .setOnlyAlertOnce(true);
        manager.notify(id, builder.build());
        Diagnostics.note(Diagnostics.NOTIFICATIONS, Diagnostics.scrub(text));
        // Then, on the local connection, the same alert again with a picture from the printer's camera (it alerts only once).
        if (getSharedPreferences("workshop-settings", MODE_PRIVATE).getBoolean("alertPictures", true))
            grab(image -> {
                // Small copies only: a notification travels between processes, and a full camera frame (megabytes) can be refused.
                android.graphics.Bitmap picture = scaled(image, 640), icon = scaled(image, 128);
                manager.notify(id, builder.setLargeIcon(icon).setStyle(new Notification.BigPictureStyle().bigPicture(picture).setSummaryText(text)).build());
            });
    }
    /** A copy at most `width` pixels wide, in 16-bit colour (a quarter of a full-size ARGB frame or less). */
    static android.graphics.Bitmap scaled(android.graphics.Bitmap image, int width) {
        int w = Math.min(width, image.getWidth()), h = Math.max(1, Math.round(image.getHeight() * (w / (float) image.getWidth())));
        android.graphics.Bitmap small = android.graphics.Bitmap.createScaledBitmap(image, w, h, true);
        android.graphics.Bitmap compact = small.copy(android.graphics.Bitmap.Config.RGB_565, false);
        return compact != null ? compact : small;
    }
    /** The progress notification's picture: a fresh frame every 2 minutes on the local connection, else the print's newest recording picture. */
    private volatile android.graphics.Bitmap progressPicture;
    private String progressPictureKey = "";
    private long progressGrabAt;
    private android.graphics.Bitmap progressPicture() {
        long now = SystemClock.elapsedRealtime();
        if ((ready() || ExtraCameras.forPictures(this) != null) && (progressGrabAt == 0 || now - progressGrabAt > 120_000)) {
            progressGrabAt = now;
            grab(image -> updateNotification(true));
        }
        if (!"live".equals(progressPictureKey) && recorder != null) {
            PrintRecorder.Recording current = recorder.current();
            List<java.io.File> pictures = current == null ? null : PrintRecorder.pictures(current);
            if (pictures != null && !pictures.isEmpty()) {
                java.io.File newest = pictures.get(pictures.size() - 1);
                if (!newest.getPath().equals(progressPictureKey)) {
                    android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options(); options.inSampleSize = 2;
                    android.graphics.Bitmap read = android.graphics.BitmapFactory.decodeFile(newest.getPath(), options);
                    if (read != null) { progressPicture = scaled(read, 640); progressPictureKey = newest.getPath(); }
                }
            }
        }
        return progressPicture;
    }
    private interface Frame { void take(android.graphics.Bitmap image); }

    private Notification notification() {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent view = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent disconnect = PendingIntent.getService(this, 1, new Intent(this, PrinterService.class).setAction(DISCONNECT), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String text = !ready() ? connection.split("\n")[0] : !fresh() ? "Waiting for printer" : StatusPresentation.state(status);
        if (!wanted && cloudFresh()) text = "Cloud · " + StatusPresentation.overview(cloudStatus).split("\n")[0];
        else if (!wanted && cloudBackground() && !cloudMessage.isEmpty()) text = "Cloud watching paused: " + cloudMessage.split("\n")[0];
        if (ready() && fresh() && !StatusPresentation.faultCodes(status).isEmpty()) text += " · Printer reports a fault";
        if (uploading()) text = feedback;
        JSONObject running = wanted ? (ready() && fresh() ? status : null) : (cloudFresh() ? cloudStatus : null);
        JSONObject runningMachine = running == null ? null : running.optJSONObject("machine_status");
        boolean printingNow = runningMachine != null && runningMachine.optInt("status", -1) == 2 && !uploading();
        if (printingNow) {
            // While printing: "41% · layer 87 of 212 · 1h 15m left · done ≈ 14:30", with a progress bar.
            JSONObject print = running.optJSONObject("print_status");
            long remaining = print == null ? -1 : print.optLong("remaining_time_sec", -1);
            text = StatusPresentation.runningLine(runningMachine, print) + (remaining > 0 ? " · done ≈ " + StatusPresentation.doneAt(System.currentTimeMillis(), remaining, java.util.Locale.getDefault(), java.util.TimeZone.getDefault()) : "")
                + (wanted ? "" : " · cloud") + (ready() && fresh() && !StatusPresentation.faultCodes(status).isEmpty() ? " · Printer reports a fault" : "");
        }
        String title = wanted || cloudName.isEmpty() ? host : StatusPresentation.clean(cloudName);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_launcher).setContentTitle("Link Workshop · " + title)
            .setContentText(text).setContentIntent(view).setOngoing(true).setOnlyAlertOnce(true);
        if (printingNow && runningMachine.has("progress")) builder.setProgress(100, Math.max(0, Math.min(100, runningMachine.optInt("progress"))), false);
        // A look at the print: a camera picture (expand the notification to see it larger).
        android.graphics.Bitmap look = printingNow && getSharedPreferences("workshop-settings", MODE_PRIVATE).getBoolean("alertPictures", true) ? progressPicture() : null;
        if (!printingNow) { progressPicture = null; progressPictureKey = ""; }
        // Android 16: a Live Update while printing (progress in the notification's own style, time left as a status-bar chip);
        // the picture becomes the notification's icon. Earlier Android keeps the large picture.
        boolean live = printingNow && Build.VERSION.SDK_INT >= 36 && getSharedPreferences("workshop-settings", MODE_PRIVATE).getBoolean("liveProgress", true);
        if (live) {
            JSONObject print = running.optJSONObject("print_status");
            long remaining = print == null ? -1 : print.optLong("remaining_time_sec", -1);
            Notification.ProgressStyle progress = new Notification.ProgressStyle().setStyledByProgress(true)
                .setProgress(Math.max(0, Math.min(100, runningMachine.optInt("progress", 0))));
            builder.setStyle(progress);
            if (remaining > 0) builder.setShortCriticalText(StatusPresentation.span(remaining));
            android.os.Bundle promote = new android.os.Bundle(); promote.putBoolean("android.requestPromotedOngoing", true); builder.addExtras(promote);
            if (look != null) builder.setLargeIcon(scaled(look, 256));
        } else if (look != null) builder.setLargeIcon(scaled(look, 128)).setStyle(new Notification.BigPictureStyle().bigPicture(look).setSummaryText(text));
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
    /** Print guardian (beta): each kept picture compared with the toolpath so far (see GuardianRun). Notify only, never acts on the printer. */
    public GuardianRun guardian;   // made in onCreate (the service has no context before)
    private void guard(PrintRecorder.Recording recording, byte[] jpeg, int layer, int trayRgb, String cameraId) {
        if (!getSharedPreferences("workshop-settings", MODE_PRIVATE).getBoolean("guardian", true)) return;
        JSONObject now = liveStatus(), print = now.optJSONObject("print_status"), move = now.optJSONObject("gcode_move_inf");
        int total = print == null ? 0 : print.optInt("total_layer", 0);
        double[] head = move == null ? new double[] {Double.NaN, Double.NaN, Double.NaN}
            : new double[] {move.optDouble("x", Double.NaN), move.optDouble("y", Double.NaN), move.optDouble("z", Double.NaN)};
        guardian.check(recording, jpeg, layer, total, head, trayRgb, cameraId, fileJob(), this::guardianAlarm, this::changed);
    }
    private void guardianAlarm(String reason, android.graphics.Bitmap overlay) {
        // The alarm is an event for automations (webhooks, Home Assistant) whether or not the phone may show notifications;
        // the permission only decides the notification itself.
        Diagnostics.note(Diagnostics.NOTIFICATIONS, "print guardian: " + reason);
        if (automations != null) automations.outside(wanted ? automations.local : automations.cloud, Automation.GUARDIAN, reason, liveStatus(), wanted ? host : cloudName);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("printer-alerts", "Print completion and faults", NotificationManager.IMPORTANCE_DEFAULT));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String text = "The camera picture does not look like the print should: " + reason + ". Check the print. (Beta: it can be wrong.)";
        Notification.Builder builder = new Notification.Builder(this, "printer-alerts").setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Link Workshop · Print guardian").setContentText(text).setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(true)
            .setStyle(overlay == null ? new Notification.BigTextStyle().bigText(text) : new Notification.BigPictureStyle().bigPicture(overlay).setSummaryText(text));
        if (overlay != null) builder.setLargeIcon(scaled(overlay, 128));
        manager.notify(6, builder.build());
    }
    /** Automation workshop rules (see AutomationRun); made in onCreate. */
    public AutomationRun automations;
    private final AutomationRun.Host automationHost = new AutomationRun.Host() {
        public String lightBlocked() {
            if (ready() && fresh()) return "";
            if (viaCloud()) return workshop().getBoolean("cloudControlUnderstood", false) ? "" : "cloud control is not turned on yet (use a control in the app once to agree)";
            return "no live connection to the printer";
        }
        public void light(boolean on) { if (ready() && fresh()) PrinterService.this.light(on); else cloudLight(on); }
        public JSONObject liveStatus() { return PrinterService.this.liveStatus(); }
        public JSONObject canvas() { return canvas; }
        public java.util.Map<String, String> appValues() {
            java.util.Map<String, String> v = new java.util.LinkedHashMap<>();
            PrintRecorder.Recording running = recorder == null ? null : recorder.current();
            if (running != null) {
                v.put("pictures", String.valueOf(running.pictures));
                if (running.extrudedMm > 0) v.put("filament_used_m", String.format(java.util.Locale.ROOT, "%.2f", running.extrudedMm / 1000));
                long learned = recorder.learnedRemaining(SystemClock.elapsedRealtime());
                if (learned >= 0) v.put("done_at_learned", StatusPresentation.doneAt(System.currentTimeMillis(), learned, java.util.Locale.getDefault(), java.util.TimeZone.getDefault()));
            }
            if (guardian != null && !guardian.verdict.isEmpty()) v.put("guardian", guardian.verdict);
            double rate = chamberTrend.perMinute(); if (!Double.isNaN(rate)) v.put("chamber_rate", String.format(java.util.Locale.ROOT, "%.2f", rate));
            JSONObject tray = PrintWatch.activeTray(canvas), chamber = liveStatus().optJSONObject("ztemperature_sensor");
            double goal = ChamberTrend.goal(tray == null ? "" : tray.optString("filament_type"));
            if (!Double.isNaN(goal) && chamber != null && chamber.has("temperature")) {
                v.put("chamber_goal", String.valueOf(Math.round(goal)));
                long ready = chamberTrend.secondsTo(goal, chamber.optDouble("temperature"));
                if (ready >= 0) v.put("chamber_ready_in", String.valueOf(Math.round(ready / 60.0)));
            }
            return v;
        }
        public PrintRecorder.Recording recordingNow() { return recorder == null ? null : recorder.current(); }
        public String printerKey() { return ready() ? "local:" + serial : usingCloud() ? "cloud:" + cloudSerial : ""; }
        /** The given print, or the one being recorded, or else the last one that finished. */
        private PrintRecorder.Recording which(PrintRecorder.Recording recording) {
            if (recording != null) return recording;
            PrintRecorder.Recording running = recorder == null ? null : recorder.current();
            if (running != null || recorder == null) return running;
            java.io.File meta = recorder.lastFinished();
            try { return meta == null ? null : PrintRecorder.Recording.read(meta); } catch (Exception unreadable) { return null; }
        }
        public byte[] latestPicture(PrintRecorder.Recording recording) {
            PrintRecorder.Recording chosen = which(recording);
            List<java.io.File> pictures = chosen == null ? null : PrintRecorder.pictures(chosen);
            if (pictures != null && !pictures.isEmpty()) try { return java.nio.file.Files.readAllBytes(pictures.get(pictures.size() - 1).toPath()); } catch (Exception unreadable) { }
            // The picture on screen only stands in for the print being recorded now.
            android.graphics.Bitmap shown = progressPicture;
            if (shown == null || recorder == null || chosen != recorder.current()) return null;
            java.io.ByteArrayOutputStream jpeg = new java.io.ByteArrayOutputStream(); shown.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, jpeg); return jpeg.toByteArray();
        }
        public List<double[]> chartRows(PrintRecorder.Recording recording) {
            PrintRecorder.Recording chosen = which(recording);
            if (chosen == null) return null;
            try { return PrintRecorder.samples(chosen); } catch (Exception unreadable) { return null; }
        }
        public byte[] chartPng(PrintRecorder.Recording recording, List<double[]> rows) {
            PrintRecorder.Recording chosen = recording != null ? recording : recorder == null ? null : recorder.current();
            try { return PrintChart.png(PrinterService.this, chosen, rows); } catch (Exception failed) { return null; }
        }
        public void notifyPerson(int id, String title, String text, android.graphics.Bitmap picture) {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return;
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(new NotificationChannel("automations", "Automations", NotificationManager.IMPORTANCE_DEFAULT));
            PendingIntent open = PendingIntent.getActivity(PrinterService.this, 0, new Intent(PrinterService.this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder builder = new Notification.Builder(PrinterService.this, "automations").setSmallIcon(R.drawable.ic_launcher).setContentTitle(StatusPresentation.clean(title))
                .setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text)).setContentIntent(open).setAutoCancel(true);
            if (picture != null) builder.setLargeIcon(scaled(picture, 128)).setStyle(new Notification.BigPictureStyle().bigPicture(scaled(picture, 720)).setSummaryText(text));
            manager.notify(id, builder.build());
        }
        public String homeAssistantEvent(JSONObject event, byte[] chart) {
            if (!workshop().getBoolean("haEnabled", false)) return "link is off; skipped";
            String node = haNode; if (node == null) return "not connected yet; event not sent";
            haWorker.execute(() -> Diagnostics.note(Diagnostics.AUTOMATIONS, "Home Assistant: " + ha.event(node, event, chart)));
            return "event handed to the Home Assistant link";
        }
    };
    @Override public void onDestroy() {
        if (guardian != null) guardian.shutdown();
        if (automations != null) automations.shutdown();
        { String node = haNode; haWorker.execute(() -> ha.stop(node)); haWorker.shutdown(); }   // Home Assistant shows the printer offline
        destroyed = true; observer = null; watchers.clear(); clearRoute(); main.removeCallbacksAndMessages(null);
        if (cloudControl != null) cloudControl.close(); CloudLive live = cloudLive; if (live != null) cloudWorker.execute(live::close); cloudWorker.shutdown();
        if (session != null) session.close(); session = null; code = "";
        probeWorker.shutdownNow(); files.shutdownNow(); if (selectedFile != null) selectedFile.delete();
        if (foreground) stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy();
    }
}
