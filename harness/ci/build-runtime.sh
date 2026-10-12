#!/bin/bash
# Builds the harness's JVM runtime (the stand-in for the app) into a dist/ dir of jars.
#   build-runtime.sh <runtime-dir> <dist-out-dir>
set -euo pipefail
RUNTIME=$(realpath "$1"); DIST=$(realpath -m "$2")
cd "$RUNTIME"
if [ -x ./gradlew ]; then GRADLE=./gradlew; else GRADLE=gradle; fi
"$GRADLE" -q --console=plain --no-daemon dist
mkdir -p "$DIST"
cp -f build/dist/*.jar "$DIST"/
echo "[build-runtime] wrote $(ls "$DIST" | wc -l) jars to $DIST" >&2
