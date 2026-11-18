#!/usr/bin/env bash
# SDK-only build: no Gradle plugins, online inference services or model downloads.
set -euo pipefail
cd "$(dirname "$0")"
sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
: "${sdk:?Set ANDROID_HOME to your Android SDK}"
bt="$sdk/build-tools/${BUILD_TOOLS_VERSION:-35.0.0}"
platform="$sdk/platforms/android-35/android.jar"
for tool in "$bt/aapt2" "$bt/d8" "$bt/zipalign" "$bt/apksigner" "$platform"; do
  test -f "$tool" || { echo "Missing Android SDK component: $tool" >&2; exit 1; }
done
npm ci --ignore-scripts --no-audit --no-fund
npm test
npm run build
work="$PWD/build/android"; rm -rf "$work"; mkdir -p "$work/classes" "$work/dex"
"$bt/aapt2" compile --dir android/res -o "$work/resources.zip"
"$bt/aapt2" link -I "$platform" --manifest android/AndroidManifest.xml -A build/web -o "$work/base.apk" "$work/resources.zip"
find android/java -name '*.java' > "$work/sources.txt"
javac -encoding UTF-8 -source 8 -target 8 -bootclasspath "$platform" -d "$work/classes" @"$work/sources.txt"
jar cf "$work/classes.jar" -C "$work/classes" .
"$bt/d8" --min-api 28 --lib "$platform" --output "$work/dex" "$work/classes.jar"
(cd "$work/dex" && zip -q -0 "$work/base.apk" classes.dex)
"$bt/zipalign" -f 4 "$work/base.apk" "$work/aligned.apk"
key="$HOME/.android/orbit-debug.p12"
if [ ! -f "$key" ]; then
  mkdir -p "$(dirname "$key")"
  (umask 077; keytool -genkeypair -alias orbit-debug -keystore "$key" -storepass android -keypass android -keyalg RSA -keysize 3072 -validity 3650 -dname 'CN=Orbit Local Debug')
fi
out="$PWD/build/Orbit-Workstation.apk"
"$bt/apksigner" sign --ks "$key" --ks-pass pass:android --out "$out" "$work/aligned.apk"
"$bt/apksigner" verify --verbose "$out"
echo "Installable debug build: $out"
