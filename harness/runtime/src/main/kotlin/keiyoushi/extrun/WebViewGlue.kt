package keiyoushi.extrun

import android.net.Uri
import android.webkit.WebSettings
import android.webkit.WebView
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Base64

/**
 * The WebView side of androidx.webkit's boundary interfaces (org.chromium.support_lib_boundary), which
 * extensions reach through WebViewCompat/WebSettingsCompat or core's WebViewGlueBridge. Each object is an
 * InvocationHandler the caller wraps in a Proxy of the boundary interface, as with Chromium's glue; methods
 * are matched by name, so the harness needs no androidx.webkit dependency.
 *
 * Supported features are those the sidecar reports (WebViewBridge.features): the nodriver backend
 * implements them, Playwright none, so there callers take the same fallback as on a WebView without them.
 * MULTI_PROFILE + CUSTOM_REQUEST_HEADERS: getProfile() is the one default profile; its origin-matched headers go
 * to the sidecar, which sets them on matching live requests.
 * JS_INJECTION_IN_FRAME_AND_WORLD (Chromium 146+): named isolated worlds (getJavaScriptWorld), scripts on document
 * start or end in a world (addJavaScriptOnEvent), web message listeners in a world, and JsReplyProxy.executeJavaScript.
 */
object WebViewGlue {
    /** The features this file implements; the sidecar's list is intersected with it. */
    val IMPLEMENTED = setOf(
        "DOCUMENT_START_SCRIPT:1",
        "WEB_MESSAGE_LISTENER",
        "WEB_MESSAGE_ARRAY_BUFFER",
        "USER_AGENT_METADATA",
        "COOKIE_INTERCEPT",
        "MUTE_AUDIO",
        "MULTI_PROFILE",
        "CUSTOM_REQUEST_HEADERS",
        "JS_INJECTION_IN_FRAME_AND_WORLD",
    )

    fun features(): Array<String> = WebViewBridge.features().filter { it in IMPLEMENTED }.toTypedArray()

    fun factory(): InvocationHandler = handler("WebViewProviderFactory") { name, args ->
        when (name) {
            "getSupportedFeatures" -> features()
            "createWebView" -> provider(args[0] as WebView)
            "getWebkitToCompatConverter" -> converter
            else -> unsupported("WebViewProviderFactory", name)
        }
    }

    // ---- WebViewProviderBoundaryInterface: one per WebView ----

    private fun provider(view: WebView): InvocationHandler = handler("WebViewProvider") { name, args ->
        when (name) {
            // No audio output in the sidecar; kept so a later read sees what was set.
            "setAudioMuted" -> { view.audioMuted = args[0] as Boolean; null }
            "isAudioMuted" -> view.audioMuted
            "addDocumentStartJavaScript" -> {
                val rules = rules(args[1])
                val id = view.addStartScript(args[0] as String, rules)
                handler("ScriptHandler") { n, _ ->
                    if (n == "remove") view.removeStartScript(id) else unsupported("ScriptHandler", n)
                    null
                }
            }
            "addWebMessageListener" -> {
                val world = (args.getOrNull(3) as String?).orEmpty()
                requireWorld(view, world)
                view.addMessageListener(args[0] as String, rules(args[1]), args[2] as InvocationHandler, world)
                null
            }
            // Chromium: a world is registered by name once; the same name gives the same world.
            "getJavaScriptWorld" -> (args[0] as String).let { name ->
                if (name.isEmpty()) 0 else view.worlds.computeIfAbsent(name) { view.worlds.size + 1 }
            }
            "addJavaScriptOnEvent" -> {
                val world = args[3] as String
                requireWorld(view, world)
                val event = args[2] as Int
                require(event == 0 || event == 1) { "Unknown injection event $event" }
                val id = view.addStartScript(args[0] as String, rules(args[1]), world, event)
                handler("ScriptHandler") { n, _ ->
                    if (n == "remove") view.removeStartScript(id) else unsupported("ScriptHandler", n)
                    null
                }
            }
            "removeWebMessageListener" -> { view.removeMessageListener(args[0] as String); null }
            "getProfile" -> profile
            else -> unsupported("WebViewProvider", name)
        }
    }

    private fun requireWorld(view: WebView, world: String) =
        require(world.isEmpty() || view.worlds.containsKey(world)) { "JavaScript world $world was not registered (getJavaScriptWorld)" }

    // ---- ProfileBoundaryInterface: the default profile, shared by every WebView (as in the app) ----

    class OriginHeader(val name: String, val value: String, val rules: List<String>)

    /** The profile's origin-matched headers (CUSTOM_REQUEST_HEADERS); the sidecar adds them to matching requests. */
    val originHeaders = java.util.concurrent.CopyOnWriteArrayList<OriginHeader>()

    private val profile: InvocationHandler = handler("Profile") { name, args ->
        fun header(): OriginHeader {
            val n = args[0] as String
            val v = args[1] as String
            // Chromium's checks: a valid header name, no line breaks in the value.
            require(n.isNotEmpty() && n.all { it in '!'..'~' && it !in "()<>@,;:\\\"/[]?={}" }) { "Invalid header name: $n" }
            require('\r' !in v && '\n' !in v && '\u0000' !in v) { "Invalid header value for $n" }
            @Suppress("UNCHECKED_CAST")
            return OriginHeader(n, v, rules((args[2] as Set<String>).toTypedArray()))
        }
        when (name) {
            "setOriginMatchedHeader" -> header().let { h ->
                originHeaders.removeIf { it.name.equals(h.name, true) }
                originHeaders += h
            }
            "addOriginMatchedHeader" -> header().let { h ->
                originHeaders.removeIf { it.name.equals(h.name, true) && it.value == h.value }
                originHeaders += h
            }
            "hasOriginMatchedHeader" -> return@handler originHeaders.any { it.name.equals(args[0] as String, true) }
            "clearOriginMatchedHeader" -> originHeaders.removeIf {
                it.name.equals(args[0] as String, true) && (args.size < 2 || it.value == args[1])
            }
            "clearAllOriginMatchedHeaders" -> originHeaders.clear()
            else -> unsupported("Profile", name)
        }
        WebViewBridge.setOriginHeaders()
        null
    }

    // ---- WebkitToCompatConverter / WebSettingsBoundaryInterface ----

    private val converter: InvocationHandler = handler("WebkitToCompatConverter") { name, args ->
        if (name == "convertSettings") settings(args[0] as WebSettings) else unsupported("WebkitToCompatConverter", name)
    }

    private fun settings(s: WebSettings): InvocationHandler = handler("WebSettings") { name, args ->
        when (name) {
            "setIncludeCookiesOnIntercept" -> { s.includeCookiesOnIntercept = args[0] as Boolean; null }
            "getIncludeCookiesOnIntercept" -> s.includeCookiesOnIntercept
            "getUserAgentMetadataMap" -> HashMap(s.userAgentMetadata())
            "setUserAgentMetadataFromMap" -> {
                @Suppress("UNCHECKED_CAST")
                s.setUserAgentMetadata(HashMap(args[0] as Map<String, Any?>))
                null
            }
            else -> unsupported("WebSettings", name)
        }
    }

    // ---- web messages: the page called <jsObjectName>.postMessage(data) ----

    /** Calls the extension's WebMessageListener (on the main thread, as Android does). */
    fun deliver(view: WebView, listener: InvocationHandler, name: String, world: String, data: String, arrayBuffer: Boolean, origin: String, isMainFrame: Boolean, frameId: String) {
        val payload = handler("WebMessagePayload") { n, _ ->
            when (n) {
                "getType" -> if (arrayBuffer) 1 else 0
                "getAsString" -> if (arrayBuffer) throw IllegalStateException("Not a string payload") else data
                "getAsArrayBuffer" -> if (arrayBuffer) Base64.getDecoder().decode(data) else throw IllegalStateException("Not an ArrayBuffer payload")
                "getSupportedFeatures" -> emptyArray<String>()
                else -> unsupported("WebMessagePayload", n)
            }
        }
        val message = handler("WebMessage") { n, _ ->
            when (n) {
                // Chromium throws for non-string payloads here; callers read getMessagePayload instead.
                "getData" -> if (arrayBuffer) throw IllegalStateException("Not a string payload") else data
                "getMessagePayload" -> payload
                "getPorts" -> emptyArray<InvocationHandler>()
                "getSupportedFeatures" -> arrayOf("WEB_MESSAGE_ARRAY_BUFFER")
                else -> unsupported("WebMessage", n)
            }
        }
        val reply = handler("JsReplyProxy") { n, args ->
            when (n) {
                "postMessage" -> { WebViewBridge.postReply(view, frameId, name, args[0] as String, false); null }
                "postMessageWithPayload" -> {
                    val p = args[0] as InvocationHandler
                    val type = p.invoke(null, method("getType"), null) as Int
                    if (type == 1) {
                        val bytes = p.invoke(null, method("getAsArrayBuffer"), null) as ByteArray
                        WebViewBridge.postReply(view, frameId, name, Base64.getEncoder().encodeToString(bytes), true)
                    } else {
                        WebViewBridge.postReply(view, frameId, name, p.invoke(null, method("getAsString"), null) as String, false)
                    }
                    null
                }
                "executeJavaScript" -> {
                    val callback = args[1] as InvocationHandler
                    WebViewBridge.replyEval(view, frameId, world, args[0] as String) { result ->
                        keiyoushi.extrun.MainThread.post {
                            if (result != null) {
                                callback.invoke(null, method("onSuccess"), arrayOf(result))
                            } else {
                                callback.invoke(null, method("onFailure"), arrayOf<Any?>(null))
                            }
                        }
                    }
                    null
                }
                "getSupportedFeatures" -> arrayOf("WEB_MESSAGE_ARRAY_BUFFER")
                else -> unsupported("JsReplyProxy", n)
            }
        }
        val origin2 = Uri.parse(origin)
        listener.invoke(listenerProxy(listener), method("onPostMessage"), arrayOf(view, message, origin2, isMainFrame, reply))
    }

    // ---- helpers ----

    /** Android's origin rules: "*", or scheme://host[:port] with an optional leading "*." on the host; no path. */
    fun rules(arg: Any?): List<String> {
        @Suppress("UNCHECKED_CAST")
        val list = (arg as? Array<String>)?.toList() ?: emptyList()
        list.forEach {
            require(it == "*" || RULE.matches(it)) { "allowedOriginRules $it is invalid" }
        }
        return list
    }

    private val RULE = Regex("""(?i)(https?)://(\*\.)?[^*:/\s]+(:\d{1,5})?""")

    private fun handler(what: String, body: (String, Array<out Any?>) -> Any?): InvocationHandler = InvocationHandler { proxy, m, args ->
        when (m.name) {
            "equals" -> proxy === args?.getOrNull(0)
            "hashCode" -> System.identityHashCode(proxy ?: this)
            "toString" -> "ext-run $what"
            else -> body(m.name, args ?: emptyArray())
        }
    }

    private fun unsupported(iface: String, name: String): Nothing =
        throw UnsupportedOperationException("ext-run: $iface.$name isn't implemented by the harness")

    /** A Method with the right name for calling a handler the extension built (it dispatches on the name). */
    private interface Names {
        fun onPostMessage(view: Any?, message: Any?, sourceOrigin: Any?, isMainFrame: Boolean, replyProxy: Any?)
        fun getType(): Int
        fun getAsString(): String?
        fun getAsArrayBuffer(): ByteArray?
        fun onSuccess(result: String?)
        fun onFailure(error: Any?)
    }

    private fun method(name: String): Method = Names::class.java.methods.first { it.name == name }

    private fun listenerProxy(h: InvocationHandler): Any =
        Proxy.newProxyInstance(Names::class.java.classLoader, arrayOf(Names::class.java), h)
}
