#!/usr/bin/env python3
"""Turn one smoke-run's JSON (or its absence, on a build/run error) into one results.tsv row:
ext, status (pass|fail|untestable|error), secs, summary, via.

This row's summary is GitHub-hosted-sweep free text (scrubbed, but still free text — a recheck
*node*'s output never goes through this script at all; see node/categorize.py, which only ever
emits a fixed category, never a message built from error text). Scrub BEFORE truncating: an IP or
ray ID can sit past the 200-char cut point of a long exception message, so truncating first can
leave a leak; this file always calls scrub_text() first, then slices.
"""
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent / "publisher"))
from leak_check import scrub_text  # noqa: E402

ext, secs, via, json_path, error_text = sys.argv[1:6]


def first_failure(steps):
    for s in steps:
        if s.get("status") == "fail":
            err = (s.get("error") or "").split("\n")[0]
            return scrub_text(f"{s['step']}: {err}")[:200]
    return ""


def row(status, summary):
    print("\t".join([ext, status, secs, summary.replace("\t", " "), via]))


try:
    with open(json_path) as f:
        data = json.load(f)
except (FileNotFoundError, json.JSONDecodeError):
    row("error", scrub_text(error_text or "no JSON output")[:200])
    sys.exit(0)

if data.get("untestable"):
    row("untestable", first_failure(data.get("steps", [])) or "untestable")
elif data.get("ok"):
    row("pass", "")
else:
    row("fail", first_failure(data.get("steps", [])))
