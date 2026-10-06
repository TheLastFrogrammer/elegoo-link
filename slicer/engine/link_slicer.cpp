// Link Slicer engine facade over libslic3r. Mirrors the steps ElegooSlicer's own CLI takes for one plate
// (src/ElegooSlicer.cpp: load presets -> full config -> load model -> place -> Print::apply/validate/process ->
// export_gcode) without its GUI-only pieces (PartPlate, thumbnails rendered with OpenGL).
#include "link_slicer.hpp"
#include "thumbnail.hpp"

#include <algorithm>
#include <stdexcept>

#include <boost/filesystem.hpp>
#include <boost/nowide/fstream.hpp>

#include "nlohmann/json.hpp"

#include "libslic3r/libslic3r.h"
#include "libslic3r/Arrange.hpp"
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
    for (size_t i = 0; i < selection.filaments.size(); ++i)
        files.push_back(write_preset(find(bundle.filaments, selection.filaments[i], "filament"), "filament",
            (fs::path(directory) / ("filament_" + std::to_string(i + 1) + ".json")).string()));
    return files;
}

static void place_on_bed(Model& model, const DynamicPrintConfig& config)
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

    ArrangePolygons unselected;
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
    for (size_t i = 0; i < selection.filaments.size(); ++i) {
        if (bundle.filaments.find_preset(selection.filaments[i], false) == nullptr)
            throw std::runtime_error("Unknown filament preset: " + selection.filaments[i]);
        if (i == 0)
            bundle.filaments.select_preset_by_name(selection.filaments[i], true);
        bundle.set_filament_preset(i, selection.filaments[i]);
    }
    bundle.filament_presets.resize(selection.filaments.size());
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
    std::vector<int>& filament_map = config.option<ConfigOptionInts>("filament_map", true)->values;
    filament_map.assign(filament_count, 1);
    if (!config.has("nozzle_volume_type"))
        config.option<ConfigOptionEnumsGeneric>("nozzle_volume_type", true)->values.assign(1, nvtStandard);

    // 2. Models.
    report(0, "Loading model");
    Model model;
    for (const std::string& path : models) {
        DynamicPrintConfig ignored;
        ConfigSubstitutionContext ignored_substitutions(ForwardCompatibilitySubstitutionRule::EnableSilent);
        Model part = Model::read_from_file(path, &ignored, &ignored_substitutions, LoadStrategy::LoadModel | LoadStrategy::AddDefaultInstances);
        for (ModelObject* object : part.objects) {
            model.add_object(*object);
        }
    }
    if (model.objects.empty())
        throw std::runtime_error("The model files contain no objects");
    for (ModelObject* object : model.objects) {
        // Single-filament plates print everything with filament 1; extra filaments are assigned by the caller later.
        if (!object->config.has("extruder"))
            object->config.set_key_value("extruder", new ConfigOptionInt(1));
        object->ensure_on_bed();
    }

    // 3. Placement, as ElegooSlicer's arrange does it for one plate: footprints aligned to the axes, the bed's
    // excluded areas kept clear, then nested from the bed center.
    place_on_bed(model, config);

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
