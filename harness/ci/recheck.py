#!/usr/bin/env python3
"""Run a sweep shard's blocked-looking failures again through the recheck proxies.

    recheck.py <extensions-source> <runtime-dist> <shard-out-dir> <work-dir>
    recheck.py --any <shard-out-dir>     exit 0 if the shard has anything to recheck, else 1

Runs in the sweep shard right after run-shard.sh, on the same runner. Reads the shard's
results.tsv, sends every failure that looks like a block (BLOCKISH) out through the proxies, and
writes <shard-out-dir>/recheck.json: per extension a list of the proxies it went through, in order,
each with its country, the status the page gives that run (publisher/build_status.py's own
entry()), each step's status word, its failing step or block tags, and whether Cloudflare's
checkbox was ticked. Nothing else from the proxy run leaves <work-dir>: its raw logs can carry a proxy's exit
IP, and the shard's artifact is public.

Proxies: every online tailnet peer tagged tag:kmon-proxy (the runner joined the tailnet in the step
before), plus the RECHECK_PROXIES secret (proxy/README.md). Each one's exit country and time zone
are measured through the proxy itself. An extension goes first to proxies in the country its
recheck last passed from (the previous status.json, RECHECK_PREVIOUS), then a country it wants
(recheck/geo.tsv, else LANG_COUNTRY), then those named in RECHECK_PROXY_ORDER, then the rest in a
stable per-extension shuffle that alternates plain and WARP exits. It moves to the next proxy only
while the result is still a block or an error, at most RECHECK_MAX_TRIES (default 3) proxies.
The console shows proxy names, countries and status words only.
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
from build_status import entry  # noqa: E402

PROXY_TAG = "tag:kmon-proxy"
PROXY_PORT = 3128
MAX_PER_SHARD = 300
# A failure that could be about where the check runs from; anything else fails the same everywhere.
BLOCKISH = re.compile(
    r"HTTP error (403|429|451|5\d\d)\b|cloudflare|challenge|bypass|turnstile|cf-mitigated|"
    r"timed? ?out|timeout|connect(ion)?(exception| refused| reset)|failed to connect|"
    r"unknownhost|could not resolve|name resolution|name or service not known|no address associated|"
    r"ssl|handshake|unexpected end of stream|\beof\b",
    re.I,
)
# Languages spoken in essentially one country, where a site's audience (and any geo rule) is there.
LANG_COUNTRY = {"vi": "VN", "id": "ID", "th": "TH", "ko": "KR", "ja": "JP", "tr": "TR",
                "pl": "PL", "uk": "UA", "cs": "CZ", "it": "IT", "de": "DE", "ru": "RU"}
GEO_FILE = ROOT / "recheck" / "geo.tsv"


def log(msg: str) -> None:
    print(f"[recheck] {msg}", flush=True)


def read_tsv(path: Path) -> dict[str, tuple[str, str]]:
    """ext -> (status, summary)."""
    rows = {}
    if path.exists():
        for line in path.read_text().splitlines()[1:]:
            cols = line.split("\t") + ["", "", ""]
            if cols[0]:
                rows[cols[0]] = (cols[1], cols[3])
    return rows


def candidates(out: Path) -> list[str]:
    rows = read_tsv(out / "results.tsv")
    return [e for e, (st, summary) in rows.items() if st in ("fail", "error") and BLOCKISH.search(summary)][:MAX_PER_SHARD]


# ---- proxies

def tailnet_proxies() -> list[dict]:
    try:
        out = subprocess.run(["tailscale", "status", "--json"], capture_output=True, text=True)
    except FileNotFoundError:
        return []  # not on the tailnet this run
    if out.returncode != 0:
        log("tailscale status failed; no tailnet proxies")
        return []
    found = []
    for p in (json.loads(out.stdout).get("Peer") or {}).values():
        v4 = [ip for ip in p.get("TailscaleIPs") or [] if "." in ip]
        if PROXY_TAG in (p.get("Tags") or []) and p.get("Online") and v4:
            found.append({"name": p.get("HostName") or "?", "host": v4[0], "port": PROXY_PORT})
    return found


def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def public_proxies() -> list[dict]:
    """RECHECK_PROXIES, masked in the job log first. A SOCKS5 proxy or one with a login gets a
    proxy_chain.py front on 127.0.0.1 (Chrome and the JVM can't send proxy credentials)."""
    raw = os.environ.get("RECHECK_PROXIES", "").strip()
    try:
        entries = json.loads(raw) if raw else []
    except json.JSONDecodeError:
        print("::warning::RECHECK_PROXIES isn't valid JSON; ignoring it")
        return []
    found = []
    for i, e in enumerate(entries if isinstance(entries, list) else []):
        e = e if isinstance(e, dict) else {"url": str(e)}
        url = e.get("url", "")
        u = urllib.parse.urlsplit(url)
        for secret in {url, u.hostname or "", u.password or "", u.username or ""} - {""}:
            print(f"::add-mask::{secret}")
        name = re.sub(r"[^a-z0-9-]", "-", str(e.get("name") or f"public-{i + 1}").lower())
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
        found[-1]["geoOnly"] = bool(e.get("geoOnly"))
    return found


def probe(proxy: dict) -> dict | None:
    """The proxy's exit country + time zone as the outside sees it; only those fields are asked
    for, so the exit IP never reaches this process."""
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
            ext, _, ccs = line.split("#", 1)[0].strip().partition("\t")
            if ext:
                geo[ext.strip()] = [c.strip().upper() for c in ccs.replace(" ", ",").split(",") if c.strip()]
    return geo


def load_worked(path: str) -> dict[str, str]:
    """ext -> the country its recheck last passed from, out of the previously published status.json
    (RECHECK_PREVIOUS; absent or unreadable = nothing remembered)."""
    try:
        data = json.loads(Path(path).read_text())
    except (OSError, ValueError):
        return {}
    worked = {}
    for x in data.get("extensions", []) if isinstance(data, dict) else []:
        cc = next((a.get("from") for a in x.get("rechecks") or [] if isinstance(a, dict) and a.get("status") == "pass"), None)
        if isinstance(x.get("id"), str) and isinstance(cc, str) and re.fullmatch(r"[A-Z]{2}", cc):
            worked[x["id"]] = cc
    return worked


def proxy_order(ext: str, proxies: list[dict], geo: dict, ranking: list[str], worked: dict | None = None) -> list[dict]:
    lang = ext.split("/")[0]
    geo_wanted = geo.get(ext) or ([LANG_COUNTRY[lang]] if lang in LANG_COUNTRY else [])
    # A geoOnly proxy (someone's own server) only ever gets sources that want its country by
    # geo.tsv or language; a country that merely worked before never opens it up.
    proxies = [p for p in proxies if not p.get("geoOnly") or p["cc"] in geo_wanted]
    remembered = (worked or {}).get(ext)
    wanted = ([remembered] if remembered else []) + [c for c in geo_wanted if c != remembered]

    def shuffle(p):
        return hashlib.sha256(f"{ext}|{p['name']}".encode()).hexdigest()

    def rank(p):
        return (wanted.index(p["cc"]) if p["cc"] in wanted else len(wanted),
                ranking.index(p["name"]) if p["name"] in ranking else len(ranking), shuffle(p))
    head = [p for p in sorted(proxies, key=rank) if p["cc"] in wanted or p["name"] in ranking]
    rest = [p for p in proxies if p not in head]
    plain = sorted((p for p in rest if "warp" not in p["name"]), key=shuffle)
    warp = sorted((p for p in rest if "warp" in p["name"]), key=shuffle)
    return head + [p for pair in zip(plain, warp) for p in pair] + plain[len(warp):] + warp[len(plain):]


# ---- runs

def stop_webview() -> None:
    """run-shard.sh starts its own sidecar (Xvfb :99 + bridge.py + Chrome) and leaves it running;
    the next group needs :99 free and a Chrome on its own proxy."""
    for pattern in ("--remote-debugging", "webview/nodriver/bridge.py", "Xvfb :99"):
        subprocess.run(["pkill", "-f", "--", pattern], capture_output=True)
    time.sleep(2)


def run_group(proxy: dict, exts: list[str], wt: str, dist: str, out: Path) -> dict[str, tuple[str, str]]:
    out.mkdir(parents=True, exist_ok=True)
    (out / "shard.txt").write_text("\n".join(exts) + "\n")
    h, p = proxy["host"], proxy["port"]
    env = {**os.environ, "EXT_RUN_QUIET": "1", "EXT_RUN_VIA": "proxy", "EXT_RUN_WV_PROXY": f"http://{h}:{p}",
           "EXT_RUN_TZ": proxy["tz"],
           "EXT_RUN_JAVA_EXTRA_OPTS": f"{os.environ.get('EXT_RUN_JAVA_EXTRA_OPTS', '')} -Dhttps.proxyHost={h} "
                                      f"-Dhttps.proxyPort={p} -Dhttp.proxyHost={h} -Dhttp.proxyPort={p}".strip()}
    log(f"{len(exts)} extension(s) via {proxy['name']} ({proxy['cc']})")
    stop_webview()
    subprocess.run([str(HERE / "run-shard.sh"), wt, dist, str(out / "shard.txt"), str(out)], env=env)
    stop_webview()
    return read_tsv(out / "results.tsv")


def attempt(wt: str, run_dir: Path, ext: str, status: str, summary: str, cc: str) -> dict:
    """One proxy's result as the page shows it: the fixed fields of build_status.entry() on the run.
    status: pass, fail (the extension itself, with its step), maybe (blocked: Cloudflare, 403, 429,
    with tags) or error (a timeout or connect failure through the proxy, or a harness error: says
    nothing about the site)."""
    x = entry(Path(wt), run_dir / "json", ROOT / "config", ext, status, summary, "proxy")
    st = x["status"]
    step = x.get("step") or next((o["op"] for o in x.get("ops", []) if o["status"] == "fail"), None)
    if st in ("partial", "fail") and step and not BLOCKISH.search(summary):
        st = "fail"
    elif st not in ("pass", "maybe"):
        st = "error"
    row = {"from": cc, "status": st, "ops": [{"op": o["op"], "status": o["status"]} for o in x.get("ops", [])]}
    if st == "fail":
        row["step"] = step
    if st == "maybe" and x.get("tags"):
        row["tags"] = x["tags"]
    smoke = run_dir / "json" / f"{ext.replace('/', '_')}.json"
    try:
        if "cloudflare checkbox clicked" in (json.loads(smoke.read_text()).get("warnings") or []):
            row["ticked"] = True
    except (OSError, ValueError):
        pass
    return row


def main():
    if sys.argv[1:2] == ["--any"]:
        sys.exit(0 if candidates(Path(sys.argv[2])) else 1)
    if len(sys.argv) != 5:
        sys.exit(__doc__)
    wt, dist, out, work = sys.argv[1], sys.argv[2], Path(sys.argv[3]), Path(sys.argv[4])
    exts = candidates(out)
    result: dict[str, list] = {}
    proxies = [p for p in (probe(p) for p in tailnet_proxies() + public_proxies()) if p]
    proxies = [p for i, p in enumerate(proxies) if p["name"] not in [q["name"] for q in proxies[:i]]]
    for p in proxies:
        log(f"proxy {p['name']}: exits in {p['cc']}")
    if exts and not proxies:
        print("::warning::no recheck proxy reachable; nothing rechecked")
    if exts and proxies:
        geo = load_geo()
        ranking = [n.strip() for n in os.environ.get("RECHECK_PROXY_ORDER", "").split(",") if n.strip()]
        worked = load_worked(os.environ.get("RECHECK_PREVIOUS", ""))
        orders = {e: proxy_order(e, proxies, geo, ranking, worked) for e in exts}
        tries = max(1, int(os.environ.get("RECHECK_MAX_TRIES") or 3))
        pending = [e for e in exts if orders[e]]
        for rnd in range(tries):
            groups: dict[str, list[str]] = {}
            for e in pending:
                if rnd < len(orders[e]):
                    groups.setdefault(orders[e][rnd]["name"], []).append(e)
            pending = []
            for name, group in groups.items():
                proxy = next(p for p in proxies if p["name"] == name)
                run_dir = work / f"r{rnd}-{name}"
                rows = run_group(proxy, group, wt, dist, run_dir)
                for e in group:
                    a = attempt(wt, run_dir, e, *rows.get(e, ("error", "")), proxy["cc"])
                    result.setdefault(e, []).append(a)
                    if a["status"] in ("maybe", "error"):
                        pending.append(e)
            if not pending:
                break
    (out / "recheck.json").write_text(json.dumps(result, indent=1))
    passed = sum(any(a["status"] == "pass" for a in tries) for tries in result.values())
    log(f"done: {passed} of {len(exts)} pass through a proxy")


if __name__ == "__main__":
    main()
