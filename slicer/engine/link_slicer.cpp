// Link Slicer engine facade over libslic3r. Mirrors the steps ElegooSlicer's own CLI takes for one plate
// (src/ElegooSlicer.cpp: load presets -> full config -> load model -> place -> Print::apply/validate/process ->
// export_gcode) without its GUI-only pieces (PartPlate, thumbnails rendered with OpenGL).
#include "link_slicer.hpp"
#include "thumbnail.hpp"

#include <algorithm>
#include <cctype>
#include <cmath>
#include <optional>
#include <set>
#include <stdexcept>

#include <boost/filesystem.hpp>
#include <boost/nowide/fstream.hpp>

#include "nlohmann/json.hpp"

#include "libslic3r/libslic3r.h"
#include "libslic3r/Arrange.hpp"
#include "libslic3r/FlushVolCalc.hpp"
#include "libslic3r/GCode/WipeTower.hpp"
#include "libslic3r/BoundingBox.hpp"
#include "libslic3r/GCode/GCodeProcessor.hpp"
#include "libslic3r/Model.hpp"
#include "libslic3r/ModelArrange.hpp"
#include "libslic3r/Preset.hpp"
#include "libslic3r/PresetBundle.hpp"
#include "libslic3r/Print.hpp"
#include "libslic3r/PrintConfig.hpp"
#include "libslic3r/Utils.hpp"

namespace linkslicer {

using namespace Slic3r;

struct Engine::State {
    std::string resources;
    std::string profiles;
    PresetBundle library;  // OrcaFilamentLibrary: base for filaments other vendors inherit from
    PresetBundle bundle;   // the selected vendor's presets
    bool loaded = false;
};

Engine::Engine(const std::string& resources_dir, const std::string& work_dir) : m_state(new State)
{
    namespace fs = boost::filesystem;
    fs::path resources = fs::absolute(resources_dir);
    if (!fs::exists(resources / "profiles"))
        throw std::runtime_error("No profiles directory in " + resources.string());
    fs::create_directories(work_dir);
    set_resources_dir(resources.string());
    set_var_dir((resources / "images").string());
    set_local_dir((resources / "i18n").string());
    set_sys_shapes_dir((resources / "shapes").string());
    set_custom_gcodes_dir((resources / "custom_gcodes").string());
    set_data_dir(fs::absolute(work_dir).string());
    set_temporary_dir(fs::absolute(work_dir).string());
    m_state->resources = resources.string();
    m_state->profiles = (resources / "profiles").string();
}

Engine::~Engine() = default;

void Engine::load_vendor(const std::string& vendor)
{
    const auto rule = ForwardCompatibilitySubstitutionRule::EnableSilent;
    m_state->library.load_vendor_configs_from_json(m_state->profiles, PresetBundle::ORCA_FILAMENT_LIBRARY, PresetBundle::LoadSystem, rule);
    m_state->bundle.load_vendor_configs_from_json(m_state->profiles, vendor, PresetBundle::LoadSystem, rule, &m_state->library);
    m_state->loaded = true;
}

static PresetCollection& collection(PresetBundle& bundle, PresetKind kind)
{
    switch (kind) {
    case PresetKind::Printer: return bundle.printers;
    case PresetKind::Process: return bundle.prints;
    default: return bundle.filaments;
    }
}

static void select(PresetCollection& presets, const std::string& name, const char* what)
{
    const Preset* preset = presets.find_preset(name, false);
    if (preset == nullptr || preset->is_default)
        throw std::runtime_error(std::string("Unknown ") + what + " preset: " + name);
    presets.select_preset_by_name(name, true);
}

std::vector<std::string> Engine::presets(PresetKind kind, const std::string& printer) const
{
    if (!m_state->loaded)
        throw std::runtime_error("No vendor loaded");
    PresetBundle& bundle = m_state->bundle;
    if (!printer.empty() && kind != PresetKind::Printer) {
        select(bundle.printers, printer, "printer");
        bundle.update_compatible(PresetSelectCompatibleType::Never);
    }
    std::vector<std::string> names;
    for (const Preset& preset : collection(bundle, kind)) {
        if (preset.is_default || !preset.is_system || !preset.is_visible)
            continue;
        if (!printer.empty() && kind != PresetKind::Printer && !preset.is_compatible)
            continue;
        names.push_back(preset.name);
    }
    return names;
}

static BedType default_bed_type(PresetBundle& bundle, const Preset& printer)
{
    return const_cast<Preset&>(printer).get_default_bed_type(&bundle);
}

// Writes one resolved preset in the JSON layout of a system profile, with inheritance already applied.
static std::string write_preset(const Preset& preset, const std::string& type, const std::string& path)
{
    preset.config.save_to_json(path, preset.name, "system", SLIC3R_VERSION);
    nlohmann::json json;
    {
        boost::nowide::ifstream in(path);
        in >> json;
    }
    json["type"] = type;
    json["inherits"] = "";
    if (type == "filament" && !preset.filament_id.empty())
        json["filament_id"] = preset.filament_id;
    boost::nowide::ofstream out(path);
    out << json.dump(4);
    return path;
}

std::vector<std::string> Engine::export_presets(const Selection& selection, const std::string& directory)
{
    if (!m_state->loaded)
        throw std::runtime_error("No vendor loaded");
    namespace fs = boost::filesystem;
    fs::create_directories(directory);
    PresetBundle& bundle = m_state->bundle;
    std::vector<std::string> files;
    auto find = [](PresetCollection& presets, const std::string& name, const char* what) -> const Preset& {
        const Preset* preset = presets.find_preset(name, false);
        if (preset == nullptr || preset->is_default)
            throw std::runtime_error(std::string("Unknown ") + what + " preset: " + name);
        return *preset;
    };
    const Preset& printer = find(bundle.printers, selection.printer, "printer");
    files.push_back(write_preset(printer, "machine", (fs::path(directory) / "printer.json").string()));
    {
        // Carry the plate type the engine would use, so another slicer loading these files picks the same plate.
        nlohmann::json json;
        { boost::nowide::ifstream in(files.back()); in >> json; }
        json["curr_bed_type"] = ConfigOptionEnum<BedType>(default_bed_type(bundle, printer)).serialize();
        boost::nowide::ofstream out(files.back());
        out << json.dump(4);
    }
    files.push_back(write_preset(find(bundle.prints, selection.process, "process"), "process", (fs::path(directory) / "process.json").string()));
    for (size_t i = 0; i < selection.filaments.size(); ++i) {
        files.push_back(write_preset(find(bundle.filaments, selection.filaments[i], "filament"), "filament",
            (fs::path(directory) / ("filament_" + std::to_string(i + 1) + ".json")).string()));
        if (i < selection.filament_colours.size() && !selection.filament_colours[i].empty()) {
            nlohmann::json json;
            { boost::nowide::ifstream in(files.back()); in >> json; }
            json["filament_colour"] = nlohmann::json::array({selection.filament_colours[i]});
            boost::nowide::ofstream out(files.back());
            out << json.dump(4);
        }
    }
    return files;
}


// "#RRGGBB" or "#RRGGBBAA" -> RGBA (BitmapCache::parse_color4 in ElegooSlicer's GUI).
static bool parse_colour(const std::string& text, unsigned char rgba[4])
{
    if ((text.size() != 7 && text.size() != 9) || text[0] != '#')
        return false;
    for (size_t i = 1; i < text.size(); ++i)
        if (!std::isxdigit(static_cast<unsigned char>(text[i])))
            return false;
    for (int c = 0; c < 4; ++c)
        rgba[c] = c < 3 || text.size() == 9 ? (unsigned char) std::stoi(text.substr(1 + 2 * c, 2), nullptr, 16) : 255;
    return true;
}

// get_min_flush_volumes() from ElegooSlicer's GUI (Plater.cpp): the nozzle volume less any long retraction on cut.
static std::vector<int> min_flush_volumes(const DynamicPrintConfig& config, size_t nozzle_id)
{
    const auto* nozzle_volume_opt = config.option<ConfigOptionFloatsNullable>("nozzle_volume");
    const int nozzle_volume = nozzle_volume_opt ? (int) nozzle_volume_opt->get_at(nozzle_id) : 0;
    const auto* machine_level_opt = config.option<ConfigOptionInt>("enable_long_retraction_when_cut");
    const int machine_level = machine_level_opt ? machine_level_opt->value : 0;
    const auto* machine_on_opt = config.option<ConfigOptionBools>("long_retractions_when_cut");
    const bool machine_on = machine_on_opt && machine_on_opt->values.size() > nozzle_id && machine_on_opt->values[nozzle_id] == 1;
    const size_t count = config.option<ConfigOptionFloats>("filament_diameter")->values.size();
    std::vector<double> filament_distance(count, 18.0), printer_distance(count, 18.0);
    std::vector<unsigned char> filament_on(count, 0);
    if (const auto* opt = config.option<ConfigOptionFloats>("filament_retraction_distances_when_cut")) filament_distance = opt->values;
    if (const auto* opt = config.option<ConfigOptionFloats>("retraction_distances_when_cut")) printer_distance = opt->values;
    if (const auto* opt = config.option<ConfigOptionBools>("filament_long_retractions_when_cut")) filament_on = opt->values;
    std::vector<int> volumes;
    for (size_t i = 0; i < count; ++i) {
        int retract = machine_level && machine_on ? (int) printer_distance[nozzle_id] : 0;
        const unsigned char on = i < filament_on.size() ? filament_on[i] : 0;
        const double distance = i < filament_distance.size() ? filament_distance[i] : 18.0;
        if (on == 0)
            retract = 0;
        else if (on == 1 && machine_level == LongRectrationLevel::EnableFilament)
            retract = !std::isnan(distance) ? (int) distance : (int) printer_distance[nozzle_id];
        volumes.push_back(int(nozzle_volume - PI * 1.75 * 1.75 / 4 * retract));
    }
    return volumes;
}

// The flushing volume between each pair of filaments from their colours, as ElegooSlicer's CLI computes it for a
// single-nozzle printer (FlushVolCalculator with the printer's flush dataset and per-printer override table).
static void compute_flush_volumes(DynamicPrintConfig& config)
{
    const std::vector<std::string> colours = config.option<ConfigOptionStrings>("filament_colour", true)->values;
    const size_t count = colours.size();
    const auto* is_support = config.option<ConfigOptionBools>("filament_is_support", true);
    const std::vector<int> minimum = min_flush_volumes(config, 0);
    int dataset = 0;
    if (const auto* opt = config.option<ConfigOptionIntsNullable>("nozzle_flush_dataset"); opt != nullptr && !opt->values.empty() && opt->values.front() != ConfigOptionIntsNullable::nil_value())
        dataset = opt->values.front();
    const std::string printer_settings_id = config.opt_string("printer_settings_id");
    std::vector<double> matrix(count * count, 0.0);
    for (size_t from = 0; from < count; ++from) {
        unsigned char from_rgba[4] = {255, 255, 255, 255};
        parse_colour(colours[from], from_rgba);
        const bool from_support = is_support->get_at(from);
        for (size_t to = 0; to < count; ++to) {
            if (from == to) continue;
            int volume;
            if (is_support->get_at(to)) {
                volume = g_flush_volume_to_support;
            } else {
                unsigned char to_rgba[4] = {255, 255, 255, 255};
                parse_colour(colours[to], to_rgba);
                FlushVolCalculator calculator(minimum[from], g_max_flush_volume, dataset);
                volume = calculator.calc_flush_vol(from_rgba[3], from_rgba[0], from_rgba[1], from_rgba[2], to_rgba[3], to_rgba[0], to_rgba[1], to_rgba[2], printer_settings_id);
                if (from_support) volume = std::max(g_min_flush_volume_from_support, volume);
            }
            matrix[count * from + to] = volume;
        }
    }
    config.option<ConfigOptionFloats>("flush_volumes_matrix", true)->values = matrix;
    config.option<ConfigOptionFloats>("flush_multiplier", true)->values.resize(1, 1.0);
}

// Filaments the model uses: object and part assignments, and painted regions.
static std::set<int> used_filaments(const Model& model)
{
    std::set<int> used;
    for (const ModelObject* object : model.objects)
        for (const ModelVolume* volume : object->volumes)
            if (volume->is_model_part())
                for (int id : volume->get_extruders())
                    if (id > 0) used.insert(id);
    return used;
}

// With more than one filament on the plate, places the prime tower as ElegooSlicer's CLI does when it arranges
// model files (ElegooSlicer.cpp with PartPlate::estimate_wipe_tower_polygon): the default back-of-bed position, kept
// inside the bed, sized from the filaments used and the tallest part. Returns its outline so arranging keeps parts
// clear of it.
static std::optional<arrangement::ArrangePolygon> place_prime_tower(DynamicPrintConfig& config, const Model& model, size_t filaments_used)
{
    if (filaments_used <= 1 || !config.opt_bool("enable_prime_tower"))
        return std::nullopt;
    if (config.has("print_sequence") && config.opt_enum<PrintSequence>("print_sequence") == PrintSequence::ByObject)
        return std::nullopt;
    auto* x_opt = config.option<ConfigOptionFloats>("wipe_tower_x", true);
    auto* y_opt = config.option<ConfigOptionFloats>("wipe_tower_y", true);
    // WIPE_TOWER_DEFAULT_X_POS / _Y_POS in ElegooSlicer's PartPlate.cpp (bed-slingers start at the left).
    const auto* structure = config.option<ConfigOptionEnum<PrinterStructure>>("printer_structure");
    float x = structure && structure->value == PrinterStructure::psI3 ? 0.f : 165.f, y = 250.f;
    const float width = float(config.opt_float("prime_tower_width"));
    const float prime_volume = float(config.opt_float("prime_volume"));

    // PartPlate::estimate_wipe_tower_size() for a single extruder.
    double max_height = 0;
    for (const ModelObject* object : model.objects)
        max_height = std::max(max_height, object->bounding_box_exact().size().z());
    const double layer_height = config.opt_float("layer_height");
    const double extra_spacing = config.option("prime_tower_infill_gap")->getFloat() / 100.;
    const auto* wall_type = config.option<ConfigOptionEnum<WipeTowerWallType>>("wipe_tower_wall_type");
    const bool rib_wall = wall_type && wall_type->value == WipeTowerWallType::wtwRib;
    double rib_width = config.option("wipe_tower_rib_width")->getFloat();
    const double volume = prime_volume * double(filaments_used - 1);
    double depth;
    if (rib_wall) {
        depth = std::sqrt(volume / layer_height * extra_spacing);
        const double volume_depth = depth;
        depth = std::max(double(WipeTower::get_limit_depth_by_height(float(max_height))), depth);
        rib_width = std::min(rib_width, depth / 2);
        depth = rib_width / std::sqrt(2.0) + std::max(depth + config.opt_float("wipe_tower_extra_rib_length"), volume_depth);
    } else {
        depth = volume / (layer_height * width) * extra_spacing;
        if (depth > EPSILON) depth = std::max(double(WipeTower::get_limit_depth_by_height(float(max_height))), depth);
    }

    BoundingBoxf bed;
    for (const Vec2d& p : config.option<ConfigOptionPoints>("printable_area")->values) bed.merge(p);
    const float bed_width = float(bed.size().x()), bed_depth = float(bed.size().y());
    const float tower_brim_width = float(config.opt_float("prime_tower_brim_width"));
    const float margin = float(WIPE_TOWER_MARGIN) + tower_brim_width;
    x = std::max(x, margin);
    y = std::max(y, margin);
    float brim = tower_brim_width;
    if (brim < 0) brim = WipeTower::get_auto_brim_by_height(float(max_height));
    // The polygon keeps the configured tower width even for rib walls, as the desktop's does.
    x = std::clamp(x, margin, bed_width - width - margin - brim);
    y = std::clamp(y, margin, float(bed_depth - depth - margin - brim));
    ConfigOptionFloat x_value(x), y_value(y);
    x_opt->set_at(&x_value, 0, 0);
    y_opt->set_at(&y_value, 0, 0);

    arrangement::ArrangePolygon tower;
    tower.poly.contour = Polygon({ {scaled(x - brim), scaled(y - brim)}, {scaled(x + width + brim), scaled(y - brim)},
                                   {scaled(x + width + brim), scaled(y + depth + brim)}, {scaled(x - brim), scaled(y + depth + brim)} });
    tower.bed_idx = 0;
    tower.setter = nullptr;
    tower.translation = {0, 0};
    tower.rotation = config.opt_float("wipe_tower_rotation_angle");
    tower.name = "WipeTower";
    tower.is_virt_object = true;
    tower.is_wipe_tower = true;
    return tower;
}

static void place_on_bed(Model& model, const DynamicPrintConfig& config, arrangement::ArrangePolygons unselected)
{
    using namespace arrangement;
    ArrangePolygons selected, excluded_regions;
    for (ModelObject* object : model.objects)
        for (ModelInstance* instance : object->instances) {
            ArrangePolygon ap = get_instance_arrange_poly(instance, config);
            ap.itemid = int(selected.size());
            selected.emplace_back(std::move(ap));
        }

    ArrangeParams params;
    params.is_seq_print = config.has("print_sequence") && config.opt_enum<PrintSequence>("print_sequence") == PrintSequence::ByObject;
    params.min_obj_distance = 0;
    if (params.is_seq_print)
        params.bed_shrink_x = params.bed_shrink_y = BED_SHRINK_SEQ_PRINT;
    if (const auto* structure = config.option<ConfigOptionEnum<PrinterStructure>>("printer_structure"))
        params.align_to_y_axis = structure->value == PrinterStructure::psI3;
    params.progressind = [](unsigned, std::string) {};

    // The printer's excluded bed areas, as one bounding box each (PartPlateList::preprocess_exclude_areas).
    if (const auto* area = config.option<ConfigOptionPoints>("bed_exclude_area"); area != nullptr && area->values.size() >= 3) {
        BoundingBoxf box;
        for (const Vec2d& p : area->values)
            box.merge(p);
        ArrangePolygon region;
        region.poly.contour = Polygon({ {scaled(box.min.x()), scaled(box.min.y())}, {scaled(box.max.x()), scaled(box.min.y())},
                                        {scaled(box.max.x()), scaled(box.max.y())}, {scaled(box.min.x()), scaled(box.max.y())} });
        region.is_virt_object = true;
        region.bed_idx = 0;
        region.height = 1;
        region.name = "ExcludedRegion";
        region.inflation = scaled(1.0);
        excluded_regions.emplace_back(std::move(region));
    }
    params.excluded_regions = excluded_regions;

    update_arrange_params(params, &config, selected);
    update_selected_items_inflation(selected, &config, params);
    update_unselected_items_inflation(unselected, &config, params);
    update_selected_items_axis_align(selected, &config, params);
    const Points bed = get_shrink_bedpts(&config, params);
    if (bed.size() < 3)
        throw std::runtime_error("The printer preset has no printable_area");
    arrange(selected, unselected, bed, params);
    for (ArrangePolygon& ap : selected) {
        if (ap.bed_idx != 0)
            throw std::runtime_error("The objects do not fit on the bed");
        ap.apply();
    }
}

Result Engine::slice(const std::vector<std::string>& models, const Selection& selection, const std::string& output,
                     const Progress& progress, const std::atomic<bool>* cancel)
{
    if (!m_state->loaded)
        throw std::runtime_error("No vendor loaded");
    if (models.empty())
        throw std::runtime_error("No model files given");
    if (selection.filaments.empty())
        throw std::runtime_error("No filament preset given");
    auto report = [&](int percent, const std::string& text) { if (progress) progress(percent, text); };

    // 1. Presets -> one flat config, as the desktop app builds it for the active printer/process/filaments.
    PresetBundle& bundle = m_state->bundle;
    select(bundle.printers, selection.printer, "printer");
    bundle.update_compatible(PresetSelectCompatibleType::Never);
    select(bundle.prints, selection.process, "process");
    // set_filament_preset() ignores slots beyond the current list, so size it first.
    bundle.filament_presets.resize(selection.filaments.size());
    for (size_t i = 0; i < selection.filaments.size(); ++i) {
        if (bundle.filaments.find_preset(selection.filaments[i], false) == nullptr)
            throw std::runtime_error("Unknown filament preset: " + selection.filaments[i]);
        if (i == 0)
            bundle.filaments.select_preset_by_name(selection.filaments[i], true);
        bundle.set_filament_preset(i, selection.filaments[i]);
    }
    DynamicPrintConfig config = bundle.full_config();
    // The plate type is project state in the desktop app, which starts it at the printer's default_bed_type.
    config.set_key_value("curr_bed_type", new ConfigOptionEnum<BedType>(default_bed_type(bundle, bundle.printers.get_edited_preset())));

    ConfigSubstitutionContext substitutions(ForwardCompatibilitySubstitutionRule::Disable);
    for (const auto& [key, value] : selection.overrides) {
        if (config.def()->get(key) == nullptr)
            throw std::runtime_error("Unknown setting: " + key);
        config.set_deserialize(key, value, substitutions);
    }
    config.normalize_fdm();

    const size_t filament_count = selection.filaments.size();
    if (!selection.filament_colours.empty()) {
        std::vector<std::string>& colours = config.option<ConfigOptionStrings>("filament_colour", true)->values;
        colours.resize(filament_count, colours.empty() ? "#F2754E" : colours.back());
        for (size_t i = 0; i < filament_count && i < selection.filament_colours.size(); ++i) {
            const std::string& colour = selection.filament_colours[i];
            if (colour.empty()) continue;
            unsigned char rgba[4];
            if (!parse_colour(colour, rgba))
                throw std::runtime_error("Filament colour " + std::to_string(i + 1) + " is not #RRGGBB: " + colour);
            colours[i] = colour;
        }
    }
    if (filament_count > 1)
        compute_flush_volumes(config);
    std::vector<int>& filament_map = config.option<ConfigOptionInts>("filament_map", true)->values;
    filament_map.assign(filament_count, 1);
    if (!config.has("nozzle_volume_type"))
        config.option<ConfigOptionEnumsGeneric>("nozzle_volume_type", true)->values.assign(1, nvtStandard);

    // 2. Models.
    report(0, "Loading model");
    Model model;
    for (size_t file = 0; file < models.size(); ++file) {
        DynamicPrintConfig ignored;
        ConfigSubstitutionContext ignored_substitutions(ForwardCompatibilitySubstitutionRule::EnableSilent);
        Model part = Model::read_from_file(models[file], &ignored, &ignored_substitutions, LoadStrategy::LoadModel | LoadStrategy::AddDefaultInstances);
        const int assigned = file < selection.model_filaments.size() ? selection.model_filaments[file] : 0;
        if (assigned < 0 || assigned > int(filament_count))
            throw std::runtime_error("Model " + std::to_string(file + 1) + " is assigned filament " + std::to_string(assigned) + ", but only " + std::to_string(filament_count) + " are set up");
        for (ModelObject* source : part.objects) {
            ModelObject* object = model.add_object(*source);
            if (assigned > 0) {
                // The whole file prints with this filament: the object's setting, with its parts' own ones cleared.
                object->config.set_key_value("extruder", new ConfigOptionInt(assigned));
                for (ModelVolume* volume : object->volumes) volume->config.erase("extruder");
            } else if (!object->config.has("extruder")) {
                object->config.set_key_value("extruder", new ConfigOptionInt(1));
            }
        }
    }
    if (model.objects.empty())
        throw std::runtime_error("The model files contain no objects");
    for (ModelObject* object : model.objects)
        object->ensure_on_bed();
    const std::set<int> used = used_filaments(model);
    if (!used.empty() && *used.rbegin() > int(filament_count))
        throw std::runtime_error("The model uses filament " + std::to_string(*used.rbegin()) + "; set up at least that many filaments");

    // 3. Placement, as ElegooSlicer's arrange does it for one plate: footprints aligned to the axes, the bed's
    // excluded areas kept clear, then nested from the bed center.
    arrangement::ArrangePolygons keep_clear;
    if (auto tower = place_prime_tower(config, model, used.size()))
        keep_clear.push_back(*tower);
    place_on_bed(model, config, keep_clear);

    // 4. Slice.
    Print print;
    Model::setExtruderParams(config, int(filament_count));
    print.apply(model, config);
    print.is_BBL_printer() = false;
    StringObjectException warning;
    StringObjectException error = print.validate(&warning);
    if (!error.string.empty())
        throw std::runtime_error(error.string);
    if (print.empty())
        throw std::runtime_error("Nothing to slice: no object is fully inside the printable area");

    Result result;
    if (!warning.string.empty())
        result.warnings.push_back(warning.string);
    print.set_status_callback([&](const PrintBase::SlicingStatus& status) {
        if (cancel != nullptr && cancel->load())
            print.cancel();
        if (status.warning_step != -1 && !status.text.empty())
            result.warnings.push_back(status.text);
        else if (status.percent >= 0)
            report(std::min(status.percent, 99), status.text);
    });
    Model::setPrintSpeedTable(config, print.config());
    try {
        print.process();
    } catch (const CanceledException&) {
        throw std::runtime_error("Slicing cancelled");
    }
    const std::string conflicts = print.get_conflict_string();
    if (!conflicts.empty())
        throw std::runtime_error("Toolpath conflict: " + conflicts);

    report(99, "Writing G-code");
    // libslic3r creates the output's parent directory itself, which fails for a bare file name.
    const boost::filesystem::path output_path = boost::filesystem::absolute(output);
    boost::filesystem::create_directories(output_path.parent_path());
    GCodeProcessorResult processed;
    // The preview image printers show in their file lists; the desktop renders it with OpenGL, the engine in software.
    ThumbnailsGeneratorCallback thumbnails = [&model, &config](const ThumbnailsParams& params) { return render_thumbnails(model, config, params); };
    result.gcode_path = print.export_gcode(output_path.string(), &processed, thumbnails);
    if (processed.gcode_check_result.error_code != 0)
        throw std::runtime_error("The G-code leaves the printable area (check code " + std::to_string(processed.gcode_check_result.error_code) + ")");

    const PrintStatistics& stats = print.print_statistics();
    result.print_time_s = processed.print_statistics.modes[static_cast<size_t>(PrintEstimatedStatistics::ETimeMode::Normal)].time;
    result.filament_mm = stats.total_used_filament;
    result.filament_g = stats.total_weight;
    result.filament_cm3 = stats.total_extruded_volume / 1000.0;
    report(100, "Done");
    return result;
}

} // namespace linkslicer
