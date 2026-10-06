# Step 1 results: headless engine vs. the official ElegooSlicer

**Outcome:** the headless engine produces the **same G-code as the official ElegooSlicer v1.5.3.5** for every model
and preset combination tested: every command identical, with identical time, filament and layer estimates.

## Setup

- Engine source: ElegooSlicer `2d507e39` (main, 2026-09-23). This is the v1.5.3.5 release plus one commit, a bounds
  fix in the ADMesh STL reader (`deps_src/admesh/stlinit.cpp`); no other files differ from the tag.
- Reference: the official `ElegooSlicer_Linux_V1.5.3.5.AppImage` (SHA-256 `7b3b3f08…0101afc`), run through its
  own CLI under `xvfb-run`.
- Both slicers were given the same resolved presets (`link-slicer --export-presets`) and the same model, with
  `scripts/compare-official.sh`. The official CLI was run with `--orient 0 --allow-rotations=0`. By default it
  auto-orients STL files and lets arrange rotate parts; the desktop app does neither, and link-slicer follows the
  desktop app.
- Machine: 4-core Intel Xeon 2.8 GHz, Ubuntu 24.04, GCC 13. Times are wall clock for the whole CLI run (preset load +
  slice + G-code export).

## Comparisons

All on the Centauri Carbon 2 with the Textured PEI plate (the printer preset's `default_bed_type`).

| Model | Printer / process / filament | G-code commands (differing) | Estimate | Filament | Layers | link-slicer time, peak RAM |
|---|---|---|---|---|---|---|
| Elegoo cube (STL) | 0.4 / 0.20mm Standard / Elegoo PLA | 118,704 (0) | 34m 52s | 12.10 g | 150 | 3.6 s, 164 MB |
| 3DBenchy (Draco) | 0.4 / 0.20mm Standard / Elegoo PLA | 116,074 (0) | 37m 53s | 11.70 g | 240 | 8.3 s, 202 MB |
| Elegoo tolerance test (STL) | 0.4 / 0.20mm Standard / Elegoo PLA | 20,997 (0) | 11m 49s | 3.45 g | 32 | 2.1 s, 99 MB |
| 3DBenchy (Draco) | 0.6 / 0.30mm Standard / Elegoo PETG | 51,773 (0) | 37m 50s | 13.39 g | 160 | 6.4 s, 162 MB |
| Elegoo tolerance test (STL) | 0.4 / 0.12mm Fine / Elegoo PLA | 41,719 (0) | 20m 13s | 4.07 g | 53 | 2.1 s, 111 MB |

Also checked with link-slicer alone:
- Stanford bunny (project 3MF): sliced fine (2h 58m, 97.1 g; 12.7 s, 458 MB). Not compared, because the official CLI
  crashes (segmentation fault) loading this 3MF headless.
- Settings overrides (`--set sparse_infill_density=40% --set enable_support=1 --set wall_loops=4`) are applied and
  show up in the G-code's config block.
- Three models together are arranged on one plate. Unknown settings, unknown presets and unreadable files return a
  readable `{"error": ...}`.

## What this step established

- libslic3r builds and runs **without** wxWidgets, OpenGL/GLFW, Sentry, CURL, DBus or the Agora/Elegoo network
  libraries. The engine needs 17 external libraries, built from ElegooSlicer's own pinned recipes
  (`deps/CMakeLists.txt`). Installed size: 618 MB of headers and static libraries; the `link-slicer` binary is
  66 MB, unstripped.
- System presets load straight from ElegooSlicer's `resources/profiles` (inheritance resolved by libslic3r's own
  `PresetBundle`), so the phone can ship the same profile files and pick presets by name.
- Steps the desktop app does in its GUI layer had to be replicated in the facade, and now match it: placement
  (ElegooSlicer's arrange with the bed's excluded area), the plate type default, filament map and nozzle volume
  defaults, and nanosvg's implementation.
- The facade (`engine/link_slicer.hpp`: load vendor, list presets, export presets, slice with progress and
  cancellation) is the API the Android JNI layer will wrap in step 3.

## Known gaps (for later steps)

- **No embedded thumbnail.** The CC2 expects a 144×144 PNG preview in the G-code. The desktop app renders it with
  OpenGL, which the engine doesn't have; neither CLI output above contains one. Options: a small software
  rasterizer in the facade, or rendering on Android with GLES.
- Only the model geometry of a 3MF is used. Its saved print settings, plates and per-plate assignments are not
  applied yet.
- One plate, and filament 1 for every object. Multi-filament (CANVAS) assignment comes with the Android UI.
- `DEP_GIT_ARCHIVES=ON` was used here, because this build machine could clone public GitHub repositories but not
  download their source archives. The tags fetched were:

  ```
  madler/zlib v1.2.13 04f42ceca40f73e2978b50e93806c2a18c1281fc
  glennrp/libpng v1.6.35 c17d164b4467f099b4484dfd4a279da0bc1dbd4a
  libjpeg-turbo/libjpeg-turbo 3.0.1 ec32420f6b5dfa4e86883d42b209e8371e55aeb5
  USCiLab/cereal v1.3.0 02eace19a99ce3cd564ca4e379753d69af08c2c8
  qhull/qhull v8.0.2 613debeaea72ee66626dace9ba1a2eff11b5d37d
  oneapi-src/oneTBB v2021.5.0 3df08fe234f23e732a122809b40eb129ae22733f
  stevengj/nlopt v2.5.0 24fc75fa160978e0f3d757cbe54e8d858bd25ac9
  SoftFever/Orca-deps-libnoise 1.0 f25d5331570ae109f0e645cb729ecab155612714
  google/draco 1.5.7 8786740086a9f4d83f44aa83badfbea4dce7a1b5
  Open-Cascade-SAS/OCCT V7_6_0 80ffc5f84dae96de6ed093d3e5d2466a9e368b27
  opencv/opencv 4.6.0 b0dc474160e389b9c9045da5db49d03ae17c6a6b
  tamasmeszaros/c-blosc v1.17.0_tm ab274210ddf40af659811062323cb0d6a0c64d76
  AcademySoftwareFoundation/openexr v2.5.5 4212416433a230334cef0ac122cb8d722746035d
  tamasmeszaros/openvdb a68fd58d0e2b85f01adeb8b13d7555183ab10aa5
  ```

## Implications for step 2 (Android cross-compile)

- Same 17 libraries, from the same recipes; the deps driver already passes `CMAKE_TOOLCHAIN_FILE` through to each
  one. Expect work in the autotools builds (GMP, MPFR: `--host`, and `TOOLCHAIN_PREFIX` as the MPFR recipe expects),
  Boost (Android toolchain flags), OpenCV (its own Android options), OCCT (Android support exists), and OpenVDB.
- Memory: 100–460 MB peak for these models, fine on a current phone; very large models will need a guard.
