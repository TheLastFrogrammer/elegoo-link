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

#include <cfloat>

#include <boost/algorithm/string/predicate.hpp>
#include <boost/filesystem.hpp>
#include <boost/nowide/fstream.hpp>

#include "nlohmann/json.hpp"

#include "libslic3r/libslic3r.h"
#include "libslic3r/Arrange.hpp"
#include "libslic3r/FlushVolCalc.hpp"
#include "libslic3r/Format/bbs_3mf.hpp"
#include "libslic3r/QuadricEdgeCollapse.hpp"
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

// ----------------------------------------------------------------------------------------------------------------
// Loading models and resolving the configuration, shared by slice(), arrange() and describe().

namespace {

constexpr double PLATE_GAP = 1. / 5.; // LOGICAL_PART_PLATE_GAP in ElegooSlicer's PartPlate.cpp

// compute_colum_count() in ElegooSlicer's PartPlate.hpp: plates are laid out in a near-square grid.
int plate_columns(int count)
{
    const float value = std::sqrt(float(count)), rounded = std::round(value);
    return value > rounded ? int(rounded) + 1 : int(rounded);
}

BoundingBoxf bed_box(const DynamicPrintConfig& config)
{
    BoundingBoxf bed;
    if (const auto* area = config.option<ConfigOptionPoints>("printable_area"))
        for (const Vec2d& p : area->values) bed.merge(p);
    return bed;
}

bool is_3mf(const std::string& path) { return boost::algorithm::iends_with(path, ".3mf"); }

struct FileModel {
    Model model;
    DynamicPrintConfig project;          // a 3MF's project settings (empty otherwise)
    std::vector<std::vector<std::pair<int, int>>> plates; // per plate: (object, instance)
    std::vector<std::string> plate_names;
};

// Where plate `index` (0-based) of `count` sits in the desktop's plate grid (PartPlateList::compute_origin).
Vec3d plate_origin(int index, int count, const BoundingBoxf& bed)
{
    const int columns = plate_columns(count);
    return Vec3d((index % columns) * bed.size().x() * (1. + PLATE_GAP), -(index / columns) * bed.size().y() * (1. + PLATE_GAP), 0);
}

// Which plate each copy is on. The 3MF lists plates, but like the desktop app (PartPlateList::reload_all_objects) the
// copies are assigned by where they sit: the plate whose area holds the center of their footprint.
void assign_plates(FileModel& file, const BoundingBoxf& bed)
{
    const int count = int(file.plates.size());
    for (auto& plate : file.plates) plate.clear();
    if (count == 0 || !bed.defined) return;
    for (size_t o = 0; o < file.model.objects.size(); ++o)
        for (size_t i = 0; i < file.model.objects[o]->instances.size(); ++i) {
            const Vec3d center = file.model.objects[o]->instance_bounding_box(i, false).center();
            for (int p = 0; p < count; ++p) {
                const Vec3d origin = plate_origin(p, count, bed);
                if (center.x() >= origin.x() + bed.min.x() && center.x() <= origin.x() + bed.max.x() &&
                    center.y() >= origin.y() + bed.min.y() && center.y() <= origin.y() + bed.max.y()) {
                    file.plates[p].emplace_back(int(o), int(i));
                    break;
                }
            }
        }
}

FileModel read_model(const std::string& path)
{
    FileModel file;
    ConfigSubstitutionContext substitutions(ForwardCompatibilitySubstitutionRule::EnableSilent);
    PlateDataPtrs plate_data;
    LoadStrategy strategy = LoadStrategy::LoadModel | LoadStrategy::AddDefaultInstances;
    if (is_3mf(path)) strategy = strategy | LoadStrategy::LoadConfig;
    try {
        file.model = Model::read_from_file(path, &file.project, &substitutions, strategy, &plate_data);
    } catch (...) {
        release_PlateData_list(plate_data);
        throw;
    }
    for (const PlateData* plate : plate_data) {
        file.plates.emplace_back();
        file.plate_names.push_back(plate->plate_name);
    }
    release_PlateData_list(plate_data);
    if (file.project.has("printable_area"))
        assign_plates(file, bed_box(file.project));
    return file;
}

// Rotation about Z (degrees) and uniform scale of `matrix` relative to `base` (both linear parts).
std::pair<double, double> relative_rotation_scale(const Matrix3d& matrix, const Matrix3d& base)
{
    const Matrix3d relative = matrix * base.inverse();
    const double scale = std::cbrt(std::abs(relative.determinant()));
    return {std::atan2(relative(1, 0), relative(0, 0)) * 180. / PI, scale};
}

// Replaces an object's copies by the given placements: the first copy's orientation and scale from the file, turned
// and scaled further, with the footprint centered on (x, y) and resting on the bed.
void apply_placements(ModelObject* object, const std::vector<const Selection::Placement*>& placements)
{
    const Matrix3d base = object->instances.front()->get_matrix_no_offset().linear();
    while (!object->instances.empty())
        object->delete_instance(object->instances.size() - 1);
    for (const Selection::Placement* placement : placements) {
        if (!(placement->scale > 0.001 && placement->scale < 1000))
            throw std::runtime_error("Scale must be between 0.001 and 1000");
        Transform3d matrix = Transform3d::Identity();
        matrix.linear() = (Eigen::AngleAxisd(placement->rotation * PI / 180., Vec3d::UnitZ()).toRotationMatrix() * placement->scale) * base;
        ModelInstance* instance = object->add_instance();
        instance->set_transformation(Geometry::Transformation(matrix));
        const BoundingBoxf3 box = object->instance_bounding_box(*instance, false);
        instance->set_offset(Vec3d(placement->x - box.center().x(), placement->y - box.center().y(), -box.min.z()));
    }
}

} // namespace

struct Engine::Loaded {
    Model model;
    DynamicPrintConfig config;
    std::vector<std::vector<int>> objects;  // per file: index in `model` of each file object, -1 when left out
    std::vector<Matrix3d> base;             // per model object: the file's own orientation and scale (first copy)
    std::set<int> used;
    bool keep_layout = false;               // positions come from placements or the project plate
    std::vector<std::string> warnings;
    std::optional<arrangement::ArrangePolygon> tower;
};

Engine::Loaded Engine::load(const std::vector<std::string>& models, const Selection& selection, bool need_models)
{
    if (!m_state->loaded)
        throw std::runtime_error("No vendor loaded");
    if (need_models && models.empty())
        throw std::runtime_error("No model files given");
    if (selection.filaments.empty())
        throw std::runtime_error("No filament preset given");
    Loaded loaded;
    const size_t filament_count = selection.filaments.size();

    // 1. Presets -> one flat config, as the desktop app builds it for the active printer/process/filaments.
    PresetBundle& bundle = m_state->bundle;
    select(bundle.printers, selection.printer, "printer");
    bundle.update_compatible(PresetSelectCompatibleType::Never);
    select(bundle.prints, selection.process, "process");
    // set_filament_preset() ignores slots beyond the current list, so size it first.
    bundle.filament_presets.resize(filament_count);
    for (size_t i = 0; i < filament_count; ++i) {
        if (bundle.filaments.find_preset(selection.filaments[i], false) == nullptr)
            throw std::runtime_error("Unknown filament preset: " + selection.filaments[i]);
        if (i == 0)
            bundle.filaments.select_preset_by_name(selection.filaments[i], true);
        bundle.set_filament_preset(i, selection.filaments[i]);
    }
    DynamicPrintConfig& config = loaded.config;
    config = bundle.full_config();
    // The plate type is project state in the desktop app, which starts it at the printer's default_bed_type.
    config.set_key_value("curr_bed_type", new ConfigOptionEnum<BedType>(default_bed_type(bundle, bundle.printers.get_edited_preset())));

    // 2. Models. A project 3MF brings its plates and settings; the plate to slice is picked here.
    DynamicPrintConfig project;
    bool project_plates = false;
    loaded.objects.resize(models.size());
    for (size_t file = 0; file < models.size(); ++file) {
        FileModel part = read_model(models[file]);
        if (project.empty() && part.project.has("print_settings_id"))
            project = part.project;
        const int assigned = file < selection.model_filaments.size() ? selection.model_filaments[file] : 0;
        if (assigned < 0 || assigned > int(filament_count))
            throw std::runtime_error("Model " + std::to_string(file + 1) + " is assigned filament " + std::to_string(assigned) + ", but only " + std::to_string(filament_count) + " are set up");

        // Copies per object: a project's chosen plate (moved from where that plate sits in the project's plate grid
        // to the bed), or all of them.
        std::vector<std::vector<int>> keep(part.model.objects.size());
        if (!part.plates.empty()) {
            const BoundingBoxf bed = bed_box(part.project.has("printable_area") ? part.project : config);
            assign_plates(part, bed);
            const int plate = selection.plate > 0 ? selection.plate : 1;
            if (plate > int(part.plates.size()))
                throw std::runtime_error("The project has " + std::to_string(part.plates.size()) + " plate(s); there is no plate " + std::to_string(plate));
            for (const auto& [object, instance] : part.plates[plate - 1])
                if (object >= 0 && object < int(keep.size()) && instance >= 0 && instance < int(part.model.objects[object]->instances.size()))
                    keep[object].push_back(instance);
            const Vec3d origin = plate_origin(plate - 1, int(part.plates.size()), bed);
            for (size_t o = 0; o < keep.size(); ++o)
                for (int instance : keep[o]) {
                    ModelInstance* copy = part.model.objects[o]->instances[instance];
                    copy->set_offset(copy->get_offset() - origin);
                }
            project_plates = true;
        } else
            for (size_t o = 0; o < keep.size(); ++o)
                for (size_t i = 0; i < part.model.objects[o]->instances.size(); ++i) keep[o].push_back(int(i));

        loaded.objects[file].assign(part.model.objects.size(), -1);
        for (size_t o = 0; o < part.model.objects.size(); ++o) {
            ModelObject* source = part.model.objects[o];
            std::vector<const Selection::Placement*> placed;
            for (const Selection::Placement& placement : selection.placements)
                if (placement.file == int(file) && placement.object == int(o)) placed.push_back(&placement);
            if (selection.placements.empty() ? keep[o].empty() : placed.empty())
                continue;
            ModelObject* object = loaded.model.add_object(*source);
            loaded.base.push_back(source->instances.front()->get_matrix_no_offset().linear());
            if (!selection.placements.empty())
                apply_placements(object, placed);
            else if (keep[o].size() != object->instances.size()) {
                for (int i = int(object->instances.size()) - 1; i >= 0; --i)
                    if (std::find(keep[o].begin(), keep[o].end(), i) == keep[o].end()) object->delete_instance(i);
            }
            if (assigned > 0) {
                // The whole file prints with this filament: the object's setting, with its parts' own ones cleared.
                object->config.set_key_value("extruder", new ConfigOptionInt(assigned));
                for (ModelVolume* volume : object->volumes) volume->config.erase("extruder");
            } else if (!object->config.has("extruder")) {
                object->config.set_key_value("extruder", new ConfigOptionInt(1));
            }
            loaded.objects[file][o] = int(loaded.model.objects.size()) - 1;
        }
    }
    if (need_models && loaded.model.objects.empty())
        throw std::runtime_error(selection.placements.empty() ? "The model files contain no objects" : "No objects are placed on the plate");
    for (ModelObject* object : loaded.model.objects)
        object->ensure_on_bed();
    loaded.keep_layout = !selection.placements.empty() || (models.size() == 1 && project_plates);

    // 3. The project's own process settings, then the app's overrides.
    if (selection.project_settings && !project.empty()) {
        for (const std::string& key : Preset::print_options())
            if (project.has(key) && config.def()->get(key) != nullptr)
                config.set_key_value(key, project.option(key)->clone());
        const std::string printer = project.opt_string("printer_settings_id");
        if (!printer.empty() && printer != selection.printer)
            loaded.warnings.push_back("The project was set up for " + printer + "; its process settings were applied to " + selection.printer + ".");
    }
    ConfigSubstitutionContext substitutions(ForwardCompatibilitySubstitutionRule::Disable);
    for (const auto& [key, value] : selection.overrides) {
        if (config.def()->get(key) == nullptr)
            throw std::runtime_error("Unknown setting: " + key);
        config.set_deserialize(key, value, substitutions);
    }
    config.normalize_fdm();

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

    loaded.used = used_filaments(loaded.model);
    if (!loaded.used.empty() && *loaded.used.rbegin() > int(filament_count))
        throw std::runtime_error("The model uses filament " + std::to_string(*loaded.used.rbegin()) + "; set up at least that many filaments");

    // 4. Placement, as ElegooSlicer's arrange does it for one plate: footprints aligned to the axes, the bed's
    // excluded areas kept clear, then nested from the bed center. Placed or project layouts are kept as they are.
    loaded.tower = place_prime_tower(config, loaded.model, loaded.used.size());
    if (!loaded.keep_layout && !loaded.model.objects.empty()) {
        arrangement::ArrangePolygons keep_clear;
        if (loaded.tower)
            keep_clear.push_back(*loaded.tower);
        place_on_bed(loaded.model, config, keep_clear);
    }
    return loaded;
}

Selection selection_from_json(const std::string& text)
{
    const nlohmann::json json = nlohmann::json::parse(text);
    Selection selection;
    selection.printer = json.value("printer", "");
    selection.process = json.value("process", "");
    if (json.contains("filaments")) selection.filaments = json["filaments"].get<std::vector<std::string>>();
    if (json.contains("colours"))
        for (const auto& colour : json["colours"]) selection.filament_colours.push_back(colour.is_string() ? colour.get<std::string>() : "");
    if (json.contains("model_filaments")) selection.model_filaments = json["model_filaments"].get<std::vector<int>>();
    if (json.contains("overrides"))
        for (const auto& [key, value] : json["overrides"].items()) selection.overrides.emplace_back(key, value.is_string() ? value.get<std::string>() : value.dump());
    selection.plate = json.value("plate", 0);
    selection.project_settings = json.value("project_settings", false);
    if (json.contains("placements"))
        for (const auto& item : json["placements"]) {
            Selection::Placement placement;
            placement.file = item.value("file", 0); placement.object = item.value("object", 0);
            placement.x = item.value("x", 0.0); placement.y = item.value("y", 0.0);
            placement.rotation = item.value("rotation", 0.0); placement.scale = item.value("scale", 1.0);
            selection.placements.push_back(placement);
        }
    return selection;
}

std::string Engine::inspect(const std::vector<std::string>& models, const std::string& mesh_dir, size_t max_triangles)
{
    nlohmann::json files = nlohmann::json::array();
    std::vector<FileModel> parts;
    size_t total_triangles = 0;
    for (const std::string& path : models) {
        parts.push_back(read_model(path));
        for (const ModelObject* object : parts.back().model.objects)
            for (const ModelVolume* volume : object->volumes)
                if (volume->is_model_part()) total_triangles += volume->mesh().its.indices.size();
    }
    double total_area = 0;
    for (size_t f = 0; f < parts.size(); ++f) {
        FileModel& part = parts[f];
        nlohmann::json objects = nlohmann::json::array();
        for (size_t o = 0; o < part.model.objects.size(); ++o) {
            const ModelObject* object = part.model.objects[o];
            const Transform3d base = object->instances.front()->get_matrix_no_offset();
            size_t triangles = 0; double area = 0;
            indexed_triangle_set merged; std::vector<unsigned char> filament;
            for (const ModelVolume* volume : object->volumes) {
                if (!volume->is_model_part()) continue;
                indexed_triangle_set its = volume->mesh().its;
                its_transform(its, base * volume->get_matrix());
                triangles += its.indices.size();
                for (const stl_triangle_vertex_indices& t : its.indices)
                    area += 0.5 * (its.vertices[t[1]] - its.vertices[t[0]]).cast<double>().cross((its.vertices[t[2]] - its.vertices[t[0]]).cast<double>()).norm();
                if (mesh_dir.empty()) continue;
                // Simplified for display, each part in proportion to its share of all triangles.
                const size_t budget = std::max<size_t>(500, size_t(double(max_triangles) * double(its.indices.size()) / double(std::max<size_t>(1, total_triangles))));
                if (its.indices.size() > budget)
                    its_quadric_edge_collapse(its, uint32_t(budget));
                const int id = volume->config.has("extruder") ? volume->config.opt_int("extruder")
                             : object->config.has("extruder") ? object->config.opt_int("extruder") : 1;
                filament.insert(filament.end(), its.indices.size(), (unsigned char) std::clamp(id, 1, 255));
                its_merge(merged, its);
            }
            const BoundingBoxf3 box = object->instance_bounding_box(0, true);
            nlohmann::json item = {{"name", object->name}, {"triangles", triangles}, {"area", area},
                                   {"size", {box.size().x(), box.size().y(), box.size().z()}}, {"copies", object->instances.size()}};
            if (!mesh_dir.empty()) {
                const std::string path = (boost::filesystem::path(mesh_dir) / ("f" + std::to_string(f) + "_o" + std::to_string(o) + ".lkm")).string();
                boost::filesystem::create_directories(mesh_dir);
                const Vec3f shift(float(-box.center().x()), float(-box.center().y()), float(-box.min.z()));
                boost::nowide::ofstream out(path, std::ios::binary);
                const uint32_t count = uint32_t(merged.indices.size());
                out.write("LKM1", 4); out.write(reinterpret_cast<const char*>(&count), 4);
                for (const stl_triangle_vertex_indices& t : merged.indices)
                    for (int corner = 0; corner < 3; ++corner) { const Vec3f v = merged.vertices[t[corner]] + shift; out.write(reinterpret_cast<const char*>(v.data()), 12); }
                out.write(reinterpret_cast<const char*>(filament.data()), filament.size());
                if (!out) throw std::runtime_error("Cannot write " + path);
                item["mesh"] = path;
            }
            total_area += area * double(object->instances.size());
            objects.push_back(item);
        }
        nlohmann::json plates = nlohmann::json::array();
        for (size_t p = 0; p < part.plates.size(); ++p) {
            nlohmann::json members = nlohmann::json::array();
            for (const auto& [object, instance] : part.plates[p]) members.push_back({object, instance});
            plates.push_back({{"plate", p + 1}, {"name", part.plate_names[p]}, {"objects", members}});
        }
        nlohmann::json file = {{"path", models[f]}, {"objects", objects}, {"plates", plates}};
        if (part.project.has("print_settings_id")) {
            nlohmann::json project = {{"printer", part.project.opt_string("printer_settings_id")}, {"process", part.project.opt_string("print_settings_id")}};
            if (const auto* ids = part.project.option<ConfigOptionStrings>("filament_settings_id")) project["filaments"] = ids->values;
            if (const auto* colours = part.project.option<ConfigOptionStrings>("filament_colour")) project["colours"] = colours->values;
            if (part.project.has("layer_height")) project["layer_height"] = part.project.opt_float("layer_height");
            file["project"] = project;
        }
        files.push_back(file);
    }
    return nlohmann::json({{"files", files}, {"triangles", total_triangles}, {"area", total_area}}).dump();
}

std::string Engine::arrange(const std::vector<std::string>& models, const Selection& selection)
{
    // Without placements: the layout slicing would use. With them: those copies (turned, scaled, duplicated) arranged.
    Loaded loaded = load(models, selection, true);
    if (!selection.placements.empty()) {
        arrangement::ArrangePolygons keep_clear;
        if (loaded.tower)
            keep_clear.push_back(*loaded.tower);
        place_on_bed(loaded.model, loaded.config, keep_clear);
    }
    nlohmann::json placements = nlohmann::json::array();
    for (size_t f = 0; f < loaded.objects.size(); ++f)
        for (size_t o = 0; o < loaded.objects[f].size(); ++o) {
            if (loaded.objects[f][o] < 0) continue;
            const ModelObject* object = loaded.model.objects[loaded.objects[f][o]];
            const Matrix3d& base = loaded.base[loaded.objects[f][o]];
            for (size_t i = 0; i < object->instances.size(); ++i) {
                const BoundingBoxf3 box = object->instance_bounding_box(i, false);
                const auto [rotation, scale] = relative_rotation_scale(object->instances[i]->get_matrix_no_offset().linear(), base);
                placements.push_back({{"file", f}, {"object", o}, {"x", box.center().x()}, {"y", box.center().y()}, {"rotation", rotation}, {"scale", scale}});
            }
        }
    nlohmann::json bed = nlohmann::json::array();
    for (const Vec2d& p : loaded.config.option<ConfigOptionPoints>("printable_area")->values) bed.push_back({p.x(), p.y()});
    nlohmann::json excluded = nlohmann::json::array();
    if (const auto* area = loaded.config.option<ConfigOptionPoints>("bed_exclude_area"))
        for (const Vec2d& p : area->values) excluded.push_back({p.x(), p.y()});
    nlohmann::json result = {{"placements", placements}, {"bed", bed}, {"excluded", excluded}, {"height", loaded.config.opt_float("printable_height")},
                             {"kept_layout", loaded.keep_layout && selection.placements.empty()}, {"warnings", loaded.warnings}};
    if (loaded.tower) {
        const BoundingBox box = loaded.tower->poly.contour.bounding_box();
        result["tower"] = {unscale<double>(box.min.x()), unscale<double>(box.min.y()), unscale<double>(box.max.x()), unscale<double>(box.max.y())};
    }
    return result.dump();
}

std::string Engine::describe(const Selection& selection, const std::vector<std::string>& keys, const std::vector<std::string>& models)
{
    Selection base_selection = selection;
    base_selection.overrides.clear();
    base_selection.placements.clear();
    const DynamicPrintConfig base = load(models, base_selection, false).config;
    const DynamicPrintConfig effective = load(models, Selection(selection), false).config;
    nlohmann::json result = nlohmann::json::object();
    for (const std::string& key : keys) {
        const ConfigOptionDef* def = print_config_def.get(key);
        if (def == nullptr) continue;
        std::string type;
        switch (def->type) {
        case coFloat: type = "float"; break;           case coInt: type = "int"; break;
        case coPercent: type = "percent"; break;       case coFloatOrPercent: type = "float_or_percent"; break;
        case coBool: type = "bool"; break;             case coEnum: type = "enum"; break;
        case coString: type = "string"; break;         case coFloats: type = "floats"; break;
        case coInts: type = "ints"; break;             case coBools: type = "bools"; break;
        case coPercents: type = "percents"; break;     case coStrings: type = "strings"; break;
        default: type = "other"; break;
        }
        nlohmann::json item = {{"label", def->full_label.empty() ? def->label : def->full_label}, {"category", def->category},
                               {"tooltip", def->tooltip}, {"unit", def->sidetext}, {"type", type}};
        if (def->min > -FLT_MAX) item["min"] = def->min;
        if (def->max < FLT_MAX) item["max"] = def->max;
        if (!def->enum_values.empty()) {
            nlohmann::json options = nlohmann::json::array();
            for (size_t i = 0; i < def->enum_values.size(); ++i)
                options.push_back({def->enum_values[i], i < def->enum_labels.size() ? def->enum_labels[i] : def->enum_values[i]});
            item["enum"] = options;
        }
        if (effective.has(key)) item["value"] = effective.opt_serialize(key);
        if (base.has(key)) item["preset"] = base.opt_serialize(key);
        result[key] = item;
    }
    return result.dump();
}

Result Engine::slice(const std::vector<std::string>& models, const Selection& selection, const std::string& output,
                     const Progress& progress, const std::atomic<bool>* cancel)
{
    auto report = [&](int percent, const std::string& text) { if (progress) progress(percent, text); };
    report(0, "Loading model");
    Loaded loaded = load(models, selection, true);
    Model& model = loaded.model;
    DynamicPrintConfig& config = loaded.config;
    const size_t filament_count = selection.filaments.size();

    // Slice.
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
    result.warnings = loaded.warnings;
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
