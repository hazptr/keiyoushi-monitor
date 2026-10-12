#!/usr/bin/env python3
"""build-site job: turn this run's results.tsv into status.json and a copy of site/ ready to
deploy. Has no local persistent state — history ("since", run-history strips, previous-run diff)
is read back from the currently-published status.json instead of a state directory, so a fork
needs nothing but this repo + its Pages branch.

A later, separate workflow (`ingest`, triggered by a recheck node's controller — see
node/README.md) can patch individual entries in the already-published status.json; this script
has no part in that and no concept of a node recheck.

  build_status.py --results TSV --extensions-source DIR --site DIR --out DIR
                   [--overrides FILE] [--config-dir DIR] [--previous-status FILE]
                   [--kei-commit SHA] [--run-id ID] [--progress DONE/TOTAL] [--run-date YYYY-MM-DD]
"""
import argparse
import datetime as dt
import hashlib
import json
import re
import shutil
import sys
import tomllib
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from leak_check import leak_check, runner_public_ips, scrub_json, scrub_smoke_json, scrub_text  # noqa: E402

# Failures that aren't the extension's fault, with the plain reason shown instead.
UNTESTABLE_RULES = [
    (re.compile(r"(Failed to connect to|connect to) /?(127\.0\.0\.1|localhost|\[?::1\]?)\b|No server address|server address (is )?(empty|not set)", re.I),
     "Self-hosted, needs your own server to test"),
    (re.compile(r"NoClassDefFoundError|NoSuchMethodError|NoSuchFieldError|NoSuchAlgorithmException|ext-run: the source uses an Android class|isn't supported in the harness|postUrl", re.I),
     "Our tester can't run this one yet, a gap on our side"),
    (re.compile(r"\b(log ?in|sign ?in|session expired|credentials|username or password|refresh token)\b|\bwebview\b", re.I),
     "Needs an account or a WebView step in the app"),
]

# The site turned the checker away, so we can't tell if the extension works: "Might be broken",
# tagged with why. Checked before UNTESTABLE_RULES ("waiting for WebView" would match its login rule).
BLOCK_TAGS = [
    ("cloudflare", re.compile(r"WebViewTimeoutException|Timed out waiting for WebView|turnstile|Failed to bypass Cloudflare|cloudflare|cf-mitigated", re.I)),
    ("http403", re.compile(r"HTTP error 403\b")),
    ("ratelimit", re.compile(r"HTTP error 429\b")),
]

OPS = ["browse", "latest", "search", "details", "chapters", "read", "pages"]
LEGACY_STEPS = {"popular": "browse", "manga": "details", "pages": "read", "images": "pages"}
RUNS_KEPT = 90
CODES = {"pass": "p", "partial": "a", "fail": "f", "maybe": "m", "untestable": "u", "unsupported": "n"}
STATUSES = ("pass", "partial", "fail", "maybe", "untestable", "unsupported", "pending")


def block_tags(text: str) -> list:
    return [tag for tag, rx in BLOCK_TAGS if rx.search(text or "")]


def untestable_reason(summary: str):
    return next((reason for rx, reason in UNTESTABLE_RULES if rx.search(summary or "")), None)


def short_error(err):
    if not err:
        return None
    err = err.splitlines()[0]
    err = re.sub(r"^(?:[a-z_][\w]*\.)+([A-Z]\w*)", r"\1", err.strip())
    err = re.sub(r"https?://([^/\s:]+)[^\s]*", r"\1", err)
    return scrub_text(err)[:160]


def gradle_fields(ext_source_dir: Path, ext: str) -> dict:
    f = ext_source_dir / "src" / ext / "build.gradle.kts"
    t = f.read_text() if f.exists() else ""
    get = lambda k: (re.search(rf'\b{k}\s*=\s*"([^"]*)"', t) or [None, None])[1]
    vc = re.search(r"\bversionCode\s*=\s*(\d+)", t)
    cw = re.search(r"ContentWarning\.(\w+)", t)
    return {"name": get("name") or ext.split("/")[1], "versionCode": int(vc[1]) if vc else None,
            "contentWarning": cw[1] if cw else None, "libVersion": get("libVersion") or "1.6"}


def ext_config(config_dir: Path | None, ext: str) -> dict:
    if not config_dir:
        return {}
    f = config_dir / f"{ext.replace('/', '_')}.toml"
    return tomllib.loads(f.read_text()) if f.exists() else {}


def operations(smoke, cfg: dict | None = None) -> list:
    steps = (smoke or {}).get("steps", [])
    legacy = any(st.get("step") in ("popular", "manga", "images") for st in steps)
    out = []
    for st in steps:
        op = LEGACY_STEPS.get(st.get("step"), st.get("step")) if legacy else st.get("step")
        status = st.get("status") or ("pass" if st.get("ok") else "fail")
        item = {"op": op, "status": status}
        if status == "fail":
            tags, reason = block_tags(st.get("error")), untestable_reason(st.get("error") or "")
            if tags:
                item.update(status="maybe", error=short_error(st.get("error")) or "blocked", tags=tags)
            elif reason:
                item.update(status="untestable", error=reason)
            else:
                item.update(error=short_error(st.get("error")) or "failed")
        elif status == "skip" and st.get("reason"):
            item["reason"] = scrub_text(st["reason"])[:160]
        if op in (cfg or {}).get("not_applicable", []) and item["status"] != "pass":
            item = {"op": op, "status": "na", "reason": scrub_text((cfg or {}).get("not_applicable_reason", "Not part of this source"))[:200]}
        out.append(item)
    return [o for o in out if o["op"] in OPS]


def overall(ops: list) -> str:
    by = {o["op"]: o["status"] for o in ops}
    fails = [o for o in ops if o["status"] == "fail"]
    reading = by.get("read") == "pass" and by.get("pages") == "pass"
    if not fails:
        if any(o["status"] == "maybe" for o in ops):
            return "maybe"
        return "pass" if reading or not ops else "untestable"
    return "partial" if reading else "fail"


NOTE_RULES = [
    (re.compile(r"^(\d+) of (\d+) titles tried didn't read \((\w+): "),
     lambda m: f"Only 1 of the {m[2]} titles we tried could be read"),
    (re.compile(r"^(\d+) of (\d+) page images is a single flat colour"), lambda m: "A page came back as a blank image"),
    (re.compile(r"^search for '.*' \(from its own \w+ list\) found nothing"), lambda m: "Searching for one of its own titles found nothing"),
    (re.compile(r"^(\d+) duplicate manga urls"), lambda m: f"Its lists show {m[1]} {'title' if m[1] == '1' else 'titles'} more than once"),
    (re.compile(r"^(\d+) duplicate chapter urls"), lambda m: f"The chapter list repeats {m[1]} {'chapter' if m[1] == '1' else 'chapters'}"),
    (re.compile(r"^\d+ of \d+ \w+ entries with a blank title"), lambda m: "Some titles in its lists have no name"),
]


def notes(smoke) -> list:
    found = {}
    for w in (smoke or {}).get("warnings", []):
        for i, (rx, text) in enumerate(NOTE_RULES):
            m = rx.search(w)
            if m:
                found.setdefault(i, scrub_text(text(m))[:140])
                break
    count = next((st.get("detail", {}).get("chapterCount") for st in (smoke or {}).get("steps", []) if st.get("step") == "chapters"), None)
    undated = next((int(m[1]) for w in (smoke or {}).get("warnings", []) if (m := re.match(r"(\d+) chapters with date_upload=0", w))), 0)
    if count and count > 1 and undated * 2 >= count:
        found[len(NOTE_RULES)] = "Chapters have no dates" if undated == count else "Most chapters have no date"
    return [found[i] for i in sorted(found)][:5]


def load_smoke_json(json_dir: Path, ext: str):
    """Loads a sweep shard's raw per-extension JSON and immediately narrows+scrubs it
    (scrub_smoke_json()) — nothing raw reaches anything built from this. entry()/operations()/
    notes() additionally scrub_text() the specific strings they publish; that's redundant on
    already-scrubbed input, which is the point."""
    f = json_dir / f"{ext.replace('/', '_')}.json"
    if not f.exists():
        return None
    try:
        return scrub_smoke_json(json.loads(f.read_text()))
    except json.JSONDecodeError:
        return None


def entry(ext_source_dir, json_dir, config_dir, ext: str, status: str, summary: str, via: str) -> dict:
    # Note: this function has no concept of a node recheck. A node's result is never merged
    # here — it's applied straight onto the already-published status.json entries by the
    # `ingest` workflow (publisher/merge_node_result.py), well after this script has run, since
    # a node's result can arrive on its own schedule, independent of a daily sweep.
    smoke = load_smoke_json(json_dir, ext)
    pub = {"id": ext, "lang": ext.split("/")[0], **gradle_fields(ext_source_dir, ext)}
    details = next((st for st in (smoke or {}).get("steps", []) if st.get("step") in ("details", "manga")), {})
    title = (details.get("detail") or {}).get("title")
    if title:
        pub["checkedWith"] = scrub_text(title)[:120]
    ops = operations(smoke, ext_config(config_dir, ext))
    if ops:
        pub["ops"] = ops
    if notes(smoke):
        pub["notes"] = notes(smoke)
    if status == "pass" and ops:
        pub["status"] = overall(ops)
        if pub["status"] == "untestable":
            pub["error"] = next((o.get("error") for o in ops if o["status"] == "untestable"), "Couldn't check this one automatically")
        first = next((o for o in ops if o["status"] == "fail"), None)
        if first:
            pub.update(step=first["op"], error=first["error"])
    elif status == "pass":
        pub["status"] = "pass"
    elif status == "fail" and block_tags(summary) and not any(o["status"] == "fail" for o in ops):
        pub.update(status="maybe", error=short_error(summary) or "blocked")
    elif status == "fail" and untestable_reason(summary):
        pub.update(status="untestable", error=untestable_reason(summary))
    elif status == "fail":
        pub.update(status="fail", error=short_error(summary) or "failed")
    else:
        for o in ops:
            if o["status"] == "fail":
                o.update(status="untestable", error=scrub_text(summary)[:160] if summary else "Couldn't check")
        reason = (scrub_text(summary)[:160] if status == "untestable" and summary else "Build or harness error, not the extension's fault")
        pub.update(status="untestable", error=reason)
    if pub["status"] == "maybe":
        tags = {t for o in ops for t in o.get("tags", [])} | set(block_tags(summary))
        pub["tags"] = [t for t, _ in BLOCK_TAGS if t in tags]
    return pub


def read_tsv(path: Path) -> dict:
    """ext -> (status, secs, summary, via)."""
    rows = {}
    if not path or not path.exists():
        return rows
    for line in path.read_text().splitlines()[1:]:
        if not line.strip():
            continue
        cols = line.split("\t")
        cols += [""] * (5 - len(cols))
        rows[cols[0]] = (cols[1], cols[2], cols[3], cols[4] or "gha")
    return rows


def build(args) -> dict:
    ext_source = Path(args.extensions_source)
    json_dir = Path(args.results).parent / "json"
    config_dir = Path(args.config_dir) if args.config_dir else None
    main_rows = read_tsv(Path(args.results))

    overrides = {}
    if args.overrides and Path(args.overrides).exists():
        overrides = dict(l.split("\t", 1) for l in Path(args.overrides).read_text().splitlines() if l.strip() and not l.startswith("#"))

    exts = []
    for ext, (status, secs, summary, via) in main_rows.items():
        use_status, use_summary, use_via = status, summary, via
        if ext in overrides:
            use_status, use_summary = "untestable", overrides[ext]
        exts.append(entry(ext_source, json_dir, config_dir, ext, use_status, use_summary, use_via))

    # 1.4 extensions aren't run by the checker at all; list them so nobody wonders where they went.
    listed = {x["id"] for x in exts}
    for f in sorted(ext_source.glob("src/*/*/build.gradle.kts")):
        ext = "/".join(f.parent.relative_to(ext_source / "src").parts)
        if ext not in listed and 'libVersion = "1.4"' in f.read_text():
            exts.append({"id": ext, "lang": ext.split("/")[0], **gradle_fields(ext_source, ext), "status": "unsupported",
                         "error": "Uses the older 1.4 extension API, which this checker doesn't run"})

    date = args.run_date
    prev = json.loads(Path(args.previous_status).read_text()) if args.previous_status and Path(args.previous_status).exists() else None
    prev_by_id = {x["id"]: x for x in (prev or {}).get("extensions", [])}
    hist = {x["id"]: {"status": x["status"], "since": x.get("since", date)} for x in (prev or {}).get("extensions", [])}
    before_status = {k: v["status"] for k, v in prev_by_id.items()}
    for x in exts:
        h = hist.get(x["id"])
        if not h or h["status"] != x["status"]:
            hist[x["id"]] = {"status": x["status"], "since": date}
        x["since"] = hist[x["id"]]["since"]

    # 1.6 extensions this run didn't reach (a subset run, or an in-progress partial publish) stay
    # listed as pending, with fresh gradle fields only, so search still finds them. Deliberately
    # NOT carrying anything forward from the previous run's entry (ops, error, step,
    # recheckedOnNode/recheckedOn, ...): a node result (or any other prior-run detail) must never
    # leak onto a pending entry of a run that hasn't reached that extension yet. The run-history
    # sparkline (built below from history/ext) is how "last known result" actually survives
    # across runs, not this entry.
    listed = {x["id"] for x in exts}
    for f in sorted(ext_source.glob("src/*/*/build.gradle.kts")):
        ext = "/".join(f.parent.relative_to(ext_source / "src").parts)
        if ext not in listed and 'libVersion = "1.6"' in f.read_text():
            exts.append({"id": ext, "lang": ext.split("/")[0], **gradle_fields(ext_source, ext), "status": "pending"})

    for x in exts:
        x["name"] = scrub_text(x.get("name", ""))
    exts.sort(key=lambda x: (x["lang"], x.get("name", "").lower()))
    counts = {s: sum(x["status"] == s for x in exts) for s in STATUSES}

    runs = (prev or {}).get("history", {}).get("runs", [])
    ext_hist = (prev or {}).get("history", {}).get("ext", {})
    ext_hist_wide = {eid: dict(zip(runs, codes)) for eid, codes in ext_hist.items()}
    if not args.progress and date not in runs:
        runs = (runs + [date])[-RUNS_KEPT:]
    for x in exts:
        if x["status"] == "pending" or args.progress:
            continue
        ext_hist_wide.setdefault(x["id"], {})[date] = CODES.get(x["status"], "u")
    for d in ext_hist_wide.values():
        for old in [k for k in d if k not in runs]:
            del d[old]

    data = {
        "generated": dt.datetime.now(dt.timezone.utc).isoformat(timespec="minutes"),
        "sweepDate": date, "previousSweepDate": (prev or {}).get("sweepDate"),
        "keiCommit": args.kei_commit or "", "runId": args.run_id or "", "counts": counts,
        "changes": {
            "newlyFailing": [x["id"] for x in exts if x["status"] in ("fail", "partial") and before_status.get(x["id"]) == "pass"],
            "nowPassing": [x["id"] for x in exts if x["status"] == "pass" and before_status.get(x["id"]) in ("fail", "partial")],
        },
        "extensions": exts,
        "history": {"runs": runs, "ext": {eid: "".join(codes.get(r, "-") for r in runs) for eid, codes in ext_hist_wide.items()}},
    }
    if args.progress:
        done, total = map(int, args.progress.split("/"))
        data["inProgress"] = {"done": done, "total": total}
    return data


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--results", required=True)
    ap.add_argument("--extensions-source", required=True)
    ap.add_argument("--site", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--overrides")
    ap.add_argument("--config-dir")
    ap.add_argument("--previous-status")
    ap.add_argument("--kei-commit", default="")
    ap.add_argument("--run-id", default="")
    ap.add_argument("--progress")
    ap.add_argument("--run-date", default=dt.date.today().isoformat())
    args = ap.parse_args()

    data = build(args)
    out = Path(args.out)
    shutil.rmtree(out, ignore_errors=True)
    shutil.copytree(args.site, out)
    index = out / "index.html"
    html = index.read_text()
    for asset in ("style.css", "app.js"):
        v = hashlib.sha256((out / asset).read_bytes()).hexdigest()[:10]
        html = html.replace(f'"{asset}"', f'"{asset}?v={v}"')
    index.write_text(html)
    # Backstop: everything written into `data` is already individually scrubbed (load_smoke_json,
    # short_error, notes(), etc.) — this final recursive pass is the "make sure publish applies
    # the same scrub" net, so nothing raw from a sweep/recheck shard reaches gh-pages even if a
    # future field is added here without its own scrub_text() call.
    (out / "status.json").write_text(json.dumps(scrub_json(data), indent=1, ensure_ascii=False))
    (out / ".nojekyll").write_text("")

    hits = leak_check(out, extra_needles=runner_public_ips())
    if hits:
        print("REFUSING TO WRITE OUTPUT; possible leaked data:", file=sys.stderr)
        for h in sorted(set(hits)):
            print(f"  {h}", file=sys.stderr)
        sys.exit(1)
    print(f"built {out}: {data['counts']} (run {data['sweepDate']}), leak check clean")


if __name__ == "__main__":
    main()
