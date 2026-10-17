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
    static final String EXTRA_FILE = "file", EXTRA_NAME = "name", EXTRA_FOLLOW = "follow", EXTRA_ALIGN = "align", EXTRA_CAMERA = "camera";
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
    private LinearLayout alignSheet;   // the panel as shown: the scrolling sections, and the buttons fixed at its foot
    private double[] aligning, alignStart;
    private double alignZ;
    private CameraFrames cameraFrames;
    private boolean cameraStarted, cameraCloud;
    /** The added camera playing in the view, or null (the printer's own camera or none). */
    private ExtraCameras.Camera addedCamera;
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
        applyPlate();
        super.onCreate(saved);
        ink = dark ? 0xffe6eef1 : 0xff17252c; muted = dark ? 0xff9fb3bb : 0xff5a6d76; teal = dark ? 0xff5fd4c4 : 0xff00796b;
        background = dark ? 0xff0e1417 : 0xfff2f5f6; surface = dark ? 0xff182227 : Color.WHITE; buttonColor = dark ? 0xff21343a : 0xffe2efed;
        library = new GcodeLibrary(new File(getFilesDir(), "gcode-library"));
        Diagnostics.init(getFilesDir()); Crashes.install(this);
        followMode = getIntent().getBooleanExtra(EXTRA_FOLLOW, false);
        alignOnly = followMode && getIntent().getBooleanExtra(EXTRA_ALIGN, false);
        following = followMode;
        // The camera shown: the one asked for (an added camera's "Line up in 3D…"), else the last one chosen here.
        String askedCamera = getIntent().getStringExtra(EXTRA_CAMERA);
        if (askedCamera != null) viewerPrefs().edit().putString("viewCamera", askedCamera).apply();
        camId = viewerPrefs().getString("viewCamera", "");
        if (!camId.isEmpty() && ExtraCameras.find(this, camId) == null) { camId = ""; viewerPrefs().edit().putString("viewCamera", "").apply(); }
        // The live preview opens as it was left: line types shown, travel moves, and (once loaded) the view itself.
        if (followMode) { hidden = viewerPrefs().getInt("liveHidden", hidden); showTravel = viewerPrefs().getBoolean("liveTravel", showTravel); }
        build();
        String file = getIntent().getStringExtra(EXTRA_FILE), name = getIntent().getStringExtra(EXTRA_NAME);
        if (file != null) load(new File(file), name != null ? name : new File(file).getName());
        else if (!followMode) status.setText("No G-code file to show.");
        if (followMode) bound = bindService(new Intent(this, PrinterService.class), connection, BIND_AUTO_CREATE);
        if (getIntent().getBooleanExtra(Showcase.EXTRA, false) && Showcase.stage == Showcase.VIEWER) showcase();
    }

    // ------------------------------------------------------------------ showcase tour
    private boolean loadedOnce;
    private Showcase.Script showcaseScript;
    /** The live toolpath's part of the showcase: changes only how things are shown here, and puts every option back after. */
    private void showcase() {
        boolean details = viewerPrefs().getBoolean("details", true), buttons = viewerPrefs().getBoolean("quickButtons", true);
        String head = headLook(); int[] hiddenBefore = {0};
        Showcase.Script script = new Showcase.Script(this); showcaseScript = script;
        script.until(30_000, () -> loadedOnce && path != null)
            .then(followMode ? 2500 : 800, () -> {
                hiddenBefore[0] = hidden;
                viewerPrefs().edit().putBoolean("quickButtons", true).putBoolean("details", false).apply(); syncOverlay(); showDetails(shownSegment, true);
                Showcase.caption(this, followMode ? "Live toolpath: the file drawn up to the nozzle, over the camera" : "The toolpath of the file, in 3D");
            })
            .then(4800, () -> Showcase.fade(details(), () -> { viewerPrefs().edit().putBoolean("details", true).apply(); syncOverlay(); showDetails(shownSegment, true);
                Showcase.caption(this, "Details: the G-code line, the file's time, the camera's lag and the filament"); }))
            .then(followMode ? 5200 : 0, () -> { if (!followMode) return;
                Showcase.fade(stage, () -> { viewerPrefs().edit().putString("headLook", "mask").apply(); pushView(); syncOverlay(); }); Showcase.caption(this, "The print head masked out of the picture"); })
            .then(followMode ? 4000 : 0, () -> { if (!followMode) return;
                Showcase.fade(stage, () -> { viewerPrefs().edit().putString("headLook", "outline").apply(); pushView(); syncOverlay(); }); Showcase.caption(this, "…or drawn as an outline"); })
            .then(followMode ? 4000 : 0, () -> {
                if (followMode) { viewerPrefs().edit().putString("headLook", head).apply(); pushView(); syncOverlay(); }
                Showcase.fade(stage, () -> js("viewer.resetCamera()")); Showcase.caption(this, "Turn it freely in 3D");
            })
            .then(900, () -> js("viewer.orbit(6500, 110)"))
            .then(6800, () -> Showcase.fade(stage, () -> { hidden = soloMask(0); buildLegend(); pushView(); }))
            .then(0, () -> Showcase.caption(this, "Line types: only the outer walls…"))
            .then(3800, () -> { int infill = path.featureLengths().length > 3 && path.featureLengths()[3] > 0 ? 3 : 1; Showcase.fade(stage, () -> { hidden = soloMask(infill); buildLegend(); pushView(); });
                Showcase.caption(this, "…only the infill…"); })
            .then(3800, () -> { Showcase.fade(stage, () -> { hidden = 0; buildLegend(); pushView(); }); Showcase.caption(this, "…or everything"); })
            .then(3200, () -> { showFirstLayer(); Showcase.caption(this, "Replay the print layer by layer"); })
            .then(1200, () -> setPlaying(true))
            .then(7500, () -> setPlaying(false))
            .then(300, () -> {
                if (followMode && follow != null) { follow.performClick(); Showcase.fade(stage, () -> { if (cameraWanted()) js("viewer.setView('printer')"); else js("viewer.resetCamera()"); }); Showcase.caption(this, "Back to live: where the nozzle is now"); }
                else Showcase.caption(this, null);
            })
            .then(followMode ? 4200 : 300, () -> {
                hidden = hiddenBefore[0]; buildLegend(); pushView();
                viewerPrefs().edit().putBoolean("details", details).putBoolean("quickButtons", buttons).apply(); syncOverlay(); showDetails(shownSegment, true);
                Showcase.caption(this, null);
            })
            .then(500, () -> Showcase.toRecording(this, printer));
        script.run(null);
    }
    private View details() { return details; }

    @Override protected void onStart() { super.onStart(); lastStepAt = 0; if (printer != null && followMode) printer.watch(this); startCamera(); }
    @Override protected void onStop() {
        if (followMode && web != null && pageReady && path != null) web.evaluateJavascript("JSON.stringify(viewer.getViewState())", value -> {
            try { Object text = new org.json.JSONTokener(value).nextValue(); if (text instanceof String) viewerPrefs().edit().putString("liveView", (String) text).apply(); } catch (Exception ignored) { }
        }); setPlaying(false); stopCamera(); if (printer != null) printer.unwatch(this); super.onStop(); }
    @Override protected void onDestroy() {
        if (showcaseScript != null) showcaseScript.cancel();
        stopCamera();
        if (printer != null) printer.unwatch(this);
        if (bound) unbindService(connection);
        worker.shutdownNow(); main.removeCallbacksAndMessages(null); if (cursor != null) cursor.close();
        if (web != null) { ((android.view.ViewGroup) web.getParent()).removeView(web); web.destroy(); web = null; }
        super.onDestroy();
    }

    // ------------------------------------------------------------------ layout
    private void build() {
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(background); root.setFitsSystemWindows(true);
        web = new WebView(this);
        // The 3D view with a column of quick buttons over its right edge; they fold away into one chip.
        stage = new android.widget.FrameLayout(this);
        stage.addView(web, new android.widget.FrameLayout.LayoutParams(-1, -1));
        overlay = new LinearLayout(this); overlay.setOrientation(LinearLayout.VERTICAL); overlay.setPadding(dp(8), dp(8), dp(8), dp(8));
        // Scrolls when a short view or large text can't fit the whole column, so no button is ever cut off.
        ScrollView column = new ScrollView(this); column.setVerticalScrollBarEnabled(false); column.addView(overlay);
        stage.addView(column, new android.widget.FrameLayout.LayoutParams(-2, -2, android.view.Gravity.TOP | android.view.Gravity.END));
        // Details over the top-left corner, clear of the button column: one line each for the G-code, the file's time there,
        // the camera and the filament, with the names in a column.
        details = new LinearLayout(this); details.setOrientation(LinearLayout.VERTICAL); details.setPadding(dp(6), dp(3), dp(6), dp(3));
        // A faint plate and shadowed text: readable over the picture while the picture still shows through.
        GradientDrawable plate = new GradientDrawable(); plate.setColor(0x40000000); plate.setCornerRadius(dp(8)); details.setBackground(plate); A11y.polite(details);
        int valueWidth = Math.round(getResources().getDisplayMetrics().widthPixels * 0.64f) - dp(68);
        String[] names = {"G-code", "Print at", "Camera", "Filament"};
        for (int i = 0; i < names.length; i++) {
            LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.setPadding(0, 0, 0, 0);
            TextView name = new TextView(this); name.setText(names[i]); name.setTextSize(10); name.setTextColor(0xccd0d8de); name.setSingleLine(true); name.setShadowLayer(dp(2), 0, dp(1), 0xff000000);
            row.addView(name, new LinearLayout.LayoutParams(dp(56), -2));
            TextView value = new TextView(this); value.setTextSize(10.5f); value.setShadowLayer(dp(2), 0, dp(1), 0xff000000); value.setTextColor(0xffffffff); value.setSingleLine(true); value.setEllipsize(android.text.TextUtils.TruncateAt.END);
            value.setMaxWidth(valueWidth); if (i == 0) { value.setTypeface(android.graphics.Typeface.MONOSPACE); value.setTextSize(9.5f); }
            row.addView(value, new LinearLayout.LayoutParams(-2, -2)); details.addView(row); detailValues[i] = value;
        }
        android.widget.FrameLayout.LayoutParams corner = new android.widget.FrameLayout.LayoutParams(-2, -2, android.view.Gravity.TOP | android.view.Gravity.START);
        corner.setMargins(dp(8), dp(8), 0, 0); stage.addView(details, corner); details.setVisibility(View.GONE);
        buildOverlay();
        root.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));
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
        status = A11y.polite(label(titles, "Reading toolpath…", 12, muted, false)); status.setPadding(0, 0, 0, 0);
        legend = new Flow(this); panel.addView(legend, new LinearLayout.LayoutParams(-1, -2));
        // One hint line under the chips: how to hide a type, and how the view works (live view always; gestures the first few times).
        int shown = hintsShown();
        String tip = "Tap a line type to hide it. Hold it to show only that type.";
        String how = followMode ? " Live: the file up to the nozzle. Grey: still to print on this layer."
            : shown < 3 ? " Drag to turn. Two fingers to move and zoom. Double-tap to reset." : "";
        hint = label(panel, tip + how, 12, muted, false);
        // The hint describes a drawn toolpath; it hides while there is no file to draw.
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
        if (followMode) follow = rowButton(row, "Back to live", () -> { following = true; lastLocated = -1; setPlaying(false); sendLockstep(); syncOverlay(); changed(); });
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

    /** An added camera's picture turned and mirrored as it is set to be (the printer's own pictures pass as they are). */
    private byte[] turned(byte[] jpeg) {
        ExtraCameras.Camera added = addedCamera;
        if (jpeg == null || added == null || added.rotation == 0 && !added.mirror) return jpeg;
        try {
            android.graphics.Bitmap picture = ExtraCameras.turn(ExtraCameras.decode(jpeg), added.rotation, added.mirror);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(); picture.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out); return out.toByteArray();
        } catch (Exception unreadable) { return jpeg; }
    }
    /** The newest camera picture for the scene; the page asks for one only after it has shown the previous one. */
    private WebResourceResponse serveFrame(String name) {
        CameraFrames frames = cameraFrames;
        byte[] frame = frames == null || !name.startsWith("frame.jpg") ? null : turned(frames.take());
        if (frame == null) return new WebResourceResponse("text/plain", "utf-8", 404, "Not found", null, new ByteArrayInputStream(new byte[0]));
        return new WebResourceResponse("image/jpeg", null, new ByteArrayInputStream(frame));
    }

    // ------------------------------------------------------------------ the printer's camera in the scene
    private android.content.SharedPreferences viewerPrefs() { return getSharedPreferences("viewer-hints", MODE_PRIVATE); }
    // ------------------------------------------------------------------ quick buttons over the 3D view
    private LinearLayout overlay;
    private android.widget.FrameLayout stage;
    private Button viewButton, liveButton, headButton;
    private final java.util.List<Button> overlayButtons = new java.util.ArrayList<>();
    private Button overlayToggle;
    private void buildOverlay() {
        overlayToggle = chipButton("", () -> { viewerPrefs().edit().putBoolean("quickButtons", !viewerPrefs().getBoolean("quickButtons", true)).apply(); syncOverlay(); });
        overlayButtons.remove(overlayToggle);
        viewButton = chipButton("3D view", () -> { if (web != null && pageReady) web.evaluateJavascript("viewer.getViewState().looking", looking -> {
            if ("true".equals(looking)) js("viewer.resetCamera()"); else js("viewer.setView('printer')");
            main.postDelayed(this::syncOverlay, 100); }); });
        liveButton = chipButton("Back to live", () -> { if (follow != null) follow.performClick(); });
        chipButton("Line types", this::featureDialog).setContentDescription("Show or hide line types");
        headButton = chipButton("Head", () -> {
            String next = "off".equals(headLook()) ? "mask" : "mask".equals(headLook()) ? "outline" : "off";
            viewerPrefs().edit().putString("headLook", next).apply(); pushView(); syncOverlay();
        });
        detailsButton = chipButton("Details", () -> { viewerPrefs().edit().putBoolean("details", !viewerPrefs().getBoolean("details", true)).apply(); syncOverlay(); showDetails(shownSegment, true); });
        chipButton("Reset view", () -> js("viewer.resetCamera()"));
        syncOverlay();
    }
    /** A compact translucent button for the 3D view: readable over the picture, 48 dp tall. */
    private Button chipButton(String text, Runnable action) {
        Button b = new Button(this); b.setText(text); b.setAllCaps(false); b.setTextSize(13); b.setTextColor(ink); b.setMinHeight(dp(48)); b.setMinimumHeight(dp(48));
        b.setMinWidth(dp(48)); b.setMinimumWidth(dp(48)); b.setPadding(dp(12), 0, dp(12), 0);
        GradientDrawable shape = new GradientDrawable(); shape.setColor((surface & 0x00ffffff) | 0xd9000000); shape.setCornerRadius(dp(24)); shape.setStroke(Math.max(1, dp(1)), (muted & 0x00ffffff) | 0x55000000);
        b.setBackground(shape); b.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-2, -2); layout.gravity = android.view.Gravity.END; layout.bottomMargin = dp(6);
        overlay.addView(b, layout); overlayButtons.add(b); return b;
    }
    /** Shows the buttons that fit now, with labels that say what a tap does. */
    private void syncOverlay() {
        if (overlay == null) return;
        boolean shown = viewerPrefs().getBoolean("quickButtons", true);
        for (Button b : overlayButtons) b.setVisibility(shown ? View.VISIBLE : View.GONE);
        if (shown) {
            viewButton.setVisibility(cameraWanted() ? View.VISIBLE : View.GONE);
            liveButton.setVisibility(followMode && !following ? View.VISIBLE : View.GONE);
            headButton.setVisibility(followMode ? View.VISIBLE : View.GONE);
            headButton.setText("Head: " + ("mask".equals(headLook()) ? "mask" : "outline".equals(headLook()) ? "outline" : "off"));
            headButton.setContentDescription("Printhead: " + headLook() + ". Tap to change");
            if (web != null && pageReady) web.evaluateJavascript("viewer.getViewState().looking", looking -> {
                boolean fromCamera = "true".equals(looking);
                viewButton.setText(fromCamera ? "3D view" : "Camera view"); viewButton.setContentDescription(fromCamera ? "Leave the camera view" : "Look from the printer's camera");
            });
        }
        if (detailsButton != null) { boolean on = viewerPrefs().getBoolean("details", true); detailsButton.setText(on ? "Details: on" : "Details: off"); A11y.state(detailsButton, on ? "On" : "Off"); }
        overlayToggle.setText(shown ? "Hide ›" : "‹ Buttons");
        overlayToggle.setContentDescription(shown ? "Hide the quick buttons" : "Show the quick buttons");
        A11y.expandable(overlayToggle, shown);
    }
    private boolean cameraWanted() { return followMode && (alignOnly || viewerPrefs().getBoolean("camera", false)); }
    /** Opened only to line the camera up (Camera tab): the bed and camera show without a print or toolpath. */
    private boolean alignOnly;
    /**
     * Where the bed is now (the printer's reported Z). The CC2's bed goes down as a print grows while the camera stays on the
     * frame, so camera heights are kept with the bed at 0 and drawn this much higher above the bed.
     */
    private double bedZ, shownBedZ = Double.NaN;
    /** Whether the bed's height can be trusted (Z homed, not moving); see BedHeight. */
    private final BedHeight bed = new BedHeight();
    private void readBedZ() {
        if (printer == null) return;
        bed.report(printer.liveStatus(), System.currentTimeMillis());
        double layerZ = printingLayerZ();
        // While printing, the bed sits at the height of the layer being printed: the reported Z also carries every Z-hop
        // and lift, which would make the camera overlay jump about between layers.
        if (!Double.isNaN(layerZ)) bedZ = layerZ;
        else if (!Double.isNaN(bed.z())) bedZ = Math.max(0, bed.z());   // only heights reported while Z is homed
    }
    /** The height of the layer the printer reports printing, from this file; NaN when not printing it. */
    private double printingLayerZ() {
        if (path == null || printer == null) return Double.NaN;
        JSONObject live = printer.liveStatus();
        JSONObject machine = live.optJSONObject("machine_status"), print = live.optJSONObject("print_status");
        if (machine == null || machine.optInt("status", -1) != 2 || print == null) return Double.NaN;
        int current = print.optInt("current_layer", 0);
        return current >= 1 && current <= path.layerCount ? path.layerZ(current - 1) : Double.NaN;
    }
    private boolean heightReady() { return bed.state(System.currentTimeMillis()) == BedHeight.State.KNOWN; }
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
    private double[] currentParams() { return cameraFor(viewerPrefs(), bedZ, camId); }
    /**
     * Which camera the view shows and lines up: "" for the printer's own, else an added camera's id. Each camera keeps its
     * own line-up (and picture delay) under its own keys.
     */
    private String camId = "";
    static String camKey(String base, String id) { return id == null || id.isEmpty() ? base : base + "@" + id; }
    private String ck(String base) { return camKey(base, camId); }
    /** Where an added camera starts before it is lined up: above the front of the bed (the printer's own starts at its real spot). */
    static int defaultSpot(String id) { return id == null || id.isEmpty() ? 0 : 4; }
    static double[] cameraFor(android.content.SharedPreferences prefs, double bedZ) { return cameraFor(prefs, bedZ, ""); }
    /** The camera (as lined up in this viewer, else the chosen spot) for the bed at height bedZ; also used by the print guardian. */
    static double[] cameraFor(android.content.SharedPreferences prefs, double bedZ, String id) {
        int spot = prefs.getInt(camKey("cameraSpot", id), defaultSpot(id));
        java.util.List<double[]> points = spot == LINED_UP ? savedPoints(prefs, id) : java.util.Collections.emptyList();
        if (!points.isEmpty()) return modelAt(points, bedZ);
        double[] c = spotParams(spot == LINED_UP ? defaultSpot(id) : spot); c[2] += bedZ; return c;
    }
    /** Whether this camera has been lined up by hand (so the picture and the toolpath match). */
    static boolean linedUp(android.content.SharedPreferences prefs, String id) { return prefs.getInt(camKey("cameraSpot", id), defaultSpot(id)) == LINED_UP && !savedPoints(prefs, id).isEmpty(); }
    /** Saved line-ups as {bed Z, x, y, height above the bed, turn, tilt, view angle, lens}. */
    private java.util.List<double[]> savedPoints() { return savedPoints(viewerPrefs(), camId); }
    static java.util.List<double[]> savedPoints(android.content.SharedPreferences prefs) { return savedPoints(prefs, ""); }
    static java.util.List<double[]> savedPoints(android.content.SharedPreferences prefs, String id) {
        java.util.List<double[]> points = parsePoints(prefs.getString(camKey("cameraPoints", id), null));
        if (points.isEmpty()) {   // v0.14.5 kept one line-up with the bed at 0
            double[] old = parseParams(prefs.getString(camKey("cameraCustomBed0", id), null));
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
        ExtraCameras.Camera added = camId.isEmpty() ? null : ExtraCameras.find(this, camId);
        if (added != null) {
            // A camera the person added: its stream or snapshots, with its own line-up.
            String url = ExtraCameras.url(this, added);
            if (!ExtraCameras.problem(url).isEmpty()) { cameraMessage("The camera “" + added.name + "” has no usable address. Edit it in Print, Camera, Added cameras."); return; }
            PrinterHttp.ConnectionFactory factory = ExtraCameras.connections(added.user, ExtraCameras.password(this, added));
            cameraStarted = true; cameraCloud = false; addedCamera = added;
            CameraFrames.Listener listener = new CameraFrames.Listener() {
                public void frame() { main.post(() -> { if (web != null && cameraFrames != null) { web.evaluateJavascript("viewer.cameraFrame('/live/frame.jpg?n=" + (frameNumber++) + "')", null); frameAt = System.currentTimeMillis(); keepLocalPicture(); } }); }
                public void error(String message) { main.post(() -> { cameraStarted = false; cameraFrames = null; cameraMessage(added.name + ": " + message + " Check it in Print, Camera, Added cameras."); }); }
            };
            cameraFrames = ExtraCameras.SNAPSHOT.equals(added.kind) ? CameraFrames.snapshots(url, factory, 1000, listener) : new CameraFrames(url, factory, listener);
            cameraMessage("Camera: " + added.name + (linedUp(viewerPrefs(), camId) ? "." : ". Line it up by hand (More…, Line up the camera by hand) so the picture matches the toolpath."));
            return;
        }
        addedCamera = null;
        if (printer.ready() || printer.localCamera()) {
            // Locally, or alongside the cloud when the printer's own stream was found to work on this Wi-Fi.
            boolean alongside = !printer.ready();
            String host = alongside ? printer.localHost : printer.host(), url;
            try { url = alongside ? printer.localCameraUrl() : FeatureData.cameraUrl(host, printer.cameraUrl.isEmpty() ? "http://" + host + ":8080/?action=stream" : printer.cameraUrl); }
            catch (Exception invalid) { cameraMessage("The camera address is not on the printer. It was not opened."); return; }
            NetworkRoute route;
            try { route = alongside ? NetworkRoute.local(this) : NetworkRoute.select(this, printer.remote()); } catch (IOException unavailable) { cameraMessage(unavailable.getMessage()); return; }
            cameraStarted = true; cameraCloud = false;
            cameraFrames = new CameraFrames(url, route.http(), new CameraFrames.Listener() {
                public void frame() { main.post(() -> { if (web != null && cameraFrames != null) { web.evaluateJavascript("viewer.cameraFrame('/live/frame.jpg?n=" + (frameNumber++) + "')", null); frameAt = System.currentTimeMillis(); keepLocalPicture(); } }); }
                public void error(String message) { main.post(() -> { cameraStarted = false; cameraFrames = null; cameraMessage(message + " Open the camera from Monitor to check it."); }); }
            });
            cameraMessage(alongside ? "Camera: the printer's own stream on this Wi-Fi, alongside the cloud." : "Camera: the printer's own stream on this network.");
        } else if (printer.usingCloud() && !printer.cloudSerial.isEmpty()) {
            if (!getSharedPreferences("workshop-settings", MODE_PRIVATE).getBoolean("cloudControlUnderstood", false)) {
                cameraMessage("The cloud camera needs cloud control turned on. Print > Camera explains how. The camera model is shown without a picture."); return;
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
        } else cameraMessage("No camera picture. Connect to the printer, or watch it through the Elegoo cloud.");
    }
    private void stopCamera() {
        CameraFrames frames = cameraFrames; cameraFrames = null; if (frames != null) frames.close();
        if (cameraStarted && cameraCloud && web != null) web.evaluateJavascript("viewer.cameraStop()", null);
        cameraStarted = false; addedCamera = null;
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
    private ExtraCameras.Camera addedOrNull() { return camId.isEmpty() ? null : ExtraCameras.find(this, camId); }
    /** The printer's own camera or an added one; each keeps its own line-up. */
    private void chooseCamera() {
        List<ExtraCameras.Camera> cameras = ExtraCameras.list(this);
        String[] names = new String[cameras.size() + 1]; names[0] = "The printer's camera";
        for (int i = 0; i < cameras.size(); i++) names[i + 1] = cameras.get(i).name + (linedUp(viewerPrefs(), cameras.get(i).id) ? " (lined up)" : " (not lined up yet)");
        new AlertDialog.Builder(this).setTitle("Camera in the view").setItems(names, (d, which) -> {
            stopCamera();
            camId = which == 0 ? "" : cameras.get(which - 1).id;
            viewerPrefs().edit().putString("viewCamera", camId).apply();
            if (!cameraWanted()) viewerPrefs().edit().putBoolean("camera", true).apply();
            startCamera(); sendLockstep();
        }).setNegativeButton("Cancel", null).show();
    }
    private void cameraSpotDialog() {
        new AlertDialog.Builder(this).setTitle("Where is the camera?")
            .setSingleChoiceItems(CAMERA_SPOTS, viewerPrefs().getInt(ck("cameraSpot"), defaultSpot(camId)), (d, which) -> {
                d.dismiss();
                if (which == LINED_UP) { startAligning(); return; }
                viewerPrefs().edit().putInt(ck("cameraSpot"), which).apply();
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
        mainPanel.setVisibility(View.GONE); alignSheet.setVisibility(View.VISIBLE); arrange();
        if (android.os.Build.VERSION.SDK_INT >= 33 && alignBack == null) {
            android.window.OnBackInvokedCallback callback = () -> finishAligning(false); alignBack = callback;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, (android.window.OnBackInvokedCallback) alignBack);
        }
        setMarking(true);   // taps mark from the start; drag pans and pinch zooms the picture, as elsewhere in the camera view
        pushAlign();
    }
    /**
     * Sideways while lining up, the sliders sit in a column beside the picture, so the whole camera picture shows (upright,
     * a tall screen shows only its middle, and the edges are where a lens curve shows). Otherwise the panel is below.
     */
    private void arrange() {
        if (root == null || stage == null) return;
        boolean beside = aligning != null && alignSheet != null
            && getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        root.setOrientation(beside ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        stage.setLayoutParams(beside ? new LinearLayout.LayoutParams(0, -1, 1) : new LinearLayout.LayoutParams(-1, 0, 1));
        if (alignSheet != null) {
            alignSheet.setLayoutParams(beside ? new LinearLayout.LayoutParams(dp(340), -1) : new LinearLayout.LayoutParams(-1, -2));
            alignScroll.setLayoutParams(beside ? new LinearLayout.LayoutParams(-1, 0, 1) : new LinearLayout.LayoutParams(-1, -2));
            android.graphics.drawable.Drawable panel = alignSheet.getBackground();
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
        // The panel is a sheet: its sections scroll above, and Cancel / Reset / Save stay in view at its foot.
        alignSheet = new LinearLayout(this); alignSheet.setOrientation(LinearLayout.VERTICAL);
        alignSheet.setBackground(mainPanel.getBackground().getConstantState().newDrawable());
        alignScroll = new android.widget.ScrollView(this) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                // Portrait: the scrolling part takes at most 40% of the screen, so the picture above keeps room.
                if (getResources().getConfiguration().orientation != Configuration.ORIENTATION_LANDSCAPE) {
                    int cap = Math.round(getResources().getDisplayMetrics().heightPixels * 0.4f);
                    int size = View.MeasureSpec.getMode(heightSpec) == View.MeasureSpec.UNSPECIFIED ? cap : Math.min(cap, View.MeasureSpec.getSize(heightSpec));
                    heightSpec = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.AT_MOST);
                }
                super.onMeasure(widthSpec, heightSpec);
            }
        };
        alignPanel = new LinearLayout(this); alignPanel.setOrientation(LinearLayout.VERTICAL); alignPanel.setPadding(dp(16), dp(8), dp(16), dp(8));
        label(alignPanel, "Line the yellow outline up with the bed in the picture. Turn the phone sideways to see all of it.", 12, muted, false);
        // The main way in: tap along the bed's edges, then Fit. Open from the start.
        A11y.heading(label(alignPanel, "Line up from taps", 15, ink, true));
        buildMarking();
        // Nudging by hand, after Fit or instead of it (the sliders are added below).
        LinearLayout fine = section("Fine-tune by hand", false);
        // How the picture looks, and whether a drag turns the view.
        LinearLayout viewOpts = section("View options", false);
        LinearLayout lockRow = new LinearLayout(this); lockRow.setOrientation(LinearLayout.HORIZONTAL); viewOpts.addView(lockRow);
        alignLockButton = rowButton(lockRow, "", () -> { viewerPrefs().edit().putBoolean("alignLock", !viewerPrefs().getBoolean("alignLock", true)).apply(); sendLock(); });
        LinearLayout zoom = new LinearLayout(this); zoom.setOrientation(LinearLayout.HORIZONTAL); viewOpts.addView(zoom);
        rowButton(zoom, "Zoom in", () -> js("viewer.zoomPicture(1.5)"));
        rowButton(zoom, "Zoom out", () -> js("viewer.zoomPicture(1 / 1.5)"));
        rowButton(zoom, "Reset zoom", () -> js("viewer.resetZoom()"));
        // How the outline and the taps look over the picture (kept between line-ups).
        LinearLayout look = new LinearLayout(this); look.setOrientation(LinearLayout.HORIZONTAL); viewOpts.addView(look);
        gridButton = rowButton(look, "", () -> {
            String now = viewerPrefs().getString("alignGrid", "all");
            viewerPrefs().edit().putString("alignGrid", "all".equals(now) ? "edges" : "edges".equals(now) ? "none" : "all").apply();
            sendAlignStyle();
        });
        styleSlider(viewOpts, "Outline strength", "alignAlpha", 15, 100, 90, "%");
        styleSlider(viewOpts, "Dot size", "alignDot", 4, 32, 12, " px");
        // The plate can be larger than the 256 mm print area; the taps mark the plate's edges, so the fit needs to know by how much.
        LinearLayout plateBox = section("Plate edges", false);
        label(plateBox, "The taps mark the plate's edges, but the print area (256 × 256 mm) can sit inside a larger plate. If the outline matches the plate "
            + "but prints land off to one side, set how far the plate reaches past the print area. Each change fits again from your saved taps; Save when it lines up.", 12, muted, false);
        String[] plateNames = {"Left", "Right", "Front", "Back"}, plateKeys = {"plateLeft", "plateRight", "plateFront", "plateBack"};
        for (int i = 0; i < 4; i++) styleSlider(plateBox, plateNames[i] + " margin", plateKeys[i], 0, 40, 0, " mm", this::plateChanged);
        // Other bed heights: the bed's note, the moves of the real printer, and the saved line-ups.
        LinearLayout other = section("Other bed heights", false);
        bedNote = A11y.polite(label(other, "", 12, muted, false));
        buildBedMoves(other);
        LinearLayout forget = new LinearLayout(this); forget.setOrientation(LinearLayout.HORIZONTAL); other.addView(forget);
        rowButton(forget, "Forget saved line-ups", this::forgetLineUps);
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
                nudge.setMinWidth(dp(48)); nudge.setMinimumWidth(dp(48)); nudge.setMinHeight(dp(48)); nudge.setMinimumHeight(dp(48)); nudge.setPadding(0, 0, 0, 0);
                nudge.setContentDescription((direction < 0 ? "Less " : "More ") + ALIGN_NAMES[i].toLowerCase(Locale.ROOT));
                nudge.setOnClickListener(v -> {
                    if (aligning == null) return;
                    aligning[index] = Math.max(ALIGN_RANGE[index][0], Math.min(ALIGN_RANGE[index][1], aligning[index] + direction * ALIGN_STEP[index]));
                    syncAlignRow(index); pushAlign();
                });
                row.addView(nudge, new LinearLayout.LayoutParams(dp(48), dp(48)));
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
            LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-1, -2); gap.topMargin = dp(4); fine.addView(row, gap);
        }
        alignScroll.addView(alignPanel);
        alignSheet.addView(alignScroll, new LinearLayout.LayoutParams(-1, -2));
        View rule = new View(this); rule.setBackgroundColor((muted & 0x00ffffff) | 0x40000000);
        alignSheet.addView(rule, new LinearLayout.LayoutParams(-1, dp(1)));
        LinearLayout buttons = new LinearLayout(this); buttons.setOrientation(LinearLayout.HORIZONTAL); buttons.setPadding(dp(16), dp(4), dp(16), dp(12));
        rowButton(buttons, "Cancel", () -> finishAligning(false));
        Button reset = rowButton(buttons, "Reset", () -> { aligning = spotParams(defaultSpot(camId)); aligning[2] += bedZ; for (int i = 0; i < ALIGN_NAMES.length; i++) syncAlignRow(i); pushAlign(); });
        reset.setContentDescription("Reset to the default camera");
        primaryButton(buttons, "Save", this::saveLineUp);
        alignSheet.addView(buttons);
        root.addView(alignSheet, new LinearLayout.LayoutParams(-1, -2));
    }
    /** A part of the panel that opens and closes: its header speaks "expanded" or "collapsed", and a tap toggles it. */
    private LinearLayout section(String title, boolean open) {
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(0, dp(4), 0, 0);
        boolean[] expanded = {open};
        Button header = new Button(this); header.setAllCaps(false); header.setTextSize(14); header.setTextColor(ink);
        header.setTypeface(Typeface.DEFAULT, Typeface.BOLD); header.setGravity(android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.START);
        header.setMinHeight(dp(48)); header.setMinimumHeight(dp(48)); header.setPadding(dp(12), dp(4), dp(12), dp(4));
        GradientDrawable shape = new GradientDrawable(); shape.setColor(buttonColor); shape.setCornerRadius(dp(12));
        header.setBackground(new RippleDrawable(ColorStateList.valueOf(dark ? 0x4463d5c7 : 0x33006b65), shape, null));
        A11y.heading(header);
        Runnable show = () -> {
            header.setText((expanded[0] ? "▼  " : "►  ") + title);
            header.setContentDescription(title);   // the arrow is not spoken; the state is
            A11y.expandable(header, expanded[0]);
            body.setVisibility(expanded[0] ? View.VISIBLE : View.GONE);
        };
        header.setOnClickListener(v -> { expanded[0] = !expanded[0]; show.run(); });
        show.run();
        LinearLayout block = new LinearLayout(this); block.setOrientation(LinearLayout.VERTICAL);
        block.addView(header, new LinearLayout.LayoutParams(-1, -2));
        block.addView(body, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-1, -2); gap.topMargin = dp(8);
        alignPanel.addView(block, gap);
        return body;
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
    private TextView markNote;
    private final java.util.List<Button> edgeButtons = new java.util.ArrayList<>();
    private static final String[] EDGE_NAMES = {"Front", "Back", "Left", "Right"};
    private static final int[] EDGE_COLOURS = {0xffff5252, 0xff40c4ff, 0xff69f0ae, 0xffff4dd2};
    private void buildMarking() {
        label(alignPanel, "1. Pick an edge.\n2. Tap 2–4 points along it, where the bed's top surface ends (drag to pan, pinch to zoom).\n3. Tap Fit.\nDo three or four edges; the far edge and both sides help most.", 12, ink, false);
        LinearLayout edges = new LinearLayout(this); edges.setOrientation(LinearLayout.HORIZONTAL); alignPanel.addView(edges);
        for (int i = 0; i < EDGE_NAMES.length; i++) {
            int edge = i;
            Button b = rowButton(edges, EDGE_NAMES[i], () -> { markEdge = edge; syncEdgeButtons(); });
            edgeButtons.add(b);
        }
        LinearLayout actions = new LinearLayout(this); actions.setOrientation(LinearLayout.HORIZONTAL); alignPanel.addView(actions);
        rowButton(actions, "Undo tap", () -> { if (!marks.isEmpty()) marks.remove(marks.size() - 1); sendMarks(); });
        rowButton(actions, "Clear taps", () -> { marks.clear(); sendMarks(); });
        primaryButton(actions, "Fit", this::fitToMarks);
        markNote = A11y.polite(label(alignPanel, "", 12, ink, false));
        syncEdgeButtons();
    }
    private Button gridButton, alignLockButton;
    // Moving the bed from the line-up, to tap at other bed heights: real printer moves, one per tap, each confirmed.
    private TextView moveNote;
    private boolean moveWarned;
    private String lastMoveFeedback = "";   // the warning was accepted with "don't ask again" for this line-up
    private void buildBedMoves(LinearLayout parent) {
        A11y.heading(label(parent, "Move the bed (moves the real printer)", 13, ink, true));
        LinearLayout first = new LinearLayout(this); first.setOrientation(LinearLayout.HORIZONTAL); parent.addView(first);
        rowButton(first, "Home Z", () -> confirmBedMove(0));
        rowButton(first, "Raise 10 mm", () -> confirmBedMove(-10));
        rowButton(first, "Lower 10 mm", () -> confirmBedMove(10));
        LinearLayout second = new LinearLayout(this); second.setOrientation(LinearLayout.HORIZONTAL); parent.addView(second);
        rowButton(second, "Raise 50 mm", () -> confirmBedMove(-50));
        rowButton(second, "Lower 50 mm", () -> confirmBedMove(50));
        moveNote = A11y.polite(label(parent, "", 12, muted, false));
    }
    /** Why a bed move cannot be sent now, or null. The printer service checks the same again before sending. */
    private String bedMoveBlocked(double distance) {
        if (printer == null) return "Not connected to the printer.";
        if (!printer.ready() && !getSharedPreferences("workshop-settings", MODE_PRIVATE).getBoolean("cloudControlUnderstood", false))
            return "Moving the printer through Elegoo's cloud needs cloud control turned on first (Print > Controls asks once).";
        if (!printer.liveFresh()) return "Waiting for fresh printer status before moving.";
        JSONObject live = printer.liveStatus();
        if (!Cc2Codec.idle(live) || !StatusPresentation.faultCodes(live).isEmpty()) return "The printer must be idle, without faults, to move the bed.";
        if (distance == 0) return null;
        if (!Cc2Codec.homed(live, "z")) return "Home Z first: the printer only moves an axis it has homed.";
        double target = bedZ + distance;
        if (target < 0 || target > 250) return String.format(Locale.getDefault(), "That would take the bed to Z %.0f mm, outside 0–250 mm.", target);
        return null;
    }
    private void confirmBedMove(double distance) {
        String blocked = bedMoveBlocked(distance);
        if (blocked != null) { if (moveNote != null) moveNote.setText(blocked); return; }
        if (moveWarned) { sendBedMove(distance); return; }
        String what = distance == 0 ? "The bed rises until it finds its home position at the top, near the nozzle."
            : distance < 0 ? String.format(Locale.getDefault(), "The bed rises %.0f mm, toward the nozzle, to about Z %.0f mm.", -distance, bedZ + distance)
            : String.format(Locale.getDefault(), "The bed lowers %.0f mm, to about Z %.0f mm.", distance, bedZ + distance);
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(dp(20), dp(8), dp(20), 0);
        label(body, what + "\n\nThis moves the real printer straight away. Before you continue:\n• nothing is printing;\n• nothing loose is on the bed, and nothing is under it or in the way;\n"
            + "• hands are out of the printer.\n\nA raised bed comes close to the nozzle; a part or tool left on it can be crushed. Each tap sends one move, and the app never repeats it.", 14, ink, false);
        android.widget.CheckBox again = new android.widget.CheckBox(this); again.setText("Don't ask again until I leave the line-up"); again.setTextColor(ink);
        body.addView(again);
        new AlertDialog.Builder(this).setTitle(distance == 0 ? "Home the bed (Z)?" : "Move the printer's bed?").setView(body)
            .setNegativeButton("Cancel", null)
            .setPositiveButton(distance == 0 ? "Home Z" : "Move bed", (d, which) -> { if (again.isChecked()) moveWarned = true; sendBedMove(distance); }).show();
    }
    private void sendBedMove(double distance) {
        String blocked = bedMoveBlocked(distance);   // the status may have changed while the warning was open
        if (blocked != null) { if (moveNote != null) moveNote.setText(blocked); return; }
        try {
            printer.maintenance(distance == 0 ? Cc2Codec.homeRequest(0, "z") : Cc2Codec.moveRequest(0, "z", distance));
            bed.moveSent(distance, System.currentTimeMillis());
            // Ask for status a few times (reads only), so the new height is confirmed without waiting for the cloud's slow polling.
            for (long delay : new long[] {3_000, 6_000, 10_000, 20_000, 40_000})
                main.postDelayed(() -> { if (printer != null && aligning != null && bed.state(System.currentTimeMillis()) == BedHeight.State.MOVING) printer.refresh(); }, delay);
            if (bedNote != null) bedNote.setText(bedText());
            if (moveNote != null) moveNote.setText(distance == 0 ? "Home Z sent once. The bed's height updates when the printer reports it." : "Move sent once. The bed's height updates when the printer reports it.");
        } catch (Exception invalid) { if (moveNote != null) moveNote.setText("That move could not be prepared."); }
    }
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
    private void styleSlider(LinearLayout parent, String name, String key, int min, int max, int fallback, String unit) { styleSlider(parent, name, key, min, max, fallback, unit, this::sendAlignStyle); }
    private void styleSlider(LinearLayout parent, String name, String key, int min, int max, int fallback, String unit, Runnable changed) {
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
            @Override public void onProgressChanged(SeekBar b, int value, boolean fromUser) { if (!fromUser) return; viewerPrefs().edit().putInt(key, value + min).apply(); show.run(); changed.run(); }
            @Override public void onStartTrackingTouch(SeekBar b) { }
            @Override public void onStopTrackingTouch(SeekBar b) { }
        });
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-1, -2); gap.topMargin = dp(4); parent.addView(row, gap);
    }
    /** The plate's margins to the fit and the outline; while lining up with saved or current taps, fit again. */
    private void applyPlate() {
        android.content.SharedPreferences p = viewerPrefs();
        CameraFit.setPlate(p.getInt("plateLeft", 0), p.getInt("plateRight", 0), p.getInt("plateFront", 0), p.getInt("plateBack", 0));
    }
    private void plateChanged() {
        applyPlate(); sendAlignStyle();
        main.removeCallbacks(refitForPlate); main.postDelayed(refitForPlate, 400);   // once the slider settles
    }
    private final Runnable refitForPlate = () -> { if (aligning != null && (!marks.isEmpty() || !savedTaps().isEmpty())) fitToMarks(); };
    private void sendAlignStyle() {
        String grid = viewerPrefs().getString("alignGrid", "all");
        if (gridButton != null) gridButton.setText("all".equals(grid) ? "Grid: all lines" : "edges".equals(grid) ? "Grid: bed edges only" : "Grid: hidden");
        if (web == null) return;
        try {
            web.evaluateJavascript("viewer.setAlignStyle(" + new JSONObject().put("grid", grid).put("alpha", viewerPrefs().getInt("alignAlpha", 90) / 100.0)
                .put("dot", viewerPrefs().getInt("alignDot", 12))
                .put("plate", new JSONArray().put(viewerPrefs().getInt("plateLeft", 0)).put(viewerPrefs().getInt("plateRight", 0)).put(viewerPrefs().getInt("plateFront", 0)).put(viewerPrefs().getInt("plateBack", 0))) + ")", null);
        } catch (org.json.JSONException ignored) { }
    }
    private void syncEdgeButtons() {
        for (int i = 0; i < edgeButtons.size(); i++) {
            Button b = edgeButtons.get(i); boolean on = i == markEdge;
            b.setText(EDGE_NAMES[i]);
            styleEdge(b, EDGE_COLOURS[i], on);
            A11y.state(b, on ? "Selected" : "Not selected");
            b.setContentDescription(EDGE_NAMES[i] + " edge of the bed");
        }
    }
    /** The selected edge: a fill in its colour (faint), and bold text. */
    private void styleEdge(Button b, int colour, boolean on) {
        GradientDrawable shape = new GradientDrawable(); shape.setColor(on ? (colour & 0x00ffffff) | 0x3d000000 : buttonColor); shape.setCornerRadius(dp(12));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(dark ? 0x4463d5c7 : 0x33006b65), shape, null));
        b.setTypeface(Typeface.DEFAULT, on ? Typeface.BOLD : Typeface.NORMAL);
        b.setTextColor(on ? ink : muted);
    }
    /** The main action of a row: a filled teal button, dark text in the dark theme for contrast. */
    private Button primaryButton(LinearLayout row, String text, Runnable action) {
        Button b = rowButton(row, text, action);
        GradientDrawable shape = new GradientDrawable(); shape.setColor(teal); shape.setCornerRadius(dp(12));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(dark ? 0x66000000 : 0x55ffffff), shape, null));
        b.setTextColor(new ColorStateList(new int[][] {new int[] {-android.R.attr.state_enabled}, new int[] {}}, new int[] {muted, dark ? 0xff0e1417 : Color.WHITE}));
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return b;
    }
    private void setMarking(boolean on) {
        marking = on;
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
            text.append(free == 0 ? ". Tap at least 3 points to fit." : free < CameraFit.COUNT ? ". Fit will adjust some settings; 8 taps on 3 edges adjust them all." : ". Ready: Fit will adjust every setting.");
            markNote.setText(text);
        }
    }
    /**
     * Taps saved at other bed heights (one set per height), so a fit can use them all: the camera stays on the frame while
     * the bed moves, so they describe one camera whose height above the bed changes by exactly the bed's Z.
     */
    private java.util.List<CameraFit.TapSet> savedTaps() { return parseTaps(viewerPrefs().getString(ck("cameraTaps"), null)); }
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
        if (!marks.isEmpty() && !heightReady()) { if (markNote != null) markNote.setText("Not fitted. " + bed.describe(System.currentTimeMillis())); return; }
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
        String now = bed.describe(System.currentTimeMillis()) + " ";
        java.util.List<CameraFit.TapSet> taps = viewerPrefs().getInt(ck("cameraSpot"), defaultSpot(camId)) == LINED_UP ? savedTaps() : java.util.Collections.emptyList();
        if (!taps.isEmpty()) {
            StringBuilder heights = new StringBuilder();
            for (CameraFit.TapSet set : taps) heights.append(heights.length() > 0 ? ", " : "").append(String.format(Locale.getDefault(), "%.0f", set.z));
            return now + "Taps saved at bed Z " + heights + " mm. Fit uses them all, with any taps made now, as one camera. "
                + "Tapping at heights far apart (Z 5, 100, 200) pins it down best.";
        }
        StringBuilder heights = new StringBuilder();
        if (viewerPrefs().getInt(ck("cameraSpot"), defaultSpot(camId)) == LINED_UP) for (double[] point : savedPoints()) heights.append(heights.length() > 0 ? ", " : "").append(String.format(Locale.getDefault(), "%.1f", point[0]));
        return now + (heights.length() == 0 ? "No line-ups saved yet." : "Line-ups saved at bed Z " + heights + " mm.")
            + " Line up from taps and Save at two or more bed heights (use Move the bed below): the taps from all of them are fitted as one camera.";
    }
    private void forgetLineUps() {
        viewerPrefs().edit().remove(ck("cameraPoints")).remove(ck("cameraTaps")).remove(ck("cameraCustomBed0")).putInt(ck("cameraSpot"), defaultSpot(camId)).apply();
        if (aligning != null) { aligning = spotParams(defaultSpot(camId)); aligning[2] += bedZ; for (int i = 0; i < ALIGN_NAMES.length; i++) syncAlignRow(i); pushAlign(); }
    }
    /**
     * Save, if the bed's height is known. Taps made now are only kept at a confirmed height; a line-up made with the sliders
     * alone can be saved at an unconfirmed height after a warning (it may then sit off at other bed heights).
     */
    private void saveLineUp() {
        if (aligning == null) return;
        long now = System.currentTimeMillis();
        if (heightReady()) { finishAligning(true); return; }
        if (!marks.isEmpty()) { if (markNote != null) markNote.setText("Not saved. " + bed.describe(now)); return; }
        new AlertDialog.Builder(this).setTitle("Save at an unconfirmed bed height?").setMessage(bed.describe(now)
                + "\n\nThe camera will be right at this bed position, but may sit off when the bed moves. Homing Z first and saving again fixes that.")
            .setNegativeButton("Cancel", null).setPositiveButton("Save anyway", (d, w) -> finishAligning(true)).show();
    }
    private void finishAligning(boolean save) {
        if (save && aligning != null) {
            boolean confirmed = heightReady();
            java.util.List<CameraFit.TapSet> sets = confirmed ? withTaps(savedTaps(), currentTaps()) : savedTaps();
            // With taps (from any height), the camera is one camera on the frame: saved once, it follows the bed exactly.
            // Without, as before: a line-up per bed height, fitted with a line.
            java.util.List<double[]> points = !sets.isEmpty() ? addPoint(new java.util.ArrayList<>(), alignZ, aligning)
                : addPoint(viewerPrefs().getInt(ck("cameraSpot"), defaultSpot(camId)) == LINED_UP ? savedPoints() : new java.util.ArrayList<>(), alignZ, aligning);
            viewerPrefs().edit().putString(ck("cameraPoints"), pointsJson(points)).putString(ck("cameraTaps"), tapsJson(sets)).remove(ck("cameraCustomBed0")).putInt(ck("cameraSpot"), LINED_UP).apply();
            Diagnostics.note(Diagnostics.FOLLOW, String.format(Locale.ROOT, "bed height for this line-up: %s, Z %s, status %d s old",
                bed.state(System.currentTimeMillis()), bed.homed() ? "homed" : "not homed", Math.max(-1, bed.age(System.currentTimeMillis()) / 1000)));
            Diagnostics.note(Diagnostics.FOLLOW, String.format(Locale.ROOT, "camera lined up by hand at bed Z %.2f; all line-ups {bed Z, x, y, height, turn, tilt, view, lens}: %s", alignZ, pointsJson(points)));
            status.setText("Camera position saved. More… > Look from the camera shows the picture with the toolpath over it.");
        }
        aligning = null; moveWarned = false;
        marks.clear(); if (marking) setMarking(false); else sendMarks();
        sendLock();
        double[] shown = currentParams();
        if (android.os.Build.VERSION.SDK_INT >= 33 && alignBack != null) { getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback((android.window.OnBackInvokedCallback) alignBack); alignBack = null; }
        if (alignSheet != null) alignSheet.setVisibility(View.GONE);
        mainPanel.setVisibility(View.VISIBLE); arrange();
        try { if (web != null && shown != null) web.evaluateJavascript("viewer.showPrinterCamera(" + livePose(shown) + ")", null); } catch (Exception ignored) { }
        if (alignOnly) finish();
    }

    private final class Bridge {
        @JavascriptInterface public void onReady() { main.post(() -> { pageReady = true; sendTheme(); sendData(); sendLock(); sendAlignStyle(); if (alignOnly) startAligning(); }); }
        @JavascriptInterface public void onLoaded(int count) { main.post(() -> { loadedOnce = true; setControlsEnabled(true); sendLockstep(); restoreView(); pushView(); syncOverlay(); if (following) changed(); startCamera(); if (aligning != null) pushAlign(); }); }
        @JavascriptInterface public void onMark(double u, double v, double aspect) { main.post(() -> {
            if (!marking || aligning == null) return;
            // Taps are tied to the bed's height: none while it is unknown or moving.
            if (!heightReady()) { if (markNote != null) markNote.setText("Tap not added. " + bed.describe(System.currentTimeMillis())); return; }
            markAspect = aspect > 0.2 && aspect < 5 ? aspect : markAspect;
            marks.add(new CameraFit.Mark(markEdge, u, v)); sendMarks();
        }); }
        @JavascriptInterface public void onCamera(String message) { main.post(() -> {
            if ("playing".equals(message)) cameraMessage("Camera: live video through Elegoo's cloud.");
            else cameraMessage(CloudCameraActivity.explain(message));
        }); }
        @JavascriptInterface public void onCameraDelay(int ms) { main.post(() -> {
            measuredDelay = Math.max(0, Math.min(30_000, ms));
            if (delayNote != null) delayNote.setText(delayText());
            sendLockstep();
        }); }
        @JavascriptInterface public void onError(String message) { main.post(() -> status.setText(message)); }
    }

    // ------------------------------------------------------------------ loading
    // ------------------------------------------------------------------ details over the picture
    private LinearLayout details;
    /** The G-code, print position, camera and filament values. */
    private final TextView[] detailValues = new TextView[4];
    private Button detailsButton;
    private GcodeToolpath.LineCursor cursor;
    private int shownSegment = -1, detailedSegment = -2;
    /** When the local camera's newest picture reached the phone (wall clock), 0 before any. */
    private volatile long frameAt;
    /** Fills the details for the segment drawn last: its G-code line (read off the main thread), the file's time, camera and filament. */
    private void showDetails(int segment, boolean force) {
        shownSegment = segment;
        boolean on = viewerPrefs().getBoolean("details", true) && path != null;
        details.setVisibility(on ? View.VISIBLE : View.GONE);
        if (!on || (!force && segment == detailedSegment)) return;
        detailedSegment = segment;
        GcodeToolpath read = path; GcodeToolpath.LineCursor lines = cursor;
        int line = read.lineOf(segment), tool = read.toolOf(segment); float[] time = read.timeAt(segment);
        String filament = read.filamentText(tool), camera = cameraText();
        String hex = read.filamentColour(tool);
        String timeText = time == null ? "time not in the file" : clock(time[0]) + " of " + clock(time[1]);
        worker.execute(() -> {
            String code = lines == null || line <= 0 ? "not known" : lines.line(line).trim();
            String where = timeText + (line > 0 ? String.format(Locale.getDefault(), " · line %,d", line) : "");
            android.text.SpannableStringBuilder material = new android.text.SpannableStringBuilder();
            if (hex != null) material.append("● ", new android.text.style.ForegroundColorSpan(Color.parseColor(hex)), 0);
            material.append(filament.replaceFirst("^Filament ", ""));
            main.post(() -> {
                if (isDestroyed() || detailedSegment != segment) return;
                detailValues[0].setText(code); detailValues[1].setText(where); detailValues[2].setText(camera); detailValues[3].setText(material);
                details.setContentDescription("G-code " + code + ". Print at " + where + ". Camera " + camera + ". Filament " + material + ".");
            });
        });
    }
    /** The details as one text (for tests and reading aloud). */
    String detailsText() { StringBuilder out = new StringBuilder(); for (TextView v : detailValues) out.append(out.length() > 0 ? "\n" : "").append(v.getText()); return out.toString(); }
    /** Minutes as "1:47" (hours and minutes). */
    static String clock(float m) { int total = Math.round(m); return String.format(Locale.ROOT, "%d:%02d", total / 60, total % 60); }
    /** What the picture lags by: measured from the cloud video's own frame times, else the delay set by hand, else not known. */
    private String cameraText() {
        long delay = effectiveDelay();
        if (delay <= 0 && cameraStarted && !cameraCloud && frameAt > 0)
            return new java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new java.util.Date(frameAt)) + " · arrival (stream has no time)";
        if (delay <= 0) return "delay not known";
        String when = new java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new java.util.Date(System.currentTimeMillis() - delay));
        boolean measured = viewerPrefs().getBoolean(ck("autoDelay"), true) && measuredDelay > 0;
        return String.format(Locale.getDefault(), "%s · %.1f s behind · %s", when, delay / 1000.0, measured ? "measured" : "manual");
    }
    private void load(File file, String name) {
        if (name.equals(loadingName)) return;
        loadingName = name; title.setText(StatusPresentation.clean(name.replaceFirst("(?i)\\.gcode$", "")));
        status.setText("Reading toolpath…"); missingCard.setVisibility(View.GONE); if (hint != null) hint.setVisibility(View.VISIBLE); setControlsEnabled(false);
        worker.execute(() -> {
            try {
                GcodeToolpath read = GcodeToolpath.read(file);
                byte[] s = read.segmentsBinary(), t = read.travelsBinary(), m = read.layersJson().getBytes("UTF-8");
                main.post(() -> {
                    if (isDestroyed()) return;
                    synchronized (this) { path = read; segments = s; travels = t; meta = m; }
                    GcodeToolpath.LineCursor old = cursor; cursor = new GcodeToolpath.LineCursor(file); detailedSegment = -2; if (old != null) worker.execute(old::close);
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
        layerLabel.setText(String.format(Locale.getDefault(), "%sLayer %d of %d · %.2f mm high", following ? "● Live · " : "", layer + 1, path.layerCount, path.layerZ(layer)));
        moveLabel.setText(String.format(Locale.getDefault(), "Lines drawn in this layer: %,d of %,d", move, layerSize(layer)));
        A11y.state(layerBar, String.format(Locale.getDefault(), "Layer %d of %d", layer + 1, path.layerCount));
        A11y.state(moveBar, String.format(Locale.getDefault(), "%,d of %,d lines", move, layerSize(layer)));
    }

    /** Sends the current range, nozzle and filters to the page. `nozzle` is null outside follow mode. */
    private void pushView() { pushView(null); }
    /** Live following: how long the next update should take to glide to its position (0 = jump), and when the last step came. */
    private int smoothNext; private long lastStepAt;
    /** While the printhead size dialog is open: the mask is shown tinted so it can be matched to the real head. */
    private boolean headPreview; private double[] previewNozzle;
    private void pushView(double[] nozzle) {
        if (path == null || web == null || !pageReady) return;
        try {
            int start = path.layerStart(layer), end = start + Math.min(move, layerSize(layer)), layerEnd = path.layerEnd(layer);
            boolean partial = following || end < layerEnd;
            boolean only = viewerPrefs().getBoolean("layerOnly", false);   // just this layer, nothing printed below it
            if (followMode && (viewerPrefs().getInt("liveHidden", 0) != hidden || viewerPrefs().getBoolean("liveTravel", false) != showTravel))
                viewerPrefs().edit().putInt("liveHidden", hidden).putBoolean("liveTravel", showTravel).apply();
            JSONObject state = new JSONObject().put("start", only ? start : 0).put("end", end).put("ghostEnd", layerEnd)
                .put("dimBelow", partial && !only ? start : 0).put("hidden", hidden).put("showTravel", showTravel)
                .put("travelStart", only ? path.layerTravelStart[layer] : 0).put("travelEnd", layer + 1 < path.layerCount ? path.layerTravelStart[layer + 1] : path.travelCount)
                .put("nozzleSize", viewerPrefs().getInt("nozzleSize", 16)).put("nozzleAlpha", viewerPrefs().getInt("nozzleAlpha", 100) / 100.0)
                .put("nozzleFlat", viewerPrefs().getBoolean("nozzleFlat", false))
                .put("head", followMode && (!"off".equals(headLook()) || headPreview)).put("headShow", headPreview).put("headLook", headLook())
                .put("headSize", new JSONObject().put("w", viewerPrefs().getInt("headW", 70)).put("d", viewerPrefs().getInt("headD", 80))
                    .put("h", viewerPrefs().getInt("headH", 80)).put("block", viewerPrefs().getInt("headBlock", 24)).put("offset", viewerPrefs().getInt("headOffset", 0))
                    .put("blockD", viewerPrefs().getInt("headBlockD", viewerPrefs().getInt("headBlock", 24))).put("blockH", viewerPrefs().getInt("headBlockH", 12))
                    .put("tip", viewerPrefs().getInt("headTip", 1)).put("side", viewerPrefs().getInt("headSide", 0))
                    .put("shroud", new JSONObject().put("on", viewerPrefs().getBoolean("shroudOn", false)).put("w", viewerPrefs().getInt("shroudW", 40))
                        .put("d", viewerPrefs().getInt("shroudD", 40)).put("z", viewerPrefs().getInt("shroudZ", 3)))
                    .put("fan", new JSONObject().put("on", viewerPrefs().getBoolean("fanOn", false)).put("x", viewerPrefs().getInt("fanX", -45)).put("y", viewerPrefs().getInt("fanY", 0))
                        .put("z", viewerPrefs().getInt("fanZ", 10)).put("w", viewerPrefs().getInt("fanW", 20)).put("d", viewerPrefs().getInt("fanD", 50)).put("h", viewerPrefs().getInt("fanH", 50))
                        .put("mirror", viewerPrefs().getBoolean("fanMirror", false))))
                .put("layerStart", start).put("alpha", viewerPrefs().getInt("layerOpacity", 100) / 100.0).put("belowAlpha", viewerPrefs().getInt("belowOpacity", 100) / 100.0)
                .put("supportAlpha", viewerPrefs().getInt("supportOpacity", 35) / 100.0)
                .put("typeAlpha", new JSONArray(typeAlpha(viewerPrefs()))).put("edgeMask", edgeMask(viewerPrefs()));
            if (smoothNext > 0 && viewerPrefs().getBoolean("smoothLive", true)) state.put("smoothMs", smoothNext);
            if (liveAt > 0) { state.put("at", liveAt); liveAt = 0; }
            smoothNext = 0;
            if (nozzle == null && headPreview && previewNozzle != null) nozzle = previewNozzle;
            if (nozzle != null) state.put("nozzle", new JSONArray(nozzle));
            else if (end > 0 && end < layerEnd) state.put("nozzle", new JSONArray(new double[] {path.x1[end - 1], path.y1[end - 1], path.z1[end - 1]}));
            else state.put("nozzle", JSONObject.NULL);
            web.evaluateJavascript("viewer.update(" + state + ")", null);
            showDetails(Math.max(0, end - 1), false);
        } catch (Exception ignored) { }
    }

    private void setPlaying(boolean on) {
        playing = on && path != null; if (play != null) play.setText(playing ? "Pause" : "Play");
        main.removeCallbacks(player);
        if (playing) { if (layer >= path.layerCount - 1 && move >= layerSize(layer)) { layer = 0; move = 0; } main.post(player); }
    }

    /** The cloud camera's delay as the page measured it (ms, 0 = not measured), and when the next live update arrived (lockstep). */
    private int measuredDelay; private long liveAt;
    private TextView delayNote;
    /** How long the drawing waits for the camera: the measured cloud delay when that is chosen and known, otherwise the slider. */
    private long effectiveDelay() { return viewerPrefs().getBoolean(ck("autoDelay"), true) && measuredDelay > 0 ? measuredDelay : viewerPrefs().getInt(ck("liveDelay"), 0) * 100L; }
    /** Lockstep: the page keeps the live positions with their times and draws each camera picture's moment itself. */
    private String delayText() {
        boolean auto = viewerPrefs().getBoolean(ck("autoDelay"), true);
        return measuredDelay > 0 ? String.format(Locale.getDefault(), "Cloud camera delay measured now: %.1f s%s", measuredDelay / 1000.0, auto ? " (used instead of the slider)." : " (not used; the slider is).")
            : "Measured only for the cloud camera, once its video plays; until then the slider is used.";
    }
    /** The live preview's saved view, given back once per opening (later files keep whatever view is on screen). */
    private boolean viewRestored;
    private void restoreView() {
        if (!followMode || viewRestored) return; viewRestored = true;
        String saved = viewerPrefs().getString("liveView", null);
        try { if (saved != null) js("viewer.setViewState(" + new JSONObject(saved) + ")"); } catch (org.json.JSONException ignored) { }
    }
    /** Through the cloud, the print's recording takes its camera pictures from the cloud video shown here (locally the service takes them). */
    private boolean picturing;
    private void takeCloudPicture() {
        if (picturing || printer == null || printer.recorder == null || printer.ready() || web == null || !pageReady || addedCamera != null || ExtraCameras.forPictures(this) != null) return;
        long mono = android.os.SystemClock.elapsedRealtime();
        if (!printer.recorder.pictureDue(mono)) return;
        picturing = true;
        web.evaluateJavascript("viewer.snapshot()", value -> {
            picturing = false;
            try {
                Object text = new org.json.JSONTokener(value).nextValue();
                String url = text instanceof String ? (String) text : "";
                if (url.startsWith("data:image/jpeg;base64,") && printer != null)
                    printer.keepPicture(android.util.Base64.decode(url.substring(23), android.util.Base64.DEFAULT), mono, PrintRecorder.PRINTER_CAMERA, "Live toolpath's cloud video");
            } catch (Exception ignored) { }
        });
    }
    /** Locally the printer gives one camera stream at a time: while it plays here, the print's recording takes its pictures from it. */
    private void keepLocalPicture() {
        if (printer == null || printer.recorder == null || cameraFrames == null) return;
        // Only the camera chosen for print pictures (the printer's own unless an added one is), so a timelapse keeps one view.
        ExtraCameras.Camera pictures = ExtraCameras.forPictures(this), added = addedCamera;
        if (added != null ? pictures == null || !pictures.id.equals(added.id) : pictures != null) return;
        long mono = android.os.SystemClock.elapsedRealtime();
        if (printer.recorder.pictureDue(mono)) printer.keepPicture(turned(cameraFrames.latest()), mono, added != null ? "added:" + added.id : PrintRecorder.PRINTER_CAMERA, added != null ? "the added camera “" + added.name + "”" : "Live toolpath's camera stream");
    }
    private boolean lockstep() { return followMode && following && viewerPrefs().getBoolean("lockstep", false); }
    private void sendLockstep() { js("viewer.setLockstep({on:" + lockstep() + ",delayMs:" + effectiveDelay() + "})"); }
    /** Groups the delayed live updates, so leaving live view drops the ones still waiting. */
    private final Object delayToken = new Object();
    private void stopFollowing() {
        main.removeCallbacksAndMessages(delayToken);
        if (following) {
            following = false; if (follow != null) follow.setEnabled(true); sendLockstep(); syncOverlay();
            status.setText("Not live now: you are viewing the file. Tap “Back to live” to jump to the printer's position.");
        }
    }

    /** Layer 1 with everything printed, seen from above: the view for checking the first layer before a print. */
    private void showFirstLayer() {
        if (path == null) return;
        stopFollowing(); setPlaying(false);
        layer = 0; move = layerSize(0); syncBars(); pushView();
        if (web != null && pageReady) web.evaluateJavascript("viewer.setView('top')", null);
        status.setText("First layer, from above. It should be solid: even lines touching edge to edge, with no gaps. Drag the Layer bar up to see higher layers.");
    }

    private int hintsShown() {
        android.content.SharedPreferences prefs = getSharedPreferences("viewer-hints", MODE_PRIVATE);
        int n = prefs.getInt("shown", 0); prefs.edit().putInt("shown", n + 1).apply(); return n;
    }

    /** The less used controls, kept out of the panel so the 3D view stays large. */
    /** The More… list in three groups (what to show, the camera, view and help), most used first; headings are text, not buttons. */
    private void moreDialog() {
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(dp(20), dp(4), dp(20), dp(8));
        ScrollView scroll = new ScrollView(this); scroll.addView(body);
        AlertDialog[] dialog = new AlertDialog[1];
        groupHeading(body, "What to show");
        moreButton(body, dialog, "Show or hide line types (walls, infill, support)…", this::featureDialog);
        moreButton(body, dialog, showTravel ? "Hide travel moves (blue)" : "Show travel moves (blue)", () -> { showTravel = !showTravel; pushView(); });
        moreButton(body, dialog, moveBar.getVisibility() == View.VISIBLE ? "Hide the step-through bar" : "Step through this layer…",
            () -> { int v = moveBar.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE; moveBar.setVisibility(v); moveLabel.setVisibility(v); });
        moreButton(body, dialog, "Layers, transparency and nozzle dot…", this::nozzleDialog);
        if (followMode) {
            boolean on = cameraWanted();
            groupHeading(body, "Camera");
            List<ExtraCameras.Camera> cameras = ExtraCameras.list(this);
            if (!cameras.isEmpty()) moreButton(body, dialog, "Camera: " + (addedOrNull() == null ? "the printer's" : addedOrNull().name) + "…", this::chooseCamera);
            moreButton(body, dialog, on ? "Hide the camera" : "Show the camera in the view", this::toggleCamera);
            if (on) {
                moreButton(body, dialog, "Camera position: " + CAMERA_SPOTS[Math.max(0, Math.min(CAMERA_SPOTS.length - 1, viewerPrefs().getInt(ck("cameraSpot"), defaultSpot(camId))))] + "…", this::cameraSpotDialog);
                moreButton(body, dialog, "Look from the camera", () -> { if (web != null) web.evaluateJavascript("viewer.setView('printer')", null); });
                moreButton(body, dialog, "Camera overlay: grid and strength…", this::overlayDialog);
                moreButton(body, dialog, "Line up the camera by hand…", this::startAligning);
            }
        }
        groupHeading(body, "View and help");
        boolean locked = viewerPrefs().getBoolean("viewLock", false);
        moreButton(body, dialog, locked ? "Unlock rotation (drag turns the view)" : "Lock rotation (drag moves the view)",
            () -> { viewerPrefs().edit().putBoolean("viewLock", !locked).apply(); sendLock(); });
        moreButton(body, dialog, "What am I seeing?", this::helpDialog);
        dialog[0] = new AlertDialog.Builder(this).setTitle("More options").setView(scroll).setNegativeButton("Close", null).show();
    }
    /** A small heading that groups the buttons after it. */
    private void groupHeading(LinearLayout parent, String text) {
        TextView view = A11y.heading(label(parent, text, 12, muted, true));
        ((LinearLayout.LayoutParams) view.getLayoutParams()).topMargin = dp(10);
    }
    /** A full-width button in a dialog's list: closes the dialog, then runs the action; off until a file is loaded. */
    private void moreButton(LinearLayout body, AlertDialog[] dialog, String text, Runnable action) {
        Button b = rowButton(body, text, () -> { dialog[0].dismiss(); action.run(); });
        ((LinearLayout.LayoutParams) b.getLayoutParams()).width = -1; ((LinearLayout.LayoutParams) b.getLayoutParams()).weight = 0; ((LinearLayout.LayoutParams) b.getLayoutParams()).leftMargin = 0;
        b.setEnabled(path != null);
    }

    private final AlertDialog[] dialogs = new AlertDialog[1];
    /** How the printhead shows: off, mask, outline or tint (earlier versions saved only whether the mask was on). */
    private String headLook() { return viewerPrefs().getString("headLook", viewerPrefs().getBoolean("headMask", false) ? "mask" : "off"); }
    /**
     * Sizing the printhead mask against the camera picture: while this is open the mask is drawn tinted over the picture (from
     * the camera), so its width, depth, height and offset can be matched to the real head, best with the head parked.
     */
    private void headSizeDialog() {
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(dp(20), dp(8), dp(20), dp(8));
        TextView note = new TextView(this); note.setTextSize(13); note.setTextColor(ink);
        note.setText("The mask shows tinted over the camera picture while this is open. Move the sliders until it covers the real printhead. Easiest with the head still (between prints).");
        body.addView(note);
        groupHeading(body, "Body");
        styleSlider(body, "Width (left – right)", "headW", 20, 160, 70, " mm", this::pushView);
        styleSlider(body, "Depth (front – back)", "headD", 20, 160, 80, " mm", this::pushView);
        styleSlider(body, "Height", "headH", 20, 160, 80, " mm", this::pushView);
        styleSlider(body, "Forward of the nozzle", "headOffset", -60, 60, 0, " mm", this::pushView);
        styleSlider(body, "Beside the nozzle (right +)", "headSide", -60, 60, 0, " mm", this::pushView);
        groupHeading(body, "Heater block and nozzle");
        styleSlider(body, "Heater block: width", "headBlock", 6, 60, 24, " mm", this::pushView);
        styleSlider(body, "Heater block: depth", "headBlockD", 6, 60, viewerPrefs().getInt("headBlock", 24), " mm", this::pushView);
        styleSlider(body, "Heater block: height", "headBlockH", 2, 60, 12, " mm", this::pushView);
        styleSlider(body, "Nozzle tip below the block", "headTip", 0, 15, 1, " mm", this::pushView);
        // A shroud or duct around the nozzle, from just above the tip up to the body.
        android.widget.CheckBox shroud = new android.widget.CheckBox(this); shroud.setTextColor(ink); shroud.setMinHeight(dp(48));
        shroud.setText("Shroud around the nozzle (up to the body)"); shroud.setChecked(viewerPrefs().getBoolean("shroudOn", false));
        LinearLayout shroudBody = new LinearLayout(this); shroudBody.setOrientation(LinearLayout.VERTICAL); shroudBody.setVisibility(shroud.isChecked() ? View.VISIBLE : View.GONE);
        shroud.setOnCheckedChangeListener((v, on) -> { viewerPrefs().edit().putBoolean("shroudOn", on).apply(); shroudBody.setVisibility(on ? View.VISIBLE : View.GONE); pushView(); });
        body.addView(shroud); body.addView(shroudBody);
        styleSlider(shroudBody, "Shroud: width", "shroudW", 10, 120, 40, " mm", this::pushView);
        styleSlider(shroudBody, "Shroud: depth", "shroudD", 10, 120, 40, " mm", this::pushView);
        styleSlider(shroudBody, "Shroud: bottom above the tip", "shroudZ", 0, 40, 3, " mm", this::pushView);
        groupHeading(body, "Fans");
        // The part-cooling fan on the head's side: its own block, placed by its centre and bottom.
        android.widget.CheckBox fan = new android.widget.CheckBox(this); fan.setTextColor(ink); fan.setMinHeight(dp(48));
        fan.setText("Part-cooling fan (a block on the head's side)"); fan.setChecked(viewerPrefs().getBoolean("fanOn", false));
        LinearLayout fanBody = new LinearLayout(this); fanBody.setOrientation(LinearLayout.VERTICAL); fanBody.setVisibility(fan.isChecked() ? View.VISIBLE : View.GONE);
        fan.setOnCheckedChangeListener((v, on) -> { viewerPrefs().edit().putBoolean("fanOn", on).apply(); fanBody.setVisibility(on ? View.VISIBLE : View.GONE); pushView(); });
        body.addView(fan); body.addView(fanBody);
        styleSlider(fanBody, "Fan: left – right", "fanX", -120, 120, -45, " mm", this::pushView);
        styleSlider(fanBody, "Fan: front – back", "fanY", -120, 120, 0, " mm", this::pushView);
        styleSlider(fanBody, "Fan: bottom above the nozzle", "fanZ", 0, 120, 10, " mm", this::pushView);
        styleSlider(fanBody, "Fan: thickness", "fanW", 5, 80, 20, " mm", this::pushView);
        styleSlider(fanBody, "Fan: depth", "fanD", 10, 120, 50, " mm", this::pushView);
        styleSlider(fanBody, "Fan: height", "fanH", 10, 120, 50, " mm", this::pushView);
        android.widget.CheckBox mirror = new android.widget.CheckBox(this); mirror.setTextColor(ink); mirror.setMinHeight(dp(48));
        mirror.setText("A second fan on the other side (mirrored)"); mirror.setChecked(viewerPrefs().getBoolean("fanMirror", false));
        mirror.setOnCheckedChangeListener((v, on) -> { viewerPrefs().edit().putBoolean("fanMirror", on).apply(); pushView(); });
        fanBody.addView(mirror);
        ScrollView scroll = new ScrollView(this); scroll.addView(body);
        headPreview = true; if ("off".equals(headLook())) viewerPrefs().edit().putString("headLook", "mask").apply();
        // Between prints there is no live nozzle on the toolpath: use where the printer reports the head (parked).
        previewNozzle = null;
        JSONObject position = printer == null ? null : Cc2Codec.position(printer.liveStatus());
        if (position != null && position.has("x") && position.has("y")) previewNozzle = new double[] {position.optDouble("x"), position.optDouble("y"), Math.max(0, position.optDouble("z", 0))};
        else if (path == null || printer == null) note.append("\n\nThe printer has not reported where the head is, so the mask cannot be placed yet.");
        if (web != null && pageReady) web.evaluateJavascript("viewer.setView('printer')", null);
        pushView();
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Printhead size").setView(scroll).setPositiveButton("Done", null)
            .setNeutralButton("Defaults", null).create();
        dialog.setOnDismissListener(d -> { headPreview = false; pushView(); });
        // Kept low on the screen, so the camera picture above stays visible while adjusting.
        if (dialog.getWindow() != null) { dialog.getWindow().setGravity(android.view.Gravity.BOTTOM); dialog.getWindow().setDimAmount(0f); }
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            viewerPrefs().edit().remove("headW").remove("headD").remove("headH").remove("headBlock").remove("headOffset")
                .remove("headSide").remove("headBlockD").remove("headBlockH").remove("headTip").remove("shroudW").remove("shroudD").remove("shroudZ")
                .remove("fanX").remove("fanY").remove("fanZ").remove("fanW").remove("fanD").remove("fanH").apply();
            dialog.dismiss(); headSizeDialog();
        }));
        dialog.show();
    }
    /** Line types in groups for the opacity and edge settings: {key, name, feature indices (GcodeToolpath.FEATURES)}. Supports use "supportOpacity". */
    static final String[][] LINE_GROUPS = {{"walls", "Walls", "0,1,2"}, {"infill", "Infill and surfaces", "3,4,5,6,7,8,15"},
        {"supports", "Supports", "11,12,13"}, {"other", "Skirt, brim, tower, other", "9,10,14,16,17"}};
    /** Opacity per feature type from the groups' settings (supports stay 1 here: the page applies supportOpacity to them). */
    static double[] typeAlpha(android.content.SharedPreferences prefs) {
        double[] out = new double[GcodeToolpath.FEATURES.length]; java.util.Arrays.fill(out, 1);
        for (String[] group : LINE_GROUPS) {
            if (group[0].equals("supports")) continue;
            double a = Math.max(5, Math.min(100, prefs.getInt("opacity_" + group[0], 100))) / 100.0;
            for (String i : group[2].split(",")) out[Integer.parseInt(i)] = a;
        }
        return out;
    }
    /** Feature types drawn as edges only, as a bit mask. */
    static int edgeMask(android.content.SharedPreferences prefs) {
        int mask = 0;
        for (String[] group : LINE_GROUPS) if (prefs.getBoolean("edges_" + group[0], false)) for (String i : group[2].split(",")) mask |= 1 << Integer.parseInt(i);
        return mask;
    }
    /** How the nozzle dot looks (size, transparency, flat on the bed or a fixed screen dot), and whether only this layer shows. */
    private void nozzleDialog() {
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(dp(20), dp(8), dp(20), dp(8));
        ScrollView scroll = new ScrollView(this); scroll.addView(body);
        groupHeading(body, "Layers");
        styleSlider(body, "This layer: opacity", "layerOpacity", 5, 100, 100, "%", this::pushView);
        styleSlider(body, "Layers below: opacity", "belowOpacity", 5, 100, 100, "%", this::pushView);
        groupHeading(body, "Line types");
        label(body, "How see-through each kind of line is. Edges only draws a line's outline, so what is inside or behind it shows.", 12, muted, false);
        for (String[] group : LINE_GROUPS) {
            boolean supports = group[0].equals("supports");
            styleSlider(body, group[1] + ": opacity", supports ? "supportOpacity" : "opacity_" + group[0], 5, 100, supports ? 35 : 100, "%", this::pushView);
            android.widget.CheckBox edges = new android.widget.CheckBox(this); edges.setTextColor(ink); edges.setMinHeight(dp(48));
            edges.setText(group[1] + ": edges only"); edges.setChecked(viewerPrefs().getBoolean("edges_" + group[0], false));
            edges.setOnCheckedChangeListener((v, on) -> { viewerPrefs().edit().putBoolean("edges_" + group[0], on).apply(); pushView(); });
            body.addView(edges);
        }
        android.widget.CheckBox only = new android.widget.CheckBox(this); only.setTextColor(ink); only.setMinHeight(dp(48));
        only.setText("Show only the current layer");
        only.setChecked(viewerPrefs().getBoolean("layerOnly", false));
        only.setOnCheckedChangeListener((v, on) -> { viewerPrefs().edit().putBoolean("layerOnly", on).apply(); pushView(); });
        body.addView(only);
        label(body, "Hides the layers below the one you are looking at.", 12, muted, false);
        groupHeading(body, "Nozzle dot");
        styleSlider(body, "Dot size", "nozzleSize", 4, 48, 16, " px", this::pushView);
        styleSlider(body, "Dot opacity", "nozzleAlpha", 10, 100, 100, "%", this::pushView);
        android.widget.CheckBox flat = new android.widget.CheckBox(this); flat.setTextColor(ink); flat.setMinHeight(dp(48));
        flat.setText("Lie flat on the bed");
        flat.setChecked(viewerPrefs().getBoolean("nozzleFlat", false));
        flat.setOnCheckedChangeListener((v, on) -> { viewerPrefs().edit().putBoolean("nozzleFlat", on).apply(); pushView(); });
        body.addView(flat);
        label(body, "Follows the grid's perspective and the lens curve.", 12, muted, false);
        groupHeading(body, "Printhead");
        android.widget.RadioGroup looks = new android.widget.RadioGroup(this); looks.setOrientation(android.widget.RadioGroup.VERTICAL);
        String[][] choices = {{"off", "Off"}, {"mask", "Mask: hides the lines behind the head"}, {"outline", "Outline: draws the head's edges"}, {"tint", "Tinted: shades the head"}};
        for (String[] choice : choices) {
            android.widget.RadioButton option = new android.widget.RadioButton(this); option.setText(choice[1]); option.setTextColor(ink); option.setMinHeight(dp(48));
            option.setButtonTintList(ColorStateList.valueOf(teal)); option.setId(View.generateViewId()); looks.addView(option);
            if (choice[0].equals(headLook())) option.setChecked(true);
            option.setOnCheckedChangeListener((v, on) -> { if (on) { viewerPrefs().edit().putString("headLook", choice[0]).apply(); pushView(); } });
        }
        body.addView(looks);
        label(body, "A simple printhead sits on the nozzle and hides the lines behind it, as the real one does in the camera picture.", 12, muted, false);
        if (followMode) {
            Button size = rowButton(body, "Printhead size…", () -> { if (dialogs[0] != null) dialogs[0].dismiss(); headSizeDialog(); });
            ((LinearLayout.LayoutParams) size.getLayoutParams()).width = -1; ((LinearLayout.LayoutParams) size.getLayoutParams()).weight = 0; ((LinearLayout.LayoutParams) size.getLayoutParams()).leftMargin = 0;
        }
        groupHeading(body, "Live timing");
        android.widget.CheckBox smooth = new android.widget.CheckBox(this); smooth.setTextColor(ink); smooth.setMinHeight(dp(48));
        smooth.setText("Smooth live movement");
        smooth.setChecked(viewerPrefs().getBoolean("smoothLive", true));
        smooth.setOnCheckedChangeListener((v, on) -> viewerPrefs().edit().putBoolean("smoothLive", on).apply());
        body.addView(smooth);
        label(body, "Moves smoothly between the printer's updates, about one update behind.", 12, muted, false);
        if (followMode) {
            // Delay in tenths of a second, 0–10 s.
            LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            TextView label = new TextView(this); label.setTextSize(12); label.setTextColor(ink); body.addView(label);   // above: − slider + below
            SeekBar bar = new SeekBar(this); bar.setMax(100); bar.setProgress(viewerPrefs().getInt(ck("liveDelay"), 0));
            bar.setProgressTintList(ColorStateList.valueOf(teal)); bar.setThumbTintList(ColorStateList.valueOf(teal));
            row.addView(bar, new LinearLayout.LayoutParams(0, dp(48), 1)); A11y.labelFor(label, bar);
            Runnable showValue = () -> { String v = String.format(Locale.getDefault(), "%.1f s", bar.getProgress() / 10.0); label.setText("Delay to match the camera: " + v); A11y.state(bar, v); };
            showValue.run();
            // − and + step a tenth of a second, for fine tuning against the picture.
            for (int step : new int[] {-1, 1}) {
                Button nudge = new Button(this); nudge.setText(step < 0 ? "−" : "+"); nudge.setTextSize(18); nudge.setTextColor(teal); nudge.setMinWidth(dp(48)); nudge.setMinimumWidth(dp(48));
                GradientDrawable shape = new GradientDrawable(); shape.setColor(buttonColor); shape.setCornerRadius(dp(12)); nudge.setBackground(shape);
                nudge.setContentDescription(step < 0 ? "Less delay, a tenth of a second" : "More delay, a tenth of a second");
                nudge.setOnClickListener(v -> { int value = Math.max(0, Math.min(100, bar.getProgress() + step)); bar.setProgress(value); viewerPrefs().edit().putInt(ck("liveDelay"), value).apply(); showValue.run(); });
                LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(dp(56), -2); nudge.setMinHeight(dp(48)); nudge.setMinimumHeight(dp(48)); nudge.setPadding(0, 0, 0, 0);
                if (step < 0) row.addView(nudge, 0, size); else row.addView(nudge, size);
            }
            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar b, int value, boolean fromUser) { if (fromUser) { viewerPrefs().edit().putInt(ck("liveDelay"), value).apply(); showValue.run(); } }
                @Override public void onStartTrackingTouch(SeekBar b) { }
                @Override public void onStopTrackingTouch(SeekBar b) { }
            });
            body.addView(row);
            label(body, "If the printhead in the picture trails the drawing, raise this until they move together. Often 2–5 s through the cloud.", 12, muted, false);
            android.widget.CheckBox auto = new android.widget.CheckBox(this); auto.setTextColor(ink); auto.setMinHeight(dp(48));
            auto.setText("Use the cloud camera's measured delay"); auto.setChecked(viewerPrefs().getBoolean(ck("autoDelay"), true));
            auto.setOnCheckedChangeListener((v, on) -> { viewerPrefs().edit().putBoolean(ck("autoDelay"), on).apply(); delayNote.setText(delayText()); sendLockstep(); });
            body.addView(auto);
            delayNote = A11y.polite(label(body, delayText(), 12, muted, false));
            android.widget.CheckBox step = new android.widget.CheckBox(this); step.setTextColor(ink); step.setMinHeight(dp(48));
            step.setText("Move in step with the camera's pictures"); step.setChecked(viewerPrefs().getBoolean("lockstep", false));
            step.setOnCheckedChangeListener((v, on) -> { viewerPrefs().edit().putBoolean("lockstep", on).apply(); main.removeCallbacksAndMessages(delayToken); sendLockstep(); });
            body.addView(step);
            label(body, "Each picture shows the toolpath where the printer was when that picture was taken. The two stay together, and catch up at once after the app was away. Turn off if it looks jumpy.", 12, muted, false);
        }
        dialogs[0] = new AlertDialog.Builder(this).setTitle("Layers, transparency and nozzle dot").setView(scroll).setPositiveButton("Done", null).show();
        dialogs[0].setOnDismissListener(d -> delayNote = null);
    }

    /** The bed grid drawn over the camera picture (seen from the camera): all lines, edges only or hidden, and how strong. */
    private void overlayDialog() {
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(dp(20), dp(8), dp(20), dp(8));
        TextView note = new TextView(this); note.setTextSize(13); note.setTextColor(ink);
        note.setText("The bed outline drawn over the camera picture when you look from the camera. The same settings are used while lining up.");
        body.addView(note);
        Button[] grid = new Button[1];
        Runnable label = () -> { String g = viewerPrefs().getString("alignGrid", "all"); grid[0].setText("all".equals(g) ? "Grid: all lines" : "edges".equals(g) ? "Grid: bed edges only" : "Grid: hidden"); };
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); body.addView(row);
        grid[0] = rowButton(row, "", () -> {
            String now = viewerPrefs().getString("alignGrid", "all");
            viewerPrefs().edit().putString("alignGrid", "all".equals(now) ? "edges" : "edges".equals(now) ? "none" : "all").apply();
            label.run(); sendAlignStyle();
        });
        label.run();
        styleSlider(body, "Outline strength", "alignAlpha", 15, 100, 90, "%");
        new AlertDialog.Builder(this).setTitle("Camera overlay").setView(body).setPositiveButton("Done", null).show();
        if (web != null && pageReady) web.evaluateJavascript("viewer.setView('printer')", null);   // so the change shows at once
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
            .append("Gestures: drag to turn the view, two fingers to move and zoom, double-tap to reset.\n\nTap a colour chip to hide that type; hold it to see only that type (the quickest way to find supports or the prime tower). More… > Show or hide line types does the same from a list.");
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
        if (aligning != null && bedNote != null) bedNote.setText(bedText());
        if (aligning != null && moveNote != null && printer.feedback != null && !printer.feedback.isEmpty() && !printer.feedback.equals(lastMoveFeedback)) {
            lastMoveFeedback = printer.feedback; moveNote.setText(StatusPresentation.clean(printer.feedback));
        }
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
        // A step forward within the layer glides there over about the time since the last step (the next one is due then).
        long now = System.currentTimeMillis();
        if (located != lastLocated) {
            boolean sameLayer = lastLocated >= 0 && located > lastLocated && located < path.count && lastLocated < path.count && path.layerOf(located) == path.layerOf(lastLocated);
            smoothNext = sameLayer && lastStepAt > 0 ? (int) Math.max(250, Math.min(4000, now - lastStepAt)) : 0;
            lastStepAt = now;
        }
        boolean moved = located != lastLocated;
        lastLocated = located;
        int newLayer = located >= path.count ? path.layerCount - 1 : path.layerOf(located), newMove = located - path.layerStart(newLayer);
        double[] nozzle = hasPosition ? new double[] {x, y, path.layerZ(newLayer)} : null;
        int glide = smoothNext; smoothNext = 0;
        Runnable show = () -> {
            if (!following || path == null || isDestroyed()) return;
            layer = newLayer; move = newMove; syncBars(); smoothNext = glide; pushView(nozzle);
        };
        // The camera's picture arrives seconds late (the cloud's video most of all): the drawing can wait as long, so the two
        // show the same moment. Only a new position waits; the status line below is never delayed.
        takeCloudPicture();
        long delay = effectiveDelay();
        if (lockstep()) { liveAt = System.currentTimeMillis(); show.run(); }   // the page draws each picture's moment itself
        else if (delay > 0 && moved) main.postAtTime(show, delayToken, android.os.SystemClock.uptimeMillis() + delay);
        else if (delay == 0) show.run();
        int progress = live.optJSONObject("machine_status").optInt("progress", -1);
        logFollow(print, position, hasPosition, currentLayer, located);
        status.setText(String.format(Locale.getDefault(), "Live · printing layer %d of %d%s%s", currentLayer, path.layerCount,
            progress >= 0 ? " · " + progress + "%" : "", hasPosition ? "" : " · no nozzle position reported, so showing the layer start"));
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
            if (hint != null) hint.setVisibility(View.GONE);
            missingPreview = new ImageView(this); missingPreview.setAdjustViewBounds(true); missingPreview.setScaleType(ImageView.ScaleType.FIT_CENTER); missingPreview.setVisibility(View.GONE);
            missingPreview.setContentDescription("Preview image of the print that is running, from the printer");
            missingCard.addView(missingPreview, new LinearLayout.LayoutParams(-1, dp(160)));
            label(missingCard, StatusPresentation.NO_FILE_LINE, 13, ink, false);
            buildMissingActions(filename);
            missingDetail = A11y.polite(label(missingCard, "", 13, ink, false)); missingDetail.setTextIsSelectable(true);
            // Why a download fails, and a download that waits for the printer to serve the file: both only read from the printer.
            LinearLayout more = new LinearLayout(this); more.setOrientation(LinearLayout.HORIZONTAL); missingCard.addView(more);
            rowButton(more, "Check file access", () -> { if (printer != null) printer.checkFileAccess(); });
            waitButton = rowButton(more, "Download when it opens", () -> {
                if (printer == null) return;
                lastAttempt = true;
                if (filename.replaceFirst("^/+", "").equals(printer.waitingFor())) printer.stopWaiting("Stopped waiting for the printer's file server."); else printer.downloadWhenOpen("local", filename);
            });
            accessText = A11y.polite(label(missingCard, "", 13, ink, false)); accessText.setTextIsSelectable(true); accessText.setVisibility(View.GONE);
        }
        if (printer != null) {
            printer.thumbnail("local", filename);
            android.graphics.Bitmap picture = ThumbnailDecoder.decodeBase64(printer.thumbnails.get("local/" + filename));
            if (picture != null) { missingPreview.setImageBitmap(picture); missingPreview.setVisibility(View.VISIBLE); }
        }
        boolean waiting = printer != null && filename.replaceFirst("^/+", "").equals(printer.waitingFor());
        missingDetail.setText(waiting ? StatusPresentation.clean(printer.feedback) : failed ? "The download did not work: " + StatusPresentation.clean(printer.feedback) : "");
        missingDetail.setVisibility(waiting || failed ? View.VISIBLE : View.GONE);
        if (waitButton != null) waitButton.setText(waiting ? "Stop waiting" : "Download when it opens");
        String access = printer == null ? null : printer.fileAccessText != null && printer.folderText != null ? printer.fileAccessText + "\n" + printer.folderText : printer.fileAccessText != null ? printer.fileAccessText : printer.folderText;
        if (accessText != null) { accessText.setText(access == null ? "" : StatusPresentation.clean(access)); accessText.setVisibility(access == null ? View.GONE : View.VISIBLE); }
    }

    private Button waitButton; private TextView accessText;

    private TextView missingDetail, hint;
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
        TextView tip = new TextView(this); tip.setTextSize(13); tip.setTextColor(muted);
        tip.setText("Untick a line type to hide it. On the main screen, tap its chip to hide it, or hold the chip to show only that type.");
        body.addView(tip);
        java.util.List<CheckBox> boxes = new java.util.ArrayList<>();
        LinearLayout quick = new LinearLayout(this); quick.setOrientation(LinearLayout.HORIZONTAL); body.addView(quick);
        rowButton(quick, "Show all", () -> { for (CheckBox box : boxes) box.setChecked(true); });
        rowButton(quick, "Hide all", () -> { for (CheckBox box : boxes) box.setChecked(false); });
        for (int i = 0; i < lengths.length; i++) {
            if (lengths[i] <= 0) continue;
            final int feature = i;
            CheckBox box = new CheckBox(this);
            box.setText(String.format(Locale.getDefault(), "%s · %.1f m", displayName(i), lengths[i] / 1000));
            box.setMinHeight(dp(48));
            box.setTextColor(ink); box.setChecked((hidden >> i & 1) == 0); box.setButtonTintList(ColorStateList.valueOf(Color.parseColor(PALETTE[i])));
            box.setOnCheckedChangeListener((view, checked) -> { hidden = checked ? hidden & ~(1 << feature) : hidden | (1 << feature); buildLegend(); pushView(); });
            body.addView(box); boxes.add(box);
        }
        ScrollView scroll = new ScrollView(this); scroll.addView(body);
        new AlertDialog.Builder(this).setTitle("Show or hide line types").setView(scroll).setPositiveButton("Done", null).show();
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
