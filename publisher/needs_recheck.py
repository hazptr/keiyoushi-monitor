#!/usr/bin/env python3
"""classify job: read merged results.tsv, decide which failures look IP/network-shaped rather
than extension-shaped, and write the list of extensions worth a recheck node's attention.

  needs_recheck.py <results.tsv> <out-file> [max]

The BLOCKISH pattern: a failure that could be about the caller's IP (Cloudflare, rate limits,
connect/TLS/timeouts) is worth retrying from a different network; anything else will fail the
same way everywhere, so there's no point asking a recheck node to try it too.

Every id this script writes out eventually becomes a path component on the node side
(pack-recheck-bundle.sh, extract_bundle.py) and a GitHub Actions workflow_dispatch input, so it's
validated against the same EXT_ID_RE node_schema.py and pack-recheck-bundle.sh use — a malformed
or path-traversal-shaped id from a corrupted/edited results.tsv is dropped here rather than
carried forward.
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from node_schema import EXT_ID_RE  # noqa: E402

BLOCKISH = __import__("re").compile(
    r"HTTP error (403|429|451|5\d\d)\b|cloudflare|challenge|bypass|turnstile|cf-mitigated|"
    r"timed? ?out|timeout|connect(ion)?(exception| refused| reset)|failed to connect|"
    r"unknownhost|could not resolve|ssl|handshake|unexpected end of stream|\beof\b",
    __import__("re").I,
)


def main():
    tsv, out, *rest = sys.argv[1:]
    cap = int(rest[0]) if rest else 300
    lines = open(tsv).read().splitlines()[1:]
    wanted = []
    for line in lines:
        cols = line.split("\t")
        if len(cols) < 4:
            continue
        ext, status, _secs, summary = cols[0], cols[1], cols[2], cols[3]
        if status in ("fail", "error") and BLOCKISH.search(summary):
            if not EXT_ID_RE.match(ext) or ".." in ext:
                print(f"needs-recheck: dropping malformed id {ext!r}", file=sys.stderr)
                continue
            wanted.append(ext)
    wanted = wanted[:cap]
    with open(out, "w") as f:
        f.write("\n".join(wanted) + ("\n" if wanted else ""))
    print(f"needs-recheck: {len(wanted)} of {len(lines)} rows (cap {cap})")


if __name__ == "__main__":
    main()
