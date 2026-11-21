"""ext-run's WebView backend on real Google Chrome, driven over raw CDP the way nodriver does it.

    python3 bridge.py /sock/nodriver.sock

Speaks the same line protocol as the Playwright backend (see ../PROTOCOL.md). Chrome runs headed
under Xvfb, launched without automation flags (so navigator.webdriver is false natively), and the
Runtime domain is never enabled: Runtime.enable is a known CDP detection signal (it makes V8
serialise console arguments). Everything here uses Runtime.evaluate / callFunctionOn,
Page.addScriptToEvaluateOnNewDocument, Fetch and Network instead.

Each WebView is one browser context (Target.createBrowserContext) with one page target. Chrome is
started on the first WebView and closed after IDLE_BROWSER_S without one; the process exits after
IDLE_EXIT_S without connections (ext-run.sh starts it again).
"""
import asyncio
import base64
import json
import re
import os
import secrets
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import time
from urllib.parse import urlsplit, urlunsplit

import websockets

SOCK = sys.argv[1]
CHROME = os.environ.get("CHROME_BIN", "/usr/bin/google-chrome")
IDLE_BROWSER_S = int(os.environ.get("EXT_RUN_WV_IDLE_BROWSER_S") or 10 * 60)
IDLE_EXIT_S = int(os.environ.get("EXT_RUN_WV_IDLE_EXIT_S") or 60 * 60)
RECORD_TYPES = {"document", "script", "xhr", "fetch", "stylesheet"}
WINDOW = (1366, 900)
VERBOSE = os.environ.get("EXT_RUN_WV_DEBUG") == "1"

# Turnstile ticking (see "Turnstile" in ../PROTOCOL.md): a widget gets this long to clear on its own
# before the first tick, at most TICK_MAX ticks TICK_GAP_S apart, and the watcher gives up on a page
# whose widget hasn't been visible for TICK_IDLE_S (after the last tick it keeps pushing a new
# cf_clearance that long).
TURNSTILE_HOST = "challenges.cloudflare.com"
TICK_FIRST_S = 8.0  # Cloudflare's automatic checks clear in 3-7 s; a spinner isn't clicked
TICK_GAP_S = 8.0
TICK_MAX = 3
TICK_IDLE_S = 30.0

# Diagnostics only (EXT_RUN_WV_CF_DEBUG=1): a screenshot + the widget box/click point before and after
# each tick, to see by eye whether a tick landed and what state it leaves the widget in. No effect at
# all when unset: this never changes detection or tick behaviour.
CF_DEBUG = os.environ.get("EXT_RUN_WV_CF_DEBUG") == "1"
CF_DEBUG_DIR = os.environ.get("EXT_RUN_WV_CF_DEBUG_DIR") or os.path.join(tempfile.gettempdir(), "cf-debug")

# nodriver's launch arguments (nodriver/core/config.py), headed, plus the GPU flags that make WebGL
# report the real GPU, no background throttling (several WebViews are windows on one screen), and no
# automatic http->https upgrade (desktop Chrome's HttpsUpgrades; a WebView doesn't do it).
#
# CI change: a runner has no real GPU (no /dev/dri). EXT_RUN_WV_NO_GPU=1 swaps the real-GPU ANGLE/Vulkan
# flags for software GL (SwiftShader) so Chrome still launches headed under Xvfb; WebGL then reports a
# software renderer instead of real hardware. Cloudflare's challenge/managed-check JS doesn't depend on
# that, only on navigator.webdriver / automation flags, which are unaffected either way.
if os.environ.get("EXT_RUN_WV_NO_GPU") == "1":
    GPU_ARGS = ["--use-gl=swiftshader", "--disable-gpu-sandbox"]
else:
    GPU_ARGS = ["--use-gl=angle", "--use-angle=vulkan", "--enable-features=Vulkan", "--ignore-gpu-blocklist"]

# The recheck proxy for the browser (harness/ci/recheck.py); unset for the sweep itself.
PROXY_ARGS = [f"--proxy-server={os.environ['EXT_RUN_WV_PROXY']}"] if os.environ.get("EXT_RUN_WV_PROXY") else []

# --remote-allow-origins is set per-launch (Browser.start(), with the actual --remote-debugging-
# port already known) to the exact "http://127.0.0.1:<port>" origin this bridge itself connects
# from — not "*". Nothing here is ever a browser page with its own Origin header to smuggle in;
# this process is the only intended client of that port, so there's no reason to accept every
# origin rather than just the one this bridge actually uses.
CHROME_ARGS = [
    "--no-first-run", "--no-service-autorun", "--no-default-browser-check",
    "--homepage=about:blank", "--no-pings", "--password-store=basic", "--disable-infobars", "--disable-breakpad",
    "--disable-dev-shm-usage", "--disable-session-crashed-bubble", "--disable-search-engine-choice-screen",
    "--disable-features=IsolateOrigins,site-per-process,HttpsUpgrades", "--no-sandbox", "--lang=en-US", *PROXY_ARGS,
    f"--window-size={WINDOW[0]},{WINDOW[1]}", *GPU_ARGS,
    "--disable-backgrounding-occluded-windows", "--disable-renderer-backgrounding", "--disable-background-timer-throttling",
]


def log(*a):
    print(*a, file=sys.stderr, flush=True)


def debug(*a):
    if VERBOSE:
        log(*a)


def same_url(a, b):
    def norm(u):
        try:
            s = urlsplit(u)
            return urlunsplit((s.scheme.lower(), s.netloc.lower(), s.path or "/", s.query, s.fragment))
        except ValueError:
            return u
    return norm(a) == norm(b)


# ---------------------------------------------------------------- CDP connection (flattened sessions)

class CDPError(Exception):
    pass


class CDP:
    def __init__(self, ws):
        self.ws = ws
        self.next_id = 1
        self.pending = {}
        self.handlers = {}  # sessionId ("" = browser) -> fn(method, params)
        self.closed = asyncio.get_running_loop().create_future()

    async def send(self, method, params=None, session=None):
        mid = self.next_id
        self.next_id += 1
        msg = {"id": mid, "method": method, "params": params or {}}
        if session:
            msg["sessionId"] = session
        fut = asyncio.get_running_loop().create_future()
        self.pending[mid] = fut
        try:
            await self.ws.send(json.dumps(msg))
        except Exception as e:
            self.pending.pop(mid, None)
            raise CDPError(f"{method}: connection closed ({e})")
        return await fut

    def fire(self, method, params=None, session=None):
        """send() without waiting; errors (closed targets etc.) are dropped."""
        t = asyncio.ensure_future(self.send(method, params, session))
        t.add_done_callback(lambda f: f.exception() if not f.cancelled() else None)
        return t

    async def run(self):
        try:
            async for raw in self.ws:
                m = json.loads(raw)
                if "id" in m:
                    fut = self.pending.pop(m["id"], None)
                    if fut and not fut.done():
                        if "error" in m:
                            fut.set_exception(CDPError(m["error"].get("message", str(m["error"]))))
                        else:
                            fut.set_result(m.get("result", {}))
                    continue
                h = self.handlers.get(m.get("sessionId", ""))
                if h:
                    try:
                        h(m["method"], m.get("params", {}))
                    except Exception as e:  # never kill the reader
                        log(f"event {m['method']} failed: {e!r}")
        except websockets.ConnectionClosed:
            pass
        finally:
            for fut in self.pending.values():
                if not fut.done():
                    fut.set_exception(CDPError("browser connection closed"))
            self.pending.clear()
            if not self.closed.done():
                self.closed.set_result(None)


# ---------------------------------------------------------------- the browser (one Chrome, lazily)

class Browser:
    def __init__(self):
        self.proc = None
        self.cdp = None
        self.dir = None
        self.user_agent = None
        self.on_session_gone = {}  # sessionId -> fn() when Chrome dies

    async def start(self):
        self.dir = tempfile.mkdtemp(prefix="chrome-")
        # A real port, as nodriver does: with --remote-debugging-port=0 Chrome sets navigator.webdriver.
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", 0))
            port = probe.getsockname()[1]
        args = [CHROME, f"--user-data-dir={self.dir}", *CHROME_ARGS,
                f"--remote-allow-origins=http://127.0.0.1:{port}",
                "--remote-debugging-host=127.0.0.1", f"--remote-debugging-port={port}"]
        self.proc = subprocess.Popen(args, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                                     stderr=None if VERBOSE else subprocess.DEVNULL, start_new_session=True)
        ws_url = None
        for _ in range(300):
            if self.proc.poll() is not None:
                raise RuntimeError(f"chrome exited with {self.proc.returncode}")
            try:
                r, w = await asyncio.open_connection("127.0.0.1", port)
                w.write(f"GET /json/version HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\nConnection: close\r\n\r\n".encode())
                head = (await asyncio.wait_for(r.readuntil(b"\r\n\r\n"), 2)).decode("latin-1").lower()
                length = int(head.split("content-length:", 1)[1].split("\r\n", 1)[0])
                raw = await asyncio.wait_for(r.readexactly(length), 2)
                w.close()
                ws_url = json.loads(raw)["webSocketDebuggerUrl"]
                break
            except (OSError, ValueError, KeyError, IndexError, asyncio.TimeoutError, asyncio.IncompleteReadError):
                await asyncio.sleep(0.05)
        if not ws_url:
            raise RuntimeError("chrome didn't open its DevTools port")
        ws = await websockets.connect(ws_url, max_size=None, ping_interval=None)
        # For drive.py (`ext-run cdp`): the DevTools port, next to the socket, so a held view can be driven.
        with open(os.path.join(os.path.dirname(SOCK), "chrome.json"), "w") as f:
            json.dump({"port": port}, f)
        self.cdp = CDP(ws)
        asyncio.ensure_future(self.cdp.run())
        self.user_agent = (await self.cdp.send("Browser.getVersion"))["userAgent"].replace("HeadlessChrome/", "Chrome/")
        log(f"chrome started: {self.user_agent}")

    async def close(self):
        try:
            await asyncio.wait_for(self.cdp.send("Browser.close"), 5)
        except Exception:
            pass
        try:
            await self.cdp.ws.close()
        except Exception:
            pass
        self.kill()

    def kill(self):
        if self.proc and self.proc.poll() is None:
            try:
                os.killpg(self.proc.pid, signal.SIGKILL)
            except Exception:
                pass
        if self.dir:
            shutil.rmtree(self.dir, ignore_errors=True)


browser_task = None  # asyncio.Task[Browser]
live_contexts = 0
browser_idle = None
exit_timer = None
connections = 0


async def get_browser():
    global browser_task
    if browser_task is None:
        async def launch():
            b = Browser()
            try:
                await b.start()
            except BaseException:
                b.kill()
                raise

            async def watch():
                global browser_task
                await b.cdp.closed
                if browser_task is not None and browser_task.done() and not browser_task.cancelled() \
                        and browser_task.exception() is None and browser_task.result() is b:
                    browser_task = None
                b.kill()
                for fn in list(b.on_session_gone.values()):
                    fn()
            asyncio.ensure_future(watch())
            return b
        browser_task = asyncio.ensure_future(launch())
        task = browser_task

        def failed(t):
            global browser_task
            if (t.cancelled() or t.exception()) and browser_task is t:
                browser_task = None
        task.add_done_callback(failed)
    return await asyncio.shield(browser_task)


def context_opened():
    global live_contexts, browser_idle
    live_contexts += 1
    if browser_idle:
        browser_idle.cancel()
        browser_idle = None


def context_closed():
    global live_contexts, browser_idle
    live_contexts -= 1
    if live_contexts > 0:
        return
    if browser_idle:
        browser_idle.cancel()

    async def close_later():
        global browser_task
        await asyncio.sleep(IDLE_BROWSER_S)
        t, browser_task = browser_task, None
        if t and t.done() and not t.cancelled() and t.exception() is None:
            await t.result().close()
    browser_idle = asyncio.ensure_future(close_later())


def arm_exit():
    global exit_timer
    if exit_timer:
        exit_timer.cancel()
        exit_timer = None
    if connections == 0:
        exit_timer = asyncio.get_running_loop().call_later(IDLE_EXIT_S, lambda: os._exit(0))


# ---------------------------------------------------------------- page-side scripts

BRIDGE_URL = "https://ext-run.invalid/bridge/"


def bridge_script(url, bridges):
    """window.<name>.<method>(...) for every JS interface, like addJavascriptInterface.

    A call is a synchronous XHR to a per-WebView URL that the Fetch interceptor answers itself (it
    never reaches the network or the JVM's intercept). Not a CDP binding: Runtime.addBinding only
    reaches documents created later while the Runtime domain is enabled, which this backend never
    does. Synchronous like Android's calls, so they arrive in order and before a following
    navigation; they return undefined (Android returns the method's value). XHR, JSON.stringify
    and Function.prototype.call are captured before page scripts can replace them."""
    return f"""(() => {{
  const U = {json.dumps(url)}, defs = {json.dumps(bridges)};
  const XHR = XMLHttpRequest, open = XHR.prototype.open, send = XHR.prototype.send, call = Function.prototype.call;
  const stringify = JSON.stringify;
  for (const {{ name, methods }} of defs) {{
    const obj = {{}};
    for (const m of methods) obj[m] = function (...args) {{
      const body = stringify([name, m, args]);
      try {{ const x = new XHR(); call.call(open, x, 'POST', U, false); call.call(send, x, body); }} catch (e) {{}}
    }};
    Object.defineProperty(window, name, {{ value: obj, configurable: true, writable: true }});
  }}
}})();"""


# androidx.webkit features (see the JVM's WebViewGlue) this backend implements.
FEATURES = ["DOCUMENT_START_SCRIPT:1", "WEB_MESSAGE_LISTENER", "WEB_MESSAGE_ARRAY_BUFFER", "USER_AGENT_METADATA",
            "COOKIE_INTERCEPT", "MUTE_AUDIO", "MULTI_PROFILE", "CUSTOM_REQUEST_HEADERS"]


def origin_allowed(url, rules):
    """ALLOWED_JS on this side: does url's origin match one of Android's origin rules."""
    try:
        u = urlsplit(url)
        host, port = (u.hostname or "").lower(), u.port
    except ValueError:
        return False
    default = {"http": 80, "https": 443}.get(u.scheme)
    for r in rules:
        if r == "*":
            return True
        m = re.match(r"^([a-z][a-z0-9+.-]*)://(\*\.)?([^:/]+)(?::(\d+))?$", r, re.I)
        if not m or u.scheme != m.group(1).lower():
            continue
        if (int(m.group(4)) if m.group(4) else default) != (port or default):
            continue
        rh = m.group(3).lower()
        if (host.endswith("." + rh) if m.group(2) else host == rh):
            return True
    return False


def origin_headers(st, url):
    """Profile.setOriginMatchedHeader (CUSTOM_REQUEST_HEADERS): the headers for a request to url."""
    return {h["name"]: h["value"] for h in st.origin_headers if origin_allowed(url, h["rules"])}

# Android's origin rules ("*", scheme://host[:port], "*." host prefix for subdomains) checked in the page.
ALLOWED_JS = r"""(o, rules) => rules.some(r => {
  if (r === '*') return true;
  const m = /^([a-z][a-z0-9+.-]*):\/\/(\*\.)?([^:/]+)(?::(\d+))?$/i.exec(r);
  if (!m) return false;
  let u; try { u = new URL(o); } catch (e) { return false; }
  const dp = { 'http:': '80', 'https:': '443' };
  if (u.protocol !== m[1].toLowerCase() + ':') return false;
  if ((m[4] || dp[u.protocol]) !== (u.port || dp[u.protocol])) return false;
  const h = u.hostname.toLowerCase(), rh = m[3].toLowerCase();
  return m[2] ? h.endsWith('.' + rh) : h === rh;
})"""


def start_script_source(source, origins):
    """addDocumentStartJavaScript: every frame whose origin matches, before the page's scripts. With
    specific origins the script runs inside an if block (top-level let/const/class become block-scoped)."""
    if "*" in origins:
        return source
    return f"if (({ALLOWED_JS})(location.origin, {json.dumps(origins)})) {{\n{source}\n}}"


def listener_script(url, name, origins, evt):
    """addWebMessageListener: window.<name> with postMessage / onmessage / addEventListener('message') in
    frames whose origin matches. postMessage goes out like a JS interface call (synchronous XHR to the
    view's bridge URL, answered by the Fetch handler); replies come back as a CustomEvent on the frame's
    document, dispatched from an isolated world (no Runtime.enable)."""
    return f"""(() => {{
  if (!(({ALLOWED_JS})(location.origin, {json.dumps(origins)}))) return;
  const U = {json.dumps(url)}, NAME = {json.dumps(name)}, EVT = {json.dumps(evt)};
  const XHR = XMLHttpRequest, open = XHR.prototype.open, send = XHR.prototype.send, call = Function.prototype.call;
  const stringify = JSON.stringify, parse = JSON.parse, top = window === window.top;
  const listeners = new Set(); let onmessage = null;
  const b64 = buf => {{ let s = ''; const a = new Uint8Array(buf); for (let i = 0; i < a.length; i++) s += String.fromCharCode(a[i]); return btoa(s); }};
  const unb64 = s => {{ const t = atob(s), a = new Uint8Array(t.length); for (let i = 0; i < t.length; i++) a[i] = t.charCodeAt(i); return a.buffer; }};
  const obj = {{
    postMessage(data) {{
      const buf = data instanceof ArrayBuffer;
      const body = stringify({{ k: 'm', name: NAME, data: buf ? b64(data) : String(data), arrayBuffer: buf, origin: location.origin, top }});
      try {{ const x = new XHR(); call.call(open, x, 'POST', U, false); call.call(send, x, body); }} catch (e) {{}}
    }},
    addEventListener(type, fn) {{ if (type === 'message' && typeof fn === 'function') listeners.add(fn); }},
    removeEventListener(type, fn) {{ if (type === 'message') listeners.delete(fn); }},
    get onmessage() {{ return onmessage; }},
    set onmessage(fn) {{ onmessage = typeof fn === 'function' ? fn : null; }},
  }};
  document.addEventListener(EVT, e => {{
    let d; try {{ d = parse(e.detail); }} catch (err) {{ return; }}
    const ev = new MessageEvent('message', {{ data: d.b ? unb64(d.d) : d.d }});
    for (const fn of [onmessage, ...listeners]) if (fn) try {{ fn.call(obj, ev); }} catch (err) {{ setTimeout(() => {{ throw err; }}); }}
  }});
  Object.defineProperty(window, NAME, {{ value: obj, configurable: true, writable: true }});
}})();"""


UA_META = None


async def ua_metadata_default():
    """The browser's own client hints (navigator.userAgentData), read once from a page on a secure origin
    the Fetch handler answers itself (userAgentData needs a secure context; nothing goes to the network)."""
    global UA_META
    if UA_META:
        return UA_META
    b = await get_browser()
    ctx = (await b.cdp.send("Target.createBrowserContext", {"disposeOnDetach": True}))["browserContextId"]
    context_opened()
    try:
        target = (await b.cdp.send("Target.createTarget", {"url": "about:blank", "browserContextId": ctx}))["targetId"]
        sid = (await b.cdp.send("Target.attachToTarget", {"targetId": target, "flatten": True}))["sessionId"]

        def on_event(method, p):
            if method == "Fetch.requestPaused":
                b.cdp.fire("Fetch.fulfillRequest", fulfil_params(p["requestId"], {
                    "status": 200, "contentType": "text/html", "body": ""}), sid)
        b.cdp.handlers[sid] = on_event
        await b.cdp.send("Fetch.enable", {"patterns": [{"urlPattern": "https://ext-run.invalid/*"}]}, sid)
        await b.cdp.send("Page.navigate", {"url": "https://ext-run.invalid/ua"}, sid)
        expr = ("navigator.userAgentData.getHighEntropyValues(['architecture','bitness','model','platformVersion',"
                "'uaFullVersion','fullVersionList','wow64']).then(v => JSON.stringify(v))")
        for _ in range(50):
            r = await b.cdp.send("Runtime.evaluate", {"expression": "location.href", "returnByValue": True}, sid)
            if r.get("result", {}).get("value", "").startswith("https://ext-run.invalid/"):
                break
            await asyncio.sleep(0.1)
        r = await b.cdp.send("Runtime.evaluate", {"expression": expr, "awaitPromise": True, "returnByValue": True}, sid)
        UA_META = json.loads(r["result"]["value"])
        return UA_META
    finally:
        try:
            await b.cdp.send("Target.disposeBrowserContext", {"browserContextId": ctx})
        except CDPError:
            pass
        context_closed()


STRINGIFY = "function () { try { const s = JSON.stringify(this); return s === undefined ? 'null' : s; } catch (e) { return 'null'; } }"


def cdp_cookie(c):
    """A protocol cookie (Playwright shape) as a CDP CookieParam. A domain without a leading dot is
    host-only, which CDP only creates from a url (a bare domain makes a domain cookie)."""
    out = {"name": c["name"], "value": c.get("value", ""), "path": c.get("path") or "/",
           "secure": bool(c.get("secure")), "httpOnly": bool(c.get("httpOnly"))}
    domain = c.get("domain") or ""
    if domain.startswith("."):
        out["domain"] = domain
    else:
        out["url"] = f"{'https' if c.get('secure') else 'http'}://{domain}{out['path']}"
    if (c.get("expires") or -1) > 0:
        out["expires"] = c["expires"]
    if c.get("sameSite") in ("Strict", "Lax", "None"):
        out["sameSite"] = c["sameSite"]
    return out


def proto_cookie(c):
    return {"name": c["name"], "value": c["value"], "domain": c["domain"], "path": c["path"],
            "expires": c.get("expires", -1) if not c.get("session") else -1,
            "httpOnly": c.get("httpOnly", False), "secure": c.get("secure", False),
            "sameSite": c.get("sameSite", "Lax")}


def header_list(h):
    return [{"name": k, "value": str(v)} for k, v in h.items()]


def fulfil_params(rid, r):
    headers = dict(r.get("headers") or {})
    if r.get("contentType") and not any(k.lower() == "content-type" for k in headers):
        headers["content-type"] = r["contentType"]
    return {"requestId": rid, "responseCode": r.get("status") or 200, "responseHeaders": header_list(headers),
            "body": r.get("body") or ""}


# ---------------------------------------------------------------- one JVM connection

class View:
    def __init__(self, m):
        self.wid = m["wid"]
        self.block_images = bool(m.get("blockImages"))
        self.serve = bool(m.get("serve"))
        self.live = bool(m.get("live"))
        self.record = bool(m.get("record"))
        self.user_agent = m.get("userAgent")
        self.js = m.get("javaScriptEnabled") is not False
        self.pending_data = None
        self.nav_headers = None
        self.blank_requested = False
        self.ctx = None
        self.target = None
        self.session = None
        self.sessions = set()
        self.scripts = []      # (key, source) init scripts, applied to every page/iframe session; key None = not removable
        self.script_ids = {}   # (session, key) -> CDP script identifier, for removal
        self.listeners = {}    # web message listener name -> event name its replies are dispatched as
        self.frame_sid = {}    # frameId -> the session it posted a message from (replies go back there)
        self.include_cookies = bool(m.get("includeCookies"))
        self.ua_metadata = m.get("uaMetadata")
        self.origin_headers = m.get("originHeaders") or []  # [{name, value, rules}], the profile's origin-matched headers
        self.bridge_url = BRIDGE_URL + secrets.token_hex(8)
        self.url = "about:blank"
        self.via = {}          # (networkId, url) -> via
        self.reqs = {}         # requestId -> request info from Network
        self.closed = False
        self.recording = set()  # record tasks still reading bodies; close waits for them
        self.ready = None
        self.b = None
        self.tick = m.get("tick") is not False  # tick Turnstile checkboxes the way the app's user would
        self.tick_task = None
        self.ticks = 0  # Turnstile ticks on this view, across watcher restarts (TICK_MAX is per view)
        self.clearance_sent = set()  # cf_clearance (domain, value) pairs already pushed after a tick


class Client:
    def __init__(self, reader, writer):
        self.reader = reader
        self.writer = writer
        self.views = {}
        self.pending = {}
        self.next_rid = 1

    def send(self, msg):
        if self.writer.is_closing():
            return
        self.writer.write((json.dumps(msg, separators=(",", ":")) + "\n").encode())

    def ask(self, msg):
        rid = self.next_rid
        self.next_rid += 1
        fut = asyncio.get_running_loop().create_future()
        self.pending[rid] = fut
        self.send({**msg, "rid": rid})
        return fut

    # ---- opening a WebView

    async def open(self, st, m):
        b = st.b = await get_browser()
        st.ctx = (await b.cdp.send("Target.createBrowserContext", {"disposeOnDetach": True}))["browserContextId"]
        context_opened()
        if st.closed:
            return
        st.target = (await b.cdp.send("Target.createTarget", {
            "url": "about:blank", "browserContextId": st.ctx, "newWindow": True,
            "width": WINDOW[0], "height": WINDOW[1]}))["targetId"]
        if m.get("cookies"):
            try:
                await b.cdp.send("Storage.setCookies", {"cookies": [cdp_cookie(c) for c in m["cookies"]], "browserContextId": st.ctx})
            except CDPError as e:
                self.send({"ev": "log", "wid": st.wid, "text": f"cookies not copied: {e}"})
        if m.get("bridges"):
            self.add_bridges(st, m["bridges"], apply=False)
        for s in m.get("startScripts") or []:
            self.add_start_script(st, s, apply=False)
        for listener in m.get("messageListeners") or []:
            self.add_listener(st, listener, apply=False)
        sid = (await b.cdp.send("Target.attachToTarget", {"targetId": st.target, "flatten": True}))["sessionId"]
        st.session = sid
        await self.setup_session(st, sid, "page")

    async def setup_session(self, st, sid, kind):
        b = st.b
        st.sessions.add(sid)
        b.cdp.handlers[sid] = lambda method, p: self.on_event(st, sid, kind, method, p)
        b.on_session_gone[sid] = lambda: self.gone(st, sid, kind)
        send = lambda method, params=None: b.cdp.send(method, params, sid)
        cmds = [
            send("Network.enable"),
            send("Network.setCacheDisabled", {"cacheDisabled": True}),
            # Service workers would answer requests before Fetch sees them (and fetch our bridge URL).
            send("Network.setBypassServiceWorker", {"bypass": True}),
            send("Fetch.enable", {"patterns": [{"urlPattern": "*", "requestStage": "Request"}]}),
        ]
        if st.user_agent or st.ua_metadata:
            cmds.append(send("Network.setUserAgentOverride", self.ua_override(st)))
        if kind == "page":
            # Only the page (and its in-process frames): bypassing CSP inside an out-of-process
            # iframe makes Cloudflare's Turnstile frame fail its managed challenge (checked on
            # mangadot.net: with it, no cf_clearance in 30 s; without it, cleared in ~5 s).
            cmds.append(send("Page.setBypassCSP", {"enabled": True}))
        if kind in ("page", "iframe"):
            cmds += [
                send("Page.enable"),
                send("Inspector.enable"),
                send("Log.enable"),
                send("Target.setAutoAttach", {"autoAttach": True, "waitForDebuggerOnStart": True, "flatten": True}),
            ]
            if not st.js:
                cmds.append(send("Emulation.setScriptExecutionDisabled", {"value": True}))
            keyed = []
            for key, src in st.scripts:
                cmds.append(send("Page.addScriptToEvaluateOnNewDocument", {"source": src}))
                keyed.append((len(cmds) - 1, key))
        else:
            keyed = []
        res = await asyncio.gather(*cmds, return_exceptions=True)
        for i, key in keyed:
            if key is not None and isinstance(res[i], dict):
                st.script_ids[(sid, key)] = res[i].get("identifier")
        errs = [r for r in res if isinstance(r, Exception)]
        if errs and kind == "page":
            raise errs[0]
        if kind != "page":
            b.cdp.fire("Runtime.runIfWaitingForDebugger", None, sid)

    def add_bridges(self, st, bridges, apply=True):
        src = bridge_script(st.bridge_url, bridges)
        st.scripts.append((None, src))
        if apply:
            for sid in list(st.sessions):
                st.b.cdp.fire("Page.addScriptToEvaluateOnNewDocument", {"source": src}, sid)

    def ua_override(self, st):
        params = {"userAgent": st.user_agent or st.b.user_agent}
        if st.ua_metadata:
            params["userAgentMetadata"] = st.ua_metadata
        return params

    def add_script(self, st, key, src, apply):
        st.scripts.append((key, src))
        if not apply:
            return

        async def add(sid):
            try:
                r = await st.b.cdp.send("Page.addScriptToEvaluateOnNewDocument", {"source": src}, sid)
                st.script_ids[(sid, key)] = r.get("identifier")
            except CDPError:
                pass
        for sid in list(st.sessions):
            asyncio.ensure_future(add(sid))

    def remove_script(self, st, key):
        st.scripts = [(k, s) for k, s in st.scripts if k != key]
        for sid in list(st.sessions):
            ident = st.script_ids.pop((sid, key), None)
            if ident:
                st.b.cdp.fire("Page.removeScriptToEvaluateOnNewDocument", {"identifier": ident}, sid)

    def add_start_script(self, st, s, apply=True):
        self.add_script(st, ("start", s["id"]), start_script_source(s["source"], s.get("origins") or ["*"]), apply)

    def add_listener(self, st, listener, apply=True):
        name, evt = listener["name"], "ext-run-message-" + secrets.token_hex(6)
        st.listeners[name] = evt
        self.add_script(st, ("listener", name), listener_script(st.bridge_url, name, listener.get("origins") or ["*"], evt), apply)

    async def post_reply(self, st, m):
        evt = st.listeners.get(m["name"])
        if not evt:
            return
        frame = m["frameId"]
        sid = st.frame_sid.get(frame, st.session)
        cdp = st.b.cdp
        detail = json.dumps({"d": m["data"], "b": bool(m.get("arrayBuffer"))})
        try:
            ctx = (await cdp.send("Page.createIsolatedWorld", {"frameId": frame, "worldName": "ext-run-reply"}, sid))["executionContextId"]
            await cdp.send("Runtime.evaluate", {
                "expression": f"document.dispatchEvent(new CustomEvent({json.dumps(evt)}, {{ detail: {json.dumps(detail)} }}))",
                "contextId": ctx}, sid)
        except CDPError as e:
            self.send({"ev": "log", "wid": st.wid, "text": f"reply to {m['name']} not delivered: {e}"})

    def gone(self, st, sid, kind):
        st.sessions.discard(sid)
        if kind == "page" and not st.closed:
            self.send({"ev": "crash", "wid": st.wid})

    # ---- CDP events of one session

    def ours(self, st, url):
        # A new page's own about:blank isn't a load the WebView asked for; Android reports no events for it.
        return url != "about:blank" or st.blank_requested

    def on_event(self, st, sid, kind, method, p):
        if st.closed:
            return
        main = kind == "page"
        if method == "Fetch.requestPaused":
            asyncio.ensure_future(self.on_request(st, sid, main, p))
        elif method == "Network.requestWillBeSent":
            self.on_request_will_be_sent(st, sid, p)
        elif method == "Network.responseReceived":
            r = st.reqs.get(p["requestId"])
            if r is not None:
                resp = p["response"]
                r["sid"] = sid
                r["status"] = resp.get("status", 0)
                r["finalUrl"] = resp.get("url")
                r["contentType"] = next((v for k, v in resp.get("headers", {}).items() if k.lower() == "content-type"), None) \
                    or resp.get("mimeType")
                # Android's onReceivedHttpError: any response >= 400, when its headers arrive (so before pageFinished).
                if r["status"] >= 400:
                    self.send({"ev": "httpError", "wid": st.wid, "url": r["url"], "method": r["method"],
                               "isMainFrame": r["isMainFrame"], "status": r["status"],
                               "contentType": r["contentType"], "headers": resp.get("headers", {})})
        elif method == "Network.loadingFinished":
            r = st.reqs.pop(p["requestId"], None)
            if r is not None:
                self.finished(st, sid, r, p["requestId"])
        elif method == "Network.loadingFailed":
            r = st.reqs.pop(p["requestId"], None)
            if r is not None:
                self.failed(st, r, p)
        elif method == "Page.frameNavigated" and main:
            f = p["frame"]
            if not f.get("parentId"):
                st.url = f["url"] + f.get("urlFragment", "")
                if self.ours(st, st.url):
                    self.send({"ev": "pageStarted", "wid": st.wid, "url": st.url})
        elif method == "Page.navigatedWithinDocument" and main:
            if p["frameId"] == st.target:
                st.url = p["url"]
                if self.ours(st, st.url):
                    self.send({"ev": "pageStarted", "wid": st.wid, "url": st.url})
        elif method == "Page.loadEventFired" and main:
            if self.ours(st, st.url):
                asyncio.ensure_future(self.page_finished(st, st.url))
        elif method == "Log.entryAdded":
            e = p["entry"]
            if e.get("url", "").startswith(BRIDGE_URL) or e.get("text", "").startswith("[Deprecation] Synchronous XMLHttpRequest"):
                return
            level = {"warning": "warning", "error": "error", "verbose": "debug"}.get(e.get("level"), "log")
            self.send({"ev": "console", "wid": st.wid, "level": level, "text": e.get("text", ""),
                       "source": e.get("url", ""), "line": e.get("lineNumber", 0)})
        elif method == "Inspector.targetCrashed" and main:
            self.send({"ev": "crash", "wid": st.wid})
        elif method == "Target.attachedToTarget":
            info = p["targetInfo"]
            child = p["sessionId"]
            t = info.get("type")
            if t in ("iframe", "worker"):
                asyncio.ensure_future(self.setup_child(st, child, t))
            else:
                st.b.cdp.fire("Runtime.runIfWaitingForDebugger", None, child)
        elif method == "Target.detachedFromTarget":
            child = p.get("sessionId")
            st.sessions.discard(child)
            st.b.cdp.handlers.pop(child, None)
            st.b.on_session_gone.pop(child, None)
        elif method == "Inspector.detached" and main:
            pass

    async def setup_child(self, st, sid, kind):
        try:
            await self.setup_session(st, sid, kind)
        except Exception as e:
            debug(f"child {kind} setup failed: {e}")

    async def page_finished(self, st, url):
        try:
            cookies = (await st.b.cdp.send("Storage.getCookies", {"browserContextId": st.ctx}))["cookies"]
            self.send({"ev": "cookies", "wid": st.wid, "cookies": [proto_cookie(c) for c in cookies]})
        except Exception:
            pass
        if not st.closed:
            self.send({"ev": "pageFinished", "wid": st.wid, "url": url})

    # ---- Turnstile: tick the checkbox like the app's user does

    def watch_turnstile(self, st):
        """Started by the page's first request to Cloudflare's challenge host; pages without a
        Turnstile widget never get here, so nothing is ever clicked on them."""
        if st.ticks < TICK_MAX and (st.tick_task is None or st.tick_task.done()):
            st.tick_task = asyncio.ensure_future(self.tick_turnstile(st))

    async def turnstile_box(self, st):
        """The Turnstile iframe's box (x, y, width, height) in the page's viewport, or None while there
        is none or it isn't shown. The DOM is read pierced (the widget sits in a closed shadow root on
        Cloudflare's own pages), on the page session, without Runtime."""
        cdp, sid = st.b.cdp, st.session
        try:
            root = (await cdp.send("DOM.getDocument", {"depth": -1, "pierce": True}, sid))["root"]
        except CDPError:
            return None
        stack = [root]
        while stack:
            n = stack.pop()
            if n.get("nodeName") == "IFRAME":
                attrs = n.get("attributes", [])
                src = next((attrs[i + 1] for i in range(0, len(attrs) - 1, 2) if attrs[i] == "src"), "")
                if urlsplit(src).hostname == TURNSTILE_HOST:
                    try:
                        await cdp.send("DOM.scrollIntoViewIfNeeded", {"backendNodeId": n["backendNodeId"]}, sid)
                        m = (await cdp.send("DOM.getBoxModel", {"backendNodeId": n["backendNodeId"]}, sid))["model"]
                    except CDPError:
                        continue  # display:none (an interaction-only widget that needs nothing yet)
                    q = m["content"]
                    if m["width"] >= 100 and m["height"] >= 40:
                        return q[0], q[1], m["width"], m["height"]
            stack += n.get("children", []) + n.get("shadowRoots", []) + ([n["contentDocument"]] if "contentDocument" in n else [])
        return None

    async def click(self, st, x, y):
        """A user's tap with a mouse: in from the side, a pause, press, release; on the page session
        (CDP routes it into the widget's frame). The window comes to the front first, as the WebView a
        user taps is the one on screen."""
        cdp, sid = st.b.cdp, st.session
        await cdp.send("Page.bringToFront", None, sid)
        await cdp.send("Input.dispatchMouseEvent", {"type": "mouseMoved", "x": x - 60, "y": y + 25}, sid)
        await asyncio.sleep(0.12)
        await cdp.send("Input.dispatchMouseEvent", {"type": "mouseMoved", "x": x, "y": y}, sid)
        await asyncio.sleep(0.2)
        for kind in ("mousePressed", "mouseReleased"):
            await cdp.send("Input.dispatchMouseEvent", {"type": kind, "x": x, "y": y, "button": "left", "clickCount": 1}, sid)
            await asyncio.sleep(0.09)

    async def push_clearance(self, st):
        """After a tick, a new cf_clearance goes to the JVM's jar at once (a `cookies` event), as the app's
        CookieManager has it the moment the WebView does: the page it unlocks may not fire `load` for
        a long time (ads), and a user who ticked doesn't wait for that."""
        cookies = (await st.b.cdp.send("Storage.getCookies", {"browserContextId": st.ctx}))["cookies"]
        clearance = {(c["domain"], c["value"]) for c in cookies if c["name"] == "cf_clearance"}
        if clearance - st.clearance_sent:
            st.clearance_sent = clearance
            self.send({"ev": "cookies", "wid": st.wid, "cookies": [proto_cookie(c) for c in cookies]})

    async def cf_debug_shot(self, st, stage, box=None, point=None):
        """Diagnostics only: a screenshot of the page plus the widget box/click point, saved under
        CF_DEBUG_DIR. No-op unless EXT_RUN_WV_CF_DEBUG=1."""
        if not CF_DEBUG or st.closed or not st.b:
            return
        try:
            os.makedirs(CF_DEBUG_DIR, exist_ok=True)
            r = await st.b.cdp.send("Page.captureScreenshot", {"format": "png"}, st.session)
            path = os.path.join(CF_DEBUG_DIR, f"wid{st.wid}-{stage}.png")
            with open(path, "wb") as f:
                f.write(base64.b64decode(r["data"]))
            log(f"cf-debug: wid={st.wid} stage={stage} box={box} click_point={point} -> {path}")
        except Exception as e:
            log(f"cf-debug: wid={st.wid} stage={stage} box={box} click_point={point} (screenshot failed: {e})")

    async def tick_turnstile(self, st):
        """While the page shows a Turnstile widget: let it clear on its own for TICK_FIRST_S, then tick
        its checkbox (28 px in from the left, vertically centred), at most TICK_MAX times per view. Each
        tick is reported (`tick` event) so a ticked pass is never mistaken for one that needed no user.
        After a tick, a new cf_clearance is pushed to the JVM as soon as it appears."""
        shown_at, last_seen, last_tick = None, time.monotonic(), 0.0
        try:
            while not st.closed:
                now = time.monotonic()
                box = await self.turnstile_box(st) if st.ticks < TICK_MAX else None
                if box is None:
                    shown_at = None
                    if now - last_seen > TICK_IDLE_S:
                        return
                else:
                    last_seen = now
                    shown_at = shown_at or now
                    if now - shown_at >= TICK_FIRST_S and now - last_tick >= TICK_GAP_S:
                        x, y, w, h = box
                        await self.cf_debug_shot(st, f"before-tick{st.ticks + 1}", box, (x + 28, y + h / 2))
                        await self.click(st, x + 28, y + h / 2)
                        st.ticks += 1
                        last_tick = last_seen = time.monotonic()
                        self.send({"ev": "tick", "wid": st.wid, "url": st.url, "n": st.ticks})
                        if CF_DEBUG:
                            await asyncio.sleep(2)
                        await self.cf_debug_shot(st, f"after-tick{st.ticks}", box, (x + 28, y + h / 2))
                if st.ticks:
                    await self.push_clearance(st)
                await asyncio.sleep(1)
        except (CDPError, KeyError, ValueError) as e:
            debug(f"turnstile watcher stopped: {e}")
        finally:
            # getDocument turned the DOM agent on; its events would keep coming for the page's life.
            if not st.closed:
                st.b.cdp.fire("DOM.disable", None, st.session)

    # ---- network bookkeeping (the `net`, `error` and `record` events)

    def via_of(self, st, network_id, url):
        return st.via.get((network_id, url), "live")

    def on_request_will_be_sent(self, st, sid, p):
        req = p["request"]
        url = req["url"] + req.get("urlFragment", "")
        if url.startswith(("data:", "blob:", BRIDGE_URL)) or self.favicon(p.get("type"), url):
            return
        if st.tick and urlsplit(url).hostname == TURNSTILE_HOST:
            self.watch_turnstile(st)
        # Keyed by requestId alone: for an out-of-process iframe the request starts on the parent's
        # session and finishes on the child's.
        key = p["requestId"]
        prev = st.reqs.get(key)
        if prev is not None and p.get("redirectResponse"):
            # The previous hop ended in a redirect: Playwright reports it as a finished request.
            prev["status"] = p["redirectResponse"].get("status", 0)
            self.net(st, prev, prev["status"], self.via_of(st, p["requestId"], prev["url"]))
        st.reqs[key] = {
            "url": url, "method": req.get("method", "GET"), "type": (p.get("type") or "other").lower(),
            "frameId": p.get("frameId"), "headers": req.get("headers", {}), "status": 0,
            "isMainFrame": (p.get("type") == "Document" and p.get("frameId") == st.target and sid == st.session),
            "postData": None,
        }

    def net(self, st, r, status, via, error=None):
        msg = {"ev": "net", "wid": st.wid, "method": r["method"], "url": r["url"], "type": r["type"],
               "status": status, "via": via}
        if error is not None:
            msg["error"] = error
        self.send(msg)

    def finished(self, st, sid, r, request_id):
        via = self.via_of(st, request_id, r["url"])
        self.net(st, r, r["status"], via)
        if self.recordable(st, r, via):
            t = asyncio.ensure_future(self.record(st, sid, r, request_id))
            st.recording.add(t)
            t.add_done_callback(st.recording.discard)
            return t
        return None

    @staticmethod
    def recordable(st, r, via):
        return st.record and via == "live" and r["type"] in RECORD_TYPES and not (300 <= r["status"] < 400)

    async def record(self, st, sid, r, request_id):
        try:
            body = await asyncio.wait_for(st.b.cdp.send("Network.getResponseBody", {"requestId": request_id}, sid), 5)
        except Exception as e:
            debug(f"record {r['url']}: {e}")
            return
        data = body["body"] if body.get("base64Encoded") else base64.b64encode(body["body"].encode()).decode()
        info = st.via.get((request_id, r["url"], "req")) or {}
        self.send({
            "ev": "record", "wid": st.wid, "url": r["url"], "method": r["method"],
            "headers": info.get("headers") or r["headers"], "postData": info.get("postData"),
            "status": r["status"], "finalUrl": r.get("finalUrl") or r["url"], "contentType": r.get("contentType"),
            "body": data,
        })

    def failed(self, st, r, p):
        via = st.via.get((p["requestId"], r["url"]))
        text = p.get("errorText") or "failed"
        if p.get("canceled") and text in ("", "net::ERR_FAILED"):
            text = "net::ERR_ABORTED"
        self.net(st, r, 0, via or "failed", text)
        if via in ("blocked", "miss"):
            return
        self.send({"ev": "error", "wid": st.wid, "url": r["url"], "method": r["method"],
                   "isMainFrame": r["isMainFrame"], "description": text})

    # ---- request interception (every request goes through here)

    async def on_request(self, st, sid, main_session, p):
        cdp = st.b.cdp
        rid = p["requestId"]
        req = p["request"]
        url = req["url"] + req.get("urlFragment", "")
        nid = p.get("networkId")
        # Fetch reports fetch() calls as XHR; Network (and Playwright) say fetch.
        rtype = ((st.reqs.get(nid) or {}).get("type") or p.get("resourceType") or "other").lower()
        main = main_session and rtype == "document" and p.get("frameId") == st.target

        def mark(via):
            if nid:
                st.via[(nid, url)] = via

        try:
            if url.startswith(st.bridge_url):
                try:
                    body = json.loads(self.post_data(req) and base64.b64decode(self.post_data(req)) or b"")
                    if isinstance(body, dict) and body.get("k") == "m":
                        if body.get("name") in st.listeners:
                            st.frame_sid[p.get("frameId")] = sid
                            self.send({"ev": "message", "wid": st.wid, "name": body["name"], "data": body.get("data", ""),
                                       "arrayBuffer": bool(body.get("arrayBuffer")), "origin": body.get("origin"),
                                       "isMainFrame": bool(body.get("top")), "frameId": p.get("frameId")})
                    else:
                        name, meth, args = body
                        self.send({"ev": "bridge", "wid": st.wid, "name": name, "method": meth, "args": args})
                except (ValueError, TypeError):
                    pass
                return await cdp.send("Fetch.fulfillRequest", fulfil_params(rid, {
                    "status": 204, "headers": {"Access-Control-Allow-Origin": "*"}}), sid)
            if p.get("redirectedRequestId"):
                # A redirect hop goes on to the network without asking the JVM, as with Playwright.
                mark("live")
                extra = origin_headers(st, url)
                if extra:
                    merged = {k.lower(): v for k, v in req.get("headers", {}).items()}
                    merged.update({k.lower(): v for k, v in extra.items()})
                    return await cdp.send("Fetch.continueRequest", {"requestId": rid, "headers": header_list(merged)}, sid)
                return await cdp.send("Fetch.continueRequest", {"requestId": rid}, sid)
            if self.favicon(rtype, url):
                # Headed Chrome fetches favicons from the browser process; a WebView doesn't.
                return await cdp.send("Fetch.failRequest", {"requestId": rid, "errorReason": "Aborted"}, sid)
            # loadDataWithBaseURL: the document is the given HTML, with baseUrl as its origin.
            if main and st.pending_data and same_url(url, st.pending_data["baseUrl"]):
                d, st.pending_data = st.pending_data, None
                mark("data")
                return await cdp.send("Fetch.fulfillRequest", fulfil_params(rid, {
                    "status": 200, "contentType": f"{d['mimeType']}; charset=utf-8",
                    "body": base64.b64encode(d["html"].encode()).decode()}), sid)
            if st.block_images and rtype == "image":
                mark("blocked")
                return await cdp.send("Fetch.failRequest", {"requestId": rid, "errorReason": "BlockedByClient"}, sid)
            headers = req.get("headers", {})
            post = self.post_data(req)
            if nid:
                st.via[(nid, url, "req")] = {"headers": headers, "postData": post}
            if st.include_cookies:
                # WebSettingsCompat.setIncludeCookiesOnIntercept: the request's cookies in its intercept headers.
                try:
                    cs = (await cdp.send("Network.getCookies", {"urls": [url]}, sid))["cookies"]
                    if cs:
                        headers = {**headers, "Cookie": "; ".join(f"{c['name']}={c['value']}" for c in cs)}
                except CDPError:
                    pass
            info = {"url": url, "method": req.get("method", "GET"), "headers": headers, "isMainFrame": main,
                    "isRedirect": bool(p.get("redirectedRequestId")), "type": rtype}
            intercepted = await self.ask({"ev": "intercept", "wid": st.wid, "req": info})
            if intercepted:
                mark("intercept")
                return await cdp.send("Fetch.fulfillRequest", fulfil_params(rid, intercepted), sid)
            if st.serve:
                served = await self.ask({"ev": "serve", "wid": st.wid, "req": {**info, "postData": post}})
                if served:
                    mark(served.get("via") or "fixture")
                    return await cdp.send("Fetch.fulfillRequest", fulfil_params(rid, served), sid)
                if not st.live:
                    mark("miss")
                    return await cdp.send("Fetch.failRequest", {"requestId": rid, "errorReason": "InternetDisconnected"}, sid)
            mark("live")
            extra = {}
            if main and st.nav_headers and same_url(url, st.nav_headers["url"]):
                extra, st.nav_headers = st.nav_headers["headers"], None
            # The profile's origin-matched headers go on every request to a matching origin, over loadUrl's.
            extra = {**extra, **origin_headers(st, url)}
            if extra:
                merged = {k.lower(): v for k, v in req.get("headers", {}).items()}
                for k, v in extra.items():
                    merged[k.lower()] = v
                return await cdp.send("Fetch.continueRequest", {"requestId": rid, "headers": header_list(merged)}, sid)
            return await cdp.send("Fetch.continueRequest", {"requestId": rid}, sid)
        except Exception as e:
            if not st.closed and "closed" not in str(e) and "Invalid InterceptionId" not in str(e):
                self.send({"ev": "log", "wid": st.wid, "text": f"route {url}: {e}"})
            try:
                await cdp.send("Fetch.failRequest", {"requestId": rid, "errorReason": "Failed"}, sid)
            except Exception:
                pass

    @staticmethod
    def post_data(req):
        """The request body, base64, or None."""
        if req.get("postDataEntries"):
            return base64.b64encode(b"".join(base64.b64decode(e.get("bytes", "")) for e in req["postDataEntries"])).decode()
        if req.get("postData") is not None:
            return base64.b64encode(req["postData"].encode()).decode()
        return None

    @staticmethod
    def favicon(rtype, url):
        return (rtype or "").lower() == "other" and urlsplit(url).path == "/favicon.ico"

    # ---- ops

    async def handle(self, m):
        op = m.get("op")
        if op == "reply":
            fut = self.pending.pop(m.get("rid"), None)
            if fut and not fut.done():
                fut.set_result(m.get("value"))
            return None
        if op == "open":
            st = View(m)
            self.views[st.wid] = st
            st.ready = asyncio.ensure_future(self.open(st, m))

            def opened(t):
                if t.cancelled():
                    return
                if t.exception():
                    self.send({"ev": "fatal", "wid": st.wid, "message": str(t.exception())})
            st.ready.add_done_callback(opened)
            return None
        if op == "defaultUserAgent":
            return (await get_browser()).user_agent
        if op == "features":
            return FEATURES
        if op == "uaMetadataDefault":
            return await ua_metadata_default()

        st = self.views.get(m.get("wid"))
        if st is None:
            raise RuntimeError(f"no webview {m.get('wid')}")
        await st.ready
        cdp, sid = st.b.cdp, st.session
        if op == "set":
            if "blockImages" in m:
                st.block_images = bool(m["blockImages"])
            if "includeCookies" in m:
                st.include_cookies = bool(m["includeCookies"])
            if "originHeaders" in m:
                st.origin_headers = m["originHeaders"] or []
            if "uaMetadata" in m:
                st.ua_metadata = m["uaMetadata"]
                for s in list(st.sessions):
                    cdp.fire("Network.setUserAgentOverride", self.ua_override(st), s)
            return None
        if op == "addStartScript":
            self.add_start_script(st, m["script"])
            return None
        if op == "removeStartScript":
            self.remove_script(st, ("start", m["id"]))
            return None
        if op == "addMessageListener":
            self.add_listener(st, m["listener"])
            return None
        if op == "removeMessageListener":
            st.listeners.pop(m["name"], None)
            self.remove_script(st, ("listener", m["name"]))
            return None
        if op == "postReply":
            await self.post_reply(st, m)
            return None
        if op == "load":
            if m["url"] == "about:blank":
                st.blank_requested = True
            if m.get("headers"):
                st.nav_headers = {"url": m["url"], "headers": m["headers"]}
            cdp.fire("Page.navigate", {"url": m["url"]}, sid)
            return None
        if op == "loadData":
            base = m.get("baseUrl") or ""
            if base.lower().startswith(("http:", "https:")):
                st.pending_data = {"baseUrl": base, "html": m["html"], "mimeType": m.get("mimeType") or "text/html"}
                cdp.fire("Page.navigate", {"url": base}, sid)
            else:
                st.blank_requested = True
                asyncio.ensure_future(self.set_content(st, m["html"]))
            return None
        if op == "eval":
            return await self.evaluate(st, m["script"])
        if op == "addBridges":
            self.add_bridges(st, m["bridges"])
            return None
        if op == "stop":
            await self.settle(st)
            try:
                await cdp.send("Page.stopLoading", None, sid)
            except CDPError:
                pass
            return None
        if op == "cookies":
            return [proto_cookie(c) for c in (await cdp.send("Storage.getCookies", {"browserContextId": st.ctx}))["cookies"]]
        if op == "setCookies":
            now = time.time()
            for c in m["cookies"]:
                if (c.get("expires") or -1) > 0 and c["expires"] < now:
                    await cdp.send("Network.deleteCookies", {"name": c["name"], "domain": c.get("domain"), "path": c.get("path") or "/"}, sid)
                else:
                    await cdp.send("Storage.setCookies", {"cookies": [cdp_cookie(c)], "browserContextId": st.ctx})
            return None
        if op == "close":
            return await self.close_view(st)
        if op == "target":
            return st.target
        raise RuntimeError(f"unknown op {op}")

    async def set_content(self, st, html):
        """loadData without an http(s) base: the HTML replaces about:blank's document, as Playwright's setContent does."""
        cdp, sid = st.b.cdp, st.session
        try:
            frame = (await cdp.send("Page.getFrameTree", None, sid))["frameTree"]["frame"]["id"]
            await cdp.send("Page.setDocumentContent", {"frameId": frame, "html": html}, sid)
        except CDPError as e:
            self.send({"ev": "log", "wid": st.wid, "text": f"loadData: {e}"})

    async def evaluate(self, st, script):
        """evaluateJavascript: the completion value, JSON-encoded; "null" for undefined and errors."""
        cdp, sid = st.b.cdp, st.session
        try:
            r = await cdp.send("Runtime.evaluate", {"expression": script, "objectGroup": "extrun-eval"}, sid)
            if r.get("exceptionDetails"):
                d = r["exceptionDetails"]
                text = (d.get("exception") or {}).get("description") or d.get("text") or "error"
                self.send({"ev": "console", "wid": st.wid, "level": "error", "text": "Uncaught " + text,
                           "source": d.get("url", ""), "line": d.get("lineNumber", 0)})
                return "null"
            v = r["result"]
            if v.get("objectId"):
                s = await cdp.send("Runtime.callFunctionOn", {"objectId": v["objectId"], "functionDeclaration": STRINGIFY,
                                                              "returnByValue": True}, sid)
                cdp.fire("Runtime.releaseObjectGroup", {"objectGroup": "extrun-eval"}, sid)
                return (s.get("result") or {}).get("value") or "null"
            t = v.get("type")
            if t == "undefined" or v.get("subtype") == "null":
                return "null"
            if "unserializableValue" in v:
                return "0" if v["unserializableValue"] == "-0" else "null"
            if t == "string":
                return json.dumps(v["value"], ensure_ascii=False)
            if t == "boolean":
                return "true" if v["value"] else "false"
            if t == "number":  # let the page format it, so it reads exactly like JSON.stringify
                s = await cdp.send("Runtime.evaluate", {"expression": f"JSON.stringify({json.dumps(v['value'])})",
                                                        "returnByValue": True}, sid)
                return (s.get("result") or {}).get("value") or "null"
            return "null"
        except CDPError as e:
            self.send({"ev": "console", "wid": st.wid, "level": "error", "text": f"evaluateJavascript: {e}", "source": "", "line": 0})
            return "null"

    async def settle(self, st, timeout=1.5):
        """Wait (briefly) for requests whose response has arrived to finish loading. A source typically
        stops and closes the WebView as soon as the page has handed it a result, which can be before
        Chrome reports the request that carried it as finished; stopping then would cancel it and
        lose its `net` line and fixture."""
        end = time.monotonic() + timeout
        while any(r["status"] for r in st.reqs.values()) and time.monotonic() < end:
            await asyncio.sleep(0.02)

    async def close_view(self, st):
        self.views.pop(st.wid, None)
        if st.tick_task:
            st.tick_task.cancel()
        await self.settle(st)
        st.closed = True
        cookies = []
        if st.b and st.ctx:
            cdp = st.b.cdp
            # Requests that have their response but not their loadingFinished yet (a page often hands
            # an API result to a JS interface, and the source closes the view, before that event):
            # report and record them now, while the bodies can still be read.
            for rid, r in list(st.reqs.items()):
                if r["status"]:
                    self.finished(st, r.get("sid"), r, rid)
            st.reqs.clear()
            if st.recording:
                await asyncio.wait(list(st.recording), timeout=10)
            try:
                cookies = [proto_cookie(c) for c in (await cdp.send("Storage.getCookies", {"browserContextId": st.ctx}))["cookies"]]
            except Exception:
                pass
            for sid in list(st.sessions):
                cdp.handlers.pop(sid, None)
                st.b.on_session_gone.pop(sid, None)
            try:
                await cdp.send("Target.disposeBrowserContext", {"browserContextId": st.ctx})
            except Exception:
                pass
            context_closed()
        return cookies

    # ---- the socket

    async def serve(self):
        global connections
        connections += 1
        arm_exit()
        try:
            while True:
                line = await self.reader.readline()
                if not line:
                    break
                if not line.strip():
                    continue
                try:
                    m = json.loads(line)
                except ValueError:
                    continue
                if m.get("op") == "reply":  # inline, so replies never queue behind slow ops
                    await self.handle(m)
                    continue
                asyncio.ensure_future(self.run_op(m))
        except (ConnectionError, asyncio.IncompleteReadError):
            pass
        finally:
            connections -= 1
            for fut in self.pending.values():
                if not fut.done():
                    fut.set_result(None)
            for st in list(self.views.values()):
                try:
                    await st.ready
                except Exception:
                    pass
                await self.close_view(st)
            self.views.clear()
            try:
                self.writer.close()
            except Exception:
                pass
            arm_exit()

    async def run_op(self, m):
        try:
            value = await self.handle(m)
            if m.get("id") is not None:
                self.send({"re": m["id"], "ok": True, "value": value})
        except Exception as e:
            if m.get("id") is not None:
                self.send({"re": m["id"], "ok": False, "error": str(e) or type(e).__name__})


async def main():
    try:
        os.unlink(SOCK)
    except FileNotFoundError:
        pass
    server = await asyncio.start_unix_server(lambda r, w: Client(r, w).serve(), path=SOCK, limit=256 * 1024 * 1024)
    os.chmod(SOCK, 0o660)
    log(f"ext-run webview bridge (nodriver/chrome) listening on {SOCK}")
    arm_exit()
    loop = asyncio.get_running_loop()
    stop = loop.create_future()
    for sig in (signal.SIGTERM, signal.SIGINT):
        loop.add_signal_handler(sig, lambda: stop.done() or stop.set_result(None))
    await stop
    if browser_task and browser_task.done() and not browser_task.cancelled() and browser_task.exception() is None:
        browser_task.result().kill()
    server.close()


if __name__ == "__main__":
    asyncio.run(main())
