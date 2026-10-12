#!/usr/bin/env python3
"""ingest workflow: merge a validated node payload into the currently-published status.json.

A node row only ever overrides the published entry for the categories that are themselves a
real verdict about the site — pass, fail, blocked, cloudflare. timeout/connect/error are the
node's OWN tooling/network having trouble, not a result about the extension, and must never
override a GitHub-hosted result (same rule the original recheckedOnRunner design used for
pass/fail; this just extends the accepted-verdict set to the two new categories a node can also
report cleanly). Every piece of text this writes is a fixed string keyed off the category and
(for "fail") the fixed step-name enum — never anything derived from the node's own raw output,
because there isn't any: node/categorize.py never puts raw text in a row in the first place.

  merge_node_result.py <status.json> <node-payload.json> <out.json>
"""
import datetime as dt
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from node_schema import CATEGORIES  # noqa: E402

OVERRIDE_CATEGORIES = {"pass", "fail", "blocked", "cloudflare"}

# Same short, plain phrasing style as the page's own OPS[op][1] strings (site/app.js) — kept as a
# small separate copy here since the publisher has no access to the frontend's OPS object.
STEP_TEXT = {
    "browse": "Can’t load its list of titles", "latest": "Latest updates don’t load",
    "search": "Search doesn’t work", "details": "Titles don’t open",
    "chapters": "Chapter lists don’t load", "read": "Chapters don’t open", "pages": "Pages don’t load",
}


def category_text(row: dict) -> str | None:
    cat = row["category"]
    if cat == "fail":
        return f"Also fails from our recheck node: {STEP_TEXT.get(row.get('step'), 'something in it is broken')}"
    if cat == "blocked":
        return "Also blocked from our recheck node"
    if cat == "cloudflare":
        return "Cloudflare still blocks our recheck node"
    return None


def merge(status_data: dict, node_payload: dict, run_date: str | None = None) -> dict:
    # Defense in depth: a node result is only ever valid against the run whose recheck.json it
    # was generated from. ingest.yml already checks the payload's runId against the published
    # recheck.json before calling this, but this function is the one place that actually writes
    # into status.json, so it re-checks against status.json's OWN runId (set by build_status.py
    # for the same run that built recheck.json) rather than trusting the caller. A MISSING
    # expected runId is treated exactly like a mismatch — a status.json this check can't actually
    # perform is not a reason to skip it and merge anyway — and both exit non-zero rather than
    # silently returning the input unchanged, so a caller that doesn't check this function's
    # return value separately still can't ship a no-op merge as if it had succeeded.
    expected_run = status_data.get("runId")
    payload_run = node_payload.get("runId")
    if not expected_run:
        sys.exit("refusing to merge: this status.json has no runId to check the node payload against")
    if payload_run != expected_run:
        sys.exit(f"refusing to merge: payload runId {payload_run!r} does not match this status.json's "
                  f"runId {expected_run!r} — stale or mismatched node result")
    by_id = {x["id"]: x for x in status_data.get("extensions", [])}
    applied = 0
    for row in node_payload.get("results", []):
        cat = row.get("category")
        if cat not in CATEGORIES:
            continue  # already schema-validated upstream; defensive only
        if cat not in OVERRIDE_CATEGORIES:
            continue
        x = by_id.get(row["id"])
        if x is None:
            continue  # not currently a published extension; nothing to attach this to
        # Every branch below clears any GitHub-side `step` first: the frontend uses the presence
        # of `step` (alongside recheckedOnNode) to tell "fails at a specific step" apart from
        # "blocked/cloudflare, no step" — a stale step left over from the GitHub-side result would
        # otherwise make a node's "blocked" verdict render as if it failed at that old step.
        x.pop("step", None)
        if cat == "pass":
            x["status"] = "pass"
            x.pop("error", None)
        elif cat == "fail":
            x["status"] = "fail"
            x["step"] = row["step"]
            x["error"] = category_text(row)
        elif cat == "blocked":
            x["status"] = "fail"
            x["error"] = category_text(row)
        elif cat == "cloudflare":
            x["status"] = "untestable"
            x["error"] = category_text(row)
        x["recheckedOnNode"] = True
        if run_date:
            x["recheckedOn"] = run_date
        if row.get("warning") == "cloudflare checkbox clicked":
            notes = x.setdefault("notes", [])
            tag = "Our recheck node had to click Cloudflare's verification checkbox"
            if tag not in notes:
                notes.append(tag)
        applied += 1
    # The summary counts (stacked bar, legend, headline %) are computed once at build time and
    # otherwise never touched again; a merge that changes any x["status"] must recompute them or
    # the page's own summary disagrees with the per-extension statuses it just applied.
    if applied:
        status_data["counts"] = {
            s: sum(x["status"] == s for x in status_data.get("extensions", []))
            for s in ("pass", "partial", "fail", "untestable", "unsupported", "pending")
        }
    status_data["generated"] = dt.datetime.now(dt.timezone.utc).isoformat(timespec="minutes")
    print(f"merged {applied} node row(s) of {len(node_payload.get('results', []))} into status.json")
    return status_data


def main():
    if len(sys.argv) != 4:
        sys.exit("usage: merge_node_result.py <status.json> <node-payload.json> <out.json>")
    status_path, payload_path, out_path = sys.argv[1:4]
    status_data = json.loads(Path(status_path).read_text())
    node_payload = json.loads(Path(payload_path).read_text())
    merged = merge(status_data, node_payload, run_date=node_payload.get("runDate"))
    Path(out_path).write_text(json.dumps(merged, indent=1, ensure_ascii=False))


if __name__ == "__main__":
    main()
