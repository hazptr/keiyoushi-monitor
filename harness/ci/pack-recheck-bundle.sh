#!/bin/bash
# Repackage a SUBSET of an already-built extensions-build archive (build-all.sh's output,
# already extracted onto this runner by extract-build.sh) into a bundle a recheck node can
# extract safely: every path in the archive is RELATIVE (no `tar --absolute-names`), so
# extraction can be validated member-by-member (no absolute paths, no `..`) before anything
# touches disk — see node/extract_bundle.py, which is what actually extracts this on the node
# side. This script only runs on the GitHub-hosted `build-site` job, never inside the sandbox.
#
# The harness RUNTIME (dist/) is NOT packed here: it's baked into the node image itself
# (see .github/workflows/node-image.yml + node/Dockerfile), so this bundle only ever carries the
# recheck subset's extension classes + their dependency jars — much smaller, and the node always
# runs its own known-good runtime build rather than one shipped inside an untrusted-adjacent bundle.
#
#   pack-recheck-bundle.sh <extensions-source-checkout> <recheck-list-file> <out.tar.zst>
set -euo pipefail
WT=$(realpath "$1"); LIST=$2; ARCHIVE=$(realpath -m "$3")
GRADLE_CACHES=$(realpath -m "${GRADLE_USER_HOME:-$HOME/.gradle}/caches")

# Same id shape publisher/node_schema.py's EXT_ID_RE enforces (src/<lang>/<name>, no dots, no
# "..") — this id becomes a path component below ($WT/src/$lang/$name), so it's re-validated
# here too rather than trusted just because it came from a file this script was handed.
EXT_ID_RE='^(all|[a-z]{2,3}(-[A-Za-z]+)?)/[a-z0-9]+(-[a-z0-9]+)*$'

mapfile -t EXTS < <(grep -vE '^\s*(#|$)' "$LIST")
[ "${#EXTS[@]}" -gt 0 ] || { echo "[pack-recheck-bundle] empty recheck list, nothing to pack" >&2; exit 1; }

declare -A SEEN
PATHS=()
add_path() {
  local p=$1
  [ -e "$p" ] || return 0
  [ -n "${SEEN[$p]:-}" ] && return 0
  SEEN[$p]=1
  # Whitelisted to live under the worktree or the gradle caches, same rule build-all.sh applies
  # when it packs the main build archive — anything else (a classpath.txt entry some Gradle
  # plugin wrote outside both, a stray /tmp path) is dropped with a warning, never archived.
  case "$p" in
    "$WT"/*|"$WT"|"$GRADLE_CACHES"/*|"$GRADLE_CACHES") ;;
    *) echo "[pack-recheck-bundle] dropping out-of-whitelist path (not under the worktree or gradle caches): $p" >&2; return 0 ;;
  esac
  PATHS+=("$p")
}

for ext in "${EXTS[@]}"; do
  if [[ ! $ext =~ $EXT_ID_RE ]] || [[ $ext == *..* ]]; then
    echo "[pack-recheck-bundle] refusing malformed extension id: $ext" >&2
    exit 1
  fi
  lang=${ext%%/*}; name=${ext##*/}
  pdir="$WT/src/$lang/$name"
  cpf="$pdir/build/ext-run/classpath.txt"
  [ -f "$cpf" ] || { echo "[pack-recheck-bundle] no classpath.txt for $ext; was it in the main build's extension list?" >&2; exit 1; }
  add_path "$pdir/build"
  while IFS= read -r entry; do add_path "$(realpath -m "$entry")"; done < "$cpf"
  while IFS= read -r line; do
    moddir=${line%%/build/*}
    [ -d "$moddir/assets" ] && add_path "$moddir"
  done < <(printf '%s\n' "$pdir/build/x"; cat "$cpf")
done

mkdir -p "$(dirname "$ARCHIVE")"
command -v zstd >/dev/null 2>&1 || sudo apt-get install -y -qq zstd >/dev/null
# --transform strips the leading "/" from every member's name, so the archive has no absolute
# paths at all — a validated extractor can only ever write under whatever destination it chooses
# (see node/extract_bundle.py), never at a path this script picked.
tar --transform 's,^/,,' -I 'zstd -T0 -3' -cf "$ARCHIVE" "${PATHS[@]}"
echo "[pack-recheck-bundle] packed ${#PATHS[@]} path(s) from ${#EXTS[@]} extension(s) -> $ARCHIVE ($(du -h "$ARCHIVE" | cut -f1))" >&2
