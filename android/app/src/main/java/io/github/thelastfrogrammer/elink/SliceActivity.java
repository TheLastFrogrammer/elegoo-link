package io.github.thelastfrogrammer.elink;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
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
 * then hands the G-code to the Files tab (RESULT_FILE/RESULT_NAME) or saves it.
 */
public final class SliceActivity extends Activity {
    static final String RESULT_FILE = "slicedFile", RESULT_NAME = "slicedName";
    private static final int PICK_MODELS = 1, SAVE = 2;
    private static final String DEFAULT_PRINTER = "Elegoo Centauri Carbon 2 0.4 nozzle";
    private static final Set<String> MODEL_TYPES = new HashSet<>(Arrays.asList("stl", "3mf", "obj", "drc", "step", "stp", "amf"));

    // One engine per process: loading the presets takes seconds, and the engine must stay on one thread.
    private static NativeSlicer engine;
    private static final ExecutorService worker = Executors.newSingleThreadExecutor(runnable ->
        new Thread(null, runnable, "slicer", 64L * 1024 * 1024)); // libslic3r recurses deeply; give it a desktop-sized stack

    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean cancelRequested = new AtomicBoolean();
    private SharedPreferences settings;
    private boolean dark, busy;
    private int ink, muted, teal, background, surface, buttonColor, error;
    private LinearLayout content;
    private TextView modelsLabel, status, resultText;
    private Spinner printerSpinner, processSpinner, filamentSpinner, supportSpinner, brimSpinner;
    private EditText infill;
    private Button chooseModels, slice, cancel, useInFiles, saveCopy;
    private ProgressBar progress;
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
        ScrollView scroll = new ScrollView(this); scroll.setBackgroundColor(background); scroll.setFitsSystemWindows(true);
        content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(dp(16), dp(16), dp(16), dp(24)); scroll.addView(content);
        setContentView(scroll);
        label(content, "Slice a model", 22, ink, true);
        label(content, "ElegooSlicer's own slicing engine and Elegoo presets, running on this phone.", 13, muted, false);
        if (!NativeSlicer.available(this)) {
            LinearLayout card = card("Slicer not included");
            label(card, "This build of Link Workshop was made without the slicer library. Builds that include it are made with slicer/scripts/install-into-app.sh before building the app.", 14, ink, false);
            return;
        }
        build();
        if (saved != null) {
            for (String path : saved.getStringArrayList("models") != null ? saved.getStringArrayList("models") : new ArrayList<String>()) { File file = new File(path); if (file.isFile()) models.add(file); }
            showModels();
        }
        loadPresets();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        ArrayList<String> paths = new ArrayList<>(); for (File file : models) paths.add(file.getAbsolutePath());
        state.putStringArrayList("models", paths);
    }

    @Override protected void onDestroy() {
        if (isFinishing()) cancelRequested.set(true);
        super.onDestroy();
    }

    private void build() {
        LinearLayout modelCard = card("Model");
        modelsLabel = label(modelCard, "Choose one or more STL, 3MF, OBJ, Draco or STEP files. Several files are arranged on one plate.", 14, muted, false);
        chooseModels = button(modelCard, "Choose model files", () -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT); intent.setType("*/*"); intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true); startActivityForResult(intent, PICK_MODELS);
        }, false);

        LinearLayout presetCard = card("Presets");
        label(presetCard, "Printer", 12, muted, false); printerSpinner = spinner(presetCard);
        label(presetCard, "Process", 12, muted, false); processSpinner = spinner(presetCard);
        label(presetCard, "Filament", 12, muted, false); filamentSpinner = spinner(presetCard);
        printerSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { loadCompatible(selected(printerSpinner)); }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });

        LinearLayout settingsCard = card("Quick settings");
        label(settingsCard, "Leave a setting on “Preset” to use the process preset's value.", 13, muted, false);
        label(settingsCard, "Infill density (%)", 12, muted, false);
        infill = new EditText(this); infill.setHint("Preset"); infill.setHintTextColor(muted); infill.setTextColor(ink); infill.setSingleLine(true);
        infill.setInputType(InputType.TYPE_CLASS_NUMBER); infill.setBackgroundTintList(ColorStateList.valueOf(teal)); settingsCard.addView(infill, new LinearLayout.LayoutParams(-1, dp(52)));
        label(settingsCard, "Supports", 12, muted, false);
        supportSpinner = spinner(settingsCard); fill(supportSpinner, Arrays.asList("Preset", "Off", "Normal (auto)", "Tree (auto)"), "Preset");
        label(settingsCard, "Brim", 12, muted, false);
        brimSpinner = spinner(settingsCard); fill(brimSpinner, Arrays.asList("Preset", "Off", "Auto", "Outer only"), "Preset");

        LinearLayout sliceCard = card(null);
        status = label(sliceCard, "Loading Elegoo presets…", 14, ink, false);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); progress.setMax(100); progress.setProgressTintList(ColorStateList.valueOf(teal));
        progress.setVisibility(View.GONE); sliceCard.addView(progress, new LinearLayout.LayoutParams(-1, dp(12)));
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); sliceCard.addView(row);
        slice = rowButton(row, "Slice", this::startSlice, true);
        cancel = rowButton(row, "Cancel", () -> { cancelRequested.set(true); status.setText("Cancelling…"); }, false);

        resultCard = card("Result"); resultCard.setVisibility(View.GONE);
        resultText = label(resultCard, "", 14, ink, false); resultText.setTextIsSelectable(true);
        useInFiles = button(resultCard, "Send to Files tab for upload", () -> {
            setResult(RESULT_OK, new Intent().putExtra(RESULT_FILE, sliced.getAbsolutePath()).putExtra(RESULT_NAME, slicedName)); finish();
        }, true);
        saveCopy = button(resultCard, "Save G-code…", () -> {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT); intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/octet-stream"); intent.putExtra(Intent.EXTRA_TITLE, slicedName); startActivityForResult(intent, SAVE);
        }, false);
        label(resultCard, "No preview image is embedded yet, so the printer's file list shows a placeholder for phone-sliced files.", 12, muted, false);
        updateButtons();
    }

    private void loadPresets() {
        busy = true; updateButtons();
        worker.execute(() -> {
            try {
                if (engine == null) engine = NativeSlicer.open(getApplicationContext(), "Elegoo");
                String[] printers = engine.presets(NativeSlicer.PRINTER, null);
                main.post(() -> {
                    if (isDestroyed()) return;
                    busy = false; status.setText("Ready.");
                    fill(printerSpinner, Arrays.asList(printers), settings.getString("slicePrinter", DEFAULT_PRINTER));
                    updateButtons();
                });
            } catch (Exception failure) {
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
                busy = false;
                fill(processSpinner, Arrays.asList(p), remembered(p, "sliceProcess", "0.20mm Standard"));
                fill(filamentSpinner, Arrays.asList(f), remembered(f, "sliceFilament", "Elegoo PLA @"));
                updateButtons();
            });
        });
    }

    private void startSlice() {
        String printer = selected(printerSpinner), process = selected(processSpinner), filament = selected(filamentSpinner);
        if (models.isEmpty() || printer == null || process == null || filament == null) return;
        List<String[]> overrides = new ArrayList<>();
        String density = infill.getText().toString().trim();
        if (!density.isEmpty()) {
            int value; try { value = Integer.parseInt(density); } catch (NumberFormatException e) { value = -1; }
            if (value < 0 || value > 100) { status.setText("Infill density must be 0 to 100."); status.setTextColor(error); return; }
            overrides.add(new String[] {"sparse_infill_density", value + "%"});
        }
        switch (supportSpinner.getSelectedItemPosition()) {
            case 1: overrides.add(new String[] {"enable_support", "0"}); break;
            case 2: overrides.add(new String[] {"enable_support", "1"}); overrides.add(new String[] {"support_type", "normal(auto)"}); break;
            case 3: overrides.add(new String[] {"enable_support", "1"}); overrides.add(new String[] {"support_type", "tree(auto)"}); break;
            default: break;
        }
        switch (brimSpinner.getSelectedItemPosition()) {
            case 1: overrides.add(new String[] {"brim_type", "no_brim"}); break;
            case 2: overrides.add(new String[] {"brim_type", "auto_brim"}); break;
            case 3: overrides.add(new String[] {"brim_type", "outer_only"}); break;
            default: break;
        }
        settings.edit().putString("slicePrinter", printer).putString("sliceProcess", process).putString("sliceFilament", filament).apply();
        String base = models.get(0).getName().replaceFirst("\\.[^.]+$", "");
        slicedName = (models.size() > 1 ? base + "_plate" : base) + ".gcode";
        File outputDir = new File(getCacheDir(), "sliced"); outputDir.mkdirs();
        File output = new File(outputDir, slicedName);
        List<File> input = new ArrayList<>(models);
        busy = true; cancelRequested.set(false); resultCard.setVisibility(View.GONE); progress.setProgress(0); progress.setVisibility(View.VISIBLE);
        status.setTextColor(ink); status.setText("Slicing…"); updateButtons();
        long started = System.currentTimeMillis();
        worker.execute(() -> {
            try {
                NativeSlicer.Result result = engine.slice(input, printer, process, Collections.singletonList(filament), overrides, output, (percent, text) -> {
                    main.post(() -> { if (!isDestroyed()) { progress.setProgress(percent); if (!cancelRequested.get()) status.setText(percent + "% · " + text); } });
                    return !cancelRequested.get();
                });
                long elapsed = System.currentTimeMillis() - started;
                main.post(() -> { if (!isDestroyed()) finished(result, elapsed, printer, process, filament); });
            } catch (IOException failure) {
                main.post(() -> {
                    if (isDestroyed()) return;
                    busy = false; progress.setVisibility(View.GONE);
                    status.setText(cancelRequested.get() ? "Slicing cancelled." : "Slicing failed: " + failure.getMessage());
                    status.setTextColor(cancelRequested.get() ? ink : error); updateButtons();
                });
            }
        });
    }

    private void finished(NativeSlicer.Result result, long elapsedMs, String printer, String process, String filament) {
        busy = false; progress.setVisibility(View.GONE); sliced = result.gcode;
        status.setText(String.format(Locale.getDefault(), "Sliced in %.1f s.", elapsedMs / 1000.0));
        StringBuilder text = new StringBuilder();
        text.append(slicedName).append("\n");
        text.append("Estimated print time: ").append(duration(result.printSeconds)).append("\n");
        text.append(String.format(Locale.getDefault(), "Filament: %.1f g (%.2f m)\n", result.filamentGrams, result.filamentMm / 1000.0));
        text.append(printer).append("\n").append(process).append("\n").append(filament);
        for (String warning : result.warnings) text.append("\n\nWarning: ").append(warning);
        resultText.setText(text.toString());
        resultCard.setVisibility(View.VISIBLE);
        updateButtons();
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null) return;
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
                String name = displayName(uri);
                String extension = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
                if (!MODEL_TYPES.contains(extension)) { problem = name + " is not an STL, 3MF, OBJ, Draco or STEP file."; continue; }
                // libslic3r picks the reader by extension; keep it, and a filename the printer accepts later.
                String safe = name.substring(0, name.lastIndexOf('.')).replaceAll("[^A-Za-z0-9 _.-]", "_");
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
            String shownProblem = problem;
            main.post(() -> {
                if (isDestroyed()) return;
                busy = false; models.clear(); models.addAll(imported); showModels();
                status.setText(shownProblem != null ? shownProblem : "Ready."); status.setTextColor(shownProblem != null ? error : ink);
                resultCard.setVisibility(View.GONE); updateButtons();
            });
        });
    }

    private void showModels() {
        if (models.isEmpty()) { modelsLabel.setText("Choose one or more STL, 3MF, OBJ, Draco or STEP files. Several files are arranged on one plate."); modelsLabel.setTextColor(muted); return; }
        StringBuilder names = new StringBuilder();
        for (File file : models) names.append(names.length() > 0 ? "\n" : "").append(file.getName()).append(" (").append(size(file.length())).append(")");
        modelsLabel.setText(names.toString()); modelsLabel.setTextColor(ink);
    }

    private void updateButtons() {
        if (slice == null) return;
        boolean presetsReady = selected(printerSpinner) != null && selected(processSpinner) != null && selected(filamentSpinner) != null;
        slice.setEnabled(!busy && !models.isEmpty() && presetsReady);
        cancel.setEnabled(busy && progress.getVisibility() == View.VISIBLE);
        chooseModels.setEnabled(!busy);
        printerSpinner.setEnabled(!busy); processSpinner.setEnabled(!busy); filamentSpinner.setEnabled(!busy);
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
