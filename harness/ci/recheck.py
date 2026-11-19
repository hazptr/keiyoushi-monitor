#!/usr/bin/env python3
"""Recheck a sweep shard's blocked-looking failures through the recheck proxies on the tailnet.

    recheck.py <extensions-source> <runtime-dist> <shard-file> <run-id> <work-dir> <out.json>

Runs in the sweep shard right after its own run, on the same GitHub-hosted runner, once that
runner has joined the tailnet as tag:kmon-ci (the tailscale/github-action step before this).
Every online peer tagged tag:kmon-proxy is a proxy; each one's exit country and time zone are measured
through the proxy itself at the start, so a proxy's location is never taken on trust.

Each extension goes to the proxies in this order: those in a country it prefers (recheck/geo.tsv,
else a default for languages tied to one country, e.g. vi -> VN), then the rest in
RECHECK_PROXY_ORDER (comma-separated hostnames; unlisted ones after, by name). It moves on to the
next proxy only while the result still looks like a block (blocked, cloudflare, timeout,
connect); a real failure is the same from anywhere. A pass anywhere is a pass.

Output is the category-only payload publisher/categorize.py defines (no raw text, safe for a public
artifact), each pass carrying the country it passed from as `cc`. The console only ever shows
proxy hostnames, countries and per-extension status words: never an exit IP, never error text.
"""
import json
import os
import subprocess
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
sys.path.insert(0, str(ROOT / "publisher"))
from node_schema import validate_node_payload  # noqa: E402

PROXY_TAG = "tag:kmon-proxy"
PROXY_PORT = 3128
RETRY_CATEGORIES = {"blocked", "cloudflare", "timeout", "connect"}
# Languages spoken in essentially one country, where a site's audience (and any geo rule) is there.
LANG_COUNTRY = {"vi": "VN", "id": "ID", "th": "TH", "ko": "KR", "ja": "JP", "tr": "TR",
                "pl": "PL", "uk": "UA", "cs": "CZ", "it": "IT", "de": "DE", "ru": "RU"}
GEO_FILE = ROOT / "recheck" / "geo.tsv"


def log(msg: str) -> None:
    print(f"[recheck] {msg}", flush=True)


def discover() -> list[dict]:
    out = subprocess.run(["tailscale", "status", "--json"], capture_output=True, text=True)
    if out.returncode != 0:
        log("tailscale status failed; no proxies")
        return []
    peers = (json.loads(out.stdout).get("Peer") or {}).values()
    found = []
    for p in peers:
        if PROXY_TAG not in (p.get("Tags") or []) or not p.get("Online"):
            continue
        v4 = [ip for ip in p.get("TailscaleIPs") or [] if "." in ip]
        if v4:
            found.append({"name": p.get("HostName") or p.get("DNSName", "?"), "ip": v4[0]})
    return found


def probe(proxy: dict) -> dict | None:
    """The proxy's exit country + time zone, as the outside world sees it. Only those two fields
    are requested, so the exit IP never even reaches this process."""
    url = "http://ip-api.com/json/?fields=status,countryCode,timezone"
    for _ in range(2):
        r = subprocess.run(["curl", "-sS", "--max-time", "20", "-x", f"http://{proxy['ip']}:{PROXY_PORT}", url],
                           capture_output=True, text=True)
        try:
            data = json.loads(r.stdout)
        except json.JSONDecodeError:
            data = {}
        if data.get("status") == "success" and len(data.get("countryCode") or "") == 2:
            return {**proxy, "cc": data["countryCode"].upper(), "tz": data.get("timezone") or "UTC"}
        time.sleep(5)
    return None


def load_geo() -> dict[str, list[str]]:
    geo = {}
    if GEO_FILE.exists():
        for line in GEO_FILE.read_text().splitlines():
            line = line.split("#", 1)[0].strip()
            if not line:
                continue
            ext, _, ccs = line.partition("\t")
            geo[ext.strip()] = [c.strip().upper() for c in ccs.replace(" ", ",").split(",") if c.strip()]
    return geo


def proxy_order(ext: str, proxies: list[dict], geo: dict, ranking: list[str]) -> list[dict]:
    wanted = geo.get(ext) or ([LANG_COUNTRY[ext.split("/")[0]]] if ext.split("/")[0] in LANG_COUNTRY else [])

    def rank(p):
        name_rank = ranking.index(p["name"]) if p["name"] in ranking else len(ranking)
        cc_rank = wanted.index(p["cc"]) if p["cc"] in wanted else len(wanted)
        return (cc_rank, name_rank, p["name"])
    return sorted(proxies, key=rank)


def stop_webview() -> None:
    """Every run-shard.sh call starts its own WebView sidecar (Xvfb :99 + bridge.py + Chrome) and
    leaves it running; the next call needs :99 free and a Chrome on the new proxy. Also clears
    the sweep's own sidecar before the first group."""
    for pattern in ("--remote-debugging", "webview/nodriver/bridge.py", "Xvfb :99"):
        subprocess.run(["pkill", "-f", "--", pattern], capture_output=True)
    time.sleep(2)


def run_group(proxy: dict, exts: list[str], wt: str, dist: str, out: Path, run_id: str, run_date: str) -> list[dict]:
    out.mkdir(parents=True, exist_ok=True)
    shard = out / "shard.txt"
    shard.write_text("\n".join(exts) + "\n")
    url = f"http://{proxy['ip']}:{PROXY_PORT}"
    env = {**os.environ,
           "EXT_RUN_QUIET": "1",
           "EXT_RUN_VIA": "proxy",
           "EXT_RUN_WV_PROXY": url,
           "EXT_RUN_TZ": proxy["tz"],
           "EXT_RUN_JAVA_EXTRA_OPTS": (f"{os.environ.get('EXT_RUN_JAVA_EXTRA_OPTS', '')} "
                                       f"-Dhttps.proxyHost={proxy['ip']} -Dhttps.proxyPort={PROXY_PORT} "
                                       f"-Dhttp.proxyHost={proxy['ip']} -Dhttp.proxyPort={PROXY_PORT} ").strip()}
    log(f"{len(exts)} extension(s) via {proxy['name']} ({proxy['cc']})")
    stop_webview()
    subprocess.run([str(HERE / "run-shard.sh"), wt, dist, str(shard), str(out)], env=env)
    stop_webview()
    res = out / "result.json"
    r = subprocess.run([sys.executable, str(ROOT / "publisher" / "categorize.py"), str(out / "results.tsv"),
                        str(out / "json"), run_id, run_date, str(res)], capture_output=True, text=True)
    if r.returncode != 0 or not res.exists():
        log(f"couldn't categorize the results from {proxy['name']}; counting them as errors")
        return [{"id": e, "category": "error"} for e in exts]
    return json.loads(res.read_text())["results"]


def main():
    if len(sys.argv) != 7:
        sys.exit(__doc__)
    wt, dist, shard_file, run_id, work, out_path = sys.argv[1:7]
    run_date = time.strftime("%Y-%m-%d", time.gmtime())
    exts = [l.strip() for l in Path(shard_file).read_text().splitlines() if l.strip()]
    payload = {"runId": run_id, "runDate": run_date, "results": []}
    if not exts:
        Path(out_path).write_text(json.dumps(payload))
        log("nothing to recheck in this shard")
        return

    proxies = [p for p in (probe(p) for p in discover()) if p]
    for p in proxies:
        log(f"proxy {p['name']}: exits in {p['cc']}")
    if not proxies:
        Path(out_path).write_text(json.dumps(payload))
        print("::warning::no recheck proxy reachable on the tailnet; nothing rechecked")
        return

    geo = load_geo()
    ranking = [n.strip() for n in os.environ.get("RECHECK_PROXY_ORDER", "").split(",") if n.strip()]
    orders = {e: proxy_order(e, proxies, geo, ranking) for e in exts}
    final: dict[str, dict] = {}
    pending = list(exts)
    for rnd in range(len(proxies)):
        if not pending:
            break
        groups: dict[str, list[str]] = {}
        for e in pending:
            groups.setdefault(orders[e][rnd]["name"], []).append(e)
        still = []
        for name, group in groups.items():
            proxy = next(p for p in proxies if p["name"] == name)
            rows = run_group(proxy, group, wt, dist, Path(work) / f"r{rnd}-{name}", run_id, run_date)
            got = {r["id"]: r for r in rows}
            for e in group:
                row = got.get(e, {"id": e, "category": "error"})
                if row["category"] == "pass":
                    row = {**row, "cc": proxy["cc"]}
                final[e] = row
                if row["category"] in RETRY_CATEGORIES and rnd + 1 < len(proxies):
                    still.append(e)
        pending = still

    payload["results"] = [final[e] for e in exts if e in final]
    validated = validate_node_payload(payload, set(exts), run_date=run_date)
    Path(out_path).write_text(json.dumps(validated))
    passed = sum(r["category"] == "pass" for r in validated["results"])
    log(f"done: {passed} of {len(exts)} pass through a proxy")


if __name__ == "__main__":
    main()
