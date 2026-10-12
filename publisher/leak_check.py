#!/usr/bin/env python3
"""Shared scrubbing/leak-check primitives used by the `publish` job (scrub whatever lands in
status.json) and as a backstop for anything a recheck node reports (see node/categorize.py,
which turns a raw smoke result into a category-only row before it ever leaves the sandbox — this
module exists for the GitHub-hosted side's free text and as a last line of defense, not as the
thing that makes node output safe)."""
import re
import subprocess
import sys
import urllib.request

IPV4 = re.compile(r"\b(?:\d{1,3}\.){3}\d{1,3}\b")
# Full and compressed forms ("2601:abc::1"); needs "::" or 3+ groups, so clock times like 14:39:12
# don't match. Not inside a word (CSS "::after"), and at least one digit.
IPV6 = re.compile(r"(?<![\w:])(?=[0-9a-f:]*\d)(?=[0-9a-f:]*::|(?:[0-9a-f]{1,4}:){3})(?:[0-9a-f]{0,4}:){2,7}[0-9a-f]{0,4}(?![\w:])", re.I)
# Cloudflare ray IDs ("8f2a9c3d4e5f6071-SJC", any case), plus a bare 16-hex ray id with no
# trailing "-XXX" (some error paths print just the hex half), plus "colo=" / "loc=" query-style
# params that name a specific edge datacenter or geolocation hint.
CF_RAY = re.compile(r"\b[0-9a-f]{16}-[a-z]{3}\b", re.I)
CF_RAY_BARE = re.compile(r"(?<![0-9a-f])[0-9a-f]{16}(?![0-9a-f])", re.I)
CF_COLO = re.compile(r"\b(?:colo|loc)=[a-z0-9_-]+", re.I)
PATHISH = re.compile(r"(/home|/mnt|/tmp|/root|/Users|/var|/opt|C:\\Users)[/\\]\S*", re.I)
URL_QUERY = re.compile(r"(https?://[^\s?]+)\?[^\s]*")
# Hex and decimal dword encodings of an IPv4 address some tools/logs use instead of dotted-quad
# (e.g. "0x4909960B", or "ip=1225602827"). The decimal form is only flagged in an obvious
# "ip=NNNNNNNNNN" / "addr=NNNNNNNNNN"-shaped context so it doesn't false-positive on ordinary
# large numbers (byte counts, timestamps).
IPV4_HEX = re.compile(r"\b0x[0-9a-f]{8}\b", re.I)
IPV4_DEC = re.compile(r"\b(?:ip|addr|address)[=:]\s*\d{8,10}\b", re.I)
# Reverse-DNS hostnames that encode a residential WAN IP in dashed form, e.g.
# "c-198-51-100-7.hsd1.example.net" or "pool-198-51-100-8.example.net" — these identify
# a connection even though they aren't themselves IPv4-shaped.
DASHED_IP_HOST = re.compile(r"\b[a-z]*-?\d{1,3}-\d{1,3}-\d{1,3}-\d{1,3}\.[\w-]+(?:\.[\w-]+)+\b", re.I)
# ISO-8601 UTC-offset suffixes on a timestamp ("...12:03:00+09:00" / "...12:03:00+0900") can hint
# at a node's timezone/region; scrub the offset itself, leaving the timestamp.
ISO_TZ_OFFSET = re.compile(r"(T\d{2}:\d{2}:\d{2}(?:\.\d+)?)[+-]\d{2}:?\d{2}\b")


def runner_public_ips() -> list:
    """Looked up at call time, never printed by the caller. Returns [] if unreachable (caller
    decides whether that's fatal)."""
    ips = []
    for url in ("https://api.ipify.org", "https://api64.ipify.org"):
        try:
            ips.append(urllib.request.urlopen(url, timeout=10).read().decode().strip())
        except OSError:
            pass
    try:
        lan = subprocess.run(["hostname", "-I"], text=True, capture_output=True, timeout=5).stdout.split()
    except Exception:
        lan = []
    return sorted(set(ip for ip in ips + lan if ip))


def scrub_text(s: str) -> str:
    """Best-effort redaction for text that will be published. Not a substitute for leak_check()."""
    if not s:
        return s
    s = IPV4.sub("[ip]", s)
    s = IPV6.sub("[ip]", s)
    s = IPV4_HEX.sub("[ip]", s)
    s = IPV4_DEC.sub("[ip]", s)
    s = DASHED_IP_HOST.sub("[host]", s)
    s = CF_RAY.sub("[ray]", s)
    s = CF_RAY_BARE.sub("[ray]", s)
    s = CF_COLO.sub("[colo]", s)
    s = ISO_TZ_OFFSET.sub(r"\1[tz]", s)
    s = URL_QUERY.sub(r"\1", s)
    s = PATHISH.sub("[path]", s)
    return s


def scrub_json(obj):
    """Recursively scrub every string leaf in a JSON-shaped value. Structure-preserving — call
    this on something already narrowed to a known-safe set of fields (see filter_smoke_json),
    not on a raw blob, since scrubbing only redacts IP/ray/path *shapes* and does nothing about
    fields that simply shouldn't be published at all (request logs, headers, bodies)."""
    if isinstance(obj, str):
        return scrub_text(obj)
    if isinstance(obj, list):
        return [scrub_json(v) for v in obj]
    if isinstance(obj, dict):
        return {k: scrub_json(v) for k, v in obj.items()}
    return obj


# The only fields anything downstream (publisher/build_status.py's operations()/notes()/entry())
# ever reads out of a smoke JSON. Everything else — request logs, raw stdout, any field a future
# harness change might add — is dropped here rather than merely redacted, so a reviewer doesn't
# have to trust a regex to catch it.
_STEP_FIELDS = ("step", "status", "ok", "error", "reason")


def filter_smoke_json(smoke: dict | None) -> dict | None:
    if not smoke:
        return smoke
    steps = []
    for st in smoke.get("steps", []):
        if not isinstance(st, dict):
            continue
        s = {k: st[k] for k in _STEP_FIELDS if k in st}
        detail = st.get("detail") or {}
        if st.get("step") in ("details", "manga") and detail.get("title"):
            s["detail"] = {"title": detail["title"]}
        elif st.get("step") == "chapters" and "chapterCount" in detail:
            s["detail"] = {"chapterCount": detail["chapterCount"]}  # notes()'s undated-chapters check
        steps.append(s)
    return {"steps": steps, "warnings": list(smoke.get("warnings", []))}


def scrub_smoke_json(smoke: dict | None) -> dict | None:
    """filter_smoke_json then scrub_json — the one call site should ever need for a smoke result
    that's about to be written to disk/artifact."""
    return scrub_json(filter_smoke_json(smoke))


def leak_check(root, extra_needles: list | None = None) -> list:
    """Walk every file under `root` and flag anything that looks like it identifies a machine or
    network: the caller's own public IPs (from runner_public_ips(), passed in by the caller so
    it's looked up once and never printed here either), any IP-looking string, Cloudflare
    ray/colo codes, or an extra list of literal strings the caller wants banned (e.g. the repo's
    own absolute checkout path). Returns a list of human-readable hit descriptions; empty means
    clean. The caller decides what "clean" means for it (dry-run vs fail-closed).

    Skips anything that isn't valid UTF-8 text — a published opaque binary artifact (the recheck
    bundle: compiled extension bytecode, same content upstream already publishes) is full of
    byte sequences that incidentally match an IP/ray-shaped regex without being one; this check
    exists for text we or a node produced, not for text-scanning arbitrary binary blobs."""
    hits = []
    needles = [n for n in (extra_needles or []) if n]
    for f in root.rglob("*"):
        if not f.is_file() or ".git" in f.parts:
            continue
        try:
            text = f.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            continue
        except OSError:
            continue
        f = f.relative_to(root)
        # Never interpolate the matched text itself into the hit message — these messages may
        # end up in a public job log, and the whole point is not to put IPs/ray codes there.
        for ip in needles:
            if ip in text:
                hits.append(f"{f}: contains a banned string")
        if IPV4.search(text):
            hits.append(f"{f}: IPv4-like string")
        if IPV6.search(text):
            hits.append(f"{f}: IPv6-like string")
        if IPV4_HEX.search(text) or IPV4_DEC.search(text):
            hits.append(f"{f}: hex/decimal-encoded IPv4-like string")
        if DASHED_IP_HOST.search(text):
            hits.append(f"{f}: dashed-IP reverse-DNS-style hostname")
        if CF_RAY.search(text) or CF_RAY_BARE.search(text):
            hits.append(f"{f}: Cloudflare ray code")
        if CF_COLO.search(text):
            hits.append(f"{f}: Cloudflare colo/loc code")
        if ISO_TZ_OFFSET.search(text):
            hits.append(f"{f}: ISO timezone offset")
        if PATHISH.search(text):
            hits.append(f"{f}: absolute filesystem path")
    return hits


def main():
    """CLI: leak_check.py <dir> [extra-needle ...] — exits 1 and prints hits (never the matched
    text itself, just file + kind) if anything is found."""
    import pathlib
    if len(sys.argv) < 2:
        sys.exit("usage: leak_check.py <dir> [extra-needle ...]")
    root = pathlib.Path(sys.argv[1])
    ips = runner_public_ips()
    hits = leak_check(root, extra_needles=ips + sys.argv[2:])
    if hits:
        print("LEAK CHECK FAILED; refusing to upload:", file=sys.stderr)
        for h in sorted(set(hits)):
            print(f"  {h}", file=sys.stderr)
        sys.exit(1)
    print(f"leak check clean: {root}")


if __name__ == "__main__":
    main()
