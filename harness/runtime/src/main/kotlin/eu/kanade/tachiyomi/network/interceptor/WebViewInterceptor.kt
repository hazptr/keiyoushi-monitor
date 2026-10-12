/*
 * Ported from mihonapp/mihon core/common/src/main/kotlin/eu/kanade/tachiyomi/network/interceptor/WebViewInterceptor.kt
 * (+ setDefaultSettings/isOutdated from app/src/main/java/eu/kanade/tachiyomi/util/system/WebViewUtil.kt)
 * at df6507256acce8e7f3660783a3db6dbd1a31b6b5 (tag v0.20.4).
 * Copyright the Mihon Open Source Project and the Tachiyomi Open Source Project.
 * Licensed under the Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0).
 * Changes for ext-run are marked "ext-run:".
 */
package eu.kanade.tachiyomi.network.interceptor

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import keiyoushi.extrun.Transport
import keiyoushi.extrun.WebViewBridge
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

abstract class WebViewInterceptor(
    private val context: Context,
    private val defaultUserAgentProvider: () -> String,
) : Interceptor {

    /**
     * When this is called, it initializes the WebView if it wasn't already. We use this to avoid
     * blocking the main thread too much. If used too often we could consider moving it to the
     * Application class.
     */
    private val initWebView by lazy {
        // ext-run: the MIUI / Samsung-on-S skip doesn't apply.
        try {
            WebSettings.getDefaultUserAgent(context)
        } catch (_: Exception) {
            // Avoid some crashes like when Chrome/WebView is being updated.
        }
    }

    abstract fun shouldIntercept(response: Response): Boolean

    abstract fun intercept(chain: Interceptor.Chain, request: Request, response: Response): Response

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = proceed(chain, request) // ext-run: was chain.proceed(request), see proceed()
        if (!shouldIntercept(response)) {
            return response
        }

        // ext-run: WebViewUtil.supportsWebView(context) -> is the WebView sidecar there. The app toasts.
        if (!WebViewBridge.available()) {
            System.err.println("  ! Cloudflare challenge on ${request.url} (not solved: no WebView sidecar; the app would toast \"WebView is required\")")
            return response
        }
        initWebView

        return intercept(chain, request, response)
    }

    /**
     * ext-run: every place Mihon calls `chain.proceed(request)` calls this. With --direct/--app it is
     * exactly `chain.proceed(request)` (plus a log line); otherwise it is the harness transport
     * (fixtures, --map, curl-impersonate), see keiyoushi.extrun.Transport.
     */
    protected open fun proceed(chain: Interceptor.Chain, request: Request): Response = Transport.proceed(chain, request)

    fun parseHeaders(headers: Headers): Map<String, String> {
        return headers
            // Keeping unsafe header makes webview throw [net::ERR_INVALID_ARGUMENT]
            .filter { (name, value) ->
                isRequestHeaderSafe(name, value)
            }
            .groupBy(keySelector = { (name, _) -> name }) { (_, value) -> value }
            .mapValues { it.value.getOrNull(0).orEmpty() }
    }

    fun CountDownLatch.awaitFor30Seconds() {
        await(30, TimeUnit.SECONDS)
    }

    fun createWebView(request: Request): WebView {
        return WebView(context).apply {
            setDefaultSettings()
            // Avoid sending empty User-Agent, Chromium WebView will reset to default if empty
            // ext-run: DEVIATION. Mihon sets the request's User-Agent (the app's Android Chrome UA) here:
            //   setUserAgent(request.header("User-Agent") ?: defaultUserAgentProvider())
            // The sidecar is desktop Chrome, and desktop Chrome claiming to be Android fails Cloudflare's
            // managed check (measured 2026-10-10 on mangadot.net, with the time zone set) while its native UA
            // passes. So the WebView keeps the browser's own UA, and CloudflareInterceptor retries with that
            // UA (see CfClearances). README "Parity with the app".
        }
    }
}

// ext-run: from WebViewUtil.kt (app module).
private fun WebView.setDefaultSettings() {
    with(getSettings()) {
        javaScriptEnabled = true
        domStorageEnabled = true
        useWideViewPort = true
        loadWithOverviewMode = true
        cacheMode = WebSettings.LOAD_DEFAULT

        // Handle popups properly
        setSupportMultipleWindows(true)

        // Allow zooming
        setSupportZoom(true)
        builtInZoomControls = true
        displayZoomControls = false
    }

    CookieManager.getInstance().acceptThirdPartyCookies(this)
}

// ext-run: from WebViewUtil.kt (app module): WebViewUtil.MINIMUM_WEBVIEW_VERSION = 118.
internal fun WebView.isOutdated(): Boolean {
    val uaRegexMatch = """.*Chrome/(\d+)\..*""".toRegex().matchEntire(WebViewBridge.defaultUserAgent())
    val major = if (uaRegexMatch != null && uaRegexMatch.groupValues.size > 1) uaRegexMatch.groupValues[1].toInt() else 0
    return major < 118
}

// Based on [IsRequestHeaderSafe] in
// https://source.chromium.org/chromium/chromium/src/+/main:services/network/public/cpp/header_util.cc
private fun isRequestHeaderSafe(_name: String, _value: String): Boolean {
    val name = _name.lowercase(Locale.ENGLISH)
    val value = _value.lowercase(Locale.ENGLISH)
    if (name in unsafeHeaderNames || name.startsWith("proxy-")) return false
    if (name == "connection" && value == "upgrade") return false
    return true
}
private val unsafeHeaderNames = listOf(
    "content-length", "host", "trailer", "te", "upgrade", "cookie2", "keep-alive", "transfer-encoding", "set-cookie",
)
