#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
: "${ANDROID_PLATFORM_JAR:?Set ANDROID_PLATFORM_JAR to Android API 35 android.jar}"
: "${ANDROID_BUILD_TOOLS:?Set ANDROID_BUILD_TOOLS to the Android 35 build-tools directory}"
tools="$ANDROID_BUILD_TOOLS"
output="${1:-build/SonLive.apk}"
temp="$(mktemp -d)"
trap 'rm -rf "$temp"' EXIT
mkdir -p "$temp/classes" "$temp/dex" "$(dirname "$output")"
mapfile -t sources < <(find app/src/main/java -name '*.java')
javac --release 8 -cp "$ANDROID_PLATFORM_JAR" -d "$temp/classes" "${sources[@]}"
jar cf "$temp/classes.jar" -C "$temp/classes" .
"$tools/d8" --min-api 29 --lib "$ANDROID_PLATFORM_JAR" --output "$temp/dex" "$temp/classes.jar"
"$tools/aapt2" link -I "$ANDROID_PLATFORM_JAR" --manifest app/src/main/AndroidManifest.xml \
  --min-sdk-version 29 --target-sdk-version 35 --version-code 1 --version-name 0.1.0 \
  -A app/src/main/assets -o "$temp/raw.apk"
(cd "$temp/dex" && zip -q "$temp/raw.apk" classes.dex)
"$tools/zipalign" -p -f 4 "$temp/raw.apk" "$temp/aligned.apk"
signing_key="${SONLIVE_KEYSTORE:-$PWD/.sonlive-development.jks}"
if [[ -n "${SONLIVE_KEYSTORE:-}" ]]; then
  "$tools/apksigner" sign --ks "$signing_key" --out "$output" "$temp/aligned.apk"
else
  if [[ ! -f "$signing_key" ]]; then
    keytool -genkeypair -keystore "$signing_key" -storepass android -keypass android -alias sonlive \
      -dname "CN=SonLive Development" -keyalg RSA -keysize 2048 -validity 10000
  fi
  "$tools/apksigner" sign --ks "$signing_key" --ks-pass pass:android --out "$output" "$temp/aligned.apk"
fi
"$tools/apksigner" verify --verbose "$output"
