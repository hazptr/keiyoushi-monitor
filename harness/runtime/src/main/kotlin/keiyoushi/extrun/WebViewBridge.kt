package keiyoushi.extrun

import android.net.Uri
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.Cookie
import java.io.File
import java.io.IOException
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Connects android.webkit.WebView to the browser sidecar (a webview/<backend>, see webview/PROTOCOL.md) over a
 * unix socket: one JSON object per line each way. The sidecar runs the page; this side calls the WebViewClient,
 * WebChromeClient and JS interfaces with Android's threading (page callbacks on the main thread,
 * request interception on IO threads, JS interface calls on one bridge thread).
 */
object WebViewBridge {
    private val sockPath = System.getenv("EXT_RUN_WEBVIEW_SOCK") ?: "/ext-run-webview/bridge.sock"
    private val views = ConcurrentHashMap<Int, WebView>()
    private val nextView = AtomicInteger(1)
    private val nextOp = AtomicLong(1)
    private val replies = ConcurrentHashMap<Long, CompletableFuture<JsonElement?>>()
    private val io = Executors.newCachedThreadPool { Thread(it, "ext-run-webview-io").apply { isDaemon = true } }
    private val javaBridge = Executors.newSingleThreadExecutor { Thread(it, "JavaBridge").apply { isDaemon = true } }
    private var channel: SocketChannel? = null
    private var userAgent: String? = null
    private val cfClicked = java.util.concurrent.atomic.AtomicBoolean(false)

    fun register(view: WebView): Int = nextView.getAndIncrement().also { views[it] = view }

    /** Whether a backend reported clicking Cloudflare's Turnstile checkbox since the last call; resets it. */
    fun consumeCfClicked(): Boolean = cfClicked.getAndSet(false)

    /** Stand-in for Mihon's WebViewUtil.supportsWebView: is there a sidecar to talk to. */
    fun available(): Boolean = channel != null || File(sockPath).exists()

    fun defaultUserAgent(): String = userAgent ?: request(buildJsonObject {
        put("op", "defaultUserAgent")
        put("browser", Harness.browser)
    }).jsonPrimitive.content.also { userAgent = it }

    fun load(view: WebView, url: String, headers: Map<String, String>) {
        ensureOpen(view)
        send(buildJsonObject {
            put("op", "load")
            put("wid", view.id)
            put("url", url)
            put("headers", JsonObject(headers.mapValues { JsonPrimitive(it.value) }))
        })
    }

    fun loadData(view: WebView, baseUrl: String?, html: String, mimeType: String) {
        ensureOpen(view)
        send(buildJsonObject {
            put("op", "loadData")
            put("wid", view.id)
            put("baseUrl", baseUrl)
            put("html", html)
            put("mimeType", mimeType)
        })
    }

    fun evaluate(view: WebView, script: String, callback: ValueCallback<String>?) {
        ensureOpen(view)
        val reply = requestAsync(buildJsonObject {
            put("op", "eval")
            put("wid", view.id)
            put("script", script)
        })
        if (callback != null) {
            reply.whenComplete { value, _ ->
                val s = (value as? JsonPrimitive)?.contentOrNull ?: "null"
                if (!view.destroyed) MainThread.post { callback.onReceiveValue(s) }
            }
        }
    }

    fun stop(view: WebView) = send(buildJsonObject {
        put("op", "stop")
        put("wid", view.id)
    })

    fun setBlockImages(view: WebView, block: Boolean) = send(buildJsonObject {
        put("op", "set")
        put("wid", view.id)
        put("blockImages", block)
    })

    fun addInterfaces(view: WebView, interfaces: Map<String, Any>) = send(buildJsonObject {
        put("op", "addBridges")
        put("wid", view.id)
        put("bridges", bridgeDefs(interfaces))
    })

    /** Closes the page and copies its cookies into the jar. */
    fun destroy(view: WebView) {
        views.remove(view.id)
        if (!view.opened || channel == null) return
        val cookies = try {
            requestAsync(buildJsonObject {
                put("op", "close")
                put("wid", view.id)
            }).get(5, TimeUnit.SECONDS)
        } catch (_: Exception) {
            null
        }
        (cookies as? JsonArray)?.let(::saveCookies)
    }

    /** CookieManager reads see a live page's cookies at once, as on Android, not only after its next load. */
    fun pullCookies() {
        openViews().forEach { view ->
            val cookies = try {
                requestAsync(buildJsonObject {
                    put("op", "cookies")
                    put("wid", view.id)
                }).get(3, TimeUnit.SECONDS)
            } catch (_: Exception) {
                null
            }
            (cookies as? JsonArray)?.let(::saveCookies)
        }
    }

    /** CookieManager writes reach live pages at once. */
    fun pushCookie(cookie: Cookie) {
        val views = openViews()
        if (views.isEmpty()) return
        val arr = buildJsonArray { add(cookieJson(cookie)) }
        views.forEach { view ->
            try {
                requestAsync(buildJsonObject {
                    put("op", "setCookies")
                    put("wid", view.id)
                    put("cookies", arr)
                }).get(3, TimeUnit.SECONDS)
            } catch (_: Exception) {
            }
        }
    }

    /** One view's cookies, copied into the jar; usable from any thread (no main-thread callback). */
    fun cookiesOf(view: WebView, timeoutMs: Long = 3000) {
        if (!view.opened || view.destroyed) return
        val cookies = try {
            requestAsync(buildJsonObject {
                put("op", "cookies")
                put("wid", view.id)
            }).get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            null
        }
        (cookies as? JsonArray)?.let(::saveCookies)
    }

    /** evaluateJavascript, answered on the calling thread (the JSON-encoded value, or null on timeout/error). */
    fun evalSync(view: WebView, script: String, timeoutMs: Long = 3000): String? {
        if (!view.opened || view.destroyed) return null
        return try {
            (requestAsync(buildJsonObject {
                put("op", "eval")
                put("wid", view.id)
                put("script", script)
            }).get(timeoutMs, TimeUnit.MILLISECONDS) as? JsonPrimitive)?.contentOrNull
        } catch (_: Exception) {
            null
        }
    }

    private fun openViews() = if (channel == null) emptyList() else views.values.filter { it.opened && !it.destroyed }

    private fun cookieJson(c: Cookie) = buildJsonObject {
        put("name", c.name)
        put("value", c.value)
        put("domain", if (c.hostOnly) c.domain else ".${c.domain}")
        put("path", c.path)
        put("expires", if (c.persistent) c.expiresAt / 1000.0 else -1.0)
        put("httpOnly", c.httpOnly)
        put("secure", c.secure)
    }

    fun shutdown() {
        runCatching { channel?.close() }
    }

    private fun ensureOpen(view: WebView) {
        if (view.opened) return
        view.opened = true
        val s = view.getSettings()
        send(buildJsonObject {
            put("op", "open")
            put("wid", view.id)
            put("browser", Harness.browser)
            // Sources usually copy their OkHttp User-Agent (the app's default: Android Chrome) into the WebView. On a
            // phone that matches the engine; here the engine is desktop Chrome, and desktop Chrome claiming Android
            // fails Cloudflare's managed check (measured 2026-10-10, README "Parity with the app"). So the app's default
            // UA becomes the browser's own; any other UA a source sets is used as given.
            put("userAgent", s.userAgentOverride()?.takeUnless { it == Harness.userAgent })
            put("javaScriptEnabled", s.javaScriptEnabled)
            put("blockImages", s.blockNetworkImage || !s.loadsImagesAutomatically)
            put("bridges", bridgeDefs(view.jsInterfaces))
            // Fixtures or --map: every request asks the JVM first; live fills the gaps (and records).
            put("serve", !view.harnessOwned && (Harness.fixturesDir != null || Harness.urlMap.isNotEmpty()))
            put("live", view.harnessOwned || Harness.live || Harness.direct)
            put("record", !view.harnessOwned && Harness.fixturesDir != null && Harness.live)
            putJsonArray("cookies") {
                val now = System.currentTimeMillis()
                HarnessCookieJar.all().filter { it.expiresAt > now }.forEach { add(cookieJson(it)) }
            }
        })
    }

    private fun bridgeDefs(interfaces: Map<String, Any>) = buildJsonArray {
        interfaces.forEach { (name, obj) ->
            add(buildJsonObject {
                put("name", name)
                putJsonArray("methods") {
                    obj.javaClass.methods.filter { it.isAnnotationPresent(JavascriptInterface::class.java) }
                        .map { it.name }.distinct().forEach { add(JsonPrimitive(it)) }
                }
            })
        }
    }

    // ---- socket ----

    @Synchronized
    private fun connection(): SocketChannel {
        channel?.let { return it }
        if (!File(sockPath).exists()) throw IOException(NOT_RUNNING)
        val ch = try {
            SocketChannel.open(UnixDomainSocketAddress.of(sockPath))
        } catch (e: IOException) {
            throw IOException("$NOT_RUNNING (${e.message})", e)
        }
        channel = ch
        Thread({ readLoop(ch) }, "ext-run-webview-reader").apply { isDaemon = true }.start()
        return ch
    }

    @Synchronized
    private fun send(msg: JsonObject) {
        val ch = connection()
        val buf = ByteBuffer.wrap((msg.toString() + "\n").toByteArray())
        while (buf.hasRemaining()) ch.write(buf)
    }

    private fun requestAsync(msg: JsonObject): CompletableFuture<JsonElement?> {
        val id = nextOp.getAndIncrement()
        val future = CompletableFuture<JsonElement?>()
        replies[id] = future
        send(JsonObject(msg + ("id" to JsonPrimitive(id))))
        return future
    }

    private fun request(msg: JsonObject): JsonElement = requestAsync(msg).get(60, TimeUnit.SECONDS) ?: JsonNull

    private fun readLoop(ch: SocketChannel) {
        try {
            Channels.newReader(ch, Charsets.UTF_8).buffered().forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                val m = try {
                    Json.parseToJsonElement(line).jsonObject
                } catch (_: Exception) {
                    return@forEachLine
                }
                val re = m["re"]?.jsonPrimitive?.long
                if (re != null) {
                    val f = replies.remove(re) ?: return@forEachLine
                    if (m["ok"]?.jsonPrimitive?.boolean == true) {
                        f.complete(m["value"])
                    } else {
                        f.completeExceptionally(IOException("webview sidecar: " + m["error"]?.jsonPrimitive?.contentOrNull))
                    }
                } else {
                    try {
                        onEvent(m)
                    } catch (t: Throwable) {
                        System.err.println("ext-run: webview event failed: $t")
                    }
                }
            }
        } catch (_: IOException) {
        }
        channel = null
        replies.values.forEach { it.completeExceptionally(IOException("webview sidecar connection closed")) }
        replies.clear()
        views.values.filter { it.opened && !it.destroyed }.forEach { renderGone(it, "webview sidecar connection closed") }
    }

    private fun reply(rid: Long, value: JsonElement?) = send(buildJsonObject {
        put("op", "reply")
        put("rid", rid)
        put("value", value ?: JsonNull)
    })

    // ---- events from the page ----

    private fun onEvent(m: JsonObject) {
        val ev = m["ev"]?.jsonPrimitive?.content
        val view = m["wid"]?.jsonPrimitive?.intOrNull?.let(views::get)
        val rid = m["rid"]?.jsonPrimitive?.long
        when (ev) {
            "intercept" -> io.execute {
                val response = if (view == null || view.destroyed) {
                    null
                } else {
                    try {
                        view.webViewClient.shouldInterceptRequest(view, Req(m["req"]!!.jsonObject))
                    } catch (t: Throwable) {
                        System.err.println("ext-run: shouldInterceptRequest threw (the app would crash):")
                        t.printStackTrace()
                        null
                    }
                }
                reply(rid!!, response?.let(::toJson))
            }
            "serve" -> io.execute {
                val r = m["req"]!!.jsonObject
                val served = Transport.serveWebView(
                    r.str("url")!!,
                    r.str("method")!!,
                    r.headers(),
                    r.str("postData")?.let(Base64.getDecoder()::decode),
                )
                reply(rid!!, served)
            }
            "record" -> io.execute {
                Transport.recordWebView(
                    url = m.str("url")!!,
                    method = m.str("method")!!,
                    headers = m.headers(),
                    postData = m.str("postData")?.let(Base64.getDecoder()::decode),
                    code = m["status"]!!.jsonPrimitive.int,
                    finalUrl = m.str("finalUrl"),
                    contentType = m.str("contentType"),
                    body = Base64.getDecoder().decode(m.str("body")!!),
                )
            }
            "net" -> Transport.logWebView(
                m.str("method")!!,
                m.str("url")!!,
                m["status"]?.jsonPrimitive?.intOrNull ?: 0,
                m.str("via").orEmpty(),
                m.str("type").orEmpty(),
                m.str("error"),
            )
            "cookies" -> (m["cookies"] as? JsonArray)?.let(::saveCookies)
            "bridge" -> javaBridge.execute { callInterface(view ?: return@execute, m.str("name")!!, m.str("method")!!, m["args"]!!.jsonArray) }
            "log" -> System.err.println("ext-run webview: ${m.str("text")}")
            "note" -> {
                val text = m.str("text")
                System.err.println("ext-run webview: $text")
                if (text == "clicked Cloudflare's checkbox") cfClicked.set(true)
            }
            "fatal" -> {
                System.err.println("ext-run: the webview sidecar couldn't open a page: ${m.str("message")}")
                view?.let { renderGone(it, m.str("message")) }
            }
            "crash" -> view?.let { renderGone(it, "page crashed") }
        }
        if (view == null || view.destroyed) return
        when (ev) {
            "pageStarted" -> MainThread.post {
                view.currentUrl = m.str("url")
                if (!view.destroyed) view.webViewClient.onPageStarted(view, m.str("url"), null)
            }
            "pageFinished" -> MainThread.post {
                if (view.destroyed) return@post
                view.webViewClient.onPageFinished(view, m.str("url"))
                view.webChromeClient?.onProgressChanged(view, 100)
            }
            "httpError" -> MainThread.post {
                if (view.destroyed) return@post
                val req = Req(
                    buildJsonObject {
                        put("url", m.str("url"))
                        put("method", m.str("method"))
                        put("isMainFrame", m["isMainFrame"]?.jsonPrimitive?.booleanOrNull ?: false)
                    },
                )
                val ctype = m.str("contentType")
                val response = WebResourceResponse(
                    ctype?.substringBefore(';')?.trim(),
                    ctype?.substringAfter("charset=", "")?.trim()?.ifEmpty { null },
                    m["status"]?.jsonPrimitive?.intOrNull ?: 0,
                    "",
                    m.headers(),
                    null,
                )
                view.webViewClient.onReceivedHttpError(view, req, response)
            }
            "error" -> {
                val description = m.str("description").orEmpty()
                // Android doesn't report requests that were cancelled (navigation replaced, window.stop()).
                if ("ABORTED" in description || "cancel" in description.lowercase()) return
                MainThread.post {
                    if (view.destroyed) return@post
                    val req = Req(
                        buildJsonObject {
                            put("url", m.str("url"))
                            put("method", m.str("method"))
                            put("isMainFrame", m["isMainFrame"]?.jsonPrimitive?.booleanOrNull ?: false)
                        },
                    )
                    view.webViewClient.onReceivedError(view, req, Err(description))
                }
            }
            "console" -> MainThread.post {
                val level = when (m.str("level")) {
                    "error" -> ConsoleMessage.MessageLevel.ERROR
                    "warning", "warn" -> ConsoleMessage.MessageLevel.WARNING
                    "debug" -> ConsoleMessage.MessageLevel.DEBUG
                    else -> ConsoleMessage.MessageLevel.LOG
                }
                val msg = ConsoleMessage(m.str("text").orEmpty(), m.str("source").orEmpty(), m["line"]?.jsonPrimitive?.intOrNull ?: 0, level)
                val handled = view.webChromeClient?.onConsoleMessage(msg) ?: false
                if (!handled && Harness.verbose) System.err.println("[webview console ${m.str("level")}] ${msg.message()}")
            }
        }
    }

    private fun renderGone(view: WebView, why: String?) = MainThread.post {
        if (view.destroyed) return@post
        val handled = view.webViewClient.onRenderProcessGone(
            view,
            object : RenderProcessGoneDetail() {
                override fun didCrash() = true
                override fun rendererPriorityAtExit() = 0
            },
        )
        if (!handled) System.err.println("ext-run: WebView render process gone ($why) and the client didn't handle it (the app would crash)")
    }

    private fun callInterface(view: WebView, name: String, method: String, args: JsonArray) {
        if (view.destroyed) return
        val obj = view.jsInterfaces[name] ?: return
        val m = obj.javaClass.methods.firstOrNull {
            it.name == method && it.parameterCount == args.size && it.isAnnotationPresent(JavascriptInterface::class.java)
        } ?: run {
            System.err.println("ext-run: JS called $name.$method with ${args.size} argument(s); no matching @JavascriptInterface method")
            return
        }
        m.isAccessible = true // interfaces are often anonymous objects, whose class isn't public
        val converted = m.parameterTypes.mapIndexed { i, type -> convertArg(args[i], type) }.toTypedArray()
        try {
            m.invoke(obj, *converted)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            System.err.println("ext-run: JS interface $name.$method threw:")
            e.targetException.printStackTrace()
        }
    }

    // How Android's Java bridge converts JS values for common parameter types.
    private fun convertArg(v: JsonElement, type: Class<*>): Any? {
        val p = v as? JsonPrimitive
        return when (type) {
            String::class.java -> when {
                v is JsonNull || p == null -> if (v is JsonNull) null else v.toString()
                p.isString -> p.content
                else -> p.content
            }
            Int::class.javaPrimitiveType, Int::class.javaObjectType -> p?.contentOrNull?.toDoubleOrNull()?.toInt() ?: 0
            Long::class.javaPrimitiveType, Long::class.javaObjectType -> p?.contentOrNull?.toDoubleOrNull()?.toLong() ?: 0L
            Double::class.javaPrimitiveType, Double::class.javaObjectType -> p?.contentOrNull?.toDoubleOrNull() ?: 0.0
            Float::class.javaPrimitiveType, Float::class.javaObjectType -> p?.contentOrNull?.toFloatOrNull() ?: 0f
            Boolean::class.javaPrimitiveType, Boolean::class.javaObjectType -> p?.booleanOrNull ?: false
            else -> null
        }
    }

    private fun toJson(r: WebResourceResponse): JsonObject {
        val bytes = r.data?.use { it.readBytes() } ?: ByteArray(0)
        return buildJsonObject {
            put("status", r.statusCode)
            put("contentType", listOfNotNull(r.mimeType ?: "text/plain", r.encoding?.let { "charset=$it" }).joinToString("; "))
            put("headers", JsonObject(r.responseHeaders.orEmpty().mapValues { JsonPrimitive(it.value) }))
            put("body", Base64.getEncoder().encodeToString(bytes))
        }
    }

    private fun saveCookies(arr: JsonArray) {
        arr.forEach { e ->
            val c = e.jsonObject
            val domain = c.str("domain") ?: return@forEach
            val cookie = try {
                Cookie.Builder().name(c.str("name")!!).value(c.str("value").orEmpty()).path(c.str("path") ?: "/").apply {
                    if (domain.startsWith(".")) domain(domain.removePrefix(".")) else hostOnlyDomain(domain)
                    val expires = c["expires"]?.jsonPrimitive?.double ?: -1.0
                    if (expires > 0) expiresAt((expires * 1000).toLong())
                    if (c["secure"]?.jsonPrimitive?.booleanOrNull == true) secure()
                    if (c["httpOnly"]?.jsonPrimitive?.booleanOrNull == true) httpOnly()
                }.build()
            } catch (_: Exception) {
                return@forEach
            }
            HarnessCookieJar.put(cookie)
        }
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.headers(): Map<String, String> =
        (this["headers"] as? JsonObject)?.mapValues { (it.value as JsonPrimitive).content }.orEmpty()

    private class Req(private val j: JsonObject) : WebResourceRequest {
        override val url: Uri = Uri.parse(j.str("url").orEmpty())
        override val isForMainFrame = j["isMainFrame"]?.jsonPrimitive?.booleanOrNull ?: false
        override val isRedirect = j["isRedirect"]?.jsonPrimitive?.booleanOrNull ?: false
        override fun hasGesture() = false
        override val method = j.str("method") ?: "GET"
        override val requestHeaders = j.headers()
    }

    private class Err(override val description: CharSequence) : WebResourceError() {
        override val errorCode = when {
            "UNKNOWN_HOST" in description || "NAME_NOT_RESOLVED" in description -> WebViewClient.ERROR_HOST_LOOKUP
            "TIMEOUT" in description || "TIMED_OUT" in description -> WebViewClient.ERROR_TIMEOUT
            "CONNECTION_REFUSED" in description -> WebViewClient.ERROR_CONNECT
            else -> WebViewClient.ERROR_UNKNOWN
        }
    }

    private const val NOT_RUNNING = "ext-run: the WebView sidecar isn't running (ext-run.sh starts it; is the socket mounted?)"
}
