# ElegooSlicer on Android: native port plan

Goal: slice STL/3MF on the phone with the same engine and CC2 profiles as ElegooSlicer, then hand the G-code to
Link Workshop's existing upload/start path. No proot, no X11, no wxWidgets.

## Why not port the desktop app

ElegooSlicer (an OrcaSlicer/Bambu Studio derivative) has two halves:

- **libslic3r**: the slicing engine (~200k lines of C++). Pure computation, no GUI.
- **slic3r/GUI**: wxWidgets + desktop OpenGL. wxWidgets has no Android port, and the GUI assumes a mouse, a large
  screen and desktop GL. The proot/X11 route works because it avoids porting this, and that's also why it feels hacky.

So the plan is: build **libslic3r headless for Android arm64**, wrap it in a small JNI facade, and write a
**native Android UI** for the part of the workflow that fits a phone (open model, pick printer/filament/process
preset, a few common overrides, slice, preview, send).

The CLI entry point (`CLI::run` in `src/ElegooSlicer.cpp`, used by `--slice` / `--export-3mf`) already drives
libslic3r without the GUI; it's the reference for what the JNI facade has to do.

## Phases

### 1. Headless build on Linux (proves the engine path)
- Configure ElegooSlicer with `SLIC3R_GUI=OFF` and build only libslic3r + the CLI.
- Slice a CC2 test model with the bundled `resources/profiles/Elegoo/machine/ECC2/*.json` presets and diff the
  G-code against the desktop app's output for the same 3MF. This becomes the regression check for every later step.

### 2. Cross-compile the dependencies with the NDK (arm64-v8a, API 26+)
The bulk of the work. Most are CMake projects that cross-compile cleanly:

| Dependency | Notes |
|---|---|
| Boost (filesystem, thread, log, locale, iostreams, …) | Build with b2 + NDK clang toolchain; trim to the libs libslic3r links |
| TBB (oneTBB) | Has Android support |
| CGAL + GMP + MPFR | CGAL is headers; GMP/MPFR need autotools cross-builds (GMP: `--disable-assembly` is the safe start) |
| OpenCASCADE (OCCT) | Only for STEP import; large. First cut: **disable STEP**, add later |
| OpenCV | Use the official Android SDK build, or drop if only used for a GUI feature |
| Eigen, libigl, cereal, Clipper/Clipper2, qhull, admesh, miniz, libnest2d, semver, qoi, noise, mcut, glu-libtess | Header-only or bundled; build as-is |
| expat, zlib, libpng, libjpeg-turbo, freetype, draco | Straightforward CMake/NDK builds (zlib ships with the NDK) |
| OpenSSL / curl | Only for network features the engine doesn't need; stub or drop |
| OpenVDB, fontconfig | Disable (hollowing / system fonts not needed for FDM slicing on the phone) |

Output: a prebuilt sysroot (`deps/android-arm64/`) produced by a script, cached in CI.

### 3. libslic3r for Android + JNI facade
A deliberately small C API so the Java side never touches libslic3r types:

```
slicer_open(model_path) -> handle
slicer_load_presets(printer_json, filament_json, process_json)
slicer_set(key, value)                // overrides: layer height, infill, supports, brim, …
slicer_arrange()
slicer_slice(progress_callback, cancel_flag)
slicer_export_gcode(out_path) -> stats JSON (time, filament g/m, layers)
slicer_export_preview(out_path)        // toolpaths for the preview
slicer_close(handle)
```
Run slicing in a foreground service (it can take minutes and lots of memory); report progress via the callback.

### 4. Android UI (in this app or a sibling app — see licensing)
- Import STL/3MF/OBJ from the Files picker or share sheet.
- Preset pickers seeded from the CC2 profile JSON (printer, filament per tray, process), plus a short list of
  common overrides. Material-per-tray can reuse the CANVAS data Link Workshop already reads.
- Simple 3D model view (rotate/scale/place on plate) and a layer preview. libvgcode (the G-code viewer library)
  already supports OpenGL ES (`ENABLE_OPENGL_ES`), so the preview can reuse it on a GLSurfaceView.
- "Send to printer" hands the G-code to the existing upload + start flow (local or cloud).

### 5. Later
STEP import (OCCT), multi-plate, painting (supports/seams/color), and a fuller settings editor.

## Licensing (decide before phase 3)
ElegooSlicer and libslic3r are **AGPL-3.0**. Any app that links them must be distributed under AGPL-3.0 with
source. Options:
1. **Separate "Link Slicer" app/module** under AGPL-3.0 that hands G-code to Link Workshop through an Android
   share/intent. Keeps Link Workshop's license independent. *(Recommended.)*
2. Put the slicer inside Link Workshop and license the whole app AGPL-3.0. Simpler UX; note Link Workshop already
   ships the proprietary Agora RTM SDK, which conflicts with AGPL distribution — so this option would need the
   cloud remote-control piece split out.

## Alternative worth checking
ElegooSlicer has a "CloudSlicing" printer status (1080), which suggests Elegoo runs a cloud slicing service. If its
API is usable it would give phone slicing with no port at all, but it would depend on Elegoo's servers and
wouldn't work offline. Worth a short investigation alongside phase 1, not instead of it.

## Rough effort
Phase 1: days. Phase 2: the long pole (1–3 weeks of build plumbing, mostly Boost/GMP/MPFR/CGAL). Phase 3: about a
week. Phase 4: the open-ended part; a minimal import → preset → slice → send flow is a few weeks.

## Reusing the proot work
Keep the proot/X11 setup as a stopgap until phase 4 lands. Its Ubuntu toolchain is also a handy place to run the
phase 1 headless build directly on the phone.
