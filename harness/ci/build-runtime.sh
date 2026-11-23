#!/bin/bash
# Builds the harness's JVM runtime (the stand-in for the app) into a dist/ dir of jars.
#   build-runtime.sh <runtime-dir> <dist-out-dir>
set -euo pipefail
RUNTIME=$(realpath "$1"); DIST=$(realpath -m "$2")
cd "$RUNTIME"
if [ -x ./gradlew ]; then GRADLE=./gradlew; else GRADLE=gradle; fi
# Maven Central sometimes answers GitHub's runners with 429; with the Actions cache down there is
# no cached copy to fall back on, so retry a couple of times before failing the run.
for attempt in 1 2 3; do
  "$GRADLE" -q --console=plain --no-daemon dist && break
  [ "$attempt" = 3 ] && exit 1
  echo "[build-runtime] gradle failed (attempt $attempt), retrying in $((attempt * 90))s" >&2
  sleep $((attempt * 90))
done
mkdir -p "$DIST"
cp -f build/dist/*.jar "$DIST"/
echo "[build-runtime] wrote $(ls "$DIST" | wc -l) jars to $DIST" >&2
