package io.github.thelastfrogrammer.elink;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
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
    static final String EXTRA_SELECTION = "selection", EXTRA_MODELS = "models", EXTRA_OVERRIDES = "overrides", EXTRA_FILAMENT_SLOT = "filamentSlot",
        EXTRA_OBJECT = "object", EXTRA_OBJECT_NAME = "objectName";
    static final String CUSTOM_PRESETS = "slice-custom-presets", FILAMENT_SETS = "slice-filament-sets", OBJECT_SETS = "slice-object-sets";

    /** Filament settings for one slot (the per-filament values of the slot's preset). */
    static final String[][] FILAMENT_GROUPS = {
        {"Temperatures", "nozzle_temperature_initial_layer", "nozzle_temperature", "textured_plate_temp_initial_layer", "textured_plate_temp"},
        {"Flow", "filament_flow_ratio", "enable_pressure_advance", "pressure_advance", "filament_max_volumetric_speed"},
        {"Retraction", "filament_retraction_length", "filament_retraction_speed", "filament_deretraction_speed", "filament_z_hop"},
        {"Cooling", "fan_min_speed", "fan_max_speed", "close_fan_the_first_x_layers", "full_fan_speed_layer", "slow_down_layer_time"},
    };
    private int slot = -1;   // filament slot being edited (0-based), or -1 for the process settings
    /** Object being edited ({file, object}, 0-based), or null: only settings an object can have, on top of the plate's. */
    private int[] object;
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
    private static Set<String> keysOf(String[][] groups) {
        Set<String> keys = new HashSet<>();
        for (String[] group : groups) keys.addAll(Arrays.asList(group).subList(1, group.length));
        return keys;
    }
    static final Set<String> PROCESS_KEYS = keysOf(GROUPS), FILAMENT_KEYS = keysOf(FILAMENT_GROUPS);

    /**
     * Everyday words and the settings they usually mean, for a search that a label does not answer. A word matches
     * when it contains what was typed (at least three letters), so "strong" and "stro" both find the strength words.
     */
    static final Map<String, String[]> SEARCH_TERMS = new LinkedHashMap<>();
    static {
        String[][] terms = {
            {"walls perimeters perimeter", "wall_loops"},
            {"strong strength stronger sturdy tough durable", "wall_loops sparse_infill_density top_shell_layers top_shell_thickness bottom_shell_layers bottom_shell_thickness"},
            {"solid", "sparse_infill_density top_shell_layers bottom_shell_layers internal_solid_infill_pattern"},
            {"dense density denser", "sparse_infill_density"},
            {"shell shells thick thicker", "top_shell_layers top_shell_thickness bottom_shell_layers bottom_shell_thickness"},
            {"stringing string strings stringy oozing ooze", "filament_retraction_length filament_retraction_speed filament_deretraction_speed filament_z_hop"},
            {"adhesion adhere stick sticking stuck warp warping curl peel", "brim_type brim_width brim_object_gap initial_layer_print_height initial_layer_speed nozzle_temperature_initial_layer textured_plate_temp_initial_layer textured_plate_temp"},
            {"first", "initial_layer_print_height initial_layer_speed nozzle_temperature_initial_layer textured_plate_temp_initial_layer"},
            {"bed", "textured_plate_temp_initial_layer textured_plate_temp"},
            {"vase", "spiral_mode"},
            {"smooth smoother shiny", "ironing_type"},
        };
        for (String[] term : terms) SEARCH_TERMS.put(term[0], term[1].split(" "));
    }

    /** Names for settings whose ElegooSlicer label only makes sense inside its desktop panel ("Enable", "Width", "Outer wall"). */
    static final Map<String, String> LABELS = new HashMap<>();
    static {
        String[][] names = {{"line_width", "Line width"}, {"default_acceleration", "Acceleration (normal printing)"}, {"enable_prime_tower", "Prime tower"},
            {"wall_loops", "Walls (wall loops)"}, {"sparse_infill_density", "Infill amount (sparse infill density)"},
            {"sparse_infill_pattern", "Infill pattern (sparse infill)"}, {"infill_wall_overlap", "Infill overlap with walls (infill/wall overlap)"},
            {"top_shell_layers", "Top solid layers (top shell)"}, {"bottom_shell_layers", "Bottom solid layers (bottom shell)"},
            {"seam_gap", "Seam gap (loop cut-off)"}, {"ironing_type", "Ironing (smooths top surfaces)"}, {"brim_type", "Brim type (adhesion)"},
            {"spiral_mode", "Spiral vase (vase mode)"},
            {"prime_tower_width", "Prime tower width"}, {"support_type", "Support type"}, {"support_style", "Support style"},
            {"support_threshold_angle", "Support threshold angle"}, {"support_top_z_distance", "Support top Z distance"},
            {"support_interface_top_layers", "Support top interface layers"}, {"support_base_pattern_spacing", "Support base pattern spacing"},
            {"support_on_build_plate_only", "Support on build plate only"}, {"initial_layer_speed", "First layer speed"},
            {"outer_wall_speed", "Outer wall speed"}, {"inner_wall_speed", "Inner wall speed"}, {"sparse_infill_speed", "Sparse infill speed"},
            {"internal_solid_infill_speed", "Internal solid infill speed"}, {"top_surface_speed", "Top surface speed"},
            {"gap_infill_speed", "Gap infill speed"}, {"travel_speed", "Travel speed"}, {"fuzzy_skin", "Fuzzy skin"},
            {"fan_min_speed", "Minimum fan speed"}, {"fan_max_speed", "Maximum fan speed"}, {"textured_plate_temp", "Bed temperature (textured plate)"},
            {"textured_plate_temp_initial_layer", "First layer bed temperature (textured plate)"}, {"filament_retraction_length", "Retraction length"},
            {"filament_retraction_speed", "Retraction speed"}, {"filament_deretraction_speed", "De-retraction speed"}, {"slow_down_layer_time", "Slow down for layers under"}};
        for (String[] name : names) LABELS.put(name[0], name[1]);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private WorkshopUi ui;
    private LinearLayout content, list;
    private TextView summary, searchHint;
    private EditText search;
    private Switch changedOnly;
    private String objectLabel = "this model";
    /** One settings group on screen: its card, the header that opens it, the rows, and the keys in it. */
    private static final class Section {
        final String name; final LinearLayout card, body; final TextView header; final List<String> keys = new ArrayList<>();
        Section(String name, LinearLayout card, LinearLayout body, TextView header) { this.name = name; this.card = card; this.body = body; this.header = header; }
    }
    private final List<Section> sections = new ArrayList<>();
    /** Groups the user has opened; null until the first list is built (then the defaults apply). Kept across recreation. */
    private Set<String> expanded;
    private static final String EXPANDED = "expandedGroups";
    private JSONObject selection, definitions;
    private List<File> models = new ArrayList<>();
    private final Map<String, String> overrides = new LinkedHashMap<>();
    private final Map<String, View> rows = new LinkedHashMap<>();

    @Override protected void onCreate(Bundle saved) {
        ui = new WorkshopUi(this);
        super.onCreate(saved);
        slot = getIntent().getIntExtra(EXTRA_FILAMENT_SLOT, -1);
        object = getIntent().getIntArrayExtra(EXTRA_OBJECT);
        if (object != null && object.length != 2) object = null;
        String objectName = getIntent().getStringExtra(EXTRA_OBJECT_NAME);
        if (objectName != null && !objectName.isEmpty()) objectLabel = objectName;
        content = object != null ? ui.page("Settings for " + objectLabel,
                "Only " + objectLabel + " and its copies use these changes; the rest of the plate keeps the print settings.")
            : slot >= 0 ? ui.page("Filament " + (slot + 1) + " settings", "Changes apply to this filament slot on top of its preset. \u201cPrinter's value\u201d means the printer preset decides.")
            : ui.page("Print settings", "Changes apply to this slice on top of the process preset. The preset's value is shown under each setting.");
        if (saved != null && saved.getStringArrayList(EXPANDED) != null) expanded = new HashSet<>(saved.getStringArrayList(EXPANDED));
        try {
            selection = new JSONObject(getIntent().getStringExtra(EXTRA_SELECTION));
            JSONObject start = saved != null && saved.getString(EXTRA_OVERRIDES) != null ? new JSONObject(saved.getString(EXTRA_OVERRIDES))
                : object != null ? objectValues(selection, object)
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
        searchHint = ui.label(top, "", 12, ui.muted, false);
        changedOnly = new Switch(this); changedOnly.setText("Show changed only"); changedOnly.setTextColor(ui.ink); changedOnly.setTextSize(14);
        changedOnly.setPadding(0, ui.dp(6), 0, ui.dp(6));
        top.addView(changedOnly, new LinearLayout.LayoutParams(-1, -2));
        changedOnly.setOnCheckedChangeListener((v, checked) -> filter());
        LinearLayout buttons = ui.row(top);
        ui.rowButton(buttons, "Saved sets…", this::chooseSaved, false);
        ui.rowButton(buttons, "Save these…", this::saveSet, false);
        ui.button(top, object != null ? "Reset all to the plate's settings" : "Reset all to the preset", () -> { overrides.clear(); load(); }, false);
        list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL); content.addView(list);
        LinearLayout done = ui.card(content, null);
        ui.button(done, "Use these settings", this::finishWithResult, true);
        load();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString(EXTRA_OVERRIDES, new JSONObject(overrides).toString());
        if (expanded != null) state.putStringArrayList(EXPANDED, new ArrayList<>(expanded));
    }

    // The result is kept current, so leaving with Back (or a back gesture) keeps the changes too.
    private void keepResult() {
        Intent result = new Intent().putExtra(EXTRA_OVERRIDES, new JSONObject(overrides).toString()).putExtra(EXTRA_FILAMENT_SLOT, slot);
        if (object != null) result.putExtra(EXTRA_OBJECT, object);
        setResult(RESULT_OK, result);
    }

    /** The values already set for one object in a selection's object_settings, or null. */
    static JSONObject objectValues(JSONObject selection, int[] object) {
        JSONArray list = selection.optJSONArray("object_settings");
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject item = list.optJSONObject(i);
            if (item != null && item.optInt("file") == object[0] && item.optInt("object") == object[1]) return item.optJSONObject("values");
        }
        return null;
    }

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
            } else if (object == null) json.put("overrides", new JSONObject(overrides));
            request = json.toString();
        } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
        SliceActivity.worker().execute(() -> {
            try {
                NativeSlicer engine = SliceActivity.engine(getApplicationContext());
                JSONObject described = engine.describeJson(models, request, keys);
                JSONObject read = slot >= 0 ? forSlot(described, slot) : object != null ? forObject(described) : described;
                main.post(() -> { if (!isDestroyed()) { definitions = read; build(); } });
            } catch (Exception failure) {
                main.post(() -> { if (!isDestroyed()) { summary.setText("Settings could not be read: " + failure.getMessage()); summary.setTextColor(ui.error); } });
            }
        });
    }

    private void build() {
        list.removeAllViews(); rows.clear(); sections.clear();
        for (String[] group : groups()) {
            Section section = null;
            for (int i = 1; i < group.length; i++) {
                JSONObject definition = definitions.optJSONObject(group[i]);
                if (definition == null || !supported(definition.optString("type"))) continue;
                if (LABELS.containsKey(group[i])) try { definition.put("label", LABELS.get(group[i])); } catch (JSONException ignored) { }
                if (section == null) section = section(group[0]);
                rows.put(group[i], row(section.body, group[0], group[i], definition));
                section.keys.add(group[i]);
            }
        }
        if (expanded == null) { // first list: the first group, and any group with changed settings
            expanded = new HashSet<>();
            for (int i = 0; i < sections.size(); i++) if (i == 0 || changedIn(sections.get(i)) > 0) expanded.add(sections.get(i).name);
        }
        updateSummary(); filter();
    }

    /** A settings group: a header that opens or closes it, and the body holding its rows. */
    private Section section(String name) {
        LinearLayout card = ui.card(list, null);
        TextView header = ui.label(card, "", 17, ui.ink, true);
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); card.addView(body);
        Section section = new Section(name, card, body, header);
        header.setOnClickListener(v -> { if (!expanded.remove(name)) expanded.add(name); filter(); });
        sections.add(section);
        return section;
    }

    private int changedIn(Section section) {
        int changed = 0;
        for (String key : section.keys) if (overrides.containsKey(key)) changed++;
        return changed;
    }

    /** Object mode: only settings an object can have, measured against the plate's value rather than the preset. */
    static JSONObject forObject(JSONObject definitions) throws JSONException {
        JSONObject out = new JSONObject();
        for (Iterator<String> keys = definitions.keys(); keys.hasNext(); ) {
            String key = keys.next(); JSONObject definition = definitions.getJSONObject(key);
            if (!definition.optBoolean("per_object")) continue;
            if (definition.has("value")) definition.put("preset", definition.getString("value"));
            out.put(key, definition);
        }
        return out;
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

    private View row(LinearLayout card, String group, String key, JSONObject definition) {
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.VERTICAL); row.setPadding(0, ui.dp(4), 0, ui.dp(8));
        row.setTag((group + " " + definition.optString("label") + " " + key).toLowerCase(Locale.ROOT));
        card.addView(row);
        String unit = definition.optString("unit"), type = definition.optString("type"), label = definition.optString("label");
        String preset = definition.optString("preset"), value = overrides.containsKey(key) ? overrides.get(key) : preset;
        // The unit goes in the name only for a plain name; a name with its own brackets already says what it is.
        boolean unitInName = !unit.isEmpty() && !type.equals("percent") && !label.contains("(");
        String heading = label + (unitInName ? " (" + unit + ")" : "");
        LinearLayout header = new LinearLayout(this); header.setOrientation(LinearLayout.HORIZONTAL); header.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(header, new LinearLayout.LayoutParams(-1, -2));
        TextView title = new TextView(this); title.setText(heading); title.setTextSize(14); title.setTextColor(ui.ink);
        title.setPadding(0, ui.dp(2), 0, ui.dp(2));
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        String tooltip = definition.optString("tooltip");
        Runnable showHelp = () -> new AlertDialog.Builder(this).setTitle(label).setMessage(tooltip).setPositiveButton("Close", null).show();
        if (!tooltip.isEmpty()) {
            title.setOnClickListener(v -> showHelp.run());
            TextView help = new TextView(this); help.setText("?"); help.setGravity(Gravity.CENTER); help.setTextSize(14);
            help.setTypeface(Typeface.DEFAULT, Typeface.BOLD); help.setTextColor(ui.teal); help.setContentDescription("What is " + label + "?");
            GradientDrawable ring = new GradientDrawable(); ring.setShape(GradientDrawable.OVAL); ring.setStroke(ui.dp(2), ui.teal); help.setBackground(ring);
            help.setOnClickListener(v -> showHelp.run());
            LinearLayout.LayoutParams helpLayout = new LinearLayout.LayoutParams(ui.dp(30), ui.dp(30)); helpLayout.leftMargin = ui.dp(8);
            header.addView(help, helpLayout);
        }
        TextView reset = new TextView(this); reset.setText("Reset"); reset.setTextSize(13); reset.setTextColor(ui.teal);
        reset.setTypeface(Typeface.DEFAULT, Typeface.BOLD); reset.setMinHeight(ui.dp(40)); reset.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        reset.setPadding(ui.dp(10), 0, 0, 0); reset.setVisibility(View.GONE);
        reset.setContentDescription("Reset " + label + " to the " + (object != null ? "plate's" : slot >= 0 ? "filament preset's" : "preset's") + " value");
        reset.setOnClickListener(v -> { overrides.remove(key); build(); });
        header.addView(reset, new LinearLayout.LayoutParams(-2, -2));
        TextView note = ui.label(row, "", 12, ui.muted, false); note.setPadding(0, 0, 0, ui.dp(2));
        Runnable showNote = () -> {
            boolean changed = overrides.containsKey(key);
            note.setText((object != null ? (changed ? "Changed · plate: " : "Plate: ") : changed ? "Changed · preset: " : "Preset: ") + display(definition, preset));
            note.setTextColor(changed ? ui.teal : ui.muted);
            title.setTypeface(Typeface.DEFAULT, changed ? Typeface.BOLD : Typeface.NORMAL);
            reset.setVisibility(changed ? View.VISIBLE : View.GONE);
            updateSummary();
        };
        switch (type) {
            case "bool": {
                Switch toggle = new Switch(this); toggle.setChecked("1".equals(value)); toggle.setText("");
                LinearLayout.LayoutParams toggleLayout = new LinearLayout.LayoutParams(-2, -2); toggleLayout.leftMargin = ui.dp(8);
                header.addView(toggle, 1, toggleLayout); // on the title line, before the ? badge
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

    /** The name shown for a setting, as the engine labels it after LABELS. */
    private String labelOf(String key) {
        JSONObject definition = definitions == null ? null : definitions.optJSONObject(key);
        return definition == null || definition.optString("label").isEmpty() ? key : definition.optString("label");
    }

    /** The changed settings by name, the first few, then how many more. */
    private String changedNames(String lead) {
        List<String> names = new ArrayList<>();
        for (String key : overrides.keySet()) names.add(labelOf(key));
        StringBuilder text = new StringBuilder(lead);
        for (int i = 0; i < names.size() && i < 4; i++) text.append(i == 0 ? " " : ", ").append(names.get(i));
        if (names.size() > 4) text.append(" and ").append(names.size() - 4).append(" more");
        return text.toString();
    }

    private void updateSummary() {
        if (summary == null) return;
        keepResult();
        int n = overrides.size();
        if (object != null) summary.setText(n == 0 ? objectLabel + " follows the plate's settings."
            : changedNames(n + (n == 1 ? " setting differs" : " settings differ") + " from the plate for " + objectLabel + ":"));
        else if (n == 0) summary.setText(slot >= 0 ? "All settings follow this filament's preset." : "All settings follow the process preset.");
        else summary.setText(changedNames(n + (n == 1 ? " setting" : " settings") + " changed from the " + (slot >= 0 ? "filament's preset:" : "preset:")));
        summary.setTextColor(ui.ink);
    }

    /** The settings a search word means that this screen leaves out, with a pointer to where they are; null if none. */
    private String elsewhere(Set<String> meant) {
        if (slot >= 0) { for (String key : meant) if (PROCESS_KEYS.contains(key)) return "That is a print setting. Use All settings… on the Slice screen."; }
        else if (object == null) { for (String key : meant) if (FILAMENT_KEYS.contains(key)) return "That is set per filament. Open a filament's Settings… on the Slice screen."; }
        else {
            for (String key : meant) if (FILAMENT_KEYS.contains(key)) return "That is set per filament, not per model.";
            for (String key : meant) if (PROCESS_KEYS.contains(key)) return "That applies to the whole plate. Change it in All settings… on the Slice screen.";
        }
        return null;
    }

    /** Settings a search word means, for words that name the settings (see SEARCH_TERMS). */
    static Set<String> termKeys(String query) {
        Set<String> keys = new HashSet<>();
        if (query.length() < 3) return keys;
        for (Map.Entry<String, String[]> term : SEARCH_TERMS.entrySet()) if (term.getKey().contains(query)) keys.addAll(Arrays.asList(term.getValue()));
        return keys;
    }

    private void filter() {
        if (list == null || search == null) return;
        String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        boolean onlyChanged = changedOnly != null && changedOnly.isChecked();
        Set<String> meant = termKeys(query);
        boolean searching = !query.isEmpty() || onlyChanged;
        int shown = 0;
        for (Section section : sections) {
            int matches = 0;
            for (String key : section.keys) {
                View row = rows.get(key);
                boolean match = (query.isEmpty() || String.valueOf(row.getTag()).contains(query) || meant.contains(key)) && (!onlyChanged || overrides.containsKey(key));
                row.setVisibility(match ? View.VISIBLE : View.GONE);
                if (match) matches++;
            }
            shown += matches;
            // While searching, a group with matches opens by itself and one without is left out; otherwise the user's choice holds.
            boolean open = searching ? matches > 0 : expanded.contains(section.name);
            section.card.setVisibility(searching && matches == 0 ? View.GONE : View.VISIBLE);
            section.body.setVisibility(open ? View.VISIBLE : View.GONE);
            int changed = changedIn(section);
            section.header.setText((open ? "▾ " : "▸ ") + section.name + (changed > 0 ? "   " + changed + " changed" : ""));
        }
        if (searchHint == null) return;
        String defaultHint = slot >= 0 ? "Try “stringing”, “first layer” or “adhesion”. Tap ? on a setting for what it does."
            : "Try “walls”, “adhesion” or “strong”. Tap ? on a setting for what it does.";
        if (shown > 0 || (query.isEmpty() && !onlyChanged)) searchHint.setText(defaultHint);
        else if (query.isEmpty()) searchHint.setText("No settings are changed yet.");
        else if (onlyChanged) searchHint.setText("No changed setting matches “" + query + "”. Turn off Show changed only to search every setting.");
        else searchHint.setText(elsewhere(meant) != null ? elsewhere(meant) : "Nothing matches “" + query + "”. Try a word like walls, stringing or adhesion.");
    }

    // ------------------------------------------------------------------ saved sets of changes
    private SharedPreferences store() { return getSharedPreferences(object != null ? OBJECT_SETS : slot >= 0 ? FILAMENT_SETS : CUSTOM_PRESETS, MODE_PRIVATE); }

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
