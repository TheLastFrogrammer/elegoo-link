// Link Slicer engine facade over libslic3r. Mirrors the steps ElegooSlicer's own CLI takes for one plate
// (src/ElegooSlicer.cpp: load presets -> full config -> load model -> place -> Print::apply/validate/process ->
// export_gcode) without its GUI-only pieces (PartPlate, thumbnails rendered with OpenGL).
#include "link_slicer.hpp"
#include "thumbnail.hpp"

#include <algorithm>
#include <array>
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
#include "libslic3r/CutUtils.hpp"
#include "libslic3r/Flow.hpp"
#include "libslic3r/calib.hpp"
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

// The turn that lays a face with outward normal `down` flat on the bed (the shortest one; half a turn about X for
// a face already pointing up). The plate view computes the same.
Matrix3d lay_down(const double down[3])
{
    Vec3d n(down[0], down[1], down[2]);
    if (n.norm() < 1e-9) return Matrix3d::Identity();
    n.normalize();
    const Vec3d target(0, 0, -1);
    const double c = std::clamp(n.dot(target), -1.0, 1.0);
    if (c > 1 - 1e-9) return Matrix3d::Identity();
    if (c < -1 + 1e-9) return Eigen::AngleAxisd(PI, Vec3d::UnitX()).toRotationMatrix();
    return Eigen::AngleAxisd(std::acos(c), n.cross(target).normalized()).toRotationMatrix();
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
        matrix.linear() = (Eigen::AngleAxisd(placement->rotation * PI / 180., Vec3d::UnitZ()).toRotationMatrix() * placement->scale) * lay_down(placement->down) * base;
        ModelInstance* instance = object->add_instance();
        instance->set_transformation(Geometry::Transformation(matrix));
        const BoundingBoxf3 box = object->instance_bounding_box(*instance, false);
        instance->set_offset(Vec3d(placement->x - box.center().x(), placement->y - box.center().y(), -box.min.z()));
    }
}

} // namespace

// Cuts object `index` of `model` at height z (from the bed), keeping the lower or the upper part (Plater::cut_horizontal).
static void cut_horizontal(Model& model, size_t index, double z, bool keep_lower)
{
    ModelObject* object = model.objects[index];
    const Vec3d offset = object->instances.front()->get_offset();
    ModelObjectCutAttributes attributes = keep_lower ? ModelObjectCutAttribute::KeepLower : ModelObjectCutAttribute::KeepUpper;
    Cut cut(object, 0, Geometry::translation_transform(z * Vec3d::UnitZ() - offset), attributes);
    const ModelObjectPtrs pieces = cut.perform_with_plane();
    if (pieces.empty())
        throw std::runtime_error("The calibration model could not be cut");
    model.delete_object(index);
    for (ModelObject* piece : pieces) { ModelObject* added = model.add_object(*piece); added->ensure_on_bed(); }
}

template <typename Option, typename Value> static void set_all(DynamicPrintConfig& config, const std::string& key, Value value)
{
    auto* option = config.option<Option>(key, true);
    for (auto& v : option->values) v = value;
    if (option->values.empty()) option->values.push_back(value);
}

// A pressure advance pattern and the parameters it refers to (CalibPressureAdvancePattern keeps a reference to them).
struct PaPattern {
    Calib_Params params;
    std::unique_ptr<CalibPressureAdvancePattern> pattern;
};

// Sets every element of a (vector) option from one serialized value, whatever its exact option type (some filament
// options are nullable vectors); a missing option is created with one element.
static void set_each(DynamicPrintConfig& config, const std::string& key, const std::string& value)
{
    if (config.def()->get(key) == nullptr) return;
    ConfigOption* option = config.optptr(key, true);
    auto* vector = dynamic_cast<ConfigOptionVectorBase*>(option);
    std::string text = value;
    if (vector != nullptr) for (size_t i = 1; i < vector->size(); ++i) text += "," + value;
    if (!option->deserialize(text)) throw std::runtime_error("Bad value for " + key + ": " + value);
}

// Builds a calibration print as ElegooSlicer's Plater::calib_* functions do (model, cuts, object and global
// settings), for filament slot 1. Returns the calibration parameters for Print::set_calib_params.
static Calib_Params setup_calibration(Model& model, DynamicPrintConfig& config, const Selection::Calibration& calibration, const std::string& resources,
                                      std::shared_ptr<PaPattern>& pattern)
{
    const std::string calib = resources + "/calib/";
    auto load = [&](const std::string& path) {
        DynamicPrintConfig ignored; ConfigSubstitutionContext substitutions(ForwardCompatibilitySubstitutionRule::EnableSilent);
        Model loaded = Model::read_from_file(calib + path, &ignored, &substitutions, LoadStrategy::LoadModel | LoadStrategy::AddDefaultInstances);
        for (ModelObject* object : loaded.objects) { ModelObject* added = model.add_object(*object); added->ensure_on_bed(); }
    };
    const double nozzle = config.option<ConfigOptionFloats>("nozzle_diameter")->get_at(0);
    Calib_Params params;
    params.start = calibration.start; params.end = calibration.end; params.step = calibration.step; params.print_numbers = false;
    config.set_key_value("enable_wrapping_detection", new ConfigOptionBool(false));
    if (config.has("resonance_avoidance")) config.set_key_value("resonance_avoidance", new ConfigOptionBool(false));
    const std::string& mode = calibration.mode;

    if (mode == "temperature") {
        if (!(params.start > params.end && params.end >= 150 && params.start <= 350))
            throw std::runtime_error("Temperature tower: start must be hotter than end, between 150 and 350 °C");
        params.mode = CalibMode::Calib_Temp_Tower;
        params.step = 5; // the tower's blocks are 5 °C apart
        load("temperature_tower/temperature_tower.drc");
        const double block_height = 10.0, scale = nozzle / 0.4;
        long blocks = std::lround((500 - params.end) / 5 + 1);
        if (blocks > 0 && blocks * block_height - EPSILON < model.objects[0]->bounding_box_exact().size().z())
            cut_horizontal(model, 0, blocks * block_height - EPSILON, true);
        blocks = std::lround((500 - params.start) / 5);
        if (blocks > 0 && blocks * block_height + EPSILON < model.objects[0]->bounding_box_exact().size().z())
            cut_horizontal(model, 0, blocks * block_height + EPSILON, false);
        if (std::abs(scale - 1.0) > EPSILON) model.objects[0]->scale(scale, scale, scale);
        model.objects[0]->ensure_on_bed();
        set_all<ConfigOptionInts>(config, "nozzle_temperature_initial_layer", int(std::lround(params.start)));
        set_all<ConfigOptionInts>(config, "nozzle_temperature", int(std::lround(params.start)));
        auto& object = model.objects[0]->config;
        object.set_key_value("layer_height", new ConfigOptionFloat(nozzle / 2));
        object.set_key_value("brim_type", new ConfigOptionEnum<BrimType>(btOuterOnly));
        object.set_key_value("brim_width", new ConfigOptionFloat(5.0));
        object.set_key_value("brim_object_gap", new ConfigOptionFloat(0.0));
        object.set_key_value("alternate_extra_wall", new ConfigOptionBool(false));
        object.set_key_value("seam_slope_type", new ConfigOptionEnum<SeamScarfType>(SeamScarfType::None));
        object.set_key_value("overhang_reverse", new ConfigOptionBool(false));
        object.set_key_value("precise_z_height", new ConfigOptionBool(false));
        config.set_key_value("initial_layer_print_height", new ConfigOptionFloat(nozzle / 2));
    } else if (mode == "pressure_advance") {
        if (!(params.step > 0 && params.end > params.start && params.start >= 0 && params.end <= 2))
            throw std::runtime_error("Pressure advance: end must be above start (0 to 2), with a positive step");
        params.mode = CalibMode::Calib_PA_Tower;
        config.set_key_value("overhang_reverse", new ConfigOptionBool(false));
        config.set_key_value("precise_z_height", new ConfigOptionBool(false));
        load("pressure_advance/tower_with_seam.drc");
        set_all<ConfigOptionFloats>(config, "slow_down_layer_time", 1.0);
        auto& object = model.objects[0]->config;
        object.set_key_value("alternate_extra_wall", new ConfigOptionBool(false));
        const double speed = CalibPressureAdvance::find_optimal_PA_speed(config, config.get_abs_value("line_width", nozzle), config.get_abs_value("layer_height"), 0, 0);
        object.set_key_value("outer_wall_speed", new ConfigOptionFloat(speed));
        object.set_key_value("inner_wall_speed", new ConfigOptionFloat(speed));
        object.set_key_value("seam_position", new ConfigOptionEnum<SeamPosition>(spRear));
        object.set_key_value("wall_loops", new ConfigOptionInt(2));
        object.set_key_value("top_shell_layers", new ConfigOptionInt(0));
        object.set_key_value("bottom_shell_layers", new ConfigOptionInt(0));
        object.set_key_value("sparse_infill_density", new ConfigOptionPercent(0));
        object.set_key_value("brim_type", new ConfigOptionEnum<BrimType>(btEar));
        object.set_key_value("brim_object_gap", new ConfigOptionFloat(0.0));
        object.set_key_value("brim_ears_max_angle", new ConfigOptionFloat(135.0));
        object.set_key_value("brim_width", new ConfigOptionFloat(6.0));
        object.set_key_value("seam_slope_type", new ConfigOptionEnum<SeamScarfType>(SeamScarfType::None));
        config.set_key_value("max_volumetric_extrusion_rate_slope", new ConfigOptionFloat(0));
        const double height = std::ceil((params.end - params.start) / params.step) + 1;
        if (height < model.objects[0]->bounding_box_exact().size().z())
            cut_horizontal(model, 0, height, true);
    } else if (mode == "pa_line" || mode == "pa_pattern") {
        if (!(params.step > 0 && params.end > params.start && params.start >= 0 && params.end <= 2))
            throw std::runtime_error("Pressure advance: end must be above start (0 to 2), with a positive step");
        if ((params.end - params.start) / params.step > 60)
            throw std::runtime_error("Pressure advance: at most 60 steps; use a larger step");
        config.set_key_value("overhang_reverse", new ConfigOptionBool(false));
        config.set_key_value("precise_z_height", new ConfigOptionBool(false));
        params.print_numbers = true;
        if (mode == "pa_line") {
            // Plater::calib_pa, line method: a small anchor model; the lines themselves are G-code from CalibPressureAdvanceLine.
            params.mode = CalibMode::Calib_PA_Line;
            load("pressure_advance/pressure_advance_test.drc");
        } else {
            // Plater::_calib_pa_pattern with one speed and one acceleration: a "handle" cube; the pattern is custom G-code.
            params.mode = CalibMode::Calib_PA_Pattern;
            set_each(config, "filament_retract_when_changing_layer", "0");
            set_each(config, "filament_wipe", "0");
            set_each(config, "wipe", "0");
            set_each(config, "retract_when_changing_layer", "0");
            double accel = config.opt_float("outer_wall_acceleration");
            if (accel == 0) accel = config.opt_float("inner_wall_acceleration");
            if (accel == 0) accel = config.opt_float("default_acceleration");
            config.set_key_value("outer_wall_acceleration", new ConfigOptionFloat(accel));
            config.set_key_value("print_sequence", new ConfigOptionEnum<PrintSequence>(PrintSequence::ByLayer));
            if (config.opt_float("default_jerk") > 0) {
                double jerk = config.opt_float("outer_wall_jerk");
                if (jerk == 0) jerk = config.opt_float("inner_wall_jerk");
                if (jerk == 0) jerk = config.opt_float("default_jerk");
                for (const char* key : {"default_jerk", "outer_wall_jerk", "inner_wall_jerk", "top_surface_jerk", "infill_jerk", "travel_jerk"})
                    config.set_key_value(key, new ConfigOptionFloat(jerk));
            }
            const SuggestedConfigCalibPAPattern suggested;
            for (const auto& [key, value] : suggested.float_pairs) config.set_key_value(key, new ConfigOptionFloat(value));
            for (const auto& [key, value] : suggested.nozzle_ratio_pairs) config.set_key_value(key, new ConfigOptionFloatOrPercent(nozzle * value / 100, false));
            for (const auto& [key, value] : suggested.int_pairs) config.set_key_value(key, new ConfigOptionInt(value));
            config.set_key_value(suggested.brim_pair.first, new ConfigOptionEnum<BrimType>(suggested.brim_pair.second));
            const double speed = CalibPressureAdvance::find_optimal_PA_speed(config, config.get_abs_value("line_width", nozzle), config.get_abs_value("layer_height"), 0, 0);
            config.set_key_value("outer_wall_speed", new ConfigOptionFloat(speed));
            params.speeds = {speed}; params.accelerations = {accel};

            ModelObject* cube = model.add_object();
            cube->name = "pa_pattern_" + std::to_string(int(speed)) + "_" + std::to_string(int(accel));
            cube->add_volume(TriangleMesh(its_make_cube(1, 1, 1)));
            cube->add_instance();
            cube->center_around_origin();
            pattern = std::make_shared<PaPattern>();
            pattern->params = params;
            pattern->pattern = std::make_unique<CalibPressureAdvancePattern>(pattern->params, config, false, *cube, Vec3d::Zero());
            const CalibPressureAdvancePattern& test = *pattern->pattern;
            const BoundingBoxf3 raw = cube->raw_bounding_box();
            cube->scale(test.handle_xy_size() / raw.size().x(), test.handle_xy_size() / raw.size().y(), test.max_layer_z() / raw.size().z());
            // One test: the pattern area centered on the bed, the handle where the pattern expects it.
            BoundingBoxf bed; for (const Vec2d& p : config.option<ConfigOptionPoints>("printable_area")->values) bed.merge(p);
            if (test.print_size_x() + 4 > bed.size().x() || test.print_size_y() + 4 > bed.size().y())
                throw std::runtime_error("Pressure advance pattern: too many steps for the bed; use a larger step");
            cube->instances[0]->set_offset(Vec3d(bed.center().x(), bed.center().y(), 0) + test.handle_pos_offset());
            cube->ensure_on_bed();
        }
    } else if (mode == "shaping_freq" || mode == "shaping_damp") {
        // Plater::calib_input_shaping_freq / _damp with the ringing tower: the shaper changes with height.
        const bool freq = mode == "shaping_freq";
        if (freq) {
            if (!(params.start >= 0 && params.end > params.start && params.end <= 500))
                throw std::runtime_error("Input shaping frequency: end above start, up to 500 Hz");
            if (!(params.step >= 0 && params.step < 1))
                throw std::runtime_error("Input shaping frequency: damping must be 0 (printer's value) or below 1");
            params.mode = CalibMode::Calib_Input_shaping_freq;
            params.freqStartX = params.freqStartY = params.start; params.freqEndX = params.freqEndY = params.end;
            params.start = params.step; params.end = 0;
        } else {
            if (!(params.start >= 0 && params.end > params.start && params.end < 1))
                throw std::runtime_error("Input shaping damping: end above start, below 1");
            if (!(params.step > 0 && params.step <= 500))
                throw std::runtime_error("Input shaping damping: give the frequency found with the frequency test (up to 500 Hz)");
            params.mode = CalibMode::Calib_Input_shaping_damp;
            params.freqStartX = params.freqStartY = params.step;
        }
        params.step = 0; params.shaper_type = "";
        load("input_shaping/ringing_tower.drc");
        const std::string flavor = config.opt_serialize("gcode_flavor");
        const double jerk = flavor == "klipper" ? 5.0 : 10.0;
        for (const char* key : {"machine_max_jerk_x", "machine_max_jerk_y"}) {
            auto* option = config.option<ConfigOptionFloats>(key, true);
            const double current = option->values.empty() ? 0 : option->values.front();
            option->values.assign(std::max<size_t>(1, option->values.size()), std::max(current, jerk));
        }
        config.set_key_value("default_jerk", new ConfigOptionFloat(0));
        const auto* pa = config.option<ConfigOptionBools>("enable_pressure_advance");
        if (pa == nullptr || pa->values.empty() || !pa->values.front()) {
            set_each(config, "enable_pressure_advance", "1");
            set_each(config, "pressure_advance", "0");
            set_each(config, "adaptive_pressure_advance", "0");
        }
        if (config.has("input_shaping_emit")) config.set_key_value("input_shaping_emit", new ConfigOptionBool(false));
        set_each(config, "slow_down_layer_time", "0");
        set_each(config, "slow_down_min_speed", "0");
        set_each(config, "slow_down_for_layer_cooling", "0");
        if (freq) config.set_key_value("layer_height", new ConfigOptionFloat(0.2));
        config.set_key_value("enable_overhang_speed", new ConfigOptionBool(false));
        config.set_key_value("timelapse_type", new ConfigOptionEnum<TimelapseType>(tlTraditional));
        config.set_key_value("wall_loops", new ConfigOptionInt(1));
        config.set_key_value("top_shell_layers", new ConfigOptionInt(0));
        config.set_key_value("bottom_shell_layers", new ConfigOptionInt(1));
        config.set_key_value("sparse_infill_density", new ConfigOptionPercent(0));
        config.set_key_value("detect_thin_wall", new ConfigOptionBool(false));
        config.set_key_value("spiral_mode", new ConfigOptionBool(true));
        config.set_key_value("spiral_mode_smooth", new ConfigOptionBool(false));
        config.set_key_value("bottom_surface_pattern", new ConfigOptionEnum<InfillPattern>(ipRectilinear));
        config.set_key_value("outer_wall_speed", new ConfigOptionFloat(200));
        config.set_key_value("default_acceleration", new ConfigOptionFloat(20000));
        config.set_key_value("outer_wall_acceleration", new ConfigOptionFloat(20000));
        config.set_key_value("precise_z_height", new ConfigOptionBool(false));
        auto& object = model.objects[0]->config;
        object.set_key_value("brim_type", new ConfigOptionEnum<BrimType>(btOuterOnly));
        object.set_key_value("brim_width", new ConfigOptionFloat(3.0));
        object.set_key_value("brim_object_gap", new ConfigOptionFloat(0.0));
    } else if (mode == "flow") {
        const int pass = int(std::lround(params.start));
        if (pass != 1 && pass != 2) throw std::runtime_error("Flow rate: pass 1 or 2");
        params.mode = CalibMode::Calib_None; // flow tests are plain prints with per-object flow ratios
        load(pass == 1 ? "filament_flow/Orca-LinearFlow.3mf" : "filament_flow/Orca-LinearFlow_fine.3mf");
        // adjust_settings_for_flowrate_calib (linear "YOLO" variant)
        const double xy = nozzle / 0.6, layer_height = nozzle / 2.0;
        const double first_layer = std::max(config.opt_float("initial_layer_print_height"), layer_height);
        const double z = (first_layer + 9 * layer_height) / 2;
        const double flow = config.option<ConfigOptionFloats>("filament_flow_ratio")->get_at(0);
        const Flow infill(float(nozzle * 1.2), float(layer_height), float(nozzle));
        const double max_speed = config.option<ConfigOptionFloats>("filament_max_volumetric_speed")->get_at(0) /
                                 (infill.mm3_per_mm() * (flow + (pass == 2 ? 0.035 : 0.05)) / flow);
        const double solid_speed = std::floor(std::min(config.opt_float("internal_solid_infill_speed"), max_speed));
        const double top_speed = std::floor(std::min(config.opt_float("top_surface_speed"), max_speed));
        for (ModelObject* object : model.objects) {
            for (ModelInstance* instance : object->instances) {
                const Vec3d factor = xy > 1.2 ? Vec3d(xy, xy, z) : Vec3d(1, 1, z);
                instance->set_scaling_factor(instance->get_scaling_factor().cwiseProduct(factor));
            }
            object->ensure_on_bed();
            auto& c = object->config;
            c.set_key_value("wall_loops", new ConfigOptionInt(1));
            c.set_key_value("only_one_wall_top", new ConfigOptionBool(true));
            c.set_key_value("thick_internal_bridges", new ConfigOptionBool(false));
            c.set_key_value("enable_extra_bridge_layer", new ConfigOptionEnum<EnableExtraBridgeLayer>(eblDisabled));
            c.set_key_value("internal_bridge_density", new ConfigOptionPercent(100));
            c.set_key_value("sparse_infill_density", new ConfigOptionPercent(35));
            c.set_key_value("min_width_top_surface", new ConfigOptionFloatOrPercent(100, true));
            c.set_key_value("bottom_shell_layers", new ConfigOptionInt(2));
            c.set_key_value("top_shell_layers", new ConfigOptionInt(5));
            c.set_key_value("top_shell_thickness", new ConfigOptionFloat(0));
            c.set_key_value("bottom_shell_thickness", new ConfigOptionFloat(0));
            c.set_key_value("detect_thin_wall", new ConfigOptionBool(true));
            c.set_key_value("filter_out_gap_fill", new ConfigOptionFloat(0));
            c.set_key_value("sparse_infill_pattern", new ConfigOptionEnum<InfillPattern>(ipRectilinear));
            c.set_key_value("top_surface_line_width", new ConfigOptionFloatOrPercent(nozzle * 1.2, false));
            c.set_key_value("internal_solid_infill_line_width", new ConfigOptionFloatOrPercent(nozzle * 1.2, false));
            c.set_key_value("top_surface_pattern", new ConfigOptionEnum<InfillPattern>(ipMonotonic));
            c.set_key_value("top_solid_infill_flow_ratio", new ConfigOptionFloat(1.0));
            c.set_key_value("infill_direction", new ConfigOptionFloat(45));
            c.set_key_value("solid_infill_direction", new ConfigOptionFloat(135));
            c.set_key_value("align_infill_direction_to_model", new ConfigOptionBool(true));
            c.set_key_value("ironing_type", new ConfigOptionEnum<IroningType>(IroningType::NoIroning));
            c.set_key_value("internal_solid_infill_speed", new ConfigOptionFloat(solid_speed));
            c.set_key_value("top_surface_speed", new ConfigOptionFloat(top_speed));
            c.set_key_value("seam_slope_type", new ConfigOptionEnum<SeamScarfType>(SeamScarfType::None));
            c.set_key_value("gap_fill_target", new ConfigOptionEnum<GapFillTarget>(GapFillTarget::gftNowhere));
            c.set_key_value("calib_flowrate_topinfill_special_order", new ConfigOptionBool(true));
            // The object name carries the flow change: flowrate_0.05, flowrate_m0.05 ...
            std::string name = object->name.size() > 9 ? object->name.substr(9) : "0";
            if (!name.empty() && name[0] == 'm') name[0] = '-';
            double modifier = 0;
            try { modifier = std::stod(name); } catch (...) { }
            c.set_key_value("print_flow_ratio", new ConfigOptionFloat((flow + modifier) / flow));
        }
        config.set_key_value("max_volumetric_extrusion_rate_slope", new ConfigOptionFloat(0));
        config.set_key_value("layer_height", new ConfigOptionFloat(layer_height));
        config.set_key_value("alternate_extra_wall", new ConfigOptionBool(false));
        config.set_key_value("initial_layer_print_height", new ConfigOptionFloat(first_layer));
        config.set_key_value("reduce_crossing_wall", new ConfigOptionBool(true));
    } else if (mode == "max_flow") {
        if (!(params.step > 0 && params.end > params.start && params.start > 0 && params.end <= 200))
            throw std::runtime_error("Max volumetric speed: end above start (up to 200 mm³/s), positive step");
        params.mode = CalibMode::Calib_Vol_speed_Tower;
        load("volumetric_speed/SpeedTestStructure.drc");
        ModelObject* object = model.objects[0];
        BoundingBoxf bed; for (const Vec2d& p : config.option<ConfigOptionPoints>("printable_area")->values) bed.merge(p);
        const double fit = (bed.size().x() - 10) / object->bounding_box_exact().size().x();
        if (fit < 1.0) object->scale(fit, 1, 1);
        const double line_width = nozzle * 1.75, layer_height = nozzle * 0.8;
        auto* max_layer = config.option<ConfigOptionFloats>("max_layer_height", true);
        if (max_layer->values.empty() || max_layer->values[0] < layer_height) max_layer->values.assign(std::max<size_t>(1, max_layer->values.size()), layer_height);
        set_all<ConfigOptionFloats>(config, "filament_max_volumetric_speed", 200.0);
        set_all<ConfigOptionFloats>(config, "slow_down_layer_time", 0.0);
        auto& c = object->config;
        c.set_key_value("enable_overhang_speed", new ConfigOptionBool(false));
        c.set_key_value("wall_loops", new ConfigOptionInt(1));
        c.set_key_value("alternate_extra_wall", new ConfigOptionBool(false));
        c.set_key_value("top_shell_layers", new ConfigOptionInt(0));
        c.set_key_value("bottom_shell_layers", new ConfigOptionInt(0));
        c.set_key_value("sparse_infill_density", new ConfigOptionPercent(0));
        c.set_key_value("outer_wall_line_width", new ConfigOptionFloatOrPercent(line_width, false));
        c.set_key_value("layer_height", new ConfigOptionFloat(layer_height));
        c.set_key_value("brim_type", new ConfigOptionEnum<BrimType>(btOuterAndInner));
        c.set_key_value("brim_width", new ConfigOptionFloat(5.0));
        c.set_key_value("brim_object_gap", new ConfigOptionFloat(0.0));
        c.set_key_value("precise_z_height", new ConfigOptionBool(false));
        config.set_key_value("timelapse_type", new ConfigOptionEnum<TimelapseType>(tlTraditional));
        config.set_key_value("spiral_mode", new ConfigOptionBool(true));
        config.set_key_value("max_volumetric_extrusion_rate_slope", new ConfigOptionFloat(0));
        const double height = (params.end - params.start + 1) / params.step;
        if (height < object->bounding_box_exact().size().z()) cut_horizontal(model, 0, height, true);
        const double flow = config.option<ConfigOptionFloats>("filament_flow_ratio")->get_at(0);
        const double mm3_per_mm = Flow(float(line_width), float(layer_height), float(nozzle)).mm3_per_mm() * flow;
        params.end /= mm3_per_mm; params.start /= mm3_per_mm; params.step /= mm3_per_mm;
    } else if (mode == "retraction") {
        if (!(params.step > 0 && params.end > params.start && params.start >= 0 && params.end <= 10))
            throw std::runtime_error("Retraction: end above start (up to 10 mm), positive step");
        params.mode = CalibMode::Calib_Retraction_tower;
        load("retraction/retraction_tower.drc");
        ModelObject* object = model.objects[0];
        const double layer_height = nozzle <= 0.1 ? 0.05 : nozzle <= 0.2 ? 0.1 : 0.2;
        auto* max_layer = config.option<ConfigOptionFloats>("max_layer_height", true);
        if (max_layer->values.empty() || max_layer->values[0] < layer_height) max_layer->values.assign(std::max<size_t>(1, max_layer->values.size()), layer_height);
        config.set_key_value("use_firmware_retraction", new ConfigOptionBool(false));
        auto& c = object->config;
        c.set_key_value("wall_loops", new ConfigOptionInt(2));
        c.set_key_value("top_shell_layers", new ConfigOptionInt(0));
        c.set_key_value("bottom_shell_layers", new ConfigOptionInt(3));
        c.set_key_value("sparse_infill_density", new ConfigOptionPercent(0));
        config.set_key_value("initial_layer_print_height", new ConfigOptionFloat(layer_height));
        c.set_key_value("layer_height", new ConfigOptionFloat(layer_height));
        c.set_key_value("alternate_extra_wall", new ConfigOptionBool(false));
        c.set_key_value("seam_position", new ConfigOptionEnum<SeamPosition>(spAligned));
        c.set_key_value("wall_sequence", new ConfigOptionEnum<WallSequence>(WallSequence::InnerOuter));
        c.set_key_value("overhang_reverse", new ConfigOptionBool(false));
        c.set_key_value("precise_z_height", new ConfigOptionBool(false));
        const double height = 1.0 + 0.4 + (params.end - params.start) / params.step - EPSILON;
        if (height < object->bounding_box_exact().size().z()) cut_horizontal(model, 0, height, true);
    } else
        throw std::runtime_error("Unknown calibration: " + mode);
    for (ModelObject* object : model.objects)
        if (!object->config.has("extruder")) object->config.set_key_value("extruder", new ConfigOptionInt(1));
    return params;
}

struct Engine::Loaded {
    Model model;
    DynamicPrintConfig config;
    std::vector<std::vector<int>> objects;  // per file: index in `model` of each file object, -1 when left out
    std::vector<Matrix3d> base;             // per model object: the file's own orientation and scale (first copy)
    std::vector<std::vector<std::array<double, 3>>> downs; // per model object: each placed copy's laid-down face
    std::set<int> used;
    bool keep_layout = false;               // positions come from placements or the project plate
    Calib_Params calibration;
    std::vector<std::string> warnings;
    std::optional<arrangement::ArrangePolygon> tower;
    std::shared_ptr<PaPattern> pattern;     // pressure advance pattern: its custom G-code comes last
};

Engine::Loaded Engine::load(const std::vector<std::string>& models, const Selection& selection, bool need_models)
{
    if (!m_state->loaded)
        throw std::runtime_error("No vendor loaded");
    if (need_models && models.empty() && selection.calibration.mode.empty())
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
    const bool calibrating = !selection.calibration.mode.empty();
    loaded.objects.resize(calibrating ? 0 : models.size());
    for (size_t file = 0; file < (calibrating ? 0 : models.size()); ++file) {
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
            loaded.downs.emplace_back();
            for (const Selection::Placement* p : placed) loaded.downs.back().push_back({p->down[0], p->down[1], p->down[2]});
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
            for (const Selection::ObjectSettings& settings : selection.object_settings)
                if (settings.file == int(file) && settings.object == int(o))
                    for (const auto& [key, value] : settings.values) {
                        if (!object_setting(key))
                            throw std::runtime_error("Not a per-object setting: " + key);
                        ConfigSubstitutionContext strict(ForwardCompatibilitySubstitutionRule::Disable);
                        object->config.set_deserialize(key, value, strict);
                    }
            loaded.objects[file][o] = int(loaded.model.objects.size()) - 1;
        }
    }
    if (need_models && loaded.model.objects.empty() && !calibrating)
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
    // Per-slot filament settings: element `slot` of the filament option.
    for (size_t slot = 0; slot < selection.filament_overrides.size() && slot < filament_count; ++slot)
        for (const auto& [key, value] : selection.filament_overrides[slot]) {
            const ConfigOptionDef* def = config.def()->get(key);
            auto* vector = dynamic_cast<ConfigOptionVectorBase*>(config.optptr(key, true));
            if (def == nullptr || vector == nullptr)
                throw std::runtime_error("Not a filament setting: " + key);
            std::unique_ptr<ConfigOption> single(def->create_empty_option());
            if (!single->deserialize(value)) throw std::runtime_error("Bad value for " + key + ": " + value);
            if (vector->size() < filament_count) vector->resize(filament_count);
            vector->set_at(single.get(), slot, 0);
        }
    if (calibrating) {
        if (need_models)
            loaded.calibration = setup_calibration(loaded.model, config, selection.calibration, m_state->resources, loaded.pattern);
        if (loaded.pattern) loaded.keep_layout = true; // the handle sits where the pattern's G-code expects it
        loaded.objects.assign(1, std::vector<int>());
        for (size_t i = 0; i < loaded.model.objects.size(); ++i) { loaded.objects[0].push_back(int(i)); loaded.base.push_back(loaded.model.objects[i]->instances.front()->get_matrix_no_offset().linear()); loaded.downs.emplace_back(); }
        for (ModelObject* object : loaded.model.objects) object->ensure_on_bed();
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
    // The pattern is drawn by custom G-code around the handle cube, from the final settings (Plater::_calib_pa_pattern_gen_gcode).
    if (loaded.pattern && !loaded.model.objects.empty()) {
        loaded.model.curr_plate_index = 0;
        loaded.model.plates_custom_gcodes[0] = loaded.pattern->pattern->generate_custom_gcodes(config, false, *loaded.model.objects[0], Vec3d::Zero());
    }
    return loaded;
}

bool object_setting(const std::string& key)
{
    static const std::set<std::string> keys = [] {
        std::set<std::string> all;
        for (const std::string& k : PrintObjectConfig().keys()) all.insert(k);
        for (const std::string& k : PrintRegionConfig().keys()) all.insert(k);
        all.erase("extruder");
        return all;
    }();
    return keys.count(key) > 0;
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
    if (json.contains("filament_overrides"))
        for (const auto& slot : json["filament_overrides"]) {
            selection.filament_overrides.emplace_back();
            for (const auto& [key, value] : slot.items()) selection.filament_overrides.back().emplace_back(key, value.is_string() ? value.get<std::string>() : value.dump());
        }
    if (json.contains("object_settings"))
        for (const auto& item : json["object_settings"]) {
            Selection::ObjectSettings settings;
            settings.file = item.value("file", 0); settings.object = item.value("object", 0);
            if (item.contains("values"))
                for (const auto& [key, value] : item["values"].items()) settings.values.emplace_back(key, value.is_string() ? value.get<std::string>() : value.dump());
            selection.object_settings.push_back(settings);
        }
    if (json.contains("calibration")) {
        const auto& c = json["calibration"];
        selection.calibration.mode = c.value("mode", ""); selection.calibration.start = c.value("start", 0.0);
        selection.calibration.end = c.value("end", 0.0); selection.calibration.step = c.value("step", 0.0);
    }
    if (json.contains("placements"))
        for (const auto& item : json["placements"]) {
            Selection::Placement placement;
            placement.file = item.value("file", 0); placement.object = item.value("object", 0);
            placement.x = item.value("x", 0.0); placement.y = item.value("y", 0.0);
            placement.rotation = item.value("rotation", 0.0); placement.scale = item.value("scale", 1.0);
            if (item.contains("down") && item["down"].is_array() && item["down"].size() == 3)
                for (int k = 0; k < 3; ++k) placement.down[k] = item["down"][k].get<double>();
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
            const int index = loaded.objects[f][o];
            for (size_t i = 0; i < object->instances.size(); ++i) {
                const BoundingBoxf3 box = object->instance_bounding_box(i, false);
                std::array<double, 3> down = {0, 0, 0};
                if (i < loaded.downs[index].size()) down = loaded.downs[index][i];
                const Matrix3d base = lay_down(down.data()) * loaded.base[index];
                const auto [rotation, scale] = relative_rotation_scale(object->instances[i]->get_matrix_no_offset().linear(), base);
                nlohmann::json item = {{"file", f}, {"object", o}, {"x", box.center().x()}, {"y", box.center().y()}, {"rotation", rotation}, {"scale", scale}};
                if (down[0] != 0 || down[1] != 0 || down[2] != 0) item["down"] = {down[0], down[1], down[2]};
                placements.push_back(item);
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
    base_selection.filament_overrides.clear();
    base_selection.placements.clear();
    base_selection.object_settings.clear();
    base_selection.calibration = {};
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
        item["per_object"] = object_setting(key);
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
    if (loaded.calibration.mode != CalibMode::Calib_None)
        print.set_calib_params(loaded.calibration);
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
