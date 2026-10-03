#!/bin/sh
# Installs what a fresh machine (or a fresh cloud session) needs to build
# the app: the Android command-line tools, the SDK platform, build-tools and
# the NDK, the Rust Android targets and cargo-ndk; then writes
# local.properties. Versions come from gradle/libs.versions.toml, so this
# script never drifts from the build. Safe to run again.
#
#   ANDROID_HOME=~/Android/Sdk scripts/setup-android-sdk.sh   (Android Studio's SDK)
#   ANDROID_HOME=/opt/android-sdk scripts/setup-android-sdk.sh (a bare machine)
set -e
cd "$(dirname "$0")/.."

: "${ANDROID_HOME:=/opt/android-sdk}"
case "$(uname -s)" in
  Darwin) CMDLINE_TOOLS_ZIP=commandlinetools-mac-11076708_latest.zip ;;
  *) CMDLINE_TOOLS_ZIP=commandlinetools-linux-11076708_latest.zip ;;
esac

catalog() { sed -n "s/^$1 = \"\(.*\)\"/\1/p" gradle/libs.versions.toml; }
NDK=$(catalog ndk)
COMPILE_SDK=$(catalog compileSdk)
# The build-tools AGP picks by default; it downloads them itself otherwise.
BUILD_TOOLS=36.0.0

SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
if [ ! -x "$SDKMANAGER" ]; then
  echo "== Android command-line tools → $ANDROID_HOME"
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  tmp=$(mktemp -d)
  curl -sSL -o "$tmp/tools.zip" "https://dl.google.com/android/repository/$CMDLINE_TOOLS_ZIP"
  unzip -q "$tmp/tools.zip" -d "$tmp"
  rm -rf "$ANDROID_HOME/cmdline-tools/latest"
  mv "$tmp/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
  rm -rf "$tmp"
fi

echo "== SDK packages: android-$COMPILE_SDK, build-tools $BUILD_TOOLS, NDK $NDK"
yes | "$SDKMANAGER" --licenses >/dev/null 2>&1 || true
"$SDKMANAGER" "platform-tools" "platforms;android-$COMPILE_SDK.0" \
  "build-tools;$BUILD_TOOLS" "ndk;$NDK" >/dev/null

echo "== Rust: Android targets and cargo-ndk"
rustup target add aarch64-linux-android x86_64-linux-android
command -v cargo-ndk >/dev/null 2>&1 || cargo install cargo-ndk

echo "sdk.dir=$ANDROID_HOME" > local.properties
echo "== done: local.properties points at $ANDROID_HOME"
