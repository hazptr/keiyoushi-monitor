#!/usr/bin/env python3
"""The one schema a recheck node's output is allowed to have, and the one schema the controller
sends on to the `ingest` workflow. Imported by:
  - node/categorize.py, running INSIDE the sandbox, to build its single output file;
  - node/controller.py, running on the host OUTSIDE the sandbox, to validate that file strictly
    before it ever leaves the machine;
  - the `ingest` workflow's own validator (same file, read from the repo checkout), as the
    authoritative, don't-trust-the-client check.

A node row is never free text. It is exactly: an extension id that was actually on the current
recheck.json list, a category from CATEGORIES, optionally the one fixed warning tag, and nothing
else — no error strings, titles, URLs, or timestamps beyond the run date. This is the fix for a
real leak found in testing: a free-text recheck error message once revealed wording from a page
on the node's own network, unrelated to the extension being checked. Categories carry no detail
beyond themselves.
"""
import datetime as dt
import re

# step name, only present when category == "fail" — the op that failed, not why.
STEPS = ("browse", "latest", "search", "details", "chapters", "read", "pages")

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
DIGEST_RE = re.compile(r"^sha256:[0-9a-f]{64}$")
SHA_RE = re.compile(r"^[0-9a-f]{40}$")


class SchemaError(ValueError):
    pass


def _fail(msg):
    raise SchemaError(msg)


def validate_row(row: dict, allowed_ids: set) -> dict:
    """One extension's result. Raises SchemaError on anything not exactly matching the schema.
    Returns the row, normalized to only its allowed keys (belt-and-suspenders: callers should
    already only have built it this way, but ingest treats this as the authority, not a hint)."""
    if not isinstance(row, dict):
        _fail("row is not an object")
    extra = set(row) - {"id", "category", "step", "warning"}
    if extra:
        _fail(f"row has disallowed field(s): {sorted(extra)}")
    ext = row.get("id")
    if not isinstance(ext, str) or not EXT_ID_RE.match(ext):
        _fail(f"row id {ext!r} is not a well-formed lang/name extension id")
    if ext not in allowed_ids:
        _fail(f"row id {ext!r} is not in the current recheck.json list")
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
    out = {"id": ext, "category": cat}
    if step is not None:
        out["step"] = step
    if warning is not None:
        out["warning"] = warning
    return out


def validate_node_payload(payload: dict, allowed_ids: set, run_date: str | None = None) -> dict:
    """The whole single-output-file schema a sandbox run produces and the controller forwards:
        {"runId": "...", "runDate": "YYYY-MM-DD", "results": [row, ...]}
    `allowed_ids` is the exact id list from the recheck.json this run was dispatched against —
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


BUNDLE_SHA_RE = re.compile(r"^[0-9a-f]{64}$")


def validate_recheck_manifest(manifest: dict) -> dict:
    """The recheck.json schema the sweep side publishes and both the controller and node trust as
    the list of extensions a run is allowed to report on."""
    if not isinstance(manifest, dict):
        _fail("recheck.json is not an object")
    required = {
        "generated", "keiCommit", "runId", "nodeImage", "extensions",
        "bundleUrl", "bundleSha256", "bundleSize", "harnessRuntimeCommit",
    }
    missing = required - set(manifest)
    if missing:
        _fail(f"recheck.json is missing field(s): {sorted(missing)}")
    if not SHA_RE.match(manifest["keiCommit"] or ""):
        _fail("recheck.json keiCommit is not a 40-hex SHA")
    if not RUN_ID_RE.match(str(manifest["runId"])):
        _fail("recheck.json runId is not a plain numeric GitHub run id")
    image = manifest["nodeImage"]
    if not isinstance(image, str) or "@" not in image or not DIGEST_RE.match(image.rsplit("@", 1)[-1]):
        _fail("recheck.json nodeImage is not pinned by sha256 digest")
    exts = manifest["extensions"]
    if not isinstance(exts, list) or not exts:
        _fail("recheck.json extensions is empty or not a list")
    if len(exts) > MAX_EXTENSIONS_DEFAULT:
        _fail(f"recheck.json lists {len(exts)} extensions, more than the {MAX_EXTENSIONS_DEFAULT} cap")
    for ext in exts:
        if not isinstance(ext, str) or not EXT_ID_RE.match(ext):
            _fail(f"recheck.json extension id {ext!r} is not well-formed")
    # the bundle is never committed to gh-pages — it's a Release asset referenced by URL,
    # with its own checksum/size pinned HERE so the controller downloads recheck.json once and
    # trusts those exact bytes, never re-deriving them from a second fetch. harnessRuntimeCommit
    # is this repo's commit that built the node image's baked-in harness runtime: the node
    # refuses to run a bundle built against a different runtime than the one it's shipping.
    url = manifest["bundleUrl"]
    if not isinstance(url, str) or not url.startswith("https://github.com/"):
        _fail("recheck.json bundleUrl must be an https://github.com/... Release asset URL")
    if not BUNDLE_SHA_RE.match(manifest["bundleSha256"] or ""):
        _fail("recheck.json bundleSha256 is not a 64-hex sha256")
    size = manifest["bundleSize"]
    if not isinstance(size, int) or size <= 0 or size > 500 * 1024 * 1024:
        _fail("recheck.json bundleSize is not a plausible positive byte count")
    if not SHA_RE.match(manifest["harnessRuntimeCommit"] or ""):
        _fail("recheck.json harnessRuntimeCommit is not a 40-hex SHA")
    return manifest
