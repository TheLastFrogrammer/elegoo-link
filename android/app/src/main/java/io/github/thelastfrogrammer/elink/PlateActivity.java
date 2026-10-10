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
    private Button rotateLeft, rotateRight, copyButton, more, arrange, done;
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
        status = A11y.polite(ui.label(panel, "Placing the models…", 13, ui.muted, false));
        selectedLabel = A11y.polite(ui.label(panel, "Tap a model to select it, or open More… and choose Select a model.", 14, ui.ink, true));
        selectedLabel.setMaxLines(3); selectedLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
        status.setPadding(0, ui.dp(2), 0, ui.dp(2)); selectedLabel.setPadding(0, ui.dp(2), 0, ui.dp(2));
        LinearLayout turn = ui.row(panel);
        rotateLeft = ui.rowButton(turn, "Left 15°", () -> js("plate.rotate(15)"), false);
        rotateRight = ui.rowButton(turn, "Right 15°", () -> js("plate.rotate(-15)"), false);
        copyButton = ui.rowButton(turn, "Copy", () -> js("plate.duplicate()"), false);
        more = ui.rowButton(turn, "More…", this::moreDialog, false);
        // The compact panel keeps its buttons' text to at most 1.3x so the 3D view stays large; longer labels wrap onto a second line.
        float panelText = 12 * getResources().getDisplayMetrics().density * Math.min(1.3f, getResources().getConfiguration().fontScale);
        for (Button b : new Button[] {rotateLeft, rotateRight, copyButton, more}) { b.setPadding(ui.dp(2), ui.dp(8), ui.dp(2), ui.dp(8)); b.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, panelText); }
        rotateLeft.setContentDescription("Turn 15 degrees left"); rotateRight.setContentDescription("Turn 15 degrees right");
        copyButton.setContentDescription("Copy the selected model"); more.setContentDescription("More: select a model, move, several copies, turn 45 degrees, lay flat, scale, remove, model settings, reset view");
        LinearLayout finish = ui.row(panel);
        arrange = ui.rowButton(finish, "Arrange all", this::arrangeAll, false);
        done = ui.rowButton(finish, "Done · back to Slice", this::finishWithResult, true);
        ((LinearLayout.LayoutParams) done.getLayoutParams()).weight = 1.6f;
        for (Button b : new Button[] {arrange, done}) b.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, panelText * 14 / 12);
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
                .setMessage((problemCount == 1 ? "One model has" : problemCount + " models have") + " a problem, shown in red stripes: it is off the bed, overlapping another model, or in a no-print zone. Printing it like this may fail or print badly.\n\nTip: Arrange all puts them back on the bed.")
                .setNegativeButton("Fix it", null).setPositiveButton("Go back anyway", (d, w) -> leave()).show();
            return;
        }
        leave();
    }

    private void leave() {
        if (placements.length() == 0) { status.setText("Put at least one model in the layout, or go back to the Slice screen with Arrange."); status.setTextColor(ui.error); return; }
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
    /** Tests that only exercise the panel switch the engine call off (it would run native code in the test JVM). */
    static boolean prepareEnabled = true;

    private void prepare(String start) {
        if (!prepareEnabled) return;
        SliceActivity.worker().execute(() -> {
            try {
                NativeSlicer engine = SliceActivity.engine(getApplicationContext());
                JSONObject layout = engine.arrangeJson(models, new JSONObject(selection).put("placements", new JSONArray()).toString());
                JSONArray initial = start != null ? new JSONArray(start) : layout.getJSONArray("placements");
                byte[] built = buildScene(layout, initial);
                main.post(() -> {
                    if (isDestroyed()) return;
                    scene = built; placements = initial; sceneReady = true;
                    int unfit = layout.optInt("unfit");
                    status.setText(unfit > 0 ? (unfit == 1 ? "One model is" : unfit + " models are") + " too big to fit the bed, so they sit in the middle. Select one, open More…, choose Scale…, then tap Fit to the bed."
                        : layout.optBoolean("kept_layout") ? "Layout from the project. Drag to move; two fingers to zoom and turn the view." : "Drag a model to move it. One finger turns the view, two fingers zoom and pan.");
                    status.setTextColor(unfit > 0 ? ui.error : ui.muted);
                    load();
                });
            } catch (Exception failure) {
                main.post(() -> { if (!isDestroyed()) { status.setText("The layout could not be prepared: " + failure.getMessage()); status.setTextColor(ui.error); } });
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

    /** Scale: evenly in percent, or each axis on its own (X and Y across the bed before turning, Z up), or to fit the bed. */
    private void scaleDialog() {
        if (selected < 0 || selected >= placements.length()) return;
        JSONObject p = placements.optJSONObject(selected); JSONArray k = p.optJSONArray("stretch");
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(ui.dp(24), ui.dp(8), ui.dp(24), 0);
        EditText all = percentField(body, "Even scale (all axes)", p.optDouble("scale", 1) * 100);
        ui.label(body, "Makes the whole model bigger or smaller, keeping its shape. 100 = original size.", 13, ui.muted, false);
        android.app.AlertDialog[] dialog = new android.app.AlertDialog[1];
        ui.label(body, "Too big for the bed? One tap shrinks it to fit.", 13, ui.muted, false);
        ui.button(body, "Fit to the bed", () -> { dialog[0].dismiss(); web.evaluateJavascript("plate.fitToBed()", value -> {
            status.setText("Scaled to fit the bed and the printer height, and moved to the middle."); status.setTextColor(ui.muted); }); }, false);
        ui.label(body, "Then each axis, on top of the even scale. 100 = unchanged. Z is height.", 13, ui.muted, false);
        EditText[] axes = new EditText[3]; String[] names = {"X (width)", "Y (depth)", "Z (height)"};
        for (int i = 0; i < 3; i++) axes[i] = percentField(body, names[i], k == null ? 100 : k.optDouble(i, 1) * 100);
        dialog[0] = new android.app.AlertDialog.Builder(this).setTitle("Scale " + name(selected)).setView(scrollOf(body))
            .setNegativeButton("Cancel", null).setPositiveButton("Set", (d, w) -> applyScale(all.getText().toString(), axes)).show();
    }
    private ScrollView scrollOf(View body) { ScrollView scroll = new ScrollView(this); scroll.addView(body); return scroll; }
    private EditText percentField(LinearLayout parent, String label, double value) {
        ui.label(parent, label, 13, ui.ink, false);
        EditText input = new EditText(this); input.setSingleLine(true); input.setText(String.format(Locale.ROOT, "%.1f", value).replaceFirst("\\.0$", ""));
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL); input.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        input.setContentDescription(label + " in percent"); input.setSelectAllOnFocus(true); input.setMinHeight(ui.dp(48));
        parent.addView(input); return input;
    }

    private void applyScale(String text, EditText[] axes) {
        if (selected < 0) return;
        try {
            double percent = Double.parseDouble(text.trim()), x = Double.parseDouble(axes[0].getText().toString().trim()), y = Double.parseDouble(axes[1].getText().toString().trim()), z = Double.parseDouble(axes[2].getText().toString().trim());
            for (double v : new double[] {percent, x, y, z}) if (v < 1 || v > 2000) { status.setText("Scale between 1% and 2000%."); status.setTextColor(ui.error); return; }
            js(scaleScript(percent, x, y, z));
        } catch (NumberFormatException invalid) { status.setText("Enter numbers, such as 80 or 112.5."); status.setTextColor(ui.error); }
    }

    /** Several copies of the selected model at once, then arranged so they all sit on the bed. */
    private void copiesDialog() {
        if (selected < 0) return;
        EditText count = new EditText(this); count.setSingleLine(true); count.setInputType(InputType.TYPE_CLASS_NUMBER); count.setText("1"); count.setSelectAllOnFocus(true);
        count.setContentDescription("Number of extra copies"); count.setMinHeight(ui.dp(48));
        FrameLayout box = new FrameLayout(this); box.setPadding(ui.dp(24), ui.dp(8), ui.dp(24), 0); box.addView(count);
        new android.app.AlertDialog.Builder(this).setTitle("Extra copies of " + name(selected)).setMessage("How many more copies, from 1 to 50? They are added, then everything is arranged on the bed.").setView(box)
            .setNegativeButton("Cancel", null).setPositiveButton("Add", (d, w) -> {
                int n; try { n = Integer.parseInt(count.getText().toString().trim()); } catch (NumberFormatException e) { return; }
                if (n < 1 || n > 50) { status.setText("Add between 1 and 50 copies at a time."); status.setTextColor(ui.error); return; }
                int from = selected; StringBuilder script = new StringBuilder();
                for (int i = 0; i < n; i++) script.append(selectScript(from)).append(";plate.duplicate();");
                web.evaluateJavascript(script.toString(), value -> main.postDelayed(this::arrangeAll, 300));
            }).show();
    }

    /** The less used actions on the selected model, kept out of the panel so the 3D view stays large. */
    private void moreDialog() {
        if (laying) { laying = false; js("plate.setLayMode(false)"); more.setText("More…"); status.setText("Lay flat cancelled."); status.setTextColor(ui.muted); return; }
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(ui.dp(24), ui.dp(4), ui.dp(24), ui.dp(8));
        android.app.AlertDialog[] dialog = new android.app.AlertDialog[1];
        boolean on = selected >= 0;
        String[] names = {"Select a model…", "Move…", "Several copies…", "Turn 45° left", "Lay flat on a face", "Upright again", "Scale…", "Remove", "Model settings…", "Reset view"};
        Runnable[] actions = {this::selectDialog, this::moveDialog, this::copiesDialog, () -> js("plate.rotate(45)"), () -> { laying = true; js("plate.setLayMode(true)"); more.setText("Cancel lay flat");
                status.setText("Tap the face of the model that should lie flat on the bed."); status.setTextColor(ui.teal); },
            () -> js("plate.upright()"), this::scaleDialog, () -> js("plate.remove()"), this::editSelectedSettings, () -> js("plate.resetCamera()")};
        for (int i = 0; i < names.length; i++) {
            Runnable action = actions[i];
            Button b = ui.button(body, names[i], () -> { dialog[0].dismiss(); action.run(); }, false);
            b.setEnabled(i == 0 ? placements.length() > 0 : i == names.length - 1 || on);
        }
        if (!on) ui.label(body, "Pick a model first: tap it in the layout, or use Select a model…", 13, ui.muted, false);
        ScrollView scroll = new ScrollView(this); scroll.addView(body);
        dialog[0] = new android.app.AlertDialog.Builder(this).setTitle(selected >= 0 ? name(selected) : "Model").setView(scroll).setNegativeButton("Close", null).show();
    }

    /** The models on the plate by name, to pick one without touching the 3D view. */
    private void selectDialog() {
        String[] items = new String[placements.length()];
        for (int i = 0; i < items.length; i++) items[i] = (i + 1) + ". " + name(i);
        new android.app.AlertDialog.Builder(this).setTitle("Select a model")
            .setSingleChoiceItems(items, selected, (d, which) -> { js(selectScript(which)); d.dismiss(); })
            .setNegativeButton("Close", null).show();
    }

    /** Moves the selected model by buttons instead of dragging: the dialog stays open so a model can be nudged several times. */
    private void moveDialog() {
        if (selected < 0 || selected >= placements.length()) return;
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(ui.dp(24), ui.dp(4), ui.dp(24), ui.dp(8));
        TextView readout = A11y.polite(ui.label(body, positionText(), 14, ui.ink, true));
        Runnable refresh = () -> readout.setText(positionText());
        String[] directions = {"Left", "Right", "Front", "Back"};
        double[][] unit = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
        for (int step : MOVE_STEPS) {
            LinearLayout row = ui.row(body);
            for (int d = 0; d < 4; d++) {
                final double dx = unit[d][0] * step, dy = unit[d][1] * step; final String direction = directions[d];
                Button b = ui.rowButton(row, direction + " " + step, () -> { js(moveScript(dx, dy)); main.postDelayed(refresh, 150); }, false);
                b.setContentDescription("Move " + step + " millimetre" + (step == 1 ? "" : "s") + " " + direction.toLowerCase(Locale.ROOT));
                b.setTextSize(13); b.setPadding(ui.dp(2), ui.dp(8), ui.dp(2), ui.dp(8));
            }
        }
        ui.label(body, "Steps are in millimetres. Problems (off the bed, overlapping) show in the panel, as when dragging.", 12, ui.muted, false);
        ScrollView scroll = new ScrollView(this); scroll.addView(body);
        new android.app.AlertDialog.Builder(this).setTitle("Move " + name(selected)).setView(scroll).setNegativeButton("Done", null).show();
    }

    private String positionText() {
        JSONObject p = selected >= 0 ? placements.optJSONObject(selected) : null;
        return p == null ? "" : String.format(Locale.ROOT, "Position: X %.1f mm, Y %.1f mm", p.optDouble("x"), p.optDouble("y"));
    }

    static final int[] MOVE_STEPS = {1, 5, 10};
    /** The page call that moves the selected model; plain numbers whatever the phone's language. */
    static String moveScript(double dx, double dy) { return String.format(Locale.ROOT, "plate.move(%s,%s)", number(dx), number(dy)); }
    /** The page calls that set the selected model's even scale and per-axis stretch, from percentages. */
    static String scaleScript(double percent, double x, double y, double z) {
        return "plate.setScale(" + number(percent / 100) + ");plate.setStretch(" + number(x / 100) + "," + number(y / 100) + "," + number(z / 100) + ")";
    }
    static String selectScript(int index) { return "plate.select(" + index + ")"; }
    private static String number(double value) { return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value); }

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
        for (View view : new View[] {rotateLeft, rotateRight, copyButton}) view.setEnabled(on);
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
                    status.setText(summary != null ? summary : n == 1 ? "1 model in the layout, inside the bed." : n + " models in the layout, all inside the bed and apart.");
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
        JSONArray k = p.optJSONArray("stretch");
        String axes = k == null ? "" : String.format(Locale.getDefault(), " (X %.0f%% · Y %.0f%% · Z %.0f%%)", k.optDouble(0, 1) * 100, k.optDouble(1, 1) * 100, k.optDouble(2, 1) * 100);
        selectedLabel.setText(String.format(Locale.getDefault(), "Selected: %s · turned %.0f° · size %.0f%%", name(selected), p.optDouble("rotation"), scalePercent) + axes);
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
