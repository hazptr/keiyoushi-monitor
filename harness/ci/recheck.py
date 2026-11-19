#!/usr/bin/env python3
"""Recheck a sweep shard's blocked-looking failures through the recheck proxies.

    recheck.py <extensions-source> <runtime-dist> <shard-file> <run-id> <work-dir> <out.json>

Runs in the sweep shard right after its own run, on the same GitHub-hosted runner, once that
runner has joined the tailnet as tag:kmon-ci (the tailscale/github-action step before this).
The proxies are every online tailnet peer tagged tag:kmon-proxy, plus any public proxies in the
RECHECK_PROXIES secret: a JSON list of {"name": ..., "url": "http://[user:pass@]host:port"} (or
socks5://). A SOCKS5 proxy, or an HTTP one with a login, goes through proxy_chain.py on 127.0.0.1,
since Chrome and the JVM can't send proxy credentials (and the JVM only speaks HTTP proxy). Each proxy's exit country and time zone are measured
through the proxy itself at the start, so a proxy's location is never taken on trust.

Each extension goes to the proxies in this order: those in a country it prefers (recheck/geo.tsv,
else a default for languages tied to one country, e.g. vi -> VN), then any named in
RECHECK_PROXY_ORDER (comma-separated), then the rest spread evenly: a stable per-extension shuffle,
alternating plain VPN exits and WARP exits (a name containing "warp"), so the shards don't all
pile onto one proxy and a second try leaves from a different kind of network. It moves on to the
next proxy only while the result still looks like a block (blocked, cloudflare, timeout,
connect), at most RECHECK_MAX_TRIES (default 3) proxies; a real failure is the same from
anywhere. A pass anywhere is a pass.

Output is the category-only payload publisher/categorize.py defines (no raw text, safe for a public
artifact), each pass carrying the country it passed from as `cc`. The console only ever shows
proxy names, countries and per-extension status words: never an exit IP or a proxy URL, never
error text.
"""
import hashlib
import json
import os
import re
import socket
import subprocess
import sys
import time
import urllib.parse
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
    try:
        out = subprocess.run(["tailscale", "status", "--json"], capture_output=True, text=True)
    except FileNotFoundError:
        return []  # not on the tailnet this run (only public proxies)
    if out.returncode != 0:
        log("tailscale status failed; no tailnet proxies")
        return []
    peers = (json.loads(out.stdout).get("Peer") or {}).values()
    found = []
    for p in peers:
        if PROXY_TAG not in (p.get("Tags") or []) or not p.get("Online"):
            continue
        v4 = [ip for ip in p.get("TailscaleIPs") or [] if "." in ip]
        if v4:
            found.append({"name": p.get("HostName") or p.get("DNSName", "?"), "host": v4[0], "port": PROXY_PORT})
    return found


def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def public_proxies() -> list[dict]:
    """RECHECK_PROXIES, masked in the job log before anything else touches it. A proxy with a
    login gets its own proxy_chain.py front; one without is used as is."""
    raw = os.environ.get("RECHECK_PROXIES", "").strip()
    if not raw:
        return []
    try:
        entries = json.loads(raw)
    except json.JSONDecodeError:
        print("::warning::RECHECK_PROXIES isn't valid JSON; ignoring it")
        return []
    found = []
    for i, e in enumerate(entries if isinstance(entries, list) else []):
        url = e.get("url", "") if isinstance(e, dict) else str(e)
        u = urllib.parse.urlsplit(url)
        for secret in {url, u.hostname or "", u.password or "", u.username or ""} - {""}:
            print(f"::add-mask::{secret}")
        name = re.sub(r"[^a-z0-9-]", "-", str(e.get("name") if isinstance(e, dict) and e.get("name") else f"public-{i + 1}").lower())
        if u.scheme not in ("http", "socks5", "socks5h") or not u.hostname:
            log(f"{name}: only http:// and socks5:// proxies are supported; skipping")
            continue
        if u.username or u.scheme != "http":
            port = free_port()
            subprocess.Popen([sys.executable, str(HERE / "proxy_chain.py"), str(port)],
                             env={**os.environ, "CHAIN_UPSTREAM": url},
                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            time.sleep(1)
            found.append({"name": name, "host": "127.0.0.1", "port": port})
        else:
            found.append({"name": name, "host": u.hostname, "port": u.port or 8080})
    return found


def probe(proxy: dict) -> dict | None:
    """The proxy's exit country + time zone, as the outside world sees it. Only those two fields
    are requested, so the exit IP never even reaches this process."""
    url = "http://ip-api.com/json/?fields=status,countryCode,timezone"
    for attempt in range(4):
        r = subprocess.run(["curl", "-sS", "--max-time", "20", "-x", f"http://{proxy['host']}:{proxy['port']}", url],
                           capture_output=True, text=True)
        try:
            data = json.loads(r.stdout)
        except json.JSONDecodeError:
            data = {}
        if data.get("status") == "success" and len(data.get("countryCode") or "") == 2:
            return {**proxy, "cc": data["countryCode"].upper(), "tz": data.get("timezone") or "UTC"}
        time.sleep(5 * (attempt + 1))
    log(f"proxy {proxy['name']}: no answer through it; not using it this run")
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

    def shuffle(p):
        return hashlib.sha256(f"{ext}|{p['name']}".encode()).hexdigest()

    def rank(p):
        return (wanted.index(p["cc"]) if p["cc"] in wanted else len(wanted),
                ranking.index(p["name"]) if p["name"] in ranking else len(ranking), shuffle(p))
    head = [p for p in sorted(proxies, key=rank) if p["cc"] in wanted or p["name"] in ranking]
    rest = [p for p in proxies if p not in head]
    plain = sorted((p for p in rest if "warp" not in p["name"]), key=shuffle)
    warp = sorted((p for p in rest if "warp" in p["name"]), key=shuffle)
    mixed = [p for pair in zip(plain, warp) for p in pair] + plain[len(warp):] + warp[len(plain):]
    return head + mixed


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
    url = f"http://{proxy['host']}:{proxy['port']}"
    env = {**os.environ,
           "EXT_RUN_QUIET": "1",
           "EXT_RUN_VIA": "proxy",
           "EXT_RUN_WV_PROXY": url,
           "EXT_RUN_TZ": proxy["tz"],
           "EXT_RUN_JAVA_EXTRA_OPTS": (f"{os.environ.get('EXT_RUN_JAVA_EXTRA_OPTS', '')} "
                                       f"-Dhttps.proxyHost={proxy['host']} -Dhttps.proxyPort={proxy['port']} "
                                       f"-Dhttp.proxyHost={proxy['host']} -Dhttp.proxyPort={proxy['port']} ").strip()}
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

    candidates = discover() + public_proxies()
    names = [p["name"] for p in candidates]
    candidates = [p for i, p in enumerate(candidates) if p["name"] not in names[:i]]  # first of a name wins
    proxies = [p for p in (probe(p) for p in candidates) if p]
    for p in proxies:
        log(f"proxy {p['name']}: exits in {p['cc']}")
    if not proxies:
        Path(out_path).write_text(json.dumps(payload))
        print("::warning::no recheck proxy reachable; nothing rechecked")
        return

    geo = load_geo()
    ranking = [n.strip() for n in os.environ.get("RECHECK_PROXY_ORDER", "").split(",") if n.strip()]
    orders = {e: proxy_order(e, proxies, geo, ranking) for e in exts}
    final: dict[str, dict] = {}
    pending = list(exts)
    tries = min(len(proxies), max(1, int(os.environ.get("RECHECK_MAX_TRIES") or 3)))
    for rnd in range(tries):
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
                if row["category"] in RETRY_CATEGORIES and rnd + 1 < tries:
                    still.append(e)
        pending = still

    payload["results"] = [final[e] for e in exts if e in final]
    validated = validate_node_payload(payload, set(exts), run_date=run_date)
    Path(out_path).write_text(json.dumps(validated))
    passed = sum(r["category"] == "pass" for r in validated["results"])
    log(f"done: {passed} of {len(exts)} pass through a proxy")


if __name__ == "__main__":
    main()
