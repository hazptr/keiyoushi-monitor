package android.webkit

import android.content.Context
import keiyoushi.extrun.WebViewBridge
import java.lang.reflect.InvocationHandler
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListMap

/**
 * android.webkit.WebView backed by a page in the browser sidecar (keiyoushi.extrun.WebViewBridge).
 * The page is created on the first load, with the settings and JS interfaces present at that time,
 * as on Android where interfaces added later only show up after the next load. Calls return at once;
 * callbacks arrive on the main thread, interceptions and JS interface calls on background threads.
 */
@Suppress("DEPRECATION")
open class WebView @JvmOverloads constructor(
    context: Context?,
    attrs: Any? = null,
) : android.widget.AbsoluteLayout(context, attrs) {
    internal val id = WebViewBridge.register(this)
    private val settings = WebSettings(this)
    internal val jsInterfaces = LinkedHashMap<String, Any>()
    internal var opened = false
    internal var destroyed = false

    /** The harness's own WebView (the Cloudflare solver): always live, never served from or recorded into fixtures. */
    internal var harnessOwned = false

    /** Turnstile checkboxes the sidecar ticked in this view (the `tick` event). */
    @Volatile internal var turnstileTicks = 0

    @Volatile
    internal var currentUrl: String? = null

    // androidx.webkit features (keiyoushi.extrun.WebViewGlue): kept here, sent when the page opens or at once if open.
    internal var audioMuted = false
    internal val startScripts = ConcurrentSkipListMap<Int, Pair<String, List<String>>>()
    private var nextScript = 1
    internal val messageListeners = ConcurrentHashMap<String, Pair<List<String>, InvocationHandler>>()

    @Synchronized
    internal fun addStartScript(script: String, rules: List<String>): Int {
        val id = nextScript++
        startScripts[id] = script to rules
        if (opened) WebViewBridge.addStartScript(this, id, script, rules)
        return id
    }

    internal fun removeStartScript(id: Int) {
        if (startScripts.remove(id) != null && opened) WebViewBridge.removeStartScript(this, id)
    }

    internal fun addMessageListener(name: String, rules: List<String>, listener: InvocationHandler) {
        // Chromium: "jsObjectName ... was already added."
        require(messageListeners.putIfAbsent(name, rules to listener) == null) { "jsObjectName $name was already added." }
        if (opened) WebViewBridge.addMessageListener(this, name, rules)
    }

    internal fun removeMessageListener(name: String) {
        if (messageListeners.remove(name) != null && opened) WebViewBridge.removeMessageListener(this, name)
    }

    open var webViewClient: WebViewClient = WebViewClient()
    open var webChromeClient: WebChromeClient? = null

    fun getSettings(): WebSettings = settings
    fun getUrl(): String? = currentUrl
    fun getOriginalUrl(): String? = currentUrl
    fun getTitle(): String? = null
    fun getProgress(): Int = 100

    open fun addJavascriptInterface(obj: Any, name: String) {
        jsInterfaces[name] = obj
        if (opened) WebViewBridge.addInterfaces(this, mapOf(name to obj))
    }

    open fun removeJavascriptInterface(name: String) {
        jsInterfaces.remove(name)
    }

    open fun loadUrl(url: String) = loadUrl(url, emptyMap())

    open fun loadUrl(url: String, additionalHttpHeaders: Map<String, String>) {
        if (destroyed) return
        currentUrl = url
        WebViewBridge.load(this, url, additionalHttpHeaders)
    }

    open fun postUrl(url: String, postData: ByteArray): Unit =
        throw UnsupportedOperationException("ext-run: WebView.postUrl isn't supported")

    open fun loadDataWithBaseURL(baseUrl: String?, data: String, mimeType: String?, encoding: String?, historyUrl: String?) {
        if (destroyed) return
        currentUrl = baseUrl
        WebViewBridge.loadData(this, baseUrl, data, mimeType ?: "text/html")
    }

    open fun loadData(data: String, mimeType: String?, encoding: String?) {
        val html = if (encoding.equals("base64", ignoreCase = true)) String(java.util.Base64.getDecoder().decode(data)) else data
        loadDataWithBaseURL(null, html, mimeType, null, null)
    }

    open fun evaluateJavascript(script: String, resultCallback: ValueCallback<String>?) {
        if (destroyed) return
        WebViewBridge.evaluate(this, script, resultCallback)
    }

    open fun reload() {
        currentUrl?.let { loadUrl(it) }
    }

    open fun stopLoading() {
        if (!destroyed && opened) WebViewBridge.stop(this)
    }

    open fun destroy() {
        if (destroyed) return
        destroyed = true
        WebViewBridge.destroy(this)
    }

    // View methods (post, layout, parent ...) come from android.view.View.
    open fun onPause() {}
    open fun onResume() {}
    open fun pauseTimers() {}
    open fun resumeTimers() {}
    open fun clearCache(includeDiskFiles: Boolean) {}
    open fun clearHistory() {}
    open fun clearFormData() {}
    open fun canGoBack(): Boolean = false
    open fun goBack() {}

    internal fun settingChanged(name: String) {
        if (opened) System.err.println("ext-run: WebView setting $name changed after the page was created; the sidecar ignores it")
    }

    internal fun includeCookiesChanged() {
        if (opened) WebViewBridge.setIncludeCookies(this, settings.includeCookiesOnIntercept)
    }

    internal fun userAgentMetadataChanged() {
        if (opened) WebViewBridge.setUserAgentMetadata(this)
    }

    internal fun blockImagesChanged() {
        if (opened) WebViewBridge.setBlockImages(this, settings.blockNetworkImage || !settings.loadsImagesAutomatically)
    }

    companion object {
        @JvmStatic fun setWebContentsDebuggingEnabled(enabled: Boolean) {}

        @JvmStatic fun getWebViewClassLoader(): ClassLoader = WebView::class.java.classLoader
    }
}
