#!/bin/bash
# Extracts a build-all.sh archive via the validating extractor (harness/ci/extract_build.py) —
# never `tar --absolute-names -C /`. The archive's members are all relative ("wt/...",
# "gradle/..."); this script just picks where "wt/" and "gradle/" land on THIS job (the
# extensions-source checkout passed in, and this job's own $GRADLE_USER_HOME/caches) and lets the
# python extractor validate every member path and rewrite classpath.txt onto those roots.
#   extract-build.sh <archive.tar.zst> <extensions-source-checkout-for-sanity-check>
set -euo pipefail
ARCHIVE=$(realpath "$1"); WT=$(realpath "$2")
HERE=$(cd "$(dirname "$(readlink -f "$0")")" && pwd)
GRADLE_CACHES=$(realpath -m "${GRADLE_USER_HOME:-$HOME/.gradle}/caches")

python3 "$HERE/extract_build.py" "$ARCHIVE" "$WT" "$GRADLE_CACHES"
[ -d "$WT/src" ] || echo "[extract-build] warning: $WT doesn't look like the extensions-source checkout the archive expects" >&2
