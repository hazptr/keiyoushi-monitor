# WebView sidecar protocol

The contract between the JVM shim (`runtime/.../keiyoushi/extrun/WebViewBridge.kt`, behind
`android.webkit.WebView` and `CookieManager`) and a browser backend (`playwright/bridge.mjs`,
`nodriver/bridge.py`). A new backend only has to implement this file; nothing on the JVM side knows
which backend it is talking to, except the `browser` string it passes through.

`protocol_test.py` drives a backend over this protocol without the JVM (every request answered from
`intercept`, so no network) and prints a trace; diff two backends' traces to check a change.

## Transport

- A unix stream socket. The backend takes its path as its only argument (`bridge.mjs /sock/playwright.sock`,
  `bridge.py /sock/nodriver.sock`), removes a stale file, listens, and makes it mode 0660.
  `ext-run.sh` mounts the socket dir into the JVM container and sets `EXT_RUN_WEBVIEW_SOCK`.
- One JSON object per line (UTF-8, `\n`-terminated), both directions. Lines can be large (whole
  HTML documents, base64 bodies).
- One connection per JVM process (opened lazily on the first WebView call). Several connections at
  once are independent (two `ext-run` runs share one sidecar); `wid`s are per connection.
- Messages are handled concurrently; an op that has to wait (e.g. for the page to open) must not
  block later ones. `reply` messages must never queue behind other ops.

## JVM -> backend: ops

Every op is `{"op": ..., ...}`. If it carries `"id"`, the backend answers exactly once with
`{"re": id, "ok": true, "value": <value or null>}` or `{"re": id, "ok": false, "error": "<text>"}`.
Ops without `id` are fire-and-forget. All ops except `open`, `defaultUserAgent` and `reply` name a
WebView with `wid` and wait until that view's `open` has finished (an unknown `wid` is an error).

| op | fields | reply value | meaning |
|---|---|---|---|
| `defaultUserAgent` | `browser` | string | The engine's own UA (no `HeadlessChrome`). Starts the browser if needed. Backs `WebSettings.getDefaultUserAgent`. |
| `open` | `wid`, `browser`, `userAgent` (string or null), `javaScriptEnabled`, `blockImages`, `bridges`, `serve`, `live`, `record`, `cookies` | (sent without id) | Create the page: one isolated cookie/storage context + one page, nothing loaded. Sent on the WebView's first load. See below. |
| `load` | `wid`, `url`, `headers` (object) | null, at once | Navigate the main frame. `headers` are added to that navigation's main-frame request only (to the first request matching `url`). `about:blank` counts as a requested load (events are reported for it). |
| `loadData` | `wid`, `baseUrl` (string or null), `html`, `mimeType` | null, at once | `loadDataWithBaseURL`. With an http(s) `baseUrl`: navigate to `baseUrl` and answer that main-frame document request with `html` (`<mimeType>; charset=utf-8`, status 200), so the page has that origin and nothing is fetched. Otherwise: replace the current (about:blank) document with `html`. |
| `eval` | `wid`, `script` | string | `evaluateJavascript` in the main frame's main world: the script's completion value, JSON-encoded (`JSON.stringify` semantics); `"null"` for `undefined`, functions, non-serialisable values and thrown errors (an error also produces a `console` event at level `error`, text starting `Uncaught `). Promises are not awaited (`"{}"`). |
| `addBridges` | `wid`, `bridges` | null | More JS interfaces; visible from the next document on (as on Android). |
| `set` | `wid`, `blockImages` | null | Change image blocking for later requests. |
| `stop` | `wid` | null | `stopLoading`. |
| `cookies` | `wid` | cookie array | All cookies of the view's context. |
| `setCookies` | `wid`, `cookies` | null | Write cookies into the view's context; a cookie whose `expires` is in the past deletes the cookie with that name/domain/path. |
| `close` | `wid` | cookie array | The context's cookies, then close page and context. |
| `reply` | `rid`, `value` | (never answered) | Answer to an `intercept` or `serve` event. |

`open` fields:

- `browser`: engine name from `--browser`/`--webview` (`firefox`, `chromium`, `chrome`). A backend with
  one engine ignores it.
- `userAgent`: null = the engine's own UA. The JVM already maps "the source copied ext-run's OkHttp
  UA (Firefox 117)" to null.
- `bridges`: `[{"name": "<window property>", "methods": ["<method>", ...]}]` from
  `addJavascriptInterface`: `window.<name>.<method>(...args)` must exist in every frame's main world
  before page scripts run, and each call becomes a `bridge` event. Calls return `undefined` to the page.
- `blockImages`: fail image requests (`blocked`), without asking the JVM.
- `serve`, `live`, `record`: fixture mode, see "Requests". `cookies`: the harness jar, to preload.

## Backend -> JVM: events

`{"ev": ..., "wid": n, ...}`. Events with `rid` need a `{"op": "reply", "rid": rid, "value": ...}`;
if the connection closes first, the backend treats every pending one as `null`.

| ev | fields | JVM does |
|---|---|---|
| `intercept` | `rid`, `req` | `shouldInterceptRequest` on an IO thread; replies a response object or `null`. |
| `serve` | `rid`, `req` (+ `postData`) | fixture / `--map` lookup; replies a response object (with `via`) or `null`. |
| `pageStarted` | `url` | `onPageStarted` (main thread), sets `getUrl()`. |
| `pageFinished` | `url` | `onPageFinished` + `onProgressChanged(100)`. |
| `cookies` | `cookies` | Copies them into the harness jar. Sent right before every `pageFinished`. |
| `httpError` | `url`, `method`, `isMainFrame`, `status`, `contentType`, `headers` | `onReceivedHttpError` (main thread): sent for every response with status >= 400 when its headers arrive, so a main-frame error comes before that load's `pageFinished`. |
| `error` | `url`, `method`, `isMainFrame`, `description` | `onReceivedError`; dropped if `description` contains `ABORTED` or `cancel`. |
| `net` | `method`, `url`, `type`, `status`, `via`, `error`? | `wv#` request log line and counts. |
| `record` | `url`, `method`, `headers`, `postData`, `status`, `finalUrl`, `contentType`, `body` | Writes a fixture. |
| `bridge` | `name`, `method`, `args` | Calls the `@JavascriptInterface` method on the single `JavaBridge` thread. |
| `console` | `level` (`log`/`debug`/`warning`/`error`), `text`, `source`, `line` | `WebChromeClient.onConsoleMessage` (core only logs it; printed with `-v`). |
| `crash` | | `onRenderProcessGone`. |
| `fatal` | `message` | `open` failed; treated as render process gone. |
| `log` | `text` | Printed to stderr (backend diagnostics). |
| `note` | `text` | Printed to stderr; `text` `"clicked Cloudflare's checkbox"` also adds the `smoke` warning `"cloudflare checkbox clicked"` (see "Cloudflare Turnstile" below). |

Objects:

- `req`: `{url, method, headers, isMainFrame, isRedirect, type}`; `url` includes the fragment;
  `isMainFrame` = a navigation request of the main frame; `isRedirect` = this request follows a
  redirect; `type` = resource type (below). `headers` are what the engine knows before sending
  (no `Cookie`, like Android). `serve` adds `postData` (base64 or null).
- response object (intercept/serve reply): `{status, contentType?, headers?, body}` with `body`
  base64; `contentType` becomes `content-type` unless `headers` has one. `serve` adds
  `via` (`fixture`/`map`). A 3xx with `Location` is followed (the next hop goes to the network, see "Requests").
- cookie: `{name, value, domain, path, expires, httpOnly, secure, sameSite?}`; `domain` with a
  leading dot = domain cookie, without = host-only; `expires` in seconds since the epoch, `-1` = session.
- `type`: lower-case resource type: `document`, `script`, `stylesheet`, `image`, `font`, `media`,
  `xhr`, `fetch`, `websocket`, `other`, ...
- `via` (in `net`): `live` (went to the site), `fixture`/`map` (served by `serve`), `intercept`
  (answered by `shouldInterceptRequest`), `data` (the `loadData` document), `blocked` (image
  blocking), `miss` (fixture miss without `live`), `failed` (live request that failed). The JVM counts
  `live`/`fixture`/`map`/`failed` as site requests.

## Requests

Every request of the page (all frames; workers where the engine allows) goes through this, in order,
except redirect hops: the request a 3xx leads to (live or from a reply) goes straight to the network
(`via: live`) without `intercept`/`serve`, as Playwright's routing does. So `isRedirect` is false in
practice.

1. the pending `loadData` document -> answered with the HTML (`via: data`);
2. image and `blockImages` -> failed (`via: blocked`, `error` like `net::ERR_BLOCKED_BY_CLIENT`), no `error` event;
3. `intercept` -> a non-null reply is the response (`via: intercept`);
4. if `serve`: `serve` -> a non-null reply is the response; else, if not `live`, fail it as offline
   (`via: miss`, no `error` event);
5. otherwise it goes to the network (`via: live`), with the `load` op's extra headers if it's that
   navigation. Engine caches are bypassed, so every request is seen.

After a request ends the backend sends one `net` (redirect hops each get one, with their 3xx
status); a failure (status 0, `error` = engine error text) also sends `error` unless it was
`blocked`/`miss`. With `record`, every `live` request of type `document`, `script`, `xhr`, `fetch`
or `stylesheet` whose status is not 3xx also sends `record` with the response body.

## Page events

- `pageStarted`: the main frame committed a navigation (including same-document ones such as
  `history.pushState`), with its URL.
- `pageFinished`: the main frame's `load` event, with the page URL at that moment, preceded by a
  `cookies` event.
- The new page's initial `about:blank` reports nothing (Android reports nothing for it); an
  explicitly requested `about:blank` does.
- A main-frame navigation that fails shows the engine's error page (e.g. `chrome-error://...`) and
  reports `pageStarted`/`pageFinished` for it, after the `error` event.
- "Done" for a load is `pageFinished`; there is no network-idle notion. Events for a closed view are
  not sent.

## Cookies

Each view has its own context, preloaded with the harness jar at `open`. The backend sends the
context's cookies before every `pageFinished`; the JVM pulls them with `cookies` when a source calls
`CookieManager.getCookie`, pushes `setCookie` with `setCookies` to every open view, and saves the
`close` reply. Cookies are never shared between views directly, only through the jar.

## Cloudflare Turnstile

From some IPs (GitHub Actions runners in particular) Cloudflare shows an interactive "Verify you
are human" checkbox instead of clearing on its own, the same checkbox a real user ticks in the app.
`bridge.py` watches every page it loads for one: it looks for the widget's hidden
`input[name=cf-turnstile-response]` on the top-level document (present whether the widget itself
renders inline or inside its own `challenges.cloudflare.com` iframe) and, if the challenge hasn't
cleared on its own after a few seconds, sends a realistic move/pause/press/release CDP mouse
sequence at the checkbox, on the page's own session -- never inside the Turnstile iframe's session,
and never on a page that isn't showing a widget. It waits up to ~30s total for the `cf_clearance`
cookie (or the page navigating away), and clicks at most twice. A click sends `{"ev": "note", "wid":
n, "text": "clicked Cloudflare's checkbox"}`, which the JVM turns into the `smoke` warning
`"cloudflare checkbox clicked"` -- a clicked pass must never be reported as an unclicked one. Only
`bridge.py` does this; `bridge.mjs` (playwright) does not.

## Lifetime

- The browser starts on the first `open`/`defaultUserAgent`, stays up while any view is open, and
  closes 10 minutes after the last view closed.
- A closed connection closes all its views (without replies).
- The process exits 60 minutes after the last connection closed; `ext-run.sh` starts it again on the
  next run (and recreates it when the backend's files or the compose file change).
- If the browser dies, open views get `crash`.

## Where the backends differ

Checked with `protocol_test.py` (playwright-chromium vs nodriver traces are identical apart from these):

| | playwright (`bridge.mjs`) | nodriver (`bridge.py`) |
|---|---|---|
| `console` events | page `console.*` calls, uncaught page errors, eval errors | browser log entries (failed loads, CSP, interventions) and eval errors only: `console.*` and uncaught exceptions need `Runtime.enable`, which this backend never sends |
| JS interface transport | tagged `console.debug` (read via the Runtime domain) | synchronous XHR to `https://ext-run.invalid/bridge/<token>`, answered inside the bridge (no `net`, no `intercept`) |
| `eval` error text | `Uncaught <stack from evalInPage>`; a BigInt result also logs a serialisation error | `Uncaught <exception description>`; BigInt is silently `"null"` |
| `error` text | Firefox `NS_ERROR_*` / Chromium `net::ERR_*` | `net::ERR_*` |
| service workers | engine default | bypassed (`Network.setBypassServiceWorker`) |
| engine extras | none | favicon requests dropped; http->https auto-upgrade off; speculation-rules fetches still happen |
| CSP bypass | every page | the page and its in-process frames, not out-of-process iframes (bypassing CSP there fails Cloudflare's Turnstile) |
| Cloudflare Turnstile | not handled | clicks the checkbox if one shows up and doesn't clear on its own (see "Cloudflare Turnstile" above) |
| `pageFinished` URL | URL at the `load` event | same, but timing differs: e.g. a `pushState` just before `load` may or may not be included |
