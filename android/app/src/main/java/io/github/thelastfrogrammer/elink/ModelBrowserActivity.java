package io.github.thelastfrogrammer.elink;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import android.widget.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The model sites, browsed inside the app: each site's own pages, where the user searches and signs in as on any browser
 * (the app never sees a password). When the site starts a download, the file comes here instead of the phone's Downloads:
 * model files and ZIPs of them only, https only, at most 200 MB, with the browser's own cookies for that address and no
 * other. The downloaded models go back to the Slice screen. Nothing on these pages can reach the app or the printer: no
 * JavaScript bridge, no file access, and other apps' links (intent:, market:) are not followed.
 */
public final class ModelBrowserActivity extends Activity {
    static final String EXTRA_SITE = "site", EXTRA_TERM = "term", EXTRA_URL = "url";
    static final String RESULT_PATHS = ModelSearchActivity.RESULT_PATHS;
    /** The largest file taken from a page's own (blob:) download, which has to pass through the page's memory. */
    static final long MAX_BLOB = 50L * 1024 * 1024;
    private static final int BLOB_CHUNK = 512 * 1024;   // a multiple of 4, so each piece decodes on its own

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private boolean dark;
    private int ink, muted, teal, background, surface, buttonColor;
    private WebView web;
    private ProgressBar loading;
    private TextView where, readyText, downloadText;
    private LinearLayout readyBar, downloadBar, tabs;
    private ProgressBar downloadProgress;
    private Button sliceButton;
    private HorizontalScrollView tabScroll;
    private final ArrayList<String> ready = new ArrayList<>();
    private final List<Button> tabButtons = new ArrayList<>();
    private String site = "", term = "";
    private volatile boolean cancelDownload, downloading;
    private File folder;
    private Object backCallback;

    @SuppressLint("SetJavaScriptEnabled")
    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        int appearance = getSharedPreferences("workshop-settings", MODE_PRIVATE).getInt("theme", 0);
        dark = appearance == 2 || appearance == 0 && (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        setTheme(dark ? R.style.WorkshopDark : R.style.WorkshopLight);
        ink = dark ? 0xffe6eef1 : 0xff17252c; muted = dark ? 0xff9fb3bb : 0xff5a6d76; teal = dark ? 0xff5fd4c4 : 0xff00796b;
        background = dark ? 0xff0e1417 : 0xfff2f5f6; surface = dark ? 0xff182227 : Color.WHITE; buttonColor = dark ? 0xff21343a : 0xffe2efed;
        Diagnostics.init(getFilesDir());
        folder = new File(getCacheDir(), "downloaded-models/browser-" + System.currentTimeMillis());
        site = valueOr(getIntent().getStringExtra(EXTRA_SITE), ModelSites.BROWSER_SITES[0]);
        term = valueOr(getIntent().getStringExtra(EXTRA_TERM), "");

        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(background); root.setFitsSystemWindows(true);
        setContentView(root);
        // Top: Done, where the page is (its host, so a look-alike sign-in page shows), More…; then the sites as tabs.
        LinearLayout top = new LinearLayout(this); top.setOrientation(LinearLayout.HORIZONTAL); top.setGravity(Gravity.CENTER_VERTICAL); top.setBackgroundColor(surface);
        root.addView(top, new LinearLayout.LayoutParams(-1, -2));
        Button close = flat(top, "‹ Done", this::finishWithModels); close.setContentDescription("Close the browser");
        where = new TextView(this); where.setTextSize(13); where.setTextColor(muted); where.setMaxLines(2);   // the whole host, wrapped rather than cut, so its real ending shows
        where.setGravity(Gravity.CENTER); top.addView(where, new LinearLayout.LayoutParams(0, -2, 1)); A11y.polite(where);
        flat(top, "More…", this::moreMenu).setContentDescription("More browser options");
        tabScroll = new HorizontalScrollView(this); tabScroll.setHorizontalScrollBarEnabled(false); tabScroll.setBackgroundColor(surface);
        tabs = new LinearLayout(this); tabs.setOrientation(LinearLayout.HORIZONTAL); tabs.setPadding(dp(8), 0, dp(8), dp(4)); tabScroll.addView(tabs);
        root.addView(tabScroll, new LinearLayout.LayoutParams(-1, -2));
        for (String s : ModelSites.BROWSER_SITES) {
            Button tab = flat(tabs, s, () -> openSite(s)); tab.setTag(s); tabButtons.add(tab);
        }
        loading = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); loading.setMax(100);
        root.addView(loading, new LinearLayout.LayoutParams(-1, dp(4)));

        web = new WebView(this);
        root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);           // the sites need it; no bridge to the app is added
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false); settings.setAllowContentAccess(false);
        settings.setGeolocationEnabled(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSupportMultipleWindows(false);     // a new window opens in place
        settings.setSafeBrowsingEnabled(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);   // sign-in pages on a sister domain (e.g. Prusa account)
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String scheme = request.getUrl().getScheme();
                if ("https".equalsIgnoreCase(scheme)) return false;
                if ("http".equalsIgnoreCase(scheme)) {   // upgrade, never load an unencrypted page
                    view.loadUrl(request.getUrl().buildUpon().scheme("https").build().toString()); return true;
                }
                return true;                              // intent:, market:, mailto: … are not followed
            }
            @Override public void onPageStarted(WebView view, String url, Bitmap favicon) { showWhere(url); }
            @Override public void doUpdateVisitedHistory(WebView view, String url, boolean reload) { showWhere(url); }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int progress) {
                loading.setProgress(progress); loading.setVisibility(progress >= 100 ? View.INVISIBLE : View.VISIBLE);
            }
        });
        web.setDownloadListener((url, userAgent, disposition, mimeType, length) -> startDownload(url, userAgent, disposition, mimeType, length));

        // Downloading, then the files ready for the slicer.
        downloadBar = bar(root);
        downloadText = label(downloadBar, "", 13, ink, false);
        downloadProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); downloadProgress.setMax(1000);
        downloadBar.addView(downloadProgress, new LinearLayout.LayoutParams(-1, dp(8)));
        rowButton(row(downloadBar), "Cancel download", () -> cancelDownload = true, false);
        readyBar = bar(root);
        readyText = A11y.polite(label(readyBar, "", 13, ink, false));
        LinearLayout readyRow = row(readyBar);
        rowButton(readyRow, "Clear", () -> { ready.clear(); showReady(); }, false);
        sliceButton = rowButton(readyRow, "Slice", this::finishWithModels, true);

        if (android.os.Build.VERSION.SDK_INT >= 33) {
            android.window.OnBackInvokedCallback callback = () -> { if (web.canGoBack()) web.goBack(); else finishWithModels(); };
            backCallback = callback;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback);
        }
        showReady();
        String url = getIntent().getStringExtra(EXTRA_URL);
        if (url != null && url.startsWith("https://")) { site = ""; highlightTab(); web.loadUrl(url); }
        else openSite(site);
    }

    @SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed() { if (web != null && web.canGoBack()) web.goBack(); else finishWithModels(); }

    @Override protected void onDestroy() {
        cancelDownload = true; worker.shutdownNow();
        if (android.os.Build.VERSION.SDK_INT >= 33 && backCallback != null) getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback((android.window.OnBackInvokedCallback) backCallback);
        if (web != null) { web.stopLoading(); web.destroy(); }
        CookieManager.getInstance().flush();
        super.onDestroy();
    }

    private void openSite(String name) {
        site = name; highlightTab();
        String url = ModelSites.webSearch(name, term); showWhere(url); web.loadUrl(url);
    }
    private void highlightTab() {
        for (Button tab : tabButtons) {
            boolean on = tab.getTag().equals(site);
            tab.setTextColor(on ? (dark ? 0xff0e1417 : Color.WHITE) : teal);
            tab.setBackgroundTintList(ColorStateList.valueOf(on ? teal : Color.TRANSPARENT));
            tab.setSelected(on);
            tab.setContentDescription(tab.getTag() + (on ? ", showing" : ""));
            if (on) tabScroll.post(() -> tabScroll.smoothScrollTo(Math.max(0, tab.getLeft() - dp(48)), 0));
        }
    }
    private void showWhere(String url) {
        try {
            Uri uri = Uri.parse(url);
            boolean secure = "https".equalsIgnoreCase(uri.getScheme());
            String host = valueOr(uri.getHost(), url); if (host.startsWith("www.")) host = host.substring(4);
            where.setText((secure ? "🔒 " : "⚠ ") + host);
            where.setContentDescription((secure ? "Secure page on " : "Page on ") + valueOr(uri.getHost(), "an unknown site"));
        } catch (Exception unreadable) { where.setText(""); }
    }

    private void moreMenu() {
        String[] items = {"Reload", "Open this page in the phone's browser", "Sign out of all sites (clears their cookies)"};
        new AlertDialog.Builder(this).setTitle("Browser").setItems(items, (d, which) -> {
            if (which == 0) web.reload();
            else if (which == 1) {
                String url = web.getUrl();
                if (url == null || !url.startsWith("https://")) return;
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
                catch (android.content.ActivityNotFoundException none) { toast("No other browser is installed."); }
            } else new AlertDialog.Builder(this).setTitle("Sign out of all sites?")
                .setMessage("Clears every model site's cookies and saved data in this app's browser. You will need to sign in again.")
                .setNegativeButton("Cancel", null).setPositiveButton("Sign out", (d2, w2) -> {
                    CookieManager.getInstance().removeAllCookies(null); CookieManager.getInstance().flush();
                    WebStorage.getInstance().deleteAllData(); web.clearCache(true); web.reload();
                }).show();
        }).show();
    }

    // ------------------------------------------------------------------ downloads
    private void startDownload(String url, String userAgent, String disposition, String mimeType, long length) {
        if (downloading) { toast("One download at a time: wait for this one to finish."); return; }
        String scheme = url == null ? "" : Uri.parse(url).getScheme();
        boolean blob = "blob".equalsIgnoreCase(scheme), data = "data".equalsIgnoreCase(scheme);
        if (!blob && !data && !"https".equalsIgnoreCase(scheme)) { explain("Download not taken", "The site offered this file over an address that is not secure, so it was not downloaded."); return; }
        String name = ModelSites.fileNameFor(blob || data ? null : url, disposition, mimeType);
        if (!ModelSites.browserTakes(name)) {
            explain("Not a model file", "\"" + name + "\" is not a model. The app takes STL, 3MF, OBJ, STEP and AMF files, and ZIPs of them.");
            return;
        }
        if (length > ModelSites.MAX_DOWNLOAD || blob && length > MAX_BLOB) { explain("File too large", "The file is larger than " + (blob ? "50" : "200") + " MB."); return; }
        if (!folder.isDirectory() && !folder.mkdirs()) { explain("Download stopped", "Could not make room for the download."); return; }
        File target = unique(new File(folder, name));
        downloading = true; cancelDownload = false;
        downloadText.setText("Downloading " + name + "…"); downloadProgress.setProgress(0); downloadBar.setVisibility(View.VISIBLE);
        Diagnostics.note(Diagnostics.SITES, "browser download started (" + (blob ? "page data" : data ? "inline data" : "link") + ", type " + valueOr(ModelSites.extension(name), "?") + ")");
        if (blob) { readBlob(url, target); return; }
        String page = web.getUrl();
        worker.execute(() -> {
            try {
                if (data) writeDataUrl(url, target);
                else ModelSites.download(u -> (HttpURLConnection) u.openConnection(), url, (hop, connection) -> {
                    // The browser's own cookies for this address (as the browser itself would send), its user agent, and the
                    // page's origin as referrer; nothing else, and nothing kept by the app.
                    String cookies = CookieManager.getInstance().getCookie(hop.toString());
                    if (cookies != null && !cookies.isEmpty()) connection.setRequestProperty("Cookie", cookies);
                    if (userAgent != null) connection.setRequestProperty("User-Agent", userAgent);
                    String origin = origin(page); if (!origin.isEmpty()) connection.setRequestProperty("Referer", origin);
                }, target, (done, total) -> { progress(done, total > 0 ? total : length); return !cancelDownload; });
                main.post(() -> finished(target, null));
            } catch (Exception failed) {
                target.delete();
                String why = failed instanceof InterruptedIOException ? "Download cancelled." : failed.getMessage() == null ? "The download failed." : failed.getMessage();
                main.post(() -> finished(null, why));
            }
        });
    }

    /**
     * A page-made (blob:) file exists only inside the page, so the page is asked to read it, and the app copies it out in
     * pieces through evaluateJavascript: no bridge object is ever exposed to the page.
     */
    private void readBlob(String url, File target) {
        String js = "(function(u){window.__lwBlob=null;window.__lwErr=null;fetch(u).then(function(r){return r.blob();}).then(function(b){"
            + "if(b.size>" + MAX_BLOB + "){window.__lwErr='large';return;}var f=new FileReader();f.onload=function(){var s=f.result;window.__lwBlob=s.substring(s.indexOf(',')+1);};"
            + "f.onerror=function(){window.__lwErr='read';};f.readAsDataURL(b);}).catch(function(){window.__lwErr='fetch';});})(" + org.json.JSONObject.quote(url) + ")";
        web.evaluateJavascript(js, null);
        pollBlob(target, System.currentTimeMillis() + 60_000);
    }
    private void pollBlob(File target, long deadline) {
        if (cancelDownload) { cleanBlob(); finished(null, "Download cancelled."); return; }
        web.evaluateJavascript("window.__lwErr?('E'+window.__lwErr):(window.__lwBlob===null?'':String(window.__lwBlob.length))", value -> {
            String state = unquote(value);
            if (state.startsWith("E")) { cleanBlob(); finished(null, state.equals("Elarge") ? "The file is larger than 50 MB." : "The page's file could not be read."); return; }
            if (state.isEmpty()) {
                if (System.currentTimeMillis() > deadline) { cleanBlob(); finished(null, "The page did not hand over the file."); return; }
                main.postDelayed(() -> pollBlob(target, deadline), 200); return;
            }
            long total;
            try { total = Long.parseLong(state); } catch (NumberFormatException odd) { cleanBlob(); finished(null, "The page's file could not be read."); return; }
            try { copyBlob(target, new FileOutputStream(target), 0, total); }
            catch (IOException failed) { cleanBlob(); finished(null, "Could not save the file."); }
        });
    }
    private void copyBlob(File target, OutputStream out, long at, long total) {
        if (cancelDownload || at >= total) {
            try { out.close(); } catch (IOException ignored) { }
            cleanBlob();
            if (cancelDownload) { target.delete(); finished(null, "Download cancelled."); } else finished(target, null);
            return;
        }
        web.evaluateJavascript("window.__lwBlob?window.__lwBlob.substr(" + at + "," + BLOB_CHUNK + "):''", value -> {
            String piece = unquote(value);
            try {
                if (piece.isEmpty()) throw new IOException("The page dropped the file.");
                out.write(Base64Holder.decode(piece));
                progress(at * 3 / 4, total * 3 / 4);
                copyBlob(target, out, at + piece.length(), total);
            } catch (Exception failed) {
                try { out.close(); } catch (IOException ignored) { }
                target.delete(); cleanBlob(); finished(null, "Could not save the file.");
            }
        });
    }
    private void cleanBlob() { if (web != null) web.evaluateJavascript("window.__lwBlob=null;window.__lwErr=null;", null); }
    private static final class Base64Holder { static byte[] decode(String text) { return java.util.Base64.getDecoder().decode(text); } }

    private static void writeDataUrl(String url, File target) throws IOException {
        int comma = url.indexOf(',');
        if (comma < 0) throw new IOException("The page's file could not be read.");
        String meta = url.substring(0, comma), body = url.substring(comma + 1);
        if (body.length() > MAX_BLOB * 4 / 3 + 4) throw new IOException("The file is larger than 50 MB.");
        byte[] bytes = meta.endsWith(";base64") ? java.util.Base64.getDecoder().decode(body) : java.net.URLDecoder.decode(body.replace("+", "%2B"), "UTF-8").getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        try (OutputStream out = new FileOutputStream(target)) { out.write(bytes); }
    }

    private void progress(long done, long total) {
        int value = total > 0 ? (int) Math.min(1000, done * 1000 / total) : 0;
        String text = total > 0 ? String.format(Locale.getDefault(), "%.1f of %.1f MB", done / 1048576.0, total / 1048576.0) : String.format(Locale.getDefault(), "%.1f MB", done / 1048576.0);
        main.post(() -> { downloadProgress.setIndeterminate(total <= 0); downloadProgress.setProgress(value); downloadProgress.setContentDescription(text); });
    }

    /** After a download: name it by what it really is, unpack a ZIP, and add the models to the list for the slicer. */
    private void finished(File file, String problem) {
        downloading = false; downloadBar.setVisibility(View.GONE);
        if (isDestroyed()) return;
        if (file == null) { if (!"Download cancelled.".equals(problem)) explain("Download stopped", problem); return; }
        worker.execute(() -> {
            List<File> models = new ArrayList<>(); String why = null;
            try {
                File named = file;
                String kind = ModelSites.sniff(file), ext = ModelSites.extension(file.getName());
                if (!kind.isEmpty() && !ext.equals("zip") && !ModelSites.MODEL_TYPES.contains(ext)) {   // the site's own model name is trusted
                    String base = file.getName(); int dot = base.lastIndexOf('.'); if (dot > 0) base = base.substring(0, dot);
                    named = unique(new File(folder, base + "." + kind));
                    if (!file.renameTo(named)) named = file;
                }
                String type = ModelSites.extension(named.getName());
                if (type.equals("zip")) {
                    File unpackTo = new File(folder, "zip-" + System.nanoTime()); unpackTo.mkdirs();
                    models.addAll(ModelSites.unzipModels(named, unpackTo)); named.delete();
                    if (models.isEmpty()) why = "The ZIP has no STL, 3MF, OBJ, STEP or AMF file in it.";
                } else if (ModelSites.MODEL_TYPES.contains(type)) models.add(named);
                else { named.delete(); why = "The file is not a model the slicer can open."; }
            } catch (Exception failed) { file.delete(); why = failed.getMessage() == null ? "The file could not be opened." : failed.getMessage(); }
            String problemText = why;
            Diagnostics.note(Diagnostics.SITES, "browser download " + (why == null ? "gave " + models.size() + " model file(s)" : "not used: " + why));
            main.post(() -> {
                if (isDestroyed()) return;
                if (problemText != null) { explain("Not added", problemText); return; }
                for (File m : models) ready.add(m.getAbsolutePath());
                showReady();
            });
        });
    }

    private void showReady() {
        readyBar.setVisibility(ready.isEmpty() ? View.GONE : View.VISIBLE);
        if (ready.isEmpty()) return;
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < ready.size() && i < 3; i++) names.append(i == 0 ? "" : ", ").append(new File(ready.get(i)).getName());
        if (ready.size() > 3) names.append(" and ").append(ready.size() - 3).append(" more");
        readyText.setText((ready.size() == 1 ? "1 model ready: " : ready.size() + " models ready: ") + names);
        sliceButton.setText(ready.size() == 1 ? "Slice it" : "Slice all " + ready.size());
    }

    private void finishWithModels() {
        if (downloading) { toast("Wait for the download to finish, or cancel it."); return; }
        if (!ready.isEmpty()) setResult(RESULT_OK, new Intent().putStringArrayListExtra(RESULT_PATHS, ready));
        finish();
    }

    // ------------------------------------------------------------------ helpers
    private static File unique(File file) {
        if (!file.exists()) return file;
        String name = file.getName(); int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name, ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; ; i++) { File next = new File(file.getParentFile(), base + " (" + i + ")" + ext); if (!next.exists()) return next; }
    }
    private static String origin(String url) {
        try { Uri uri = Uri.parse(url); return "https".equals(uri.getScheme()) && uri.getHost() != null ? "https://" + uri.getHost() + "/" : ""; } catch (Exception none) { return ""; }
    }
    private static String unquote(String value) {
        if (value == null || value.equals("null")) return "";
        try { return new org.json.JSONArray("[" + value + "]").optString(0, ""); } catch (Exception odd) { return ""; }
    }
    private static String valueOr(String value, String fallback) { return value == null || value.isEmpty() ? fallback : value; }
    private void explain(String title, String message) { if (!isDestroyed()) new AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("OK", null).show(); }
    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_SHORT).show(); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView label(LinearLayout parent, String text, int size, int color, boolean bold) {
        TextView view = new TextView(this); view.setText(text); view.setTextSize(size); view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        parent.addView(view, new LinearLayout.LayoutParams(-1, -2)); return view;
    }
    private LinearLayout bar(LinearLayout parent) {
        LinearLayout bar = new LinearLayout(this); bar.setOrientation(LinearLayout.VERTICAL); bar.setPadding(dp(16), dp(8), dp(16), dp(8)); bar.setBackgroundColor(surface);
        bar.setVisibility(View.GONE); parent.addView(bar, new LinearLayout.LayoutParams(-1, -2)); return bar;
    }
    private LinearLayout row(LinearLayout parent) {
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.topMargin = dp(6); parent.addView(row, params); return row;
    }
    private Button flat(LinearLayout parent, String text, Runnable action) {
        Button button = new Button(this); button.setText(text); button.setAllCaps(false); button.setTextColor(teal); button.setMinHeight(dp(48)); button.setMinWidth(0);
        GradientDrawable shape = new GradientDrawable(); shape.setCornerRadius(dp(18)); shape.setColor(Color.WHITE); button.setBackground(shape);
        button.setBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT)); button.setPadding(dp(12), 0, dp(12), 0);
        button.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, dp(48)); params.setMargins(dp(2), dp(4), dp(2), dp(4)); parent.addView(button, params);
        return button;
    }
    private Button rowButton(LinearLayout row, String text, Runnable action, boolean primary) {
        Button button = new Button(this); button.setText(text); button.setAllCaps(false); button.setMinHeight(dp(48));
        button.setTextColor(primary ? (dark ? 0xff0e1417 : Color.WHITE) : teal);
        GradientDrawable shape = new GradientDrawable(); shape.setCornerRadius(dp(16)); shape.setColor(primary ? teal : buttonColor); button.setBackground(shape);
        button.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1); if (row.getChildCount() > 0) params.leftMargin = dp(8);
        row.addView(button, params); return button;
    }
}
