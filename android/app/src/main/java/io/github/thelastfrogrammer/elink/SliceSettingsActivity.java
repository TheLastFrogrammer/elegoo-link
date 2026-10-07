package io.github.thelastfrogrammer.elink;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import java.io.File;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Process settings beyond the Slice screen's quick settings: the common ElegooSlicer options by group, with the
 * preset's value, the effective one and a reset per setting, plus named sets of changes kept on the phone. Returns the
 * changed settings (config key -> serialized value) as EXTRA_OVERRIDES.
 */
public final class SliceSettingsActivity extends Activity {
    static final String EXTRA_SELECTION = "selection", EXTRA_MODELS = "models", EXTRA_OVERRIDES = "overrides", EXTRA_FILAMENT_SLOT = "filamentSlot";
    static final String CUSTOM_PRESETS = "slice-custom-presets", FILAMENT_SETS = "slice-filament-sets";

    /** Filament settings for one slot (the per-filament values of the slot's preset). */
    static final String[][] FILAMENT_GROUPS = {
        {"Temperatures", "nozzle_temperature_initial_layer", "nozzle_temperature", "textured_plate_temp_initial_layer", "textured_plate_temp"},
        {"Flow", "filament_flow_ratio", "enable_pressure_advance", "pressure_advance", "filament_max_volumetric_speed"},
        {"Retraction", "filament_retraction_length", "filament_retraction_speed", "filament_deretraction_speed", "filament_z_hop"},
        {"Cooling", "fan_min_speed", "fan_max_speed", "close_fan_the_first_x_layers", "full_fan_speed_layer", "slow_down_layer_time"},
    };
    private int slot = -1;   // filament slot being edited (0-based), or -1 for the process settings
    private String[][] groups() { return slot >= 0 ? FILAMENT_GROUPS : GROUPS; }

    /** Groups and keys shown, in order; keys this engine does not know are left out. */
    static final String[][] GROUPS = {
        {"Quality", "layer_height", "initial_layer_print_height", "line_width", "seam_position", "seam_gap", "ironing_type", "only_one_wall_top", "detect_overhang_wall"},
        {"Walls and shells", "wall_loops", "top_shell_layers", "top_shell_thickness", "bottom_shell_layers", "bottom_shell_thickness", "top_surface_pattern", "bottom_surface_pattern", "wall_sequence", "wall_generator"},
        {"Infill", "sparse_infill_density", "sparse_infill_pattern", "internal_solid_infill_pattern", "infill_wall_overlap", "infill_combination"},
        {"Speed", "initial_layer_speed", "outer_wall_speed", "inner_wall_speed", "sparse_infill_speed", "internal_solid_infill_speed", "top_surface_speed", "gap_infill_speed", "travel_speed", "default_acceleration"},
        {"Support", "enable_support", "support_type", "support_style", "support_threshold_angle", "support_on_build_plate_only", "support_top_z_distance", "support_interface_top_layers", "support_base_pattern_spacing"},
        {"Adhesion", "brim_type", "brim_width", "brim_object_gap", "skirt_loops", "skirt_distance"},
        {"Prime tower and multi-material", "enable_prime_tower", "prime_tower_width", "prime_volume", "flush_into_infill", "flush_into_support"},
        {"Special", "spiral_mode", "print_sequence", "fuzzy_skin", "fuzzy_skin_thickness", "reduce_crossing_wall", "enable_arc_fitting"},
    };

    /** Names for settings whose ElegooSlicer label only makes sense inside its desktop panel ("Enable", "Width", "Outer wall"). */
    static final Map<String, String> LABELS = new HashMap<>();
    static {
        String[][] names = {{"line_width", "Line width"}, {"default_acceleration", "Acceleration"}, {"enable_prime_tower", "Prime tower"},
            {"prime_tower_width", "Prime tower width"}, {"support_type", "Support type"}, {"support_style", "Support style"},
            {"support_threshold_angle", "Support threshold angle"}, {"support_top_z_distance", "Support top Z distance"},
            {"support_interface_top_layers", "Support top interface layers"}, {"support_base_pattern_spacing", "Support base pattern spacing"},
            {"support_on_build_plate_only", "Support on build plate only"}, {"initial_layer_speed", "First layer speed"},
            {"outer_wall_speed", "Outer wall speed"}, {"inner_wall_speed", "Inner wall speed"}, {"sparse_infill_speed", "Sparse infill speed"},
            {"internal_solid_infill_speed", "Internal solid infill speed"}, {"top_surface_speed", "Top surface speed"},
            {"gap_infill_speed", "Gap infill speed"}, {"travel_speed", "Travel speed"}, {"fuzzy_skin", "Fuzzy skin"}, {"ironing_type", "Ironing"},
            {"fan_min_speed", "Minimum fan speed"}, {"fan_max_speed", "Maximum fan speed"}, {"textured_plate_temp", "Bed temperature (textured plate)"},
            {"textured_plate_temp_initial_layer", "First layer bed temperature (textured plate)"}, {"filament_retraction_length", "Retraction length"},
            {"filament_retraction_speed", "Retraction speed"}, {"filament_deretraction_speed", "De-retraction speed"}, {"slow_down_layer_time", "Slow down for layers under"}};
        for (String[] name : names) LABELS.put(name[0], name[1]);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private WorkshopUi ui;
    private LinearLayout content, list;
    private TextView summary;
    private EditText search;
    private JSONObject selection, definitions;
    private List<File> models = new ArrayList<>();
    private final Map<String, String> overrides = new LinkedHashMap<>();
    private final Map<String, View> rows = new LinkedHashMap<>();

    @Override protected void onCreate(Bundle saved) {
        ui = new WorkshopUi(this);
        super.onCreate(saved);
        slot = getIntent().getIntExtra(EXTRA_FILAMENT_SLOT, -1);
        content = slot >= 0 ? ui.page("Filament " + (slot + 1) + " settings", "Changes apply to this filament slot on top of its preset. \u201cPrinter's value\u201d means the printer preset decides.")
            : ui.page("Print settings", "Changes apply to this slice on top of the process preset. The preset's value is shown under each setting.");
        try {
            selection = new JSONObject(getIntent().getStringExtra(EXTRA_SELECTION));
            JSONObject start = saved != null && saved.getString(EXTRA_OVERRIDES) != null ? new JSONObject(saved.getString(EXTRA_OVERRIDES))
                : slot >= 0 ? (selection.optJSONArray("filament_overrides") != null ? selection.getJSONArray("filament_overrides").optJSONObject(slot) : null)
                : selection.optJSONObject("overrides");
            if (start != null) for (Iterator<String> keys = start.keys(); keys.hasNext(); ) { String key = keys.next(); overrides.put(key, start.getString(key)); }
        } catch (JSONException | NullPointerException error) { finish(); return; }
        String[] paths = getIntent().getStringArrayExtra(EXTRA_MODELS);
        if (paths != null) for (String path : paths) models.add(new File(path));

        LinearLayout top = ui.card(content, null);
        summary = ui.label(top, "", 14, ui.ink, false);
        search = ui.input(top, "Find a setting");
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { filter(); }
            public void afterTextChanged(Editable s) { }
        });
        LinearLayout buttons = ui.row(top);
        ui.rowButton(buttons, "Saved sets…", this::chooseSaved, false);
        ui.rowButton(buttons, "Save these…", this::saveSet, false);
        ui.button(top, "Reset all to the preset", () -> { overrides.clear(); load(); }, false);
        list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL); content.addView(list);
        LinearLayout done = ui.card(content, null);
        ui.button(done, "Use these settings", this::finishWithResult, true);
        load();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString(EXTRA_OVERRIDES, new JSONObject(overrides).toString());
    }

    // The result is kept current, so leaving with Back (or a back gesture) keeps the changes too.
    private void keepResult() { setResult(RESULT_OK, new Intent().putExtra(EXTRA_OVERRIDES, new JSONObject(overrides).toString()).putExtra(EXTRA_FILAMENT_SLOT, slot)); }

    private void finishWithResult() { keepResult(); finish(); }

    /** Asks the engine for the definitions and values, then builds the list. */
    private void load() {
        summary.setText("Reading settings…");
        List<String> keys = new ArrayList<>();
        for (String[] group : groups()) keys.addAll(Arrays.asList(group).subList(1, group.length));
        String request;
        try {
            JSONObject json = new JSONObject(selection.toString()).put("placements", new JSONArray());
            if (slot >= 0) {
                JSONArray perSlot = json.optJSONArray("filament_overrides"); if (perSlot == null) perSlot = new JSONArray();
                while (perSlot.length() <= slot) perSlot.put(new JSONObject());
                perSlot.put(slot, new JSONObject(overrides)); json.put("filament_overrides", perSlot);
            } else json.put("overrides", new JSONObject(overrides));
            request = json.toString();
        } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
        SliceActivity.worker().execute(() -> {
            try {
                NativeSlicer engine = SliceActivity.engine(getApplicationContext());
                JSONObject described = engine.describeJson(models, request, keys);
                JSONObject read = slot >= 0 ? forSlot(described, slot) : described;
                main.post(() -> { if (!isDestroyed()) { definitions = read; build(); } });
            } catch (Exception failure) {
                main.post(() -> { if (!isDestroyed()) { summary.setText("Settings could not be read: " + failure.getMessage()); summary.setTextColor(ui.error); } });
            }
        });
    }

    private void build() {
        list.removeAllViews(); rows.clear();
        for (String[] group : groups()) {
            LinearLayout card = null;
            for (int i = 1; i < group.length; i++) {
                JSONObject definition = definitions.optJSONObject(group[i]);
                if (definition == null || !supported(definition.optString("type"))) continue;
                if (LABELS.containsKey(group[i])) try { definition.put("label", LABELS.get(group[i])); } catch (JSONException ignored) { }
                if (card == null) card = ui.card(list, group[0]);
                rows.put(group[i], row(card, group[i], definition));
            }
        }
        updateSummary(); filter();
    }

    /** Per-filament (vector) settings narrowed to one slot: its element of each value, with the scalar type. */
    static JSONObject forSlot(JSONObject definitions, int slot) throws JSONException {
        JSONObject result = new JSONObject();
        for (Iterator<String> keys = definitions.keys(); keys.hasNext(); ) {
            String key = keys.next(); JSONObject definition = new JSONObject(definitions.getJSONObject(key).toString());
            String type = definition.optString("type");
            if (!type.endsWith("s") || type.equals("float_or_percent")) continue; // only per-filament vectors
            definition.put("type", type.substring(0, type.length() - 1));
            for (String field : new String[] {"value", "preset"}) {
                String[] parts = definition.optString(field).split(",", -1);
                if (parts.length > 0) definition.put(field, parts[Math.min(slot, parts.length - 1)].trim());
            }
            result.put(key, definition);
        }
        return result;
    }

    private static boolean supported(String type) {
        return Arrays.asList("float", "int", "percent", "float_or_percent", "bool", "enum", "string").contains(type);
    }

    private View row(LinearLayout card, String key, JSONObject definition) {
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.VERTICAL); row.setPadding(0, ui.dp(4), 0, ui.dp(8));
        row.setTag(definition.optString("label").toLowerCase(Locale.ROOT) + " " + key);
        card.addView(row);
        String unit = definition.optString("unit"), type = definition.optString("type");
        String preset = definition.optString("preset"), value = overrides.containsKey(key) ? overrides.get(key) : preset;
        TextView title = ui.label(row, definition.optString("label") + (unit.isEmpty() || type.equals("percent") ? "" : " (" + unit + ")"), 14, ui.ink, overrides.containsKey(key));
        title.setPadding(0, ui.dp(2), 0, 0);
        String tooltip = definition.optString("tooltip");
        if (!tooltip.isEmpty()) title.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle(definition.optString("label")).setMessage(tooltip).setPositiveButton("Close", null).show());
        TextView note = ui.label(row, "", 12, ui.muted, false); note.setPadding(0, 0, 0, ui.dp(2));
        Runnable showNote = () -> {
            boolean changed = overrides.containsKey(key);
            note.setText((changed ? "Changed · preset: " : "Preset: ") + display(definition, preset) + (tooltip.isEmpty() ? "" : " · tap the name for help"));
            note.setTextColor(changed ? ui.teal : ui.muted);
            title.setTypeface(android.graphics.Typeface.DEFAULT, changed ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            updateSummary();
        };
        switch (type) {
            case "bool": {
                Switch toggle = new Switch(this); toggle.setChecked("1".equals(value)); toggle.setText(""); row.addView(toggle, 1);
                toggle.setOnCheckedChangeListener((v, checked) -> { set(key, checked ? "1" : "0", preset); showNote.run(); });
                break;
            }
            case "enum": {
                JSONArray options = definition.optJSONArray("enum");
                List<String> values = new ArrayList<>(), labels = new ArrayList<>();
                for (int i = 0; options != null && i < options.length(); i++) { values.add(options.optJSONArray(i).optString(0)); labels.add(options.optJSONArray(i).optString(1)); }
                Spinner spinner = ui.spinner(row, labels, values.indexOf(value));
                row.removeView(spinner); row.addView(spinner, 1);
                spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                    @Override public void onItemSelected(AdapterView<?> p, View v, int position, long id) { set(key, values.get(position), preset); showNote.run(); }
                    @Override public void onNothingSelected(AdapterView<?> p) { }
                });
                break;
            }
            default: {
                EditText input = ui.input(row, "nil".equals(preset) ? "Printer's value" : preset); input.setText("nil".equals(value) ? "" : value);
                row.removeView(input); row.addView(input, 1);
                if (type.equals("int")) input.setInputType(InputType.TYPE_CLASS_NUMBER);
                else if (type.equals("float")) input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
                input.setImeOptions(EditorInfo.IME_ACTION_DONE);
                Runnable commit = () -> {
                    String text = input.getText().toString().trim();
                    String problem = validate(definition, text);
                    if (problem != null) { note.setText(problem); note.setTextColor(ui.error); return; }
                    set(key, text.isEmpty() ? preset : text, preset); showNote.run();
                };
                input.setOnFocusChangeListener((v, focused) -> { if (!focused) commit.run(); });
                input.setOnEditorActionListener((v, action, event) -> { commit.run(); return false; });
            }
        }
        showNote.run();
        return row;
    }

    private void set(String key, String value, String preset) {
        if (value.equals(preset)) overrides.remove(key); else overrides.put(key, value);
    }

    /** A readable preset value: enum labels, On/Off, units. */
    private static String display(JSONObject definition, String value) {
        if ("nil".equals(value)) return "printer's value";
        switch (definition.optString("type")) {
            case "bool": return "1".equals(value) ? "On" : "Off";
            case "enum": {
                JSONArray options = definition.optJSONArray("enum");
                for (int i = 0; options != null && i < options.length(); i++) if (options.optJSONArray(i).optString(0).equals(value)) return options.optJSONArray(i).optString(1);
                return value;
            }
            default: {
                String unit = definition.optString("unit");
                if (unit.endsWith(" or %")) unit = unit.substring(0, unit.length() - 5); // "mm or %": the value says which
                return value + (unit.isEmpty() || value.endsWith("%") ? "" : " " + unit);
            }
        }
    }

    /** Null when `text` is acceptable for the setting, otherwise why not. Empty means "back to the preset". */
    static String validate(JSONObject definition, String text) {
        if (text.isEmpty()) return null;
        String type = definition.optString("type");
        if (type.equals("string")) return text.contains("\n") ? "One line only." : null;
        boolean percent = text.endsWith("%");
        if (percent && !(type.equals("percent") || type.equals("float_or_percent"))) return "Enter a number without %.";
        double number;
        try { number = Double.parseDouble(percent ? text.substring(0, text.length() - 1).trim() : text); }
        catch (NumberFormatException e) { return type.equals("float_or_percent") ? "Enter a number, or a percentage like 50%." : "Enter a number."; }
        if (type.equals("int") && number != Math.rint(number)) return "Enter a whole number.";
        if (Double.isNaN(number) || Double.isInfinite(number)) return "Enter a number.";
        if (!percent || type.equals("percent")) {
            if (definition.has("min") && number < definition.optDouble("min")) return "At least " + trim(definition.optDouble("min")) + ".";
            if (definition.has("max") && number > definition.optDouble("max")) return "At most " + trim(definition.optDouble("max")) + ".";
        }
        return null;
    }

    private static String trim(double value) { return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value); }

    private void updateSummary() {
        if (summary == null) return;
        keepResult();
        summary.setText(overrides.isEmpty() ? "All settings follow the process preset." : overrides.size() + " setting(s) changed from the preset.");
        summary.setTextColor(ui.ink);
    }

    private void filter() {
        String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        for (View row : rows.values()) row.setVisibility(query.isEmpty() || String.valueOf(row.getTag()).contains(query) ? View.VISIBLE : View.GONE);
        for (int i = 0; i < list.getChildCount(); i++) {
            LinearLayout card = (LinearLayout) list.getChildAt(i); boolean any = false;
            for (int j = 1; j < card.getChildCount(); j++) any |= card.getChildAt(j).getVisibility() == View.VISIBLE;
            card.setVisibility(any ? View.VISIBLE : View.GONE);
        }
    }

    // ------------------------------------------------------------------ saved sets of changes
    private SharedPreferences store() { return getSharedPreferences(slot >= 0 ? FILAMENT_SETS : CUSTOM_PRESETS, MODE_PRIVATE); }

    private void saveSet() {
        if (overrides.isEmpty()) { Toast.makeText(this, "Change a setting first.", Toast.LENGTH_SHORT).show(); return; }
        EditText name = new EditText(this); name.setSingleLine(true); name.setHint("Name, e.g. Strong parts");
        LinearLayout body = new LinearLayout(this); body.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0); body.addView(name, new LinearLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(this).setTitle("Save these changes").setMessage("Saved on this phone. They can be used with any process preset.").setView(body)
            .setNegativeButton("Cancel", null).setPositiveButton("Save", (d, w) -> {
                String title = name.getText().toString().trim();
                if (title.isEmpty()) return;
                store().edit().putString(title, new JSONObject(overrides).toString()).apply();
                Toast.makeText(this, "Saved “" + title + "”.", Toast.LENGTH_SHORT).show();
            }).show();
    }

    private void chooseSaved() {
        List<String> names = new ArrayList<>(store().getAll().keySet());
        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        if (names.isEmpty()) { Toast.makeText(this, "No saved sets yet. Change settings, then Save these.", Toast.LENGTH_LONG).show(); return; }
        new AlertDialog.Builder(this).setTitle("Saved settings").setItems(names.toArray(new String[0]), (d, which) -> {
            String name = names.get(which);
            new AlertDialog.Builder(this).setTitle(name).setMessage(describeSet(store().getString(name, "{}")))
                .setNegativeButton("Delete", (d2, w2) -> store().edit().remove(name).apply())
                .setNeutralButton("Cancel", null)
                .setPositiveButton("Use", (d2, w2) -> {
                    try {
                        JSONObject set = new JSONObject(store().getString(name, "{}"));
                        overrides.clear();
                        for (Iterator<String> keys = set.keys(); keys.hasNext(); ) { String key = keys.next(); overrides.put(key, set.getString(key)); }
                        load();
                    } catch (JSONException ignored) { }
                }).show();
        }).show();
    }

    private String describeSet(String json) {
        try {
            JSONObject set = new JSONObject(json); StringBuilder text = new StringBuilder();
            for (Iterator<String> keys = set.keys(); keys.hasNext(); ) {
                String key = keys.next(); JSONObject definition = definitions == null ? null : definitions.optJSONObject(key);
                text.append(definition == null ? key : definition.optString("label")).append(": ").append(definition == null ? set.getString(key) : display(definition, set.getString(key))).append("\n");
            }
            return text.toString().trim();
        } catch (JSONException e) { return ""; }
    }
}
