package io.github.thelastfrogrammer.elink;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.WebExtension;
import org.mozilla.geckoview.WebResponse;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The model sites, browsed inside the app with Firefox's engine (GeckoView), so the uBlock Origin add-on can block ads. The
 * user searches and signs in on each site's own pages (the app never sees a password). When a site starts a download, the
 * engine hands the file here instead of the phone's Downloads: model files and ZIPs of them only, from secure pages, at
 * most 200 MB. The downloaded models go back to the Slice screen. Nothing on these pages can reach the app or the printer:
 * no bridge to the app, no file uploads, no device permissions, and other apps' links (intent:, market:) are not followed.
 */
public final class ModelBrowserActivity extends Activity {
    static final String EXTRA_SITE = "site", EXTRA_TERM = "term", EXTRA_URL = "url";
    static final String RESULT_PATHS = ModelSearchActivity.RESULT_PATHS;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private boolean dark;
    private int ink, muted, teal, background, surface, buttonColor;
    private View page;                 // the GeckoView, or a placeholder where the engine cannot run (unit tests)
    private GeckoRuntime runtime;
    private GeckoSession session;
    private String currentUrl = "";
    private boolean canGoBack;
    private WebExtension adBlocker;
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

        if (GeckoEngine.available()) {
            runtime = GeckoEngine.runtime(this, dark);
            session = new GeckoSession(new GeckoSessionSettings.Builder().usePrivateMode(false).useTrackingProtection(true)
                .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE).build());
            session.setNavigationDelegate(navigation());
            session.setProgressDelegate(progressDelegate());
            session.setContentDelegate(contentDelegate());
            session.setPromptDelegate(new BrowserPrompts(this));
            session.open(runtime);
            GeckoView view = new GeckoView(this); view.setSession(session); page = view;
            refreshAdBlocker(true);
        } else { page = new View(this); page.setBackgroundColor(background); }
        root.addView(page, new LinearLayout.LayoutParams(-1, 0, 1));

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
            android.window.OnBackInvokedCallback callback = this::goBack;
            backCallback = callback;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback);
        }
        showReady();
        String url = getIntent().getStringExtra(EXTRA_URL);
        if (url != null && url.startsWith("https://")) { site = ""; highlightTab(); load(url); }
        else openSite(site);
    }

    @SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed() { goBack(); }
    private void goBack() { if (session != null && canGoBack) session.goBack(); else finishWithModels(); }

    @Override protected void onDestroy() {
        cancelDownload = true; worker.shutdownNow();
        if (android.os.Build.VERSION.SDK_INT >= 33 && backCallback != null) getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback((android.window.OnBackInvokedCallback) backCallback);
        if (page instanceof GeckoView) ((GeckoView) page).releaseSession();
        if (session != null) session.close();
        super.onDestroy();
    }

    private void load(String url) { currentUrl = url; showWhere(url); if (session != null) session.loadUri(url); }

    // ------------------------------------------------------------------ the engine's callbacks
    private GeckoSession.NavigationDelegate navigation() {
        return new GeckoSession.NavigationDelegate() {
            @Override public void onLocationChange(GeckoSession s, String url, List<GeckoSession.PermissionDelegate.ContentPermission> perms, Boolean gesture) {
                if (url != null) { currentUrl = url; showWhere(url); }
            }
            @Override public void onCanGoBack(GeckoSession s, boolean can) { canGoBack = can; }
            @Override public GeckoResult<AllowOrDeny> onLoadRequest(GeckoSession s, LoadRequest request) {
                if (!ModelSites.browserMayOpen(request.uri)) {
                    if (request.uri != null && request.uri.startsWith("http://")) main.post(() -> load("https://" + request.uri.substring(7)));   // upgrade
                    return GeckoResult.deny();
                }
                if (request.target == TARGET_WINDOW_NEW) { main.post(() -> load(request.uri)); return GeckoResult.deny(); }   // one tab: open it here
                return GeckoResult.allow();
            }
            @Override public GeckoResult<AllowOrDeny> onSubframeLoadRequest(GeckoSession s, LoadRequest request) {
                return ModelSites.browserMayOpen(request.uri) ? GeckoResult.allow() : GeckoResult.deny();
            }
            @Override public GeckoResult<GeckoSession> onNewSession(GeckoSession s, String uri) { return null; }
        };
    }
    private GeckoSession.ProgressDelegate progressDelegate() {
        return new GeckoSession.ProgressDelegate() {
            @Override public void onProgressChange(GeckoSession s, int progress) {
                loading.setProgress(progress); loading.setVisibility(progress >= 100 ? View.INVISIBLE : View.VISIBLE);
            }
            @Override public void onPageStop(GeckoSession s, boolean success) { loading.setVisibility(View.INVISIBLE); }
        };
    }
    private GeckoSession.ContentDelegate contentDelegate() {
        return new GeckoSession.ContentDelegate() {
            @Override public void onExternalResponse(GeckoSession s, WebResponse response) { takeDownload(response); }
            @Override public void onCrash(GeckoSession s) { restart("The page crashed."); }
            @Override public void onKill(GeckoSession s) { restart("The page was closed to free memory."); }
        };
    }
    /** After a page crash: a fresh session at the same address. */
    private void restart(String why) {
        if (isDestroyed() || runtime == null) return;
        toast(why + " Reloading.");
        if (session != null) session.close();
        session = new GeckoSession(new GeckoSessionSettings.Builder().usePrivateMode(false).useTrackingProtection(true).build());
        session.setNavigationDelegate(navigation()); session.setProgressDelegate(progressDelegate());
        session.setContentDelegate(contentDelegate()); session.setPromptDelegate(new BrowserPrompts(this));
        session.open(runtime); ((GeckoView) page).setSession(session);
        load(currentUrl.startsWith("https://") ? currentUrl : ModelSites.webSearch(site.isEmpty() ? ModelSites.BROWSER_SITES[0] : site, term));
    }

    private void openSite(String name) {
        site = name; highlightTab();
        load(ModelSites.webSearch(name, term));
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
        List<String> items = new ArrayList<>(); List<Runnable> actions = new ArrayList<>();
        items.add("Reload"); actions.add(() -> { if (session != null) session.reload(); });
        items.add(adBlocker == null ? "Install the ad blocker (" + GeckoEngine.AD_BLOCKER_NAME + ")…"
            : adBlocker.metaData.enabled ? "Ad blocker: on (tap to turn off)" : "Ad blocker: off (tap to turn on)");
        actions.add(this::adBlockerAction);
        items.add("Open this page in the phone's browser"); actions.add(() -> {
            if (!currentUrl.startsWith("https://")) return;
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl))); }
            catch (android.content.ActivityNotFoundException none) { toast("No other browser is installed."); }
        });
        items.add("Sign out of all sites"); actions.add(() -> new AlertDialog.Builder(this).setTitle("Sign out of all sites?")
            .setMessage("Clears every model site's cookies and saved data in this app's browser. You will need to sign in again. The ad blocker stays.")
            .setNegativeButton("Cancel", null).setPositiveButton("Sign out", (d2, w2) -> {
                if (runtime != null) GeckoEngine.signOutEverywhere(runtime).accept(done -> { if (session != null) session.reload(); });
            }).show());
        new AlertDialog.Builder(this).setTitle("Browser").setItems(items.toArray(new String[0]), (d, which) -> actions.get(which).run()).show();
    }

    // ------------------------------------------------------------------ ad blocker
    private void refreshAdBlocker(boolean checkUpdate) {
        if (runtime == null) return;
        GeckoEngine.adBlocker(runtime).accept(extension -> {
            adBlocker = extension;
            if (extension != null && checkUpdate) GeckoEngine.updateIfDue(this, runtime, extension);
        }, failed -> adBlocker = null);
    }
    private void adBlockerAction() {
        if (runtime == null) { toast("The browser engine is not running."); return; }
        if (adBlocker != null) {
            boolean on = !adBlocker.metaData.enabled;
            GeckoEngine.setAdBlocker(runtime, adBlocker, on).accept(extension -> {
                adBlocker = extension; toast("Ad blocker " + (on ? "on." : "off.")); if (session != null) session.reload();
            }, failed -> toast("Could not change the ad blocker."));
            return;
        }
        new AlertDialog.Builder(this).setTitle("Install the ad blocker?")
            .setMessage(GeckoEngine.AD_BLOCKER_NAME + " is a free, open-source ad blocker for Firefox by Raymond Hill. It is downloaded from "
                + "Mozilla's add-on site (addons.mozilla.org) and Firefox's engine checks Mozilla's signature before installing it. It works "
                + "only in this browser, can read and change the model sites' pages to remove ads (that is how it works), and is "
                + "updated from Mozilla about once a week. You can turn it off here at any time.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Install", (d, w) -> {
                toast("Installing the ad blocker…");
                GeckoEngine.installAdBlocker(runtime).accept(extension -> {
                    adBlocker = extension;
                    Diagnostics.note(Diagnostics.SITES, "ad blocker installed (version " + extension.metaData.version + ")");
                    toast("Ad blocker installed."); if (session != null) session.reload();
                }, failed -> {
                    Diagnostics.note(Diagnostics.SITES, "ad blocker install failed: " + failed.getClass().getSimpleName());
                    explain("Ad blocker not installed", "It could not be installed from Mozilla's add-on site. Check the connection and try again.");
                });
            }).show();
    }

    // ------------------------------------------------------------------ downloads
    /** A file the page started downloading, handed over by the engine (it has already fetched it, with the site's sign-in). */
    private void takeDownload(WebResponse response) {
        String disposition = header(response, "Content-Disposition"), type = header(response, "Content-Type");
        long length = -1; try { length = Long.parseLong(header(response, "Content-Length").trim()); } catch (NumberFormatException unknown) { }
        String name = ModelSites.fileNameFor(response.uri != null && response.uri.startsWith("https://") ? response.uri : null, disposition, type);
        String refusal = downloading ? "One download at a time: wait for this one to finish."
            : !ModelSites.browserDownloadFrom(response.uri) ? "The site offered this file over an address that is not secure, so it was not downloaded."
            : !ModelSites.browserTakes(name) ? "\"" + name + "\" is not a model. The app takes STL, 3MF, OBJ, STEP and AMF files, and ZIPs of them."
            : length > ModelSites.MAX_DOWNLOAD ? "The file is larger than 200 MB." : null;
        if (refusal == null && !folder.isDirectory() && !folder.mkdirs()) refusal = "Could not make room for the download.";
        if (refusal != null) {
            closeQuietly(response.body);
            if (downloading) toast(refusal); else explain("Download not taken", refusal);
            return;
        }
        File target = unique(new File(folder, name));
        long total = length;
        downloading = true; cancelDownload = false;
        downloadText.setText("Downloading " + name + "…"); downloadProgress.setProgress(0); downloadBar.setVisibility(View.VISIBLE);
        Diagnostics.note(Diagnostics.SITES, "browser download started (" + (response.uri != null && response.uri.startsWith("blob:") ? "page data" : "link") + ", type " + valueOr(ModelSites.extension(name), "?") + ")");
        response.setReadTimeoutMillis(30_000);
        worker.execute(() -> {
            try {
                if (response.body == null) throw new IOException("The site sent no file.");
                ModelSites.save(response.body, target, ModelSites.MAX_DOWNLOAD, total, (done, all) -> { progress(done, all); return !cancelDownload; });
                main.post(() -> finished(target, null));
            } catch (Exception failed) {
                String why = failed instanceof InterruptedIOException ? "Download cancelled." : failed.getMessage() == null ? "The download failed." : failed.getMessage();
                main.post(() -> finished(null, why));
            }
        });
    }
    private static String header(WebResponse response, String name) {
        if (response.headers == null) return "";
        for (Map.Entry<String, String> e : response.headers.entrySet()) if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) return e.getValue() == null ? "" : e.getValue();
        return "";
    }
    private static void closeQuietly(InputStream in) { try { if (in != null) in.close(); } catch (IOException ignored) { } }

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
