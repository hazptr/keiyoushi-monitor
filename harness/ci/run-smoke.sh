#!/bin/bash
# Build one keiyoushi extension natively (JDK + Android SDK already on the runner) and run the
# harness's `smoke` check against it through the real Chrome WebView sidecar (start-webview.sh).
# GitHub-Actions-only: no Docker, no long-lived daemon container, no custom networking options.
#
#   run-smoke.sh <extensions-source-checkout> <lang>/<name> <runtime-dist-dir> <webview-socket> [out.json]
#
# Prints the harness's one-line JSON result to stdout (and to out.json if given); exits 0 if the
# extension's smoke run had no failing step, matching ext-run's convention.
set -euo pipefail
WT=$(realpath "$1"); SRC=$2; RJAR_DIR=$(realpath "$3"); WV_SOCK=$4; OUT=${5:-}
LANG_=${SRC%%/*}; NAME=${SRC##*/}
PROJ=":src:$LANG_:$NAME"; PDIR="$WT/src/$LANG_/$NAME"
HERE=$(cd "$(dirname "$(readlink -f "$0")")" && pwd)

[ -d "$PDIR" ] || { echo "no such extension: $PDIR" >&2; exit 2; }

CPFILE=$PDIR/build/ext-run/classpath.txt
if [ "${EXT_RUN_PREBUILT:-}" = "1" ]; then
  # The status pipeline's `build` job already ran extRunClasspath for every extension (via
  # build-all.sh) and shipped the resulting build/ output as an artifact, extracted at the same
  # workspace path. Nothing to build here — just use what's already on disk.
  [ -f "$CPFILE" ] || { echo "EXT_RUN_PREBUILT=1 but no $CPFILE; was the build artifact extracted?" >&2; exit 1; }
else
  echo "[run-smoke] building $PROJ" >&2
  # AGP needs JDK 17; the harness runtime jar below is built for JDK 21. Both JDKs are expected to be
  # on the runner (actions/setup-java with a list of versions); EXT_RUN_GRADLE_JAVA_HOME picks which
  # one Gradle itself runs on, independent of $JAVA_HOME / $PATH's java (used for the smoke run below).
  JAVA_HOME=${EXT_RUN_GRADLE_JAVA_HOME:-$JAVA_HOME} "$WT/gradlew" --console=plain --no-daemon -p "$WT" \
    -I "$HERE/../ext-run.init.gradle.kts" -PextRun.project="$PROJ" -x spotlessCheck "$PROJ:extRunClasspath"
fi
[ -f "$CPFILE" ] || { echo "no $CPFILE after build" >&2; exit 1; }

MANIFEST=$PDIR/build/generated/manifests/generateExtensionManifest/AndroidManifest.xml
CLASS=$(grep -o 'tachiyomi.extension.class" android:value="[^"]*' "$MANIFEST" | sed 's/.*value="//')
LIB=$(grep -o 'tachiyomix.extensionLib" android:value="[^"]*' "$MANIFEST" | sed 's/.*value="//' || true)
[ "$LIB" = 1.6 ] || { echo "[run-smoke] $SRC is libVersion '${LIB:-?}'; this harness implements the 1.6 API only" >&2; exit 2; }
[[ "$CLASS" == .* ]] && CLASS="eu.kanade.tachiyomi.extension.$LANG_.$NAME$CLASS"

# Android assets are read through the classloader as "assets/...": put every module dir that has
# one (the extension, its theme, lib/*) on the classpath so those lookups resolve.
ASSETS=$( { echo "$PDIR/build/x"; cat "$CPFILE"; } | sed 's|/build/.*||' | sort -u | while read -r d; do
  if [ -d "$d/assets" ]; then echo "$d"; fi
done | paste -sd: )
CP=$(paste -sd: "$CPFILE")${ASSETS:+:$ASSETS}:"$RJAR_DIR"/*

DATA=$(mktemp -d)
trap 'rm -rf "$DATA"' EXIT

# Diagnostics only: EXT_RUN_SMOKE_VERBOSE=1 adds -v (prints the CloudflareInterceptor's retry-vs-challenge diagnostics).
VERBOSE_FLAG=()
[ "${EXT_RUN_SMOKE_VERBOSE:-}" = "1" ] && VERBOSE_FLAG=(-v)

# EXT_RUN_JAVA_EXTRA_OPTS: extra JVM flags inserted before -cp, space-separated — e.g. a recheck
# node sets its heap cap (-Xmx...) and OkHttp's egress-proxy system properties
# (-Dhttps.proxyHost=... -Dhttps.proxyPort=...) here; OkHttpClient.Builder never calls .proxy(),
# so it falls back to the JVM's default ProxySelector, which honours these out of the box — no
# harness code change needed for the proxy itself.
read -ra EXTRA_OPTS <<<"${EXT_RUN_JAVA_EXTRA_OPTS:-}"

echo "[run-smoke] running $SRC (smoke, --direct, chrome WebView)" >&2
set +e
EXT_RUN_DATA="$DATA" EXT_RUN_WEBVIEW_SOCK="$WV_SOCK" \
  "${EXT_RUN_JAVA_BIN:-java}" -XX:+UnlockDiagnosticVMOptions -XX:-BytecodeVerificationRemote -XX:TieredStopAtLevel=1 -Xshare:auto \
  "${EXTRA_OPTS[@]}" \
  -cp "$CP" keiyoushi.extrun.MainKt --class "$CLASS" --direct --browser chrome --json smoke "${VERBOSE_FLAG[@]}" \
  >"${OUT:-/dev/stdout}" 2>"$DATA/stderr.log"
rc=$?
set -e
cat "$DATA/stderr.log" >&2
[ -n "$OUT" ] && cat "$OUT"
exit $rc
