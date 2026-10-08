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
    static final String EXTRA_FILE = "file", EXTRA_NAME = "name", EXTRA_FOLLOW = "follow";
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

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) { printer = ((PrinterService.LocalBinder) binder).service(); if (followMode) printer.watch(GcodeViewerActivity.this); }
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
        following = followMode;
        build();
        String file = getIntent().getStringExtra(EXTRA_FILE), name = getIntent().getStringExtra(EXTRA_NAME);
        if (file != null) load(new File(file), name != null ? name : new File(file).getName());
        else if (!followMode) status.setText("No G-code file to show.");
        if (followMode) bound = bindService(new Intent(this, PrinterService.class), connection, BIND_AUTO_CREATE);
    }

    @Override protected void onStart() { super.onStart(); if (printer != null && followMode) printer.watch(this); }
    @Override protected void onStop() { setPlaying(false); if (printer != null) printer.unwatch(this); super.onStop(); }
    @Override protected void onDestroy() {
        if (printer != null) printer.unwatch(this);
        if (bound) unbindService(connection);
        worker.shutdownNow(); main.removeCallbacksAndMessages(null);
        if (web != null) { ((android.view.ViewGroup) web.getParent()).removeView(web); web.destroy(); web = null; }
        super.onDestroy();
    }

    // ------------------------------------------------------------------ layout
    private void build() {
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(background); root.setFitsSystemWindows(true);
        web = new WebView(this);
        root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL); panel.setPadding(dp(16), dp(8), dp(16), dp(12));
        GradientDrawable shape = new GradientDrawable(); shape.setColor(surface); shape.setCornerRadii(new float[] {dp(20), dp(20), dp(20), dp(20), 0, 0, 0, 0}); panel.setBackground(shape);
        root.addView(panel, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout header = new LinearLayout(this); header.setOrientation(LinearLayout.HORIZONTAL); header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        Button back = new Button(this); back.setText("‹ Back"); back.setAllCaps(false); back.setTextSize(14); back.setTextColor(teal); back.setBackground(null);
        back.setMinHeight(dp(48)); back.setMinimumHeight(dp(48)); back.setMinWidth(dp(64)); back.setMinimumWidth(dp(64)); back.setPadding(0, 0, dp(8), 0);
        back.setContentDescription("Back"); back.setOnClickListener(v -> finish());
        header.addView(back);
        title = new TextView(this); title.setText(followMode ? "Live toolpath" : "Toolpath"); title.setTextSize(16); title.setTextColor(ink); title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setSingleLine(true); title.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        LinearLayout titles = new LinearLayout(this); titles.setOrientation(LinearLayout.VERTICAL); titles.addView(title);
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        panel.addView(header, new LinearLayout.LayoutParams(-1, -2));
        status = label(titles, "Reading G-code…", 12, muted, false); status.setPadding(0, 0, 0, 0);
        legend = new Flow(this); panel.addView(legend, new LinearLayout.LayoutParams(-1, -2));
        int shown = hintsShown();
        if (shown < 3 || followMode) {
            label(panel, followMode ? "Live view of the file, up to the nozzle. Grey = still to print on this layer."
                : "Drag to turn the view · two fingers to move and zoom · double-tap to reset", 11, muted, false);
        }
        missingCard = new LinearLayout(this); missingCard.setOrientation(LinearLayout.VERTICAL); missingCard.setVisibility(View.GONE); panel.addView(missingCard);
        layerLabel = label(panel, "Layer", 13, ink, false);
        layerBar = seekBar(panel);
        moveLabel = label(panel, "Moves", 13, ink, false);
        moveBar = seekBar(panel);
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
        settings.setJavaScriptEnabled(true); settings.setAllowFileAccess(false); settings.setAllowContentAccess(false);
        web.setBackgroundColor(background);
        WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/data/", this::serveData).build();
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

    private final class Bridge {
        @JavascriptInterface public void onReady() { main.post(() -> { pageReady = true; sendTheme(); sendData(); }); }
        @JavascriptInterface public void onLoaded(int count) { main.post(() -> { setControlsEnabled(true); pushView(); if (following) changed(); }); }
        @JavascriptInterface public void onError(String message) { main.post(() -> status.setText(message)); }
    }

    // ------------------------------------------------------------------ loading
    private void load(File file, String name) {
        if (name.equals(loadingName)) return;
        loadingName = name; title.setText(StatusPresentation.clean(name.replaceFirst("(?i)\\.gcode$", "")));
        status.setText("Reading toolpath…"); missingCard.setVisibility(View.GONE); setControlsEnabled(false);
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
        layerLabel.setText(String.format(Locale.getDefault(), "%sLayer %d of %d · %.2f mm high", following ? "● LIVE · " : "", layer + 1, path.layerCount, path.layerZ(layer)));
        moveLabel.setText(String.format(Locale.getDefault(), "Drawn so far in this layer: %,d of %,d lines", move, layerSize(layer)));
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
        String[] names = {"Show / hide line types…", showTravel ? "Hide travel moves (blue)" : "Show travel moves (blue)",
            moveBar.getVisibility() == View.VISIBLE ? "Hide the within-layer slider" : "Step through this layer…", "What am I seeing?"};
        Runnable[] actions = {this::featureDialog, () -> { showTravel = !showTravel; pushView(); },
            () -> { int v = moveBar.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE; moveBar.setVisibility(v); moveLabel.setVisibility(v); }, this::helpDialog};
        for (int i = 0; i < names.length; i++) {
            Runnable action = actions[i];
            Button b = rowButton(body, names[i], () -> { dialog[0].dismiss(); action.run(); });
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
        if (followMode) text.append("\n\nLive: the phone shows this G-code file, not a camera. The printer reports its current layer and nozzle position, and the viewer shows the file printed up to there. Scrubbing the bars leaves live mode; “Back to live” returns.");
        new AlertDialog.Builder(this).setTitle("What am I seeing?").setMessage(text).setPositiveButton("Got it", null).show();
    }

    private void setControlsEnabled(boolean on) {
        for (View view : new View[] {layerBar, moveBar, play, firstLayer, more}) if (view != null) view.setEnabled(on);
        if (follow != null) follow.setEnabled(on && !following);
    }

    // ------------------------------------------------------------------ following a print
    @Override public void changed() {
        if (!followMode || printer == null || isDestroyed()) return;
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
            if (path == null) { showMissing(filename); return; }
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

    private void showMissing(String filename) {
        title.setText(StatusPresentation.clean(filename.replaceFirst("(?i)\\.gcode$", "")));
        boolean fetching = printer != null && (printer.fileBusy() || printer.feedback != null && printer.feedback.startsWith("Looking for"));
        status.setText(fetching ? StatusPresentation.clean(printer.feedback) : printer != null && lastAttempt && printer.feedback != null ? StatusPresentation.clean(printer.feedback) : "This phone has no copy of the G-code being printed.");
        if (missingCard.getVisibility() == View.VISIBLE) return;
        missingCard.removeAllViews(); missingCard.setVisibility(View.VISIBLE);
        label(missingCard, "Files uploaded, sliced or downloaded with this app are kept for the viewer. Download this one from the printer (the phone must be on the printer's Wi-Fi), or choose a copy on this phone.", 13, ink, false);
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
    private void buildLegend() {
        legend.removeAllViews();
        double[] lengths = path.featureLengths();
        for (int i = 0; i < lengths.length; i++) {
            if (lengths[i] <= 0) continue;
            final int feature = i;
            LinearLayout chip = new LinearLayout(this); chip.setOrientation(LinearLayout.HORIZONTAL); chip.setPadding(dp(2), dp(4), dp(10), dp(4));
            chip.setMinimumHeight(dp(32)); chip.setContentDescription(GcodeToolpath.FEATURES[i] + ((hidden >> i & 1) == 1 ? ", hidden. Tap to show." : ". Tap to hide, hold to show only this."));
            chip.setOnClickListener(v -> { hidden ^= 1 << feature; buildLegend(); pushView(); });
            chip.setOnLongClickListener(v -> { hidden = soloMask(feature); buildLegend(); pushView(); return true; });
            chip.setGravity(android.view.Gravity.CENTER_VERTICAL);
            View swatch = new View(this); GradientDrawable dot = new GradientDrawable(); dot.setColor(Color.parseColor(PALETTE[i])); dot.setCornerRadius(dp(3)); swatch.setBackground(dot);
            chip.addView(swatch, new LinearLayout.LayoutParams(dp(10), dp(10)));
            TextView name = new TextView(this); name.setText(GcodeToolpath.FEATURES[i]); name.setTextSize(12); name.setTextColor((hidden >> i & 1) == 1 ? muted : ink);
            if ((hidden >> i & 1) == 1) name.setPaintFlags(name.getPaintFlags() | android.graphics.Paint.STRIKE_THRU_TEXT_FLAG); name.setPadding(dp(5), 0, 0, 0);
            chip.addView(name);
            legend.addView(chip);
        }
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
            box.setText(String.format(Locale.getDefault(), "%s · %.1f m", GcodeToolpath.FEATURES[i], lengths[i] / 1000));
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
        Button button = new Button(this); button.setText(text); button.setAllCaps(false); button.setMinHeight(dp(48)); button.setMinimumHeight(dp(48));
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
