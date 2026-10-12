# keiyoushi-monitor

A self-hostable daily health check for [keiyoushi](https://github.com/keiyoushi/extensions-source)
1.6 extensions, running entirely on GitHub Actions, with an optional recheck node for the slice
of sources that block datacenter IPs outright.

Each extension runs on a JVM harness that stands in for the Mihon/Komikku app: a real
implementation of the extensions-lib 1.6 API plus Mihon's network stack, driving a real headed
Chrome WebView (so Cloudflare sees a normal browser). It loads popular, opens a title and its
chapter list, loads the newest chapter, then downloads and decodes two pages — the same path a
reader takes. Anything that fails gets one retry before it counts.

## What each status means

- **Working** — every step passed.
- **Partly working** — you can still read, but something else (search, latest updates) fails.
- **Not working** — a step on the way to reading failed; the page says which and why.
- **Can't test** — no result at all, usually a Cloudflare challenge the harness couldn't solve.
  The app handles most of those; check there before assuming a source is actually broken.
- **Not checked yet** — this run hasn't reached it; the last known result is shown.
- **Not supported** — a 1.4 extension (the older extension API), which this checker doesn't run.

Results can be wrong: sites change, block automated checks, or act differently depending on where
the check runs from. The pipeline runs off `extensions-source` main (or a pinned SHA), so it can
be ahead of what the app ships. If something shows as failing here, check it in the app first.

## How it works

| job / workflow | runs on | does |
|---|---|---|
| `build-runtime` | GitHub-hosted | Builds the harness runtime (the app stand-in). Never touches extension code; its own Gradle cache key. |
| `build` | GitHub-hosted | Checks out `extensions-source` at main (or a pinned, ancestry-verified SHA) and builds every extension's classpath **once**, with its own (read-only, kei-commit-keyed) Gradle cache. |
| `sweep` | GitHub-hosted, sharded | Each shard runs the smoke check for its slice — no Gradle, just the already-built classes. One retry after a delay on failure. |
| `classify` | GitHub-hosted | Merges the shards and decides which failures look IP/network-shaped (Cloudflare/403/429/timeout/connect patterns) rather than extension-shaped — the recheck candidate list. |
| `build-site` | GitHub-hosted, read-only (`contents: read`) | Builds `status.json` + the site and (if there's a recheck candidate list **and** a published, attested node image) a `recheck.json` + a prebuilt bundle for that subset. Uploads both as artifacts; never has write access to this repo. |
| `deploy` | GitHub-hosted, `contents: write` | Only downloads `build-site`'s finished artifacts and pushes/uploads them (to `gh-pages`, and the bundle to a Release asset) — it never extracts or executes anything from them. This split (and the identical one in `ingest` below) is what keeps the one job with push access from ever running a repo script against build output. |
| `node-image` | GitHub-hosted | Builds the recheck sandbox image (with the harness runtime baked in) and publishes it to GHCR, digest-pinned, with a signed build-provenance attestation. |
| `ingest` | GitHub-hosted, `workflow_dispatch` only | `validate-and-merge` (read-only) re-validates a recheck node's reported result and merges it into the published `status.json`; its own `deploy` job (`contents: write`) just pushes that finished artifact. The only way anything reaches `gh-pages` besides `status.yml`'s `deploy` job. |

No workflow in this repo has a `pull_request` or `pull_request_target` trigger, and **none of
them is, registers, or waits on a self-hosted GitHub Actions runner.** The optional recheck is a
*polling node*: your own machine, under your own user, periodically checks a public URL for work
and reports back by triggering `ingest` — GitHub never pushes a job to it. See
[`node/README.md`](node/README.md) for exactly how, and why that matters.

## Security model

Running the recheck node means **third-party extension code executes on your machine, and
visits third-party sites from your node's IP address.** That's inherent to what a residential
recheck is for (some sites only pass from a residential-looking IP) — it is not hidden behind
"optional". Concretely:

- those sites, and any ad/analytics network they load, see your node's public IP, the same as
  opening them in a normal browser would;
- if a node's result for a source differs from the GitHub-hosted sweep's, that difference can
  hint at roughly where the node is (country/region);
- a node's own report is restricted to a fixed category enum (pass/fail/blocked/cloudflare/
  timeout/connect/error, plus one fixed warning tag) and never free text — see
  [`node/README.md`](node/README.md)'s "what this exposes" for the rest.

If you don't want that tradeoff, don't run a node. The pipeline works fully without one; it just
can't recover the handful of sources that only pass from a residential-looking address.

## Setting up your own copy

1. **Fork or use this as a template** (Use this template → Create a new repository), whichever
   you prefer — the workflows derive the owner/repo and the GHCR image name from
   `github.repository`, so neither needs any manual editing after that.
2. Run the `status` workflow once by hand (`workflow_dispatch`) against `ci-sample.txt` with a
   couple of shards, with `publish: true`, to make sure it's wired up and to create the first
   `gh-pages` commit — before trusting the daily schedule with the full extension list.
3. **Enable Pages** — Settings → Pages → Source: "Deploy from a branch" → `gh-pages` / `/`. Do
   this *after* step 2: Pages has nothing to point at until that first `publish: true` run has
   created the `gh-pages` branch. On a private repo this needs a paid plan; `build-site` still
   runs and uploads a `site-preview` artifact either way, so you can preview locally without
   Pages enabled at all (the `deploy` job that actually pushes to `gh-pages` only runs on a
   schedule run or an explicit `publish: true` dispatch input).
4. *(Optional)* Set up a recheck node — see [`node/README.md`](node/README.md). Without it, the
   pipeline still runs and publishes; it just reports a few more "can't test" extensions. If you
   do, after `node-image.yml` publishes the node image at least once, **make the GHCR package
   public**: your repo's Packages tab → the `-node` package → Package settings → Change
   visibility → Public. `node/controller.py` never runs `docker login`, so a node's own
   `docker run` can only pull the image anonymously — a private package (GHCR's default) will
   fail to pull with no further hint than "pull access denied".

## Repo layout

- `harness/runtime/` — the JVM stand-in for the app: extensions-lib 1.6 API + Mihon's network
  stack + a CLI that drives one extension and prints a JSON result.
- `harness/webview/nodriver/` — real headed Chrome under Xvfb, driven over CDP, so Cloudflare
  sees a normal browser.
- `harness/ci/` — the scripts the workflow calls: `build-runtime.sh`, `build-all.sh` (build every
  extension's classpath once, packed with no absolute paths at all — see `extract_build.py`),
  `pack-recheck-bundle.sh` (repackage a subset for a recheck node, also no absolute paths),
  `extract-build.sh`/`extract_build.py` (the validating extractor for `build-all.sh`'s archive —
  every member is re-checked and rewritten onto a root THIS job picks, never the archive's own
  claim), `start-webview.sh`, `run-smoke.sh`, `run-shard.sh`, `split.py`/`make_row.py`.
- `publisher/` — `build_status.py` (status classification), `needs_recheck.py` (decides what's
  worth a recheck), `write_recheck_manifest.py` (writes the public `recheck.json`, including the
  bundle's own URL/checksum/size and the harness runtime commit it was built against),
  `node_schema.py` (the strict schema a node's report must match — shared by the node, the
  controller, and `ingest`), `merge_node_result.py` (applies a validated node report onto the
  published `status.json`), `leak_check.py` (scrub/leak-check primitives for the GitHub-hosted
  side's own free text, and a backstop everywhere else).
- `node/` — the recheck node: sandbox image (with the harness runtime baked in), controller,
  egress-proxy network + Squid config, the Chrome user-namespace seccomp profile
  (`node/seccomp/`), systemd/cron examples. See [`node/README.md`](node/README.md).
- `site/` — the static page (`index.html`/`app.js`/`style.css`).
- `untestable-sources.tsv` — sources that can't be smoke-tested at all (self-hosted software like
  Komga/LANraragi) and the reason shown on the page, instead of a failure.
- `ci-sample.txt` (a 100-extension sample for test runs; `status.yml` checks every extension when `extensions_file` is blank) / `ci-tiny.txt` — extension-list
  inputs for test runs.

## Privacy design

The GitHub-hosted side scrubs and leak-checks everything before it's written to `status.json` —
IP-looking strings (dotted-quad, IPv6, hex/decimal-encoded, dashed-IP reverse-DNS hostnames),
Cloudflare ray/colo/loc codes, ISO timezone offsets, and absolute filesystem paths — and the
published fields per extension are a fixed, whitelisted set (status, the operation that failed,
a short scrubbed error, a few plain-language notes), never logs, request bodies, headers, or
timing data.

A recheck node's own report goes further: it is **never free text in the first place**. The
sandbox only ever emits one of a fixed category enum per extension (see
[`node/README.md`](node/README.md)), so there's no error message to leak — this is the fix for a
real issue found in testing, where a free-text recheck error once revealed wording from a page on
the node's own network that had nothing to do with the extension being checked. `ingest`
re-validates that strictly before merging it in, and the
publisher's scrub/leak-check still runs over the result as a backstop.

## Credits

Built against [keiyoushi/extensions-source](https://github.com/keiyoushi/extensions-source); not
affiliated with that project. Some checks are adapted from
[vetleledaal/keiyoushi-status](https://github.com/vetleledaal/keiyoushi-status), used with
permission.

## License

[Apache License, Version 2.0](LICENSE). See [NOTICE](NOTICE) for the Mihon-derived files in
`harness/runtime/` and their original copyright notices.
