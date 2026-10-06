// Link Slicer engine facade: a small, GUI-free API over ElegooSlicer's libslic3r.
// The command-line tool uses it now; the Android JNI layer will wrap the same calls.
#pragma once

#include <atomic>
#include <functional>
#include <memory>
#include <string>
#include <utility>
#include <vector>

namespace linkslicer {

struct Selection {
    std::string printer;                 // e.g. "Elegoo Centauri Carbon 2 0.4 nozzle"
    std::string process;                 // e.g. "0.20mm Standard @Elegoo CC2 0.4 nozzle"
    std::vector<std::string> filaments;  // one per filament slot, e.g. {"Elegoo PLA @ECC2"}
    // Optional colour per slot ("#RRGGBB"), e.g. from the CANVAS trays. Used for flush volumes, the G-code's
    // filament_colour and the thumbnail; slots without one keep the preset's colour.
    std::vector<std::string> filament_colours;
    // Optional slot per model file (1-based). 0 or missing keeps the file's own assignment (3MF parts and painting),
    // or slot 1 when the file has none.
    std::vector<int> model_filaments;
    std::vector<std::pair<std::string, std::string>> overrides; // config key -> serialized value
};

struct Result {
    std::string gcode_path;
    double print_time_s = 0;      // normal-mode estimate from the G-code processor
    double filament_mm = 0;
    double filament_g = 0;
    double filament_cm3 = 0;
    std::vector<std::string> warnings;
};

enum class PresetKind { Printer, Process, Filament };

// Progress callback: percent (0-100) and a status line.
using Progress = std::function<void(int, const std::string&)>;

class Engine {
public:
    // resources_dir: ElegooSlicer's "resources" directory (profiles, shapes, custom gcodes).
    // work_dir: writable scratch directory for temporary files.
    Engine(const std::string& resources_dir, const std::string& work_dir);
    ~Engine();

    // Loads one vendor's system presets (with OrcaFilamentLibrary as the base for cross-vendor inheritance).
    void load_vendor(const std::string& vendor = "Elegoo");

    // Visible system presets of a kind; for processes and filaments, only those compatible with `printer` when given.
    std::vector<std::string> presets(PresetKind kind, const std::string& printer = "") const;

    // Writes the fully resolved printer, process and filament presets of `selection` as JSON files
    // (printer.json, process.json, filament_1.json, ...) in the format ElegooSlicer's --load-settings accepts.
    std::vector<std::string> export_presets(const Selection& selection, const std::string& directory);

    // Slices the model files (STL/3MF/OBJ) onto one plate and writes G-code to `output`.
    // Throws std::runtime_error with a readable message on failure. `cancel` may be set from another thread.
    Result slice(const std::vector<std::string>& models, const Selection& selection, const std::string& output,
                 const Progress& progress = nullptr, const std::atomic<bool>* cancel = nullptr);

private:
    struct State;
    std::unique_ptr<State> m_state;
};

} // namespace linkslicer
