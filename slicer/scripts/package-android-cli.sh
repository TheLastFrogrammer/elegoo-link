#!/usr/bin/env bash
# Packages the Android link-slicer for testing on a phone (Termux or adb shell): the stripped static binary, the
# Elegoo and OrcaFilamentLibrary profiles it needs, sample models and a run script.
# Output: $SLICER_WORK/link-slicer-android-$ANDROID_ABI.tar.gz
set -euo pipefail
source "$(dirname "$0")/env.sh"
BUILD="${BUILD:-$SLICER_WORK/build-android-$ANDROID_ABI}"
STAGE="$SLICER_WORK/package/link-slicer"
rm -rf "$STAGE" && mkdir -p "$STAGE/resources/profiles" "$STAGE/models"
"$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" -o "$STAGE/link-slicer" "$BUILD/link-slicer"
P="$ELEGOOSLICER_SRC/resources/profiles"
cp -r "$P/Elegoo" "$P/Elegoo.json" "$P/OrcaFilamentLibrary" "$P/OrcaFilamentLibrary.json" "$STAGE/resources/profiles/"
# Nozzle data (used by the time estimate and checks) and multi-filament flush data, also read by libslic3r.
cp -r "$ELEGOOSLICER_SRC/resources/info" "$ELEGOOSLICER_SRC/resources/flush" "$STAGE/resources/"
# Bed models, textures and cover images are only used by the desktop GUI.
find "$STAGE/resources" \( -name "*.png" -o -name "*.stl" -o -name "*.svg" \) -delete
H="$ELEGOOSLICER_SRC/resources/handy_models"
cp "$H/elegoo_cube.stl" "$H/3DBenchy.drc" "$H/ElegooToleranceTest.stl" "$STAGE/models/"
cp "$SLICER_ROOT/LICENSE" "$STAGE/LICENSE"
COMMIT="$(git -C "$SLICER_ROOT" rev-parse --short HEAD 2>/dev/null || echo unknown)"
cat > "$STAGE/README.txt" <<TXT
Link Slicer (Android arm64 test build): the ElegooSlicer engine as a command-line tool.

Run it from this directory, in Termux or adb shell:
  sh slice.sh models/elegoo_cube.stl
  sh slice.sh models/3DBenchy.drc "0.16mm Optimal @Elegoo CC2 0.4 nozzle" "Elegoo PETG @ECC2"
  ./link-slicer --resources resources --list processes --printer "Elegoo Centauri Carbon 2 0.4 nozzle"

Output is G-code for the Centauri Carbon 2 plus one JSON line with the print time and filament estimate and
"elapsed_s", how long the phone took.

License: AGPL-3.0 (LICENSE). Built from https://github.com/TheLastFrogrammer/elegoo-link (slicer/, commit $COMMIT)
and ElegooSlicer $ELEGOOSLICER_COMMIT (https://github.com/ELEGOO-3D/elegooslicer). Sample models are from
ElegooSlicer's resources/handy_models.
TXT
cat > "$STAGE/slice.sh" <<'SH'
#!/bin/sh
# Usage: ./slice.sh MODEL [PROCESS] [FILAMENT] [extra link-slicer options]
# Writes MODEL's name with .gcode next to this script.
cd "$(dirname "$0")"
MODEL="$1"; shift
PROCESS="${1:-0.20mm Standard @Elegoo CC2 0.4 nozzle}"; [ $# -gt 0 ] && shift
FILAMENT="${1:-Elegoo PLA @ECC2}"; [ $# -gt 0 ] && shift
OUT="$(basename "${MODEL%.*}").gcode"
mkdir -p work
./link-slicer --resources resources --work work \
    --printer "Elegoo Centauri Carbon 2 0.4 nozzle" --process "$PROCESS" --filament "$FILAMENT" \
    --output "$OUT" "$@" "$MODEL"
SH
chmod +x "$STAGE/link-slicer" "$STAGE/slice.sh"
TARBALL="$SLICER_WORK/link-slicer-android-$ANDROID_ABI.tar.gz"
tar -C "$(dirname "$STAGE")" -czf "$TARBALL" link-slicer
echo "$TARBALL"
