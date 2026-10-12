#!/bin/bash
# Runs smoke for every extension in a shard file, one retry after a short delay on failure (same
# idea as the daily sweep this prototypes). Writes per-extension JSON + logs and a results.tsv.
#
#   run-shard.sh <extensions-source-checkout> <runtime-dist-dir> <shard-file> <out-dir>
set -uo pipefail
WT=$(realpath "$1"); DIST=$(realpath "$2"); SHARD_FILE=$3; OUT=$4
HERE=$(cd "$(dirname "$(readlink -f "$0")")" && pwd)
mkdir -p "$OUT/json" "$OUT/logs"
TSV="$OUT/results.tsv"
printf 'ext\tstatus\tsecs\tsummary\tvia\n' >"$TSV"

WV_SOCK=$OUT/webview.sock
# Diagnostics only: EXT_RUN_WV_CF_DEBUG=1 in the environment (set it by hand, e.g. for a local
# debug run — no status.yml input wires this up today) saves Turnstile click screenshots next to
# the other per-shard output, so they ride along in the same artifact.
if [ "${EXT_RUN_WV_CF_DEBUG:-}" = "1" ]; then
  export EXT_RUN_WV_CF_DEBUG_DIR="$OUT/cf-debug"
  mkdir -p "$EXT_RUN_WV_CF_DEBUG_DIR"
fi
"$HERE/start-webview.sh" "$WV_SOCK"

has_real_result() {
  # A result worth retrying on: the harness actually ran against the network and produced its
  # one-line JSON, win or lose. No JSON at all (exit 127 command-not-found, a missing
  # classpath.txt, the extension not existing, a crash before MainKt even starts) is a tooling
  # problem that a 60s wait will not fix — recorded as `error` immediately instead.
  [ -s "$1" ] && python3 -c "import json,sys
try:
    json.load(open(sys.argv[1]))
except Exception:
    sys.exit(1)" "$1" 2>/dev/null
}

while IFS= read -r ext; do
  [ -z "$ext" ] && continue
  safe=${ext//\//_}
  json="$OUT/json/$safe.json"
  log="$OUT/logs/$safe.log"
  t0=$(date +%s)
  "$HERE/run-smoke.sh" "$WT" "$ext" "$DIST" "$WV_SOCK" "$json" >"$log" 2>&1
  rc=$?
  if [ $rc -ne 0 ]; then
    if has_real_result "$json"; then
      echo "[run-shard] $ext failed (exit $rc) with a result; retrying in 60s" >>"$log"
      sleep 60
      t0=$(date +%s) # time the retry, like the attempt that counts
      "$HERE/run-smoke.sh" "$WT" "$ext" "$DIST" "$WV_SOCK" "$json" >>"$log" 2>&1
      rc=$?
    else
      echo "[run-shard] $ext failed (exit $rc), no JSON result; not a site failure, not retrying" >>"$log"
    fi
  fi
  secs=$(( $(date +%s) - t0 ))
  err_tail=$(tail -3 "$log" 2>/dev/null | tr '\n' ' ')
  python3 "$HERE/make_row.py" "$ext" "$secs" "${EXT_RUN_VIA:-gha}" "$json" "$err_tail" >>"$TSV"
  if [ "${EXT_RUN_QUIET:-}" = "1" ]; then
    # recheck job: the row's summary can carry raw exception text (URLs, hostnames); never put
    # that in the job console, which is public. Status-only line, nothing else.
    echo "[run-shard] $ext -> $(cut -f2 <<<"$(tail -1 "$TSV")")"
  else
    echo "[run-shard] $ext -> $(tail -1 "$TSV")"
  fi
done <"$SHARD_FILE"

echo "[run-shard] done: $(wc -l <"$SHARD_FILE") extensions"
