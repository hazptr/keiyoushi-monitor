package android.webkit

import android.content.Context
import keiyoushi.extrun.MainThread
import keiyoushi.extrun.WebViewBridge

/**
 * android.webkit.WebView backed by a page in the browser sidecar (keiyoushi.extrun.WebViewBridge).
 * The page is created on the first load, with the settings and JS interfaces present at that time,
 * as on Android where interfaces added later only show up after the next load. Calls return at once;
 * callbacks arrive on the main thread, interceptions and JS interface calls on background threads.
 */
open class WebView @JvmOverloads constructor(
    private val context: Context?,
    @Suppress("UNUSED_PARAMETER") attrs: Any? = null,
) {
    internal val id = WebViewBridge.register(this)
    private val settings = WebSettings(this)
    internal val jsInterfaces = LinkedHashMap<String, Any>()
    internal var opened = false
    internal var destroyed = false

    /** The harness's own WebView (the Cloudflare solver): always live, never served from or recorded into fixtures. */
    internal var harnessOwned = false

    @Volatile
    internal var currentUrl: String? = null

    open var webViewClient: WebViewClient = WebViewClient()
    open var webChromeClient: WebChromeClient? = null

    fun getSettings(): WebSettings = settings
    fun getContext(): Context? = context
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

    // View methods extensions call on a WebView.
    fun post(action: Runnable): Boolean = true.also { MainThread.post(action) }
    fun postDelayed(action: Runnable, delayMillis: Long): Boolean = android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(action, delayMillis)
    open fun onPause() {}
    open fun onResume() {}
    open fun pauseTimers() {}
    open fun resumeTimers() {}
    open fun clearCache(includeDiskFiles: Boolean) {}
    open fun clearHistory() {}
    open fun clearFormData() {}
    open fun canGoBack(): Boolean = false
    open fun goBack() {}
    open fun setBackgroundColor(color: Int) {}
    open fun removeAllViews() {}

    internal fun settingChanged(name: String) {
        if (opened) System.err.println("ext-run: WebView setting $name changed after the page was created; the sidecar ignores it")
    }

    internal fun blockImagesChanged() {
        if (opened) WebViewBridge.setBlockImages(this, settings.blockNetworkImage || !settings.loadsImagesAutomatically)
    }

    companion object {
        @JvmStatic fun setWebContentsDebuggingEnabled(enabled: Boolean) {}

        @JvmStatic fun getWebViewClassLoader(): ClassLoader = WebView::class.java.classLoader
    }
}
