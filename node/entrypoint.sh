#!/bin/bash
# The recheck sandbox's ENTRYPOINT. Runs once, writes exactly one output file, exits. Nothing
# here is a long-lived daemon and nothing here registers with GitHub in any way — this container
# never talks to the GitHub API at all; it only (a) reads the three files the controller
# bind-mounted read-only at /input, and (b) talks to the extension sites under test, through the
# egress proxy (see node/proxy/ and node/compose.yml) on the only network this container is
# attached to.
#
# Inputs (read-only, bind-mounted by the controller before `docker run`):
#   /input/recheck.json       the manifest: keiCommit, runId, extension id list, bundleSha256/
#                              bundleSize/bundleUrl, harnessRuntimeCommit — fetched and validated
#                              ONCE by the controller; this container trusts exactly those bytes,
#                              it never re-fetches recheck.json or the bundle itself.
#   /input/bundle.tar.zst     harness/ci/pack-recheck-bundle.sh's output for that subset, already
#                              downloaded by the controller from recheck.json's bundleUrl and
#                              verified there against bundleSha256/bundleSize before it was even
#                              written to /input.
#
# Output: the one result JSON, printed to this container's own stdout between RESULT_BEGIN/
# RESULT_END marker lines — nothing else is a writable mount this container can grow without
# bound (see node/compose.yml: no /output volume at all). controller.py runs this container with
# `--log-driver json-file --log-opt max-size=512k --log-opt max-file=1`, so the stdout the
# controller can ever read back (via `docker logs`) is itself bounded at the Docker daemon level,
# independent of whatever this script does; it then extracts the marked region and enforces
# MAX_PAYLOAD_BYTES on top of that. `--ulimit fsize` (also set by controller.py) bounds any single
# file this process writes under /work (already a size-capped tmpfs) as a second, independent cap.
set -euo pipefail
HERE=$(cd "$(dirname "$(readlink -f "$0")")" && pwd)

RECHECK_JSON=/input/recheck.json
BUNDLE=/input/bundle.tar.zst

[ -f "$RECHECK_JSON" ] || { echo "no $RECHECK_JSON mounted" >&2; exit 2; }
[ -f "$BUNDLE" ] || { echo "no $BUNDLE mounted" >&2; exit 2; }

RUN_ID=$(python3 -c "import json; print(json.load(open('$RECHECK_JSON'))['runId'])")
BUNDLE_SHA=$(python3 -c "import json; print(json.load(open('$RECHECK_JSON'))['bundleSha256'])")
RUNTIME_COMMIT=$(python3 -c "import json; print(json.load(open('$RECHECK_JSON'))['harnessRuntimeCommit'])")
python3 -c "import json; [print(e) for e in json.load(open('$RECHECK_JSON'))['extensions']]" > /work/shard.txt

echo "[entrypoint] run $RUN_ID: $(wc -l < /work/shard.txt) extension(s) to recheck"

# the harness runtime is baked into THIS image (/app/runtime-dist), never shipped inside the
# bundle. Refuse to run if the sweep that produced this recheck was built against a different
# runtime commit than the one this image carries — better a loud, honest failure than silently
# running a possibly-incompatible extensions-lib/network-stack implementation.
IMAGE_RUNTIME_COMMIT=$(cat /app/RUNTIME_COMMIT 2>/dev/null || echo "")
if [ -z "$IMAGE_RUNTIME_COMMIT" ] || [ "$IMAGE_RUNTIME_COMMIT" != "$RUNTIME_COMMIT" ]; then
  echo "[entrypoint] harness runtime mismatch: this image was built for commit ${IMAGE_RUNTIME_COMMIT:-<unset>}, recheck.json expects $RUNTIME_COMMIT — update the node image (see node/README.md)" >&2
  exit 3
fi

# Extracts under /work/bundle (this image's filesystem is --read-only except the tmpfs mounts at
# /work, /home and /tmp — see node/compose.yml — so even a validated-but-unexpected member path
# outside that fixed root still can't be written anywhere real).
mkdir -p /work/bundle
python3 "$HERE/extract_bundle.py" "$BUNDLE" "$BUNDLE_SHA" /work/bundle

# The extensions-source checkout is wherever pack-recheck-bundle.sh found it on the GitHub-hosted
# build job (baked into classpath.txt as absolute paths, rewritten below) — found here by
# contents, not assumed, so this script doesn't hardcode the build job's workspace layout.
CPFILE=$(find /work/bundle -path '*/build/ext-run/classpath.txt' -print -quit 2>/dev/null)
WT=$(echo "$CPFILE" | sed -E 's#/src/[^/]+/[^/]+/build/ext-run/classpath\.txt$##')
DIST=/app/runtime-dist
[ -d "$WT/src" ] || { echo "[entrypoint] couldn't locate the extracted extensions-source checkout" >&2; exit 1; }
[ -d "$DIST" ] || { echo "[entrypoint] no baked-in runtime dist at $DIST" >&2; exit 1; }

export EXT_RUN_PREBUILT=1
export EXT_RUN_QUIET=1          # never print raw failure text to the container's own stdout/log
export EXT_RUN_JAVA_BIN=/opt/jdk21/bin/java
export EXT_RUN_JAVA_EXTRA_OPTS="-Xmx768m -Djava.awt.headless=true ${JAVA_PROXY_OPTS:-}"
export EXT_RUN_WV_PROXY=${EXT_RUN_WV_PROXY:-}   # e.g. http://proxy:3128 — see compose.yml
export EXT_RUN_WV_CHROME_SANDBOX=${EXT_RUN_WV_CHROME_SANDBOX:-0}   # the controller sets 1 when chrome_sandbox is on
export TZ=${EXT_RUN_TZ:-UTC}

mkdir -p /work/out
"$HERE/harness/ci/run-shard.sh" "$WT" "$DIST" /work/shard.txt /work/out

python3 "$HERE/categorize.py" /work/out/results.tsv /work/out/json "$RUN_ID" "$(date -u +%F)" /work/out/result.json

# Printed between fixed marker lines so the controller can find exactly this JSON in a `docker
# logs` capture that also carries this script's own diagnostic echo lines above — never parsed
# as "the first/last line of output", which an unrelated log line could shift.
echo "===KEI-RESULT-BEGIN==="
cat /work/out/result.json
echo
echo "===KEI-RESULT-END==="
echo "[entrypoint] done"
