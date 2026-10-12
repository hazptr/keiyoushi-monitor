#!/usr/bin/env python3
"""Cheap, dependency-free tests for the scrub/leak-check path. Run in CI (the `selftest` job) on
every trigger, before anything expensive runs. Exercises the exact failure mode found during
testing: a benign IP-shaped placeholder must scrub clean and NOT fail the batch, while a real
leak (the runner's own IP, or a raw Cloudflare ray ID) must still fail closed.

  test_scrub.py            — exits 1 and prints which assertion failed, else prints "ok" and exits 0
"""
import json
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from leak_check import leak_check, scrub_json, scrub_smoke_json, scrub_text  # noqa: E402

FAILURES = []


def check(name, cond):
    if cond:
        print(f"ok: {name}")
    else:
        FAILURES.append(name)
        print(f"FAIL: {name}")


# 1. The organic false-positive hit during real testing: Java's IPv6 "any-address" literal in a
# plain ConnectException. Must scrub to something with no IP-like string left in it.
msg = "java.io.IOException: Failed to connect to pagerrentablespotlight.com/[0:0:0:0:0:0:0:0]:443"
scrubbed = scrub_text(msg)
check("benign [0:0:0:0:0:0:0:0] placeholder is scrubbed", "0:0:0:0:0:0:0:0" not in scrubbed and "[ip]" in scrubbed)

# 2. A smoke JSON carrying that message, run through the same path the recheck job uses
# (filter+scrub), then leak-checked on disk: must pass (nothing left to flag) -> doesn't fail
# closed over a benign placeholder.
smoke_benign = {"steps": [{"step": "browse", "status": "fail", "ok": False, "error": msg}], "warnings": []}
with tempfile.TemporaryDirectory() as tmp:
    root = Path(tmp)
    (root / "en_benign.json").write_text(json.dumps(scrub_smoke_json(smoke_benign)))
    hits = leak_check(root)
    check("scrubbed benign placeholder passes leak_check (no false-closed failure)", hits == [])

# 3. A real runner IP injected into a result: scrub_text alone WILL redact it too (it's IP-shaped
# by construction), so this proves the defense-in-depth angle instead — an IP that somehow
# reaches disk unscrubbed (e.g. a future bug that bypasses scrub_smoke_json) must still be caught
# by leak_check's own IPv4 pattern before upload.
with tempfile.TemporaryDirectory() as tmp:
    root = Path(tmp)
    (root / "leak.txt").write_text("unscrubbed debug line mentioning 203.0.113.42 directly")
    hits = leak_check(root, extra_needles=["203.0.113.42"])
    check("an IP that bypassed scrubbing still fails leak_check closed", len(hits) > 0)
    check("the hit message never echoes the IP itself", all("203.0.113.42" not in h for h in hits))

# 4. A raw Cloudflare ray/colo ID must scrub to [ray], and if one somehow isn't scrubbed, it must
# still fail leak_check.
ray_msg = "cf-mitigated: challenge; ray id 8f2a9c3d4e5f6071-SJC"
check("a ray ID is scrubbed to [ray]", "[ray]" in scrub_text(ray_msg) and "8f2a9c3d4e5f6071-SJC" not in scrub_text(ray_msg))
with tempfile.TemporaryDirectory() as tmp:
    root = Path(tmp)
    (root / "unscrubbed_ray.txt").write_text(ray_msg)  # deliberately not scrubbed, to test the backstop
    hits = leak_check(root)
    check("an unscrubbed ray ID still fails leak_check closed", len(hits) > 0)

# 5. filter_smoke_json whitelist: a field nothing downstream reads (e.g. a hypothetical future
# "requestLog") must not survive scrub_smoke_json at all, scrubbed or not.
smoke_with_extra = {"steps": [{"step": "browse", "status": "pass", "ok": True}],
                     "warnings": [], "requestLog": ["GET https://example.com/?token=secret"]}
out = scrub_smoke_json(smoke_with_extra)
check("fields outside the whitelist are dropped, not just scrubbed", "requestLog" not in out)

# 6. scrub_json is structure-preserving on nested dict/list values.
nested = {"a": ["has 10.0.0.5 in it", {"b": "clean"}]}
check("scrub_json recurses into nested lists/dicts", scrub_json(nested)["a"][0] == "has [ip] in it" and scrub_json(nested)["a"][1]["b"] == "clean")

# 7. re.I on cf-ray: an upper-case-prefixed ray id must still scrub.
check("CF-RAY style text scrubs case-insensitively", "[ray]" in scrub_text("CF-RAY: 8F2A9C3D4E5F6071-SJC"))

# 8. A bare 16-hex ray id with no trailing "-XXX" still scrubs.
bare = "edge error, ray 8f2a9c3d4e5f6071 while fetching"
check("a bare 16-hex ray id (no -XXX) is scrubbed", "8f2a9c3d4e5f6071" not in scrub_text(bare))

# 9. colo= / loc= query-style hints are scrubbed.
check("colo= is scrubbed", "[colo]" in scrub_text("cf-ray 1234567890abcdef-SJC colo=SJC"))
check("loc= is scrubbed", "[colo]" in scrub_text("challenge loc=US rejected"))

# 10. A dashed-IP reverse-DNS hostname (ISP rDNS naming convention) is scrubbed even though it
# isn't IPv4-shaped itself.
for host in ("c-198-51-100-7.hsd1.example.net", "pool-198-51-100-8.example.net"):
    check(f"dashed-IP hostname {host} is scrubbed", host not in scrub_text(f"connect failed to {host}"))

# 11. Hex and decimal IPv4 encodings are scrubbed.
check("hex-encoded IPv4 is scrubbed", "0x4909960b" not in scrub_text("resolved 0x4909960B").lower())
check("decimal-encoded IPv4 (ip=... context) is scrubbed", "1225602827" not in scrub_text("ip=1225602827 refused"))

# 12. ISO timezone offsets are scrubbed, independent of the timestamp itself.
check("ISO tz offset is scrubbed", "[tz]" in scrub_text("ran at 2026-10-11T12:03:00+09:00") and "+09:00" not in scrub_text("ran at 2026-10-11T12:03:00+09:00"))

# 13. Broader absolute-path prefixes are scrubbed.
for p in (r"/Users/someone/project", r"/var/log/app.log", r"/opt/app/run.sh", r"C:\Users\someone\file.txt"):
    check(f"path {p} is scrubbed", "[path]" in scrub_text(f"failed reading {p}"))

# 14. leak_check skips non-UTF-8 binary files (an opaque published artifact like the recheck
# bundle is full of byte sequences that incidentally look IP/ray-shaped without being one).
with tempfile.TemporaryDirectory() as tmp:
    root = Path(tmp)
    (root / "binary.bin").write_bytes(bytes(range(256)) * 4)
    check("a binary file is skipped, not false-flagged", leak_check(root) == [])

if FAILURES:
    print(f"\n{len(FAILURES)} failure(s): {FAILURES}", file=sys.stderr)
    sys.exit(1)
print("\nok: all scrub/leak-check tests passed")
