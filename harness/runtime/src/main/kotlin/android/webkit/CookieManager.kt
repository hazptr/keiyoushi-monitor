package android.webkit

import keiyoushi.extrun.HarnessCookieJar
import keiyoushi.extrun.WebViewBridge
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The app's network cookie jar is backed by CookieManager, so both are the harness jar here.
 * WebView pages get the jar's cookies when created and copy theirs back after each page load.
 */
open class CookieManager protected constructor() {
    private var accept = true

    open fun setAcceptCookie(accept: Boolean) {
        this.accept = accept
    }

    open fun acceptCookie(): Boolean = accept
    open fun setAcceptThirdPartyCookies(webview: WebView?, accept: Boolean) {}
    open fun acceptThirdPartyCookies(webview: WebView?): Boolean = true

    open fun setCookie(url: String, value: String) {
        val httpUrl = url.toHttpUrlOrNull() ?: return
        val cookie = Cookie.parse(httpUrl, value) ?: return
        HarnessCookieJar.saveFromResponse(httpUrl, listOf(cookie))
        WebViewBridge.pushCookie(cookie)
    }

    open fun setCookie(url: String, value: String, callback: ValueCallback<Boolean>?) {
        setCookie(url, value)
        callback?.onReceiveValue(true)
    }

    open fun getCookie(url: String): String? {
        val httpUrl = url.toHttpUrlOrNull() ?: return null
        WebViewBridge.pullCookies()
        return HarnessCookieJar.loadForRequest(httpUrl).joinToString("; ") { "${it.name}=${it.value}" }.ifEmpty { null }
    }

    open fun removeAllCookies(callback: ValueCallback<Boolean>?) {
        HarnessCookieJar.clear()
        callback?.onReceiveValue(true)
    }

    open fun removeSessionCookies(callback: ValueCallback<Boolean>?) {
        HarnessCookieJar.clear(sessionOnly = true)
        callback?.onReceiveValue(true)
    }

    @Deprecated("Android API 21") open fun removeAllCookie() = HarnessCookieJar.clear()

    open fun hasCookies(): Boolean = HarnessCookieJar.all().isNotEmpty()
    open fun flush() {}

    companion object {
        private val INSTANCE = CookieManager()

        @JvmStatic fun getInstance(): CookieManager = INSTANCE
    }
}
