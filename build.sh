#!/usr/bin/env bash
# Builds libwater_mod.so for arm64-v8a and packs dist/water_mod/ (+ .levipack zip).
#   ANDROID_NDK_HOME=/path/to/ndk ./build.sh            (optional: PRELOADER_SDK_DIR=/path/to/sdk)
set -euo pipefail
cd "$(dirname "$0")"

: "${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME to your Android NDK folder}"
API="${ANDROID_PLATFORM:-android-26}"

extra=()
[[ -n "${PRELOADER_SDK_DIR:-}" ]] && extra+=("-DPRELOADER_SDK_DIR=${PRELOADER_SDK_DIR}")

cmake -S . -B build \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM="$API" \
  -DCMAKE_BUILD_TYPE=Release -DCMAKE_CXX_FLAGS="-include cstdlib -include cstdio -include cstring" "${extra[@]}"
cmake --build build -j"$(nproc 2>/dev/null || echo 4)"

# The folder name is the runtime mod id - keep it stable.
rm -rf dist && mkdir -p dist/water_mod
cp build/libwater_mod.so manifest.json dist/water_mod/
( cd dist && zip -qr water_mod.levipack water_mod )
echo
echo "Done:"
echo "  dist/water_mod/            (unpacked mod folder)"
echo "  dist/water_mod.levipack    (zip of that folder, for import in LeviLauncher)"
