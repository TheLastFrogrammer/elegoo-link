#!/usr/bin/env bash
# Puts the Android slicer into the app module: the stripped liblinkslicer.so into jniLibs and the profiles and
# data the engine reads into assets/slicer. Both locations are git-ignored (they are build outputs); without them
# the app still builds and its Slice screen says the slicer is not included.
set -euo pipefail
source "$(dirname "$0")/env.sh"
BUILD="${BUILD:-$SLICER_WORK/build-android-$ANDROID_ABI}"
APP="${APP:-$SLICER_ROOT/../android/app/src/main}"
LIB_DIR="$APP/jniLibs/$ANDROID_ABI"
ASSETS="$APP/assets/slicer"
mkdir -p "$LIB_DIR"
"$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" -o "$LIB_DIR/liblinkslicer.so" "$BUILD/liblinkslicer.so"
rm -rf "$ASSETS" && mkdir -p "$ASSETS/profiles"
P="$ELEGOOSLICER_SRC/resources/profiles"
cp -r "$P/Elegoo" "$P/Elegoo.json" "$P/OrcaFilamentLibrary" "$P/OrcaFilamentLibrary.json" "$ASSETS/profiles/"
cp -r "$ELEGOOSLICER_SRC/resources/info" "$ELEGOOSLICER_SRC/resources/flush" "$ASSETS/"
cp "$SLICER_ROOT/LICENSE" "$ASSETS/LICENSE-AGPL-3.0.txt"
# Bed models, textures and cover images are only used by the desktop GUI.
find "$ASSETS" \( -name "*.png" -o -name "*.stl" -o -name "*.svg" \) -delete
# Calibration models the engine's calibration prints use.
C="$ELEGOOSLICER_SRC/resources/calib"
for f in temperature_tower/temperature_tower.drc pressure_advance/tower_with_seam.drc filament_flow/Orca-LinearFlow.3mf \
         filament_flow/Orca-LinearFlow_fine.3mf volumetric_speed/SpeedTestStructure.drc retraction/retraction_tower.drc \
         pressure_advance/pressure_advance_test.drc input_shaping/ringing_tower.drc; do
    mkdir -p "$ASSETS/calib/$(dirname "$f")" && cp "$C/$f" "$ASSETS/calib/$f"
done
# The app re-extracts the assets when this changes.
echo "$ELEGOOSLICER_COMMIT $(git -C "$SLICER_ROOT" rev-parse --short HEAD 2>/dev/null || echo dev) $(date -u +%Y%m%d%H%M%S)" > "$ASSETS/VERSION"
(cd "$ASSETS" && find . -type f ! -name VERSION ! -name files.txt ! -name LICENSE-AGPL-3.0.txt | sed 's|^\./||' | sort > files.txt)
echo "Installed $(du -h "$LIB_DIR/liblinkslicer.so" | cut -f1) library and $(du -sh "$ASSETS" | cut -f1) of slicer data into $APP"
