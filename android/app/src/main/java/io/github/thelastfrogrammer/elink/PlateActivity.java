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
    private EditText scale;
    private Button rotateLeft, rotateRight, rotate45, rotate90, copy, remove, arrange, done, layFace, upright, modelSettings;
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
        selectedLabel = ui.label(panel, "Tap a model to select it. The buttons below work on the selected model.", 14, ui.ink, true);
        LinearLayout turn = ui.row(panel);
        rotateLeft = ui.rowButton(turn, "Left 15°", () -> js("plate.rotate(15)"), false);
        rotateRight = ui.rowButton(turn, "Right 15°", () -> js("plate.rotate(-15)"), false);
        rotate45 = ui.rowButton(turn, "Left 45°", () -> js("plate.rotate(45)"), false);
        rotate90 = ui.rowButton(turn, "Left 90°", () -> js("plate.rotate(90)"), false);
        for (Button b : new Button[] {rotateLeft, rotateRight, rotate45, rotate90}) { b.setPadding(ui.dp(2), ui.dp(8), ui.dp(2), ui.dp(8)); b.setTextSize(12); }
        rotateLeft.setContentDescription("Turn 15 degrees left"); rotateRight.setContentDescription("Turn 15 degrees right");
        rotate45.setContentDescription("Turn 45 degrees left"); rotate90.setContentDescription("Turn 90 degrees left");
        LinearLayout lay = ui.row(panel);
        layFace = ui.rowButton(lay, "Lay flat on a face", () -> {
            laying = !laying; js("plate.setLayMode(" + laying + ")");
            layFace.setText(laying ? "Cancel" : "Lay flat on a face");
            if (laying) { status.setText("Tap the face of the model that should lie flat on the bed."); status.setTextColor(ui.teal); }
        }, false);
        upright = ui.rowButton(lay, "Upright again", () -> js("plate.upright()"), false);
        LinearLayout edit = ui.row(panel);
        TextView scaleName = new TextView(this); scaleName.setText("Scale"); scaleName.setTextColor(ui.ink); scaleName.setTextSize(14);
        scale = new EditText(this); scale.setHint("100"); scale.setHintTextColor(ui.muted); scale.setTextColor(ui.ink); scale.setSingleLine(true);
        scale.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL); scale.setImeOptions(EditorInfo.IME_ACTION_DONE);
        scale.setBackgroundTintList(android.content.res.ColorStateList.valueOf(ui.teal)); scale.setContentDescription("Scale in percent");
        LinearLayout scaleBox = new LinearLayout(this); scaleBox.setOrientation(LinearLayout.HORIZONTAL); scaleBox.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView percent = new TextView(this); percent.setText("%"); percent.setTextColor(ui.ink); percent.setTextSize(14);
        scaleBox.addView(scaleName); scaleBox.addView(scale, new LinearLayout.LayoutParams(0, ui.dp(52), 1)); scaleBox.addView(percent);
        scaleName.setPadding(0, 0, ui.dp(8), 0); percent.setPadding(ui.dp(4), 0, ui.dp(8), 0);
        LinearLayout.LayoutParams scaleLayout = new LinearLayout.LayoutParams(0, -2, 1); scaleLayout.topMargin = ui.dp(6); edit.addView(scaleBox, scaleLayout);
        scale.setOnEditorActionListener((v, action, event) -> { applyScale(); return false; });
        scale.setOnFocusChangeListener((v, focused) -> { if (!focused) applyScale(); });
        copy = ui.rowButton(edit, "Copy", () -> js("plate.duplicate()"), false);
        remove = ui.rowButton(edit, "Remove", () -> js("plate.remove()"), false);
        LinearLayout finish = ui.row(panel);
        arrange = ui.rowButton(finish, "Arrange all", this::arrangeAll, false);
        modelSettings = ui.rowButton(finish, "Model settings…", this::editSelectedSettings, false);
        done = ui.button(panel, "Done · back to Slice", this::finishWithResult, true);
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

    private void applyScale() {
        if (selected < 0) return;
        String text = scale.getText().toString().trim();
        try {
            double percent = Double.parseDouble(text);
            if (percent < 1 || percent > 2000) { status.setText("Scale between 1% and 2000%."); status.setTextColor(ui.error); return; }
            js("plate.setScale(" + (percent / 100) + ")");
        } catch (NumberFormatException ignored) { }
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
        for (View view : new View[] {rotateLeft, rotateRight, rotate45, rotate90, copy, remove, scale, upright, modelSettings}) view.setEnabled(on);
        layFace.setEnabled(placements.length() > 0);
    }

    private final class Bridge {
        @JavascriptInterface public void onReady() { main.post(() -> { pageReady = true; load(); }); }
        @JavascriptInterface public void onLoaded(int count) { }
        @JavascriptInterface public void onLayDone(boolean laid) {
            main.post(() -> {
                laying = false; layFace.setText("Lay flat on a face");
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
                    JSONArray problems = state.getJSONArray("problems"), advice = state.optJSONArray("advice");
                    List<String> lines = new ArrayList<>();
                    for (int i = 0; i < problems.length(); i++) {
                        JSONArray issues = problems.getJSONArray(i);
                        if (issues.length() > 0) lines.add(name(i) + " - " + String.join("; ", toList(advice != null && advice.optJSONArray(i) != null ? advice.getJSONArray(i) : issues)));
                    }
                    problemCount = lines.size();
                    int n = placements.length();
                    status.setText(lines.isEmpty() ? (n == 1 ? "1 model on the plate, inside the bed." : n + " models on the plate, all inside the bed and apart.") : String.join("\n", lines));
                    status.setTextColor(lines.isEmpty() ? ui.muted : ui.error);
                    showSelected(); setButtons();
                } catch (JSONException ignored) { }
            });
        }
    }

    private void showSelected() {
        if (selected < 0 || selected >= placements.length()) { selectedLabel.setText("Tap a model to select it. The buttons below work on the selected model."); scale.setText(""); return; }
        JSONObject p = placements.optJSONObject(selected);
        selectedLabel.setText(String.format(Locale.getDefault(), "Selected: %s\nX %.1f · Y %.1f mm · turned %.0f°", name(selected), p.optDouble("x"), p.optDouble("y"), p.optDouble("rotation")));
        if (!scale.hasFocus()) scale.setText(String.format(Locale.ROOT, "%.0f", p.optDouble("scale", 1) * 100));
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
