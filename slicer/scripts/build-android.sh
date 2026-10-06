#!/usr/bin/env bash
# Cross-compiles libslic3r, the engine facade and link-slicer for Android against $DEPS_ANDROID_PREFIX.
# link-slicer is a normal Android executable (Termux, adb shell); link-slicer-static is a fully static copy that
# also runs under qemu-aarch64 on a PC.
set -euo pipefail
source "$(dirname "$0")/env.sh"
BUILD="${BUILD:-$SLICER_WORK/build-android-$ANDROID_ABI}"
cmake -S "$SLICER_ROOT" -B "$BUILD" -G Ninja -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ANDROID_ABI" -DANDROID_PLATFORM="android-$ANDROID_API" -DANDROID_STL=c++_static \
    -DCMAKE_FIND_ROOT_PATH="$DEPS_ANDROID_PREFIX" -DCMAKE_PREFIX_PATH="$DEPS_ANDROID_PREFIX" \
    -DOpenCV_DIR="$DEPS_ANDROID_PREFIX/sdk/native/jni" \
    -DELEGOOSLICER_SRC="$ELEGOOSLICER_SRC" -DLINK_SLICER_STATIC_EXE=ON
cmake --build "$BUILD" --target link-slicer link-slicer-static -j "$JOBS"
echo "Built $BUILD/link-slicer"
