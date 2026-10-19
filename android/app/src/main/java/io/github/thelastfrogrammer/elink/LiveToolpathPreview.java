package io.github.thelastfrogrammer.elink;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import androidx.webkit.WebViewAssetLoader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The Print tab's small live toolpath, shown in place of the camera when chosen: the running print's selected G-code
 * drawn up to the nozzle, as Live toolpath does. It reads the status the app already has and the copy chosen for this
 * print; it never sends anything to the printer and never guesses a file. A tap opens the full Live toolpath.
 */
final class LiveToolpathPreview {
    private static final String ORIGIN = "https://appassets.androidplatform.net";
    private final Context context;
    private final FrameLayout frame;
    private final int index, background;
    private final boolean dark;
    private final Runnable onTap, onChange;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private WebView web;
    private boolean pageReady, sent;
    private GcodeToolpath path;
    private byte[] segments, travels, meta;
    /** The print shown (its job token), the one being read, and a ticket that drops answers for an earlier print. */
    private String job = "", reading;
    private long generation;
    private int lastLocated = -1;
    private long lastStepAt;
    private String note = "";

    LiveToolpathPreview(Context context, FrameLayout frame, int index, int background, boolean dark, Runnable onTap, Runnable onChange) {
        this.context = context; this.frame = frame; this.index = index; this.background = background; this.dark = dark; this.onTap = onTap; this.onChange = onChange;
    }

    /** What to say under the frame: empty while the toolpath is drawn. */
    String note() { return note; }
    boolean drawing() { return path != null && sent; }

    /** Follows the printer's latest status. Called on every status change while the preview is chosen and on screen. */
    void update(PrinterService printer) {
        if (web == null) create();
        GcodeJobTracker.Job running = printer == null ? null : printer.fileJob();
        if (running == null) { clear(); job = ""; say(printer != null && !printer.liveFresh() ? "Waiting for printer status…" : "Waiting to identify the current print…"); return; }
        if (!running.token.equals(job)) { clear(); job = running.token; }
        if (path == null) { if (reading == null) read(printer, running); return; }
        JSONObject live = printer.liveStatus(), print = live.optJSONObject("print_status");
        if (print == null) return;
        JSONObject position = Cc2Codec.position(live);
        boolean hasPosition = position != null && position.has("x") && position.has("y");
        double x = hasPosition ? position.optDouble("x") : 0, y = hasPosition ? position.optDouble("y") : 0;
        int located = path.locate(print.optInt("current_layer", 0), x, y, hasPosition, lastLocated);
        long now = System.currentTimeMillis(); int glide = 0;
        if (located != lastLocated) {
            boolean sameLayer = lastLocated >= 0 && located > lastLocated && located < path.count && lastLocated < path.count && path.layerOf(located) == path.layerOf(lastLocated);
            glide = sameLayer && lastStepAt > 0 ? (int) Math.max(250, Math.min(4000, now - lastStepAt)) : 0;
            lastStepAt = now;
        } else if (sent) return;   // nothing moved
        lastLocated = located;
        int layer = located >= path.count ? path.layerCount - 1 : path.layerOf(located);
        push(located, layer, hasPosition ? new double[] {x, y, path.layerZ(layer)} : null, glide);
        say(hasPosition ? "" : "The printer reports no nozzle position, so the drawing shows the start of the layer.");
    }

    /** Frees the page and the file (the preview was turned off, or the tab or the app went out of sight). */
    void release() {
        clear(); job = "";
        WebView old = web; web = null; pageReady = false;
        if (old != null) { frame.removeView(old); old.destroy(); }
    }

    void shutdown() { release(); worker.shutdownNow(); }

    private void say(String text) { if (!text.equals(note)) { note = text; onChange.run(); } }

    private void clear() {
        generation++; reading = null; lastLocated = -1; lastStepAt = 0; sent = false;
        synchronized (this) { path = null; segments = travels = meta = null; }
        if (web != null && pageReady) web.evaluateJavascript("viewer.clearToolpath()", null);
    }

    /** Only the copy chosen for this print (by the user or matched exactly); without one the note says where to choose it. */
    private void read(PrinterService printer, GcodeJobTracker.Job running) {
        GcodeLibrary library = printer.library;
        if (library == null) return;
        reading = running.token; long ticket = generation;
        say("Reading this print's toolpath…");
        worker.execute(() -> {
            String problem = null; GcodeToolpath read = null; byte[] s = null, t = null, m = null;
            try {
                GcodeLibrary.Artifact copy = library.selected(running.printer, running.token);
                if (copy == null) problem = "No copy of this print's G-code is on the phone. Tap the preview to choose or download it in Live toolpath.";
                else if (!library.verify(copy)) problem = "The copy chosen for this print is missing or changed. Tap the preview to choose it again.";
                else { read = GcodeToolpath.read(copy.file); s = read.segmentsBinary(); t = read.travelsBinary(); m = read.layersJson().getBytes("UTF-8"); }
            } catch (IOException | OutOfMemoryError failure) { problem = "This print's G-code could not be read here. Live toolpath can show it."; read = null; }
            GcodeToolpath done = read; byte[] fs = s, ft = t, fm = m; String why = problem;
            main.post(() -> {
                if (ticket != generation || web == null) return;
                if (done == null) { say(why); return; }   // reading stays set: not tried again for this print
                synchronized (this) { path = done; segments = fs; travels = ft; meta = fm; }
                say(""); send();
            });
        });
    }

    private void send() {
        if (!pageReady || path == null || sent || web == null) return;
        web.evaluateJavascript("viewer.load(" + generation + ")", null);
    }

    private void push(int end, int layer, double[] nozzle, int glide) {
        if (web == null || !pageReady || !sent || path == null) return;
        try {
            SharedPreferences prefs = context.getSharedPreferences("viewer-hints", Context.MODE_PRIVATE);
            int start = path.layerStart(layer), layerEnd = path.layerEnd(layer);
            JSONObject state = new JSONObject().put("start", 0).put("end", end).put("ghostEnd", layerEnd).put("dimBelow", start)
                .put("hidden", prefs.getInt("liveHidden", 0)).put("showTravel", false)
                .put("travelStart", 0).put("travelEnd", layer + 1 < path.layerCount ? path.layerTravelStart[layer + 1] : path.travelCount)
                .put("nozzleSize", prefs.getInt("nozzleSize", 16)).put("layerStart", start)
                .put("supportAlpha", prefs.getInt("supportOpacity", 35) / 100.0)
                .put("typeAlpha", new JSONArray(GcodeViewerActivity.typeAlpha(prefs))).put("edgeMask", GcodeViewerActivity.edgeMask(prefs))
                .put("nozzle", nozzle != null ? new JSONArray(nozzle) : JSONObject.NULL);
            if (glide > 0 && prefs.getBoolean("smoothLive", true)) state.put("smoothMs", glide);
            web.evaluateJavascript("viewer.update(" + state + ")", null);
        } catch (Exception ignored) { }
    }

    @SuppressLint({"SetJavaScriptEnabled", "ClickableViewAccessibility"})
    private void create() {
        web = new WebView(context);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true); settings.setAllowFileAccess(false); settings.setAllowContentAccess(false);
        web.setBackgroundColor(background);
        web.setContentDescription("Live toolpath of the running print. Tap to open Live toolpath.");
        WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(context))
            .addPathHandler("/data/", this::serve).build();
        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) { return loader.shouldInterceptRequest(request.getUrl()); }
        });
        web.addJavascriptInterface(new Bridge(), "Android");
        // The page scrolls with the tab: turning and zooming belong to the full view, so a touch here is only a tap.
        int slop = ViewConfiguration.get(context).getScaledTouchSlop(); float[] down = new float[2];
        web.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) { down[0] = event.getX(); down[1] = event.getY(); }
            else if (event.getActionMasked() == MotionEvent.ACTION_UP && Math.hypot(event.getX() - down[0], event.getY() - down[1]) < slop) { v.performClick(); onTap.run(); }
            return true;
        });
        web.setOnClickListener(v -> { });
        frame.addView(web, Math.min(index, frame.getChildCount()), new FrameLayout.LayoutParams(-1, -1));
        web.loadUrl(ORIGIN + "/assets/viewer/index.html");
    }

    private WebResourceResponse serve(String name) {
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

    private void theme() {
        try {
            JSONObject theme = new JSONObject().put("background", new JSONArray(new double[] {Color.red(background) / 255.0, Color.green(background) / 255.0, Color.blue(background) / 255.0}))
                .put("palette", new JSONArray(GcodeViewerActivity.PALETTE));
            if (dark) theme.put("grid", new JSONArray(new double[] {0.2, 0.27, 0.3, 1})).put("plate", new JSONArray(new double[] {0.1, 0.14, 0.16, 1}))
                .put("ghost", new JSONArray(new double[] {0.45, 0.5, 0.53})).put("dim", new JSONArray(new double[] {0.3, 0.34, 0.36}));
            web.evaluateJavascript("viewer.setTheme(" + theme + ")", null);
        } catch (Exception ignored) { }
    }

    private final class Bridge {
        @JavascriptInterface public void onReady() { main.post(() -> { if (web == null) return; pageReady = true; theme(); send(); }); }
        @JavascriptInterface public void onLoaded(int count, long ticket) { main.post(() -> {
            if (ticket != generation || path == null || web == null) return;
            sent = true; lastLocated = -1; onChange.run();   // the next status (asked for by onChange) draws up to the nozzle
        }); }
        @JavascriptInterface public void onError(String text) { main.post(() -> say("The toolpath could not be drawn here: " + StatusPresentation.clean(text))); }
    }
}
