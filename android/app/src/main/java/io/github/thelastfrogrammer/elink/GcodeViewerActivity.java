package io.github.thelastfrogrammer.elink;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.*;
import androidx.webkit.WebViewAssetLoader;
import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 3D toolpath viewer (assets/viewer, WebGL) for a G-code file, with layer and move scrubbing, playback and feature
 * filters. In follow mode it tracks the running print: the printer's reported layer and nozzle position select the
 * printed part, and the rest of the current layer shows as a ghost ahead of the nozzle.
 */
public final class GcodeViewerActivity extends Activity implements PrinterService.Observer {
    static final String EXTRA_FILE = "file", EXTRA_NAME = "name", EXTRA_FOLLOW = "follow", EXTRA_ALIGN = "align";
    private static final int CHOOSE = 1;
    private static final String ORIGIN = "https://appassets.androidplatform.net";
    /** Feature colors in GcodeToolpath.FEATURES order; also sent to the viewer page. */
    static final String[] PALETTE = {"#ff7d38", "#ffd24d", "#3366ff", "#b03029", "#9654cc", "#f04848", "#5f9e5f", "#4d80ba", "#e8e8e8",
        "#00876e", "#00876e", "#45c445", "#1e7b3b", "#6a8f6a", "#b3b3b3", "#ffb3b3", "#5ed9c7", "#9a9a9a"};

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private boolean dark, pageReady, sent, following, followMode, playing, showTravel, bound;
    private int ink, muted, teal, background, surface, buttonColor;
    private WebView web;
    private TextView title, status, layerLabel, moveLabel;
    private SeekBar layerBar, moveBar;
    private Button play, follow, firstLayer, more;
    private Flow legend;
    private LinearLayout missingCard;
    private GcodeToolpath path;
    private byte[] segments, travels, meta;
    private String loadedName, loadingName;
    private int layer, move, hidden, lastLocated = -1, loggedLayer = -1;
    private boolean lastAttempt; // a download was tried: keep showing its outcome
    private long loggedAt;
    private PrinterService printer;
    private GcodeLibrary library;
    // The printer's camera in the scene (Live only): its position (an estimate the user can change) and the picture.
    static final String[] CAMERA_SPOTS = {"CC2 camera (lined up on a real printer)", "Front right, top", "Back left, top", "Back right, top", "Front centre, top", "Lined up by hand"};
    static final int LINED_UP = 5;
    private LinearLayout root, mainPanel, alignPanel;
    private android.widget.ScrollView alignScroll;
    private double[] aligning, alignStart;
    private double alignZ;
    private CameraFrames cameraFrames;
    private boolean cameraStarted, cameraCloud;
    private int frameNumber;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            printer = ((PrinterService.LocalBinder) binder).service(); if (followMode) printer.watch(GcodeViewerActivity.this);
            readBedZ(); startCamera();
            if (alignOnly && aligning == null) startAligning();
            else if (aligning != null) {   // the line-up began before the bed's height was known: start it from the right height
                alignStart = currentParams(); aligning = alignStart.clone(); alignZ = bedZ;
                for (int i = 0; i < ALIGN_NAMES.length; i++) syncAlignRow(i);
                pushAlign();
            }
        }
        @Override public void onServiceDisconnected(ComponentName name) { printer = null; }
    };
    private final Runnable player = new Runnable() {
        @Override public void run() {
            if (!playing || path == null) return;
            int size = layerSize(layer), step = Math.max(1, size / 120);
            if (move >= size) {
                if (layer + 1 >= path.layerCount) { setPlaying(false); return; }
                layer++; move = 0;
            } else move = Math.min(size, move + step);
            syncBars(); pushView(); main.postDelayed(this, 33);
        }
    };

    @Override protected void onCreate(Bundle saved) {
        int appearance = getSharedPreferences("workshop-settings", MODE_PRIVATE).getInt("theme", 0);
        dark = appearance == 2 || appearance == 0 && (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        setTheme(dark ? R.style.WorkshopDark : R.style.WorkshopLight);
        super.onCreate(saved);
        ink = dark ? 0xffe6eef1 : 0xff17252c; muted = dark ? 0xff9fb3bb : 0xff5a6d76; teal = dark ? 0xff5fd4c4 : 0xff00796b;
        background = dark ? 0xff0e1417 : 0xfff2f5f6; surface = dark ? 0xff182227 : Color.WHITE; buttonColor = dark ? 0xff21343a : 0xffe2efed;
        library = new GcodeLibrary(new File(getFilesDir(), "gcode-library"));
        Diagnostics.init(getFilesDir());
        followMode = getIntent().getBooleanExtra(EXTRA_FOLLOW, false);
        alignOnly = followMode && getIntent().getBooleanExtra(EXTRA_ALIGN, false);
        following = followMode;
        build();
        String file = getIntent().getStringExtra(EXTRA_FILE), name = getIntent().getStringExtra(EXTRA_NAME);
        if (file != null) load(new File(file), name != null ? name : new File(file).getName());
        else if (!followMode) status.setText("No G-code file to show.");
        if (followMode) bound = bindService(new Intent(this, PrinterService.class), connection, BIND_AUTO_CREATE);
    }

    @Override protected void onStart() { super.onStart(); if (printer != null && followMode) printer.watch(this); startCamera(); }
    @Override protected void onStop() { setPlaying(false); stopCamera(); if (printer != null) printer.unwatch(this); super.onStop(); }
    @Override protected void onDestroy() {
        stopCamera();
        if (printer != null) printer.unwatch(this);
        if (bound) unbindService(connection);
        worker.shutdownNow(); main.removeCallbacksAndMessages(null);
        if (web != null) { ((android.view.ViewGroup) web.getParent()).removeView(web); web.destroy(); web = null; }
        super.onDestroy();
    }

    // ------------------------------------------------------------------ layout
    private void build() {
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(background); root.setFitsSystemWindows(true);
        web = new WebView(this);
        root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL); panel.setPadding(dp(16), dp(8), dp(16), dp(12)); mainPanel = panel;
        GradientDrawable shape = new GradientDrawable(); shape.setColor(surface); shape.setCornerRadii(new float[] {dp(20), dp(20), dp(20), dp(20), 0, 0, 0, 0}); panel.setBackground(shape);
        root.addView(panel, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout header = new LinearLayout(this); header.setOrientation(LinearLayout.HORIZONTAL); header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        Button back = new Button(this); back.setText("‹ Back"); back.setAllCaps(false); back.setTextSize(14); back.setTextColor(teal); back.setBackground(null);
        back.setMinHeight(dp(48)); back.setMinimumHeight(dp(48)); back.setMinWidth(dp(64)); back.setMinimumWidth(dp(64)); back.setPadding(0, 0, dp(8), 0);
        back.setContentDescription("Back"); back.setOnClickListener(v -> finish());
        header.addView(back);
        title = new TextView(this); title.setText(followMode ? "Live toolpath" : "Toolpath"); title.setTextSize(16); title.setTextColor(ink); title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setMaxLines(2); title.setEllipsize(android.text.TextUtils.TruncateAt.END); A11y.heading(title);
        LinearLayout titles = new LinearLayout(this); titles.setOrientation(LinearLayout.VERTICAL); titles.addView(title);
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        panel.addView(header, new LinearLayout.LayoutParams(-1, -2));
        status = A11y.polite(label(titles, "Reading G-code…", 12, muted, false)); status.setPadding(0, 0, 0, 0);
        legend = new Flow(this); panel.addView(legend, new LinearLayout.LayoutParams(-1, -2));
        int shown = hintsShown();
        if (shown < 3 || followMode) {
            liveCaption = label(panel, followMode ? "Live view of the file, up to the nozzle. Grey = still to print on this layer."
                : "Drag to turn the view · two fingers to move and zoom · double-tap to reset", 11, muted, false);
        }
        // The caption describes a drawn toolpath; it hides while there is no file to draw.
        missingCard = new LinearLayout(this); missingCard.setOrientation(LinearLayout.VERTICAL); missingCard.setVisibility(View.GONE); panel.addView(missingCard);
        layerLabel = label(panel, "Layer", 13, ink, false);
        layerBar = seekBar(panel); A11y.labelFor(layerLabel, layerBar);
        moveLabel = label(panel, "Moves", 13, ink, false);
        moveBar = seekBar(panel); A11y.labelFor(moveLabel, moveBar);
        moveLabel.setVisibility(View.GONE); moveBar.setVisibility(View.GONE);
        SeekBar.OnSeekBarChangeListener scrub = new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                if (!fromUser || path == null) return;
                stopFollowing(); setPlaying(false);
                if (bar == layerBar) { layer = value; move = layerSize(layer); syncBars(); } else move = value;
                updateLabels(); pushView();
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        };
        layerBar.setOnSeekBarChangeListener(scrub); moveBar.setOnSeekBarChangeListener(scrub);
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); panel.addView(row);
        play = rowButton(row, "Play", () -> { stopFollowing(); setPlaying(!playing); });
        firstLayer = rowButton(row, "First layer", this::showFirstLayer);
        if (followMode) follow = rowButton(row, "Back to live", () -> { following = true; lastLocated = -1; setPlaying(false); changed(); });
        more = rowButton(row, "More…", this::moreDialog);
        setContentView(root);
        setControlsEnabled(false);
        setupWeb();
    }

    private void setupWeb() {
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true); settings.setAllowFileAccess(false); settings.setAllowContentAccess(false); settings.setMediaPlaybackRequiresUserGesture(false); // the cloud camera's muted video
        web.setBackgroundColor(background);
        WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/data/", this::serveData)
            .addPathHandler("/live/", this::serveFrame).build();
        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) { return loader.shouldInterceptRequest(request.getUrl()); }
        });
        web.addJavascriptInterface(new Bridge(), "Android");
        web.loadUrl(ORIGIN + "/assets/viewer/index.html");
    }

    private WebResourceResponse serveData(String name) {
        byte[] body; String type = "application/octet-stream";
        synchronized (this) {
            if ("meta.json".equals(name)) { body = meta; type = "application/json"; }
            else if ("segments.bin".equals(name)) body = segments;
            else if ("travels.bin".equals(name)) body = travels;
            else body = null;
        }
        if (body == null) return new WebResourceResponse("text/plain", "utf-8", 404, "Not found", null, new ByteArrayInputStream(new byte[0]));
        return new WebResourceResponse(type, null, new ByteArrayInputStream(body));
    }

    /** The newest camera picture for the scene; the page asks for one only after it has shown the previous one. */
    private WebResourceResponse serveFrame(String name) {
        CameraFrames frames = cameraFrames;
        byte[] frame = frames == null || !name.startsWith("frame.jpg") ? null : frames.take();
        if (frame == null) return new WebResourceResponse("text/plain", "utf-8", 404, "Not found", null, new ByteArrayInputStream(new byte[0]));
        return new WebResourceResponse("image/jpeg", null, new ByteArrayInputStream(frame));
    }

    // ------------------------------------------------------------------ the printer's camera in the scene
    private android.content.SharedPreferences viewerPrefs() { return getSharedPreferences("viewer-hints", MODE_PRIVATE); }
    private boolean cameraWanted() { return followMode && (alignOnly || viewerPrefs().getBoolean("camera", false)); }
    /** Opened only to line the camera up (Camera tab): the bed and camera show without a print or toolpath. */
    private boolean alignOnly;
    /**
     * Where the bed is now (the printer's reported Z). The CC2's bed goes down as a print grows while the camera stays on the
     * frame, so camera heights are kept with the bed at 0 and drawn this much higher above the bed.
     */
    private double bedZ, shownBedZ = Double.NaN;
    private void readBedZ() {
        if (printer == null) return;
        JSONObject position = Cc2Codec.position(printer.liveStatus());
        double z = position == null ? Double.NaN : position.optDouble("z", Double.NaN);
        if (!Double.isNaN(z) && z >= 0 && z < 400) bedZ = z;
    }
    /** A pose for the page from camera parameters that already hold for the bed's current height. */
    private JSONObject livePose(double[] c) throws org.json.JSONException { shownBedZ = bedZ; return poseJson(c); }
    /** Where the CC2's camera sits is not measured yet: five spots around the 256 mm bed, looking at its centre. */
    static JSONObject cameraPose(int spot) throws org.json.JSONException { return poseJson(spotParams(spot)); }
    /** A preset spot as camera parameters: x, y, z (mm), turn and tilt-down (degrees), vertical field of view, lens curve. */
    /** The CC2's camera, lined up by hand against its live picture (v0.14.2): just right of the bed, low, looking back-left. */
    static final double[] CC2_CAMERA = {308, -9, 28, 134, 8, 37, 0.23, 0};   // height with the bed at 0 (lined up with it at Z ≈ 3.8)
    static double[] spotParams(int spot) {
        if (spot <= 0) return CC2_CAMERA.clone();
        double[][] spots = {{-10, -20, 240}, {266, -20, 240}, {-10, 276, 240}, {266, 276, 240}, {128, -40, 240}};
        double[] p = spots[Math.max(0, Math.min(spots.length - 1, spot))];
        double dx = 128 - p[0], dy = 128 - p[1];
        return new double[] {p[0], p[1], p[2], Math.toDegrees(Math.atan2(dy, dx)), Math.toDegrees(Math.atan2(p[2], Math.hypot(dx, dy))), 50, 0, 0};
    }
    static JSONObject poseJson(double[] c) throws org.json.JSONException {
        double yaw = Math.toRadians(c[3]), pitch = Math.toRadians(c[4]), reach = 200;
        double[] target = {c[0] + reach * Math.cos(pitch) * Math.cos(yaw), c[1] + reach * Math.cos(pitch) * Math.sin(yaw), c[2] - reach * Math.sin(pitch)};
        return new JSONObject().put("position", new JSONArray(new double[] {c[0], c[1], c[2]})).put("target", new JSONArray(target))
            .put("fov", c[5]).put("screen", 90).put("lens", c[6]).put("roll", c.length > 7 ? c[7] : 0);
    }
    /** The saved hand-lined-up camera, or null. */
    static double[] parseParams(String text) {
        if (text == null) return null;
        String[] parts = text.split(",");
        if (parts.length != 7 && parts.length != 8) return null;
        double[] c = new double[8];   // a line-up from before roll existed has none
        try { for (int i = 0; i < parts.length; i++) c[i] = Double.parseDouble(parts[i]); } catch (NumberFormatException bad) { return null; }
        return c;
    }
    /**
     * The camera for the bed's current height. Line-ups saved at several bed heights are fitted with a straight line per
     * value (so how the camera relates to the bed is measured, not assumed); with one, or with a preset, the camera simply
     * sits higher above the bed by as much as the bed went down.
     */
    private double[] currentParams() {
        int spot = viewerPrefs().getInt("cameraSpot", 0);
        java.util.List<double[]> points = spot == LINED_UP ? savedPoints() : java.util.Collections.emptyList();
        if (!points.isEmpty()) return modelAt(points, bedZ);
        double[] c = spotParams(spot == LINED_UP ? 0 : spot); c[2] += bedZ; return c;
    }
    /** Saved line-ups as {bed Z, x, y, height above the bed, turn, tilt, view angle, lens}. */
    private java.util.List<double[]> savedPoints() {
        java.util.List<double[]> points = parsePoints(viewerPrefs().getString("cameraPoints", null));
        if (points.isEmpty()) {   // v0.14.5 kept one line-up with the bed at 0
            double[] old = parseParams(viewerPrefs().getString("cameraCustomBed0", null));
            if (old != null) { double[] point = new double[9]; System.arraycopy(old, 0, point, 1, 8); points.add(point); }
        }
        return points;
    }
    static java.util.List<double[]> parsePoints(String text) {
        java.util.List<double[]> points = new java.util.ArrayList<>();
        if (text == null) return points;
        try {
            JSONArray rows = new JSONArray(text);
            for (int i = 0; i < rows.length(); i++) {
                JSONArray row = rows.getJSONArray(i); if (row.length() != 8 && row.length() != 9) continue;   // 8: saved before roll
                double[] point = new double[9]; for (int j = 0; j < row.length(); j++) point[j] = row.getDouble(j);
                points.add(point);
            }
        } catch (org.json.JSONException bad) { points.clear(); }
        return points;
    }
    static String pointsJson(java.util.List<double[]> points) {
        JSONArray rows = new JSONArray();
        for (double[] point : points) { JSONArray row = new JSONArray(); for (double v : point) try { row.put(v); } catch (org.json.JSONException ignored) { } rows.put(row); }
        return rows.toString();
    }
    /** Adds a line-up at bed height z, replacing one within 2 mm of it; keeps the latest six. */
    static java.util.List<double[]> addPoint(java.util.List<double[]> points, double z, double[] c) {
        java.util.List<double[]> next = new java.util.ArrayList<>();
        for (double[] point : points) if (Math.abs(point[0] - z) >= 2) next.add(point);
        double[] point = new double[9]; point[0] = z; System.arraycopy(c, 0, point, 1, Math.min(8, c.length)); next.add(point);
        while (next.size() > 6) next.remove(0);
        return next;
    }
    private static double value(double[] point, int index) { return index < point.length ? point[index] : 0; }
    /** The camera at bed height z from saved line-ups: least-squares line per value, or one line-up moved with the bed. */
    static double[] modelAt(java.util.List<double[]> points, double z) {
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (double[] point : points) { min = Math.min(min, point[0]); max = Math.max(max, point[0]); }
        double[] c = new double[8];
        if (max - min < 5) {   // all at about one height: no slope to measure
            double[] nearest = points.get(0);
            for (double[] point : points) if (Math.abs(point[0] - z) < Math.abs(nearest[0] - z)) nearest = point;
            System.arraycopy(nearest, 1, c, 0, Math.min(8, nearest.length - 1)); c[2] += z - nearest[0]; return c;
        }
        int n = points.size(); double meanZ = 0; for (double[] point : points) meanZ += point[0]; meanZ /= n;
        double szz = 0; for (double[] point : points) szz += (point[0] - meanZ) * (point[0] - meanZ);
        for (int i = 0; i < 8; i++) {
            double mean = 0, szv = 0;
            for (double[] point : points) mean += value(point, i + 1); mean /= n;
            for (double[] point : points) szv += (point[0] - meanZ) * (value(point, i + 1) - mean);
            c[i] = mean + szv / szz * (z - meanZ);
        }
        return c;
    }
    private void startCamera() {
        if (!cameraWanted() || cameraStarted || printer == null || web == null || !pageReady || path == null && !alignOnly) return;
        if (aligning == null) try { web.evaluateJavascript("viewer.showPrinterCamera(" + livePose(currentParams()) + ")", null); } catch (Exception ignored) { }
        if (printer.ready()) {
            String host = printer.host(), url;
            try { url = FeatureData.cameraUrl(host, printer.cameraUrl.isEmpty() ? "http://" + host + ":8080/?action=stream" : printer.cameraUrl); }
            catch (Exception invalid) { cameraMessage("The camera address is not on the printer, so it was not opened."); return; }
            NetworkRoute route;
            try { route = NetworkRoute.select(this, printer.remote()); } catch (IOException unavailable) { cameraMessage(unavailable.getMessage()); return; }
            cameraStarted = true; cameraCloud = false;
            cameraFrames = new CameraFrames(url, route.http(), new CameraFrames.Listener() {
                public void frame() { main.post(() -> { if (web != null && cameraFrames != null) web.evaluateJavascript("viewer.cameraFrame('/live/frame.jpg?n=" + (frameNumber++) + "')", null); }); }
                public void error(String message) { main.post(() -> { cameraStarted = false; cameraFrames = null; cameraMessage(message + " Open the camera from Monitor to check it."); }); }
            });
            cameraMessage("Camera: the printer's own stream on this network.");
        } else if (printer.usingCloud() && !printer.cloudSerial.isEmpty()) {
            if (!getSharedPreferences("workshop-settings", MODE_PRIVATE).getBoolean("cloudControlUnderstood", false)) {
                cameraMessage("The camera through Elegoo's cloud needs cloud control turned on: Monitor > Camera explains it. The camera model is shown without a picture."); return;
            }
            cameraStarted = true; cameraCloud = true;
            String serial = printer.cloudSerial;
            cameraMessage("Getting camera access from Elegoo…");
            worker.execute(() -> {
                String error = null; CloudApi.AgoraCredential issued = null;
                try {
                    CloudAccountStore store = new CloudAccountStore(this);
                    CloudLogin.Account account = store.load();
                    if (account == null) error = "Sign in with Elegoo in Settings first.";
                    else {
                        CloudApi api = new CloudApi(store.china(), account, CloudApi.agent(this), CloudApi::https);
                        issued = api.agoraCredential();
                        if (api.account() != account) store.save(api.account());
                        if (issued.rtcToken.isEmpty() || issued.rtcUserId.isEmpty()) error = "Elegoo did not issue camera access for this account.";
                    }
                } catch (Exception failure) { error = failure.getMessage() == null ? "Could not reach the Elegoo cloud." : failure.getMessage(); }
                String text = error; CloudApi.AgoraCredential credential = issued;
                main.post(() -> {
                    if (isDestroyed() || web == null || !cameraStarted) return;
                    if (text != null) { cameraStarted = false; cameraMessage(CloudCameraActivity.explain(text)); return; }
                    web.evaluateJavascript("viewer.cameraCloud(" + JSONObject.quote(CloudControl.AGORA_APP_ID) + "," + JSONObject.quote(serial) + ","
                        + JSONObject.quote(credential.rtcToken) + "," + JSONObject.quote(credential.rtcUserId) + ")", null);
                });
            });
        } else cameraMessage("No camera picture: connect to the printer, or watch it through the Elegoo cloud.");
    }
    private void stopCamera() {
        CameraFrames frames = cameraFrames; cameraFrames = null; if (frames != null) frames.close();
        if (cameraStarted && cameraCloud && web != null) web.evaluateJavascript("viewer.cameraStop()", null);
        cameraStarted = false;
    }
    private void cameraMessage(String text) {
        if (text == null || text.isEmpty()) return;
        if (status != null) status.setText(text);
        if (bedNote != null && aligning != null) bedNote.setText(text);
    }
    private void toggleCamera() {
        boolean on = !cameraWanted();
        viewerPrefs().edit().putBoolean("camera", on).apply();
        if (on) startCamera(); else { stopCamera(); if (web != null) web.evaluateJavascript("viewer.showPrinterCamera(null)", null); }
    }
    private void cameraSpotDialog() {
        new AlertDialog.Builder(this).setTitle("Where is the camera?")
            .setSingleChoiceItems(CAMERA_SPOTS, viewerPrefs().getInt("cameraSpot", 0), (d, which) -> {
                d.dismiss();
                if (which == LINED_UP) { startAligning(); return; }
                viewerPrefs().edit().putInt("cameraSpot", which).apply();
                try { if (web != null && cameraWanted()) web.evaluateJavascript("viewer.showPrinterCamera(" + livePose(currentParams()) + ")", null); } catch (Exception ignored) { }
            }).setNegativeButton("Cancel", null).show();
    }

    // Lining the camera up by hand: the view looks from the camera with its picture behind the bed outline (yellow); the
    // sliders move and aim the camera until the outline sits on the real bed. Saved as the "Lined up by hand" spot.
    private static final String[] ALIGN_NAMES = {"Left – right", "Front – back", "Height above the bed", "Turn", "Tilt down", "Zoom (view angle)", "Lens curve", "Roll (lean sideways)"};
    // Wide enough for a camera outside the bed's footprint (the CC2's sits off its front-right corner); − and + nudge one step.
    private static final double[][] ALIGN_RANGE = CameraFit.RANGE;
    private static final double[] ALIGN_STEP = {1, 1, 1, 0.5, 0.5, 0.5, 0.01, 0.2};
    private void startAligning() {
        if (web == null || !pageReady || path == null && !alignOnly || aligning != null) return;
        if (!cameraWanted()) viewerPrefs().edit().putBoolean("camera", true).apply();
        startCamera();
        alignStart = currentParams(); aligning = alignStart.clone(); alignZ = bedZ;
        if (alignPanel == null) buildAlignPanel();
        sendAlignStyle(); sendLock();
        for (int i = 0; i < ALIGN_NAMES.length; i++) syncAlignRow(i);
        mainPanel.setVisibility(View.GONE); alignScroll.setVisibility(View.VISIBLE); arrange();
        if (android.os.Build.VERSION.SDK_INT >= 33 && alignBack == null) {
            android.window.OnBackInvokedCallback callback = () -> finishAligning(false); alignBack = callback;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, (android.window.OnBackInvokedCallback) alignBack);
        }
        pushAlign();
    }
    /**
     * Sideways while lining up, the sliders sit in a column beside the picture, so the whole camera picture shows (upright,
     * a tall screen shows only its middle, and the edges are where a lens curve shows). Otherwise the panel is below.
     */
    private void arrange() {
        if (root == null || web == null) return;
        boolean beside = aligning != null && alignScroll != null
            && getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        root.setOrientation(beside ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        web.setLayoutParams(beside ? new LinearLayout.LayoutParams(0, -1, 1) : new LinearLayout.LayoutParams(-1, 0, 1));
        if (alignScroll != null) {
            alignScroll.setLayoutParams(beside ? new LinearLayout.LayoutParams(dp(340), -1) : new LinearLayout.LayoutParams(-1, -2));
            android.graphics.drawable.Drawable panel = alignScroll.getBackground();
            if (panel instanceof GradientDrawable) ((GradientDrawable) panel).setCornerRadii(beside
                ? new float[] {dp(20), dp(20), 0, 0, 0, 0, dp(20), dp(20)} : new float[] {dp(20), dp(20), dp(20), dp(20), 0, 0, 0, 0});
        }
        mainPanel.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
    }
    @Override public void onConfigurationChanged(Configuration changed) { super.onConfigurationChanged(changed); arrange(); }
    /** Back while lining up cancels the line-up instead of closing the screen. */
    private Object alignBack;
    /** Phones before Android 13 have no back-gesture callback API; the system calls this instead (later ones use alignBack). */
    @SuppressWarnings("deprecation") @android.annotation.SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed() { if (aligning != null && android.os.Build.VERSION.SDK_INT < 33) finishAligning(false); else super.onBackPressed(); }
    private TextView bedNote;
    private final java.util.List<SeekBar> alignBars = new java.util.ArrayList<>();
    private final java.util.List<TextView> alignLabels = new java.util.ArrayList<>();
    private void buildAlignPanel() {
        alignPanel = new LinearLayout(this); alignPanel.setOrientation(LinearLayout.VERTICAL); alignPanel.setPadding(dp(16), dp(8), dp(16), dp(12));
        alignScroll = new android.widget.ScrollView(this);
        alignScroll.setBackground(mainPanel.getBackground().getConstantState().newDrawable());
        TextView intro = label(alignPanel, "Line up the camera: move the sliders until the yellow bed outline sits on the bed in the picture; − and + nudge one step. Match the middle of the bed first, then raise Lens curve until the outline bends like the bed's edges. Turn the phone sideways to see the whole picture, edges included.", 12, muted, false);
        intro.setPadding(0, 0, 0, dp(4));
        bedNote = A11y.polite(label(alignPanel, "", 12, muted, false));
        LinearLayout forget = new LinearLayout(this); forget.setOrientation(LinearLayout.HORIZONTAL); alignPanel.addView(forget);
        rowButton(forget, "Forget saved line-ups", this::forgetLineUps);
        LinearLayout lockRow = new LinearLayout(this); lockRow.setOrientation(LinearLayout.HORIZONTAL); alignPanel.addView(lockRow);
        alignLockButton = rowButton(lockRow, "", () -> { viewerPrefs().edit().putBoolean("alignLock", !viewerPrefs().getBoolean("alignLock", true)).apply(); sendLock(); });
        buildMarking();
        for (int i = 0; i < ALIGN_NAMES.length; i++) {
            int index = i;
            LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            TextView name = new TextView(this); name.setTextSize(12); name.setTextColor(ink);
            row.addView(name, new LinearLayout.LayoutParams(dp(112), -2));
            SeekBar bar = new SeekBar(this); bar.setProgressTintList(ColorStateList.valueOf(teal)); bar.setThumbTintList(ColorStateList.valueOf(teal));
            bar.setMax((int) Math.round((ALIGN_RANGE[i][1] - ALIGN_RANGE[i][0]) / ALIGN_STEP[i]));
            row.addView(bar, new LinearLayout.LayoutParams(0, dp(48), 1));
            A11y.labelFor(name, bar);
            for (int direction : new int[] {-1, 1}) {
                Button nudge = new Button(this); nudge.setText(direction < 0 ? "−" : "+"); nudge.setTextSize(18); nudge.setTextColor(teal); nudge.setBackground(null);
                nudge.setMinWidth(dp(44)); nudge.setMinimumWidth(dp(44)); nudge.setMinHeight(dp(48)); nudge.setMinimumHeight(dp(48)); nudge.setPadding(0, 0, 0, 0);
                nudge.setContentDescription((direction < 0 ? "Less " : "More ") + ALIGN_NAMES[i].toLowerCase(Locale.ROOT));
                nudge.setOnClickListener(v -> {
                    if (aligning == null) return;
                    aligning[index] = Math.max(ALIGN_RANGE[index][0], Math.min(ALIGN_RANGE[index][1], aligning[index] + direction * ALIGN_STEP[index]));
                    syncAlignRow(index); pushAlign();
                });
                row.addView(nudge, new LinearLayout.LayoutParams(dp(44), dp(48)));
            }
            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar b, int value, boolean fromUser) {
                    if (!fromUser || aligning == null) return;
                    aligning[index] = ALIGN_RANGE[index][0] + value * ALIGN_STEP[index]; syncAlignLabel(index); pushAlign();
                }
                @Override public void onStartTrackingTouch(SeekBar b) { }
                @Override public void onStopTrackingTouch(SeekBar b) { }
            });
            alignLabels.add(name); alignBars.add(bar);
            alignPanel.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
        LinearLayout buttons = new LinearLayout(this); buttons.setOrientation(LinearLayout.HORIZONTAL); alignPanel.addView(buttons);
        rowButton(buttons, "Cancel", () -> finishAligning(false));
        rowButton(buttons, "Start over", () -> { aligning = spotParams(0); aligning[2] += bedZ; for (int i = 0; i < ALIGN_NAMES.length; i++) syncAlignRow(i); pushAlign(); });
        rowButton(buttons, "Save", () -> finishAligning(true));
        alignScroll.addView(alignPanel);
        root.addView(alignScroll, new LinearLayout.LayoutParams(-1, -2));
    }
    private void syncAlignRow(int i) {
        alignBars.get(i).setProgress((int) Math.round((Math.max(ALIGN_RANGE[i][0], Math.min(ALIGN_RANGE[i][1], aligning[i])) - ALIGN_RANGE[i][0]) / ALIGN_STEP[i]));
        syncAlignLabel(i);
    }
    // Lining up from taps: the user marks points along the bed's edges in the picture and CameraFit finds the camera.
    private final java.util.List<CameraFit.Mark> marks = new java.util.ArrayList<>();
    private int markEdge = 1;
    private double markAspect = 16.0 / 9;
    private boolean marking;
    private LinearLayout markBox;
    private TextView markNote;
    private final java.util.List<Button> edgeButtons = new java.util.ArrayList<>();
    private Button markToggle;
    private static final String[] EDGE_NAMES = {"Front", "Back", "Left", "Right"};
    private static final int[] EDGE_COLOURS = {0xffff5252, 0xff40c4ff, 0xff69f0ae, 0xffff4dd2};
    private void buildMarking() {
        LinearLayout toggleRow = new LinearLayout(this); toggleRow.setOrientation(LinearLayout.HORIZONTAL); alignPanel.addView(toggleRow);
        markToggle = rowButton(toggleRow, "Line up from taps…", () -> setMarking(!marking));
        markBox = new LinearLayout(this); markBox.setOrientation(LinearLayout.VERTICAL); markBox.setVisibility(View.GONE); alignPanel.addView(markBox);
        label(markBox, "Pick an edge of the bed, then tap 2–4 points along it in the picture, on the line where the bed's top surface ends. "
            + "Mark three or four edges (the far edge and both sides help most), then Fit. Sideways gives the biggest picture; pinch and drag are off while marking.", 12, muted, false);
        LinearLayout edges = new LinearLayout(this); edges.setOrientation(LinearLayout.HORIZONTAL); markBox.addView(edges);
        for (int i = 0; i < EDGE_NAMES.length; i++) {
            int edge = i;
            Button b = rowButton(edges, EDGE_NAMES[i], () -> { markEdge = edge; syncEdgeButtons(); });
            edgeButtons.add(b);
        }
        LinearLayout actions = new LinearLayout(this); actions.setOrientation(LinearLayout.HORIZONTAL); markBox.addView(actions);
        rowButton(actions, "Undo tap", () -> { if (!marks.isEmpty()) marks.remove(marks.size() - 1); sendMarks(); });
        rowButton(actions, "Clear taps", () -> { marks.clear(); sendMarks(); });
        rowButton(actions, "Fit", this::fitToMarks);
        LinearLayout zoom = new LinearLayout(this); zoom.setOrientation(LinearLayout.HORIZONTAL); markBox.addView(zoom);
        rowButton(zoom, "Zoom in", () -> js("viewer.zoomPicture(1.5)"));
        rowButton(zoom, "Zoom out", () -> js("viewer.zoomPicture(1 / 1.5)"));
        rowButton(zoom, "Reset zoom", () -> js("viewer.resetZoom()"));
        markNote = A11y.polite(label(markBox, "", 12, ink, false));
        // How the outline and the taps look over the picture (kept between line-ups).
        LinearLayout look = new LinearLayout(this); look.setOrientation(LinearLayout.HORIZONTAL); markBox.addView(look);
        gridButton = rowButton(look, "", () -> {
            String now = viewerPrefs().getString("alignGrid", "all");
            viewerPrefs().edit().putString("alignGrid", "all".equals(now) ? "edges" : "edges".equals(now) ? "none" : "all").apply();
            sendAlignStyle();
        });
        styleSlider(markBox, "Outline strength", "alignAlpha", 15, 100, 90, "%");
        styleSlider(markBox, "Dot size", "alignDot", 4, 32, 12, " px");
        syncEdgeButtons();
    }
    private Button gridButton, alignLockButton;
    private void js(String script) { if (web != null) web.evaluateJavascript(script, null); }
    /**
     * Rotation lock: dragging pans instead of turning the view, and from the camera it zooms and pans the picture instead of
     * leaving it. On by default while lining up (a stray drag would otherwise lose the camera view), off by default otherwise.
     */
    private boolean rotationLocked() { return aligning != null ? viewerPrefs().getBoolean("alignLock", true) : viewerPrefs().getBoolean("viewLock", false); }
    private void sendLock() {
        boolean on = rotationLocked();
        js("viewer.setRotationLock(" + on + ")");
        if (alignLockButton != null) { alignLockButton.setText(on ? "Rotation lock: on (drag pans, pinch zooms)" : "Rotation lock: off"); A11y.state(alignLockButton, on ? "On" : "Off"); }
    }
    private void styleSlider(LinearLayout parent, String name, String key, int min, int max, int fallback, String unit) {
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView label = new TextView(this); label.setTextSize(12); label.setTextColor(ink);
        row.addView(label, new LinearLayout.LayoutParams(dp(112), -2));
        SeekBar bar = new SeekBar(this); bar.setProgressTintList(ColorStateList.valueOf(teal)); bar.setThumbTintList(ColorStateList.valueOf(teal));
        bar.setMax(max - min); bar.setProgress(viewerPrefs().getInt(key, fallback) - min);
        row.addView(bar, new LinearLayout.LayoutParams(0, dp(48), 1));
        A11y.labelFor(label, bar);
        Runnable show = () -> { String value = (bar.getProgress() + min) + unit; label.setText(name + "\n" + value); A11y.state(bar, value); };
        show.run();
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b, int value, boolean fromUser) { if (!fromUser) return; viewerPrefs().edit().putInt(key, value + min).apply(); show.run(); sendAlignStyle(); }
            @Override public void onStartTrackingTouch(SeekBar b) { }
            @Override public void onStopTrackingTouch(SeekBar b) { }
        });
        parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }
    private void sendAlignStyle() {
        String grid = viewerPrefs().getString("alignGrid", "all");
        if (gridButton != null) gridButton.setText("all".equals(grid) ? "Grid: all lines" : "edges".equals(grid) ? "Grid: bed edges only" : "Grid: hidden");
        if (web == null) return;
        try {
            web.evaluateJavascript("viewer.setAlignStyle(" + new JSONObject().put("grid", grid).put("alpha", viewerPrefs().getInt("alignAlpha", 90) / 100.0)
                .put("dot", viewerPrefs().getInt("alignDot", 12)) + ")", null);
        } catch (org.json.JSONException ignored) { }
    }
    private void syncEdgeButtons() {
        for (int i = 0; i < edgeButtons.size(); i++) {
            Button b = edgeButtons.get(i); boolean on = i == markEdge;
            b.setText((on ? "● " : "") + EDGE_NAMES[i]);
            b.setTextColor(on ? EDGE_COLOURS[i] : muted);
            A11y.state(b, on ? "Selected" : "Not selected");
            b.setContentDescription(EDGE_NAMES[i] + " edge of the bed");
        }
    }
    private void setMarking(boolean on) {
        marking = on;
        if (markBox != null) markBox.setVisibility(on ? View.VISIBLE : View.GONE);
        if (markToggle != null) markToggle.setText(on ? "Stop marking" : "Line up from taps…");
        sendMarks();
    }
    private void sendMarks() {
        if (web == null) return;
        JSONArray list = new JSONArray();
        for (CameraFit.Mark mark : marks) try { list.put(new JSONObject().put("edge", mark.edge).put("u", mark.u).put("v", mark.v)); } catch (org.json.JSONException ignored) { }
        web.evaluateJavascript("viewer.setMarking(" + marking + "," + list + ")", null);
        if (markNote != null) {
            int[] count = new int[4]; for (CameraFit.Mark mark : marks) count[mark.edge]++;
            StringBuilder text = new StringBuilder(marks.size() + (marks.size() == 1 ? " tap" : " taps"));
            for (int i = 0; i < 4; i++) if (count[i] > 0) text.append(" · ").append(EDGE_NAMES[i].toLowerCase(Locale.ROOT)).append(' ').append(count[i]);
            int free = CameraFit.free(marks).length;
            text.append(free == 0 ? ". Tap at least 3 points to fit." : free < CameraFit.COUNT ? ". Fit now adjusts some values; 8 taps on 3 edges adjust all of them." : ". Ready to fit everything.");
            markNote.setText(text);
        }
    }
    /**
     * Taps saved at other bed heights (one set per height), so a fit can use them all: the camera stays on the frame while
     * the bed moves, so they describe one camera whose height above the bed changes by exactly the bed's Z.
     */
    private java.util.List<CameraFit.TapSet> savedTaps() { return parseTaps(viewerPrefs().getString("cameraTaps", null)); }
    static java.util.List<CameraFit.TapSet> parseTaps(String text) {
        java.util.List<CameraFit.TapSet> sets = new java.util.ArrayList<>();
        if (text == null) return sets;
        try {
            JSONArray rows = new JSONArray(text);
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i); JSONArray list = row.getJSONArray("m");
                java.util.List<CameraFit.Mark> taps = new java.util.ArrayList<>();
                for (int j = 0; j < list.length(); j++) { JSONArray t = list.getJSONArray(j); taps.add(new CameraFit.Mark(t.getInt(0) & 3, t.getDouble(1), t.getDouble(2))); }
                sets.add(new CameraFit.TapSet(row.getDouble("z"), row.getDouble("a"), taps));
            }
        } catch (org.json.JSONException bad) { sets.clear(); }
        return sets;
    }
    static String tapsJson(java.util.List<CameraFit.TapSet> sets) {
        JSONArray rows = new JSONArray();
        try {
            for (CameraFit.TapSet set : sets) {
                JSONArray list = new JSONArray();
                for (CameraFit.Mark mark : set.marks) list.put(new JSONArray().put(mark.edge).put(mark.u).put(mark.v));
                rows.put(new JSONObject().put("z", set.z).put("a", set.aspect).put("m", list));
            }
        } catch (org.json.JSONException ignored) { }
        return rows.toString();
    }
    /** The saved sets with this height's taps in place of any saved within 2 mm of it; the latest six heights. */
    static java.util.List<CameraFit.TapSet> withTaps(java.util.List<CameraFit.TapSet> saved, CameraFit.TapSet now) {
        java.util.List<CameraFit.TapSet> sets = new java.util.ArrayList<>();
        for (CameraFit.TapSet set : saved) if (Math.abs(set.z - now.z) >= 2) sets.add(set);
        if (!now.marks.isEmpty()) sets.add(now);
        while (sets.size() > 6) sets.remove(0);
        return sets;
    }
    private CameraFit.TapSet currentTaps() { return new CameraFit.TapSet(bedZ, markAspect, marks); }
    private void fitToMarks() {
        if (aligning == null) return;
        java.util.List<CameraFit.TapSet> sets = withTaps(savedTaps(), currentTaps());
        if (CameraFit.freeSets(sets).length == 0) { sendMarks(); return; }
        double[] start = CameraFit.atBed(aligning, -bedZ);   // the camera with the bed at 0
        double heightNow = bedZ;
        if (markNote != null) markNote.setText("Fitting…");
        worker.execute(() -> {
            double before = CameraFit.rms(start, sets), fitted[] = CameraFit.fit(start, sets), after = CameraFit.rms(fitted, sets);
            StringBuilder each = new StringBuilder();
            for (CameraFit.TapSet set : sets)
                each.append(each.length() > 0 ? ", " : "").append(String.format(Locale.getDefault(), "Z %.0f: %.1f%%", set.z, CameraFit.rms(fitted, java.util.Collections.singletonList(set)) * 50));
            main.post(() -> {
                if (aligning == null || isDestroyed()) return;
                aligning = CameraFit.atBed(fitted, heightNow);
                for (int i = 0; i < ALIGN_NAMES.length; i++) syncAlignRow(i);
                pushAlign();
                // Picture heights: Y runs -1..1, so half the miss is the share of the picture's height.
                if (markNote != null) markNote.setText(String.format(Locale.getDefault(), "Fitted: the taps now sit %.1f%% of the picture's height from the outline on average (was %.1f%%)", after * 50, before * 50)
                    + (sets.size() > 1 ? ", using taps from " + sets.size() + " bed heights (" + each + ")" : "")
                    + ". Nudge with the sliders if needed, then Save.");
                Diagnostics.note(Diagnostics.FOLLOW, String.format(Locale.ROOT, "camera fitted to %d bed height(s), %s: miss %.4f -> %.4f picture heights; camera with the bed at 0 %s",
                    sets.size(), each, before / 2, after / 2, java.util.Arrays.toString(fitted)));
            });
        });
    }
    private void syncAlignLabel(int i) {
        String value = i < 3 ? String.format(Locale.getDefault(), "%.0f mm", aligning[i]) : i == CameraFit.LENS ? String.format(Locale.getDefault(), "%.2f", aligning[i]) : String.format(Locale.getDefault(), "%.1f°", aligning[i]);
        alignLabels.get(i).setText(ALIGN_NAMES[i] + "\n" + value);
        A11y.state(alignBars.get(i), value);
    }
    private void pushAlign() {
        try { if (web != null) web.evaluateJavascript("viewer.alignPrinterCamera(" + livePose(aligning) + ")", null); } catch (Exception ignored) { }
        if (bedNote != null) bedNote.setText(bedText());
    }
    private String bedText() {
        String now = String.format(Locale.getDefault(), "The bed is at Z %.1f mm now. ", bedZ);
        java.util.List<CameraFit.TapSet> taps = viewerPrefs().getInt("cameraSpot", 0) == LINED_UP ? savedTaps() : java.util.Collections.emptyList();
        if (!taps.isEmpty()) {
            StringBuilder heights = new StringBuilder();
            for (CameraFit.TapSet set : taps) heights.append(heights.length() > 0 ? ", " : "").append(String.format(Locale.getDefault(), "%.0f", set.z));
            return now + "Taps saved at bed Z " + heights + " mm; Fit uses them all with any taps made now, as one camera fixed to the frame. "
                + "Tapping at heights far apart (Z 5, 100, 200) pins it down best.";
        }
        StringBuilder heights = new StringBuilder();
        if (viewerPrefs().getInt("cameraSpot", 0) == LINED_UP) for (double[] point : savedPoints()) heights.append(heights.length() > 0 ? ", " : "").append(String.format(Locale.getDefault(), "%.1f", point[0]));
        return now + (heights.length() == 0 ? "No line-ups saved yet." : "Line-ups saved at bed Z " + heights + " mm.")
            + " Line up from taps and Save at two or more bed heights (move the bed from Controls): the taps from all of them are fitted as one camera.";
    }
    private void forgetLineUps() {
        viewerPrefs().edit().remove("cameraPoints").remove("cameraTaps").remove("cameraCustomBed0").putInt("cameraSpot", 0).apply();
        if (aligning != null) { aligning = spotParams(0); aligning[2] += bedZ; for (int i = 0; i < ALIGN_NAMES.length; i++) syncAlignRow(i); pushAlign(); }
    }
    private void finishAligning(boolean save) {
        if (save && aligning != null) {
            java.util.List<CameraFit.TapSet> sets = withTaps(savedTaps(), currentTaps());
            // With taps (from any height), the camera is one camera on the frame: saved once, it follows the bed exactly.
            // Without, as before: a line-up per bed height, fitted with a line.
            java.util.List<double[]> points = !sets.isEmpty() ? addPoint(new java.util.ArrayList<>(), alignZ, aligning)
                : addPoint(viewerPrefs().getInt("cameraSpot", 0) == LINED_UP ? savedPoints() : new java.util.ArrayList<>(), alignZ, aligning);
            viewerPrefs().edit().putString("cameraPoints", pointsJson(points)).putString("cameraTaps", tapsJson(sets)).remove("cameraCustomBed0").putInt("cameraSpot", LINED_UP).apply();
            Diagnostics.note(Diagnostics.FOLLOW, String.format(Locale.ROOT, "camera lined up by hand at bed Z %.2f; all line-ups {bed Z, x, y, height, turn, tilt, view, lens}: %s", alignZ, pointsJson(points)));
            status.setText("Camera position saved. More… > Look from the camera shows the picture with the toolpath over it.");
        }
        aligning = null;
        marks.clear(); if (marking) setMarking(false); else sendMarks();
        sendLock();
        double[] shown = currentParams();
        if (android.os.Build.VERSION.SDK_INT >= 33 && alignBack != null) { getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback((android.window.OnBackInvokedCallback) alignBack); alignBack = null; }
        if (alignScroll != null) alignScroll.setVisibility(View.GONE);
        mainPanel.setVisibility(View.VISIBLE); arrange();
        try { if (web != null && shown != null) web.evaluateJavascript("viewer.showPrinterCamera(" + livePose(shown) + ")", null); } catch (Exception ignored) { }
        if (alignOnly) finish();
    }

    private final class Bridge {
        @JavascriptInterface public void onReady() { main.post(() -> { pageReady = true; sendTheme(); sendData(); sendLock(); if (alignOnly) startAligning(); }); }
        @JavascriptInterface public void onLoaded(int count) { main.post(() -> { setControlsEnabled(true); pushView(); if (following) changed(); startCamera(); }); }
        @JavascriptInterface public void onMark(double u, double v, double aspect) { main.post(() -> {
            if (!marking || aligning == null) return;
            markAspect = aspect > 0.2 && aspect < 5 ? aspect : markAspect;
            marks.add(new CameraFit.Mark(markEdge, u, v)); sendMarks();
        }); }
        @JavascriptInterface public void onCamera(String message) { main.post(() -> {
            if ("playing".equals(message)) cameraMessage("Camera: live video through Elegoo's cloud.");
            else cameraMessage(CloudCameraActivity.explain(message));
        }); }
        @JavascriptInterface public void onError(String message) { main.post(() -> status.setText(message)); }
    }

    // ------------------------------------------------------------------ loading
    private void load(File file, String name) {
        if (name.equals(loadingName)) return;
        loadingName = name; title.setText(StatusPresentation.clean(name.replaceFirst("(?i)\\.gcode$", "")));
        status.setText("Reading toolpath…"); missingCard.setVisibility(View.GONE); if (liveCaption != null) liveCaption.setVisibility(View.VISIBLE); setControlsEnabled(false);
        worker.execute(() -> {
            try {
                GcodeToolpath read = GcodeToolpath.read(file);
                byte[] s = read.segmentsBinary(), t = read.travelsBinary(), m = read.layersJson().getBytes("UTF-8");
                main.post(() -> {
                    if (isDestroyed()) return;
                    synchronized (this) { path = read; segments = s; travels = t; meta = m; }
                    loadedName = name; layer = Math.max(0, read.layerCount - 1); move = layerSize(layer); lastLocated = -1;
                    status.setText(String.format(Locale.getDefault(), "%,d lines in %d layers%s", read.count, read.layerCount,
                        read.truncated ? " (first part only: very large file)" : ""));
                    layerBar.setMax(Math.max(0, read.layerCount - 1)); syncBars(); buildLegend(); sent = false; sendData();
                });
            } catch (IOException | OutOfMemoryError failure) {
                main.post(() -> { if (!isDestroyed()) { loadingName = null; status.setText("The G-code could not be read: " + failure.getMessage()); } });
            }
        });
    }

    private void sendData() {
        if (!pageReady || path == null || sent || web == null) return;
        sent = true; web.evaluateJavascript("viewer.load()", null);
    }

    private void sendTheme() {
        if (web == null) return;
        try {
            JSONObject theme = new JSONObject().put("background", rgb(background)).put("palette", new JSONArray(PALETTE));
            if (dark) theme.put("grid", new JSONArray(new double[] {0.2, 0.27, 0.3, 1})).put("plate", new JSONArray(new double[] {0.1, 0.14, 0.16, 1}))
                .put("ghost", new JSONArray(new double[] {0.45, 0.5, 0.53})).put("dim", new JSONArray(new double[] {0.3, 0.34, 0.36}));
            web.evaluateJavascript("viewer.setTheme(" + theme + ")", null);
        } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------------ view state
    private int layerSize(int index) { return path == null ? 0 : path.layerEnd(index) - path.layerStart(index); }

    private void syncBars() {
        if (path == null) return;
        layerBar.setProgress(layer); moveBar.setMax(Math.max(1, layerSize(layer))); moveBar.setProgress(move); updateLabels();
    }

    private void updateLabels() {
        if (path == null) return;
        if (legendLayer != layer && legend != null) buildLegend();
        layerLabel.setText(String.format(Locale.getDefault(), "%sLayer %d of %d · %.2f mm high", following ? "● LIVE · " : "", layer + 1, path.layerCount, path.layerZ(layer)));
        moveLabel.setText(String.format(Locale.getDefault(), "Drawn so far in this layer: %,d of %,d lines", move, layerSize(layer)));
        A11y.state(layerBar, String.format(Locale.getDefault(), "Layer %d of %d", layer + 1, path.layerCount));
        A11y.state(moveBar, String.format(Locale.getDefault(), "%,d of %,d lines", move, layerSize(layer)));
    }

    /** Sends the current range, nozzle and filters to the page. `nozzle` is null outside follow mode. */
    private void pushView() { pushView(null); }
    private void pushView(double[] nozzle) {
        if (path == null || web == null || !pageReady) return;
        try {
            int start = path.layerStart(layer), end = start + Math.min(move, layerSize(layer)), layerEnd = path.layerEnd(layer);
            boolean partial = following || end < layerEnd;
            JSONObject state = new JSONObject().put("start", 0).put("end", end).put("ghostEnd", layerEnd)
                .put("dimBelow", partial ? start : 0).put("hidden", hidden).put("showTravel", showTravel)
                .put("travelStart", 0).put("travelEnd", layer + 1 < path.layerCount ? path.layerTravelStart[layer + 1] : path.travelCount);
            if (nozzle != null) state.put("nozzle", new JSONArray(nozzle));
            else if (end > 0 && end < layerEnd) state.put("nozzle", new JSONArray(new double[] {path.x1[end - 1], path.y1[end - 1], path.z1[end - 1]}));
            else state.put("nozzle", JSONObject.NULL);
            web.evaluateJavascript("viewer.update(" + state + ")", null);
        } catch (Exception ignored) { }
    }

    private void setPlaying(boolean on) {
        playing = on && path != null; if (play != null) play.setText(playing ? "Pause" : "Play");
        main.removeCallbacks(player);
        if (playing) { if (layer >= path.layerCount - 1 && move >= layerSize(layer)) { layer = 0; move = 0; } main.post(player); }
    }

    private void stopFollowing() {
        if (following) {
            following = false; if (follow != null) follow.setEnabled(true);
            status.setText("Not live any more: you are looking at the file. Tap “Back to live” to jump to the printer's position.");
        }
    }

    /** Layer 1 with everything printed, seen from above: the view for checking the first layer before a print. */
    private void showFirstLayer() {
        if (path == null) return;
        stopFollowing(); setPlaying(false);
        layer = 0; move = layerSize(0); syncBars(); pushView();
        if (web != null && pageReady) web.evaluateJavascript("viewer.setView('top')", null);
        status.setText("First layer, from above. It should be solid, even lines touching edge to edge, with no gaps. Drag the Layer bar to go up.");
    }

    private int hintsShown() {
        android.content.SharedPreferences prefs = getSharedPreferences("viewer-hints", MODE_PRIVATE);
        int n = prefs.getInt("shown", 0); prefs.edit().putInt("shown", n + 1).apply(); return n;
    }

    /** The less used controls, kept out of the panel so the 3D view stays large. */
    private void moreDialog() {
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(dp(20), dp(4), dp(20), dp(8));
        AlertDialog[] dialog = new AlertDialog[1];
        java.util.List<String> names = new java.util.ArrayList<>(java.util.Arrays.asList("Show / hide line types…", showTravel ? "Hide travel moves (blue)" : "Show travel moves (blue)",
            moveBar.getVisibility() == View.VISIBLE ? "Hide the within-layer slider" : "Step through this layer…"));
        java.util.List<Runnable> actions = new java.util.ArrayList<>(java.util.Arrays.asList(this::featureDialog, () -> { showTravel = !showTravel; pushView(); },
            () -> { int v = moveBar.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE; moveBar.setVisibility(v); moveLabel.setVisibility(v); }));
        if (followMode) {
            boolean on = cameraWanted();
            names.add(on ? "Hide the printer's camera" : "Show the printer's camera in the view"); actions.add(this::toggleCamera);
            if (on) {
                names.add("Camera position: " + CAMERA_SPOTS[Math.max(0, Math.min(CAMERA_SPOTS.length - 1, viewerPrefs().getInt("cameraSpot", 0)))] + "…"); actions.add(this::cameraSpotDialog);
                names.add("Look from the camera"); actions.add(() -> { if (web != null) web.evaluateJavascript("viewer.setView('printer')", null); });
                names.add("Line up the camera by hand…"); actions.add(this::startAligning);
            }
        }
        boolean locked = viewerPrefs().getBoolean("viewLock", false);
        names.add(locked ? "Unlock rotation (drag turns the view)" : "Lock rotation (drag moves the view)");
        actions.add(() -> { viewerPrefs().edit().putBoolean("viewLock", !locked).apply(); sendLock(); });
        names.add("What am I seeing?"); actions.add(this::helpDialog);
        for (int i = 0; i < names.size(); i++) {
            Runnable action = actions.get(i);
            Button b = rowButton(body, names.get(i), () -> { dialog[0].dismiss(); action.run(); });
            ((LinearLayout.LayoutParams) b.getLayoutParams()).width = -1; ((LinearLayout.LayoutParams) b.getLayoutParams()).weight = 0; ((LinearLayout.LayoutParams) b.getLayoutParams()).leftMargin = 0;
            b.setEnabled(path != null);
        }
        dialog[0] = new AlertDialog.Builder(this).setTitle("Viewer").setView(body).setNegativeButton("Close", null).show();
    }

    /** Wraps its children onto as many lines as the width needs. */
    private static final class Flow extends android.view.ViewGroup {
        Flow(android.content.Context context) { super(context); }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec), x = 0, y = 0, line = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i); child.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST), MeasureSpec.UNSPECIFIED);
                if (x > 0 && x + child.getMeasuredWidth() > width) { x = 0; y += line; line = 0; }
                x += child.getMeasuredWidth(); line = Math.max(line, child.getMeasuredHeight());
            }
            setMeasuredDimension(width, y + line);
        }
        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int width = r - l, x = 0, y = 0, line = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                if (x > 0 && x + child.getMeasuredWidth() > width) { x = 0; y += line; line = 0; }
                child.layout(x, y, x + child.getMeasuredWidth(), y + child.getMeasuredHeight());
                x += child.getMeasuredWidth(); line = Math.max(line, child.getMeasuredHeight());
            }
        }
    }

    private void helpDialog() {
        StringBuilder text = new StringBuilder();
        text.append("Each coloured line is a stretch of plastic the printer lays down; the colour says what it is (outer wall, infill, support…). The coloured chips list the types in this file.\n\n")
            .append("Pale grey: layers below the one you are looking at, and, in the current layer, what is still to print.\n")
            .append("Blue lines (Travel moves): the nozzle moving without printing.\n")
            .append("Teal dot: the nozzle.\n\n")
            .append("Gestures: drag to turn the view, two fingers to move and zoom, double-tap to reset.\n\nTap a colour chip to hide that type; hold it to see only that type (the quickest way to find supports or the prime tower). More… > Show / hide line types does the same from a list.");
        if (followMode) text.append("\n\nCamera (More…): a dark camera shape with a faint cone shows where the printer's camera is and what it sees; the screen in front of it plays the camera's live picture. The position is an estimate: choose the spot that matches your printer under More… > Camera position.");
        if (followMode) text.append("\n\nLive: the 3D lines are this G-code file, not a camera. The printer reports its current layer and nozzle position, and the viewer shows the file printed up to there. Scrubbing the bars leaves live mode; “Back to live” returns.");
        new AlertDialog.Builder(this).setTitle("What am I seeing?").setMessage(text).setPositiveButton("Got it", null).show();
    }

    private void setControlsEnabled(boolean on) {
        for (View view : new View[] {layerBar, moveBar, play, firstLayer, more}) if (view != null) view.setEnabled(on);
        if (follow != null) follow.setEnabled(on && !following);
    }

    // ------------------------------------------------------------------ following a print
    @Override public void changed() {
        if (!followMode || printer == null || isDestroyed()) return;
        // The bed moved: the camera, fixed to the frame, sits that much higher above it.
        readBedZ();
        if (Math.abs(bedZ - shownBedZ) > 0.05 && web != null) {
            if (aligning != null) {   // keep what is on the sliders lined up while the bed moves; saved at the new height
                aligning[2] += bedZ - alignZ; alignZ = bedZ; syncAlignRow(2); pushAlign();
            } else if (cameraWanted() && pageReady && (path != null || alignOnly)) try { web.evaluateJavascript("viewer.movePrinterCamera(" + livePose(currentParams()) + ")", null); } catch (Exception ignored) { }
        }
        JSONObject live = printer.liveStatus();
        JSONObject machine = live.optJSONObject("machine_status"), print = live.optJSONObject("print_status");
        boolean printing = machine != null && machine.optInt("status", -1) == 2 && print != null;
        String filename = print == null ? "" : print.optString("filename", "");
        if (!printing || filename.isEmpty()) {
            if (following) status.setText(printer.liveFresh() ? "No print is running." : "Waiting for printer status…");
            return;
        }
        if (loadedName == null || !GcodeLibrary.safeName(filename).equals(GcodeLibrary.safeName(loadedName))) {
            File copy = library.find(filename);
            if (copy != null) { load(copy, filename); return; }
            if (path == null) { showMissing(filename, machine, print); return; }
        }
        if (!following || path == null) return;
        follow.setEnabled(false);
        int currentLayer = print.optInt("current_layer", 0);
        JSONObject position = Cc2Codec.position(live);
        boolean hasPosition = position != null && position.has("x") && position.has("y");
        double x = hasPosition ? position.optDouble("x") : 0, y = hasPosition ? position.optDouble("y") : 0;
        int located = path.locate(currentLayer, x, y, hasPosition, lastLocated);
        lastLocated = located;
        layer = located >= path.count ? path.layerCount - 1 : path.layerOf(located);
        move = located - path.layerStart(layer);
        syncBars();
        double z = path.layerZ(layer);
        pushView(hasPosition ? new double[] {x, y, z} : null);
        int progress = live.optJSONObject("machine_status").optInt("progress", -1);
        logFollow(print, position, hasPosition, currentLayer, located);
        status.setText(String.format(Locale.getDefault(), "Live · printing layer %d of %d%s%s", currentLayer, path.layerCount,
            progress >= 0 ? " · " + progress + "%" : "", hasPosition ? "" : " · nozzle position not reported, showing the layer start"));
    }

    /**
     * Records what the printer reports against the file, once per layer change and at most every minute otherwise, so a
     * field report shows whether current_layer counts from 1 and whether the nozzle position is in the file's frame.
     */
    private void logFollow(JSONObject print, JSONObject position, boolean hasPosition, int currentLayer, int located) {
        long now = System.currentTimeMillis();
        if (currentLayer == loggedLayer && now - loggedAt < 60_000) return;
        loggedLayer = currentLayer; loggedAt = now;
        StringBuilder line = new StringBuilder();
        line.append(String.format(Locale.ROOT, "printer layer %d of %d (file has %d)", currentLayer, print.optInt("total_layer", -1), path.layerCount));
        for (int candidate : new int[] {currentLayer - 1, currentLayer})
            if (candidate >= 0 && candidate < path.layerCount) line.append(String.format(Locale.ROOT, " · file layer[%d] Z %.2f", candidate, path.layerZ(candidate)));
        if (hasPosition) {
            line.append(String.format(Locale.ROOT, " · nozzle X %.2f Y %.2f", position.optDouble("x"), position.optDouble("y")));
            if (position.has("z")) line.append(String.format(Locale.ROOT, " Z %.2f", position.optDouble("z")));
            if (position.has("e")) line.append(String.format(Locale.ROOT, " E %.2f", position.optDouble("e")));
        } else line.append(" · no nozzle position");
        if (located < path.count) {
            line.append(String.format(Locale.ROOT, " · shown layer[%d] move %d/%d ends at X %.2f Y %.2f Z %.2f", layer, move, layerSize(layer), path.x1[located], path.y1[located], path.z1[located]));
        } else line.append(" · shown: end of file");
        Diagnostics.note(Diagnostics.FOLLOW, StatusPresentation.clean(print.optString("filename")) + ": " + line);
    }

    private ImageView missingPreview;
    private void showMissing(String filename, JSONObject machine, JSONObject print) {
        title.setText(StatusPresentation.clean(filename.replaceFirst("(?i)\\.gcode$", "")));
        boolean fetching = printer != null && (printer.fileBusy() || printer.feedback != null && printer.feedback.startsWith("Looking for"));
        // A failed download's message can be long (what to try next); it goes in full under the buttons, not in the short status line.
        boolean failed = !fetching && printer != null && lastAttempt && printer.feedback != null && !printer.feedback.isEmpty();
        // The running print's progress is the main message; a failed download goes in the detail under the buttons.
        status.setText(fetching ? StatusPresentation.clean(printer.feedback) : StatusPresentation.runningLine(machine, print));
        if (missingCard.getVisibility() != View.VISIBLE) {
            missingCard.removeAllViews(); missingCard.setVisibility(View.VISIBLE);
            if (liveCaption != null) liveCaption.setVisibility(View.GONE);
            missingPreview = new ImageView(this); missingPreview.setAdjustViewBounds(true); missingPreview.setScaleType(ImageView.ScaleType.FIT_CENTER); missingPreview.setVisibility(View.GONE);
            missingPreview.setContentDescription("Preview image of the print that is running, from the printer");
            missingCard.addView(missingPreview, new LinearLayout.LayoutParams(-1, dp(160)));
            label(missingCard, StatusPresentation.NO_FILE_LINE, 13, ink, false);
            buildMissingActions(filename);
            missingDetail = A11y.polite(label(missingCard, "", 13, ink, false)); missingDetail.setTextIsSelectable(true);
        }
        if (printer != null) {
            printer.thumbnail("local", filename);
            android.graphics.Bitmap picture = ThumbnailDecoder.decodeBase64(printer.thumbnails.get("local/" + filename));
            if (picture != null) { missingPreview.setImageBitmap(picture); missingPreview.setVisibility(View.VISIBLE); }
        }
        missingDetail.setText(failed ? "The download did not work: " + StatusPresentation.clean(printer.feedback) : "");
        missingDetail.setVisibility(failed ? View.VISIBLE : View.GONE);
    }

    private TextView missingDetail, liveCaption;
    private void buildMissingActions(String filename) {
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); missingCard.addView(row);
        Button download = rowButton(row, "Download from printer", () -> {
            if (printer == null) return;
            lastAttempt = true;
            printer.downloadForViewer(filename);
            status.setText(printer.feedback);
        });
        download.setEnabled(printer != null);
        rowButton(row, "Choose file…", () -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT); intent.setType("*/*"); intent.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(intent, CHOOSE);
        });
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != CHOOSE || result != RESULT_OK || data == null || data.getData() == null || printer == null) return;
        JSONObject print = printer.liveStatus().optJSONObject("print_status");
        String filename = print == null ? null : print.optString("filename", null);
        if (filename == null || filename.isEmpty()) return;
        Uri uri = data.getData();
        status.setText("Copying…");
        worker.execute(() -> {
            File temporary = new File(getCacheDir(), "viewer-choice.gcode");
            try (InputStream in = getContentResolver().openInputStream(uri); OutputStream out = new FileOutputStream(temporary)) {
                if (in == null) throw new IOException("Cannot open the file");
                byte[] buffer = new byte[65536]; int count; while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
                File stored = library.put(temporary, filename);
                main.post(() -> load(stored, filename));
            } catch (IOException failure) {
                main.post(() -> status.setText("The file could not be copied: " + failure.getMessage()));
            } finally { temporary.delete(); }
        });
    }

    // ------------------------------------------------------------------ features and legend
    /** What a line type is called on screen (the engine's "Custom" is the start and end G-code). */
    static String displayName(int feature) {
        String name = GcodeToolpath.FEATURES[feature];
        switch (name) {
            case "Custom": return "Start / end G-code";
            case "Internal solid infill": return "Solid infill";
            case "Support transition": return "Support (transition)";
            case "Support interface": return "Support (top layers)";
            default: return name;
        }
    }

    /**
     * Which types get a chip in the legend: those in the current layer, at most `max`, supports and the prime tower first
     * (they are what people look for), then the longest; in palette order. `lengths` is per type in the current layer.
     */
    static int[] legendTypes(double[] lengths, int max) {
        List<Integer> present = new ArrayList<>();
        for (int i = 0; i < lengths.length; i++) if (lengths[i] > 0) present.add(i);
        present.sort((a, b) -> {
            boolean ra = a >= 11 && a <= 14, rb = b >= 11 && b <= 14;
            if (ra != rb) return ra ? -1 : 1;
            return Double.compare(lengths[b], lengths[a]);
        });
        List<Integer> chosen = new ArrayList<>(present.subList(0, Math.min(max, present.size())));
        java.util.Collections.sort(chosen);
        int[] out = new int[chosen.size()]; for (int i = 0; i < out.length; i++) out[i] = chosen.get(i); return out;
    }

    private int legendLayer = -1;

    private double[] layerLengths(int index) {
        double[] lengths = new double[GcodeToolpath.FEATURES.length];
        for (int i = path.layerStart(index); i < path.layerEnd(index); i++) lengths[path.type[i]] += Math.hypot(path.x1[i] - path.x0[i], path.y1[i] - path.y0[i]);
        return lengths;
    }

    /** A chip for the legend: a 48dp tall tap area around a smaller visible pill. */
    private LinearLayout chip(String text, int color, boolean struck, String description, Runnable tap, Runnable hold) {
        LinearLayout area = new LinearLayout(this); area.setOrientation(LinearLayout.HORIZONTAL); area.setGravity(android.view.Gravity.CENTER_VERTICAL);
        area.setMinimumHeight(dp(48)); area.setPadding(dp(2), 0, dp(2), 0); area.setContentDescription(description);
        area.setOnClickListener(v -> tap.run());
        if (hold != null) area.setOnLongClickListener(v -> { hold.run(); return true; });
        LinearLayout pill = new LinearLayout(this); pill.setOrientation(LinearLayout.HORIZONTAL); pill.setGravity(android.view.Gravity.CENTER_VERTICAL);
        GradientDrawable back = new GradientDrawable(); back.setColor(buttonColor); back.setCornerRadius(dp(16)); pill.setBackground(back);
        pill.setPadding(dp(10), 0, dp(12), 0);
        if (color != 0) {
            View swatch = new View(this); GradientDrawable dot = new GradientDrawable(); dot.setColor(color); dot.setCornerRadius(dp(3)); dot.setStroke(Math.max(1, Math.round(1.5f * getResources().getDisplayMetrics().density)), muted); swatch.setBackground(dot); // outlined: pale colours stay visible on the chip
            pill.addView(swatch, new LinearLayout.LayoutParams(dp(12), dp(12)));
        }
        TextView name = new TextView(this); name.setText(text); name.setTextSize(12); name.setTextColor(struck ? muted : ink); name.setPadding(color != 0 ? dp(6) : 0, 0, 0, 0);
        if (struck) name.setPaintFlags(name.getPaintFlags() | android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
        pill.addView(name);
        pill.setMinimumHeight(dp(32)); area.addView(pill, new LinearLayout.LayoutParams(-2, -2)); // grows with the text size
        return area;
    }

    private void buildLegend() {
        legend.removeAllViews();
        if (path == null) return;
        legendLayer = layer;
        double[] here = layerLengths(layer), all = path.featureLengths();
        int[] shown = legendTypes(here, 4);
        for (int i : shown) {
            final int feature = i; boolean off = (hidden >> i & 1) == 1;
            legend.addView(chip(displayName(i), Color.parseColor(PALETTE[i]), off, displayName(i) + (off ? ", hidden. Tap to show." : ". Tap to hide, hold to show only this."),
                () -> { hidden ^= 1 << feature; buildLegend(); pushView(); }, () -> { hidden = soloMask(feature); buildLegend(); pushView(); }));
        }
        int others = 0; for (int i = 0; i < all.length; i++) if (all[i] > 0) { boolean in = false; for (int k : shown) in |= k == i; if (!in) others++; }
        if (others > 0) legend.addView(chip("+" + others + " more", 0, false, others + " more line types. Opens the show and hide list.", this::featureDialog, null));
    }

    /** Hides every feature that is in the file except `keep`; a second long-press on the only visible one shows all. */
    private int soloMask(int keep) {
        double[] lengths = path.featureLengths(); int mask = 0;
        for (int i = 0; i < lengths.length; i++) if (i != keep && lengths[i] > 0) mask |= 1 << i;
        return mask == hidden ? 0 : mask;
    }

    private void featureDialog() {
        if (path == null) return;
        double[] lengths = path.featureLengths();
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(dp(20), dp(8), dp(20), 0);
        for (int i = 0; i < lengths.length; i++) {
            if (lengths[i] <= 0) continue;
            final int feature = i;
            CheckBox box = new CheckBox(this);
            box.setText(String.format(Locale.getDefault(), "%s · %.1f m", displayName(i), lengths[i] / 1000));
            box.setMinHeight(dp(48));
            box.setTextColor(ink); box.setChecked((hidden >> i & 1) == 0); box.setButtonTintList(ColorStateList.valueOf(Color.parseColor(PALETTE[i])));
            box.setOnCheckedChangeListener((view, checked) -> { hidden = checked ? hidden & ~(1 << feature) : hidden | (1 << feature); buildLegend(); pushView(); });
            body.addView(box);
        }
        ScrollView scroll = new ScrollView(this); scroll.addView(body);
        new AlertDialog.Builder(this).setTitle("Show or hide line types").setView(scroll).setPositiveButton("Done", null)
            .setNeutralButton("Show all", (d, w) -> { hidden = 0; buildLegend(); pushView(); }).show();
    }

    // ------------------------------------------------------------------ helpers
    private static JSONArray rgb(int color) throws org.json.JSONException {
        return new JSONArray(new double[] {Color.red(color) / 255.0, Color.green(color) / 255.0, Color.blue(color) / 255.0});
    }
    private TextView label(LinearLayout parent, String text, int size, int color, boolean bold) {
        TextView view = new TextView(this); view.setText(text); view.setTextSize(size); view.setTextColor(color); if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setPadding(0, dp(3), 0, dp(3)); parent.addView(view, new LinearLayout.LayoutParams(-1, -2)); return view;
    }
    private SeekBar seekBar(LinearLayout parent) {
        SeekBar bar = new SeekBar(this); bar.setProgressTintList(ColorStateList.valueOf(teal)); bar.setThumbTintList(ColorStateList.valueOf(teal));
        parent.addView(bar, new LinearLayout.LayoutParams(-1, dp(48))); return bar;
    }
    private Button rowButton(LinearLayout row, String text, Runnable action) {
        Button button = new A11y.DimButton(this); button.setText(text); button.setAllCaps(false); button.setMinHeight(dp(48)); button.setMinimumHeight(dp(48));
        button.setPadding(dp(6), dp(4), dp(6), dp(4)); button.setTextSize(13);
        button.setTextColor(new ColorStateList(new int[][] {new int[] {-android.R.attr.state_enabled}, new int[] {}}, new int[] {muted, teal}));
        GradientDrawable shape = new GradientDrawable(); shape.setColor(buttonColor); shape.setCornerRadius(dp(12));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(dark ? 0x4463d5c7 : 0x33006b65), shape, null));
        button.setOnClickListener(view -> action.run());
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(0, -2, 1); layout.topMargin = dp(6); if (row.getChildCount() > 0) layout.leftMargin = dp(8);
        row.addView(button, layout); return button;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
