#!/usr/bin/env python3
"""The one schema a recheck result is allowed to have. Imported by:
  - publisher/categorize.py, in each sweep shard's recheck, to build its output file;
  - harness/ci/recheck.py, to validate a shard's combined result before it is uploaded;
  - publisher/merge_recheck.py, in build-site, to re-validate every shard's file before merging.

A recheck row is never free text. It is exactly: an extension id that was actually on the day's
recheck list, a category from CATEGORIES, optionally the one fixed warning tag, on a pass the
two-letter country of the proxy it passed through, each step's status word from OP_STATUSES, and nothing
else — no error strings, titles, URLs, or timestamps beyond the run date. This is the fix for a
real leak found in testing: a free-text recheck error message once revealed wording from a page
on the node's own network, unrelated to the extension being checked. Categories carry no detail
beyond themselves.
"""
import datetime as dt
import re

# step name, only present when category == "fail" — the op that failed, not why.
STEPS = ("browse", "latest", "search", "details", "chapters", "read", "pages")

# A step's status word, as build_status.operations() gives it.
OP_STATUSES = ("pass", "fail", "maybe", "untestable", "skip", "na")

CATEGORIES = ("pass", "fail", "blocked", "cloudflare", "timeout", "connect", "error")

WARNING_TAGS = ("cloudflare checkbox clicked",)

MAX_EXTENSIONS_DEFAULT = 300
MAX_PAYLOAD_BYTES = 256 * 1024  # generous ceiling for a 300-row category-only payload
# keiyoushi/extensions-source's actual src/<lang>/<name> shape: lang is "all", a 2-3 letter
# ISO-ish code, or one of those with a region suffix (e.g. "zh-Hans", "pt-BR"); name is lowercase
# alphanumeric, optionally hyphen-segmented. No dots at all — this id is later used to build
# filesystem paths (pack-recheck-bundle.sh, extract_bundle.py), so this also has to double as a
# path-traversal guard: no "..", no leading/trailing/doubled separators, nothing but
# [A-Za-z0-9-] in either segment.
EXT_ID_RE = re.compile(r"^(?:all|[a-z]{2,3}(?:-[A-Za-z]+)?)/[a-z0-9]+(?:-[a-z0-9]+)*$")
RUN_ID_RE = re.compile(r"^[0-9]{1,20}$")
DATE_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
# sha256:<64 hex> — how every image reference in this pipeline is pinned.


class SchemaError(ValueError):
    pass


def _fail(msg):
    raise SchemaError(msg)


CC_RE = re.compile(r"^[A-Z]{2}$")


def validate_row(row: dict, allowed_ids: set) -> dict:
    """One extension's result. Raises SchemaError on anything not exactly matching the schema.
    Returns the row, normalized to only its allowed keys (belt-and-suspenders: callers should
    already only have built it this way, but ingest treats this as the authority, not a hint)."""
    if not isinstance(row, dict):
        _fail("row is not an object")
    extra = set(row) - {"id", "category", "step", "warning", "cc", "ops"}
    if extra:
        _fail(f"row has disallowed field(s): {sorted(extra)}")
    ext = row.get("id")
    if not isinstance(ext, str) or not EXT_ID_RE.match(ext):
        _fail(f"row id {ext!r} is not a well-formed lang/name extension id")
    if ext not in allowed_ids:
        _fail(f"row id {ext!r} is not on the day's recheck list")
    cat = row.get("category")
    if cat not in CATEGORIES:
        _fail(f"row category {cat!r} is not one of {CATEGORIES}")
    step = row.get("step")
    if cat == "fail":
        if step not in STEPS:
            _fail(f"category 'fail' needs a 'step' field in {STEPS}, got {step!r}")
    elif step is not None:
        _fail(f"category {cat!r} must not carry a 'step' field")
    warning = row.get("warning")
    if warning is not None and warning not in WARNING_TAGS:
        _fail(f"warning {warning!r} is not one of {WARNING_TAGS}")
    cc = row.get("cc")
    if cc is not None and (cat != "pass" or not isinstance(cc, str) or not CC_RE.match(cc)):
        _fail(f"cc {cc!r} must be a two-letter country code, and only on a pass")
    ops = row.get("ops")
    if ops is not None:
        if not isinstance(ops, list) or len(ops) > len(STEPS):
            _fail("ops is not a list of at most one entry per step")
        for o in ops:
            if not isinstance(o, dict) or set(o) != {"op", "status"}:
                _fail("an ops entry is not exactly {op, status}")
            if o["op"] not in STEPS or o["status"] not in OP_STATUSES:
                _fail(f"ops entry {o!r} has an unknown step or status")
        if len({o["op"] for o in ops}) != len(ops):
            _fail("ops lists a step twice")
    out = {"id": ext, "category": cat}
    if ops:
        out["ops"] = [{"op": o["op"], "status": o["status"]} for o in ops]
    if cc is not None:
        out["cc"] = cc
    if step is not None:
        out["step"] = step
    if warning is not None:
        out["warning"] = warning
    return out


def validate_node_payload(payload: dict, allowed_ids: set, run_date: str | None = None) -> dict:
    """The whole single-output-file schema a sandbox run produces and the controller forwards:
        {"runId": "...", "runDate": "YYYY-MM-DD", "results": [row, ...]}
    `allowed_ids` is the exact id list this recheck was given —
    anything not on it is rejected outright, so a compromised/buggy sandbox can't report on an
    extension it was never asked to check. Returns a normalized copy; never trusts the input
    object's own shape beyond what's checked here."""
    if not isinstance(payload, dict):
        _fail("payload is not an object")
    extra = set(payload) - {"runId", "runDate", "results"}
    if extra:
        _fail(f"payload has disallowed field(s): {sorted(extra)}")
    run_id = payload.get("runId")
    if not isinstance(run_id, str) or not RUN_ID_RE.match(run_id):
        _fail(f"runId {run_id!r} is not a plain numeric GitHub run id")
    run_date_val = payload.get("runDate")
    if not isinstance(run_date_val, str) or not DATE_RE.match(run_date_val):
        _fail(f"runDate {run_date_val!r} is not YYYY-MM-DD")
    try:
        dt.date.fromisoformat(run_date_val)
    except ValueError:
        _fail(f"runDate {run_date_val!r} is not a real calendar date")
    if run_date is not None and run_date_val != run_date:
        _fail(f"runDate {run_date_val!r} does not match the expected run date {run_date!r}")
    results = payload.get("results")
    if not isinstance(results, list):
        _fail("results is not a list")
    if len(results) > MAX_EXTENSIONS_DEFAULT:
        _fail(f"results has {len(results)} rows, more than the {MAX_EXTENSIONS_DEFAULT} cap")
    seen = set()
    out_rows = []
    for row in results:
        norm = validate_row(row, allowed_ids)
        if norm["id"] in seen:
            _fail(f"duplicate row for id {norm['id']!r}")
        seen.add(norm["id"])
        out_rows.append(norm)
    return {"runId": run_id, "runDate": run_date_val, "results": out_rows}
