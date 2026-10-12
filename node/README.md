# Recheck node

A small slice of sources (Cloudflare-protected, or sites that reject datacenter IPs outright)
fail the daily GitHub-hosted sweep even when the extension itself is fine. A recheck node
re-runs just those flagged extensions from a different network, and — on a pass — that result
replaces the GitHub-hosted one. It's entirely optional; without it the pipeline still runs and
publishes, it just reports a few more "can't test" extensions.

**There is no GitHub Actions self-hosted runner anywhere in this design.** GitHub never sends
this machine a job. Instead:

1. The GitHub-hosted `build-site` job writes a public `recheck.json` (listing the flagged
   extensions, the exact node image digest, and the recheck bundle's URL + checksum + size) to
   `gh-pages`; `deploy` pushes it and uploads the bundle itself as a GitHub Release asset — never
   to `gh-pages`.
2. `node/controller.py`, running on **your** machine under **your** own user, polls that public
   URL on a schedule. When it sees new work, it runs one locked-down, ephemeral container that
   does the actual rechecking and writes one strictly-validated output file.
3. The controller reads that file, validates it again itself, and — only if it's clean —
   triggers this repo's `ingest` workflow (`workflow_dispatch`) with the result. GitHub does the
   actual publishing; the controller never pushes to `gh-pages` itself.

Nothing here registers a runner, accepts a job GitHub assigns, or gives this machine's Actions
credentials any more power than "dispatch one workflow" — see "The PAT" below for exactly what
that credential can and can't do; it's wider than "dispatch one workflow" alone, and the actual
scope is spelled out there rather than undersold here.

## Why not a self-hosted GitHub Actions runner

An earlier version of this project used one, on the theory that "it's just a recheck job". A
security review found several real, structural problems with that:

- a fork PR's own workflow file can target a self-hosted runner by its labels — no amount of
  "we don't have PR triggers" removes the risk once a repo is public and someone finds another
  way to get a workflow onto the default branch (a maintainer merge, a compromised dependency
  action, etc.) that *does* target the runner;
- the runner's registration token / admin PAT was reachable from job steps, including
  third-party extension code running inside the same job;
- the official runner image ships passwordless sudo by default;
- the container wasn't ephemeral between runs;
- packaging shipped build output with `tar --absolute-names -C /`, which will happily overwrite
  arbitrary absolute paths on whatever extracts it;
- the Chrome instance ran with `--no-sandbox` and a normal route to the LAN.

None of that is fixable by "being careful" — it's what a self-hosted runner *is*. A poll-based
node sidesteps the entire class: GitHub never gets to choose what this container runs or when,
there's no runner registration/removal token to protect, and the container that touches
third-party code never has an API credential in it at all.

## Architecture

```
build-site job (GitHub-hosted, contents: read)
  -> recheck.json            { generated, keiCommit, runId, nodeImage (digest-pinned),
                                extensions[], bundleUrl, bundleSha256, bundleSize,
                                harnessRuntimeCommit }
  -> recheck-bundle.tar.zst  prebuilt classes for just the flagged extensions, relative paths
                              only — uploaded as a build artifact, NOT written to gh-pages
       |
       v
deploy job (GitHub-hosted, contents: write — the only job in status.yml that can push)
  -> pushes recheck.json (+ status.json + site) to gh-pages
  -> uploads the bundle to a rolling "recheck-bundle" GitHub Release asset (never gh-pages)
  -> runs no script against either artifact's contents — download + push/upload only
       |
       v
node/controller.py (your host, your user, no root)
  - GETs recheck.json ONCE; skips if its runId was already handled
  - GETs the bundle from recheck.json's own bundleUrl, verifies it against recheck.json's own
    bundleSha256/bundleSize — all from the one recheck.json fetch, no second round-trip to
    "double check" either value against a different fetch
  - validates nodeImage against this node's configured repo (ghcr.io/<repo>-node@sha256:...,
    nothing else) before ever passing it to `docker run`; verifies its build-provenance
    attestation with `gh attestation verify` (on by default)
  - looks up this node's own public IP and writes/reloads a proxy deny rule for it
  - docker compose up -d proxy          (idempotent; see compose.yml)
  - docker run (the sandbox, read-only, non-root, capped, --dns 127.0.0.1, network =
    sandbox-net only, no --rm so its logs survive long enough for `docker logs` to read them;
    `--log-driver json-file --log-opt max-size=512k --log-opt max-file=1` caps what that can ever
    return, and `--ulimit fsize` caps any single file the sandboxed process writes)
  - reads the result back on the container's own stdout, between fixed RESULT_BEGIN/RESULT_END
    marker lines — not a writable mount — then validates it strictly (ids, categories, a byte
    cap on top of the log driver's own cap, regardless of what the container actually printed)
  - POSTs a workflow_dispatch to ingest.yml with the gzip+base64 result

sandbox container (node/Dockerfile, node/entrypoint.sh)
  - checks /input/recheck.json's harnessRuntimeCommit against this image's own baked-in runtime
    commit (/app/RUNTIME_COMMIT); refuses to run on a mismatch rather than mixing versions
  - verifies the bundle's checksum (again, locally — not a second network fetch) and extracts it
    with path validation under /work/bundle (no absolute paths, no ..)
  - runs harness/ci/run-shard.sh for just the flagged extensions, through the egress proxy,
    against the harness runtime baked into THIS image (/app/runtime-dist) — never a runtime
    shipped inside the bundle
  - node/categorize.py turns the raw result into the category-only schema — the only thing
    that ever leaves this container

ingest.yml (GitHub-hosted, workflow_dispatch only)
  - validate-and-merge (contents: read): re-validates the payload against the exact same schema
    (never trusts the client), requires runDate within +/-1 day of today, merges it into the
    currently-published status.json (node rows only ever override pass/fail/blocked/cloudflare,
    never error/untestable), uploads the result as an artifact
  - deploy (contents: write): downloads that artifact and pushes it — no script runs against its
    contents in this job
```

## Why the bundle is a prebuilt artifact, not built in the sandbox

The design doc offered two options: (a) the sandbox builds the flagged extensions itself from
`extensions-source` at `keiCommit`, or (b) the GitHub-hosted side publishes a prebuilt bundle and
the sandbox only extracts it. This went with **(b)**.

The GitHub-hosted `build` job already compiles every extension in that day's run exactly once —
the flagged subset is always a subset of what it already built. Having the sandbox re-build that
subset with Gradle + the Android Gradle Plugin would mean baking a JDK 17 *and* an Android SDK
(platform + build-tools, with their own license acceptance and download surface) into the
hardened image, giving the sandbox network access to Google's Maven/SDK infrastructure before a
single extension even runs, and re-running a build step the pipeline already paid for once. None
of that makes the recheck any more trustworthy — it just adds attack surface and runtime to an
image that's supposed to be as small and fast-to-audit as possible. Repackaging what's already
built, with the path-safety fix below, is strictly simpler and faster, and the bundle's own
checksum is pinned in `recheck.json` so the node never extracts something it wasn't told to
expect.

That repackaging is also where the review's `tar --absolute-names -C /` finding gets fixed: the
old design's archive was packed and extracted with absolute paths, trusting the extraction site
completely. `harness/ci/pack-recheck-bundle.sh` packs with **no absolute paths at all**
(`tar --transform 's,^/,,'`), and `node/extract_bundle.py` independently re-validates every
member (no leading `/`, no `..` component, must resolve under a FIXED destination this script
picks — `/work/bundle`, never "/") before extracting, on top of Python's own `tarfile` "data"
extraction filter — two independent layers of the same rule. The main CI-side archive
(`harness/ci/build-all.sh` / `extract_build.py`, used by the `build`/`sweep`/`build-site` jobs,
not by the node at all) got the identical fix.

## The harness runtime is baked into the image, not the bundle

The bundle (`recheck-bundle.tar.zst`) carries only the recheck subset's extension classes and
their dependency jars — no JVM runtime at all. `node-image.yml` builds `harness/runtime` itself
(the same Gradle project the GitHub-hosted `build-runtime` job builds) and bakes the resulting
`dist/` into the image at `/app/runtime-dist`, alongside a plain-text `/app/RUNTIME_COMMIT` file
recording which commit of this repo built it. `recheck.json` carries the matching
`harnessRuntimeCommit`, and `node/entrypoint.sh` refuses to run if the two don't match — a node
running an out-of-date image gets a clear, loud error instead of silently running a possibly
API-incompatible runtime against a bundle it wasn't built for.

## The sandbox image (`node/Dockerfile`)

- Base: `debian:stable-slim`, pinned by digest. **Not** the GitHub Actions runner image — no
  `actions-runner` binary, no runner user, no way for this image to register itself with GitHub
  even if someone pointed it at a repo.
- No sudo installed, anywhere.
- Runs as a dedicated non-root uid, created at build time; `node/entrypoint.sh` et al. are owned
  by root and mode 0555, so the non-root runtime user can't modify them even if something inside
  the container were compromised.
- JDK 21 only (the recheck bundle is prebuilt — see above — so there's no Gradle/AGP/Android SDK
  in this image at all), pinned to an exact Adoptium release by URL **and a SHA256 hardcoded in
  the Dockerfile** — not trusted from Adoptium's API response at build time, since a
  compromised/MITM'd API call could lie about its own checksum just as easily as about the
  binary.
- `websockets` (the one Python dependency) installed with `pip install --require-hashes`,
  pinned by the exact sha256 of its one universal wheel.
- Chrome comes from Google's own apt repo (stable channel), same as the GitHub-hosted sweep.
  This is **not** pinned to a specific build — Google doesn't publish old `stable` releases at a
  fixed URL, they get rotated out of the repo entirely, so a pinned version string would make
  every future rebuild fail — so a rebuild always gets whatever is current. Known, accepted
  tradeoff; re-review if that ever changes.
- **Chrome's own sandbox is opt-in** (`"chrome_sandbox": true` in the controller config; off by
  default). It's a tradeoff, not a free win:
  - **Off (default):** Chrome runs with `--no-sandbox` under Docker's default seccomp profile. The
    container (non-root, `--cap-drop ALL`, `no-new-privileges`, read-only, no direct network
    route) is the isolation boundary, the same as on the GitHub-hosted sweep.
  - **On:** Chrome's namespace sandbox needs to create its own user/pid/mount namespaces, which
    Docker's default seccomp profile blocks. `node/seccomp/chrome-userns.json` is Docker's default
    profile plus one rule allowing `unshare`/`setns`/`clone`/`clone3`/`mount`/`pivot_root`/`chroot`.
    That lets every process in the container, including the JVM running extension code, create a
    user namespace and so reach kernel code that's normally only reachable with privileges. Read
    `node/seccomp/README.md` before turning it on; a gVisor (`runsc`) runtime is the stronger option.
  - The controller only uses the profile when the kernel allows unprivileged user namespaces
    (`/proc/sys/kernel/unprivileged_userns_clone`), and logs which mode each run used.

## Running it (`node/controller.py` + `node/compose.yml`)

1. Fork this repo and enable Pages (`gh-pages` branch). Note your Pages URL
   (`https://<owner>.github.io/<repo>`).
2. Build and push the node image once by running `node-image.yml` (it runs automatically on every
   push to `node/**`/`harness/**`/`publisher/node_schema.py`, publishing
   `ghcr.io/<owner>/<repo>-node`, digest-pinned, with an attempted build-provenance attestation.
   **Attestations are not available at all for a private, user-owned (non-org) repository** —
   confirmed by running this: the API call fails with "Feature not available for user-owned
   private repositories." The image still builds and pushes fine either way (the attest step is
   `continue-on-error`); check the run's job summary to see whether this fork actually got one.
   If it's a private, user-owned fork, set `"verify_attestation": false` in
   `controller.config.json` (step 3) — otherwise the controller refuses to run the image at all,
   since it can never find an attestation to verify. Public or organization-owned repos get a
   real one and should leave verification on. `gh attestation verify` only needs read access to
   this repo's attestations, not the node's own PAT — install `gh` and authenticate it with a
   **separate, read-only `GH_TOKEN`** (a classic PAT with no scopes, or a fine-grained PAT with
   just "Metadata: read", works for a public repo; `gh auth login` with a broad interactive
   token is more than this one check needs) rather than reusing the controller's own
   Actions-dispatch PAT for it. **Make the `-node` package public** — your repo's Packages tab
   → the package → Package settings → Change visibility → Public — `controller.py` never runs
   `docker login`, so `docker run` can only pull the image anonymously; a private package (GHCR's
   default) fails the pull with no clearer error than "pull access denied". If you'd rather not
   make it public at all, build the image locally from `node/Dockerfile` instead (see "The sandbox
   image" above) and skip GHCR entirely.
3. `cp node/controller.config.example.json node/controller.config.json`, fill in `pages_url`,
   `repo`, and a **fine-grained PAT** (see below), then `chmod 600 node/controller.config.json`
   — the controller refuses to even read it otherwise.
4. Install the systemd user timer (`node/systemd/`) or the cron entry (`node/cron.example`,
   which uses `flock` so an overrunning or overlapping invocation doesn't start a second one).
   Either just runs `python3 node/controller.py --config node/controller.config.json` on a
   schedule; each run does at most one recheck and exits.
5. `python3 node/controller.py --config node/controller.config.json --once` to try it by hand
   first.

### The PAT

A **fine-grained PAT, scoped to this one repository, with exactly one permission: "Actions: read
and write"** (plus the implicit "Metadata: read" every fine-grained PAT carries — there's no way
to omit it). Be clear-eyed about what that scope actually grants on this one repo, because it's
wider than "dispatch one workflow":

- it **can** call `POST .../actions/workflows/ingest.yml/dispatches` (what this controller
  actually uses it for);
- it also **can** dispatch `status.yml` and `node-image.yml` — including dispatching `status.yml`
  with `publish=true` and an old `extensions_source_ref` or a tiny `extensions_file` list, which
  would publish a stale/truncated status page over the real one — re-run or cancel any workflow
  run, delete workflow runs/logs and Actions caches, download any run's artifacts (including,
  e.g., `extensions-build` — not secret, but not nothing either), and enable/disable workflows
  for this repo;
- it **cannot** read or write code, issues, pull requests, releases, or repo settings;
- it **cannot** register, remove, or otherwise touch any runner (there are none).

**Prefer a short expiry** and rotate it rather than setting "no expiration". If you want stricter
isolation than "Actions: read and write" on your main repo gives you, run the node against a
**separate, dedicated ingest-only repo** instead (its `ingest.yml` dispatches into the real repo
with its own, more narrowly-scoped credential) — this repo doesn't build that for you, but the
PAT's own scope is the only thing standing between "the controller can dispatch ingest" and "a
compromised controller can also re-run/cancel/delete this repo's Action history", so it's worth
doing if that distinction matters to your threat model.

It lives only in `node/controller.config.json`, mode 600, on this machine. **It is never passed
into the sandbox container** — `run_sandbox()` in `controller.py` only ever sets the proxy
address as an env var; the PAT, the repo name, and the Pages URL never cross into the container
at all. If you ever suspect it leaked, revoke it from the fork's PAT settings — recheck.json and
the bundle are public reads, so nothing about *fetching* work needs a credential; only
*reporting back* does.

### Network isolation

`node/compose.yml` creates two Docker networks and a proxy container between them:

- `sandbox-net` — `internal: true`, **plus** `gateway_mode_ipv4`/`gateway_mode_ipv6: isolated`
  (Docker >=28). `internal: true` alone is **not** enough — verified on a real host: a container
  on a plain `internal: true` network could still reach the *host's* sshd by connecting directly
  to the bridge's gateway IP (`internal` withholds a route to the wider network, but not to the
  gateway's own listening sockets on `0.0.0.0`). `gateway_mode_ipv4/ipv6: isolated` removes the
  gateway address from the network entirely; the same connection attempt then fails with
  "Network unreachable", and the network's IPAM no longer reports a gateway at all. The ONLY
  thing attached to this network besides the sandbox container itself is the proxy's inside leg.
- `egress-net` — an ordinary bridge network with real outbound access.
- `proxy` — Squid, pinned by digest, attached to both. The sandbox's only way anywhere is through
  it (set via `EXT_RUN_WV_PROXY` for Chrome and JVM `-Dhttps.proxyHost=...` system properties for
  OkHttp — `NetworkHelper`'s `OkHttpClient.Builder` never calls `.proxy(...)`, so it already falls
  back to the JVM's default `ProxySelector`, which honours those properties; no harness code
  change was needed there, only the env plumbing in `run-smoke.sh`/`entrypoint.sh`).

Squid (not tinyproxy) specifically because its destination ACLs are checked against the
**resolved** IP, for CONNECT/HTTPS tunnels too — see `node/proxy/squid.conf` for the actual
deny list (RFC1918, link-local/169.254.0.0/16 — which covers the 169.254.169.254 cloud metadata
address — 100.64.0.0/10 CGNAT space, loopback, the default Docker bridge range, 0.0.0.0/8,
multicast, reserved/"Class E" space, and the IPv6 ULA/link-local ranges) and the allow list (ports
80/443 only, nothing else). A hostname-pattern-only proxy can be defeated by a public hostname
that resolves to a private address; an IP-checked ACL can't. (An explicit
"IPv4-mapped-IPv6 `::ffff:0:0/96`" deny rule was tried and dropped after testing showed it denies
*all* IPv4 traffic, not just IPv6-smuggled private addresses — Squid stores every destination
internally in IPv6-mapped form, so the existing IPv4 deny rules already catch a private address
regardless of which wire form it arrived in.)

`node/controller.py` also looks up this node's own public IP(s) (via ipify) at the start of every
poll and writes a deny rule for them into `node/proxy/own-ip.generated.conf` — gitignored, never
logged, reloaded into the running proxy via `squid -k reconfigure` — so a request through the
proxy can't hairpin straight back out to whatever this node's own public IP also exposes (e.g. a
port-forwarded service on the operator's router). Set `"skip_own_ip_lookup": true` in
`controller.config.json` if you'd rather maintain that deny rule yourself (e.g. a static IP you'd
rather not re-look-up every poll); the file is also where you'd hand-write it in that case.

**If you have root** and would rather enforce this at the host firewall instead of (or in
addition to) the proxy: attach the sandbox to whatever network you choose and add `DOCKER-USER`
iptables rules that drop traffic from that network's subnet to RFC1918/link-local/metadata
ranges, same idea as `squid.conf`'s deny list. This repo doesn't ship those rules because they're
host-specific (`iptables` vs `nft`, which chain hooks exist on your Docker version) — the proxy
approach above works the same way with no root at all, which is why it's the default.

### DNS

`controller.py` runs the sandbox with `--dns 127.0.0.1 --dns-search .`, so its `/etc/resolv.conf`
never exposes the host's real upstream resolvers or search domain. This does **not** break
container-name resolution: Docker always injects its own embedded DNS (`127.0.0.11`) for
container/alias lookups regardless of `--dns` — the `proxy` alias still resolves through that —
`--dns` only changes what `127.0.0.11` forwards *unresolved* queries on to, which the sandbox
should never need anyway (its only route anywhere is through the proxy, by hostname or IP; the
proxy does its own DNS resolution for the sites it fetches).

### Resource limits on the sandbox run

`controller.py`'s `docker run` always includes: `--read-only` (root filesystem read-only;
`/work`, `/tmp`, `/home` are tmpfs), `--security-opt no-new-privileges`, `--cap-drop ALL`,
`--user 10001:10001` (never root even if the image's `USER node` were somehow bypassed),
`--memory`, `--pids-limit`, and `--cpus` (all configurable in `controller.config.json`). The JVM
itself is started with `-Xmx768m` (see `EXT_RUN_JAVA_EXTRA_OPTS` in `entrypoint.sh`).

There is deliberately no `/output` mount at all — an earlier version of this used a dedicated
per-run named Docker volume there (a host bind mount was ruled out as needing a host-side
`chmod`; a tmpfs was tried too, but `docker cp` cannot read a tmpfs mount's contents at all, on
either a running or a stopped container, confirmed by testing). A named volume has no portable
per-volume byte quota in Docker's plain `local` driver, so a runaway write from inside the
sandbox could fill the host's disk before the controller ever got to cap what it *trusted* —
bounding the read doesn't bound the write. The result now crosses entirely on the container's
own stdout instead, between fixed `RESULT_BEGIN`/`RESULT_END` marker lines (`node/entrypoint.sh`)
— `docker run --log-driver json-file --log-opt max-size=512k --log-opt max-file=1` caps what
`docker logs` can ever return, at the Docker daemon level, before the controller's own
`MAX_PAYLOAD_BYTES` check even runs; `--ulimit fsize` bounds any single file the sandboxed
process writes under `/work` as a second, independent cap. The container isn't run with `--rm`
(that would delete it, and its logs, the instant the process exits, before `docker logs` ever
gets a chance) — the controller removes it explicitly after reading the logs out, every run,
success or failure.

## What this exposes

Running a node means third-party extension code executes on your machine (inside the sandbox
above) and visits third-party sites **from your node's IP address**, through the egress proxy.
That means:

- those sites, and whatever ad/analytics network they load, see your node's public IP;
- if your node's result for a source differs from the GitHub-hosted sweep's, that difference can
  hint at roughly where your node is (country/region), the same way it would for any browser;
- category-only output (see the top-level README) means a node never *reports* anything more
  specific than that, but the sites themselves still saw the request.

If that's not a tradeoff you want to make, don't run a node — the pipeline works without one,
it just can't recover the slice of sources that only clear a Cloudflare/IP check from a
residential-looking address.
