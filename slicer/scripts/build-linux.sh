#!/usr/bin/env bash
# Builds libslic3r, the Link Slicer engine facade and the link-slicer CLI against $DEPS_PREFIX.
set -euo pipefail
source "$(dirname "$0")/env.sh"
BUILD="${BUILD:-$SLICER_WORK/build-linux}"
cmake -S "$SLICER_ROOT" -B "$BUILD" -G Ninja -DCMAKE_BUILD_TYPE=Release \
    -DELEGOOSLICER_SRC="$ELEGOOSLICER_SRC" -DCMAKE_PREFIX_PATH="$DEPS_PREFIX"
cmake --build "$BUILD" --target link-slicer -j "$JOBS"
echo "Built $BUILD/link-slicer"
