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
import android.view.View;
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
 * The camera starts when this screen opens. If it cannot connect, the screen says why and what to check, and Try again starts over.
 */
public final class CloudCameraActivity extends Activity {
    public static final String EXTRA_SERIAL = "serial", EXTRA_NAME = "name";
    private static final String ORIGIN = "https://appassets.androidplatform.net";
    private static final int SAVE_SNAPSHOT = 1;
    private static final int PROBLEM = 0xffffb4ab, NORMAL = 0xffe6eef1;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    /** While the video plays, the running print's recording takes its camera pictures from it (in cloud mode nothing else can). */
    private PrinterService printer; private boolean bound, playing; private String lastPageLine = "";
    /**
     * Whether this screen holds (or only just left) the printer's video channel. One account may be in the channel once:
     * a second join with the same identity makes the service ban one of them (UID_BANNED), so the Print tab waits.
     */
    private static volatile boolean showing; private static volatile long leftAt;
    static boolean holdsChannel() { return showing || android.os.SystemClock.elapsedRealtime() - leftAt < 3_000; }
    private final android.content.ServiceConnection connection = new android.content.ServiceConnection() {
        @Override public void onServiceConnected(android.content.ComponentName name, android.os.IBinder binder) { printer = ((PrinterService.LocalBinder) binder).service(); }
        @Override public void onServiceDisconnected(android.content.ComponentName name) { printer = null; }
    };
    private final Runnable pictureTick = new Runnable() { public void run() {
        long mono = android.os.SystemClock.elapsedRealtime();
        if (web != null && playing && printer != null && printer.recorder != null && printer.recorder.pictureDue(mono)) web.evaluateJavascript("snapshot(true)", null);
        main.postDelayed(this, 15_000);
    } };
    private WebView web;
    private TextView title, status;
    private Button snapshot, retry;
    private boolean pageReady, started, choosingFile;
    private CloudApi.AgoraCredential credential;
    private byte[] pendingSnapshot;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        showing = true;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        String serial = getIntent().getStringExtra(EXTRA_SERIAL), name = getIntent().getStringExtra(EXTRA_NAME);
        int pad = Math.round(12 * getResources().getDisplayMetrics().density), touch = Math.round(48 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(Color.BLACK); root.setFitsSystemWindows(true);
        title = new TextView(this); title.setTextColor(0xffa7bec6); title.setTextSize(13); title.setPadding(pad, pad, pad, 0);
        title.setText((name == null || name.isEmpty() ? "Printer" : StatusPresentation.clean(name)) + " · camera through the Elegoo cloud");
        status = new TextView(this); status.setTextColor(NORMAL); status.setTextSize(15); status.setPadding(pad, pad / 2, pad, pad);
        root.addView(title); root.addView(status);
        if (serial == null || !Cc2Discovery.validSerial(serial)) { showProblem("No printer was chosen for the camera. Close this screen and choose the printer on the Camera tab."); setContentView(root); return; }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            showProblem("The camera needs a newer Android System WebView. Update Android System WebView from the Play Store, then open the camera again.");
            setContentView(root); return;
        }
        web = new WebView(this); web.setBackgroundColor(Color.BLACK);
        bound = bindService(new Intent(this, PrinterService.class), connection, BIND_AUTO_CREATE);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true); settings.setDomStorageEnabled(true); settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(false); settings.setAllowContentAccess(false);
        WebViewAssetLoader assets = new WebViewAssetLoader.Builder().addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this)).build();
        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) { return assets.shouldInterceptRequest(request.getUrl()); }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) { return true; }
            // The page's own status line duplicates this screen's; the message is shown here instead.
            @Override public void onPageFinished(WebView view, String url) { view.evaluateJavascript("var s = document.getElementById('status'); if (s) s.style.display = 'none';", null); }
        });
        WebViewCompat.addWebMessageListener(web, "Android", Collections.singleton(ORIGIN), (view, message, origin, mainFrame, reply) -> {
            if (!mainFrame || message.getData() == null) return;
            handle(message.getData());
        });
        root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        // Side by side normally; stacked at large font sizes, so no label breaks mid-word.
        boolean stack = getResources().getConfiguration().fontScale >= 1.3f;
        LinearLayout buttons = new LinearLayout(this); buttons.setOrientation(stack ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL); buttons.setGravity(Gravity.CENTER); buttons.setPadding(pad, pad / 2, pad, pad / 2);
        snapshot = new Button(this); snapshot.setText("Save snapshot to phone…"); snapshot.setAllCaps(false); snapshot.setEnabled(false);
        snapshot.setOnClickListener(v -> web.evaluateJavascript("snapshot()", null));
        retry = new Button(this); retry.setText("Try again"); retry.setAllCaps(false); retry.setVisibility(View.GONE); retry.setOnClickListener(v -> retry(serial));
        Button close = new Button(this); close.setText("Close camera"); close.setAllCaps(false); close.setOnClickListener(v -> finish());
        for (Button b : new Button[] {snapshot, retry, close}) b.setMinHeight(touch);
        buttons.addView(snapshot, slot(stack)); buttons.addView(retry, slot(stack));
        buttons.addView(close, slot(stack));
        root.addView(buttons);
        setContentView(root);
        web.loadUrl(ORIGIN + "/assets/camera/index.html");
        connect(serial);
    }

    /** Gets camera credentials from Elegoo; the page joins the channel once both the page and the credentials are ready. */
    private void connect(String serial) {
        showNormal("Getting camera access from Elegoo…");
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
                if (text != null) { Diagnostics.note(Diagnostics.CAMERA, "cloud camera screen: camera access refused: " + Diagnostics.scrub(text)); showProblem(explain(text)); return; }
                Diagnostics.note(Diagnostics.CAMERA, "cloud camera screen: camera access issued");
                credential = result; startIfReady(serial);
            });
        });
    }

    /** Starts over: reloads the camera page and asks Elegoo for credentials again. */
    private void retry(String serial) {
        if (web == null) return;
        started = false; pageReady = false; credential = null; retry.setVisibility(View.GONE); snapshot.setEnabled(false); playing = false; main.removeCallbacks(pictureTick);
        web.evaluateJavascript("stop()", null);
        web.loadUrl(ORIGIN + "/assets/camera/index.html");
        connect(serial);
    }

    private void handle(String raw) {
        try {
            JSONObject message = new JSONObject(raw);
            switch (message.optString("type")) {
                case "ready": pageReady = true; startIfReady(getIntent().getStringExtra(EXTRA_SERIAL)); break;
                case "event": Diagnostics.note(Diagnostics.CAMERA, "cloud camera screen: channel: " + Diagnostics.scrub(message.optString("text"))); break;
                case "status": { String text = message.optString("text"); if (!text.isEmpty() && !text.equals(lastPageLine)) { lastPageLine = text; Diagnostics.note(Diagnostics.CAMERA, "cloud camera screen: page: " + Diagnostics.scrub(text)); } showPageStatus(text); break; }
                case "playing": showNormal("Live. Video from the printer through Elegoo's cloud."); snapshot.setEnabled(true);
                    if (!playing) { playing = true; Diagnostics.note(Diagnostics.CAMERA, "cloud camera screen: video playing"); main.postDelayed(pictureTick, 3_000); } break;
                case "picture": keepPicture(message.optString("data")); break;
                case "snapshot": saveSnapshot(message.optString("data")); break;
                default: break;
            }
        } catch (Exception ignored) { }
    }

    private void showPageStatus(String text) {
        if (text.isEmpty()) return;
        if (isProblem(text)) showProblem(explain(text)); else showNormal(explain(text));
    }

    // The buttons exist only once the page is set up; the early messages (no printer, no WebView) come before them.
    private void showNormal(String text) { status.setText(text); status.setTextColor(NORMAL); if (retry != null) retry.setVisibility(View.GONE); }
    private void showProblem(String text) {
        status.setText(text); status.setTextColor(PROBLEM);
        if (retry != null) retry.setVisibility(View.VISIBLE);
        if (snapshot != null) snapshot.setEnabled(false);
    }

    /** A button's place in the row, or in the stack at large font sizes, with a small gap around it. */
    private LinearLayout.LayoutParams slot(boolean stack) {
        int gap = Math.round(4 * getResources().getDisplayMetrics().density);
        LinearLayout.LayoutParams p = stack ? new LinearLayout.LayoutParams(-1, -2) : new LinearLayout.LayoutParams(0, -2, 1);
        p.setMargins(gap, gap, gap, gap); return p;
    }

    /** True for the camera page's messages that mean the video did not start or stopped. */
    static boolean isProblem(String message) {
        return message.startsWith("The printer is not sending") || message.startsWith("The printer stopped") || message.startsWith("The printer left")
            || message.startsWith("Disconnected from") || message.startsWith("The camera could not start") || message.startsWith("Sign in with Elegoo")
            || message.startsWith("Elegoo did not issue camera") || message.startsWith("Could not reach");
    }

    /** The camera page's and this screen's messages in words a hobbyist can act on; a problem names the next step. */
    static String explain(String message) {
        String m = message == null ? "" : message.trim();
        if (m.isEmpty()) return "";
        if (m.startsWith("Connecting to the camera")) return "Connecting to the printer's camera through Elegoo's cloud. This usually takes under 20 seconds.";
        if (m.startsWith("Connected. Waiting")) return "Connected. Waiting for the printer to send video. If nothing appears within about 15 seconds, the printer's camera may be turned off.";
        if (m.startsWith("The printer is not sending")) return "No video from the printer. It did not join its camera channel. If it is on and online, its camera service may be stuck: restart the printer, then tap Try again.";
        if (m.startsWith("The printer stopped")) return "The printer stopped sending video. Check that it is still on and connected to the internet, then tap Try again.";
        if (m.startsWith("The printer left")) return "The printer left the camera connection. Check that it is still on and connected to the internet, then tap Try again.";
        if (m.contains("UID_BANNED")) return "This account was in the camera twice at once (another camera view was still open), so the camera service closed one. Close the other view, then tap Try again.";
        if (m.startsWith("Disconnected from")) return "Lost the connection to Elegoo's camera service " + detail(m) + ". Check the phone's internet connection, then tap Try again.";
        if (m.startsWith("The camera could not start")) return "The camera could not start " + detail(m) + ". Check the phone's internet connection, then tap Try again.";
        if (m.startsWith("Sign in with Elegoo")) return "Not signed in. Close this screen, go to Settings, and tap Sign in with Elegoo.";
        if (m.startsWith("Elegoo did not issue camera") || m.contains("camera credentials")) return "Elegoo did not give camera access for this account just now. Tap Try again in a minute. If it keeps happening, check that this printer is linked to this Elegoo account in Elegoo's app.";
        if (m.startsWith("Could not reach")) return "Could not reach the Elegoo cloud. Check the phone's internet connection, then tap Try again.";
        return m + " Tap Try again, or sign in again in Settings if it keeps failing.";
    }

    /** The bracketed detail of a message such as "... (reason)", as "[reason]", or empty. */
    private static String detail(String message) {
        int open = message.indexOf('('), close = message.lastIndexOf(')');
        return open >= 0 && close > open ? "[" + message.substring(open + 1, close) + "]" : "";
    }

    private void startIfReady(String serial) {
        if (started || !pageReady || credential == null || web == null) return;
        started = true;
        web.evaluateJavascript("start(" + JSONObject.quote(CloudControl.AGORA_APP_ID) + "," + JSONObject.quote(serial) + ","
            + JSONObject.quote(credential.rtcToken) + "," + JSONObject.quote(credential.rtcUserId) + ")", null);
    }

    private void keepPicture(String dataUrl) {
        String prefix = "data:image/jpeg;base64,";
        if (!dataUrl.startsWith(prefix) || printer == null) return;
        try { printer.keepPicture(Base64.decode(dataUrl.substring(prefix.length()), Base64.DEFAULT), android.os.SystemClock.elapsedRealtime(), PrintRecorder.PRINTER_CAMERA, "the cloud camera screen"); }
        catch (IllegalArgumentException unreadable) { }
    }
    private void saveSnapshot(String dataUrl) {
        String prefix = "data:image/jpeg;base64,";
        if (!dataUrl.startsWith(prefix)) { showNormal("There is no picture to save yet. Wait for the video to appear, then try again."); return; }
        try { pendingSnapshot = Base64.decode(dataUrl.substring(prefix.length()), Base64.DEFAULT); }
        catch (IllegalArgumentException error) { showNormal("The snapshot could not be read. Try again."); return; }
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
            catch (Exception error) { text = "The snapshot could not be saved. Choose a folder you can write to, then try again."; }
            String done = text; main.post(() -> { if (!isDestroyed()) showNormal(done); });
        });
    }

    @Override protected void onStop() {
        super.onStop(); leftAt = android.os.SystemClock.elapsedRealtime();
        // Leave the video channel when hidden, like the local camera; reopening the screen starts again.
        if (web != null && started && !choosingFile) { web.evaluateJavascript("end()", null); finish(); }
    }
    @Override protected void onDestroy() {
        showing = false; leftAt = android.os.SystemClock.elapsedRealtime();
        worker.shutdownNow(); main.removeCallbacksAndMessages(null);
        if (bound) { unbindService(connection); bound = false; }
        if (web != null) { web.evaluateJavascript("end()", null); ((ViewGroup) web.getParent()).removeView(web); web.destroy(); web = null; }
        super.onDestroy();
    }
}
