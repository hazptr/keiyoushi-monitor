package keiyoushi.extrun

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Cookie
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.serialization.json.JsonObject
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Where responses come from. The ported CloudflareInterceptor calls [proceed] wherever Mihon calls
 * `chain.proceed(request)`. In order: --map URL=FILE, fixture file, curl-impersonate (--live,
 * recorded into the fixtures dir when one is given), the app's own path (--direct / --app:
 * `chain.proceed`, i.e. plain OkHttp with the app's interceptors, cookie jar and cache).
 * Otherwise: error. Challenges are handled by the CloudflareInterceptor, the same in every mode.
 */
object Transport {
    val requestCount = AtomicInteger()

    /** "host/first-path-segment" per request, for the closing summary line. */
    val requestKeys = java.util.concurrent.ConcurrentLinkedQueue<String>()
    private val liveSlots = Semaphore(2) // be polite: at most 2 concurrent live requests

    // Firefox 117 cipher order and UA, copied from curl-impersonate's curl_ff117 (what site-fetch runs). Unlike
    // site-fetch, no page-load headers (Accept: text/html, Sec-Fetch-*, ...) are added: requests carry only their
    // own headers, as in the app (an Accept the source didn't send made API endpoints answer with HTML, 2026-10-10).
    private val ffCiphers = "aes_128_gcm_sha_256,chacha20_poly1305_sha_256,aes_256_gcm_sha_384,ecdhe_ecdsa_aes_128_gcm_sha_256," +
        "ecdhe_rsa_aes_128_gcm_sha_256,ecdhe_ecdsa_chacha20_poly1305_sha_256,ecdhe_rsa_chacha20_poly1305_sha_256," +
        "ecdhe_ecdsa_aes_256_gcm_sha_384,ecdhe_rsa_aes_256_gcm_sha_384,ecdhe_ecdsa_aes_256_sha,ecdhe_ecdsa_aes_128_sha," +
        "ecdhe_rsa_aes_128_sha,ecdhe_rsa_aes_256_sha,rsa_aes_128_gcm_sha_256,rsa_aes_256_gcm_sha_384,rsa_aes_128_sha,rsa_aes_256_sha"
    private const val FIREFOX_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/117.0"
    private val ffHeaders = linkedMapOf(
        "User-Agent" to FIREFOX_UA,
        "Accept-Encoding" to "gzip, deflate, br",
    )
    private val hopHeaders = setOf("host", "connection", "content-length", "accept-encoding", "transfer-encoding", "cookie")

    fun proceed(chain: Interceptor.Chain, request: Request): Response {
        val n = requestCount.incrementAndGet()
        requestKeys.add(request.url.host + "/" + request.url.pathSegments.firstOrNull().orEmpty())
        val start = System.nanoTime()
        val fixture = Harness.fixturesDir?.let { fixtureFile(it, request) }
        val mapped = Harness.urlMap[request.url.toString()]

        val (response, via) = when {
            mapped != null -> fileResponse(request, mapped) to "map:${mapped.name}"
            fixture != null && fixture.exists() && !Harness.refresh -> fileResponse(request, fixture) to "fixture"
            Harness.live -> {
                val r = curl(request)
                if (fixture != null) {
                    save(fixture, r)
                    r.response to "live+rec"
                } else {
                    r.response to "live"
                }
            }
            // The app's path: the rest of the OkHttp chain (KeiSource's CompressionInterceptor, then
            // BridgeInterceptor with the cookie jar, the cache, network interceptors, JDK TLS).
            Harness.direct -> chain.proceed(request) to (if (Harness.fixturesDir != null) "app, not recorded" else "app")
            else -> throw IOException(
                "ext-run: no response source for ${request.method} ${request.url}" +
                    (fixture?.let { "\n  expected fixture: $it (add --live to record it)" } ?: "\n  pass --direct (app parity), --live, or --fixtures DIR"),
            )
        }
        val ms = (System.nanoTime() - start) / 1_000_000
        val size = response.body.contentLength().let { if (it >= 0) "${it / 1024}KB" else "?" }
        val cached = if (response.cacheResponse != null && response.networkResponse == null) " cache" else ""
        System.err.println("  #$n ${request.method} ${request.url} -> ${response.code} [$via$cached ${ms}ms $size]")
        return response
    }

    fun fixtureFile(dir: File, request: Request): File {
        val url = request.url
        var name = url.encodedPath.removePrefix("/").replace("/", "__").ifEmpty { "index" }
        url.encodedQuery?.let { name += "?$it" }
        if (request.method != "GET") {
            val buf = Buffer()
            request.body?.writeTo(buf)
            name += "#${request.method}-${sha1(buf.readByteArray()).take(10)}"
        }
        // Next.js RSC requests hit the same URL with different content.
        if (request.header("RSC") != null) name += "#rsc"
        name = name.replace(Regex("[^A-Za-z0-9._~%?=&,+@#!-]"), "_")
        if (name.length > 180) name = name.take(150) + "~" + sha1(name.toByteArray()).take(12)
        return dir.resolve(url.host).resolve(name)
    }

    private fun sha1(b: ByteArray) = MessageDigest.getInstance("SHA-1").digest(b).joinToString("") { "%02x".format(it) }

    private fun metaFile(f: File) = File(f.path + ".meta")

    private class Stored(val code: Int, val contentType: String, val finalUrl: String?, val bytes: ByteArray)

    private fun readStored(file: File): Stored {
        val meta = metaFile(file).takeIf { it.exists() }?.let { Json.parseToJsonElement(it.readText()).jsonObject }
        val bytes = file.readBytes()
        return Stored(
            code = meta?.get("code")?.jsonPrimitive?.int ?: 200,
            contentType = meta?.get("contentType")?.jsonPrimitive?.content ?: sniff(bytes),
            finalUrl = meta?.get("url")?.jsonPrimitive?.content,
            bytes = bytes,
        )
    }

    private fun fileResponse(request: Request, file: File): Response {
        val s = readStored(file)
        return Response.Builder()
            .request(if (s.finalUrl != null) request.newBuilder().url(s.finalUrl).build() else request)
            .protocol(Protocol.HTTP_1_1)
            .code(s.code)
            .message("")
            .header("Content-Type", s.contentType)
            .body(s.bytes.toResponseBody(s.contentType.toMediaTypeOrNull()))
            .build()
    }

    // ---- WebView traffic (the sidecar browser fetches itself; these replay, record and count it) ----

    val webviewCount = AtomicInteger()
    val webviewKeys = java.util.concurrent.ConcurrentLinkedQueue<String>()

    private fun webviewRequest(url: String, method: String, headers: Map<String, String>, postData: ByteArray?): Request =
        Request.Builder().url(url).apply {
            // Only what fixture names depend on.
            headers.entries.firstOrNull { it.key.equals("RSC", ignoreCase = true) }?.let { header("RSC", it.value) }
            method(method, if (method == "GET" || method == "HEAD") null else (postData ?: ByteArray(0)).toRequestBody())
        }.build()

    /** A --map file or fixture for a WebView request, as the sidecar's fulfil JSON, or null to go live. */
    fun serveWebView(url: String, method: String, headers: Map<String, String>, postData: ByteArray?): JsonObject? {
        val request = try {
            webviewRequest(url, method, headers, postData)
        } catch (_: IllegalArgumentException) {
            return null
        }
        val mapped = Harness.urlMap[url]
        val fixture = Harness.fixturesDir?.let { fixtureFile(it, request) }
        val (file, via) = when {
            mapped != null -> mapped to "map"
            fixture != null && fixture.exists() && !Harness.refresh -> fixture to "fixture"
            else -> return null
        }
        val s = readStored(file)
        return buildJsonObject {
            put("status", s.code)
            put("contentType", s.contentType)
            put("body", java.util.Base64.getEncoder().encodeToString(s.bytes))
            put("via", via)
        }
    }

    fun recordWebView(
        url: String,
        method: String,
        headers: Map<String, String>,
        postData: ByteArray?,
        code: Int,
        finalUrl: String?,
        contentType: String?,
        body: ByteArray,
    ) {
        val dir = Harness.fixturesDir ?: return
        val request = try {
            webviewRequest(url, method, headers, postData)
        } catch (_: IllegalArgumentException) {
            return
        }
        store(fixtureFile(dir, request), body, code, finalUrl ?: url, contentType)
    }

    fun logWebView(method: String, url: String, status: Int, via: String, type: String, error: String?) {
        // Only what went to the site (or stands in for it): not loadData documents, extension-intercepted or blocked requests.
        val n = if (via == "live" || via == "fixture" || via == "map" || via == "failed") {
            url.toHttpUrlOrNull()?.let { webviewKeys.add(it.host + "/" + it.pathSegments.firstOrNull().orEmpty()) }
            webviewCount.incrementAndGet().toString()
        } else {
            "-"
        }
        // Documents and API calls always; scripts, styles, images etc. with -v.
        if (Harness.verbose || type == "document" || type == "xhr" || type == "fetch" || via == "miss") {
            val result = if (status > 0) "$status" else (error ?: "failed")
            System.err.println("  wv#$n $method ${url.take(200)} -> $result [webview $via $type]")
        }
    }

    private fun sniff(b: ByteArray): String {
        val head = String(b, 0, minOf(b.size, 64)).trimStart()
        return when {
            head.startsWith("{") || head.startsWith("[") -> "application/json"
            b.size > 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() -> "image/jpeg"
            b.size > 3 && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() -> "image/png"
            else -> "text/html; charset=utf-8"
        }
    }

    private class Fetched(val response: Response, val bytes: ByteArray, val contentType: String?)

    private fun save(fixture: File, r: Fetched) =
        store(fixture, r.bytes, r.response.code, r.response.request.url.toString(), r.contentType)

    private fun store(fixture: File, bytes: ByteArray, code: Int, url: String, contentType: String?) {
        fixture.parentFile.mkdirs()
        fixture.writeBytes(bytes)
        metaFile(fixture).writeText(
            buildJsonObject {
                put("code", code)
                put("url", url)
                contentType?.let { put("contentType", it) }
            }.toString(),
        )
    }

    private fun curl(request: Request): Fetched {
        val tmp = File.createTempFile("ext-run", "").apply { delete(); mkdirs() }
        try {
            val headers = LinkedHashMap<String, String>()
            ffHeaders.forEach { (k, v) -> headers[k.lowercase()] = "$k: $v" }
            request.headers.forEach { (k, v) ->
                val lk = k.lowercase()
                if (lk in hopHeaders) return@forEach
                if (lk == "user-agent" && !Harness.keepUserAgent) return@forEach // keep UA consistent with the TLS fingerprint
                headers[lk] = "$k: $v"
            }
            // A cf_clearance only works with the UA of the browser that earned it (same client IP family too:
            // the JVM and WebView containers both egress over Docker's IPv4 bridge).
            CfClearances.userAgentFor(request.url)?.let { headers["user-agent"] = "User-Agent: $it" }
            HarnessCookieJar.loadForRequest(request.url).takeIf { it.isNotEmpty() }?.let { cookies ->
                headers["cookie"] = "Cookie: " + cookies.joinToString("; ") { "${it.name}=${it.value}" }
            }
            val root = Harness.curlRoot
            val cmd = mutableListOf(
                root.resolve("lib/ld-musl-x86_64.so.1").path,
                "--library-path", "${root.resolve("lib")}:${root.resolve("usr/lib")}",
                root.resolve("usr/local/bin/curl-impersonate-ff").path,
                "--ciphers", ffCiphers, "--http2", "--compressed",
                "-sS", "-g", "-L", "--max-redirs", "10", "--max-time", "90",
                "-D", tmp.resolve("h").path, "-o", tmp.resolve("b").path,
                "-w", "%{http_code}\n%{url_effective}\n%{content_type}",
            )
            headers.values.forEach { cmd += listOf("-H", it) }
            if (request.method != "GET") {
                cmd += listOf("-X", request.method)
                request.body?.let { body ->
                    val buf = Buffer().also { body.writeTo(it) }
                    tmp.resolve("d").writeBytes(buf.readByteArray())
                    cmd += listOf("--data-binary", "@${tmp.resolve("d")}")
                    body.contentType()?.let { cmd += listOf("-H", "Content-Type: $it") }
                }
            }
            cmd += request.url.newBuilder().fragment(null).build().toString() // OkHttp never sends the fragment
            liveSlots.acquire()
            val (exit, out) = try {
                val p = ProcessBuilder(cmd).redirectErrorStream(false).start()
                val stdout = p.inputStream.bufferedReader().readText()
                val stderr = p.errorStream.bufferedReader().readText()
                if (!p.waitFor(120, TimeUnit.SECONDS)) p.destroyForcibly()
                if (p.exitValue() != 0) throw IOException("curl-impersonate exit ${p.exitValue()}: ${stderr.trim()}")
                p.exitValue() to stdout
            } finally {
                liveSlots.release()
            }
            check(exit == 0)
            val (code, effUrl, ctype) = (out.lines() + listOf("", "", "")).take(3)
            val bytes = tmp.resolve("b").takeIf { it.exists() }?.readBytes() ?: ByteArray(0)

            // Last header block = final response after redirects. Cookies from every hop go to the jar.
            val blocks = tmp.resolve("h").readText().split(Regex("\r?\n\r?\n")).filter { it.startsWith("HTTP/") }
            val respHeaders = Headers.Builder()
            blocks.forEachIndexed { i, block ->
                block.lines().drop(1).filter { ':' in it }.forEach { line ->
                    val k = line.substringBefore(':').trim()
                    val v = line.substringAfter(':').trim()
                    if (k.equals("set-cookie", true)) {
                        Cookie.parse(effUrl.toHttpUrl(), v)?.let { HarnessCookieJar.saveFromResponse(effUrl.toHttpUrl(), listOf(it)) }
                    }
                    if (i == blocks.lastIndex && k.lowercase() !in setOf("content-encoding", "content-length", "transfer-encoding")) {
                        respHeaders.addUnsafeNonAscii(k, v)
                    }
                }
            }
            val contentType = ctype.ifEmpty { null }
            val response = Response.Builder()
                .request(request.newBuilder().url(effUrl.ifEmpty { request.url.toString() }).build())
                .protocol(Protocol.HTTP_2)
                .code(code.toIntOrNull() ?: 0)
                .message("")
                .headers(respHeaders.build())
                .body(bytes.toResponseBody(contentType?.toMediaTypeOrNull()))
                .build()
            return Fetched(response, bytes, contentType)
        } finally {
            tmp.deleteRecursively()
        }
    }
}
