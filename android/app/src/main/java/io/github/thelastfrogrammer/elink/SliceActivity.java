package io.github.thelastfrogrammer.elink;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.View;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Slices STL/3MF/OBJ/Draco/STEP models on the phone with ElegooSlicer's engine (NativeSlicer) and the Elegoo presets,
 * then hands the G-code to the Files tab (RESULT_FILE/RESULT_NAME) or saves it. Several filament slots can be filled from
 * the printer's CANVAS trays; the slot-to-tray plan is kept per file name (TRAY_PLANS) for the print setup dialog.
 */
public final class SliceActivity extends Activity {
    static final String RESULT_FILE = "slicedFile", RESULT_NAME = "slicedName", RESULT_PRINT_SETUP = "printSetup", TRAY_PLANS = "tray-plans";
    static final int ALL_PLATES = -1;
    private static final int PICK_MODELS = 1, SAVE = 2, SETTINGS = 3, PLATE = 4;
    private static final int PREVIEW_TRIANGLES = 150_000;
    private static final String DEFAULT_PRINTER = "Elegoo Centauri Carbon 2 0.4 nozzle";
    private static final Set<String> MODEL_TYPES = new HashSet<>(Arrays.asList("stl", "3mf", "obj", "drc", "step", "stp", "amf"));

    // One engine per process: loading the presets takes seconds, and the engine must stay on one thread.
    private static NativeSlicer engine;
    private static final ExecutorService worker = Executors.newSingleThreadExecutor(runnable ->
        new Thread(null, runnable, "slicer", 64L * 1024 * 1024)); // libslic3r recurses deeply; give it a desktop-sized stack

    /** The slicer thread; every engine call runs on it. */
    static ExecutorService worker() { return worker; }

    /** The shared engine with the Elegoo presets, opened on first use. Call on worker(). */
    static NativeSlicer engine(android.content.Context context) throws IOException {
        if (engine == null) {
            long started = System.currentTimeMillis();
            engine = NativeSlicer.open(context.getApplicationContext(), "Elegoo");
            Diagnostics.note(Diagnostics.SLICER, String.format(Locale.ROOT, "presets loaded in %.1f s · %s", (System.currentTimeMillis() - started) / 1000.0, Diagnostics.memory()));
        }
        return engine;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    // A slice outlives the screen that started it (rotation, theme change): its progress and result go to whichever
    // Slice screen is current, and any of them can cancel it.
    private static final AtomicBoolean cancelRequested = new AtomicBoolean();
    private static SliceActivity current;
    private static volatile boolean slicing;
    private static int slicingPercent;
    private static String slicingText = "Slicing…";
    private Bundle restored;
    private final List<File> slicedFiles = new ArrayList<>();
    private final List<String> slicedNames = new ArrayList<>();
    private Button uploadPrint;
    private TextView printerHint;
    // What the model files hold (engine inspect: objects, 3MF plates, project settings, preview meshes).
    private org.json.JSONObject inspected;
    private int plate;                        // 3MF plate, 1-based; 0 = the first
    private boolean projectSettings = true;   // apply a project's own process settings
    private final Map<String, String> customOverrides = new LinkedHashMap<>(); // from the settings screen
    private org.json.JSONArray placements;    // the plate view's layout, or null to arrange automatically
    private boolean largeConfirmed;
    private LinearLayout projectBox;
    private TextView layoutLabel, settingsSummary;
    private Button editPlate, autoLayout, allSettings;
    private String restoredPrinter, restoredProcess;
    private SharedPreferences settings;
    private boolean dark, busy;
    private int ink, muted, teal, background, surface, buttonColor, error;
    private LinearLayout content, actionBar;
    private ScrollView scroll;
    private TextView modelsLabel, status, resultText;
    private Spinner printerSpinner, processSpinner, supportSpinner, brimSpinner;
    private LinearLayout slotList, modelAssign;
    private Button addSlot, removeSlot, fillTrays;
    private TextView traysNote;
    private String[] filamentPresets = new String[0];
    private final List<Slot> slots = new ArrayList<>();
    private final List<Integer> modelSlots = new ArrayList<>(); // per model: 1-based slot, 0 = as in the 3MF
    private PrinterService printer;
    private boolean bound;
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) { if (binder instanceof PrinterService.LocalBinder) { printer = ((PrinterService.LocalBinder) binder).service(); refreshTrays(); } }
        @Override public void onServiceDisconnected(ComponentName name) { printer = null; }
    };

    /** One filament slot: preset, optional CANVAS tray and colour. Slot k is G-code tool k - 1. */
    private final class Slot {
        LinearLayout row; TextView title, swatch; Spinner preset, tray;
        String colour, wanted; TrayPlan.Tray source; List<TrayPlan.Tray> trayChoices = new ArrayList<>();
    }
    private EditText infill;
    private Button chooseModels, slice, cancel, useInFiles, saveCopy;
    private ProgressBar progress;
    private ImageView preview;
    private LinearLayout resultCard;
    private final List<File> models = new ArrayList<>();
    private File sliced;
    private String slicedName;

    @Override protected void onCreate(Bundle saved) {
        settings = getSharedPreferences("workshop-settings", MODE_PRIVATE);
        int appearance = settings.getInt("theme", 0);
        dark = appearance == 2 || appearance == 0 && (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        setTheme(dark ? R.style.WorkshopDark : R.style.WorkshopLight);
        super.onCreate(saved);
        ink = dark ? 0xffe6eef1 : 0xff17252c; muted = dark ? 0xff9fb3bb : 0xff5a6d76; teal = dark ? 0xff5fd4c4 : 0xff00796b;
        background = dark ? 0xff0e1417 : 0xfff2f5f6; surface = dark ? 0xff182227 : Color.WHITE; buttonColor = dark ? 0xff21343a : 0xffe2efed;
        error = dark ? 0xffffb4ab : 0xffba1a1a;
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(background); root.setFitsSystemWindows(true);
        scroll = new ScrollView(this); scroll.setBackgroundColor(background);
        content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(dp(16), dp(16), dp(16), dp(24)); scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        // The slice controls stay on screen below the form.
        actionBar = new LinearLayout(this); actionBar.setOrientation(LinearLayout.VERTICAL); actionBar.setPadding(dp(16), dp(8), dp(16), dp(12));
        actionBar.setBackgroundColor(surface); actionBar.setElevation(dp(8)); root.addView(actionBar, new LinearLayout.LayoutParams(-1, -2));
        actionBar.setVisibility(View.GONE);
        setContentView(root);
        label(content, "Slice a model", 22, ink, true);
        label(content, "ElegooSlicer's own slicing engine and Elegoo presets, running on this phone.", 13, muted, false);
        Diagnostics.init(getFilesDir());
        current = this;
        if (!NativeSlicer.available(this)) {
            LinearLayout card = card("Slicer not included");
            label(card, "This build of Link Workshop was made without the slicer library. Builds that include it are made with slicer/scripts/install-into-app.sh before building the app.", 14, ink, false);
            return;
        }
        build();
        bound = bindService(new Intent(this, PrinterService.class), connection, BIND_AUTO_CREATE);
        if (saved != null) restore(saved);
        else { List<Uri> incoming = incomingModels(getIntent()); if (!incoming.isEmpty()) importModels(incoming); }
        if (slicing) { busy = true; progress.setVisibility(View.VISIBLE); progress.setProgress(slicingPercent); status.setText(slicingText); updateButtons(); }
        else checkInterrupted();
        loadPresets();
    }

    // A file that exists only while a slice runs: still there at the next start means Android closed the app mid-slice.
    private File runningMarker() { return new File(getFilesDir(), "slice-running.txt"); }
    private void markRunning(String setup) {
        try (java.io.Writer out = new java.io.OutputStreamWriter(new FileOutputStream(runningMarker()), "UTF-8")) { out.write(setup); } catch (IOException ignored) { }
    }
    private void clearRunning() { runningMarker().delete(); }
    private void checkInterrupted() {
        File marker = runningMarker();
        if (!marker.isFile()) return;
        String setup = "";
        try (InputStream in = new FileInputStream(marker); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int count; while ((count = in.read(buffer)) != -1) bytes.write(buffer, 0, count); setup = bytes.toString("UTF-8");
        } catch (IOException ignored) { }
        marker.delete();
        Diagnostics.note(Diagnostics.SLICER, "the previous slice did not finish: the app was closed while slicing (" + setup + ")");
        status.setText("The last slice did not finish: Android closed the app while it was slicing, most likely for memory. Try fewer copies, a smaller scale or a thicker layer height.");
        status.setTextColor(error);
    }

    /** Models handed over by another app: "Open with" (VIEW) or "Share" (SEND, SEND_MULTIPLE). */
    static List<Uri> incomingModels(Intent intent) {
        List<Uri> uris = new ArrayList<>();
        if (intent == null || intent.getAction() == null) return uris;
        switch (intent.getAction()) {
            case Intent.ACTION_VIEW: if (intent.getData() != null) uris.add(intent.getData()); break;
            case Intent.ACTION_SEND: { Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM); if (uri != null) uris.add(uri); break; }
            case Intent.ACTION_SEND_MULTIPLE: { ArrayList<Uri> list = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM); if (list != null) for (Uri uri : list) if (uri != null) uris.add(uri); break; }
            default: break;
        }
        if (uris.isEmpty() && intent.getClipData() != null)
            for (int i = 0; i < intent.getClipData().getItemCount(); i++) if (intent.getClipData().getItemAt(i).getUri() != null) uris.add(intent.getClipData().getItemAt(i).getUri());
        return uris;
    }

    /** The model file extension from its name, or else from its MIME type (apps often share without one); "" if neither. */
    static String modelExtension(String name, String mime) {
        String extension = name != null && name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        if (MODEL_TYPES.contains(extension)) return extension;
        String type = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        switch (type) {
            case "model/stl": case "model/x.stl-binary": case "model/x.stl-ascii": case "application/sla": case "application/vnd.ms-pki.stl": case "application/x-navistyle": return "stl";
            case "model/3mf": case "application/vnd.ms-package.3dmanufacturing-3dmodel+xml": return "3mf";
            case "model/obj": return "obj";
            case "model/step": case "application/step": case "application/x-step": case "model/x.step": return "step";
            case "model/amf": case "application/x-amf": return "amf";
            default: return "";
        }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (slotList == null) return; // slicer not included
        ArrayList<String> paths = new ArrayList<>(); for (File file : models) paths.add(file.getAbsolutePath());
        state.putStringArrayList("models", paths);
        int[] assigned = new int[modelSlots.size()]; for (int i = 0; i < assigned.length; i++) assigned[i] = modelSlots.get(i);
        state.putIntArray("modelSlots", assigned);
        org.json.JSONArray list = new org.json.JSONArray();
        try {
            for (Slot slot : slots) {
                org.json.JSONObject item = new org.json.JSONObject().put("preset", selected(slot.preset) != null ? selected(slot.preset) : slot.wanted == null ? "" : slot.wanted)
                    .put("colour", slot.colour == null ? "" : slot.colour);
                if (slot.source != null) item.put("tray", new org.json.JSONObject().put("canvas_id", slot.source.canvasId).put("tray_id", slot.source.trayId)
                    .put("type", slot.source.type).put("name", slot.source.name).put("brand", slot.source.brand).put("colour", slot.source.colour == null ? "" : slot.source.colour));
                list.put(item);
            }
        } catch (org.json.JSONException impossible) { throw new IllegalStateException(impossible); }
        state.putString("slots", list.toString());
        state.putString("printer", selected(printerSpinner) != null ? selected(printerSpinner) : restoredPrinter);
        state.putString("process", selected(processSpinner) != null ? selected(processSpinner) : restoredProcess);
        state.putString("infill", infill.getText().toString());
        state.putInt("support", supportSpinner.getSelectedItemPosition()); state.putInt("brim", brimSpinner.getSelectedItemPosition());
        ArrayList<String> files = new ArrayList<>(); for (File file : slicedFiles) files.add(file.getAbsolutePath());
        state.putStringArrayList("slicedFiles", files); state.putStringArrayList("slicedNames", new ArrayList<>(slicedNames));
        if (sliced != null && resultCard.getVisibility() == View.VISIBLE) {
            state.putString("sliced", sliced.getAbsolutePath()); state.putString("resultText", resultText.getText().toString());
        }
        state.putString("slicedName", slicedName);
        if (inspected != null) state.putString("inspected", inspected.toString());
        state.putInt("plate", plate); state.putBoolean("projectSettings", projectSettings);
        state.putString("customOverrides", new org.json.JSONObject(customOverrides).toString());
        if (placements != null) state.putString("placements", placements.toString());
        if (!slicing && !busy) state.putString("status", status.getText().toString());
    }

    /** Puts back what onSaveInstanceState kept: models, filament slots, quick settings and the last result. */
    private void restore(Bundle saved) {
        restored = saved;
        ArrayList<String> paths = saved.getStringArrayList("models");
        if (paths != null) for (String path : paths) { File file = new File(path); if (file.isFile()) models.add(file); }
        int[] assigned = saved.getIntArray("modelSlots");
        if (assigned != null && assigned.length == models.size()) for (int value : assigned) modelSlots.add(value);
        try {
            org.json.JSONArray list = new org.json.JSONArray(saved.getString("slots", "[]"));
            if (list.length() > 0) while (slots.size() > 0) { Slot slot = slots.remove(slots.size() - 1); slotList.removeView(slot.row); }
            for (int i = 0; i < list.length() && i < TrayPlan.MAX_TOOLS; i++) {
                org.json.JSONObject item = list.getJSONObject(i), tray = item.optJSONObject("tray");
                addSlot(tray == null ? null : new TrayPlan.Tray(tray.getInt("canvas_id"), tray.getInt("tray_id"), tray.optString("type"), tray.optString("name"),
                    tray.optString("brand"), TrayPlan.colour(tray.optString("colour"))));
                Slot slot = slots.get(slots.size() - 1);
                slot.wanted = item.optString("preset").isEmpty() ? null : item.optString("preset");
                slot.colour = TrayPlan.colour(item.optString("colour")); showSwatch(slot);
            }
        } catch (org.json.JSONException ignored) { }
        restoredPrinter = saved.getString("printer"); restoredProcess = saved.getString("process");
        infill.setText(saved.getString("infill", ""));
        supportSpinner.setSelection(saved.getInt("support", 0)); brimSpinner.setSelection(saved.getInt("brim", 0));
        slicedName = saved.getString("slicedName");
        String path = saved.getString("sliced");
        if (path != null && new File(path).isFile()) {
            sliced = new File(path); resultText.setText(saved.getString("resultText", "")); resultCard.setVisibility(View.VISIBLE); showPreview(sliced);
            ArrayList<String> files = saved.getStringArrayList("slicedFiles"), names = saved.getStringArrayList("slicedNames");
            if (files != null && names != null && files.size() == names.size()) for (int i = 0; i < files.size(); i++) { slicedFiles.add(new File(files.get(i))); slicedNames.add(names.get(i)); }
            if (slicedFiles.isEmpty()) { slicedFiles.add(sliced); slicedNames.add(slicedName); }
            uploadPrint.setText(slicedFiles.size() > 1 ? "Upload all " + slicedFiles.size() + " plates" : "Upload and print…");
        }
        try {
            if (saved.getString("inspected") != null) inspected = new org.json.JSONObject(saved.getString("inspected"));
            if (saved.getString("placements") != null) placements = new org.json.JSONArray(saved.getString("placements"));
            org.json.JSONObject custom = new org.json.JSONObject(saved.getString("customOverrides", "{}"));
            for (Iterator<String> keys = custom.keys(); keys.hasNext(); ) { String key = keys.next(); customOverrides.put(key, custom.getString(key)); }
        } catch (org.json.JSONException ignored) { }
        plate = saved.getInt("plate", 0); projectSettings = saved.getBoolean("projectSettings", true);
        showModels(); showProject(); showLayout(); showSettingsSummary();
        if (saved.getString("status") != null) status.setText(saved.getString("status"));
    }

    @Override protected void onDestroy() {
        if (current == this) current = null;
        if (isFinishing() && !isChangingConfigurations()) cancelRequested.set(true);
        if (bound) unbindService(connection);
        super.onDestroy();
    }

    private void build() {
        LinearLayout modelCard = card("Model");
        modelsLabel = label(modelCard, "Choose one or more STL, 3MF, OBJ, Draco or STEP files. Several files are arranged on one plate.", 14, muted, false);
        chooseModels = button(modelCard, "Choose model files", () -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT); intent.setType("*/*"); intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true); startActivityForResult(intent, PICK_MODELS);
        }, false);
        projectBox = new LinearLayout(this); projectBox.setOrientation(LinearLayout.VERTICAL); modelCard.addView(projectBox);
        layoutLabel = label(modelCard, "", 13, muted, false); layoutLabel.setVisibility(View.GONE);
        LinearLayout layoutRow = new LinearLayout(this); layoutRow.setOrientation(LinearLayout.HORIZONTAL); modelCard.addView(layoutRow);
        editPlate = rowButton(layoutRow, "Edit plate…", this::openPlate, false);
        autoLayout = rowButton(layoutRow, "Arrange automatically", () -> { placements = null; showLayout(); }, false);
        autoLayout.setVisibility(View.GONE);

        LinearLayout presetCard = card("Presets");
        label(presetCard, "Printer", 12, muted, false); printerSpinner = spinner(presetCard);
        label(presetCard, "Process", 12, muted, false); processSpinner = spinner(presetCard);
        printerSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { loadCompatible(selected(printerSpinner)); }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });

        LinearLayout filamentCard = card("Filaments");
        label(filamentCard, "Slot 1 prints as tool T0, slot 2 as T1, and so on. A CANVAS tray gives a slot its material and colour, and Print setup offers the same trays later.", 13, muted, false);
        fillTrays = button(filamentCard, "Fill from CANVAS trays", this::fillFromTrays, false);
        traysNote = label(filamentCard, "", 12, muted, false); traysNote.setVisibility(View.GONE);
        slotList = new LinearLayout(this); slotList.setOrientation(LinearLayout.VERTICAL); filamentCard.addView(slotList);
        LinearLayout slotButtons = new LinearLayout(this); slotButtons.setOrientation(LinearLayout.HORIZONTAL); filamentCard.addView(slotButtons);
        addSlot = rowButton(slotButtons, "Add filament", () -> { addSlot(null); showModels(); updateButtons(); }, false);
        removeSlot = rowButton(slotButtons, "Remove last", () -> { removeLastSlot(); showModels(); updateButtons(); }, false);
        modelAssign = new LinearLayout(this); modelAssign.setOrientation(LinearLayout.VERTICAL); filamentCard.addView(modelAssign);
        addSlot(null);

        LinearLayout settingsCard = card("Quick settings");
        label(settingsCard, "Leave a setting on “Preset” to use the process preset's value.", 13, muted, false);
        label(settingsCard, "Infill density (%)", 12, muted, false);
        infill = new EditText(this); infill.setHint("Preset"); infill.setHintTextColor(muted); infill.setTextColor(ink); infill.setSingleLine(true);
        infill.setInputType(InputType.TYPE_CLASS_NUMBER); infill.setBackgroundTintList(ColorStateList.valueOf(teal)); settingsCard.addView(infill, new LinearLayout.LayoutParams(-1, dp(52)));
        label(settingsCard, "Supports", 12, muted, false);
        supportSpinner = spinner(settingsCard); fill(supportSpinner, Arrays.asList("Preset", "Off", "Normal (auto)", "Tree (auto)"), "Preset");
        label(settingsCard, "Brim", 12, muted, false);
        brimSpinner = spinner(settingsCard); fill(brimSpinner, Arrays.asList("Preset", "Off", "Auto", "Outer only"), "Preset");
        settingsSummary = label(settingsCard, "", 13, muted, false);
        allSettings = button(settingsCard, "All settings…", this::openSettings, false);
        showSettingsSummary();

        LinearLayout sliceCard = actionBar; actionBar.setVisibility(View.VISIBLE);
        status = label(sliceCard, "Loading Elegoo presets…", 14, ink, false); status.setPadding(0, dp(2), 0, dp(2)); status.setMaxLines(3);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); progress.setMax(100); progress.setProgressTintList(ColorStateList.valueOf(teal));
        progress.setVisibility(View.GONE); sliceCard.addView(progress, new LinearLayout.LayoutParams(-1, dp(12)));
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); sliceCard.addView(row);
        slice = rowButton(row, "Slice", this::startSlice, true);
        cancel = rowButton(row, "Cancel", () -> { cancelRequested.set(true); status.setText("Cancelling…"); }, false);

        resultCard = card("Result"); resultCard.setVisibility(View.GONE);
        preview = new ImageView(this); preview.setContentDescription("Preview image embedded in the G-code"); preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setVisibility(View.GONE); resultCard.addView(preview, new LinearLayout.LayoutParams(-1, dp(160)));
        resultText = label(resultCard, "", 14, ink, false); resultText.setTextIsSelectable(true);
        uploadPrint = button(resultCard, "Upload and print…", this::uploadAndPrint, true);
        printerHint = label(resultCard, "", 12, muted, false);
        useInFiles = button(resultCard, "Send to Files tab", () -> {
            Intent handOver = new Intent().putExtra(RESULT_FILE, sliced.getAbsolutePath()).putExtra(RESULT_NAME, slicedName);
            if (getCallingActivity() != null) setResult(RESULT_OK, handOver);
            else startActivity(handOver.setClass(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)); // opened from another app
            finish();
        }, false);
        button(resultCard, "Preview toolpath", () -> startActivity(new Intent(this, GcodeViewerActivity.class)
            .putExtra(GcodeViewerActivity.EXTRA_FILE, sliced.getAbsolutePath()).putExtra(GcodeViewerActivity.EXTRA_NAME, slicedName)), false);
        saveCopy = button(resultCard, "Save G-code…", () -> {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT); intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/octet-stream"); intent.putExtra(Intent.EXTRA_TITLE, slicedName); startActivityForResult(intent, SAVE);
        }, false);

        updateButtons();
    }

    private void loadPresets() {
        busy = true; updateButtons();
        worker.execute(() -> {
            try {
                engine(getApplicationContext());
                String[] printers = engine.presets(NativeSlicer.PRINTER, null);
                main.post(() -> {
                    if (isDestroyed()) return;
                    busy = slicing;
                    if (!slicing && (restored == null || restored.getString("status") == null)) status.setText("Ready.");
                    fill(printerSpinner, Arrays.asList(printers), restoredPrinter != null && Arrays.asList(printers).contains(restoredPrinter) ? restoredPrinter : settings.getString("slicePrinter", DEFAULT_PRINTER));
                    updateButtons();
                });
            } catch (Exception failure) {
                Diagnostics.note(Diagnostics.SLICER, "presets failed to load: " + failure.getMessage());
                main.post(() -> { if (!isDestroyed()) { busy = false; status.setText("The slicer could not start: " + failure.getMessage()); status.setTextColor(error); updateButtons(); } });
            }
        });
    }

    private void loadCompatible(String printer) {
        if (printer == null) return;
        busy = true; updateButtons();
        worker.execute(() -> {
            String[] processes, filaments;
            try { processes = engine.presets(NativeSlicer.PROCESS, printer); filaments = engine.presets(NativeSlicer.FILAMENT, printer); }
            catch (RuntimeException failure) { processes = new String[0]; filaments = new String[0]; }
            String[] p = processes, f = filaments;
            main.post(() -> {
                if (isDestroyed()) return;
                busy = slicing;
                String process = restoredProcess != null && Arrays.asList(p).contains(restoredProcess) ? restoredProcess : remembered(p, "sliceProcess", "0.20mm Standard");
                restoredProcess = null;
                fill(processSpinner, Arrays.asList(p), process);
                filamentPresets = f;
                for (int i = 0; i < slots.size(); i++) {
                    Slot slot = slots.get(i); String current = selected(slot.preset);
                    String preferred = slot.wanted != null && Arrays.asList(f).contains(slot.wanted) ? slot.wanted : slot.source != null ? TrayPlan.preset(slot.source, Arrays.asList(f), null) : null;
                    slot.wanted = null;
                    if (preferred == null) preferred = current != null && Arrays.asList(f).contains(current) ? current : remembered(f, i == 0 ? "sliceFilament" : "sliceFilament" + (i + 1), "Elegoo PLA @");
                    fill(slot.preset, Arrays.asList(f), preferred);
                }
                updateButtons();
            });
        });
    }

    /** The quick settings as config overrides, or null when one is invalid (the status line says why). */
    private Map<String, String> quickOverrides() {
        Map<String, String> overrides = new LinkedHashMap<>();
        String density = infill.getText().toString().trim();
        if (!density.isEmpty()) {
            int value; try { value = Integer.parseInt(density); } catch (NumberFormatException e) { value = -1; }
            if (value < 0 || value > 100) { status.setText("Infill density must be 0 to 100."); status.setTextColor(error); return null; }
            overrides.put("sparse_infill_density", value + "%");
        }
        switch (supportSpinner.getSelectedItemPosition()) {
            case 1: overrides.put("enable_support", "0"); break;
            case 2: overrides.put("enable_support", "1"); overrides.put("support_type", "normal(auto)"); break;
            case 3: overrides.put("enable_support", "1"); overrides.put("support_type", "tree(auto)"); break;
            default: break;
        }
        switch (brimSpinner.getSelectedItemPosition()) {
            case 1: overrides.put("brim_type", "no_brim"); break;
            case 2: overrides.put("brim_type", "auto_brim"); break;
            case 3: overrides.put("brim_type", "outer_only"); break;
            default: break;
        }
        return overrides;
    }

    /** Settings from the settings screen go back into the quick settings where those can show them. */
    private void takeOverrides(Map<String, String> overrides) {
        customOverrides.clear(); customOverrides.putAll(overrides);
        String density = customOverrides.get("sparse_infill_density");
        if (density != null && density.matches("\\d{1,3}%")) { infill.setText(density.substring(0, density.length() - 1)); customOverrides.remove("sparse_infill_density"); }
        else infill.setText("");
        String support = customOverrides.get("enable_support"), type = customOverrides.get("support_type");
        int supportChoice = 0;
        if ("0".equals(support) && type == null) supportChoice = 1;
        else if ("1".equals(support) && "normal(auto)".equals(type)) supportChoice = 2;
        else if ("1".equals(support) && "tree(auto)".equals(type)) supportChoice = 3;
        if (supportChoice > 0) { customOverrides.remove("enable_support"); customOverrides.remove("support_type"); }
        supportSpinner.setSelection(supportChoice);
        int brimChoice = Arrays.asList("", "no_brim", "auto_brim", "outer_only").indexOf(customOverrides.getOrDefault("brim_type", ""));
        if (brimChoice > 0) customOverrides.remove("brim_type");
        brimSpinner.setSelection(Math.max(0, brimChoice));
        showSettingsSummary();
    }

    private void showSettingsSummary() {
        settingsSummary.setText(customOverrides.isEmpty() ? "Everything else follows the process preset." : customOverrides.size() + " more setting(s) changed in All settings.");
        settingsSummary.setTextColor(customOverrides.isEmpty() ? muted : teal);
    }

    /** What to slice: presets, filament slots, settings, 3MF plate and the layout. Null when something is missing or invalid. */
    private NativeSlicer.Selection selection(boolean withPlacements) {
        String printer = selected(printerSpinner), process = selected(processSpinner);
        List<String> filaments = new ArrayList<>();
        for (Slot slot : slots) filaments.add(selected(slot.preset));
        if (printer == null || process == null || filaments.contains(null)) return null;
        Map<String, String> quick = quickOverrides();
        if (quick == null) return null;
        NativeSlicer.Selection selection = new NativeSlicer.Selection(printer, process, filaments);
        for (Slot slot : slots) selection.colours.add(slot.colour);
        int[] assignment = new int[models.size()];
        for (int i = 0; i < assignment.length; i++) assignment[i] = slots.size() > 1 && i < modelSlots.size() ? modelSlots.get(i) : 0;
        selection.modelFilaments = assignment;
        selection.overrides.putAll(customOverrides); selection.overrides.putAll(quick);
        selection.plate = plate == ALL_PLATES ? 1 : plate; selection.projectSettings = projectSettings && project() != null;
        if (withPlacements && placements != null)
            for (int i = 0; i < placements.length(); i++) {
                org.json.JSONObject p = placements.optJSONObject(i);
                selection.placements.add(new double[] {p.optInt("file"), p.optInt("object"), p.optDouble("x"), p.optDouble("y"), p.optDouble("rotation"), p.optDouble("scale", 1)});
            }
        return selection;
    }

    /** Rough peak memory of this slice, or null before the models were inspected. */
    private SliceEstimate estimate(NativeSlicer.Selection selection) {
        if (inspected == null) return null;
        Map<String, Double> scales = null;
        if (!selection.placements.isEmpty()) {
            scales = new HashMap<>();
            for (double[] p : selection.placements) scales.merge((int) p[0] + ":" + (int) p[1], p[5], Math::max);
        }
        double layer = 0.2;
        try { if (selection.overrides.containsKey("layer_height")) layer = Double.parseDouble(selection.overrides.get("layer_height")); } catch (NumberFormatException ignored) { }
        return SliceEstimate.of(inspected, scales, layer);
    }

    private void startSlice() {
        NativeSlicer.Selection selection = selection(true);
        if (models.isEmpty() || selection == null) return;
        String printer = selection.printer, process = selection.process;
        List<String> filaments = new ArrayList<>(selection.filaments), colours = new ArrayList<>(selection.colours);
        TrayPlan plan = plan();
        // Warn before a slice that likely needs more memory than the phone has free; Android would close the app.
        SliceEstimate need = estimate(selection);
        android.app.ActivityManager.MemoryInfo memory = new android.app.ActivityManager.MemoryInfo();
        ((android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(memory);
        if (need != null && need.risky(memory.availMem) && !largeConfirmed) {
            new AlertDialog.Builder(this).setTitle("This is a big slice")
                .setMessage(String.format(Locale.getDefault(), "Slicing this plate may need %s of memory; the phone has about %d MB free. Android may close the app while it slices.\n\nClose other apps, use fewer copies or a smaller scale, or try anyway.",
                    need.describe(), memory.availMem / (1024 * 1024)))
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Slice anyway", (d, w) -> { largeConfirmed = true; startSlice(); largeConfirmed = false; }).show();
            return;
        }
        SharedPreferences.Editor remember = settings.edit().putString("slicePrinter", printer).putString("sliceProcess", process);
        for (int i = 0; i < filaments.size(); i++) remember.putString(i == 0 ? "sliceFilament" : "sliceFilament" + (i + 1), filaments.get(i));
        remember.apply();
        String base = models.get(0).getName().replaceFirst("\\.[^.]+$", "");
        slicedName = (models.size() > 1 ? base + "_plate" : base) + ".gcode";
        File outputDir = new File(getCacheDir(), "sliced"); outputDir.mkdirs();
        // One plate, or every plate of the project with a G-code each.
        List<Integer> plates = new ArrayList<>();
        if (plate == ALL_PLATES) for (int p = 1; p <= projectPlates(); p++) plates.add(p); else plates.add(plate);
        List<String> names = new ArrayList<>(); List<File> outputs = new ArrayList<>();
        for (int p : plates) {
            String fileName = plates.size() > 1 ? base + "_plate" + p + ".gcode" : slicedName;
            names.add(fileName); outputs.add(new File(outputDir, fileName));
        }
        List<File> input = new ArrayList<>(models);
        busy = true; cancelRequested.set(false); resultCard.setVisibility(View.GONE); progress.setProgress(0); progress.setVisibility(View.VISIBLE);
        status.setTextColor(ink); status.setText("Slicing…"); updateButtons();
        slicing = true; slicingPercent = 0; slicingText = "Slicing…";
        StringBuilder inputs = new StringBuilder();
        for (File model : input) inputs.append(inputs.length() > 0 ? ", " : "").append(model.getName()).append(" (").append(size(model.length())).append(")");
        String setup = inputs + " · " + process + " · " + filaments.size() + " filament(s)" + (selection.overrides.isEmpty() ? "" : " · " + selection.overrides.size() + " override(s)")
            + (plate == ALL_PLATES ? " · all " + plates.size() + " plates" : selection.plate > 0 ? " · plate " + selection.plate : "") + (selection.placements.isEmpty() ? "" : " · " + selection.placements.size() + " placed cop(ies)")
            + (need == null ? "" : " · estimated " + need.describe());
        markRunning(setup);
        long started = System.currentTimeMillis();
        worker.execute(() -> {
            try {
                List<NativeSlicer.Result> results = new ArrayList<>();
                for (int i = 0; i < plates.size(); i++) {
                    String prefix = plates.size() > 1 ? "Plate " + plates.get(i) + " of " + plates.size() + " · " : "";
                    int part = i, count = plates.size();
                    if (plates.size() > 1 || plate == ALL_PLATES) selection.plate = plates.get(i);
                    results.add(engine.slice(input, selection, outputs.get(i), (percent, text) -> {
                        int overall = (part * 100 + percent) / count;
                        main.post(() -> {
                            slicingPercent = overall; if (!cancelRequested.get()) slicingText = prefix + percent + "% · " + text;
                            SliceActivity screen = current;
                            if (screen != null && !screen.isDestroyed()) { screen.progress.setProgress(overall); if (!cancelRequested.get()) screen.status.setText(slicingText); }
                        });
                        return !cancelRequested.get();
                    }));
                }
                long elapsed = System.currentTimeMillis() - started;
                double seconds = 0, grams = 0; for (NativeSlicer.Result r : results) { seconds += r.printSeconds; grams += r.filamentGrams; }
                Diagnostics.note(Diagnostics.SLICER, String.format(Locale.ROOT, "sliced in %.1f s · %s · estimate %s, %.1f g · %s",
                    elapsed / 1000.0, setup, duration(seconds), grams, Diagnostics.memory()));
                clearRunning();
                main.post(() -> { slicing = false; SliceActivity screen = current; if (screen != null && !screen.isDestroyed()) screen.finished(results, plates, names, elapsed, printer, process, filaments, colours, plan); });
            } catch (IOException failure) {
                long elapsed = System.currentTimeMillis() - started;
                boolean cancelled = cancelRequested.get();
                Diagnostics.note(Diagnostics.SLICER, String.format(Locale.ROOT, "%s after %.1f s · %s · %s", cancelled ? "cancelled" : "failed: " + failure.getMessage(), elapsed / 1000.0, setup, Diagnostics.memory()));
                clearRunning();
                main.post(() -> {
                    slicing = false;
                    SliceActivity screen = current;
                    if (screen == null || screen.isDestroyed()) return;
                    screen.busy = false; screen.progress.setVisibility(View.GONE);
                    screen.status.setText(cancelled ? "Slicing cancelled." : "Slicing failed: " + failure.getMessage());
                    screen.status.setTextColor(cancelled ? screen.ink : screen.error); screen.updateButtons();
                });
            }
        });
    }

    private void finished(List<NativeSlicer.Result> results, List<Integer> plates, List<String> names, long elapsedMs, String printer, String process,
                          List<String> filaments, List<String> colours, TrayPlan plan) {
        busy = false; progress.setVisibility(View.GONE);
        slicedFiles.clear(); slicedNames.clear();
        for (int i = 0; i < results.size(); i++) { slicedFiles.add(results.get(i).gcode); slicedNames.add(names.get(i)); }
        sliced = slicedFiles.get(0); slicedName = slicedNames.get(0);
        // The print setup dialog offers this plan for a printer file of the same name.
        SharedPreferences.Editor plans = getSharedPreferences(TRAY_PLANS, MODE_PRIVATE).edit();
        for (String name : names) plans.putString(GcodeLibrary.safeName(name), plan.toJson());
        plans.apply();
        status.setText(String.format(Locale.getDefault(), "Sliced %sin %.1f s.", results.size() > 1 ? results.size() + " plates " : "", elapsedMs / 1000.0));
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < results.size(); i++) {
            NativeSlicer.Result result = results.get(i);
            if (results.size() > 1) text.append("Plate ").append(plates.get(i)).append(": ");
            text.append(names.get(i)).append("\n");
            text.append("Estimated print time: ").append(duration(result.printSeconds)).append("\n");
            text.append(String.format(Locale.getDefault(), "Filament: %.1f g (%.2f m)\n", result.filamentGrams, result.filamentMm / 1000.0));
            for (String warning : result.warnings) text.append("Warning: ").append(warning).append("\n");
            if (results.size() > 1) text.append("\n");
        }
        text.append(printer).append("\n").append(process);
        for (int i = 0; i < filaments.size(); i++) {
            TrayPlan.Tool tool = plan.tool(i);
            text.append("\n").append(filaments.size() > 1 ? "T" + i + ": " : "").append(filaments.get(i));
            if (colours.get(i) != null) text.append(" · ").append(colours.get(i));
            if (tool != null) text.append(" · CANVAS ").append(tool.canvasId).append(" tray ").append(tool.trayId);
        }
        resultText.setText(text.toString());
        uploadPrint.setText(slicedFiles.size() > 1 ? "Upload all " + slicedFiles.size() + " plates" : "Upload and print…");
        useInFiles.setText(slicedFiles.size() > 1 ? "Send plate " + plates.get(0) + " to the Files tab" : "Send to Files tab");
        resultCard.setVisibility(View.VISIBLE);
        preview.setVisibility(View.GONE);
        scroll.post(() -> scroll.smoothScrollTo(0, resultCard.getTop() - dp(12)));
        showPreview(sliced);
        updateButtons();
    }

    /** Uploads the result over the local connection; for one file, Print setup opens when it is on the printer. */
    private void uploadAndPrint() {
        if (printer == null || slicedFiles.isEmpty()) return;
        if (!printer.ready() || printer.pinProbe()) { status.setText("Uploading needs the local connection to the printer: connect in Settings, or use Send to Files tab."); status.setTextColor(error); return; }
        for (File file : slicedFiles) if (!file.isFile()) { status.setText("The sliced file is gone; slice again."); status.setTextColor(error); return; }
        if (!printer.uploadFiles(new ArrayList<>(slicedFiles), new ArrayList<>(slicedNames))) return;
        Intent back = new Intent();
        if (slicedFiles.size() == 1) back.putExtra(RESULT_PRINT_SETUP, slicedName);
        if (getCallingActivity() != null) setResult(RESULT_OK, back);
        else startActivity(back.setClass(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        finish();
    }

    /** Plates in the loaded project, or 0. */
    private int projectPlates() {
        org.json.JSONObject file = project();
        org.json.JSONArray plates = file == null ? null : file.optJSONArray("plates");
        return plates == null ? 0 : plates.length();
    }

    /** Shows the preview image the engine embedded (the one the printer's file list will show). */
    private void showPreview(File gcode) {
        worker.execute(() -> {
            android.graphics.Bitmap image = null;
            try { GcodeInspector.Report report = GcodeInspector.inspect(gcode); image = ThumbnailDecoder.decode(report.thumbnail); } catch (Exception ignored) { }
            android.graphics.Bitmap shown = image;
            main.post(() -> { if (!isDestroyed() && shown != null && gcode.equals(sliced)) { preview.setImageBitmap(shown); preview.setVisibility(View.VISIBLE); } });
        });
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null) return;
        if (request == SETTINGS) {
            try {
                org.json.JSONObject changed = new org.json.JSONObject(data.getStringExtra(SliceSettingsActivity.EXTRA_OVERRIDES));
                Map<String, String> map = new LinkedHashMap<>();
                for (Iterator<String> keys = changed.keys(); keys.hasNext(); ) { String key = keys.next(); map.put(key, changed.getString(key)); }
                takeOverrides(map);
            } catch (org.json.JSONException | NullPointerException ignored) { }
            return;
        }
        if (request == PLATE) {
            try { placements = new org.json.JSONArray(data.getStringExtra(PlateActivity.EXTRA_PLACEMENTS)); } catch (org.json.JSONException | NullPointerException ignored) { }
            showLayout();
            return;
        }
        if (request == PICK_MODELS) {
            List<Uri> uris = new ArrayList<>();
            if (data.getClipData() != null) for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
            else if (data.getData() != null) uris.add(data.getData());
            importModels(uris);
        } else if (request == SAVE && data.getData() != null && sliced != null) {
            File source = sliced; Uri target = data.getData();
            worker.execute(() -> {
                String message;
                try (InputStream in = new FileInputStream(source); OutputStream out = getContentResolver().openOutputStream(target)) {
                    if (out == null) throw new IOException("Cannot write there");
                    byte[] buffer = new byte[65536]; int count; while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
                    message = "Saved " + slicedName + ".";
                } catch (IOException failure) { message = "Saving failed: " + failure.getMessage(); }
                String shown = message; main.post(() -> { if (!isDestroyed()) status.setText(shown); });
            });
        }
    }

    private void importModels(List<Uri> uris) {
        busy = true; status.setText("Reading model files…"); status.setTextColor(ink); updateButtons();
        File inputDir = new File(getCacheDir(), "slice-input");
        worker.execute(() -> {
            List<File> imported = new ArrayList<>(); String problem = null;
            deleteChildren(inputDir); inputDir.mkdirs();
            for (Uri uri : uris) {
                String name = displayName(uri), type = null;
                try { type = getContentResolver().getType(uri); } catch (RuntimeException ignored) { }
                String extension = modelExtension(name, type);
                if (extension.isEmpty()) { problem = name + " is not an STL, 3MF, OBJ, Draco or STEP file."; continue; }
                // libslic3r picks the reader by extension; keep it, and a filename the printer accepts later.
                String stem = name.toLowerCase(Locale.ROOT).endsWith("." + extension) ? name.substring(0, name.length() - extension.length() - 1) : name;
                String safe = stem.replaceAll("[^A-Za-z0-9 _.-]", "_");
                if (safe.isEmpty()) safe = "model"; if (safe.length() > 80) safe = safe.substring(0, 80);
                File file = new File(inputDir, safe + "." + extension);
                for (int n = 2; file.exists(); n++) file = new File(inputDir, safe + "_" + n + "." + extension);
                try (InputStream in = getContentResolver().openInputStream(uri); OutputStream out = new FileOutputStream(file)) {
                    if (in == null) throw new IOException("Cannot open " + name);
                    byte[] buffer = new byte[65536]; int count; long total = 0;
                    while ((count = in.read(buffer)) != -1) { total += count; if (total > 512L * 1024 * 1024) throw new IOException(name + " is larger than 512 MiB"); out.write(buffer, 0, count); }
                    imported.add(file);
                } catch (IOException failure) { problem = failure.getMessage(); file.delete(); }
            }
            // What the files hold: objects, 3MF plates and project settings, and simplified meshes for the plate view.
            org.json.JSONObject read = null;
            File meshDir = new File(getCacheDir(), "slice-meshes");
            deleteChildren(meshDir);
            if (!imported.isEmpty()) {
                try { read = engine(getApplicationContext()).inspect(imported, meshDir, PREVIEW_TRIANGLES); }
                catch (IOException failure) { problem = "A model could not be read: " + failure.getMessage(); imported.clear(); }
            }
            String shownProblem = problem;
            org.json.JSONObject shownRead = read;
            main.post(() -> {
                if (isDestroyed()) return;
                inspected = shownRead; plate = 0; placements = null; projectSettings = true;
                busy = slicing; models.clear(); modelSlots.clear(); models.addAll(imported); showModels(); showProject(); showLayout();
                status.setText(shownProblem != null ? shownProblem : "Ready."); status.setTextColor(shownProblem != null ? error : ink);
                resultCard.setVisibility(View.GONE); updateButtons();
            });
        });
    }

    private void showModels() {
        modelAssign.removeAllViews();
        while (modelSlots.size() > models.size()) modelSlots.remove(modelSlots.size() - 1);
        // New models: 3MF files keep their own assignment; others take the next slot in turn.
        while (modelSlots.size() < models.size()) { int i = modelSlots.size(); modelSlots.add(is3mf(models.get(i)) ? 0 : i % Math.max(1, slots.size()) + 1); }
        if (models.isEmpty()) { modelsLabel.setText("Choose one or more STL, 3MF, OBJ, Draco or STEP files. Several files are arranged on one plate."); modelsLabel.setTextColor(muted); return; }
        StringBuilder names = new StringBuilder();
        for (File file : models) names.append(names.length() > 0 ? "\n" : "").append(file.getName()).append(" (").append(size(file.length())).append(")");
        modelsLabel.setText(names.toString()); modelsLabel.setTextColor(ink);
        if (slots.size() < 2) return;
        TextView heading = label(modelAssign, "Filament for each model", 14, ink, true);
        ((LinearLayout.LayoutParams) heading.getLayoutParams()).topMargin = dp(14);
        for (int i = 0; i < models.size(); i++) {
            File model = models.get(i); int index = i;
            List<String> choices = new ArrayList<>();
            choices.add(is3mf(model) ? "As in the 3MF (painting and parts)" : "Slot 1");
            for (int k = is3mf(model) ? 1 : 2; k <= slots.size(); k++) choices.add("Slot " + k + slotSummary(k));
            if (!is3mf(model)) choices.set(0, "Slot 1" + slotSummary(1));
            label(modelAssign, model.getName(), 13, ink, false);
            Spinner spinner = spinner(modelAssign);
            int slot = Math.min(modelSlots.get(i), slots.size());
            fill(spinner, choices, choices.get(is3mf(model) ? slot : Math.max(slot, 1) - 1));
            spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(AdapterView<?> p, View v, int position, long id) { modelSlots.set(index, is3mf(model) ? position : position + 1); }
                @Override public void onNothingSelected(AdapterView<?> p) { }
            });
        }
    }

    /** The first model file that is a 3MF project (plates or settings), or null. */
    private org.json.JSONObject project() {
        org.json.JSONArray files = inspected == null ? null : inspected.optJSONArray("files");
        if (files == null) return null;
        for (int f = 0; f < files.length(); f++) {
            org.json.JSONObject file = files.optJSONObject(f);
            if (file.has("project") || file.optJSONArray("plates") != null && file.optJSONArray("plates").length() > 0) return file;
        }
        return null;
    }

    /** A 3MF project's plate choice, its settings and its filaments. */
    private void showProject() {
        projectBox.removeAllViews();
        org.json.JSONObject file = project();
        if (file == null) return;
        TextView heading = label(projectBox, "3MF project", 14, ink, true);
        ((LinearLayout.LayoutParams) heading.getLayoutParams()).topMargin = dp(10);
        org.json.JSONArray plates = file.optJSONArray("plates");
        if (plates != null && plates.length() > 1) {
            if (models.size() > 1) label(projectBox, "With other model files added, the project's objects are arranged with them; pick its plate below.", 12, muted, false);
            List<String> names = new ArrayList<>();
            for (int i = 0; i < plates.length(); i++) {
                org.json.JSONObject entry = plates.optJSONObject(i);
                int count = entry.optJSONArray("objects") == null ? 0 : entry.optJSONArray("objects").length();
                String name = entry.optString("name");
                names.add("Plate " + (i + 1) + (name.isEmpty() ? "" : " · " + name) + " · " + count + " object" + (count == 1 ? "" : "s"));
            }
            names.add("All " + plates.length() + " plates · one G-code each");
            label(projectBox, "Plate to slice", 12, muted, false);
            Spinner plates_ = spinner(projectBox); fill(plates_, names, names.get(plate == ALL_PLATES ? names.size() - 1 : Math.max(0, Math.min(plate, names.size() - 1) - 1)));
            plates_.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(AdapterView<?> p, View v, int position, long id) {
                    int chosen = position == names.size() - 1 ? ALL_PLATES : position + 1;
                    if (chosen != (plate == 0 ? 1 : plate)) { plate = chosen; placements = null; showLayout(); }
                    updateButtons();
                }
                @Override public void onNothingSelected(AdapterView<?> p) { }
            });
        }
        org.json.JSONObject settingsOf = file.optJSONObject("project");
        if (settingsOf != null) {
            CheckBox use = new CheckBox(this); use.setText("Use the project's print settings"); use.setTextColor(ink); use.setButtonTintList(ColorStateList.valueOf(teal));
            use.setChecked(projectSettings); use.setOnCheckedChangeListener((v, checked) -> projectSettings = checked); projectBox.addView(use);
            String printer = settingsOf.optString("printer");
            label(projectBox, "Saved with " + settingsOf.optString("process") + (settingsOf.has("layer_height") ? String.format(Locale.getDefault(), " (%.2f mm layers)", settingsOf.optDouble("layer_height")) : "")
                + (printer.isEmpty() ? "" : " for " + printer) + ". They apply on top of the process preset; your changes below still win.", 12, muted, false);
            org.json.JSONArray filaments = settingsOf.optJSONArray("filaments");
            if (filaments != null && filaments.length() > 0)
                button(projectBox, "Use the project's " + filaments.length() + " filament" + (filaments.length() == 1 ? "" : "s"), this::useProjectFilaments, false);
        }
    }

    /** One slot per project filament, with its preset (when this printer has it) and colour. */
    private void useProjectFilaments() {
        org.json.JSONObject file = project(); if (file == null || file.optJSONObject("project") == null) return;
        org.json.JSONArray names = file.optJSONObject("project").optJSONArray("filaments"), colours = file.optJSONObject("project").optJSONArray("colours");
        if (names == null || names.length() == 0) return;
        while (slots.size() > 1) removeLastSlot();
        for (int i = 1; i < names.length() && i < TrayPlan.MAX_TOOLS; i++) addSlot(null);
        List<String> missing = new ArrayList<>();
        for (int i = 0; i < slots.size(); i++) {
            Slot slot = slots.get(i); String name = names.optString(i);
            if (Arrays.asList(filamentPresets).contains(name)) fill(slot.preset, Arrays.asList(filamentPresets), name); else missing.add(name);
            slot.colour = colours == null ? null : TrayPlan.colour(colours.optString(i)); showSwatch(slot);
        }
        for (int i = 0; i < modelSlots.size(); i++) if (is3mf(models.get(i))) modelSlots.set(i, 0);
        showModels(); updateButtons();
        status.setText(missing.isEmpty() ? "Set up " + slots.size() + " slot(s) from the project." : "Set up " + slots.size() + " slot(s); this printer has no preset named " + String.join(", ", missing) + ", so those slots keep their preset.");
        status.setTextColor(missing.isEmpty() ? ink : error);
    }

    /** Automatic placement, or the plate view's layout. */
    private void showLayout() {
        boolean custom = placements != null;
        layoutLabel.setVisibility(models.isEmpty() ? View.GONE : View.VISIBLE);
        layoutLabel.setText(custom ? "Your layout: " + placements.length() + " cop" + (placements.length() == 1 ? "y" : "ies") + " placed in the plate view."
            : project() != null && models.size() == 1 ? "Placed as in the project." : "Placed automatically.");
        layoutLabel.setTextColor(custom ? teal : muted);
        autoLayout.setVisibility(custom ? View.VISIBLE : View.GONE);
        updateButtons();
    }

    private void openPlate() {
        NativeSlicer.Selection selection = selection(false);
        if (selection == null || inspected == null) return;
        String[] paths = new String[models.size()]; for (int i = 0; i < paths.length; i++) paths[i] = models.get(i).getAbsolutePath();
        String[] colours = new String[slots.size()]; for (int i = 0; i < colours.length; i++) colours[i] = slots.get(i).colour != null ? slots.get(i).colour : "#F2754E";
        Intent intent = new Intent(this, PlateActivity.class).putExtra(PlateActivity.EXTRA_MODELS, paths).putExtra(PlateActivity.EXTRA_SELECTION, selection.toJson())
            .putExtra(PlateActivity.EXTRA_INSPECTED, inspected.toString()).putExtra(PlateActivity.EXTRA_COLOURS, colours).putExtra(PlateActivity.EXTRA_SLOTS, selection.modelFilaments);
        if (placements != null) intent.putExtra(PlateActivity.EXTRA_PLACEMENTS, placements.toString());
        startActivityForResult(intent, PLATE);
    }

    private void openSettings() {
        NativeSlicer.Selection selection = selection(false);
        if (selection == null) return;
        String[] paths = new String[models.size()]; for (int i = 0; i < paths.length; i++) paths[i] = models.get(i).getAbsolutePath();
        startActivityForResult(new Intent(this, SliceSettingsActivity.class).putExtra(SliceSettingsActivity.EXTRA_SELECTION, selection.toJson())
            .putExtra(SliceSettingsActivity.EXTRA_MODELS, project() != null && projectSettings ? paths : new String[0]), SETTINGS);
    }

    private static boolean is3mf(File file) { return file.getName().toLowerCase(Locale.ROOT).endsWith(".3mf"); }
    private String slotSummary(int k) {
        Slot slot = slots.get(k - 1); String preset = selected(slot.preset);
        return (preset == null ? "" : " · " + preset.replaceFirst(" @.*$", "")) + (slot.colour == null ? "" : " " + slot.colour);
    }

    private void addSlot(TrayPlan.Tray tray) {
        if (slots.size() >= TrayPlan.MAX_TOOLS) return;
        Slot slot = new Slot(); int number = slots.size() + 1;
        slot.row = new LinearLayout(this); slot.row.setOrientation(LinearLayout.VERTICAL); slot.row.setPadding(0, dp(6), 0, dp(6));
        LinearLayout header = new LinearLayout(this); header.setOrientation(LinearLayout.HORIZONTAL); header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        slot.title = new TextView(this); slot.title.setText("Slot " + number + " · T" + (number - 1)); slot.title.setTextColor(ink); slot.title.setTextSize(14); slot.title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.addView(slot.title, new LinearLayout.LayoutParams(0, -2, 1));
        slot.swatch = new TextView(this); slot.swatch.setTextSize(12); slot.swatch.setGravity(android.view.Gravity.CENTER); slot.swatch.setMinHeight(dp(36)); slot.swatch.setPadding(dp(10), 0, dp(10), 0);
        slot.swatch.setOnClickListener(view -> chooseColour(slot)); slot.swatch.setContentDescription("Filament colour for slot " + number);
        header.addView(slot.swatch, new LinearLayout.LayoutParams(-2, dp(36)));
        slot.row.addView(header);
        label(slot.row, "Preset", 12, muted, false); slot.preset = spinner(slot.row);
        slot.preset.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int position, long id) { if (slots.size() > 1 && !models.isEmpty()) showModels(); updateButtons(); }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        });
        label(slot.row, "CANVAS tray", 12, muted, false); slot.tray = spinner(slot.row);
        slot.tray.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int position, long id) {
                TrayPlan.Tray chosen = position > 0 && position <= slot.trayChoices.size() ? slot.trayChoices.get(position - 1) : null;
                if (chosen == slot.source) return; // set by code, or unchanged
                if (chosen == null && slot.source != null && slot.trayChoices.isEmpty()) return; // trays not reported right now: keep the plan
                applyTray(slot, chosen); showModels(); updateButtons();
            }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        });
        slots.add(slot); slotList.addView(slot.row);
        if (filamentPresets.length > 0)
            fill(slot.preset, Arrays.asList(filamentPresets), remembered(filamentPresets, slots.size() == 1 ? "sliceFilament" : "sliceFilament" + slots.size(), "Elegoo PLA @"));
        fillTrayChoices(slot);
        applyTray(slot, tray);
    }

    private void removeLastSlot() {
        if (slots.size() <= 1) return;
        Slot slot = slots.remove(slots.size() - 1); slotList.removeView(slot.row);
        for (int i = 0; i < modelSlots.size(); i++) if (modelSlots.get(i) > slots.size()) modelSlots.set(i, slots.size());
    }

    /** Takes a tray's material and colour (null clears the tray, keeping the preset and colour). */
    private void applyTray(Slot slot, TrayPlan.Tray tray) {
        TrayPlan.Tray match = null;
        if (tray != null) for (TrayPlan.Tray choice : slot.trayChoices) if (choice.same(tray.canvasId, tray.trayId)) match = choice;
        slot.source = match != null ? match : tray;
        if (tray != null) {
            slot.colour = tray.colour;
            String preset = TrayPlan.preset(tray, Arrays.asList(filamentPresets), null);
            if (preset != null) fill(slot.preset, Arrays.asList(filamentPresets), preset);
        }
        if (tray != null && match == null) fillTrayChoices(slot); // not among the reported trays: list it so the plan shows
        else { int index = match == null ? 0 : slot.trayChoices.indexOf(match) + 1; if (slot.tray.getSelectedItemPosition() != index) slot.tray.setSelection(index); }
        showSwatch(slot);
    }

    private void showSwatch(Slot slot) {
        GradientDrawable shape = new GradientDrawable(); shape.setCornerRadius(dp(10));
        if (slot.colour != null) {
            int rgb = Color.parseColor(slot.colour); shape.setColor(rgb);
            boolean light = (Color.red(rgb) * 299 + Color.green(rgb) * 587 + Color.blue(rgb) * 114) / 1000 > 140;
            slot.swatch.setTextColor(light ? 0xff17252c : Color.WHITE); slot.swatch.setText(slot.colour);
            shape.setStroke(dp(1), muted);
        } else {
            shape.setColor(buttonColor); slot.swatch.setTextColor(teal); slot.swatch.setText("Preset colour");
        }
        slot.swatch.setBackground(shape);
    }

    private void chooseColour(Slot slot) {
        if (busy) return;
        EditText hex = new EditText(this); hex.setSingleLine(true); hex.setHint("#RRGGBB"); hex.setText(slot.colour == null ? "" : slot.colour);
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(dp(20), dp(8), dp(20), 0); body.addView(hex);
        new AlertDialog.Builder(this).setTitle("Slot " + (slots.indexOf(slot) + 1) + " colour")
            .setMessage("Used for the preview image, the G-code's filament colours and the flushing volumes between colours.").setView(body)
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Preset colour", (d, w) -> { slot.colour = null; showSwatch(slot); showModels(); })
            .setPositiveButton("Use", (d, w) -> {
                String colour = TrayPlan.colour(hex.getText().toString());
                if (colour == null) { status.setText("Colours are six hex digits, like #D02828."); status.setTextColor(error); return; }
                slot.colour = colour; showSwatch(slot); showModels();
            }).show();
    }

    /** The trays the printer reported last (possibly stale), or an empty list. */
    private List<TrayPlan.Tray> reportedTrays() { return printer == null ? new ArrayList<>() : TrayPlan.trays(printer.canvas); }

    private void fillTrayChoices(Slot slot) {
        slot.trayChoices = reportedTrays();
        List<String> choices = new ArrayList<>(); choices.add(slot.trayChoices.isEmpty() && slot.source == null ? "No trays reported (choose at print start)" : "No tray (choose at print start)");
        // Keep a planned tray when the printer has not reported trays (yet).
        if (slot.source != null) {
            TrayPlan.Tray match = null;
            for (TrayPlan.Tray tray : slot.trayChoices) if (tray.same(slot.source.canvasId, slot.source.trayId)) match = tray;
            if (match == null) slot.trayChoices.add(slot.source); else slot.source = match;
        }
        for (TrayPlan.Tray tray : slot.trayChoices) choices.add(tray.label());
        fill(slot.tray, choices, slot.source == null ? choices.get(0) : slot.source.label());
    }

    private void refreshTrays() {
        if (isDestroyed() || slotList == null) return;
        for (Slot slot : slots) fillTrayChoices(slot);
        List<TrayPlan.Tray> trays = reportedTrays();
        boolean fresh = printer != null && printer.canvasFresh();
        traysNote.setVisibility(View.VISIBLE);
        traysNote.setText(trays.isEmpty() ? "No CANVAS trays reported. Connect to the printer (Status tab) to fill slots from its trays."
            : trays.size() + " loaded tray(s) reported" + (fresh ? "." : "; refresh status on the printer tab for the latest."));
        updateButtons();
    }

    private void fillFromTrays() {
        if (printer != null) printer.refresh();
        List<TrayPlan.Tray> trays = reportedTrays();
        if (trays.isEmpty()) { refreshTrays(); status.setText("No loaded CANVAS trays reported yet. Connect to the printer, wait for its status, then try again."); status.setTextColor(error); return; }
        while (slots.size() > 1) removeLastSlot();
        for (Slot slot : slots) fillTrayChoices(slot);
        applyTray(slots.get(0), trays.get(0));
        for (int i = 1; i < trays.size() && i < TrayPlan.MAX_TOOLS; i++) addSlot(trays.get(i));
        refreshTrays();
        status.setText("Filled " + slots.size() + " slot(s) from the CANVAS trays. Check each slot's preset."); status.setTextColor(ink);
        showModels(); updateButtons();
    }

    /** The slot-to-tray plan for the print setup dialog. */
    private TrayPlan plan() {
        List<TrayPlan.Tool> tools = new ArrayList<>();
        for (int i = 0; i < slots.size(); i++) { TrayPlan.Tray tray = slots.get(i).source; if (tray != null) tools.add(new TrayPlan.Tool(i, tray.canvasId, tray.trayId)); }
        return new TrayPlan(slots.size(), tools);
    }

    private void updateButtons() {
        if (slice == null) return;
        boolean presetsReady = selected(printerSpinner) != null && selected(processSpinner) != null;
        for (Slot slot : slots) presetsReady &= selected(slot.preset) != null;
        slice.setEnabled(!busy && !models.isEmpty() && presetsReady);
        boolean slicing = busy && progress.getVisibility() == View.VISIBLE;
        cancel.setEnabled(slicing); cancel.setVisibility(slicing ? View.VISIBLE : View.GONE); slice.setVisibility(slicing ? View.GONE : View.VISIBLE);
        chooseModels.setEnabled(!busy);
        printerSpinner.setEnabled(!busy); processSpinner.setEnabled(!busy);
        for (Slot slot : slots) { slot.preset.setEnabled(!busy); slot.tray.setEnabled(!busy); slot.swatch.setEnabled(!busy); }
        editPlate.setEnabled(!busy && presetsReady && inspected != null && !models.isEmpty() && plate != ALL_PLATES); autoLayout.setEnabled(!busy);
        boolean canUpload = printer != null && printer.ready() && !printer.pinProbe();
        if (uploadPrint != null) uploadPrint.setEnabled(!busy && canUpload && !slicedFiles.isEmpty());
        if (printerHint != null) printerHint.setText(canUpload ? "Uploads over the local connection, then opens Print setup with the CANVAS trays from this slice." : "Connect to the printer locally (Settings) to upload from here; Send to Files tab works without.");
        allSettings.setEnabled(!busy && presetsReady);
        addSlot.setEnabled(!busy && slots.size() < TrayPlan.MAX_TOOLS); removeSlot.setEnabled(!busy && slots.size() > 1); fillTrays.setEnabled(!busy);
        for (int i = 0; i < modelAssign.getChildCount(); i++) modelAssign.getChildAt(i).setEnabled(!busy);
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && cursor.getString(0) != null) return cursor.getString(0);
        } catch (RuntimeException ignored) { }
        String path = uri.getLastPathSegment(); return path != null ? path : "model";
    }

    private static void deleteChildren(File dir) { File[] files = dir.listFiles(); if (files != null) for (File file : files) file.delete(); }
    /** The last used preset when this printer offers it, otherwise the first one containing `fallback`. */
    private String remembered(String[] values, String key, String fallback) {
        String saved = settings.getString(key, null);
        if (saved != null && Arrays.asList(values).contains(saved)) return saved;
        return firstContaining(values, fallback);
    }
    private static String firstContaining(String[] values, String part) { for (String value : values) if (value.contains(part)) return value; return values.length > 0 ? values[0] : ""; }
    private static String size(long bytes) { return bytes >= 1 << 20 ? String.format(Locale.getDefault(), "%.1f MB", bytes / 1048576.0) : (bytes + 1023) / 1024 + " KB"; }
    private static String duration(double seconds) {
        long total = Math.round(seconds), hours = total / 3600, minutes = (total % 3600) / 60;
        return hours > 0 ? hours + " h " + minutes + " min" : minutes > 0 ? minutes + " min " + total % 60 + " s" : total + " s";
    }

    private static String selected(Spinner spinner) { Object item = spinner == null ? null : spinner.getSelectedItem(); return item == null ? null : item.toString(); }
    private void fill(Spinner spinner, List<String> values, String preferred) {
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, values) {
            @Override public View getView(int position, View convert, android.view.ViewGroup parent) { TextView view = (TextView) super.getView(position, convert, parent); view.setTextColor(ink);
                view.setSingleLine(false); view.setMaxLines(2); return view; } // preset names are long: wrap instead of cutting them off
            @Override public View getDropDownView(int position, View convert, android.view.ViewGroup parent) { TextView view = (TextView) super.getDropDownView(position, convert, parent); view.setSingleLine(false); view.setMaxLines(3); return view; }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        int index = values.indexOf(preferred); if (index >= 0) spinner.setSelection(index);
    }
    private Spinner spinner(LinearLayout parent) {
        Spinner spinner = new Spinner(this); spinner.setBackgroundTintList(ColorStateList.valueOf(teal));
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int position, long id) { updateButtons(); }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        });
        spinner.setMinimumHeight(dp(48)); parent.addView(spinner, new LinearLayout.LayoutParams(-1, -2)); return spinner;
    }
    private LinearLayout card(String title) {
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable shape = new GradientDrawable(); shape.setColor(surface); shape.setCornerRadius(dp(20)); card.setBackground(shape);
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2); layout.topMargin = dp(12); content.addView(card, layout);
        if (title != null) label(card, title, 17, ink, true);
        return card;
    }
    private TextView label(LinearLayout parent, String text, int size, int color, boolean bold) {
        TextView view = new TextView(this); view.setText(text); view.setTextSize(size); view.setTextColor(color); if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setPadding(0, dp(5), 0, dp(7)); parent.addView(view, new LinearLayout.LayoutParams(-1, -2)); return view;
    }
    private Button button(LinearLayout parent, String text, Runnable action, boolean primary) {
        Button button = styled(text, action, primary); LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2); layout.topMargin = dp(6); parent.addView(button, layout); return button;
    }
    private Button rowButton(LinearLayout row, String text, Runnable action, boolean primary) {
        Button button = styled(text, action, primary); LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(0, -2, 1); layout.topMargin = dp(6);
        if (row.getChildCount() > 0) layout.leftMargin = dp(8); row.addView(button, layout); return button;
    }
    private Button styled(String text, Runnable action, boolean primary) {
        Button button = new Button(this); button.setText(text); button.setAllCaps(false); button.setMinHeight(dp(48)); button.setPadding(dp(10), dp(8), dp(10), dp(8));
        ColorStateList disabledAware = new ColorStateList(new int[][] {new int[] {-android.R.attr.state_enabled}, new int[] {}}, new int[] {muted, primary ? (dark ? 0xff00201c : Color.WHITE) : teal});
        GradientDrawable shape = new GradientDrawable(); shape.setCornerRadius(dp(12));
        shape.setColor(primary ? new ColorStateList(new int[][] {new int[] {-android.R.attr.state_enabled}, new int[] {}}, new int[] {buttonColor, teal}) : ColorStateList.valueOf(buttonColor));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(dark ? 0x4463d5c7 : 0x33006b65), shape, null));
        button.setTextColor(disabledAware); button.setOnClickListener(view -> action.run()); return button;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
