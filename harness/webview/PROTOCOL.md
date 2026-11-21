# WebView sidecar protocol

The contract between the JVM shim (`runtime/.../keiyoushi/extrun/WebViewBridge.kt`, behind
`android.webkit.WebView` and `CookieManager`) and the browser backend (`nodriver/bridge.py`; the
Playwright backend `playwright/bridge.mjs` was removed 2026-11-04). A new backend only has to
implement this file; nothing on the JVM side knows which backend it is talking to, except the
`browser` string it passes through.

`protocol_test.py` drives a backend over this protocol without the JVM (every request answered from
`intercept`, so no network) and prints a trace; diff traces before and after a change to check it.

## Transport

- A unix stream socket. The backend takes its path as its only argument (`bridge.py /sock/nodriver.sock`), removes a stale file, listens, and makes it mode 0660.
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
| `open` | `wid`, `browser`, `userAgent` (string or null), `javaScriptEnabled`, `blockImages`, `bridges`, `serve`, `live`, `record`, `cookies`, `startScripts`, `messageListeners`, `uaMetadata`, `includeCookies`, `originHeaders` | (sent without id) | Create the page: one isolated cookie/storage context + one page, nothing loaded. Sent on the WebView's first load. See below. |
| `load` | `wid`, `url`, `headers` (object) | null, at once | Navigate the main frame. `headers` are added to that navigation's main-frame request only (to the first request matching `url`). `about:blank` counts as a requested load (events are reported for it). |
| `loadData` | `wid`, `baseUrl` (string or null), `html`, `mimeType` | null, at once | `loadDataWithBaseURL`. With an http(s) `baseUrl`: navigate to `baseUrl` and answer that main-frame document request with `html` (`<mimeType>; charset=utf-8`, status 200), so the page has that origin and nothing is fetched. Otherwise: replace the current (about:blank) document with `html`. |
| `eval` | `wid`, `script` | string | `evaluateJavascript` in the main frame's main world: the script's completion value, JSON-encoded (`JSON.stringify` semantics); `"null"` for `undefined`, functions, non-serialisable values and thrown errors (an error also produces a `console` event at level `error`, text starting `Uncaught `). Promises are not awaited (`"{}"`). |
| `addBridges` | `wid`, `bridges` | null | More JS interfaces; visible from the next document on (as on Android). |
| `set` | `wid`, `blockImages`?, `includeCookies`?, `uaMetadata`?, `originHeaders`? | null | Change image blocking for later requests; `originHeaders` (`[{name, value, rules}]`, the profile's `setOriginMatchedHeader` list, `CUSTOM_REQUEST_HEADERS`): set on every live request whose URL origin matches the rules, replacing the list; `includeCookies` (`setIncludeCookiesOnIntercept`): `intercept` request headers carry the request's `Cookie`; `uaMetadata` (CDP `UserAgentMetadata` shape): client hints for later requests. |
| `stop` | `wid` | null | `stopLoading`. |
| `cookies` | `wid` | cookie array | All cookies of the view's context. |
| `setCookies` | `wid`, `cookies` | null | Write cookies into the view's context; a cookie whose `expires` is in the past deletes the cookie with that name/domain/path. |
| `close` | `wid` | cookie array | The context's cookies, then close page and context. |
| `features` | `browser` | string array | androidx.webkit features this backend implements (the JVM's `WebViewGlue` intersects them with its own). A backend without the op errors, which the JVM reads as none. |
| `uaMetadataDefault` | `browser` | object | The engine's own client hints: `navigator.userAgentData` brands, platform, mobile and the high-entropy values (`architecture`, `bitness`, `model`, `platformVersion`, `uaFullVersion`, `fullVersionList`, `wow64`). Backs `WebSettingsCompat.getUserAgentMetadata` before one is set. |
| `addStartScript` | `wid`, `script` (`id`, `source`, `origins`) | null, at once | `addDocumentStartJavaScript`: runs in every frame whose origin matches `origins` (Android's rules: `*`, `scheme://host[:port]`, `*.` host prefix), before the page's scripts, from the next document on. |
| `removeStartScript` | `wid`, `id` | null | `ScriptHandler.remove`: from the next document on. |
| `addMessageListener` | `wid`, `listener` (`name`, `origins`) | null, at once | `addWebMessageListener`: `window.<name>` with `postMessage(string or ArrayBuffer)`, `onmessage` and `addEventListener('message')` in matching frames, from the next document on. Posts arrive as `message` events. |
| `removeMessageListener` | `wid`, `name` | null | From the next document on; later posts from old documents are dropped. |
| `postReply` | `wid`, `frameId`, `name`, `data`, `arrayBuffer` | null | `JsReplyProxy.postMessage`: a `message` event on that frame's `window.<name>` (`data` base64 when `arrayBuffer`). |
| `target` | `wid` | string | The view's CDP page target id, for driving a held view from outside (`ext-run cdp`, `drive.py`). The backend also writes Chrome's DevTools port to `chrome.json` next to the socket when it starts the browser. |
| `reply` | `rid`, `value` | (never answered) | Answer to an `intercept` or `serve` event. |

`open` fields:

- `browser`: engine name; the JVM always sends `chrome`. A backend with one engine ignores it.
- `userAgent`: null = the engine's own UA. The JVM already maps "the source copied the app's default
  UA" to null.
- `bridges`: `[{"name": "<window property>", "methods": ["<method>", ...]}]` from
  `addJavascriptInterface`: `window.<name>.<method>(...args)` must exist in every frame's main world
  before page scripts run, and each call becomes a `bridge` event. Calls return `undefined` to the page.
- `tick` (default true): tick Turnstile checkboxes the page shows, as the app's user would (README
  "Turnstile"); each tick sends a `tick` event, and after one a new `cf_clearance` sends `cookies` at once.
- `blockImages`: fail image requests (`blocked`), without asking the JVM.
- `serve`, `live`, `record`: fixture mode, see "Requests". The JVM sends `serve` with `--fixtures` or
  `--map`, `live` unless `--offline`, `record` with `--fixtures` and not `--offline` (`serve`/`record`
  never for its own Cloudflare-solving views). `cookies`: the harness jar, to preload.

## Backend -> JVM: events

`{"ev": ..., "wid": n, ...}`. Events with `rid` need a `{"op": "reply", "rid": rid, "value": ...}`;
if the connection closes first, the backend treats every pending one as `null`.

| ev | fields | JVM does |
|---|---|---|
| `intercept` | `rid`, `req` | `shouldInterceptRequest` on an IO thread; replies a response object or `null`. |
| `serve` | `rid`, `req` (+ `postData`) | fixture / `--map` lookup; replies a response object (with `via`) or `null`. |
| `pageStarted` | `url` | `onPageStarted` (main thread), sets `getUrl()`. |
| `pageFinished` | `url` | `onPageFinished` + `onProgressChanged(100)`. |
| `cookies` | `cookies` | Copies them into the harness jar. Sent right before every `pageFinished`, and after a Turnstile tick when a new `cf_clearance` appears. |
| `tick` | `url`, `n` | The backend ticked a Turnstile checkbox (the `n`th on this page); logged on stderr, counted (`smoke` warning, the Cloudflare solve line). |
| `httpError` | `url`, `method`, `isMainFrame`, `status`, `contentType`, `headers` | `onReceivedHttpError` (main thread): sent for every response with status >= 400 when its headers arrive, so a main-frame error comes before that load's `pageFinished`. |
| `error` | `url`, `method`, `isMainFrame`, `description` | `onReceivedError`; dropped if `description` contains `ABORTED` or `cancel`. |
| `net` | `method`, `url`, `type`, `status`, `via`, `error`? | `wv#` request log line and counts. |
| `record` | `url`, `method`, `headers`, `postData`, `status`, `finalUrl`, `contentType`, `body` | Writes a fixture. |
| `bridge` | `name`, `method`, `args` | Calls the `@JavascriptInterface` method on the single `JavaBridge` thread. |
| `message` | `name`, `data`, `arrayBuffer`, `origin`, `isMainFrame`, `frameId` | `WebMessageListener.onPostMessage` on the main thread; `frameId` addresses `postReply`. |
| `console` | `level` (`log`/`debug`/`warning`/`error`), `text`, `source`, `line` | `WebChromeClient.onConsoleMessage` (core only logs it; printed with `-v`). |
| `crash` | | `onRenderProcessGone`. |
| `fatal` | `message` | `open` failed; treated as render process gone. |
| `log` | `text` | Printed to stderr (backend diagnostics). |

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
(`via: live`) without `intercept`/`serve`. So `isRedirect` is false in
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

## Lifetime

- The browser starts on the first `open`/`defaultUserAgent`, stays up while any view is open, and
  closes 10 minutes after the last view closed.
- A closed connection closes all its views (without replies).
- The process exits 60 minutes after the last connection closed; `ext-run.sh` starts it again on the
  next run (and recreates it when the backend's files or the compose file change).
- If the browser dies, open views get `crash`.

## Backend notes (nodriver)

| | nodriver (`bridge.py`) |
|---|---|
| `console` events | browser log entries (failed loads, CSP, interventions) and eval errors only: `console.*` and uncaught exceptions need `Runtime.enable`, which this backend never sends |
| JS interface transport | synchronous XHR to `https://ext-run.invalid/bridge/<token>`, answered inside the bridge (no `net`, no `intercept`) |
| `eval` error text | `Uncaught <exception description>`; BigInt is silently `"null"` |
| `error` text | `net::ERR_*` |
| service workers | bypassed (`Network.setBypassServiceWorker`) |
| engine extras | favicon requests dropped; http->https auto-upgrade off; speculation-rules fetches still happen |
| Turnstile | ticks a visible `challenges.cloudflare.com` iframe's checkbox with CDP mouse events on the page session, found with a pierced `DOM.getDocument`; only after the page requested that host |
| CSP bypass | the page and its in-process frames, not out-of-process iframes (bypassing CSP there fails Cloudflare's Turnstile) |
| `pageFinished` URL | URL at the `load` event; a `pushState` just before `load` may or may not be included |
| `record` | only bodies Chrome kept (`Network.getResponseBody`): a `fetch()` whose body the page never reads isn't recorded |
