#!/usr/bin/env python3
"""Turn one shard's raw results (same results.tsv + json/*.json shape run-shard.sh already
produces for the GitHub-hosted sweep) into the strict, category-only node payload — the only
thing that ever leaves the sandbox. Never reads error text into the output: a failing
extension's row is exactly {id, category, step?, warning?}, nothing else. This is what makes the
"never free text" rule structural rather than a scrub step trying to catch leaks after the fact.

    categorize.py <results.tsv> <json-dir> <run-id> <run-date> <out.json>

Exits non-zero (and writes nothing) if the inputs don't parse — better to produce no output than
a malformed one, since the controller treats "no output file" as a hard failure anyway.
"""
import json
import re
import sys
from pathlib import Path

# In the repo checkout this is ../publisher/node_schema.py; in the built node image (see
# node/Dockerfile) it's copied in at /app/publisher/node_schema.py alongside this script at
# /app/categorize.py.
_here = Path(__file__).resolve().parent
for _candidate in (_here.parent / "publisher", _here / "publisher"):
    if (_candidate / "node_schema.py").exists():
        sys.path.insert(0, str(_candidate))
        break
from node_schema import validate_node_payload  # noqa: E402

BLOCKED = re.compile(r"\b(?:40[13]|429)\b")
CLOUDFLARE = re.compile(r"cloudflare|turnstile|challenge|cf-mitigated", re.I)
TIMEOUT = re.compile(r"timed? ?out|timeout", re.I)
CONNECT = re.compile(
    r"connect(ion)?(exception| refused| reset)|failed to connect|unknownhost|"
    r"could not resolve|\bssl\b|handshake|unexpected end of stream|\beof\b",
    re.I,
)
LEGACY_STEPS = {"popular": "browse", "manga": "details", "pages": "read", "images": "pages"}


def categorize_one(status: str, summary: str, smoke: dict | None) -> dict:
    """status/summary are results.tsv columns (pass|fail|untestable|error); smoke is the raw
    per-extension JSON if one exists. Returns {category, step?}."""
    if status == "pass":
        return {"category": "pass"}
    text = summary or ""
    step = None
    if smoke:
        for st in smoke.get("steps", []):
            if st.get("status") == "fail":
                step = LEGACY_STEPS.get(st.get("step"), st.get("step"))
                text = f"{text} {st.get('error') or ''}"
                break
    if BLOCKED.search(text):
        return {"category": "blocked"}
    if CLOUDFLARE.search(text):
        return {"category": "cloudflare"}
    if TIMEOUT.search(text):
        return {"category": "timeout"}
    if CONNECT.search(text):
        return {"category": "connect"}
    if status == "fail" and step:
        return {"category": "fail", "step": step}
    # untestable / error / a fail whose step we couldn't pin down from the fixed enum.
    return {"category": "error"}


def warning_tag(smoke: dict | None):
    if smoke and "cloudflare checkbox clicked" in (smoke.get("warnings") or []):
        return "cloudflare checkbox clicked"
    return None


def main():
    if len(sys.argv) != 6:
        sys.exit("usage: categorize.py <results.tsv> <json-dir> <run-id> <run-date> <out.json>")
    tsv_path, json_dir, run_id, run_date, out_path = sys.argv[1:6]
    json_dir = Path(json_dir)

    rows = []
    allowed_ids = set()
    for line in Path(tsv_path).read_text().splitlines()[1:]:
        if not line.strip():
            continue
        cols = line.split("\t")
        if len(cols) < 2:
            continue
        ext, status = cols[0], cols[1]
        summary = cols[3] if len(cols) > 3 else ""
        allowed_ids.add(ext)
        smoke_file = json_dir / f"{ext.replace('/', '_')}.json"
        smoke = None
        if smoke_file.exists():
            try:
                smoke = json.loads(smoke_file.read_text())
            except json.JSONDecodeError:
                smoke = None
        cat = categorize_one(status, summary, smoke)
        row = {"id": ext, **cat}
        warn = warning_tag(smoke)
        if warn:
            row["warning"] = warn
        rows.append(row)

    payload = {"runId": str(run_id), "runDate": run_date, "results": rows}
    # Validate against the same schema the controller and ingest workflow enforce, so a bug here
    # fails loudly inside the sandbox instead of producing an output the controller then rejects
    # (or worse, a future validator bug lets slip through).
    validated = validate_node_payload(payload, allowed_ids, run_date=run_date)
    Path(out_path).write_text(json.dumps(validated))
    print(f"wrote {out_path}: {len(validated['results'])} row(s)")


if __name__ == "__main__":
    main()
