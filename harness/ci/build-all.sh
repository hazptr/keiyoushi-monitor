#!/bin/bash
# Build the harness's classpath (compiled classes + manifest) for every extension in a list, in
# one Gradle invocation, so later shard jobs never run Gradle at all. Run once in the `build` job.
#
#   build-all.sh <extensions-source-checkout> <ext-list-file> <archive-out.tar.zst>
#
# <ext-list-file>: one "<lang>/<name>" per line (blank lines/comments skipped), or the single word
# ALL to build every 1.6 extension in the checkout.
set -euo pipefail
WT=$(realpath "$1"); LIST=$2; ARCHIVE=$(realpath -m "$3")
HERE=$(cd "$(dirname "$(readlink -f "$0")")" && pwd)
GRADLE_CACHES=$(realpath -m "${GRADLE_USER_HOME:-$HOME/.gradle}/caches")

if [ "$LIST" = ALL ]; then
  TASKS=(extRunClasspath)
  PROJECT_ARG=ALL
else
  mapfile -t EXTS < <(grep -vE '^\s*(#|$)' "$LIST")
  TASKS=()
  for ext in "${EXTS[@]}"; do
    lang=${ext%%/*}; name=${ext##*/}
    TASKS+=(":src:$lang:$name:extRunClasspath")
  done
  PROJECT_ARG=ALL  # the init script's per-project gate only matters for ALL vs one exact project;
                   # explicit task selectors below already restrict Gradle to just these projects.
fi

echo "[build-all] building ${#TASKS[@]} extRunClasspath task(s)" >&2
JAVA_HOME=${EXT_RUN_GRADLE_JAVA_HOME:-$JAVA_HOME} "$WT/gradlew" --console=plain --no-daemon -p "$WT" \
  -I "$HERE/../ext-run.init.gradle.kts" -PextRun.project="$PROJECT_ARG" -x spotlessCheck "${TASKS[@]}"

# Package exactly what run-smoke.sh will need on the classpath: each extension's own build dir
# (manifest + compiled classes) AND every entry in its classpath.txt. Those entries resolve
# :core/:lib*/lib-multisrc project classes AND third-party AAR-as-jar transforms out of Gradle's
# dependency cache (GRADLE_USER_HOME, e.g. ~/.gradle) — neither lives under the extensions-source
# checkout, so a plain "tar the build/ dirs under $WT" misses them.
#
# Every path added below is WHITELISTED to live under
# either $WT or $GRADLE_USER_HOME/caches — anything else (e.g. a classpath.txt entry some Gradle
# plugin wrote outside both, a stray /tmp path) is dropped with a warning, never archived. Nothing
# is ever packed as an absolute path: paths are staged into a relative "wt/"/"gradle/" tree first,
# so `tar` itself never sees (and so never needs `--absolute-names` to round-trip) an absolute
# member name. See harness/ci/extract_build.py for the matching validating extractor.
mapfile -t CPFILES < <(find "$WT" -path '*/build/ext-run/classpath.txt')
[ "${#CPFILES[@]}" -gt 0 ] || { echo "[build-all] no classpath.txt files were produced; did the gradle build above fail silently?" >&2; exit 1; }

declare -A SEEN
WT_PATHS=()
GRADLE_PATHS=()
add_path() {
  local p=$1
  [ -e "$p" ] || return 0
  [ -n "${SEEN[$p]:-}" ] && return 0
  SEEN[$p]=1
  case "$p" in
    "$WT"/*|"$WT") WT_PATHS+=("$p") ;;
    "$GRADLE_CACHES"/*|"$GRADLE_CACHES") GRADLE_PATHS+=("$p") ;;
    *) echo "[build-all] dropping out-of-whitelist path (not under the worktree or gradle caches): $p" >&2 ;;
  esac
}
for cpf in "${CPFILES[@]}"; do
  pdir=${cpf%/build/ext-run/classpath.txt}
  add_path "$pdir/build"   # manifest, classpath.txt itself, compiled classes for this project
  while IFS= read -r entry; do
    add_path "$(realpath -m "$entry")"
  done < "$cpf"
  # Asset dirs: same rule run-smoke.sh uses (any module on the classpath with its own assets/).
  while IFS= read -r line; do
    moddir=${line%%/build/*}
    [ -d "$moddir/assets" ] && add_path "$moddir"
  done < <(printf '%s\n' "$pdir/build/x"; cat "$cpf")
done

command -v zstd >/dev/null 2>&1 || sudo apt-get install -y -qq zstd >/dev/null
mkdir -p "$(dirname "$ARCHIVE")"
STAGE=$(mktemp -d)
trap 'rm -rf "$STAGE"' EXIT
mkdir -p "$STAGE/wt" "$STAGE/gradle"
for p in "${WT_PATHS[@]}"; do
  rel=${p#"$WT"/}
  mkdir -p "$(dirname "$STAGE/wt/$rel")"
  cp -al "$p" "$STAGE/wt/$rel" 2>/dev/null || cp -a "$p" "$STAGE/wt/$rel"
done
for p in "${GRADLE_PATHS[@]}"; do
  rel=${p#"$GRADLE_CACHES"/}
  mkdir -p "$(dirname "$STAGE/gradle/$rel")"
  cp -al "$p" "$STAGE/gradle/$rel" 2>/dev/null || cp -a "$p" "$STAGE/gradle/$rel"
done
python3 -c "
import json, sys
json.dump({'wt': sys.argv[1], 'gradle_caches': sys.argv[2]}, open(sys.argv[3] + '/MANIFEST.json', 'w'))
" "$WT" "$GRADLE_CACHES" "$STAGE"

tar -C "$STAGE" -I 'zstd -T0 -3' -cf "$ARCHIVE" wt gradle MANIFEST.json
echo "[build-all] archived ${#WT_PATHS[@]} worktree + ${#GRADLE_PATHS[@]} gradle-cache path(s) from ${#CPFILES[@]} extension(s) -> $ARCHIVE ($(du -h "$ARCHIVE" | cut -f1))" >&2
