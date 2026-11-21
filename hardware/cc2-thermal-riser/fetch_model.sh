#!/usr/bin/env bash
# Rebuilds the CC2 model the placement study and verify.py use, from the OpenCentauri community CAD (GPL-3.0, not kept in
# this repository): downloads it, keeps only the parts that matter (frame and panels, door, CANVAS, the bed assembly,
# the gantry and the head), and meshes them into build/cc2mesh/{static,bed,gantry,head,lid}.stl.
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p build && cd build
if [ ! -d opencentauri-cad ]; then GIT_LFS_SKIP_SMUDGE=1 git clone --depth 1 https://github.com/OpenCentauri/cad opencentauri-cad; fi
[ -f CC2_CANVAS_step.step ] || unzip -o -q opencentauri-cad/CC2_CANVAS_step.zip
python ../placement/keep_step.py CC2_CANVAS_step.step study.step "0[0-9]{3}.*" "rod mounts" "frame" "CC_door" "CC_z_azis v12" "XY_Assm" "挤出齿外壳" "散热片导风件" "CANVAS" "CC屏幕" "cc2 tophat"
mkdir -p cc2mesh && python ../placement/occ_export.py study.step cc2mesh
