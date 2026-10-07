// link-slicer: headless command-line front end for the Link Slicer engine.
//
//   link-slicer --resources DIR --list printers|processes|filaments [--printer NAME]
//   link-slicer --resources DIR --printer NAME --process NAME --filament NAME [--filament NAME ...]
//               [--colour #RRGGBB ...] [--assign 1,2,...] [--set key=value ...] --output out.gcode model.stl [model2.3mf ...]
//   link-slicer --resources DIR --printer NAME --process NAME --filament NAME --export-presets DIR
//               (resolved presets as JSON, the form ElegooSlicer's own --load-settings/--load-filaments take)
//   link-slicer --resources DIR --inspect [--mesh-dir DIR] MODEL...      (objects, 3MF plates and project settings)
//   link-slicer --resources DIR ... --arrange MODEL...                   (where each object would go)
//   link-slicer --resources DIR ... --describe key,key [MODEL...]         (setting definitions and values)
// Projects and layouts: --plate N, --project-settings, --place FILE,OBJECT,X,Y[,ROTATION[,SCALE]] (once per copy).
//
// Prints one JSON object with the result (or {"error": ...}) on stdout; progress goes to stderr.
#include <chrono>
#include <cstdlib>
#include <iostream>
#include <sstream>
#include <string>
#include <vector>

#include <boost/filesystem.hpp>

#include "libslic3r/Utils.hpp"
#include "link_slicer.hpp"

namespace {

std::string json_string(const std::string& value)
{
    std::ostringstream out;
    out << '"';
    for (unsigned char c : value) {
        switch (c) {
        case '"': out << "\\\""; break;
        case '\\': out << "\\\\"; break;
        case '\n': out << "\\n"; break;
        case '\r': out << "\\r"; break;
        case '\t': out << "\\t"; break;
        default:
            if (c < 0x20) {
                char buffer[8];
                std::snprintf(buffer, sizeof buffer, "\\u%04x", c);
                out << buffer;
            } else {
                out << c;
            }
        }
    }
    out << '"';
    return out.str();
}

int usage()
{
    std::cerr <<
        "Usage:\n"
        "  link-slicer --resources DIR --list printers|processes|filaments [--printer NAME]\n"
        "  link-slicer --resources DIR --printer NAME --process NAME --filament NAME [--filament NAME ...]\n"
        "              [--colour #RRGGBB ...] [--assign N,N,...] [--set key=value ...] [--vendor Elegoo] [--work DIR]\n"
        "              --output out.gcode MODEL...\n"
        "    --filament and --colour repeat once per filament slot; --assign gives each MODEL file its slot.\n"
        "  link-slicer --resources DIR --printer NAME --process NAME --filament NAME --export-presets DIR\n"
        "  link-slicer --resources DIR --inspect [--mesh-dir DIR] MODEL...\n"
        "  link-slicer --resources DIR --printer NAME --process NAME --filament NAME --arrange MODEL...\n"
        "  link-slicer --resources DIR --printer NAME --process NAME --filament NAME --describe KEY,KEY [MODEL...]\n"
        "    With slicing or --arrange: --plate N (3MF project plate), --project-settings (the project's process\n"
        "    settings), --place FILE,OBJECT,X,Y[,ROTATION[,SCALE[,DX,DY,DZ]]] (once per copy; files and objects count from 0;\n"
        "    DX,DY,DZ: outward normal of a face to lay on the bed).\n"
        "    --filament-set SLOT,key=value changes one filament slot's setting; --calibrate MODE,START,END,STEP slices\n"
        "    a calibration print instead of MODEL files (temperature, pressure_advance, flow, max_flow, retraction).\n";
    return 2;
}

} // namespace

int main(int argc, char** argv)
{
    const auto started = std::chrono::steady_clock::now(); // elapsed_s covers preset loading as well as slicing
    std::string resources, vendor = "Elegoo", list, output, work, export_dir, mesh_dir, describe;
    bool quiet = false, inspect = false, arrange = false;
    linkslicer::Selection selection;
    std::vector<std::string> models;
    for (int i = 1; i < argc; ++i) {
        std::string arg = argv[i];
        auto value = [&]() -> std::string {
            if (i + 1 >= argc) { std::cerr << arg << " needs a value\n"; std::exit(usage()); }
            return argv[++i];
        };
        if (arg == "--resources") resources = value();
        else if (arg == "--vendor") vendor = value();
        else if (arg == "--list") list = value();
        else if (arg == "--printer") selection.printer = value();
        else if (arg == "--process") selection.process = value();
        else if (arg == "--filament") selection.filaments.push_back(value());
        else if (arg == "--colour" || arg == "--color") selection.filament_colours.push_back(value());
        else if (arg == "--assign") {
            std::stringstream list(value()); std::string item;
            while (std::getline(list, item, ',')) {
                try { selection.model_filaments.push_back(std::stoi(item)); }
                catch (const std::exception&) { std::cerr << "--assign expects filament numbers like 1,2,1\n"; return usage(); }
            }
        }
        else if (arg == "--output") output = value();
        else if (arg == "--work") work = value();
        else if (arg == "--export-presets") export_dir = value();
        else if (arg == "--quiet") quiet = true;
        else if (arg == "--inspect") inspect = true;
        else if (arg == "--mesh-dir") mesh_dir = value();
        else if (arg == "--arrange") arrange = true;
        else if (arg == "--describe") describe = value();
        else if (arg == "--plate") selection.plate = std::atoi(value().c_str());
        else if (arg == "--project-settings") selection.project_settings = true;
        else if (arg == "--calibrate") {
            std::stringstream list(value()); std::string item; std::vector<std::string> parts;
            while (std::getline(list, item, ',')) parts.push_back(item);
            if (parts.empty()) { std::cerr << "--calibrate expects MODE[,START,END,STEP]\n"; return usage(); }
            selection.calibration.mode = parts[0];
            if (parts.size() > 1) selection.calibration.start = std::atof(parts[1].c_str());
            if (parts.size() > 2) selection.calibration.end = std::atof(parts[2].c_str());
            if (parts.size() > 3) selection.calibration.step = std::atof(parts[3].c_str());
        }
        else if (arg == "--filament-set") {
            std::string spec = value(); size_t comma = spec.find(','), eq = spec.find('=');
            if (comma == std::string::npos || eq == std::string::npos || eq < comma) { std::cerr << "--filament-set expects SLOT,key=value\n"; return usage(); }
            const size_t slot = size_t(std::max(1, std::atoi(spec.substr(0, comma).c_str()))) - 1;
            if (selection.filament_overrides.size() <= slot) selection.filament_overrides.resize(slot + 1);
            selection.filament_overrides[slot].emplace_back(spec.substr(comma + 1, eq - comma - 1), spec.substr(eq + 1));
        }
        else if (arg == "--place") {
            std::stringstream list(value()); std::string item; std::vector<double> numbers;
            while (std::getline(list, item, ',')) numbers.push_back(std::atof(item.c_str()));
            if (numbers.size() < 4) { std::cerr << "--place expects FILE,OBJECT,X,Y[,ROTATION[,SCALE]]\n"; return usage(); }
            linkslicer::Selection::Placement placement;
            placement.file = int(numbers[0]); placement.object = int(numbers[1]); placement.x = numbers[2]; placement.y = numbers[3];
            if (numbers.size() > 4) placement.rotation = numbers[4];
            if (numbers.size() > 5) placement.scale = numbers[5];
            if (numbers.size() > 8) for (int k = 0; k < 3; ++k) placement.down[k] = numbers[6 + k];
            selection.placements.push_back(placement);
        }
        else if (arg == "--set") {
            std::string pair = value();
            size_t eq = pair.find('=');
            if (eq == std::string::npos || eq == 0) { std::cerr << "--set expects key=value\n"; return usage(); }
            selection.overrides.emplace_back(pair.substr(0, eq), pair.substr(eq + 1));
        }
        else if (arg == "--help" || arg == "-h") return usage();
        else if (!arg.empty() && arg[0] == '-') { std::cerr << "Unknown option " << arg << "\n"; return usage(); }
        else models.push_back(arg);
    }
    const bool query = inspect || arrange || !describe.empty();
    const bool calibrating = !selection.calibration.mode.empty();
    if (resources.empty() || (list.empty() && export_dir.empty() && !query && (output.empty() || (models.empty() && !calibrating) || selection.printer.empty() || selection.process.empty())))
        return usage();
    if (work.empty())
        work = (boost::filesystem::temp_directory_path() / "link-slicer").string();

    // Errors only, unless asked otherwise; libslic3r logs a lot at info level.
    Slic3r::set_logging_level(quiet ? 0 : 1);
    try {
        linkslicer::Engine engine(resources, work);
        engine.load_vendor(vendor);
        if (!list.empty()) {
            linkslicer::PresetKind kind;
            if (list == "printers") kind = linkslicer::PresetKind::Printer;
            else if (list == "processes") kind = linkslicer::PresetKind::Process;
            else if (list == "filaments") kind = linkslicer::PresetKind::Filament;
            else return usage();
            for (const std::string& name : engine.presets(kind, selection.printer))
                std::cout << name << "\n";
            return 0;
        }
        if (inspect) { std::cout << engine.inspect(models, mesh_dir) << std::endl; return 0; }
        if (arrange) { std::cout << engine.arrange(models, selection) << std::endl; return 0; }
        if (!describe.empty()) {
            std::vector<std::string> keys; std::stringstream list(describe); std::string key;
            while (std::getline(list, key, ',')) keys.push_back(key);
            std::cout << engine.describe(selection, keys, models) << std::endl;
            return 0;
        }
        if (!export_dir.empty()) {
            for (const std::string& file : engine.export_presets(selection, export_dir))
                std::cout << file << "\n";
            if (output.empty())
                return 0;
        }
        int last = -1;
        auto progress = [&](int percent, const std::string& text) {
            if (quiet || percent == last) return;
            last = percent;
            std::cerr << "[" << percent << "%] " << text << std::endl;
        };
        linkslicer::Result result = engine.slice(models, selection, output, progress);
        const double elapsed = std::chrono::duration<double>(std::chrono::steady_clock::now() - started).count();
        std::cout << "{\"gcode\":" << json_string(result.gcode_path)
                  << ",\"print_time_s\":" << result.print_time_s
                  << ",\"filament_mm\":" << result.filament_mm
                  << ",\"filament_g\":" << result.filament_g
                  << ",\"filament_cm3\":" << result.filament_cm3
                  << ",\"elapsed_s\":" << elapsed
                  << ",\"warnings\":[";
        for (size_t i = 0; i < result.warnings.size(); ++i)
            std::cout << (i ? "," : "") << json_string(result.warnings[i]);
        std::cout << "]}" << std::endl;
        return 0;
    } catch (const std::exception& error) {
        std::cout << "{\"error\":" << json_string(error.what()) << "}" << std::endl;
        return 1;
    }
}
