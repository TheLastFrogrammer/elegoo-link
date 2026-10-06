#!/usr/bin/env bash
# Builds the dependencies the slicing engine needs from ElegooSlicer's own pinned recipes (same versions and patches
# the desktop app ships), without its GUI-only ones. Output: $DEPS_PREFIX.
set -euo pipefail
source "$(dirname "$0")/env.sh"
"$(dirname "$0")/fetch-elegooslicer.sh"
cmake -S "$SLICER_ROOT/deps" -B "$DEPS_BUILD" -G Ninja -DCMAKE_BUILD_TYPE=Release \
    -DELEGOOSLICER_SRC="$ELEGOOSLICER_SRC" -DDEP_DOWNLOAD_DIR="$SLICER_WORK/downloads" \
    -DDEP_GIT_ARCHIVES="${DEP_GIT_ARCHIVES:-OFF}"
cmake --build "$DEPS_BUILD" --target deps
echo "Dependencies installed in $DEPS_PREFIX"
