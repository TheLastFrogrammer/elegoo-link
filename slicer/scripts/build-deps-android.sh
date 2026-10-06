#!/usr/bin/env bash
# Cross-compiles the engine's dependencies for Android with the NDK, from the same ElegooSlicer recipes as the
# Linux build. Output: $DEPS_ANDROID_PREFIX. Needs ANDROID_SDK (or ANDROID_HOME) with the NDK installed.
set -euo pipefail
source "$(dirname "$0")/env.sh"
"$(dirname "$0")/fetch-elegooslicer.sh"
[ -f "$ANDROID_NDK/build/cmake/android.toolchain.cmake" ] || { echo "No NDK at $ANDROID_NDK" >&2; exit 1; }
# Autotools builds (GMP, MPFR) pick these up from the environment; the CMake builds use the toolchain file's.
LLVM="$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
export AR="$LLVM/llvm-ar" RANLIB="$LLVM/llvm-ranlib" NM="$LLVM/llvm-nm" STRIP="$LLVM/llvm-strip"
cmake -S "$SLICER_ROOT/deps" -B "$DEPS_ANDROID_BUILD" -G Ninja -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ANDROID_ABI" -DANDROID_PLATFORM="android-$ANDROID_API" -DANDROID_STL=c++_static \
    -DELEGOOSLICER_SRC="$ELEGOOSLICER_SRC" -DDEP_DOWNLOAD_DIR="$SLICER_WORK/downloads" \
    -DDEP_GIT_ARCHIVES="${DEP_GIT_ARCHIVES:-OFF}"
cmake --build "$DEPS_ANDROID_BUILD" --target ${DEPS_TARGET:-deps} -- ${NINJA_ARGS:-}
echo "Android dependencies installed in $DEPS_ANDROID_PREFIX"
