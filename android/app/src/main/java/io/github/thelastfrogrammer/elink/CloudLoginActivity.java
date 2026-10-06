package io.github.thelastfrogrammer.elink;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.annotation.SuppressLint;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.ViewGroup;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.window.OnBackInvokedDispatcher;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import java.util.Locale;

/** Hosts Elegoo's own sign-in page; the app never sees the password, only the tokens the page reports. */
public final class CloudLoginActivity extends Activity implements CloudLogin.Host {
    public static final String EXTRA_CHINA = "china";
    private WebView web;
    private TextView status;
    private CloudAccountStore store;
    private boolean done;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        store = new CloudAccountStore(this);
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(dark ? 0xff10191d : 0xffedf3f4); root.setFitsSystemWindows(true);
        status = new TextView(this); status.setTextColor(dark ? 0xffa7bec6 : 0xff536976); status.setTextSize(13);
        int pad = Math.round(12 * getResources().getDisplayMetrics().density); status.setPadding(pad, pad, pad, pad);
        status.setText("Sign in on Elegoo's page. Link Workshop only receives the account tokens Elegoo hands to ElegooSlicer, and stores them encrypted on this phone.");
        root.addView(status, new LinearLayout.LayoutParams(-1, -2));
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            status.setText("This phone's Android System WebView is too old for Elegoo sign-in. Update Android System WebView from the Play Store and try again.");
            setContentView(root); return;
        }
        web = new WebView(this); web.setBackgroundColor(Color.TRANSPARENT);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true); settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false); settings.setAllowContentAccess(false); settings.setSupportMultipleWindows(false);
        // The page only talks to its host over window.wx when the user agent names ElegooSlicer.
        settings.setUserAgentString(settings.getUserAgentString() + " " + CloudLogin.SLICER_AGENT + " (" + (dark ? "dark" : "light") + ") LinkWorkshop/" + version());
        CloudLogin.Bridge bridge = new CloudLogin.Bridge(store.deviceId(), this);
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) WebViewCompat.addWebMessageListener(web, "wx", CloudLogin.ORIGINS, (view, message, origin, mainFrame, reply) -> {
            if (!mainFrame || done || message.getData() == null) return;
            for (String script : bridge.receive(message.getData()))
                if (CloudLogin.trusted(view.getUrl())) view.evaluateJavascript(script, null);
        });
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // Single sign-on may leave Elegoo's origin; it gets no bridge there. Anything but https opens outside.
                if ("https".equals(request.getUrl().getScheme())) return false;
                openExternal(request.getUrl().toString()); return true;
            }
            @Override public void onReceivedError(WebView view, int code, String description, String url) {
                if (!done) status.setText("Elegoo's sign-in page could not be loaded: " + description + ". Check the phone's internet connection.");
            }
        });
        root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1f));
        // Back gestures bypass onBackPressed from Android 13 when targeting API 33+.
        if (Build.VERSION.SDK_INT >= 33) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::back);
        setContentView(root);
        boolean china = getIntent().getBooleanExtra(EXTRA_CHINA, false);
        Locale locale = Locale.getDefault();
        String language = china ? "zh-CN" : locale.getLanguage().isEmpty() ? "en" : locale.getLanguage();
        web.loadUrl(CloudLogin.url(china, language, china ? "CN" : locale.getCountry(), store.deviceId(), dark));
    }
    private String version() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception error) { return "dev"; }
    }

    @Override public void signedIn(CloudLogin.Account account) {
        try {
            store.save(account); done = true;
            status.setText("Signed in: " + account.summary() + ". Tokens are stored encrypted on this phone.");
            setResult(RESULT_OK);
            status.postDelayed(this::finish, 1500);
        } catch (Exception error) { status.setText("Signed in, but the account could not be saved securely on this phone. Nothing was stored."); }
    }
    @Override public void failed(String message) { if (!done) status.setText(message); }
    @Override public void openExternal(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception error) { status.setText("No app can open that link."); }
    }

    private void back() { if (web != null && web.canGoBack()) web.goBack(); else finish(); }
    @SuppressLint("GestureBackNavigation") @Override public void onBackPressed() { back(); }
    @Override protected void onDestroy() {
        if (web != null) { ((ViewGroup) web.getParent()).removeView(web); web.destroy(); web = null; }
        super.onDestroy();
    }
}
