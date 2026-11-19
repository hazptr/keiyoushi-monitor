#!/usr/bin/env python3
"""build-site: fold the recheck jobs' results into the status.json build_status.py just wrote.

    merge_recheck.py <status.json> <recheck-list.txt> <results-dir>

<results-dir> holds one category-only payload per recheck shard (harness/ci/recheck.py), each
re-validated here against the day's recheck list before anything is merged; a shard whose file
is missing or malformed is skipped, the rest still merge. The merge itself is
merge_node_result.merge, which only lets a real verdict (pass/fail/blocked/cloudflare) override.
"""
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from merge_node_result import merge  # noqa: E402
from node_schema import SchemaError, validate_node_payload  # noqa: E402


def main():
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    status_path, list_path, results_dir = sys.argv[1:4]
    allowed = {l.strip() for l in Path(list_path).read_text().splitlines() if l.strip()}
    status = json.loads(Path(status_path).read_text())
    rows, run_date = {}, None
    for f in sorted(Path(results_dir).glob("**/*.json")):
        try:
            p = validate_node_payload(json.loads(f.read_text()), allowed)
        except (SchemaError, json.JSONDecodeError) as e:
            print(f"::warning::skipping recheck result {f.name}: {type(e).__name__}")
            continue
        if p["runId"] != str(status.get("runId")):
            print(f"::warning::skipping recheck result {f.name}: from another run")
            continue
        run_date = max(run_date or p["runDate"], p["runDate"])
        for r in p["results"]:
            rows.setdefault(r["id"], r)
    if not rows:
        print("no recheck results to merge")
        return
    payload = {"runId": str(status["runId"]), "runDate": run_date, "results": list(rows.values())}
    merged = merge(status, payload, run_date=run_date)
    Path(status_path).write_text(json.dumps(merged, indent=1, ensure_ascii=False))


if __name__ == "__main__":
    main()
