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

# Chrome's own sandbox needs a setuid helper or the ability to create user namespaces, which a
# hardened container usually blocks. EXT_RUN_WV_CHROME_SANDBOX=1 keeps it on; the launch scripts
# default to 0 (--no-sandbox), and the recheck node's controller sets 1 only when chrome_sandbox
# is enabled in its config and the container got the matching seccomp profile.
_SANDBOX_ON = os.environ.get("EXT_RUN_WV_CHROME_SANDBOX", "1") == "1"
CHROME_SANDBOX_ARGS = [] if _SANDBOX_ON else ["--no-sandbox"]

# IsolateOrigins/site-per-process disabling weakens one of Chrome's own security
# boundaries (process-per-site isolation) — fine to trade off on the GitHub-hosted runner, which
# is already running Chrome with --no-sandbox and has no untrusted-host concerns beyond what the
# container/VM itself provides, but there's no reason to also give it up on the recheck node,
# where Chrome's sandbox is the whole point. Only disabled when the sandbox itself is off.
_DISABLE_FEATURES = ["HttpsUpgrades"] if _SANDBOX_ON else ["HttpsUpgrades", "IsolateOrigins", "site-per-process"]

# Outbound HTTP(S) proxy for the browser, e.g. the recheck node's egress proxy
# (http://proxy:3128) — the sandbox has no direct route to the internet at all, only through
# this proxy. Unset in CI, where the GitHub-hosted runner's own network is used directly.
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
    f"--disable-features={','.join(_DISABLE_FEATURES)}", "--lang=en-US",
    *CHROME_SANDBOX_ARGS, *PROXY_ARGS,
    f"--window-size={WINDOW[0]},{WINDOW[1]}", *GPU_ARGS,
    "--disable-backgrounding-occluded-windows", "--disable-renderer-backgrounding", "--disable-background-timer-throttling",
]


def log(*a):
    print(*a, file=sys.stderr, flush=True)


def debug(*a):
    if VERBOSE:
        log(*a)


# Cloudflare Turnstile: the hidden response input Cloudflare's JS inserts into the top-level
# document; its parent element is the widget box (the checkbox sits ~28px in from its left edge,
# vertically centred). Same box whether the widget renders inline or via its challenges.cloudflare.com
# iframe: the input (and so this rect) always lives in the top-level document, so this is evaluated
# on the page session, never on a child/iframe session.
TURNSTILE_BOX_JS = """(() => {
  const i = document.querySelector('input[name=cf-turnstile-response]');
  const r = i && i.parentElement.getBoundingClientRect();
  return r && r.height > 20 ? [r.x, r.y, r.height] : null;
})()"""

CF_DETECT_S = 8       # how long a Turnstile widget gets to render before this page is given up on
CF_FIRST_CLICK_S = 5  # let Cloudflare's automatic/managed check clear on its own this long first
CF_BUDGET_S = 30       # total time (from first detection) to wait for clearance
CF_RECLICK_S = 8       # minimum gap between clicks
CF_MAX_CLICKS = 2

# Diagnostics only (EXT_RUN_WV_CF_DEBUG=1): a screenshot + the widget box/click point at four points
# in handle_turnstile, to see by eye whether a click landed and what state it leaves the widget in.
# No effect at all when unset -- this never changes detection or click behaviour.
CF_DEBUG = os.environ.get("EXT_RUN_WV_CF_DEBUG") == "1"
CF_DEBUG_DIR = os.environ.get("EXT_RUN_WV_CF_DEBUG_DIR") or os.path.join(tempfile.gettempdir(), "cf-debug")


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
        self.scripts = []      # init scripts, applied to every page/iframe session
        self.bridge_url = BRIDGE_URL + secrets.token_hex(8)
        self.url = "about:blank"
        self.via = {}          # (networkId, url) -> via
        self.reqs = {}         # requestId -> request info from Network
        self.closed = False
        self.recording = set()  # record tasks still reading bodies; close waits for them
        self.ready = None
        self.b = None
        self.cf_task = None  # the Cloudflare Turnstile watcher for the current page, if any


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
        if st.user_agent:
            cmds.append(send("Network.setUserAgentOverride", {"userAgent": st.user_agent}))
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
            for src in st.scripts:
                cmds.append(send("Page.addScriptToEvaluateOnNewDocument", {"source": src}))
        res = await asyncio.gather(*cmds, return_exceptions=True)
        errs = [r for r in res if isinstance(r, Exception)]
        if errs and kind == "page":
            raise errs[0]
        if kind != "page":
            b.cdp.fire("Runtime.runIfWaitingForDebugger", None, sid)

    def add_bridges(self, st, bridges, apply=True):
        src = bridge_script(st.bridge_url, bridges)
        st.scripts.append(src)
        if apply:
            for sid in list(st.sessions):
                st.b.cdp.fire("Page.addScriptToEvaluateOnNewDocument", {"source": src}, sid)

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
        self.watch_turnstile(st)

    # ---- Cloudflare Turnstile: click the "Verify you are human" checkbox like the app's user does.
    #
    # From GitHub Actions runner IPs, Cloudflare shows this interactive checkbox instead of clearing
    # automatically; from a home IP it usually clears on its own. Only ever acts on a Turnstile widget
    # it finds on the page (see TURNSTILE_BOX_JS) -- nothing here can fire against an unrelated page.

    def watch_turnstile(self, st):
        if st.cf_task and not st.cf_task.done():
            st.cf_task.cancel()
        st.cf_task = asyncio.ensure_future(self.handle_turnstile(st))

    async def turnstile_box(self, st):
        """The widget's (x, y, height) in viewport coordinates, or None. Evaluated on the top-level
        page session only: the hidden response input Cloudflare injects always lives in the
        top-level document, whether the widget itself renders inline or via its own iframe."""
        try:
            r = await st.b.cdp.send("Runtime.evaluate", {"expression": TURNSTILE_BOX_JS, "returnByValue": True}, st.session)
        except CDPError:
            return None
        v = (r.get("result") or {}).get("value")
        return tuple(v) if v else None

    async def cf_cleared(self, st):
        if st.closed or not st.ctx:
            return True
        try:
            cookies = (await st.b.cdp.send("Storage.getCookies", {"browserContextId": st.ctx}))["cookies"]
        except Exception:
            return False
        return any(c.get("name") == "cf_clearance" for c in cookies)

    async def cf_debug_shot(self, st, stage, box=None, point=None):
        """Diagnostics only: a screenshot of the top-level page plus the widget box/click point and
        cf_clearance state, saved under CF_DEBUG_DIR. No-op unless EXT_RUN_WV_CF_DEBUG=1."""
        if not CF_DEBUG or st.closed or not st.b:
            return
        cleared = await self.cf_cleared(st)
        try:
            os.makedirs(CF_DEBUG_DIR, exist_ok=True)
            r = await st.b.cdp.send("Page.captureScreenshot", {"format": "png"}, st.session)
            path = os.path.join(CF_DEBUG_DIR, f"wid{st.wid}-{stage}.png")
            with open(path, "wb") as f:
                f.write(base64.b64decode(r["data"]))
            log(f"cf-debug: wid={st.wid} stage={stage} box={box} click_point={point} cf_clearance={cleared} -> {path}")
        except Exception as e:
            log(f"cf-debug: wid={st.wid} stage={stage} box={box} click_point={point} cf_clearance={cleared} (screenshot failed: {e})")

    async def click_turnstile(self, st, box):
        """A move, a short pause, then press/release -- a realistic sequence, not a single synthetic
        click -- at the checkbox inside the widget box, on the top-level page session."""
        x, y, h = box
        px, py = x + 28, y + h / 2
        cdp, sid = st.b.cdp, st.session
        try:
            await cdp.send("Input.dispatchMouseEvent", {"type": "mouseMoved", "x": px, "y": py, "button": "none"}, sid)
            await asyncio.sleep(0.15)
            await cdp.send("Input.dispatchMouseEvent", {"type": "mousePressed", "x": px, "y": py, "button": "left", "clickCount": 1}, sid)
            await asyncio.sleep(0.08)
            await cdp.send("Input.dispatchMouseEvent", {"type": "mouseReleased", "x": px, "y": py, "button": "left", "clickCount": 1}, sid)
            return True
        except CDPError as e:
            debug(f"turnstile click failed: {e}")
            return False

    async def handle_turnstile(self, st):
        task = asyncio.current_task()

        def alive():
            return not st.closed and st.cf_task is task

        start = time.monotonic()
        box = None
        # Most pages never show a Turnstile widget; give one a little time to render (it can appear
        # after the load event) and move on if it never does.
        while alive() and time.monotonic() - start < CF_DETECT_S:
            if await self.cf_cleared(st):
                return
            box = await self.turnstile_box(st)
            if box:
                break
            await asyncio.sleep(1)
        if not box:
            return
        await self.cf_debug_shot(st, "detected", box=box)

        clicks = 0
        next_click = start + CF_FIRST_CLICK_S  # let the automatic/managed check clear on its own first
        while alive() and time.monotonic() - start < CF_BUDGET_S:
            if await self.cf_cleared(st):
                return
            if clicks < CF_MAX_CLICKS and time.monotonic() >= next_click:
                box = await self.turnstile_box(st) or box  # the widget can re-render between clicks
                point = (box[0] + 28, box[1] + box[2] / 2)
                await self.cf_debug_shot(st, f"before-click{clicks + 1}", box=box, point=point)
                if await self.click_turnstile(st, box):
                    clicks += 1
                    self.send({"ev": "note", "wid": st.wid, "text": "clicked Cloudflare's checkbox"})
                    await asyncio.sleep(2)
                    await self.cf_debug_shot(st, f"after-click{clicks}", box=box, point=point)
                next_click = time.monotonic() + CF_RECLICK_S
            await asyncio.sleep(1)
        if not await self.cf_cleared(st):
            await self.cf_debug_shot(st, "giveup", box=box)

    # ---- network bookkeeping (the `net`, `error` and `record` events)

    def via_of(self, st, network_id, url):
        return st.via.get((network_id, url), "live")

    def on_request_will_be_sent(self, st, sid, p):
        req = p["request"]
        url = req["url"] + req.get("urlFragment", "")
        if url.startswith(("data:", "blob:", BRIDGE_URL)) or self.favicon(p.get("type"), url):
            return
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
                    name, meth, args = json.loads(self.post_data(req) and base64.b64decode(self.post_data(req)) or b"")
                    self.send({"ev": "bridge", "wid": st.wid, "name": name, "method": meth, "args": args})
                except ValueError:
                    pass
                return await cdp.send("Fetch.fulfillRequest", fulfil_params(rid, {
                    "status": 204, "headers": {"Access-Control-Allow-Origin": "*"}}), sid)
            if p.get("redirectedRequestId"):
                # A redirect hop goes on to the network without asking the JVM, as with Playwright.
                mark("live")
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
            if main and st.nav_headers and same_url(url, st.nav_headers["url"]):
                extra, st.nav_headers = st.nav_headers["headers"], None
                merged = {k.lower(): v for k, v in headers.items()}
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

        st = self.views.get(m.get("wid"))
        if st is None:
            raise RuntimeError(f"no webview {m.get('wid')}")
        await st.ready
        cdp, sid = st.b.cdp, st.session
        if op == "set":
            if "blockImages" in m:
                st.block_images = bool(m["blockImages"])
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
        if st.cf_task and not st.cf_task.done():
            st.cf_task.cancel()
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
