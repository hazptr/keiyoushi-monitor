/*
 * Ported from mihonapp/mihon core/common/src/main/kotlin/eu/kanade/tachiyomi/network/OkHttpExtensions.kt
 * at df6507256acce8e7f3660783a3db6dbd1a31b6b5 (tag v0.20.4).
 * Copyright the Mihon Open Source Project and the Tachiyomi Open Source Project.
 * Licensed under the Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0).
 * Changes for ext-run are marked "ext-run:". Only what extensions-lib 1.6 exposes plus what
 * HttpSource.getImage uses; the deprecated Rx helpers and parseAs aren't in the 1.6 API.
 */
package eu.kanade.tachiyomi.network

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resumeWithException

// Based on https://github.com/square/okhttp/blob/master/okhttp-coroutines/src/main/kotlin/okhttp3/coroutines/ExecuteAsync.kt
// and https://github.com/gildor/kotlin-coroutines-okhttp
private suspend fun Call.await(callStack: Array<StackTraceElement>): Response {
    return suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation {
            try {
                this.cancel()
            } catch (_: Throwable) {
                // ignore
            }
        }

        this.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isCancelled) return
                val exception = IOException(e.message, e).apply { stackTrace = callStack }
                continuation.resumeWithException(exception)
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, value, _ ->
                    value.close()
                }
            }
        })
    }
}

suspend fun Call.await(): Response {
    val callStack = Exception().stackTrace.run { copyOfRange(1, size) }
    return await(callStack)
}

/**
 * Similar to [await] but throws [HttpException] if [Response.isSuccessful] returns false
 */
suspend fun Call.awaitSuccess(): Response {
    val callStack = Exception().stackTrace.run { copyOfRange(1, size) }
    val response = await(callStack)
    if (!response.isSuccessful) {
        response.close()
        throw HttpException(response.code).apply { stackTrace = callStack }
    }
    return response
}

// ext-run: the progress listener (reader UI) is left out; the client and Range handling are as in the app.
fun OkHttpClient.newCachelessCallWithProgress(
    request: Request,
    existingSize: Long = 0L,
): Call {
    val progressClient = newBuilder()
        .cache(null)
        .addNetworkInterceptor { chain ->
            val request = chain.request()
                .newBuilder()
                .apply {
                    if (existingSize > 0 && request.header("Range") == null) {
                        header("Range", "bytes=$existingSize-")
                    }
                }
                .build()

            chain.proceed(request)
        }
        .build()

    return progressClient.newCall(request)
}
