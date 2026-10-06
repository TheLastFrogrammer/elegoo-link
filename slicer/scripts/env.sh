# Shared settings for the headless ElegooSlicer engine build. Source this file.
# ElegooSlicer is fetched at a pinned commit; override ELEGOOSLICER_SRC to use another checkout.
SLICER_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ELEGOOSLICER_REPO="${ELEGOOSLICER_REPO:-https://github.com/ELEGOO-3D/elegooslicer.git}"
ELEGOOSLICER_COMMIT="${ELEGOOSLICER_COMMIT:-2d507e39a96ab9562b35d06b23ef5df8fe613297}"
SLICER_WORK="${SLICER_WORK:-$SLICER_ROOT/work}"
ELEGOOSLICER_SRC="${ELEGOOSLICER_SRC:-$SLICER_WORK/elegooslicer}"
DEPS_BUILD="${DEPS_BUILD:-$SLICER_WORK/deps-linux}"
DEPS_PREFIX="$DEPS_BUILD/ElegooSlicer_dep/usr/local"
JOBS="${JOBS:-$(nproc)}"
