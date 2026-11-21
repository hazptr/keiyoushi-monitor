/*
 * Ported from mihonapp/mihon core/common/src/main/kotlin/eu/kanade/tachiyomi/network/NetworkHelper.kt
 * at df6507256acce8e7f3660783a3db6dbd1a31b6b5 (tag v0.20.4).
 * Copyright the Mihon Open Source Project and the Tachiyomi Open Source Project.
 * Licensed under the Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0).
 * Changes for ext-run are marked "ext-run:".
 */
package eu.kanade.tachiyomi.network

import android.content.Context
import eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor
import eu.kanade.tachiyomi.network.interceptor.UncaughtExceptionInterceptor
import eu.kanade.tachiyomi.network.interceptor.UserAgentInterceptor
import keiyoushi.extrun.FixtureServer
import keiyoushi.extrun.Harness
import keiyoushi.extrun.Transport
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class NetworkHelper(
    private val context: Context,
    // ext-run: the app injects this; extensions-lib's NetworkHelper(Context) is the signature extensions see.
    private val preferences: NetworkPreferences = NetworkPreferences(),
) {

    val cookieJar = AndroidCookieJar()

    private val clientBuilder: OkHttpClient.Builder = run {
        val builder = OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .connectTimeout(30.seconds)
            .readTimeout(30.seconds)
            // ext-run: no call timeout with --pause, so a held challenge isn't cut off at 2 min (README "Holding a WebView").
            .callTimeout(if (keiyoushi.extrun.Hold.pause) kotlin.time.Duration.ZERO else 2.minutes)
            .cache(
                Cache(
                    directory = File(context.getCacheDir(), "network_cache"), // ext-run: the shim has no cacheDir property
                    maxSize = 5L * 1024 * 1024, // 5 MiB
                ),
            )
            .addInterceptor(UncaughtExceptionInterceptor())
            .addInterceptor(UserAgentInterceptor(::defaultUserAgentProvider))

        // ext-run: verbose HTTP logging and DoH are off by default in the app and are not ported
        // (preferences.verboseLogging == false, preferences.dohProvider == -1).

        // ext-run: with --fixtures, --offline or --map every connection goes to the harness's fixture
        // server instead of the site (socket factory, DNS and trust; keiyoushi.extrun.FixtureServer).
        // Sources build their clients on this one, so they inherit it; the app chain above is unchanged.
        if (Harness.fixtureServer) FixtureServer.install(builder)
        // ext-run: the request log and count (keiyoushi.extrun.Transport), as a network interceptor so it
        // sees every request whichever application interceptors a source keeps.
        builder.addNetworkInterceptor(Transport.RequestLog)
        builder
    }

    val client = clientBuilder
        .addInterceptor(
            CloudflareInterceptor(context, cookieJar, ::defaultUserAgentProvider),
        )
        .build()

    /**
     * @deprecated Since extension-lib 1.5
     */
    @Deprecated("The regular client handles Cloudflare by default")
    @Suppress("UNUSED")
    val cloudflareClient: OkHttpClient = client

    fun defaultUserAgentProvider() = preferences.defaultUserAgent.trim()
}
