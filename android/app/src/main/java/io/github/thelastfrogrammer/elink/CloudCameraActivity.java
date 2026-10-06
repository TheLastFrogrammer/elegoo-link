package io.github.thelastfrogrammer.elink;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import java.io.OutputStream;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/**
 * Live camera through the Elegoo cloud (Agora RTC), for printers without a local connection. The page in assets/camera joins
 * the printer's video channel the way Elegoo's own printer page does; this activity supplies Elegoo-issued credentials.
 */
public final class CloudCameraActivity extends Activity {
    public static final String EXTRA_SERIAL = "serial", EXTRA_NAME = "name";
    private static final String ORIGIN = "https://appassets.androidplatform.net";
    private static final int SAVE_SNAPSHOT = 1;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private WebView web;
    private TextView status;
    private Button snapshot;
    private boolean pageReady, started, choosingFile;
    private CloudApi.AgoraCredential credential;
    private byte[] pendingSnapshot;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        String serial = getIntent().getStringExtra(EXTRA_SERIAL), name = getIntent().getStringExtra(EXTRA_NAME);
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int pad = Math.round(12 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(Color.BLACK); root.setFitsSystemWindows(true);
        status = new TextView(this); status.setTextColor(0xffe6eef1); status.setTextSize(14); status.setPadding(pad, pad, pad, pad);
        status.setText((name == null || name.isEmpty() ? "Printer" : StatusPresentation.clean(name)) + " · camera through the Elegoo cloud");
        root.addView(status);
        if (serial == null || !Cc2Discovery.validSerial(serial)) { status.setText("No cloud printer selected."); setContentView(root); return; }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            status.setText("Update Android System WebView from the Play Store to use the cloud camera."); setContentView(root); return;
        }
        web = new WebView(this); web.setBackgroundColor(Color.BLACK);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true); settings.setDomStorageEnabled(true); settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(false); settings.setAllowContentAccess(false);
        WebViewAssetLoader assets = new WebViewAssetLoader.Builder().addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this)).build();
        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) { return assets.shouldInterceptRequest(request.getUrl()); }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) { return true; }
        });
        WebViewCompat.addWebMessageListener(web, "Android", Collections.singleton(ORIGIN), (view, message, origin, mainFrame, reply) -> {
            if (!mainFrame || message.getData() == null) return;
            handle(message.getData());
        });
        root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout buttons = new LinearLayout(this); buttons.setGravity(Gravity.CENTER); buttons.setPadding(pad, pad / 2, pad, pad / 2);
        snapshot = new Button(this); snapshot.setText("Save snapshot"); snapshot.setAllCaps(false); snapshot.setEnabled(false);
        snapshot.setOnClickListener(v -> web.evaluateJavascript("snapshot()", null));
        Button close = new Button(this); close.setText("Close"); close.setAllCaps(false); close.setOnClickListener(v -> finish());
        buttons.addView(snapshot, new LinearLayout.LayoutParams(0, -2, 1)); buttons.addView(close, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(buttons);
        setContentView(root);
        web.loadUrl(ORIGIN + "/assets/camera/index.html");
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
                    if (issued.rtcToken.isEmpty() || issued.rtcUserId.isEmpty()) error = "Elegoo did not issue camera credentials for this account.";
                }
            } catch (Exception failure) { error = failure.getMessage() == null ? "Could not reach the Elegoo cloud." : failure.getMessage(); }
            String text = error; CloudApi.AgoraCredential result = issued;
            main.post(() -> {
                if (isDestroyed()) return;
                if (text != null) { status.setText(text); return; }
                credential = result; startIfReady(serial);
            });
        });
    }

    private void handle(String raw) {
        try {
            JSONObject message = new JSONObject(raw);
            switch (message.optString("type")) {
                case "ready": pageReady = true; startIfReady(getIntent().getStringExtra(EXTRA_SERIAL)); break;
                case "status": if (!message.optString("text").isEmpty()) status.setText(StatusPresentation.clean(message.optString("text"))); break;
                case "playing": status.setText("Live camera through the Elegoo cloud"); snapshot.setEnabled(true); break;
                case "snapshot": saveSnapshot(message.optString("data")); break;
                default: break;
            }
        } catch (Exception ignored) { }
    }

    private void startIfReady(String serial) {
        if (started || !pageReady || credential == null || web == null) return;
        started = true;
        web.evaluateJavascript("start(" + JSONObject.quote(CloudControl.AGORA_APP_ID) + "," + JSONObject.quote(serial) + ","
            + JSONObject.quote(credential.rtcToken) + "," + JSONObject.quote(credential.rtcUserId) + ")", null);
    }

    private void saveSnapshot(String dataUrl) {
        String prefix = "data:image/jpeg;base64,";
        if (!dataUrl.startsWith(prefix)) { status.setText("No video frame to save yet."); return; }
        try { pendingSnapshot = Base64.decode(dataUrl.substring(prefix.length()), Base64.DEFAULT); }
        catch (IllegalArgumentException error) { status.setText("Snapshot could not be read."); return; }
        String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT).format(new java.util.Date());
        choosingFile = true;
        startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("image/jpeg").addCategory(Intent.CATEGORY_OPENABLE)
            .putExtra(Intent.EXTRA_TITLE, "CC2-cloud-" + stamp + ".jpg"), SAVE_SNAPSHOT);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != SAVE_SNAPSHOT) return;
        choosingFile = false;
        byte[] image = pendingSnapshot; pendingSnapshot = null;
        if (result != RESULT_OK || data == null || data.getData() == null || image == null) return;
        Uri uri = data.getData();
        worker.execute(() -> {
            String text;
            try (OutputStream output = getContentResolver().openOutputStream(uri)) { if (output == null) throw new java.io.IOException(); output.write(image); text = "Snapshot saved."; }
            catch (Exception error) { text = "Snapshot could not be saved."; }
            String done = text; main.post(() -> { if (!isDestroyed()) status.setText(done); });
        });
    }

    @Override protected void onStop() {
        super.onStop();
        // Leave the video channel when hidden, like the local camera; reopening the screen starts again.
        if (web != null && started && !choosingFile) { web.evaluateJavascript("stop()", null); finish(); }
    }
    @Override protected void onDestroy() {
        worker.shutdownNow(); main.removeCallbacksAndMessages(null);
        if (web != null) { web.evaluateJavascript("stop()", null); ((ViewGroup) web.getParent()).removeView(web); web.destroy(); web = null; }
        super.onDestroy();
    }
}
