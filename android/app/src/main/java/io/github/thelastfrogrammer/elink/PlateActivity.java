package io.github.thelastfrogrammer.elink;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.webkit.*;
import android.widget.*;
import androidx.webkit.WebViewAssetLoader;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The plate before slicing (assets/plate): the models on the bed as the engine would place them, to move by dragging,
 * turn, scale, copy and remove. Returns the copies as EXTRA_PLACEMENTS (the engine's placement JSON).
 */
public final class PlateActivity extends Activity {
    static final String EXTRA_MODELS = "models", EXTRA_SELECTION = "selection", EXTRA_INSPECTED = "inspected",
        EXTRA_PLACEMENTS = "placements", EXTRA_COLOURS = "colours", EXTRA_SLOTS = "slots",
        /** Result: the Slice screen should open the settings for this object ({file, object}) after taking the layout. */
        EXTRA_EDIT_OBJECT = "editObject", EXTRA_EDIT_NAME = "editName";
    private static final String ORIGIN = "https://appassets.androidplatform.net";

    private final Handler main = new Handler(Looper.getMainLooper());
    private WorkshopUi ui;
    private WebView web;
    private TextView status, selectedLabel;
    private double scalePercent = 100;
    private Button rotateLeft, rotateRight, rotate45, more, arrange, done;
    private boolean laying;
    private int problemCount;
    private final List<File> models = new ArrayList<>();
    private String selection;
    private JSONObject inspected;
    private byte[] scene;
    private final List<File> meshes = new ArrayList<>();
    private JSONArray placements = new JSONArray();
    private int selected = -1;
    private boolean pageReady, sceneReady;

    @SuppressLint("SetJavaScriptEnabled")
    @Override protected void onCreate(Bundle saved) {
        ui = new WorkshopUi(this);
        super.onCreate(saved);
        String[] paths = getIntent().getStringArrayExtra(EXTRA_MODELS);
        selection = getIntent().getStringExtra(EXTRA_SELECTION);
        try { inspected = new JSONObject(getIntent().getStringExtra(EXTRA_INSPECTED)); } catch (JSONException | NullPointerException error) { finish(); return; }
        if (paths == null || selection == null) { finish(); return; }
        for (String path : paths) models.add(new File(path));
        String start = saved != null ? saved.getString(EXTRA_PLACEMENTS) : getIntent().getStringExtra(EXTRA_PLACEMENTS);

        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(ui.background); root.setFitsSystemWindows(true);
        web = new WebView(this); web.setBackgroundColor(ui.background);
        root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL); panel.setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(12));
        panel.setBackgroundColor(ui.surface); panel.setElevation(ui.dp(8)); root.addView(panel, new LinearLayout.LayoutParams(-1, -2));
        status = ui.label(panel, "Placing the models…", 13, ui.muted, false);
        selectedLabel = ui.label(panel, "Tap a model to select it.", 14, ui.ink, true);
        selectedLabel.setSingleLine(true); selectedLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
        status.setPadding(0, ui.dp(2), 0, ui.dp(2)); selectedLabel.setPadding(0, ui.dp(2), 0, ui.dp(2));
        LinearLayout turn = ui.row(panel);
        rotateLeft = ui.rowButton(turn, "Left 15°", () -> js("plate.rotate(15)"), false);
        rotateRight = ui.rowButton(turn, "Right 15°", () -> js("plate.rotate(-15)"), false);
        rotate45 = ui.rowButton(turn, "Left 45°", () -> js("plate.rotate(45)"), false);
        more = ui.rowButton(turn, "More…", this::moreDialog, false);
        for (Button b : new Button[] {rotateLeft, rotateRight, rotate45, more}) { b.setPadding(ui.dp(2), ui.dp(8), ui.dp(2), ui.dp(8)); b.setTextSize(12); }
        rotateLeft.setContentDescription("Turn 15 degrees left"); rotateRight.setContentDescription("Turn 15 degrees right");
        rotate45.setContentDescription("Turn 45 degrees left"); more.setContentDescription("More: copy, lay flat, scale, remove, model settings");
        LinearLayout finish = ui.row(panel);
        arrange = ui.rowButton(finish, "Arrange all", this::arrangeAll, false);
        done = ui.rowButton(finish, "Done · back to Slice", this::finishWithResult, true);
        ((LinearLayout.LayoutParams) done.getLayoutParams()).weight = 1.6f;
        setContentView(root);
        setButtons();

        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true); settings.setAllowFileAccess(false); settings.setAllowContentAccess(false);
        WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/data/", this::serveData).build();
        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) { return loader.shouldInterceptRequest(request.getUrl()); }
        });
        web.addJavascriptInterface(new Bridge(), "Android");
        web.loadUrl(ORIGIN + "/assets/plate/index.html");
        prepare(start);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString(EXTRA_PLACEMENTS, placements.toString());
    }

    @Override protected void onDestroy() { if (web != null) web.destroy(); super.onDestroy(); }

    private void finishWithResult() {
        if (problemCount > 0 && placements.length() > 0) {
            new android.app.AlertDialog.Builder(this).setTitle("Some models have a problem")
                .setMessage(problemCount + " model(s) are off the bed, overlapping or in a no-print zone, as marked in red. Slicing like this may fail or print badly.\n\nTip: Arrange all puts them back on the bed.")
                .setNegativeButton("Fix it", null).setPositiveButton("Go back anyway", (d, w) -> leave()).show();
            return;
        }
        leave();
    }

    private void leave() {
        if (placements.length() == 0) { status.setText("Put at least one model on the plate, or go back to the Slice screen with Arrange."); status.setTextColor(ui.error); return; }
        setResult(RESULT_OK, new Intent().putExtra(EXTRA_PLACEMENTS, placements.toString()));
        finish();
    }

    /** Back to the Slice screen with the layout, asking it to open the selected model's own settings. */
    private void editSelectedSettings() {
        if (selected < 0 || selected >= placements.length() || placements.length() == 0) return;
        JSONObject p = placements.optJSONObject(selected);
        setResult(RESULT_OK, new Intent().putExtra(EXTRA_PLACEMENTS, placements.toString())
            .putExtra(EXTRA_EDIT_OBJECT, new int[] {p.optInt("file"), p.optInt("object")}).putExtra(EXTRA_EDIT_NAME, name(selected)));
        finish();
    }

    /** Asks the engine for the bed and, unless given, the starting layout; then builds the page's scene. */
    private void prepare(String start) {
        SliceActivity.worker().execute(() -> {
            try {
                NativeSlicer engine = SliceActivity.engine(getApplicationContext());
                JSONObject layout = engine.arrangeJson(models, new JSONObject(selection).put("placements", new JSONArray()).toString());
                JSONArray initial = start != null ? new JSONArray(start) : layout.getJSONArray("placements");
                byte[] built = buildScene(layout, initial);
                main.post(() -> {
                    if (isDestroyed()) return;
                    scene = built; placements = initial; sceneReady = true;
                    status.setText(layout.optBoolean("kept_layout") ? "Layout from the project. Drag to move; two fingers to zoom and turn the view." : "Drag a model to move it. One finger turns the view, two fingers zoom and pan.");
                    status.setTextColor(ui.muted);
                    load();
                });
            } catch (Exception failure) {
                main.post(() -> { if (!isDestroyed()) { status.setText("The plate could not be prepared: " + failure.getMessage()); status.setTextColor(ui.error); } });
            }
        });
    }

    private byte[] buildScene(JSONObject layout, JSONArray initial) throws JSONException {
        JSONArray objects = new JSONArray(), files = inspected.getJSONArray("files");
        int[] slots = getIntent().getIntArrayExtra(EXTRA_SLOTS);
        Set<String> present = new HashSet<>();
        for (int i = 0; i < layout.getJSONArray("placements").length(); i++) { JSONObject p = layout.getJSONArray("placements").getJSONObject(i); present.add(p.getInt("file") + ":" + p.getInt("object")); }
        for (int i = 0; i < initial.length(); i++) { JSONObject p = initial.getJSONObject(i); present.add(p.getInt("file") + ":" + p.getInt("object")); }
        meshes.clear();
        for (int f = 0; f < files.length(); f++) {
            JSONArray list = files.getJSONObject(f).getJSONArray("objects");
            for (int o = 0; o < list.length(); o++) {
                JSONObject object = list.getJSONObject(o);
                if (!present.contains(f + ":" + o) || !object.has("mesh")) continue;
                meshes.add(new File(object.getString("mesh")));
                objects.put(new JSONObject().put("file", f).put("object", o).put("name", object.optString("name"))
                    .put("slot", slots != null && f < slots.length ? slots[f] : 0));
            }
        }
        String[] colours = getIntent().getStringArrayExtra(EXTRA_COLOURS);
        JSONArray colourList = new JSONArray(); if (colours != null) for (String colour : colours) colourList.put(colour);
        JSONObject json = new JSONObject().put("bed", layout.optJSONArray("bed")).put("excluded", layout.optJSONArray("excluded"))
            .put("height", layout.optDouble("height", 256)).put("objects", objects).put("placements", initial).put("colours", colourList);
        if (layout.has("tower")) json.put("tower", layout.getJSONArray("tower"));
        return json.toString().getBytes(StandardCharsets.UTF_8);
    }

    private WebResourceResponse serveData(String name) {
        try {
            if (name.equals("scene.json") && scene != null) return new WebResourceResponse("application/json", "utf-8", new ByteArrayInputStream(scene));
            if (name.startsWith("mesh/")) {
                int index = Integer.parseInt(name.substring(5));
                if (index >= 0 && index < meshes.size()) return new WebResourceResponse("application/octet-stream", null, new FileInputStream(meshes.get(index)));
            }
        } catch (NumberFormatException | IOException ignored) { }
        return new WebResourceResponse("text/plain", "utf-8", 404, "Not found", null, new ByteArrayInputStream(new byte[0]));
    }

    private void load() {
        if (!pageReady || !sceneReady) return;
        js("plate.setTheme(" + theme() + ")"); js("plate.load()");
    }

    private String theme() {
        try {
            JSONObject theme = new JSONObject().put("background", rgb(ui.background)).put("selected", rgb(ui.teal)).put("problem", rgb(ui.error));
            if (ui.dark) theme.put("plate", new JSONArray(new double[] {0.12, 0.16, 0.18, 1})).put("grid", new JSONArray(new double[] {0.24, 0.3, 0.33, 1}));
            return theme.toString();
        } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }

    private static JSONArray rgb(int color) throws JSONException {
        return new JSONArray(new double[] {((color >> 16) & 255) / 255.0, ((color >> 8) & 255) / 255.0, (color & 255) / 255.0});
    }

    private void js(String script) { if (web != null && pageReady) web.evaluateJavascript(script, null); }

    private void scaleDialog() {
        EditText input = new EditText(this); input.setSingleLine(true); input.setText(String.format(Locale.ROOT, "%.0f", scalePercent));
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL); input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        input.setContentDescription("Scale in percent"); input.setSelectAllOnFocus(true);
        FrameLayout box = new FrameLayout(this); box.setPadding(ui.dp(24), ui.dp(8), ui.dp(24), 0); box.addView(input);
        new android.app.AlertDialog.Builder(this).setTitle("Scale (percent of the original size)").setView(box)
            .setNegativeButton("Cancel", null).setPositiveButton("Set", (d, w) -> applyScale(input.getText().toString())).show();
    }

    private void applyScale(String text) {
        if (selected < 0) return;
        try {
            double percent = Double.parseDouble(text.trim());
            if (percent < 1 || percent > 2000) { status.setText("Scale between 1% and 2000%."); status.setTextColor(ui.error); return; }
            js("plate.setScale(" + (percent / 100) + ")");
        } catch (NumberFormatException ignored) { }
    }

    /** The less used actions on the selected model, kept out of the panel so the 3D view stays large. */
    private void moreDialog() {
        if (laying) { laying = false; js("plate.setLayMode(false)"); more.setText("More…"); status.setText("Lay flat cancelled."); status.setTextColor(ui.muted); return; }
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(ui.dp(20), ui.dp(4), ui.dp(20), ui.dp(8));
        android.app.AlertDialog[] dialog = new android.app.AlertDialog[1];
        boolean on = selected >= 0;
        String[] names = {"Copy", "Lay flat on a face", "Upright again", "Scale…", "Remove", "Model settings…"};
        Runnable[] actions = {() -> js("plate.duplicate()"), () -> { laying = true; js("plate.setLayMode(true)"); more.setText("Cancel lay flat");
                status.setText("Tap the face of the model that should lie flat on the bed."); status.setTextColor(ui.teal); },
            () -> js("plate.upright()"), this::scaleDialog, () -> js("plate.remove()"), this::editSelectedSettings};
        for (int i = 0; i < names.length; i++) {
            Runnable action = actions[i];
            Button b = ui.button(body, names[i], () -> { dialog[0].dismiss(); action.run(); }, false);
            b.setEnabled(on && (i != 1 || placements.length() > 0));
        }
        if (!on) ui.label(body, "Select a model first: tap it on the plate.", 13, ui.muted, false);
        ScrollView scroll = new ScrollView(this); scroll.addView(body);
        dialog[0] = new android.app.AlertDialog.Builder(this).setTitle(selected >= 0 ? name(selected) : "Model").setView(scroll).setNegativeButton("Close", null).show();
    }

    /** Lets the engine arrange the current copies (with their turns, scales and duplicates). */
    private void arrangeAll() {
        if (placements.length() == 0) return;
        arrange.setEnabled(false); status.setText("Arranging…"); status.setTextColor(ui.muted);
        String current = placements.toString();
        SliceActivity.worker().execute(() -> {
            try {
                JSONObject request = new JSONObject(selection).put("placements", new JSONArray(current));
                JSONArray arranged = SliceActivity.engine(getApplicationContext()).arrangeJson(models, request.toString()).getJSONArray("placements");
                main.post(() -> { if (!isDestroyed()) { arrange.setEnabled(true); js("plate.setPlacements(" + arranged + ")"); status.setText("Arranged."); } });
            } catch (Exception failure) {
                main.post(() -> { if (!isDestroyed()) { arrange.setEnabled(true); status.setText("Could not arrange: " + failure.getMessage()); status.setTextColor(ui.error); } });
            }
        });
    }

    private void setButtons() {
        boolean on = selected >= 0;
        for (View view : new View[] {rotateLeft, rotateRight, rotate45}) view.setEnabled(on);
        more.setEnabled(true);
    }

    private final class Bridge {
        @JavascriptInterface public void onReady() { main.post(() -> { pageReady = true; load(); }); }
        @JavascriptInterface public void onLoaded(int count) { }
        @JavascriptInterface public void onLayDone(boolean laid) {
            main.post(() -> {
                laying = false; more.setText("More…");
                if (laid && problemCount == 0) { status.setText("Laid flat on that face. “Upright again” undoes it."); status.setTextColor(ui.teal); }
            });
        }
        @JavascriptInterface public void onError(String message) { main.post(() -> { status.setText(message); status.setTextColor(ui.error); }); }
        @JavascriptInterface public void onSelect(int index) { main.post(() -> { selected = index; showSelected(); setButtons(); }); }
        @JavascriptInterface public void onChanged(String json) {
            main.post(() -> {
                try {
                    JSONObject state = new JSONObject(json);
                    placements = state.getJSONArray("placements"); selected = state.optInt("selected", -1);
                    // Kept current, so leaving with Back (or a back gesture) keeps the layout too.
                    if (placements.length() > 0) setResult(RESULT_OK, new Intent().putExtra(EXTRA_PLACEMENTS, placements.toString()));
                    JSONArray problems = state.getJSONArray("problems");
                    List<List<String>> found = new ArrayList<>();
                    for (int i = 0; i < problems.length(); i++) found.add(toList(problems.getJSONArray(i)));
                    String summary = summarize(found);
                    problemCount = 0; for (List<String> issues : found) if (!issues.isEmpty()) problemCount++;
                    int n = placements.length();
                    status.setText(summary != null ? summary : n == 1 ? "1 model on the plate, inside the bed." : n + " models on the plate, all inside the bed and apart.");
                    status.setTextColor(summary != null ? ui.error : ui.muted);
                    showSelected(); setButtons();
                } catch (JSONException ignored) { }
            });
        }
    }

    /**
     * One line for the panel about everything wrong on the plate, from the page's issue keys per model; null when all is
     * well. The per-model detail is on the labels over the models in the page.
     */
    static String summarize(List<List<String>> issuesPerModel) {
        String[][] kinds = {{"off the bed", "off the bed"}, {"in the excluded area", "in a no-print zone"}, {"on the prime tower", "on the prime tower"},
            {"taller than the printer", "too tall for the printer"}, {"touching another copy", "overlap"}};
        List<String> parts = new ArrayList<>();
        boolean movable = false, tall = false;
        for (String[] kind : kinds) {
            int count = 0;
            for (List<String> issues : issuesPerModel) if (issues.contains(kind[0])) count++;
            if (count == 0) continue;
            if (kind[0].startsWith("taller")) tall = true; else movable = true;
            String noun = count == 1 ? "1 model" : count + " models";
            parts.add(kind[1].equals("overlap") ? noun + (count == 1 ? " overlaps another" : " overlap") : noun + (count == 1 ? " is " : " are ") + kind[1]);
        }
        if (parts.isEmpty()) return null;
        String line = String.join(" · ", parts) + ".";
        int affected = 0;
        for (List<String> issues : issuesPerModel) for (String issue : issues) if (!issue.startsWith("taller")) { affected++; break; }
        if (movable) line += (affected == 1 ? " Drag it clear" : " Drag them clear") + " or tap Arrange all.";
        if (tall) line += " For a tall one: scale it down, or More… > Lay flat.";
        return line;
    }

    private void showSelected() {
        if (selected < 0 || selected >= placements.length()) { selectedLabel.setText("Tap a model to select it."); return; }
        JSONObject p = placements.optJSONObject(selected);
        scalePercent = p.optDouble("scale", 1) * 100;
        selectedLabel.setText(String.format(Locale.getDefault(), "Selected: %s · turned %.0f° · %.0f%%", name(selected), p.optDouble("rotation"), scalePercent));
    }

    private String name(int index) {
        JSONObject p = placements.optJSONObject(index);
        if (p == null) return "Model";
        try {
            return inspected.getJSONArray("files").getJSONObject(p.getInt("file")).getJSONArray("objects").getJSONObject(p.getInt("object")).optString("name", "Model");
        } catch (JSONException e) { return "Model"; }
    }

    private static List<String> toList(JSONArray array) {
        List<String> list = new ArrayList<>(); for (int i = 0; i < array.length(); i++) list.add(array.optString(i)); return list;
    }
}
