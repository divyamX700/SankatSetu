#!/usr/bin/env bash
# Cross-compiles cedar-java's native FFI crate for Android and drops the
# resulting .so files into app/src/main/jniLibs/ — see
# docs/adr/0017-cedar-cross-compile.md for the full research/reasoning
# behind every step here (verified against the real cedar-java source, not
# guessed). Requires: rustup with a working `stable` toolchain, and the
# Android SDK/NDK from scripts/setup-android-sdk.sh already installed.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK_DIR="/c/Tools/cedar-java-src"
JNI_LIBS_DIR="$REPO_ROOT/app/src/main/jniLibs"
NDK_DIR="/c/Android/sdk/ndk/26.1.10909125"

if [ ! -d "$NDK_DIR" ]; then
  echo "NDK not found at $NDK_DIR — run scripts/setup-android-sdk.sh first." >&2
  exit 1
fi
export ANDROID_NDK_HOME="$NDK_DIR"

echo "Ensuring rustup has a default toolchain..."
rustc --version >/dev/null 2>&1 || rustup default stable

echo "Adding Android targets..."
rustup target add aarch64-linux-android armv7-linux-androideabi

echo "Installing cargo-ndk (compiles locally, no large download)..."
cargo install cargo-ndk --locked || true

echo "Cloning cedar-policy/cedar-java..."
mkdir -p "$(dirname "$WORK_DIR")"
if [ ! -d "$WORK_DIR" ]; then
  git clone --depth 1 https://github.com/cedar-policy/cedar-java.git "$WORK_DIR"
fi

echo "Building libcedar_java_ffi.so for arm64-v8a and armeabi-v7a..."
mkdir -p "$JNI_LIBS_DIR"
cd "$WORK_DIR/CedarJavaFFI"
cargo ndk -t arm64-v8a -t armeabi-v7a -o "$JNI_LIBS_DIR" build --release

echo
echo "Done. Verify the output:"
find "$JNI_LIBS_DIR" -iname "libcedar_java_ffi.so"
echo
echo "These .so files are meant to be committed (not gitignored) — they're"
echo "small (a few MB) and required to ship, same treatment as the small"
echo "MiniLM/knowledge-base assets, unlike the large gitignored LLM model."
