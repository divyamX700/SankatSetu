#!/usr/bin/env bash
# Downloads the Android SDK command-line tools from scratch and installs
# exactly what this project needs to build: platform 34, build-tools
# 34.0.0, platform-tools (adb), and the NDK (needed later for the Cedar
# cross-compile — see docs/adr/0017-cedar-cross-compile.md). Writes
# local.properties (gitignored, machine-specific) pointing at it.
set -euo pipefail

SDK_ROOT="/c/Android/sdk"
ZIP="$SDK_ROOT/cmdline-tools.zip"
URL="https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

mkdir -p "$SDK_ROOT"

echo "Downloading Android SDK command-line tools (resumable)..."
curl -L --retry 20 --retry-delay 5 --retry-all-errors -C - -o "$ZIP" "$URL"

echo "Extracting..."
cd "$SDK_ROOT"
unzip -q -o cmdline-tools.zip

# The zip extracts to cmdline-tools/ but sdkmanager expects
# cmdline-tools/latest/ specifically.
mkdir -p "$SDK_ROOT/cmdline-tools/latest"
find "$SDK_ROOT/cmdline-tools" -maxdepth 1 -mindepth 1 ! -name latest -exec mv {} "$SDK_ROOT/cmdline-tools/latest/" \;

SDKMANAGER="$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager.bat"

echo "Accepting licenses..."
yes | "$SDKMANAGER" --sdk_root="$SDK_ROOT" --licenses > /dev/null || true

echo "Installing platform-tools, platform 34, build-tools 34.0.0, NDK..."
"$SDKMANAGER" --sdk_root="$SDK_ROOT" \
  "platform-tools" \
  "platforms;android-34" \
  "build-tools;34.0.0" \
  "ndk;26.1.10909125"

WIN_SDK_ROOT=$(echo "$SDK_ROOT" | sed 's#^/c#C:#' | sed 's#/#\\\\#g')

cat > "$REPO_ROOT/local.properties" <<EOF
sdk.dir=$WIN_SDK_ROOT
EOF

echo
echo "Wrote $REPO_ROOT/local.properties pointing at $WIN_SDK_ROOT"
echo "NDK installed at: $SDK_ROOT/ndk/26.1.10909125 (for scripts/build-cedar-ffi.sh)"
echo
echo "Verify adb works:"
echo "  \"$SDK_ROOT/platform-tools/adb.exe\" devices"
