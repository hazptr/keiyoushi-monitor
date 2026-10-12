package android.webkit

import android.graphics.Bitmap
import android.net.Uri
import java.io.InputStream

// The android.webkit types core's runWebView and extensions use, with Android's method names.
// Behaviour lives in WebView (forwarded to the browser sidecar by keiyoushi.extrun.WebViewBridge).

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class JavascriptInterface

fun interface ValueCallback<T> {
    fun onReceiveValue(value: T)
}

interface WebResourceRequest {
    val url: Uri
    val isForMainFrame: Boolean
    val isRedirect: Boolean
    fun hasGesture(): Boolean
    val method: String
    val requestHeaders: Map<String, String>
}

open class WebResourceResponse(
    var mimeType: String?,
    var encoding: String?,
    var data: InputStream?,
) {
    var statusCode: Int = 200
        private set
    var reasonPhrase: String = "OK"
        private set
    var responseHeaders: Map<String, String>? = null

    constructor(
        mimeType: String?,
        encoding: String?,
        statusCode: Int,
        reasonPhrase: String,
        responseHeaders: Map<String, String>?,
        data: InputStream?,
    ) : this(mimeType, encoding, data) {
        setStatusCodeAndReasonPhrase(statusCode, reasonPhrase)
        this.responseHeaders = responseHeaders
    }

    fun setStatusCodeAndReasonPhrase(statusCode: Int, reasonPhrase: String) {
        this.statusCode = statusCode
        this.reasonPhrase = reasonPhrase
    }
}

abstract class WebResourceError {
    abstract val errorCode: Int
    abstract val description: CharSequence
}

abstract class RenderProcessGoneDetail {
    abstract fun didCrash(): Boolean
    abstract fun rendererPriorityAtExit(): Int
}

class ConsoleMessage(
    private val message: String,
    private val sourceId: String,
    private val lineNumber: Int,
    private val messageLevel: MessageLevel,
) {
    enum class MessageLevel { TIP, LOG, WARNING, ERROR, DEBUG }

    fun message() = message
    fun sourceId() = sourceId
    fun lineNumber() = lineNumber
    fun messageLevel() = messageLevel
}

open class WebViewClient {
    open fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {}
    open fun onPageFinished(view: WebView?, url: String?) {}
    open fun onLoadResource(view: WebView?, url: String?) {}

    // Android's default for the request overload calls the deprecated String one.
    open fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? =
        shouldInterceptRequest(view, request?.url?.toString())

    @Deprecated("Android API 21")
    open fun shouldInterceptRequest(view: WebView?, url: String?): WebResourceResponse? = null

    open fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean =
        @Suppress("DEPRECATION")
        shouldOverrideUrlLoading(view, request?.url?.toString())

    @Deprecated("Android API 24")
    open fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = false

    // Android's default for the request overload calls the deprecated one for main-frame errors.
    open fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
        if (request?.isForMainFrame == true && error != null) {
            @Suppress("DEPRECATION")
            onReceivedError(view, error.errorCode, error.description.toString(), request.url.toString())
        }
    }

    @Deprecated("Android API 23")
    open fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {}

    open fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {}

    open fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean = false

    companion object {
        const val ERROR_UNKNOWN = -1
        const val ERROR_HOST_LOOKUP = -2
        const val ERROR_CONNECT = -6
        const val ERROR_TIMEOUT = -8
        const val ERROR_FAILED_SSL_HANDSHAKE = -11
    }
}

open class WebChromeClient {
    open fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean = false
    open fun onProgressChanged(view: WebView?, newProgress: Int) {}
    open fun onReceivedTitle(view: WebView?, title: String?) {}
}

/** Settings are read when the page is first created; later changes only log a warning. */
open class WebSettings internal constructor(private val owner: WebView) {
    var javaScriptEnabled = false
        set(value) {
            owner.settingChanged("javaScriptEnabled")
            field = value
        }
    var domStorageEnabled = false
    var databaseEnabled = false
    var blockNetworkImage = false
        set(value) {
            field = value
            owner.blockImagesChanged()
        }
    var loadsImagesAutomatically = true
        set(value) {
            field = value
            owner.blockImagesChanged()
        }
    var useWideViewPort = false
    var loadWithOverviewMode = false
    var javaScriptCanOpenWindowsAutomatically = false
    var allowFileAccess = false
    var allowContentAccess = true
    var mediaPlaybackRequiresUserGesture = true
    var builtInZoomControls = false
    var displayZoomControls = true
    var cacheMode = LOAD_DEFAULT
    var mixedContentMode = MIXED_CONTENT_NEVER_ALLOW
    var textZoom = 100

    private var ua: String? = null
    var userAgentString: String?
        get() = ua ?: getDefaultUserAgent(null)
        set(value) {
            owner.settingChanged("userAgentString")
            ua = value?.ifEmpty { null }
        }

    internal fun userAgentOverride(): String? = ua

    fun setSupportMultipleWindows(support: Boolean) {}
    fun setSupportZoom(support: Boolean) {}
    fun setGeolocationEnabled(flag: Boolean) {}

    companion object {
        const val LOAD_DEFAULT = -1
        const val LOAD_NO_CACHE = 2
        const val MIXED_CONTENT_NEVER_ALLOW = 1

        @JvmStatic
        fun getDefaultUserAgent(context: android.content.Context?): String = keiyoushi.extrun.WebViewBridge.defaultUserAgent()
    }
}
