# Link Slicer (headless ElegooSlicer engine)

Step 1 of the on-phone slicer plan in [../android/SLICER_PORT.md](../android/SLICER_PORT.md): ElegooSlicer's
slicing engine (`libslic3r`) built **without** its desktop GUI, behind a small C++ facade
(`engine/link_slicer.hpp`) that the Android JNI layer will wrap later, plus a command-line tool, `link-slicer`.

This directory is **AGPL-3.0** (see [LICENSE](LICENSE)): it builds and links ElegooSlicer's AGPL-3.0 code, and
`deps/CMakeLists.txt` adapts its dependency build. The rest of this repository keeps its own license.

## What gets built

- `deps/`: a dependency superbuild that reuses ElegooSlicer's own recipes (`deps/<Name>/<Name>.cmake`, which pin
  the same versions, hashes and patches) but only for what libslic3r links: zlib, libpng, libjpeg-turbo, FreeType,
  Boost, oneTBB, cereal, Qhull, GMP, MPFR, Eigen, CGAL, NLopt, libnoise, Draco, OpenCASCADE, OpenCV and OpenVDB
  (with Blosc and OpenEXR; only SLA hollowing uses it, but libslic3r includes it unconditionally).
  ElegooSlicer's full deps build cannot be configured without OpenGL, even for these libraries.
  Left out: wxWidgets, GLEW/GLFW, OpenCSG, Sentry, CURL and the Elegoo network stack.
- `CMakeLists.txt`: ElegooSlicer's `src/libslic3r` and the bundled libraries it uses from `deps_src`, with the
  compiler setup of ElegooSlicer's top-level CMakeLists.txt, plus `link_slicer` (the facade) and `link-slicer`.
- ElegooSlicer itself is not copied here. `scripts/fetch-elegooslicer.sh` checks it out at a pinned commit
  (`ELEGOOSLICER_COMMIT` in `scripts/env.sh`).

## Build (Linux x86-64)

```sh
slicer/scripts/build-deps-linux.sh   # dependencies into work/deps-linux (once; about an hour on 4 cores)
slicer/scripts/build-linux.sh        # libslic3r + link-slicer into work/build-linux
```

Settings (environment variables, see `scripts/env.sh`): `SLICER_WORK` (default `slicer/work`), `ELEGOOSLICER_SRC`
(use an existing checkout), `JOBS`. If GitHub source archives are blocked but git clones work, set
`DEP_GIT_ARCHIVES=ON`: the dependency build then fetches those sources by git tag instead (the tag's commit is logged
in `downloads/git-archives/git-sources.txt`, since the recipe's archive hash no longer applies).

## Use

```sh
R=work/elegooslicer/resources
link-slicer --resources $R --list printers
link-slicer --resources $R --list processes --printer "Elegoo Centauri Carbon 2 0.4 nozzle"
link-slicer --resources $R --list filaments --printer "Elegoo Centauri Carbon 2 0.4 nozzle"
link-slicer --resources $R \
    --printer "Elegoo Centauri Carbon 2 0.4 nozzle" \
    --process "0.20mm Standard @Elegoo CC2 0.4 nozzle" \
    --filament "Elegoo PLA @ECC2" \
    --set sparse_infill_density=15% --set enable_support=1 \
    --output cube.gcode model.stl
```

It prints one JSON line (`gcode`, `print_time_s`, `filament_mm`, `filament_g`, `filament_cm3`, `warnings`, or
`error`) and progress on stderr. A single object is centered on the bed; several are arranged. `--set` takes any
ElegooSlicer setting key with its serialized value. `--export-presets DIR` writes the resolved presets as JSON.

## Checking against the official ElegooSlicer

`scripts/compare-official.sh MODEL` slices a model with `link-slicer` and with the official ElegooSlicer CLI
(`OFFICIAL=.../squashfs-root/AppRun`, run under `xvfb-run` when there is no display), feeding both the same
resolved presets, and compares the G-code with `tools/compare_gcode.py`.

## Results

See [RESULTS.md](RESULTS.md).
