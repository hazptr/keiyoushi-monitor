# Recheck proxies

Some sources fail the daily sweep only because of where it runs from: GitHub's datacenter IPs,
or the wrong country. Each sweep shard in `status.yml` checks its own failures that look like a
block again, right after its run: the runner joins a private Tailscale network and sends that
traffic through the proxies on it. A pass there replaces the sweep's result on the page.

Nothing runs on a proxy host except Tailscale and Squid. No runner, no extension code, no GitHub
credentials. The host only forwards web requests for the sweep's rechecks.

```
status.yml  sweep shard: run ──> its blocked-looking failures again (tag:kmon-ci, OIDC sign-in)
                              │  tailnet: tag:kmon-ci may reach tag:kmon-proxy:3128, nothing else
                              ▼
            proxy host   tailscale (userspace) + squid on 127.0.0.1:3128 ──> the sites
```

## How a source picks a proxy

`harness/ci/recheck.py`, in each sweep shard:

1. finds every online tailnet peer tagged `tag:kmon-proxy`;
2. measures each one's exit country and time zone *through the proxy* (ip-api.com, only the
   country and zone fields), so a proxy's location is never taken on trust, and the job log
   only shows hostname and country, never an IP;
3. sends each source to proxies in the country it wants first: `recheck/geo.tsv`, else the
   language's country for languages tied to one (vi→VN, id→ID, th→TH, ko→KR, ja→JP, tr→TR, …);
   then the rest in the order of the `RECHECK_PROXY_ORDER` repo variable (comma-separated
   hostnames), unlisted ones after by name;
4. moves a source on to the next proxy only while its result still looks like a block (blocked,
   cloudflare, timeout, connect). A pass anywhere counts, with the country it passed from.

Only the category-only result (`publisher/node_schema.py`) leaves the job. Raw logs can carry an
exit IP (a block page that echoes it) and this repo's artifacts are public.

## Public proxies

Any HTTP proxy on the internet can join the pool too (a paid residential or country-specific
proxy, a friend's Squid with a password), without the tailnet: put them in the repo **secret**
`RECHECK_PROXIES` as JSON,

```json
[{"name": "res-vn", "url": "http://user:pass@host:port"}, {"name": "jp-1", "url": "http://host:port"}]
```

They're measured and ordered exactly like tailnet proxies (`name` is what `RECHECK_PROXY_ORDER`
and the job log use). Only `http://` proxies, which covers HTTPS sites through CONNECT. One with a
login goes through `harness/ci/proxy_chain.py` on the runner's 127.0.0.1, because Chrome and the
JVM can't send proxy credentials themselves; the URL, host and login are masked in the job log.
Unlike a tailnet proxy nothing limits who can use their credentials if they leak (extension code
runs in the same job), so give them their own login with a spending or bandwidth cap.

## Tailnet setup (once)

1. Create a tailnet just for this. Don't reuse a personal one; the policy below is the whole
   policy, so nothing else can be on it.
2. Access controls: replace the policy with

   ```json
   {
     "tagOwners": {
       "tag:kmon-ci":    ["autogroup:admin"],
       "tag:kmon-proxy": ["autogroup:admin"]
     },
     "grants": [
       { "src": ["tag:kmon-ci"], "dst": ["tag:kmon-proxy"], "ip": ["tcp:3128"] }
     ],
     "ssh": []
   }
   ```

   Runners reach the proxies' port 3128 and nothing else; proxies reach nothing on the tailnet.
3. Settings → Trust credentials (workload identity federation): add an OpenID Connect
   credential for GitHub Actions, issuer `https://token.actions.githubusercontent.com`, subject
   `repo:<owner>/<repo>:*` (or, if the repo uses GitHub's immutable subject,
   `repo:<owner>@<owner-id>/<repo>@<repo-id>:*`: check
   `gh api repos/<owner>/<repo>/actions/oidc/customization/sub`), custom claim
   `job_workflow_ref` = `*/.github/workflows/status.yml@*`, scope `auth_keys` (write) with tag `tag:kmon-ci`. Put its client ID
   and audience in the repo **variables** (not secrets; neither is sensitive) `TS_CLIENT_ID` and
   `TS_AUDIENCE`. No stored secret: each shard signs in with its own short-lived GitHub OIDC token
   and gets an ephemeral node that disappears when the job ends. The recheck is skipped while
   `TS_CLIENT_ID` is unset.
4. Settings → Keys: generate a **reusable, pre-approved, tagged** (`tag:kmon-proxy`) auth key for
   each proxy host. That's the only thing a proxy owner needs from you.

What a leak costs: extension code runs in the sweep job and can see what the job can (its
Tailscale node, its OIDC token). With the policy above that gets it to port 3128 on the proxies,
which forward only ports 80/443 to public addresses, at the Squid bandwidth cap, until the job
ends.

## Running a proxy

Docker and the auth key; no open ports, works behind NAT/CGNAT, no root needed beyond Docker.

```sh
git clone https://github.com/<owner>/<repo> && cd <repo>
export KMON_PROXY_NAME=kmon-vn            # tailnet hostname; shown in the job logs
export KMON_OWN_IP_CONF=$HOME/.config/kmon-proxy/$KMON_PROXY_NAME-own-ip.conf
mkdir -p "$(dirname "$KMON_OWN_IP_CONF")" && touch "$KMON_OWN_IP_CONF"
TS_AUTHKEY=tskey-auth-... docker compose -p "$KMON_PROXY_NAME" -f proxy/compose.yml up -d
proxy/refresh-own-ip.sh "$KMON_PROXY_NAME" "$KMON_OWN_IP_CONF"   # then every ~15 min (cron/timer)
```

The auth key is only read on the first start; the node's identity lives in the compose
project's `state` volume after that. `refresh-own-ip.sh` keeps a Squid deny rule for the host's
own public IPs, so nothing can come back through the proxy to whatever the host's connection
exposes (a router page, a reverse proxy).

To exit through a VPN instead of the host's own connection, add `compose.upstream.yml` and a
Squid include with the upstream (`upstream.example.conf`):

```sh
KMON_UPSTREAM_NETWORK=<network the VPN's HTTP proxy is on> KMON_SQUID_LOCAL=/path/to/upstream.conf \
  docker compose -p kmon-pia -f proxy/compose.yml -f proxy/compose.upstream.yml up -d
```

What a proxy owner should know: the rechecks visit manga sites, their images and whatever
ad/analytics networks they load, from the proxy's IP, once a day while the sweep runs, from up
to one runner per shard at a time, capped at ~4 MB/s (`delay_parameters` in `squid.conf`). Squid keeps no access log.
