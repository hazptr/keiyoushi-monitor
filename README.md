# keiyoushi-monitor

A self-hostable daily health check for [keiyoushi](https://github.com/keiyoushi/extensions-source)
1.6 extensions, running entirely on GitHub Actions, with an optional recheck through proxies
(Tailscale) for the sources that block datacenter IPs or want a particular country.

Live page: https://hazptr.github.io/keiyoushi-monitor/

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
| `sweep` | GitHub-hosted, sharded | Each shard runs the smoke check for its slice — no Gradle, just the already-built classes. One retry after a delay on failure. Then the shard's failures that look like a block go straight back out through the recheck proxies on the tailnet (OIDC sign-in), each source in the country it wants first ([`proxy/README.md`](proxy/README.md)); only a category per extension comes out of that. Skipped while the tailnet isn't configured. |
| `classify` | GitHub-hosted | Merges the shards and decides which failures look IP/network-shaped (Cloudflare/403/429/timeout/connect patterns) rather than extension-shaped: the ids the recheck results may report on. |
| `build-site` | GitHub-hosted, read-only (`contents: read`) | Builds `status.json` + the site, merges the recheck reports in, uploads the site as an artifact; never has write access to this repo. |
| `deploy` | GitHub-hosted, `contents: write` | Only downloads `build-site`'s finished artifact and pushes it to `gh-pages`; it never extracts or executes anything from it. This split is what keeps the one job with push access from ever running a repo script against build output. |

No workflow in this repo has a `pull_request` or `pull_request_target` trigger, and **none of
them is, registers, or waits on a self-hosted GitHub Actions runner.** The recheck runs in the
sweep shards themselves; they join a private Tailscale network for the length of the job and
send their traffic through the recheck proxies on it. See [`proxy/README.md`](proxy/README.md).

## Security model

Running a recheck proxy means **the sites under test, and any ad/analytics network they load,
see the proxy's public IP**, the same as opening them in a normal browser would. That's inherent
to what the recheck is for (some sites only pass from a residential IP, or from one country).
Extension code itself never runs on a proxy host: it runs on GitHub's runners, which only reach
the proxies' port 3128 over the tailnet. If a source's recheck result differs from the sweep's,
the difference can hint at roughly where a proxy is; the report itself carries only a category,
each step's status word and the proxy's country. See [`proxy/README.md`](proxy/README.md) for the rest.

## Setting up your own copy

1. **Fork or use this as a template** (Use this template → Create a new repository), whichever
   you prefer — the workflows derive the owner/repo from
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
4. *(Optional)* Set up the tailnet and at least one recheck proxy — see
   [`proxy/README.md`](proxy/README.md). Without them the recheck is skipped and the
   pipeline reports a few more "can't test" extensions.

## Repo layout

- `harness/runtime/` — the JVM stand-in for the app: extensions-lib 1.6 API + Mihon's network
  stack + a CLI that drives one extension and prints a JSON result.
- `harness/webview/nodriver/` — real headed Chrome under Xvfb, driven over CDP, so Cloudflare
  sees a normal browser.
- `harness/ci/` — the scripts the workflow calls: `build-runtime.sh`, `build-all.sh` (build every
  extension's classpath once, packed with no absolute paths at all — see `extract_build.py`),
  `recheck.py` (one recheck shard through the tailnet proxies),
  `extract-build.sh`/`extract_build.py` (the validating extractor for `build-all.sh`'s archive —
  every member is re-checked and rewritten onto a root THIS job picks, never the archive's own
  claim), `start-webview.sh`, `run-smoke.sh`, `run-shard.sh`, `split.py`/`make_row.py`.
- `publisher/` — `build_status.py` (status classification), `needs_recheck.py` (decides what's
  worth a recheck), `categorize.py` + `node_schema.py` (the category-only recheck report and its
  strict schema), `merge_recheck.py`/`merge_node_result.py` (applies the validated recheck
  reports onto `status.json`), `leak_check.py` (scrub/leak-check primitives).
- `proxy/` — a recheck proxy: Tailscale + Squid compose files, the Squid config and the own-IP
  refresher. See [`proxy/README.md`](proxy/README.md).
- `recheck/geo.tsv` — sources that only work from particular countries.
- `site/` — the static page (`index.html`/`app.js`/`style.css`).
- `untestable-sources.tsv` — sources that can't be smoke-tested at all (self-hosted software like
  Komga/LANraragi, Japan-only sites) and the reason shown on the page, instead of a failure.
- `config/<lang>_<name>.toml` — per-source settings: `not_applicable` (operations the source
  doesn't have, such as search on a site without one, shown as not applicable instead of failing),
  `not_applicable_reason`, and `smoke_url` (a title to test, for sources with no lists to pick from).
- `ci-sample.txt` (a 100-extension sample for test runs; `status.yml` checks every extension when `extensions_file` is blank) / `ci-tiny.txt` — extension-list
  inputs for test runs.

## Privacy design

The GitHub-hosted side scrubs and leak-checks everything before it's written to `status.json` —
IP-looking strings (dotted-quad, IPv6, hex/decimal-encoded, dashed-IP reverse-DNS hostnames),
Cloudflare ray/colo/loc codes, ISO timezone offsets, and absolute filesystem paths — and the
published fields per extension are a fixed, whitelisted set (status, the operation that failed,
a short scrubbed error, a few plain-language notes), never logs, request bodies, headers, or
timing data.

A recheck's report goes further: it is **never free text in the first place**. Each recheck
job only emits one of a fixed category enum per extension (`publisher/node_schema.py`), plus each
step's status word from another fixed enum and the proxy's country on a pass, so there's no error message to leak; raw logs never leave the job.
`build-site` re-validates every shard's report before merging it, and the scrub/leak-check runs
over the result as a backstop.

## Credits

Built against [keiyoushi/extensions-source](https://github.com/keiyoushi/extensions-source); not
affiliated with that project. Some checks are adapted from
[vetleledaal/keiyoushi-status](https://github.com/vetleledaal/keiyoushi-status), used with
permission.

## License

[Apache License, Version 2.0](LICENSE). See [NOTICE](NOTICE) for the Mihon-derived files in
`harness/runtime/` and their original copyright notices.
