#!/usr/bin/env bash
# Slices MODEL with link-slicer and with the official ElegooSlicer CLI using the same resolved presets, then
# compares the G-code. OFFICIAL is the official binary (for example an extracted AppImage's AppRun); on a machine
# without a display it is run under xvfb-run. The official CLI auto-orients STL files and lets arrange rotate parts
# by default; both are turned off to match the desktop app's defaults, which link-slicer follows.
#
#   OFFICIAL=/path/to/squashfs-root/AppRun scripts/compare-official.sh MODEL [PRINTER PROCESS FILAMENT]
#
# Several filaments: FILAMENT lists the presets separated by ";", COLOURS the slot colours ("#D02828;#F0F0F0") and
# MODELS more model files (space-separated) with ASSIGN giving every model's slot ("1,2"), for example
#   COLOURS="#D02828;#F0F0F0" MODELS="b.stl" ASSIGN=1,2 scripts/compare-official.sh a.stl "" "" "Elegoo PLA @ECC2;Elegoo PLA @ECC2"
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
SELECT=(--resources "$ELEGOOSLICER_SRC/resources" --printer "$PRINTER" --process "$PROCESS")
IFS=';' read -ra FILAMENTS <<< "$FILAMENT"
for f in "${FILAMENTS[@]}"; do SELECT+=(--filament "$f"); done
IFS=';' read -ra SLOT_COLOURS <<< "${COLOURS:-}"
for c in "${SLOT_COLOURS[@]}"; do SELECT+=(--colour "$c"); done
INPUTS=("$MODEL"); for m in ${MODELS:-}; do INPUTS+=("$(realpath "$m")"); done
ASSIGN_ARGS=(); OFFICIAL_ASSIGN=()
if [ -n "${ASSIGN:-}" ]; then ASSIGN_ARGS=(--assign "$ASSIGN"); OFFICIAL_ASSIGN=(--load-filament-ids "$ASSIGN"); fi
FILAMENT_FILES="$OUT/presets/filament_1.json"
for ((i = 2; i <= ${#FILAMENTS[@]}; i++)); do FILAMENT_FILES+=";$OUT/presets/filament_$i.json"; done

"$BUILD/link-slicer" "${SELECT[@]}" --export-presets "$OUT/presets" > /dev/null
/usr/bin/time -f "link-slicer: %e s, %M KB peak" "$BUILD/link-slicer" "${SELECT[@]}" "${ASSIGN_ARGS[@]}" --quiet --output "$OUT/link-slicer.gcode" "${INPUTS[@]}" > "$OUT/link-slicer.json"
cat "$OUT/link-slicer.json"

RUN=("$OFFICIAL")
if [ -z "${DISPLAY:-}" ]; then RUN=(xvfb-run -a "$OFFICIAL"); fi
/usr/bin/time -f "official: %e s, %M KB peak" "${RUN[@]}" --slice 0 --arrange 1 --orient 0 --allow-rotations=0 --allow-newer-file=1 \
    --load-settings "$OUT/presets/printer.json;$OUT/presets/process.json" --load-filaments "$FILAMENT_FILES" \
    "${OFFICIAL_ASSIGN[@]}" --outputdir "$OUT/official" "${INPUTS[@]}" > "$OUT/official.log" 2>&1 || { tail -20 "$OUT/official.log"; exit 1; }
python3 "$SLICER_ROOT/tools/compare_gcode.py" "$OUT/link-slicer.gcode" "$OUT/official/plate_1.gcode"
