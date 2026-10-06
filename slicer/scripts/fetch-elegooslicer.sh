#!/usr/bin/env bash
# Clones ElegooSlicer at the pinned commit into $ELEGOOSLICER_SRC (skipped when it already exists).
set -euo pipefail
source "$(dirname "$0")/env.sh"
if [ -d "$ELEGOOSLICER_SRC/.git" ] || [ -f "$ELEGOOSLICER_SRC/version.inc" ]; then
    echo "Using ElegooSlicer source at $ELEGOOSLICER_SRC"; exit 0
fi
mkdir -p "$ELEGOOSLICER_SRC"
git -C "$ELEGOOSLICER_SRC" init -q
git -C "$ELEGOOSLICER_SRC" remote add origin "$ELEGOOSLICER_REPO"
git -C "$ELEGOOSLICER_SRC" fetch -q --depth 1 origin "$ELEGOOSLICER_COMMIT"
git -C "$ELEGOOSLICER_SRC" checkout -q FETCH_HEAD
echo "Fetched ElegooSlicer $ELEGOOSLICER_COMMIT"
