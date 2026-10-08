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
    private int ink, muted, error;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        store = new CloudAccountStore(this);
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        ink = dark ? 0xffe4eff2 : 0xff142c3b; muted = dark ? 0xffa7bec6 : 0xff536976; error = dark ? 0xffffb4ab : 0xffba1a1a;
        int pad = Math.round(12 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(dark ? 0xff10191d : 0xffedf3f4); root.setFitsSystemWindows(true);

        // What signing in means, in plain words. Shown whatever the WebView state.
        LinearLayout intro = new LinearLayout(this); intro.setOrientation(LinearLayout.VERTICAL); intro.setPadding(pad, pad, pad, 0);
        text(intro, "Sign in to Elegoo", 18, ink, true);
        text(intro, "1. Enter your Elegoo email and password on Elegoo's page below.\n2. Link Workshop never sees your password. It only receives the sign-in tokens that Elegoo's page hands over, and keeps them encrypted on this phone.", 13, muted, false);
        text(intro, "With this account the app can show your printers' status through the Elegoo cloud and watch the camera away from home. Once you turn on cloud control (the app asks first), it can also pause, stop, send files and start prints without a local connection. It does not change your password or account details.", 13, muted, false);
        text(intro, "To remove the account from this phone later, use Settings, then Sign out on this phone.", 13, muted, false);
        root.addView(intro, new LinearLayout.LayoutParams(-1, -2));
        status = text(root, "", 14, muted, false); status.setPadding(pad, pad / 2, pad, pad / 2);

        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            setStatus("This phone's Android System WebView is too old for Elegoo sign-in. Update Android System WebView from the Play Store, then reopen this screen.", true);
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
                if (!done) setStatus("Elegoo's sign-in page did not load. Check the phone's internet connection, then close this screen and open it again. [" + description + "]", true);
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
    private TextView text(LinearLayout parent, String value, int size, int color, boolean bold) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color);
        if (bold) view.setTypeface(null, android.graphics.Typeface.BOLD);
        view.setPadding(0, 0, 0, Math.round(6 * getResources().getDisplayMetrics().density));
        parent.addView(view, new LinearLayout.LayoutParams(-1, -2)); return view;
    }
    private void setStatus(String value, boolean problem) { status.setText(value); status.setTextColor(problem ? error : muted); }
    private String version() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception error) { return "dev"; }
    }

    @Override public void signedIn(CloudLogin.Account account) {
        try {
            store.save(account); done = true;
            setStatus("Signed in as " + (account.nickname.isEmpty() ? "your Elegoo account" : account.nickname) + ". Returning to the app. Your sign-in stays encrypted on this phone.", false);
            setResult(RESULT_OK);
            status.postDelayed(this::finish, 1500);
        } catch (Exception error) { setStatus("Signed in, but the account could not be saved securely on this phone, so nothing was stored. Try signing in again. If it keeps happening, restart the phone.", true); }
    }
    @Override public void failed(String message) { if (!done) setStatus(message, true); }
    @Override public void openExternal(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception error) { setStatus("No app on this phone can open that link.", true); }
    }

    private void back() { if (web != null && web.canGoBack()) web.goBack(); else finish(); }
    @SuppressLint("GestureBackNavigation") @Override public void onBackPressed() { back(); }
    @Override protected void onDestroy() {
        if (web != null) { ((ViewGroup) web.getParent()).removeView(web); web.destroy(); web = null; }
        super.onDestroy();
    }
}
