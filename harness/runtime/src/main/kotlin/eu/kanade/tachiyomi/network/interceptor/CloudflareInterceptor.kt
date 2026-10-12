/*
 * Ported from mihonapp/mihon core/common/src/main/kotlin/eu/kanade/tachiyomi/network/interceptor/CloudflareInterceptor.kt
 * at df6507256acce8e7f3660783a3db6dbd1a31b6b5 (tag v0.20.4).
 * Copyright the Mihon Open Source Project and the Tachiyomi Open Source Project.
 * Licensed under the Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0).
 * Changes for ext-run are marked "ext-run:".
 *
 * The class name is load-bearing: KeiSource checks the default client for an interceptor with this
 * simple name and moves it to the end of the application interceptors.
 */
package eu.kanade.tachiyomi.network.interceptor

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import eu.kanade.tachiyomi.network.AndroidCookieJar
import keiyoushi.extrun.CfClearances
import keiyoushi.extrun.Harness
import keiyoushi.extrun.MainThread
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import java.io.IOException
import java.util.concurrent.CountDownLatch

class CloudflareInterceptor(
    private val context: Context,
    private val cookieManager: AndroidCookieJar,
    defaultUserAgentProvider: () -> String,
) : WebViewInterceptor(context, defaultUserAgentProvider) {

    private val executor = MainThread.executor // ext-run: ContextCompat.getMainExecutor(context)

    override fun shouldIntercept(response: Response): Boolean {
        // Check if Cloudflare anti-bot is on
        return if (response.code in ERROR_CODES && response.header("Server") in SERVER_CHECK) {
            val document = Jsoup.parse(
                response.peekBody(Long.MAX_VALUE).string(),
                response.request.url.toString(),
            )

            // solve with webview only on captcha, not on geo block
            (
                document.getElementById("challenge-error-title") != null ||
                    document.getElementById("challenge-error-text") != null
                ).also { challenge -> // ext-run: diagnostics only
                if (!challenge && Harness.verbose) {
                    System.err.println("cloudflare: ${response.code} from Cloudflare on ${response.request.url} is not a challenge page (title: ${document.title()})")
                }
            }
        } else {
            if (Harness.verbose && response.code in ERROR_CODES) { // ext-run: diagnostics only
                System.err.println("cloudflare: ${response.code} on ${response.request.url}, Server: ${response.header("Server")} (not Cloudflare's)")
            }
            false
        }
    }

    // ext-run: requests to a host whose cf_clearance our WebView earned carry that WebView's UA (see
    // createWebView). In the app the WebView already had the request's UA, so nothing changes there.
    override fun proceed(chain: Interceptor.Chain, request: Request): Response =
        super.proceed(chain, CfClearances.withUserAgent(request))

    override fun intercept(
        chain: Interceptor.Chain,
        request: Request,
        response: Response,
    ): Response {
        try {
            response.close()
            cookieManager.remove(request.url, COOKIE_NAMES, 0)
            val oldCookie = cookieManager.get(request.url)
                .firstOrNull { it.name == "cf_clearance" }
            resolveWithWebView(request, oldCookie)

            // ext-run: was chain.proceed(request); proceed() adds the solving browser's UA.
            return proceed(chain, request).also { retried ->
                if (shouldIntercept(retried)) {
                    // Not in the app (it returns the response unchecked, as here): a note for the log.
                    System.err.println("cloudflare: ${request.url.host}: the retry with the new clearance was challenged again (returned as-is, as the app does)")
                    if (Harness.verbose) { // ext-run: diagnostics only -- is this a fresh challenge or a plain block, and did the retry really carry the earned clearance+UA
                        val sentCookie = cookieManager.get(request.url).firstOrNull { it.name == "cf_clearance" }?.value
                        System.err.println(
                            "  cloudflare diag: sent User-Agent=${retried.request.header("User-Agent")} " +
                                "cf_clearance=${sentCookie?.take(12)}... status=${retried.code} " +
                                "server=${retried.header("Server")} cf-mitigated=${retried.header("cf-mitigated")} cf-ray=${retried.header("cf-ray")}",
                        )
                    }
                }
            }
        }
        // Because OkHttp's enqueue only handles IOExceptions, wrap the exception so that
        // we don't crash the entire app
        catch (e: CloudflareBypassException) {
            // ext-run: this stderr line is what ext-monitor reads as "a challenge the harness can't solve".
            System.err.println("  ! Cloudflare challenge on ${request.url} (not solved: ${e.why})")
            throw IOException(BYPASS_FAILURE, e) // ext-run: context.stringResource(MR.strings.information_cloudflare_bypass_failure)
        } catch (e: Exception) {
            throw IOException(e)
        }
    }

    private fun resolveWithWebView(originalRequest: Request, oldCookie: Cookie?) {
        // We need to lock this thread until the WebView finds the challenge solution url, because
        // OkHttp doesn't support asynchronous interceptors.
        val latch = CountDownLatch(1)

        var webview: WebView? = null

        var challengeFound = false
        var cloudflareBypassed = false
        var isWebViewOutdated = false
        var why = "no new cf_clearance within 30s" // ext-run: for the log only

        val origRequestUrl = originalRequest.url.toString()
        // ext-run: DEVIATION, the User-Agent header isn't passed either (the WebView keeps its own UA, see createWebView).
        val headers = parseHeaders(originalRequest.headers).filterKeys { !it.equals("User-Agent", ignoreCase = true) }
        val start = System.currentTimeMillis()

        executor.execute {
            webview = createWebView(originalRequest)

            webview.webViewClient = object : WebViewClient() {
                // ext-run: nullable parameters, as the shim declares them.
                override fun onPageFinished(view: WebView?, url: String?) {
                    fun isCloudFlareBypassed(): Boolean {
                        return cookieManager.get(origRequestUrl.toHttpUrl())
                            .firstOrNull { it.name == "cf_clearance" }
                            .let { it != null && it != oldCookie }
                    }

                    if (isCloudFlareBypassed()) {
                        cloudflareBypassed = true
                        latch.countDown()
                    }

                    if (url == origRequestUrl && !challengeFound) {
                        // The first request didn't return the challenge, abort.
                        why = "the WebView got the page without a challenge"
                        latch.countDown()
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: WebResourceResponse?,
                ) {
                    if (request?.isForMainFrame == true) {
                        if (errorResponse?.statusCode in ERROR_CODES) {
                            // Found the Cloudflare challenge page.
                            challengeFound = true
                        } else {
                            // Unlock thread, the challenge wasn't found.
                            why = "the WebView got HTTP ${errorResponse?.statusCode}, not a challenge"
                            latch.countDown()
                        }
                    }
                }
            }

            webview.loadUrl(origRequestUrl, headers)
        }

        latch.awaitFor30Seconds()

        executor.execute {
            if (!cloudflareBypassed) {
                isWebViewOutdated = webview?.isOutdated() == true
            }

            webview?.run {
                stopLoading()
                destroy()
            }
        }

        // ext-run: log lines, and the solving browser's UA recorded for the retry (CfClearances).
        val secs = (System.currentTimeMillis() - start + 500) / 1000
        if (cloudflareBypassed) {
            CfClearances.solved(originalRequest.url)
            System.err.println("cloudflare: solved ${originalRequest.url.host} in ${secs}s")
        }

        // Throw exception if we failed to bypass Cloudflare
        if (!cloudflareBypassed) {
            // Prompt user to update WebView if it seems too outdated
            if (isWebViewOutdated) {
                System.err.println("ext-run: the app would toast: Please update the WebView app for better compatibility")
            }

            throw CloudflareBypassException("$why, after ${secs}s")
        }
    }
}

private val ERROR_CODES = listOf(403, 503)
private val SERVER_CHECK = arrayOf("cloudflare-nginx", "cloudflare")
private val COOKIE_NAMES = listOf("cf_clearance")

// ext-run: MR.strings.information_cloudflare_bypass_failure (English).
private const val BYPASS_FAILURE = "Failed to bypass Cloudflare"

private class CloudflareBypassException(val why: String) : Exception() // ext-run: `why` for the log line
