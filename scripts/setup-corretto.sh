#!/usr/bin/env bash
# Downloads (resumable) and extracts Amazon Corretto 11 for Windows x64, then
# prints the exact gradle.properties line to pin it — see
# docs/adr/0006-jdk11-toolchain-downgrade.md for why JDK 11 specifically, and
# handoff.md §4a for why Corretto (not Temurin) is the AWS Build It artifact.
#
# This pin is deliberately machine-local, NOT committed to this repo — see
# that ADR's own note. Run this once per machine.
set -euo pipefail

JDK_DIR="/c/JDKs"
ZIP="$JDK_DIR/corretto11.zip"
URL="https://corretto.aws/downloads/latest/amazon-corretto-11-x64-windows-jdk.zip"

mkdir -p "$JDK_DIR"

echo "Downloading Amazon Corretto 11 (resumable)..."
curl -L --retry 20 --retry-delay 5 --retry-all-errors -C - -o "$ZIP" "$URL"

echo "Extracting..."
cd "$JDK_DIR"
unzip -q -o corretto11.zip
EXTRACTED_DIR=$(find "$JDK_DIR" -maxdepth 1 -iname "jdk11*" -type d | head -1)

if [ -z "$EXTRACTED_DIR" ]; then
  echo "Could not find extracted JDK directory under $JDK_DIR" >&2
  exit 1
fi

WIN_PATH=$(echo "$EXTRACTED_DIR" | sed 's#^/c#C:#' | sed 's#/#\\\\#g')

echo
echo "Corretto 11 extracted to: $EXTRACTED_DIR"
echo
echo "Add this line to your OWN user-level ~/.gradle/gradle.properties"
echo "(NOT the repo's gradle.properties — see docs/adr/0006):"
echo
echo "  org.gradle.java.home=$WIN_PATH"
echo
echo "Then verify with:"
echo "  cd \"D:\\Amazon AWS hack\" && ./gradlew -version"
echo "and confirm the JVM line shows 'Corretto', not another vendor."
