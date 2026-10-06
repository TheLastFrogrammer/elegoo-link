#!/usr/bin/env bash
# Slices MODEL with the Android build of link-slicer and with the Linux build, using the same presets, and compares
# the G-code. The static Android build runs under qemu-aarch64 unless ADB is set, in which case it is
# pushed to a connected device and run there with adb shell.
#
#   scripts/compare-android.sh MODEL [PRINTER PROCESS FILAMENT]
#   ADB=adb scripts/compare-android.sh MODEL
set -euo pipefail
source "$(dirname "$0")/env.sh"
MODEL="$(realpath "$1")"
PRINTER="${2:-Elegoo Centauri Carbon 2 0.4 nozzle}"
PROCESS="${3:-0.20mm Standard @Elegoo CC2 0.4 nozzle}"
FILAMENT="${4:-Elegoo PLA @ECC2}"
LINUX_BIN="${LINUX_BIN:-$SLICER_WORK/build-linux/link-slicer}"
ANDROID_BIN="${ANDROID_BIN:-$SLICER_WORK/build-android-$ANDROID_ABI/link-slicer}"
STATIC_BIN="${STATIC_BIN:-$SLICER_WORK/build-android-$ANDROID_ABI/link-slicer-static}"
OUT="${OUT:-$SLICER_WORK/compare-android/$(basename "${MODEL%.*}")}"
rm -rf "$OUT" && mkdir -p "$OUT"
SELECT=(--printer "$PRINTER" --process "$PROCESS" --filament "$FILAMENT" --quiet)

/usr/bin/time -f "linux: %e s, %M KB peak" "$LINUX_BIN" --resources "$ELEGOOSLICER_SRC/resources" "${SELECT[@]}" \
    --output "$OUT/linux.gcode" "$MODEL" > "$OUT/linux.json"
if [ -n "${ADB:-}" ]; then
    DEV=/data/local/tmp/link-slicer
    "$ADB" shell mkdir -p "$DEV"
    "$ADB" push -q "$ANDROID_BIN" "$DEV/link-slicer"
    "$ADB" shell test -d "$DEV/resources" || "$ADB" push -q "$ELEGOOSLICER_SRC/resources/profiles" "$DEV/resources/profiles"
    "$ADB" push -q "$MODEL" "$DEV/model"
    "$ADB" shell "cd $DEV && time ./link-slicer --resources $DEV/resources ${SELECT[*]@Q} --work $DEV/work --output $DEV/android.gcode $DEV/model" > "$OUT/android.json"
    "$ADB" pull -q "$DEV/android.gcode" "$OUT/android.gcode"
else
    /usr/bin/time -f "android (qemu-aarch64): %e s, %M KB peak" qemu-aarch64-static "$STATIC_BIN" --resources "$ELEGOOSLICER_SRC/resources" \
        "${SELECT[@]}" --work "$OUT/work" --output "$OUT/android.gcode" "$MODEL" > "$OUT/android.json"
fi
cat "$OUT/android.json"
python3 "$SLICER_ROOT/tools/compare_gcode.py" "$OUT/android.gcode" "$OUT/linux.gcode"
