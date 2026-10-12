#!/usr/bin/env python3
"""The recheck node's HOST-side controller. Runs OUTSIDE the sandbox, as your own user — nothing
here needs root, and nothing here is a GitHub Actions runner: it never registers with GitHub,
never accepts a job GitHub pushes to it. It only ever polls a public URL and, if there's new
work, runs one ephemeral, locked-down container and reports back once.

    controller.py --config /path/to/config.json [--once] [--teardown]

See node/README.md for the full setup (config file fields, GitHub App, network model) and
node/compose.yml for the egress-proxy + sandbox network topology this script drives via
`docker compose`. Intended to be invoked by a systemd user timer or cron entry
(node/systemd/*, node/cron.example) — each invocation does at most one recheck run and exits.
"""
import argparse
import base64
import datetime as dt
import gzip
import hashlib
import json
import os
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "publisher"))
from node_schema import MAX_PAYLOAD_BYTES, validate_node_payload, validate_recheck_manifest  # noqa: E402

# GitHub's workflow_dispatch input value limit (documented max 1000 ASCII characters per input,
# and 10 inputs). Our payload is one input, base64 of gzipped JSON — stay well clear of either
# boundary; 300 category-only rows gzip+base64 to a few KB, nowhere near this, so if it ever is
# close, that's a sign RECHECK_MAX_EXTENSIONS was turned up with no corresponding re-check here.
GITHUB_DISPATCH_INPUT_MAX = 65_000  # GitHub's actual documented ceiling for one input's length


def log(*a):
    print(*a, file=sys.stderr, flush=True)


def load_config(path: Path) -> dict:
    mode = path.stat().st_mode & 0o777
    if mode & 0o077:
        sys.exit(f"refusing to read {path}: mode {oct(mode)} is readable/writable by group or other; chmod 600 it")
    cfg = json.loads(path.read_text())
    for k in ("pages_url", "repo", "proxy_host", "proxy_port"):
        if k not in cfg:
            sys.exit(f"config is missing required field: {k}")
    if bool(cfg.get("pat")) == bool(cfg.get("app_id")):
        sys.exit("config needs exactly one of: app_id + app_private_key_file (a GitHub App), or pat")
    if cfg.get("app_id"):
        if not cfg.get("app_private_key_file"):
            sys.exit("config has app_id but no app_private_key_file")
        key = Path(cfg["app_private_key_file"]).expanduser()
        if key.stat().st_mode & 0o077:
            sys.exit(f"refusing to use {key}: readable/writable by group or other; chmod 600 it")
        cfg["app_private_key_file"] = str(key)
    return cfg


def _b64url(b: bytes) -> str:
    return base64.urlsafe_b64encode(b).rstrip(b"=").decode()


def _github_api(method: str, path: str, token: str, body: dict | None = None) -> dict:
    req = urllib.request.Request(
        f"https://api.github.com{path}", method=method,
        data=json.dumps(body).encode() if body is not None else None,
        headers={"Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json",
                 "Content-Type": "application/json", "X-GitHub-Api-Version": "2022-11-28"},
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        sys.exit(f"GitHub API {method} {path} failed: {e.code} {e.read().decode(errors='replace')[:300]}")


def app_token(cfg: dict) -> str:
    """An hour-long installation token for this one repo, Actions write only, minted from the
    GitHub App's private key. Signed with the openssl CLI so the controller stays stdlib-only."""
    now = int(time.time())
    head = _b64url(json.dumps({"alg": "RS256", "typ": "JWT"}).encode())
    claims = _b64url(json.dumps({"iat": now - 60, "exp": now + 540, "iss": str(cfg["app_id"])}).encode())
    sig = subprocess.run(["openssl", "dgst", "-sha256", "-sign", cfg["app_private_key_file"]],
                         input=f"{head}.{claims}".encode(), capture_output=True, check=True).stdout
    jwt = f"{head}.{claims}.{_b64url(sig)}"
    inst = _github_api("GET", f"/repos/{cfg['repo']}/installation", jwt)["id"]
    return _github_api("POST", f"/app/installations/{inst}/access_tokens", jwt, {
        "repositories": [cfg["repo"].split("/", 1)[1]],
        "permissions": {"actions": "write"},
    })["token"]


def github_token(cfg: dict) -> str:
    return app_token(cfg) if cfg.get("app_id") else cfg["pat"]


def state_path(cfg: dict) -> Path:
    return Path(cfg.get("state_file") or (HERE / ".last_run_id"))


def fetch_bytes(url: str, max_bytes: int | None = None) -> bytes:
    """GET url, streamed in chunks rather than one unbounded r.read(). If max_bytes is given,
    aborts as soon as the response would exceed it — a malicious or compromised server can send
    more bytes than any Content-Length header claims, so the cap has to be enforced while
    reading, not just checked against the final length after an unbounded read already
    happened."""
    with urllib.request.urlopen(url, timeout=60) as r:
        if max_bytes is None:
            return r.read()
        chunks = []
        total = 0
        while True:
            chunk = r.read(1 << 20)
            if not chunk:
                break
            total += len(chunk)
            if total > max_bytes:
                sys.exit(f"response from {url} exceeded the {max_bytes}-byte cap while streaming; refusing")
            chunks.append(chunk)
        return b"".join(chunks)


def run(cmd, **kw):
    log("+", " ".join(cmd))
    return subprocess.run(cmd, check=True, **kw)


COMPOSE_PROJECT = "keiyoushi-monitor-node"  # fixed, so the network name below is predictable
SECCOMP_PROFILE = HERE / "seccomp" / "chrome-userns.json"
OWN_IP_CONF = HERE / "proxy" / "own-ip.generated.conf"
UPSTREAM_CONF = HERE / "proxy" / "upstream.generated.conf"
UPSTREAM_COMPOSE = HERE / "proxy" / "compose.upstream.generated.yml"
DIRECT_PORT = 3129  # with upstream_proxy + direct_fallback: the proxy port that skips the upstream


# ---------------------------------------------------------------------------
# Chrome's own sandbox needs unprivileged user-namespace creation. Check the two host-level
# knobs that can disable that, rather than assuming; fall back to --no-sandbox (honestly, loudly)
# if the kernel itself won't allow it. See node/seccomp/README.md for how chrome-userns.json was
# derived and node/README.md for what it does and doesn't grant.
# ---------------------------------------------------------------------------
def _read_sysctl(path: str) -> str | None:
    try:
        return Path(path).read_text().strip()
    except OSError:
        return None


def chrome_sandbox_supported() -> tuple[bool, str]:
    userns = _read_sysctl("/proc/sys/kernel/unprivileged_userns_clone")
    restrict = _read_sysctl("/proc/sys/kernel/apparmor_restrict_unprivileged_userns")
    if userns == "0":
        return False, "kernel.unprivileged_userns_clone=0 on this host: unprivileged user namespaces are disabled outright"
    # apparmor_restrict_unprivileged_userns=1 alone doesn't block it here — Docker's own
    # docker-default AppArmor profile already permits userns creation for unconfined processes on
    # the Ubuntu kernels that set this; confirmed empirically (see node/seccomp/README.md). Only
    # treat it as informational.
    detail = f"unprivileged_userns_clone={userns or '<missing>'}, apparmor_restrict_unprivileged_userns={restrict or '<missing>'}"
    return True, detail


def ensure_proxy(cfg: dict):
    """Idempotent: creates the internal sandbox network + egress network + proxy container if
    they don't already exist. Safe to call every poll. Also refreshes the own-public-IP deny ACL
    and makes squid reload it — a bind-mounted file's new contents don't take effect until
    squid is told to reconfigure."""
    changed = refresh_own_ip_deny(cfg)
    changed = write_upstream(cfg) or changed
    was_running = _proxy_running()
    run(["docker", "compose", "-p", COMPOSE_PROJECT, *compose_files(), "up", "-d", "proxy"],
        cwd=str(HERE))
    # A recreated container is a fresh squid that reads its config on start (and has no pid file
    # to signal yet); only one that kept running needs telling.
    if changed and was_running and _proxy_running() == was_running:
        run(["docker", "exec", f"{COMPOSE_PROJECT}-proxy-1", "squid", "-k", "reconfigure"])
    verify_sandbox_network_isolated()


def compose_files() -> list:
    files = ["-f", str(HERE / "compose.yml")]
    if UPSTREAM_COMPOSE.exists():
        files += ["-f", str(UPSTREAM_COMPOSE)]
    return files


def write_upstream(cfg: dict) -> bool:
    """upstream_proxy ("host:port" of another HTTP proxy) sends the sandbox's traffic
    through it; upstream_network is the Docker network the proxy joins to reach it. With
    direct_fallback, a second proxy port goes out directly, for a retry of what didn't pass.
    Returns whether the squid side changed."""
    up = cfg.get("upstream_proxy")
    conf = ""
    if up:
        host, port = up.rsplit(":", 1)
        if not re.fullmatch(r"[A-Za-z0-9._-]+", host) or not port.isdigit():
            sys.exit(f"upstream_proxy {up!r} isn't host:port")
        conf = (f"acl via_upstream localport {cfg['proxy_port']}\n"
                f"cache_peer {host} parent {port} 0 no-query no-digest default name=upstream\n"
                "cache_peer_access upstream allow via_upstream\n"
                "cache_peer_access upstream deny all\n"
                "never_direct allow via_upstream\n")
        if cfg.get("direct_fallback"):
            conf += f"http_port {DIRECT_PORT}\n"
    net = cfg.get("upstream_network") if up else None
    if net and not re.fullmatch(r"[A-Za-z0-9._-]+", net):
        sys.exit(f"upstream_network {net!r} isn't a Docker network name")
    compose = (f"networks:\n  upstream:\n    external: true\n    name: {net}\n"
               "services:\n  proxy:\n    networks:\n      upstream: {}\n") if net else None
    old = UPSTREAM_CONF.read_text() if UPSTREAM_CONF.exists() else None
    UPSTREAM_CONF.write_text(conf)
    if compose:
        UPSTREAM_COMPOSE.write_text(compose)
    else:
        UPSTREAM_COMPOSE.unlink(missing_ok=True)
    return old != conf


def verify_sandbox_network_isolated():
    """The sandbox-net's whole point is: no route to the outside world, no route to the host's
    LAN, and no route to the host itself via the bridge's own gateway IP — see node/compose.yml's
    header comment for how that's set up (`internal: true` PLUS `gateway_mode_*: isolated`,
    which needs Docker >= 28). Checked for real here, every poll, rather than trusted from the
    compose file alone: an older Docker silently ignores an option it doesn't recognize, so the
    network would still come up — just not actually isolated the way this whole design assumes.
    Refuses to run anything if this isn't true right now."""
    net = f"{COMPOSE_PROJECT}_sandbox-net"
    r = subprocess.run(["docker", "network", "inspect", net], capture_output=True, text=True)
    if r.returncode != 0:
        sys.exit(f"could not inspect docker network {net!r} (not created yet?): {r.stderr.strip()}")
    try:
        info = json.loads(r.stdout)[0]
    except (ValueError, IndexError, KeyError) as e:
        sys.exit(f"could not parse `docker network inspect {net}` output: {e}")
    if not info.get("Internal"):
        sys.exit(f"docker network {net!r} is not Internal; refusing to run the sandbox on it")
    gateways = [c.get("Gateway") for c in info.get("IPAM", {}).get("Config", []) if c.get("Gateway")]
    if gateways:
        sys.exit(f"docker network {net!r} still has a Gateway ({gateways}) — gateway_mode_ipv4/"
                  "ipv6: isolated did not take effect (this needs Docker >= 28); refusing to run "
                  "the sandbox on a network the host itself is reachable from")
    modes = info.get("Options", {})
    for opt in ("com.docker.network.bridge.gateway_mode_ipv4", "com.docker.network.bridge.gateway_mode_ipv6"):
        if modes.get(opt) and modes.get(opt) != "isolated":
            sys.exit(f"docker network {net!r} has {opt}={modes.get(opt)!r}, not 'isolated'; refusing to run the sandbox")
    log(f"sandbox network isolation verified: {net} is Internal with no Gateway")


def _proxy_running() -> str | None:
    """The running proxy container's id, or None."""
    r = subprocess.run(["docker", "inspect", "-f", "{{.State.Running}} {{.Id}}", f"{COMPOSE_PROJECT}-proxy-1"],
                        capture_output=True, text=True)
    running, _, cid = r.stdout.strip().partition(" ")
    return cid if r.returncode == 0 and running == "true" else None


def refresh_own_ip_deny(cfg: dict) -> bool:
    """look up this node's own public IPv4 (and, if reachable, IPv6) and deny them at the
    proxy — otherwise a request through the proxy could hairpin straight back out to whatever
    this node's own public IP also exposes (e.g. a port-forwarded service on the node owner's
    router). Looked up fresh on every poll (an IP can change); written to a gitignored include
    file that is NEVER logged and NEVER committed. Returns True if the file's contents changed.

    Fails CLOSED: if the lookup can't resolve any address this poll (ipify unreachable, etc.),
    this leaves whatever own-IP deny rule is already on disk from a previous successful poll
    untouched, rather than overwriting it with an empty (no-op) ACL — a transient lookup failure
    must never silently widen what the proxy will let through. own_ip_rule_present() is the
    actual gate `one_pass()` checks before ever starting the sandbox."""
    if cfg.get("skip_own_ip_lookup"):
        log("skip_own_ip_lookup is set; proxy/own-ip.generated.conf left as-is (documented required-config fallback)")
        return False
    v4 = v6 = None
    try:
        v4 = fetch_bytes("https://api.ipify.org", max_bytes=256).decode().strip()
    except Exception as e:
        log(f"own-IP v4 lookup failed: {e}")
    try:
        v6 = fetch_bytes("https://api6.ipify.org", max_bytes=256).decode().strip()
        if v6 == v4:
            v6 = None  # api6 falls back to v4 when the host has no v6 route; not a real v6 addr
    except Exception:
        pass

    v4_ok = bool(v4 and re.match(r"^\d{1,3}(\.\d{1,3}){3}$", v4))
    v6_ok = bool(v6 and ":" in v6)
    if not v4_ok and not v6_ok:
        log("own-IP lookup resolved no address this poll; keeping the previous "
            "own-ip.generated.conf as-is (fail closed, not overwriting it with an empty ACL)")
        return False

    lines = [
        "# Rewritten by node/controller.py at the start of every poll (ensure_proxy()) — never\n",
        "# hand-edit, never commit real contents, never log this file's contents.\n",
    ]
    if v4_ok:
        lines.append(f"acl own_public_ip dst {v4}/32\n")
        lines.append("http_access deny own_public_ip\n")
    if v6_ok:
        lines.append(f"acl own_public_ip6 dst {v6}/128\n")
        lines.append("http_access deny own_public_ip6\n")
    new_content = "".join(lines)

    old_content = OWN_IP_CONF.read_text() if OWN_IP_CONF.exists() else None
    if new_content == old_content:
        return False
    OWN_IP_CONF.write_text(new_content)
    os.chmod(OWN_IP_CONF, 0o644)
    log("refreshed proxy/own-ip.generated.conf (own public IP(s) — not logged)")
    return True


def own_ip_rule_present(cfg: dict) -> bool:
    """The actual run/no-run gate: is there at least one own-IP deny ACL on disk right now?
    Checked right before run_sandbox(), not just logged at refresh time — a node that has never
    once completed a successful lookup (fresh install, or ipify unreachable since setup) must
    not run the sandbox with no own-IP protection in place at all, unless the operator has
    explicitly opted into that via skip_own_ip_lookup."""
    if cfg.get("skip_own_ip_lookup"):
        return True
    return OWN_IP_CONF.exists() and "http_access deny own_public_ip" in OWN_IP_CONF.read_text()


def teardown(cfg: dict):
    run(["docker", "compose", "-p", COMPOSE_PROJECT, *compose_files(), "down"], cwd=str(HERE))


# ---------------------------------------------------------------------------
# Never trust recheck.json's nodeImage blindly.
# ---------------------------------------------------------------------------
def validate_node_image(cfg: dict, image: str) -> None:
    repo_slug = cfg["repo"].lower()
    pattern = re.compile(rf"^ghcr\.io/{re.escape(repo_slug)}-node@sha256:[0-9a-f]{{64}}$")
    if not pattern.match(image):
        sys.exit(f"recheck.json nodeImage {image!r} does not match the expected "
                  f"ghcr.io/{repo_slug}-node@sha256:<64-hex> for configured repo {cfg['repo']!r}; refusing to run it")


def verify_image_attestation(cfg: dict, image: str) -> None:
    if not cfg.get("verify_attestation", True):
        log("verify_attestation is disabled in config; skipping (NOT recommended)")
        return
    gh = cfg.get("gh_path") or shutil.which("gh")
    if not gh:
        sys.exit(
            "verify_attestation is enabled (the default) but the `gh` CLI is not installed, so "
            "this node cannot verify the node image's build-provenance attestation before "
            "running it. Install `gh` 2.49 or newer (or point gh_path at one), or explicitly set "
            '"verify_attestation": false in controller.config.json if you accept running an '
            "unverified image (not recommended)."
        )
    # --signer-workflow/--source-ref pin this to an attestation actually signed by
    # node-image.yml running on main in THIS repo — not just any attestation gh's lookup happens
    # to accept for the digest. --deny-self-hosted-runners refuses one signed on a self-hosted
    # runner (this repo never registers one, but a fork's config could) rather than trusting a
    # GitHub-hosted-runner build is where this digest actually came from. `gh` won't run without
    # a token even for a public repo; it gets the node's own (GH_TOKEN, a throwaway config dir)
    # rather than whatever `gh auth login` left on this machine.
    with tempfile.TemporaryDirectory() as ghcfg:
        env = {**os.environ, "GH_TOKEN": github_token(cfg), "GH_CONFIG_DIR": ghcfg}
        env.pop("GITHUB_TOKEN", None)
        _verify(gh, cfg, image, env)


def _verify(gh: str, cfg: dict, image: str, env: dict) -> None:
    run([gh, "attestation", "verify", f"oci://{image}", "--repo", cfg["repo"],
         "--signer-workflow", f"{cfg['repo']}/.github/workflows/node-image.yml",
         "--source-ref", "refs/heads/main", "--deny-self-hosted-runners"], env=env)


# ---------------------------------------------------------------------------
# The result crosses the sandbox boundary on stdout, between RESULT_BEGIN/RESULT_END marker
# lines (see node/entrypoint.sh) — not a writable volume. The container is run with
# `--log-driver json-file --log-opt max-size=512k --log-opt max-file=1`, so whatever ends up in
# `docker logs` is already bounded at the Docker daemon level before this process ever reads it;
# `--ulimit fsize` bounds any single file the sandboxed process writes under /work as a second,
# independent cap. There is no /output mount, no docker cp, and nothing for a runaway write to
# fill on the host — the daemon drops/rotates the log itself once it hits max-size.
# ---------------------------------------------------------------------------
RESULT_BEGIN = "===KEI-RESULT-BEGIN==="
RESULT_END = "===KEI-RESULT-END==="
MAX_LOG_BYTES = 2 * 1024 * 1024  # generous relative to the 512k log cap above; a hard backstop


def extract_marked_json(log_text: str, max_bytes: int) -> bytes:
    if len(log_text.encode()) > max_bytes:
        sys.exit(f"sandbox log output is over the {max_bytes}-byte cap; refusing")
    start = log_text.find(RESULT_BEGIN)
    end = log_text.find(RESULT_END)
    if start == -1 or end == -1 or end < start:
        sys.exit("sandbox log output has no well-formed RESULT_BEGIN/RESULT_END region; "
                 "treating as a hard failure")
    body = log_text[start + len(RESULT_BEGIN):end].strip()
    if not body:
        sys.exit("sandbox produced an empty result between the markers; treating as a hard failure")
    return body.encode()


COOKIE_FILE = "clearances.json"
COOKIE_MAX_BYTES = 256 * 1024


def cookie_dir(cfg: dict, route: str) -> Path | None:
    """With keep_cookies, the Cloudflare clearances the sandbox's WebView earns are kept between
    runs, one folder per route (they're bound to the IP they were earned from), as the app keeps
    its cookies. The host never parses the file; it only keeps the folder down to that one file."""
    if not cfg.get("keep_cookies"):
        return None
    d = Path(cfg.get("cookie_dir") or state_path(cfg).parent).expanduser() / f"cloudflare-{route}"
    d.mkdir(parents=True, exist_ok=True)
    d.chmod(0o777)  # the sandbox runs as uid 10001
    tidy_cookie_dir(d)
    return d


def tidy_cookie_dir(d: Path) -> None:
    for e in d.iterdir():
        keep = (e.name == COOKIE_FILE and not e.is_symlink() and e.is_file()
                and e.stat().st_size <= COOKIE_MAX_BYTES)
        if keep:
            continue
        if e.is_dir() and not e.is_symlink():
            shutil.rmtree(e)
        else:
            e.unlink()
        log(f"removed {e.name!r} from {d.name}")


def run_sandbox(cfg: dict, manifest: dict, input_dir: Path, proxy_port: int | None = None,
                cookies: Path | None = None, shard: int = 0) -> bytes:
    """Callers must have run validate_node_image() and verify_image_attestation() on
    manifest["nodeImage"] first (sandbox_pass() does, once for all its shards)."""
    image = manifest["nodeImage"]  # e.g. ghcr.io/<owner>/<repo>-node@sha256:...
    port = proxy_port or cfg["proxy_port"]

    mem = cfg.get("mem_limit", "1500m")
    pids = str(cfg.get("pids_limit", 256))
    cpus = str(cfg.get("cpus", "2"))
    proxy_host = cfg["proxy_host"]
    proxy_url = f"http://{proxy_host}:{port}"

    # Chrome's own sandbox needs user namespaces, which means a widened seccomp profile for the whole
    # container (see node/seccomp/README.md), so it's opt-in.
    want_sandbox = bool(cfg.get("chrome_sandbox", False))
    sandbox_ok, sandbox_detail = chrome_sandbox_supported() if want_sandbox else (False, "chrome_sandbox is off in the config")
    env = {"EXT_RUN_WV_PROXY": proxy_url,
           "JAVA_PROXY_OPTS": f"-Dhttps.proxyHost={proxy_host} -Dhttps.proxyPort={port} "
                               f"-Dhttp.proxyHost={proxy_host} -Dhttp.proxyPort={port}"}
    security_opts = ["no-new-privileges"]
    if sandbox_ok and SECCOMP_PROFILE.exists():
        security_opts.append(f"seccomp={SECCOMP_PROFILE}")
        env["EXT_RUN_WV_CHROME_SANDBOX"] = "1"
        log(f"Chrome sandbox: on (custom seccomp profile; {sandbox_detail})")
    else:
        env["EXT_RUN_WV_CHROME_SANDBOX"] = "0"
        log(f"Chrome sandbox: off, --no-sandbox with Docker's default seccomp ({sandbox_detail if not sandbox_ok else 'no seccomp profile found'}); "
            "the container is still the isolation boundary (non-root, cap-drop ALL, read-only, "
            "no direct network route); see node/README.md")

    cmd = ["docker", "run",   # NOT --rm: with --rm, the container (and its logs) are gone the
                               # instant it exits, before `docker logs` below ever gets to read
                               # them. Removed explicitly in the `finally:` below instead, after
                               # that read.
           "--network", f"{COMPOSE_PROJECT}_sandbox-net",
           "--dns", "127.0.0.1", "--dns-search", ".",  # never expose the host's real
                                                        # resolvers/search domain to the sandbox;
                                                        # the "proxy" alias still resolves via
                                                        # Docker's embedded DNS (127.0.0.11),
                                                        # which --dns only changes the UPSTREAM
                                                        # forwarder for, not container-name lookups
           "--read-only",
           "--tmpfs", "/work:rw,size=2g,mode=1777",
           "--tmpfs", "/tmp:rw,exec,size=512m,mode=1777",  # exec: the JVM unpacks native libraries here and loads them
           "--tmpfs", "/home:rw,size=1g,mode=1777",
           # The result crosses on stdout (see RESULT_BEGIN/RESULT_END above), not a writable
           # mount — no /output volume, no docker cp, nothing for a runaway write to fill on the
           # host. The log driver itself caps what `docker logs` can ever return, independent of
           # anything this process does; --ulimit fsize is a second, independent cap on any
           # single file the process writes under /work.
           "--log-driver", "json-file", "--log-opt", "max-size=512k", "--log-opt", "max-file=1",
           "--ulimit", "fsize=16777216"]
    for k in security_opts:
        cmd += ["--security-opt", k]
    cmd += ["--cap-drop", "ALL", "--user", "10001:10001",
            "--memory", mem, "--pids-limit", pids, "--cpus", cpus]
    for k, v in env.items():
        cmd += ["-e", f"{k}={v}"]
    if cookies:
        cmd += ["-v", f"{cookies}:/cf:rw", "-e", "EXT_RUN_CF_DIR=/cf"]
    cmd += ["-v", f"{input_dir}:/input:ro",
            "--name", f"{COMPOSE_PROJECT}-sandbox-run-{shard}",
            "--",
            image]
    # The app key, any token, the repo, the Pages URL — nothing from `cfg` is ever passed to this container.
    # Only EXT_RUN_WV_PROXY / JAVA_PROXY_OPTS (where to send traffic) cross that boundary.
    name = f"{COMPOSE_PROJECT}-sandbox-run-{shard}"
    subprocess.run(["docker", "rm", "-f", name], capture_output=True)  # stale leftover from a
                                                                        # crashed previous run
    try:
        run(cmd)
        logs = subprocess.run(["docker", "logs", name], capture_output=True, text=True,
                               errors="replace")
        return extract_marked_json(logs.stdout, MAX_LOG_BYTES)
    finally:
        subprocess.run(["docker", "rm", "-f", name], capture_output=True)


def dispatch_ingest(cfg: dict, payload: dict):
    body = json.dumps(payload, separators=(",", ":")).encode()
    compact = base64.b64encode(gzip.compress(body)).decode()
    if len(compact) > GITHUB_DISPATCH_INPUT_MAX:
        sys.exit(f"compacted payload is {len(compact)} chars, over GitHub's workflow_dispatch input limit")
    owner_repo = cfg["repo"]
    url = f"https://api.github.com/repos/{owner_repo}/actions/workflows/ingest.yml/dispatches"
    req = urllib.request.Request(
        url, method="POST",
        data=json.dumps({"ref": "main", "inputs": {"payload_gz_b64": compact}}).encode(),
        headers={
            "Authorization": f"Bearer {github_token(cfg)}",
            "Accept": "application/vnd.github+json",
            "Content-Type": "application/json",
        },
    )
    try:
        urllib.request.urlopen(req, timeout=30)
    except urllib.error.HTTPError as e:
        sys.exit(f"ingest dispatch failed: {e.code} {e.read().decode(errors='replace')}")
    log(f"dispatched ingest.yml with a {len(compact)}-char payload")


def sandbox_pass(cfg, manifest, input_dir, allowed_ids, run_date, proxy_port=None) -> dict:
    """Runs the extensions in input_dir/recheck.json, split across `parallel` sandboxes (default 1),
    each with its own input folder and its own slice of the list."""
    validate_node_image(cfg, manifest["nodeImage"])
    verify_image_attestation(cfg, manifest["nodeImage"])
    route = "direct" if proxy_port == DIRECT_PORT or not cfg.get("upstream_proxy") else "upstream"
    cookies = cookie_dir(cfg, route)
    listed = json.loads((input_dir / "recheck.json").read_text())
    ids = listed["extensions"]
    n = max(1, min(int(cfg.get("parallel", 1)), len(ids)))
    shards = []
    for i in range(n):
        d = input_dir.parent / f"{input_dir.name}-{route}-{i}"
        shutil.rmtree(d, ignore_errors=True)
        d.mkdir(mode=0o755)
        (d / "recheck.json").write_text(json.dumps({**listed, "extensions": ids[i::n]}))
        os.link(input_dir / "bundle.tar.zst", d / "bundle.tar.zst")
        (d / "recheck.json").chmod(0o444)
        shards.append((i, d, set(ids[i::n])))
    log(f"{len(ids)} extension(s) across {n} sandbox(es), {route} route")
    try:
        with ThreadPoolExecutor(n) as pool:
            payloads = list(pool.map(lambda sh: run_sandbox(cfg, manifest, sh[1], proxy_port, cookies, sh[0]), shards))
    finally:
        for _, d, _ in shards:
            shutil.rmtree(d, ignore_errors=True)
        if cookies:
            tidy_cookie_dir(cookies)
    results, run_id = [], None
    for (_, _, shard_ids), payload_bytes in zip(shards, payloads):
        if len(payload_bytes) > MAX_PAYLOAD_BYTES:
            sys.exit(f"sandbox result is {len(payload_bytes)} bytes, over the {MAX_PAYLOAD_BYTES}-byte cap")
        v = validate_node_payload(json.loads(payload_bytes), shard_ids & allowed_ids, run_date=run_date)
        if v["runId"] != manifest["runId"]:
            sys.exit(f"a sandbox reported run {v['runId']}, not {manifest['runId']}; refusing")
        run_id = v["runId"]
        results += v["results"]
    log(f"validated {len(results)} row(s) for run {run_id}")
    return {**v, "results": results}


RUNTIME_PATHS = ("harness/runtime/", "harness/ci/build-runtime.sh")


def image_revision(image: str) -> str:
    """The commit a node image was built from (node-image.yml's revision label)."""
    out = subprocess.run(["docker", "buildx", "imagetools", "inspect", image, "--format", "{{json .}}"],
                         capture_output=True, text=True, check=True)
    img = json.loads(out.stdout).get("image") or {}
    conf = img.get("config") if "config" in img else next(iter(img.values()), {}).get("config", {})
    commit = (conf or {}).get("Labels", {}).get("org.opencontainers.image.revision", "")
    if not re.fullmatch(r"[0-9a-f]{40}", commit):
        sys.exit(f"{image} has no revision label; refusing")
    return commit


def same_runtime(cfg: dict, base: str, head: str) -> None:
    """Refuse unless nothing the baked-in harness runtime is built from changed between commits."""
    cmp = _github_api("GET", f"/repos/{cfg['repo']}/compare/{base}...{head}", github_token(cfg))
    if cmp.get("status") not in ("ahead", "identical"):
        sys.exit(f"{head[:7]} isn't ahead of {base[:7]} on {cfg['repo']}; refusing")
    if len(cmp.get("files", [])) >= 300:
        sys.exit("too many changed files to check the runtime is unchanged; refusing")
    changed = [f["filename"] for f in cmp.get("files", []) if f["filename"].startswith(RUNTIME_PATHS)]
    if changed:
        sys.exit(f"the harness runtime changed between {base[:7]} and {head[:7]} ({', '.join(changed[:5])}); "
                 "this image can't run that recheck.json")


def one_pass(cfg: dict, force: bool = False, image: str | None = None) -> bool:
    # Fetch recheck.json ONCE, validate it, and use exactly those bytes from here on — never
    # re-fetch it (or the bundle) a second time to "double check"; a second fetch could silently
    # observe a different (e.g. just-republished) version than the one actually validated.
    manifest_bytes = fetch_bytes(cfg["pages_url"].rstrip("/") + "/recheck.json", max_bytes=2 * 1024 * 1024)
    manifest = json.loads(manifest_bytes)
    validate_recheck_manifest(manifest)
    if image:
        # A newer node image for the same run (e.g. a fix landed after the sweep). It still has to
        # pass validate_node_image() and the attestation check in run_sandbox(), and the image's
        # own runtime-commit check against recheck.json.
        validate_node_image(cfg, image)
        commit = image_revision(image)
        same_runtime(cfg, manifest["harnessRuntimeCommit"], commit)
        log(f"using {image} instead of recheck.json's {manifest['nodeImage']}")
        manifest = {**manifest, "nodeImage": image, "harnessRuntimeCommit": commit}
        manifest_bytes = json.dumps(manifest).encode()

    last = state_path(cfg)
    if not force and last.exists() and last.read_text().strip() == manifest["runId"]:
        log(f"runId {manifest['runId']} already processed; nothing to do")
        return False

    allowed_ids = set(manifest["extensions"])
    run_date = dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%d")

    ensure_proxy(cfg)
    if not own_ip_rule_present(cfg):
        sys.exit("no own-IP deny rule is in place at the proxy (lookup never succeeded) and "
                 "skip_own_ip_lookup is not set; refusing to run the sandbox — see "
                 "refresh_own_ip_deny()/own_ip_rule_present() in node/controller.py")

    with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as tmp:
        tmp = Path(tmp)
        input_dir = tmp / "input"
        input_dir.mkdir(mode=0o755)

        (input_dir / "recheck.json").write_bytes(manifest_bytes)

        # Fetch the bundle ONCE from the URL recheck.json itself pins, verify it against the
        # checksum/size recheck.json itself pins, and only then write it to /input — the sandbox
        # never re-fetches it over the network at all (node/entrypoint.sh + extract_bundle.py
        # re-verify the checksum again locally, which is just re-hashing the same bytes already
        # on disk, not a second network round-trip).
        bundle_bytes = fetch_bytes(manifest["bundleUrl"], max_bytes=manifest["bundleSize"])
        actual_size = len(bundle_bytes)
        actual_sha = hashlib.sha256(bundle_bytes).hexdigest()
        if actual_size != manifest["bundleSize"]:
            sys.exit(f"bundle at {manifest['bundleUrl']} is {actual_size} bytes, recheck.json says {manifest['bundleSize']}; refusing")
        if actual_sha != manifest["bundleSha256"]:
            sys.exit(f"bundle at {manifest['bundleUrl']} has sha256 {actual_sha}, recheck.json says {manifest['bundleSha256']}; refusing")
        (input_dir / "bundle.tar.zst").write_bytes(bundle_bytes)

        for f in input_dir.iterdir():
            os.chmod(f, stat.S_IRUSR | stat.S_IRGRP | stat.S_IROTH)

        validated = sandbox_pass(cfg, manifest, input_dir, allowed_ids, run_date)
        if cfg.get("upstream_proxy") and cfg.get("direct_fallback"):
            # Same as the daily sweep: whatever didn't pass through the upstream gets one more go
            # going out directly, and that result is the one reported.
            retry = sorted(r["id"] for r in validated["results"] if r["category"] != "pass")
            if retry:
                log(f"{len(retry)} didn't pass through upstream_proxy; retrying them directly")
                (input_dir / "recheck.json").chmod(0o644)
                (input_dir / "recheck.json").write_text(json.dumps({**manifest, "extensions": retry}))
                (input_dir / "recheck.json").chmod(0o444)
                direct = sandbox_pass(cfg, manifest, input_dir, set(retry), run_date, DIRECT_PORT)
                by_id = {r["id"]: r for r in direct["results"]}
                validated["results"] = [by_id.get(r["id"], r) for r in validated["results"]]
                passed = sum(r["category"] == "pass" for r in direct["results"])
                log(f"direct retry: {passed} of {len(retry)} passed")

    dispatch_ingest(cfg, validated)
    last.write_text(manifest["runId"])
    return True


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--config", required=True, type=Path)
    ap.add_argument("--once", action="store_true", help="process even if this runId was already handled")
    ap.add_argument("--teardown", action="store_true", help="stop the proxy/sandbox networks and exit")
    ap.add_argument("--image", help="run this node image (ghcr.io/<owner>/<repo>-node@sha256:...) instead of the one recheck.json names")
    args = ap.parse_args()

    cfg = load_config(args.config)
    if args.teardown:
        teardown(cfg)
        return
    one_pass(cfg, force=args.once, image=args.image)


if __name__ == "__main__":
    main()
