package io.github.thelastfrogrammer.elink;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/**
 * Find models: searches Thingiverse and MyMiniFactory through their official APIs with the user's own token and key, lists
 * the results together, downloads a Thingiverse file straight into the Slice screen, and opens other sites' searches in the
 * in-app browser (ModelBrowserActivity), whose downloads come straight back. Returns the downloaded files' paths to the Slice screen.
 */
public final class ModelSearchActivity extends Activity {
    static final String RESULT_PATHS = "paths";
    private static final int BROWSE = 1;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newFixedThreadPool(3);
    private boolean dark;
    private int ink, muted, teal, background, surface, buttonColor;
    private EditText query;
    private TextView status;
    private LinearLayout results;
    private Button more;
    private SiteKeys keys;
    private String lastTerm = "";
    private int page = 1, generation;
    private volatile boolean cancelDownload;
    private final Set<String> shapesLogged = new HashSet<>();

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        int appearance = getSharedPreferences("workshop-settings", MODE_PRIVATE).getInt("theme", 0);
        dark = appearance == 2 || appearance == 0 && (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        setTheme(dark ? R.style.WorkshopDark : R.style.WorkshopLight);
        ink = dark ? 0xffe6eef1 : 0xff17252c; muted = dark ? 0xff9fb3bb : 0xff5a6d76; teal = dark ? 0xff5fd4c4 : 0xff00796b;
        background = dark ? 0xff0e1417 : 0xfff2f5f6; surface = dark ? 0xff182227 : Color.WHITE; buttonColor = dark ? 0xff21343a : 0xffe2efed;
        Diagnostics.init(getFilesDir());
        keys = new SiteKeys(this);
        ScrollView scroll = new ScrollView(this); scroll.setBackgroundColor(background); scroll.setFitsSystemWindows(true);
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(dp(16), dp(12), dp(16), dp(24)); scroll.addView(content);
        setContentView(scroll);

        LinearLayout header = new LinearLayout(this); header.setOrientation(LinearLayout.HORIZONTAL); header.setGravity(Gravity.CENTER_VERTICAL); content.addView(header);
        Button back = new Button(this); back.setText("‹ Back"); back.setAllCaps(false); back.setTextColor(teal); back.setBackground(null); back.setMinHeight(dp(48)); back.setOnClickListener(v -> finish());
        header.addView(back);
        TextView title = label(header, "Find models", 20, ink, true); A11y.heading(title);
        ((LinearLayout.LayoutParams) title.getLayoutParams()).weight = 1; title.getLayoutParams().width = 0;

        query = new EditText(this); query.setHint("What to print, e.g. cable clip"); query.setSingleLine(true); query.setTextColor(ink); query.setHintTextColor(muted);
        query.setInputType(InputType.TYPE_CLASS_TEXT); query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        query.setOnEditorActionListener((v, action, event) -> { if (action == EditorInfo.IME_ACTION_SEARCH) { search(1); return true; } return false; });
        content.addView(query, new LinearLayout.LayoutParams(-1, dp(56)));
        LinearLayout searchRow = row(content);
        rowButton(searchRow, "Search", () -> search(1), true);
        rowButton(searchRow, "Site keys…", this::keysDialog, false);
        status = A11y.polite(label(content, "", 13, muted, false));

        label(content, "Browse the sites", 13, ink, true);
        label(content, "Each site opens here with your search. Sign in on the site as usual; files you download come straight to the slicer.", 12, muted, false);
        // Three and two to a row; one to a row with large text, so no site name breaks mid-word.
        boolean large = getResources().getConfiguration().fontScale >= 1.3f;
        LinearLayout sites = row(content);
        for (int i = 0; i < ModelSites.BROWSER_SITES.length; i++) {
            String site = ModelSites.BROWSER_SITES[i];
            if (i > 0 && (large || i == 3)) sites = row(content);
            rowButton(sites, site, () -> startActivityForResult(new Intent(this, ModelBrowserActivity.class)
                .putExtra(ModelBrowserActivity.EXTRA_SITE, site).putExtra(ModelBrowserActivity.EXTRA_TERM, query.getText().toString().trim()), BROWSE), false);
        }
        results = new LinearLayout(this); results.setOrientation(LinearLayout.VERTICAL); content.addView(results);
        more = button(content, "More results", () -> search(page + 1)); more.setVisibility(View.GONE);
        label(content, "Models belong to their designers: check each model's licence on its page before printing or sharing it.", 12, muted, false);
        showStatus();
    }

    @Override protected void onDestroy() { cancelDownload = true; worker.shutdownNow(); super.onDestroy(); }

    private void showStatus() {
        boolean thingiverse = keys.has(SiteKeys.THINGIVERSE), mmf = keys.has(SiteKeys.MYMINIFACTORY);
        status.setText(thingiverse || mmf ? "Searches " + (thingiverse && mmf ? "Thingiverse and MyMiniFactory" : thingiverse ? "Thingiverse" : "MyMiniFactory") + " here."
            : "To search here, add your own free Thingiverse app token or MyMiniFactory API key (Site keys…). Without one, browse the sites below.");
    }

    // ------------------------------------------------------------------ searching
    private void search(int nextPage) {
        String term = query.getText().toString().trim();
        if (term.isEmpty()) { status.setText("Type what you are looking for first."); return; }
        String thingToken = keys.load(SiteKeys.THINGIVERSE), mmfKey = keys.load(SiteKeys.MYMINIFACTORY);
        if (thingToken.isEmpty() && mmfKey.isEmpty()) { keysDialog(); return; }
        if (nextPage == 1) { results.removeAllViews(); lastTerm = term; }
        page = nextPage; int mine = ++generation;
        status.setText("Searching…"); more.setVisibility(View.GONE);
        worker.execute(() -> {
            List<ModelSites.Model> found = new ArrayList<>(); List<String> problems = new ArrayList<>();
            // One site after the other on this thread (thumbnails share the pool, so nothing here waits on it).
            if (!thingToken.isEmpty()) try { found.addAll(find(ModelSites.THINGIVERSE, ModelSites.thingiverseSearch(term, nextPage), thingToken)); }
                catch (Exception failed) { problems.add("Thingiverse: " + failed.getMessage()); }
            if (!mmfKey.isEmpty()) try { found.addAll(find(ModelSites.MYMINIFACTORY, ModelSites.mmfSearch(term, nextPage, mmfKey), "")); }
                catch (Exception failed) { problems.add("MyMiniFactory: " + failed.getMessage()); }
            List<ModelSites.Model> merged = mixSources(found);
            main.post(() -> {
                if (mine != generation || isDestroyed()) return;
                for (ModelSites.Model model : merged) results.addView(card(model));
                status.setText((merged.isEmpty() ? "Nothing found" + (nextPage > 1 ? " on this page." : ".") : merged.size() + " found" + (nextPage > 1 ? " on page " + nextPage : "") + ".")
                    + (problems.isEmpty() ? "" : " " + String.join(" ", problems)));
                more.setVisibility(merged.isEmpty() ? View.GONE : View.VISIBLE);
            });
        });
    }
    private List<ModelSites.Model> find(String site, String address, String token) throws Exception {
        String body = ModelSites.getJson(this::open, address, token);
        List<ModelSites.Model> list = ModelSites.THINGIVERSE.equals(site) ? ModelSites.parseThingiverseSearch(body) : ModelSites.parseMmfSearch(body);
        logShape(site + " search", body);
        return list;
    }
    /** Results from each site taken in turn, so one site does not push the other below the fold. */
    static List<ModelSites.Model> mixSources(List<ModelSites.Model> all) {
        Map<String, Deque<ModelSites.Model>> bySite = new LinkedHashMap<>();
        for (ModelSites.Model model : all) bySite.computeIfAbsent(model.source, k -> new ArrayDeque<>()).add(model);
        List<ModelSites.Model> mixed = new ArrayList<>();
        while (mixed.size() < all.size()) for (Deque<ModelSites.Model> queue : bySite.values()) if (!queue.isEmpty()) mixed.add(queue.poll());
        return mixed;
    }
    /** Field names (never values) of a site's first answer, so a changed answer shows up in Share diagnostics. */
    private void logShape(String what, String body) {
        synchronized (shapesLogged) { if (!shapesLogged.add(what)) return; }
        try {
            Object root = new org.json.JSONTokener(body).nextValue();
            JSONObject object = root instanceof JSONObject ? (JSONObject) root : ((org.json.JSONArray) root).optJSONObject(0);
            Diagnostics.note(Diagnostics.SITES, what + " fields: " + (object == null ? "empty" : PrinterProbe.shape(object)));
        } catch (Exception unreadable) { Diagnostics.note(Diagnostics.SITES, what + ": answer was not JSON"); }
    }

    // ------------------------------------------------------------------ results
    private View card(ModelSites.Model model) {
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.HORIZONTAL); card.setPadding(dp(12), dp(12), dp(12), dp(12));
        GradientDrawable shape = new GradientDrawable(); shape.setColor(surface); shape.setCornerRadius(dp(16)); card.setBackground(shape);
        LinearLayout.LayoutParams outer = new LinearLayout.LayoutParams(-1, -2); outer.topMargin = dp(10); card.setLayoutParams(outer);
        ImageView picture = new ImageView(this); picture.setScaleType(ImageView.ScaleType.CENTER_CROP); picture.setBackgroundColor(buttonColor);
        picture.setContentDescription(null); picture.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        card.addView(picture, new LinearLayout.LayoutParams(dp(88), dp(88)));
        if (!model.thumbnail.isEmpty()) loadThumbnail(model.thumbnail, picture);
        LinearLayout text = new LinearLayout(this); text.setOrientation(LinearLayout.VERTICAL); text.setPadding(dp(12), 0, 0, 0);
        card.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        TextView name = label(text, model.name, 15, ink, true); name.setMaxLines(3);
        label(text, (model.creator.isEmpty() ? "" : "by " + model.creator + " · ") + model.source + (model.license.isEmpty() ? "" : " · " + model.license), 12, muted, false);
        LinearLayout actions = new LinearLayout(this); actions.setOrientation(LinearLayout.HORIZONTAL); text.addView(actions);
        if (ModelSites.THINGIVERSE.equals(model.source)) rowButton(actions, "Files…", () -> filesDialog(model), true);
        if (!model.page.isEmpty()) rowButton(actions, ModelSites.MYMINIFACTORY.equals(model.source) ? "Open to download" : "Open page", () -> openBrowser(model.page), false);
        return card;
    }

    private void filesDialog(ModelSites.Model model) {
        String token = keys.load(SiteKeys.THINGIVERSE);
        AlertDialog waiting = new AlertDialog.Builder(this).setTitle(model.name).setMessage("Getting the file list…").setNegativeButton("Cancel", null).show();
        worker.execute(() -> {
            List<ModelSites.ModelFile> files; String problem = null;
            try { String body = ModelSites.getJson(this::open, ModelSites.thingiverseFiles(model.id), token); logShape("Thingiverse files", body); files = ModelSites.parseThingiverseFiles(body); }
            catch (Exception failed) { files = new ArrayList<>(); problem = failed.getMessage(); }
            List<ModelSites.ModelFile> list = files; String why = problem;
            main.post(() -> {
                if (isDestroyed() || !waiting.isShowing()) return;
                waiting.dismiss();
                List<ModelSites.ModelFile> printable = new ArrayList<>();
                for (ModelSites.ModelFile file : list) if (file.printable()) printable.add(file);
                if (printable.isEmpty()) {
                    new AlertDialog.Builder(this).setTitle(model.name).setMessage(why != null ? "The file list could not be read: " + why
                        : "No STL, 3MF, OBJ or STEP file is listed for this model. Its page may have more.").setPositiveButton("Open page", (d, w) -> openBrowser(model.page)).setNegativeButton("Close", null).show();
                    return;
                }
                String[] names = new String[printable.size()]; boolean[] chosen = new boolean[printable.size()];
                for (int i = 0; i < names.length; i++) names[i] = printable.get(i).name + (printable.get(i).size > 0 ? "  ·  " + megabytes(printable.get(i).size) : "");
                if (names.length == 1) chosen[0] = true;
                new AlertDialog.Builder(this).setTitle("Files to slice").setMultiChoiceItems(names, chosen, (d, which, on) -> chosen[which] = on)
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Download and slice", (d, w) -> {
                        List<ModelSites.ModelFile> pick = new ArrayList<>(); for (int i = 0; i < chosen.length; i++) if (chosen[i]) pick.add(printable.get(i));
                        if (!pick.isEmpty()) download(model, pick, token);
                    }).show();
            });
        });
    }

    private void download(ModelSites.Model model, List<ModelSites.ModelFile> files, String token) {
        cancelDownload = false;
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); bar.setMax(1000); bar.setPadding(dp(20), dp(8), dp(20), dp(8));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Downloading " + model.name).setView(bar).setNegativeButton("Cancel", (d, w) -> cancelDownload = true).setCancelable(false).show();
        File folder = new File(getCacheDir(), "downloaded-models/" + model.source.toLowerCase(Locale.ROOT) + "-" + model.id);
        worker.execute(() -> {
            ArrayList<String> paths = new ArrayList<>(); String problem = null;
            try {
                if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("Could not make room for the download.");
                for (int i = 0; i < files.size(); i++) {
                    ModelSites.ModelFile file = files.get(i); int index = i;
                    File target = new File(folder, ModelSites.safeName(file.name));
                    ModelSites.download(this::open, file.url, token, target, (done, total) -> {
                        long known = total > 0 ? total : file.size;
                        int value = known > 0 ? (int) ((index + Math.min(1.0, done / (double) known)) * 1000 / files.size()) : 0;
                        main.post(() -> bar.setProgress(value));
                        return !cancelDownload;
                    });
                    paths.add(target.getAbsolutePath());
                }
                Diagnostics.note(Diagnostics.SITES, "downloaded " + paths.size() + " file(s) from " + model.source);
            } catch (Exception failed) { problem = failed.getMessage() == null ? "The download failed." : failed.getMessage(); }
            String why = problem;
            main.post(() -> {
                if (isDestroyed()) return;
                dialog.dismiss();
                if (why != null) { new AlertDialog.Builder(this).setTitle("Download stopped").setMessage(why).setPositiveButton("OK", null).show(); return; }
                setResult(RESULT_OK, new Intent().putStringArrayListExtra(RESULT_PATHS, paths));
                finish();
            });
        });
    }

    // ------------------------------------------------------------------ site keys
    private void keysDialog() {
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(dp(20), dp(8), dp(20), 0);
        label(body, "Searching here uses each site's official API with your own free key. They are stored encrypted on this phone and sent only to their own site.", 13, ink, false);
        label(body, "Thingiverse app token: sign in at thingiverse.com, open thingiverse.com/developers, create an app, and copy its App Token.", 12, muted, false);
        EditText thingiverse = secret(body, keys.has(SiteKeys.THINGIVERSE) ? "Saved — type to replace, or clear below" : "Thingiverse app token");
        label(body, "MyMiniFactory API key: from MyMiniFactory's developer page (myminifactory.com, For developers). Its downloads need a MyMiniFactory login, so its models open on its site.", 12, muted, false);
        EditText mmf = secret(body, keys.has(SiteKeys.MYMINIFACTORY) ? "Saved — type to replace, or clear below" : "MyMiniFactory API key");
        new AlertDialog.Builder(this).setTitle("Site keys").setView(body)
            .setNeutralButton("Clear both", (d, w) -> { try { keys.save(SiteKeys.THINGIVERSE, ""); keys.save(SiteKeys.MYMINIFACTORY, ""); } catch (Exception ignored) { } showStatus(); })
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", (d, w) -> {
                try {
                    if (thingiverse.length() > 0) keys.save(SiteKeys.THINGIVERSE, thingiverse.getText().toString());
                    if (mmf.length() > 0) keys.save(SiteKeys.MYMINIFACTORY, mmf.getText().toString());
                } catch (Exception bad) { status.setText(bad instanceof IllegalArgumentException ? bad.getMessage() : "The key could not be saved."); return; }
                showStatus();
            }).show();
    }
    private EditText secret(LinearLayout parent, String hint) {
        EditText field = new EditText(this); field.setHint(hint); field.setSingleLine(true); field.setTextColor(ink); field.setHintTextColor(muted);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        parent.addView(field, new LinearLayout.LayoutParams(-1, dp(56))); return field;
    }

    // ------------------------------------------------------------------ helpers
    private HttpURLConnection open(URL url) throws IOException { return (HttpURLConnection) url.openConnection(); }
    /** Opens a model's page in the in-app browser, where its downloads come straight to the slicer. */
    private void openBrowser(String address) {
        if (!address.startsWith("https://")) return;
        startActivityForResult(new Intent(this, ModelBrowserActivity.class).putExtra(ModelBrowserActivity.EXTRA_URL, address), BROWSE);
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == BROWSE && result == RESULT_OK && data != null && data.getStringArrayListExtra(RESULT_PATHS) != null) {
            setResult(RESULT_OK, new Intent().putStringArrayListExtra(RESULT_PATHS, data.getStringArrayListExtra(RESULT_PATHS)));
            finish();
        }
    }
    private void loadThumbnail(String address, ImageView into) {
        worker.execute(() -> {
            try {
                URL url = new URL(address); if (!"https".equals(url.getProtocol())) return;
                HttpURLConnection connection = open(url);
                try {
                    connection.setConnectTimeout(10_000); connection.setReadTimeout(15_000);
                    if (connection.getResponseCode() != 200 || connection.getContentLengthLong() > 3_000_000) return;
                    ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[16 * 1024]; int n;
                    try (InputStream in = connection.getInputStream()) { while ((n = in.read(buffer)) >= 0) { out.write(buffer, 0, n); if (out.size() > 3_000_000) return; } }
                    BitmapFactory.Options options = new BitmapFactory.Options(); options.inSampleSize = 2;
                    Bitmap bitmap = BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size(), options);
                    if (bitmap != null) main.post(() -> into.setImageBitmap(bitmap));
                } finally { connection.disconnect(); }
            } catch (Exception ignored) { }
        });
    }
    static String megabytes(long bytes) { return bytes < 1024 * 1024 ? Math.max(1, bytes / 1024) + " KB" : String.format(Locale.getDefault(), "%.1f MB", bytes / 1048576.0); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView label(LinearLayout parent, String text, int size, int color, boolean bold) {
        TextView view = new TextView(this); view.setText(text); view.setTextSize(size); view.setTextColor(color); view.setPadding(0, dp(6), 0, dp(6));
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        parent.addView(view, new LinearLayout.LayoutParams(-1, -2)); return view;
    }
    private LinearLayout row(LinearLayout parent) { LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); parent.addView(row, new LinearLayout.LayoutParams(-1, -2)); return row; }
    private Button button(LinearLayout parent, String text, Runnable action) {
        Button b = rowButton(row(parent), text, action, false); return b;
    }
    private Button rowButton(LinearLayout row, String text, Runnable action, boolean primary) {
        Button button = new A11y.DimButton(this); button.setText(text); button.setAllCaps(false); button.setTextSize(13);
        button.setMinHeight(dp(48)); button.setMinimumHeight(dp(48)); button.setPadding(dp(8), dp(4), dp(8), dp(4));
        button.setTextColor(primary ? (dark ? 0xff0e1417 : Color.WHITE) : teal);
        GradientDrawable shape = new GradientDrawable(); shape.setColor(primary ? teal : buttonColor); shape.setCornerRadius(dp(12)); button.setBackground(shape);
        if (primary) button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1); params.setMargins(row.getChildCount() == 0 ? 0 : dp(8), dp(6), 0, 0);
        row.addView(button, params); return button;
    }
}
