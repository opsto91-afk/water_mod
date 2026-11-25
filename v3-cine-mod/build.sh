#!/usr/bin/env bash
# Builds libcinematic_mod.so for arm64-v8a and packs dist/cinematic_mod/ (+ .levipack zip).
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
  -DCMAKE_BUILD_TYPE=Release "${extra[@]}"
cmake --build build -j"$(nproc 2>/dev/null || echo 4)"

# The folder name is the runtime mod id - keep it stable.
rm -rf dist && mkdir -p dist/cinematic_mod
cp build/libcinematic_mod.so manifest.json dist/cinematic_mod/
# .levipack = flat zip: manifest.json + .so at the ROOT (same layout as other LeviLauncher plugins)
( cd dist/cinematic_mod && zip -qr ../cinematic_mod.levipack . )
echo
echo "Done:"
echo "  dist/cinematic_mod/            (unpacked mod folder)"
echo "  dist/cinematic_mod.levipack    (flat zip of that folder, for import in LeviLauncher)"
