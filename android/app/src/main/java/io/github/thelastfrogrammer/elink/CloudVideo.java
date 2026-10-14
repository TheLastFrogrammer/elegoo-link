package io.github.thelastfrogrammer.elink;

import android.app.Activity;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.ViewGroup;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.json.JSONObject;

/**
 * The printer's camera through the Elegoo cloud, played inside another screen (the Print tab): the same page and credentials
 * as CloudCameraActivity, in a WebView the host places where it likes. Started only by the user; the host stops it when it
 * goes out of sight. While it plays, the running print's recording takes its pictures from it, as the full screen does.
 */
final class CloudVideo {
    interface Listener {
        /** A line for the user: what is happening, or what went wrong and what to do (problem). */
        void status(String text, boolean problem);
        void playing();
        /** The picture asked for with snapshot(), as JPEG bytes, or null when there was none. */
        void snapshot(byte[] jpeg);
    }

    private static final String ORIGIN = "https://appassets.androidplatform.net";
    private final Activity activity; private final String serial; private final Listener listener; private final Supplier<PrinterService> printer;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private WebView web;
    private boolean pageReady, started, playing, stopped;
    private CloudApi.AgoraCredential credential;
    private final Runnable pictureTick = new Runnable() { public void run() {
        PrinterService p = printer.get();
        if (web != null && playing && p != null && p.recorder != null && p.recorder.pictureDue(android.os.SystemClock.elapsedRealtime())) web.evaluateJavascript("snapshot(true)", null);
        main.postDelayed(this, 15_000);
    } };

    CloudVideo(Activity activity, String serial, Supplier<PrinterService> printer, Listener listener) { this.activity = activity; this.serial = serial; this.printer = printer; this.listener = listener; }

    static boolean supported() { return WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER); }
    boolean playing() { return playing; }

    /** The video's view, made on first call; the host adds it to its layout. */
    WebView view() {
        if (web != null) return web;
        web = new WebView(activity); web.setBackgroundColor(Color.BLACK);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true); settings.setDomStorageEnabled(true); settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(false); settings.setAllowContentAccess(false);
        WebViewAssetLoader assets = new WebViewAssetLoader.Builder().addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(activity)).build();
        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) { return assets.shouldInterceptRequest(request.getUrl()); }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) { return true; }
            @Override public void onPageFinished(WebView view, String url) { view.evaluateJavascript("var s = document.getElementById('status'); if (s) s.style.display = 'none';", null); }
        });
        WebViewCompat.addWebMessageListener(web, "Android", Collections.singleton(ORIGIN), (view, message, origin, mainFrame, reply) -> {
            if (mainFrame && message.getData() != null) handle(message.getData());
        });
        return web;
    }

    void start() {
        view().loadUrl(ORIGIN + "/assets/camera/index.html");
        listener.status("Getting camera access from Elegoo…", false);
        worker.execute(() -> {
            String error = null; CloudApi.AgoraCredential issued = null;
            try {
                CloudAccountStore store = new CloudAccountStore(activity);
                CloudLogin.Account account = store.load();
                if (account == null) error = "Sign in with Elegoo in Settings first.";
                else {
                    CloudApi api = new CloudApi(store.china(), account, CloudApi.agent(activity), CloudApi::https);
                    issued = api.agoraCredential();
                    if (api.account() != account) store.save(api.account());
                    if (issued.rtcToken.isEmpty() || issued.rtcUserId.isEmpty()) error = "Elegoo did not issue camera credentials for this account.";
                }
            } catch (Exception failure) { error = failure.getMessage() == null ? "Could not reach the Elegoo cloud." : failure.getMessage(); }
            String text = error; CloudApi.AgoraCredential result = issued;
            main.post(() -> {
                if (stopped) return;
                if (text != null) { listener.status(CloudCameraActivity.explain(text), true); return; }
                credential = result; startIfReady();
            });
        });
    }

    void snapshot() { if (web != null && playing) web.evaluateJavascript("snapshot()", null); else listener.snapshot(null); }

    /** Leaves the video channel and frees the view. */
    void stop() {
        stopped = true; playing = false; main.removeCallbacksAndMessages(null); worker.shutdownNow();
        if (web != null) {
            web.evaluateJavascript("stop()", null);
            WebView old = web; web = null;
            // Give the page a moment to leave the channel before the view goes.
            main.postDelayed(() -> { if (old.getParent() instanceof ViewGroup) ((ViewGroup) old.getParent()).removeView(old); old.destroy(); }, 500);
        }
    }

    private void startIfReady() {
        if (started || !pageReady || credential == null || web == null) return;
        started = true;
        web.evaluateJavascript("start(" + JSONObject.quote(CloudControl.AGORA_APP_ID) + "," + JSONObject.quote(serial) + ","
            + JSONObject.quote(credential.rtcToken) + "," + JSONObject.quote(credential.rtcUserId) + ")", null);
    }

    private void handle(String raw) {
        if (stopped) return;
        try {
            JSONObject message = new JSONObject(raw);
            switch (message.optString("type")) {
                case "ready": pageReady = true; startIfReady(); break;
                case "status": { String text = message.optString("text"); if (!text.isEmpty()) listener.status(CloudCameraActivity.explain(text), CloudCameraActivity.isProblem(text)); break; }
                case "playing": if (!playing) { playing = true; main.postDelayed(pictureTick, 3_000); } listener.playing(); break;
                case "picture": keep(message.optString("data")); break;
                case "snapshot": listener.snapshot(decode(message.optString("data"))); break;
                default: break;
            }
        } catch (Exception ignored) { }
    }

    private void keep(String dataUrl) {
        byte[] jpeg = decode(dataUrl); PrinterService p = printer.get();
        if (jpeg != null && p != null && p.recorder != null) p.recorder.savePicture(jpeg, android.os.SystemClock.elapsedRealtime());
    }
    private static byte[] decode(String dataUrl) {
        String prefix = "data:image/jpeg;base64,";
        if (dataUrl == null || !dataUrl.startsWith(prefix)) return null;
        try { return Base64.decode(dataUrl.substring(prefix.length()), Base64.DEFAULT); } catch (IllegalArgumentException unreadable) { return null; }
    }
}
