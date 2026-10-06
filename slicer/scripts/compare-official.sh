#!/usr/bin/env bash
# Slices MODEL with link-slicer and with the official ElegooSlicer CLI using the same resolved presets, then
# compares the G-code. OFFICIAL is the official binary (for example an extracted AppImage's AppRun); on a machine
# without a display it is run under xvfb-run. The official CLI auto-orients STL files and lets arrange rotate parts
# by default; both are turned off to match the desktop app's defaults, which link-slicer follows.
#
#   OFFICIAL=/path/to/squashfs-root/AppRun scripts/compare-official.sh MODEL [PRINTER PROCESS FILAMENT]
set -euo pipefail
source "$(dirname "$0")/env.sh"
MODEL="$(realpath "$1")"
PRINTER="${2:-Elegoo Centauri Carbon 2 0.4 nozzle}"
PROCESS="${3:-0.20mm Standard @Elegoo CC2 0.4 nozzle}"
FILAMENT="${4:-Elegoo PLA @ECC2}"
BUILD="${BUILD:-$SLICER_WORK/build-linux}"
OUT="${OUT:-$SLICER_WORK/compare/$(basename "${MODEL%.*}")}"
: "${OFFICIAL:?Set OFFICIAL to the official ElegooSlicer executable}"
rm -rf "$OUT" && mkdir -p "$OUT/presets" "$OUT/official"
SELECT=(--resources "$ELEGOOSLICER_SRC/resources" --printer "$PRINTER" --process "$PROCESS" --filament "$FILAMENT")

"$BUILD/link-slicer" "${SELECT[@]}" --export-presets "$OUT/presets" > /dev/null
/usr/bin/time -f "link-slicer: %e s, %M KB peak" "$BUILD/link-slicer" "${SELECT[@]}" --quiet --output "$OUT/link-slicer.gcode" "$MODEL" > "$OUT/link-slicer.json"
cat "$OUT/link-slicer.json"

RUN=("$OFFICIAL")
if [ -z "${DISPLAY:-}" ]; then RUN=(xvfb-run -a "$OFFICIAL"); fi
/usr/bin/time -f "official: %e s, %M KB peak" "${RUN[@]}" --slice 0 --arrange 1 --orient 0 --allow-rotations=0 --allow-newer-file=1 \
    --load-settings "$OUT/presets/printer.json;$OUT/presets/process.json" --load-filaments "$OUT/presets/filament_1.json" \
    --outputdir "$OUT/official" "$MODEL" > "$OUT/official.log" 2>&1 || { tail -20 "$OUT/official.log"; exit 1; }
python3 "$SLICER_ROOT/tools/compare_gcode.py" "$OUT/link-slicer.gcode" "$OUT/official/plate_1.gcode"
