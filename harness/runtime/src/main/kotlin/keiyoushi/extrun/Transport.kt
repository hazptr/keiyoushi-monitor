package keiyoushi.extrun

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.serialization.json.JsonObject
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import okio.buffer
import kotlinx.serialization.json.booleanOrNull
import okhttp3.HttpUrl
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/**
 * The log line for every request the app's OkHttp stack puts on the network. [RequestLog] is a network
 * interceptor on NetworkHelper's base client, which every source client is built from, so every request is
 * counted whichever application interceptors a source keeps or removes (CloudflareInterceptor included).
 * It sits above any network interceptor a source adds (those are appended after it). Each redirect hop and
 * each retry is its own line; a response served from the app's cache without a request is not counted.
 * With --fixtures, --offline or --map the socket under the chain is [FixtureServer], which serves or
 * records; it tells this side what it did through [FixtureServer.exchange].
 */
object Transport {
    val requestCount = AtomicInteger()

    /** "host/first-path-segment" per request, for the closing summary line. */
    val requestKeys = java.util.concurrent.ConcurrentLinkedQueue<String>()

    object RequestLog : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response = log(chain, chain.request())
    }

    private fun log(chain: Interceptor.Chain, request: Request): Response {
        val n = requestCount.incrementAndGet()
        requestKeys.add(request.url.host + "/" + request.url.pathSegments.firstOrNull().orEmpty())
        val start = System.nanoTime()
        val response = try {
            chain.proceed(request)
        } catch (e: IOException) {
            // The fixture server closes the connection when the site can't be reached; the app would have
            // got the upstream exception itself (UnknownHostException, a timeout, an SSL error).
            throw (if (FixtureServer.started) FixtureServer.takeFailure(request.method, request.url) else null) ?: e
        }
        val exchange = if (FixtureServer.started) FixtureServer.exchange(request.method, request.url) else null
        if (exchange?.via == "miss") {
            response.close()
            throw IOException("ext-run: --offline: no fixture for ${request.method} ${request.url}\n  expected fixture: ${exchange.missPath}")
        }
        val via = exchange?.via ?: "app"
        val ms = (System.nanoTime() - start) / 1_000_000
        val size = response.body.contentLength().let { if (it >= 0) "${it / 1024}KB" else "?" }
        // body=: non-GET requests with different bodies are different requests (ext-diff.py keys on it).
        val body = if (request.method != "GET") " body=${bodySha(request)}" else ""
        System.err.println("  #$n ${request.method} ${request.url} -> ${response.code} [$via ${ms}ms $size]$body")
        // ext-diff.py reads this line: the replayed response answered a request this run sent differently.
        exchange?.mismatch?.let { System.err.println("  ~ #$n fixture recorded from a different request: $it") }
        return response
    }

    /**
     * The fixture for [request] in [dir]. Names are canonical (see [canonicalQuery]): equivalent encodings of
     * a URL (`%2C` or `,`) name one fixture, and cache-buster parameters don't count. With [canonical] false,
     * the literal name fixtures had before 2026-11-17, which [findFixture] still finds.
     */
    fun fixtureFile(dir: File, request: Request, canonical: Boolean = true): File {
        val url = request.url
        val path = if (canonical) canonicalEncoding(url.encodedPath) else url.encodedPath
        var name = path.removePrefix("/").replace("/", "__").ifEmpty { "index" }
        (if (canonical) url.encodedQuery?.let(::canonicalQuery) else url.encodedQuery)?.let { name += "?$it" }
        if (request.method != "GET") name += "#${request.method}-${bodySha(request)}"
        // Next.js RSC requests hit the same URL with different content.
        if (request.header("RSC") != null) name += "#rsc"
        name = name.replace(Regex("[^A-Za-z0-9._~%?=&,+@#!-]"), "_")
        if (name.length > 180) name = name.take(150) + "~" + sha1(name.toByteArray()).take(12)
        return dir.resolve(url.host).resolve(name)
    }

    /** The fixture to replay for [request]: the canonical name, or an older fixture's literal name when only that exists. */
    fun findFixture(dir: File, request: Request): File {
        val canonical = fixtureFile(dir, request)
        if (canonical.exists()) return canonical
        val literal = fixtureFile(dir, request, canonical = false)
        return if (literal != canonical && literal.exists()) literal else canonical
    }

    // Characters a name keeps literal (the rest of RFC 3986's sub-delims stay percent-encoded); '&', '=', '+', '/' never change.
    private const val NAME_SAFE = "-._~,@!"
    private const val SUB_DELIMS = "!$'()*,;:@"

    /** One spelling per URL: unreserved and name-safe characters decoded, other sub-delims encoded, hex in upper case. */
    fun canonicalEncoding(s: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length && s[i + 1].isHex() && s[i + 2].isHex()) {
                val d = s.substring(i + 1, i + 3).toInt(16).toChar()
                if (d.isLetterOrDigitAscii() || d in NAME_SAFE) out.append(d) else out.append('%').append(s.substring(i + 1, i + 3).uppercase())
                i += 3
                continue
            }
            if (c in SUB_DELIMS && c !in NAME_SAFE) out.append('%').append("%02X".format(c.code)) else out.append(c)
            i++
        }
        return out.toString()
    }

    private fun Char.isHex() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
    private fun Char.isLetterOrDigitAscii() = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

    /** Cache-buster parameter names: dropped from fixture names when their value is a timestamp or a random number. */
    private val cacheBusters = setOf("_", "t", "ts", "_t", "time", "timestamp", "retry", "cb", "cachebuster", "cache", "nocache", "nc", "rnd", "rand", "random", "r", "v", "_dc")
    private val bustValue = Regex("""\d{10,13}|\d{10}\.\d+|0\.\d{6,}""")

    /**
     * A query as fixture names spell it: [canonicalEncoding] per parameter, minus cache busters
     * (`retry=<currentTimeMillis>`, `_=<ms>`, `t=<seconds>`, `rnd=0.123...`) and any `--fixture-ignore-param` name.
     * Null when nothing is left.
     */
    fun canonicalQuery(query: String): String? = query.split('&').filter { p ->
        val name = canonicalEncoding(p.substringBefore('='))
        val value = p.substringAfter('=', "")
        name !in Harness.ignoreParams && !(name.lowercase() in cacheBusters && bustValue.matches(value))
    }.joinToString("&") { canonicalEncoding(it) }.ifEmpty { null }

    private fun sha1(b: ByteArray) = MessageDigest.getInstance("SHA-1").digest(b).joinToString("") { "%02x".format(it) }

    /**
     * The body's hash, for fixture names and the log. A multipart body has its boundary (OkHttp's
     * MultipartBody picks a random one per request) replaced by a fixed token first, so the same form
     * keys the same fixture on every run; other bodies hash as they are.
     */
    private fun bodySha(request: Request): String {
        val buf = Buffer()
        request.body?.writeTo(buf)
        var bytes = buf.readByteArray()
        val type = (request.header("Content-Type") ?: request.body?.contentType()?.toString())?.toMediaTypeOrNull()
        val boundary = type?.takeIf { it.type.equals("multipart", ignoreCase = true) }?.parameter("boundary")
        if (!boundary.isNullOrEmpty()) {
            bytes = String(bytes, Charsets.ISO_8859_1).replace(String(boundary.toByteArray(), Charsets.ISO_8859_1), MULTIPART_BOUNDARY)
                .toByteArray(Charsets.ISO_8859_1)
        }
        return sha1(bytes).take(10)
    }

    private const val MULTIPART_BOUNDARY = "ext-run-boundary"

    // ---- what a fixture remembers about its request and response headers (in .meta, redacted) ----

    /** The old fixed redaction marker (fixtures from before 2026-11-17). */
    const val REDACTED = Secrets.LEGACY

    /**
     * A header value as a .meta keeps it: secrets become their placeholder ([Secrets]). Request headers are
     * compared after redaction, so a redacted header still counts as present, absent or changed.
     */
    fun redact(name: String, value: String): String = Secrets.redact(name, value)

    private fun headersJson(headers: Headers): JsonArray {
        // Learn every header's secrets first, so a token header recorded before the cookie it echoes is redacted too.
        headers.forEach { (k, v) -> Secrets.learnHeader(k, v) }
        return buildJsonArray {
            headers.forEach { (k, v) -> add(buildJsonArray { add(JsonPrimitive(k)); add(JsonPrimitive(redact(k, v))) }) }
        }
    }

    /** Headers that say something about the request itself, not the transport (OkHttp/curl set these). */
    private val transportHeaders = setOf("host", "connection", "content-length", "accept-encoding", "transfer-encoding", "te", "keep-alive")

    /**
     * Fixtures from before the fixture server (no "wire" in the .meta) recorded the request above
     * BridgeInterceptor, without the Cookie and Content-Type it adds.
     */
    private val bridgeHeaders = setOf("cookie", "content-type")

    private fun comparable(headers: List<Pair<String, String>>, legacy: Boolean): Map<String, String> =
        headers.filter { it.first.lowercase() !in transportHeaders && !(legacy && it.first.lowercase() in bridgeHeaders) }
            .groupBy({ it.first.lowercase() }, { redact(it.first, it.second) })
            .mapValues { it.value.joinToString(", ") }

    /**
     * Null when [fixture] has no recorded request (fixtures from before 2026-10-25, hand-made ones) or
     * this request matches it; otherwise which headers differ. Method and body are in the file name.
     */
    fun requestMismatch(fixture: File, request: Request): String? {
        val meta = metaJson(fixture)
        val recorded = meta?.get("request")?.jsonObject?.get("headers")?.jsonArray ?: return null
        val legacy = meta["wire"]?.jsonPrimitive?.booleanOrNull != true
        val then = comparable(recorded.map { it.jsonArray.let { kv -> kv[0].jsonPrimitive.content to kv[1].jsonPrimitive.content } }, legacy)
        val now = comparable(request.headers.map { it.first to it.second }, legacy)
        val diffs = (then.keys + now.keys).sorted().mapNotNull { k ->
            when {
                k !in now -> "$k (recorded, not sent now)"
                k !in then -> "$k (sent now, not recorded)"
                !sameRedacted(then.getValue(k), now.getValue(k)) -> "$k (value differs)"
                else -> null
            }
        }
        return diffs.takeIf { it.isNotEmpty() }?.joinToString("; ")
    }

    /** A recorded value against this run's: a legacy "<redacted>" one stands for any value of the secret. */
    private fun sameRedacted(then: String, now: String): Boolean = when {
        then == now -> true
        REDACTED !in then -> false
        then == REDACTED -> true
        else -> then == Regex("extrunRedacted[0-9a-f]{16}").replace(now, REDACTED)
    }

    private fun metaFile(f: File) = File(f.path + ".meta")

    private class Stored(val code: Int, val contentType: String, val finalUrl: String?, val bytes: ByteArray, val headers: List<Pair<String, String>>)

    private fun metaJson(file: File): JsonObject? = metaFile(file).takeIf { it.exists() }?.let { Json.parseToJsonElement(it.readText()).jsonObject }

    // Not replayed: the body is stored decoded and Content-Type comes from the meta.
    private val unreplayedHeaders = setOf("content-type", "content-length", "content-encoding", "transfer-encoding")

    private fun readStored(file: File): Stored {
        val meta = metaJson(file)
        val bytes = file.readBytes()
        return Stored(
            code = meta?.get("code")?.jsonPrimitive?.int ?: 200,
            contentType = meta?.get("contentType")?.jsonPrimitive?.content ?: sniff(bytes),
            finalUrl = meta?.get("url")?.jsonPrimitive?.content,
            bytes = bytes,
            // Recorded response headers (fixtures from 2026-10-25 on), minus redacted values, which mean nothing replayed.
            headers = meta?.get("headers")?.jsonArray.orEmpty()
                .map { it.jsonArray.let { kv -> kv[0].jsonPrimitive.content to kv[1].jsonPrimitive.content } }
                .filter { (k, v) -> k.lowercase() !in unreplayedHeaders && replayable(v) },
        )
    }

    /**
     * Redacted header values: the old whole-value "<redacted>" means nothing replayed; a value carrying a
     * placeholder (a cookie, a token header) replays only --offline, so a run with the network never sends
     * one to the site.
     */
    private fun replayable(v: String) = v != REDACTED && (Harness.offline || !Secrets.carriesRedaction(v))

    private val httpDate = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME

    /** The recorded response's Date header, as epoch millis. */
    private fun recordedAt(headers: List<Pair<String, String>>): Long? = headers.lastOrNull { it.first.equals("date", ignoreCase = true) }
        ?.let { runCatching { java.time.ZonedDateTime.parse(it.second.trim(), httpDate).toInstant().toEpochMilli() }.getOrNull() }

    /**
     * A recorded cookie that was alive when recorded but whose absolute Expires has passed since gets the
     * lifetime it had then (as Max-Age, which wins over Expires), so an old recording of a login still logs in.
     */
    private fun refreshExpiry(setCookie: String, recordedAt: Long?, url: HttpUrl): String {
        if (recordedAt == null || Regex(""";\s*max-age\s*=""", RegexOption.IGNORE_CASE).containsMatchIn(setCookie)) return setCookie
        val c = okhttp3.Cookie.parse(url, setCookie) ?: return setCookie
        if (!c.persistent || c.expiresAt > System.currentTimeMillis() || c.expiresAt <= recordedAt) return setCookie
        return "$setCookie; Max-Age=${(c.expiresAt - recordedAt) / 1000}"
    }

    /** A recorded response's headers as they replay now (see [replayable], [refreshExpiry]). */
    private fun replayHeaders(recorded: List<Pair<String, String>>, url: HttpUrl): List<Pair<String, String>> {
        val at = recordedAt(recorded)
        return recorded.filter { (_, v) -> replayable(v) }
            .map { (k, v) -> if (k.equals("set-cookie", ignoreCase = true)) k to refreshExpiry(v, at, url) else k to v }
    }

    /** A body [Secrets] scrubbed after recording: the fixture now holds the decoded, scrubbed body and no `.raw`. */
    fun bodyScrubbed(fixture: File, body: ByteArray) {
        fixture.writeBytes(body)
        File(fixture.path + ".raw").delete()
        val meta = metaJson(fixture) ?: return
        if ("raw" in meta) metaFile(fixture).writeText(JsonObject(meta - "raw").toString())
    }

    // ---- the fixture server's side: replay and record what went over the wire ----

    /** Internal marker on a [replay] answer: a legacy redirect to this final URL (never sent). */
    const val ALIAS_HEADER = "X-Ext-Run-Alias"

    /**
     * A fixture (or --map file) as the response bytes to put on the wire. A fixture recorded by the
     * fixture server replays its headers and body as the site sent them (the still-encoded body from
     * `.raw` when there is one). Older fixtures and hand-made files hold a decoded body: they replay
     * without Content-Encoding, with the .meta's Content-Type. Those recorded through curl followed
     * redirects and saved the final URL; with [legacyRedirect] such a fixture first answers a redirect
     * to that URL (and the server serves the body there), so the app ends up on the same URL as then.
     */
    fun replay(file: File, method: String, url: HttpUrl, legacyRedirect: Boolean): FixtureServer.Reply {
        val meta = metaJson(file)
        if (meta?.get("wire")?.jsonPrimitive?.booleanOrNull == true) {
            val raw = File(file.path + ".raw")
            val encoded = meta["raw"]?.jsonPrimitive?.booleanOrNull == true && raw.exists()
            val headers = replayHeaders(
                meta["headers"]?.jsonArray.orEmpty().map { it.jsonArray.let { kv -> kv[0].jsonPrimitive.content to kv[1].jsonPrimitive.content } },
                url,
            ).filter { (k, _) -> encoded || !k.equals("content-encoding", ignoreCase = true) } // the fixture file is the decoded body
            val body = if (encoded) raw.readBytes() else file.readBytes()
            return FixtureServer.Reply(
                meta["code"]?.jsonPrimitive?.int ?: 200,
                meta["message"]?.jsonPrimitive?.content.orEmpty(),
                headers,
                body,
            )
        }
        val s = readStored(file)
        if (legacyRedirect && (method == "GET" || method == "HEAD")) {
            val final = s.finalUrl?.toHttpUrlOrNull()?.newBuilder()?.fragment(null)?.build()
            if (final != null && final != url.newBuilder().fragment(null).build()) {
                return FixtureServer.Reply(302, "", listOf("Location" to final.toString(), ALIAS_HEADER to final.toString()), ByteArray(0))
            }
        }
        return FixtureServer.Reply(s.code, "", replayHeaders(s.headers, url) + ("Content-Type" to s.contentType), s.bytes)
    }

    // ---- --map ----

    /** What --map keys on: the URL with its path and query spelt as fixture names spell them (no fragment). */
    fun mapKey(url: HttpUrl): String {
        val port = if (url.port == HttpUrl.defaultPort(url.scheme)) "" else ":${url.port}"
        val query = url.encodedQuery?.let(::canonicalQuery)?.let { "?$it" }.orEmpty()
        return "${url.scheme}://${url.host}$port${canonicalEncoding(url.encodedPath)}$query"
    }

    fun mapKey(url: String): String = url.toHttpUrlOrNull()?.let(::mapKey) ?: url

    /** A raw HTTP response file (status line, headers, blank line, body bytes as sent), or null for a plain body. */
    internal fun parseHttpFile(bytes: ByteArray): FixtureServer.Reply? {
        if (bytes.size < 12 || String(bytes, 0, 5, Charsets.ISO_8859_1) != "HTTP/") return null
        var i = 0
        fun line(): String? {
            val start = i
            while (i < bytes.size && bytes[i] != '\n'.code.toByte()) i++
            if (i >= bytes.size) return null
            return String(bytes, start, i++ - start, Charsets.ISO_8859_1).removeSuffix("\r")
        }
        val status = line() ?: return null
        val parts = status.split(' ', limit = 3)
        val code = parts.getOrNull(1)?.toIntOrNull() ?: return null
        val headers = mutableListOf<Pair<String, String>>()
        while (true) {
            val h = line() ?: break
            if (h.isEmpty()) break
            val c = h.indexOf(':')
            if (c > 0) headers += h.substring(0, c).trim() to h.substring(c + 1).trim()
        }
        var body = bytes.copyOfRange(minOf(i, bytes.size), bytes.size)
        headers.lastOrNull { it.first.equals("content-length", ignoreCase = true) }?.second?.toIntOrNull()
            ?.takeIf { it in 0 until body.size }?.let { body = body.copyOf(it) }
        return FixtureServer.Reply(code, parts.getOrNull(2).orEmpty(), headers.filter { !it.first.equals("transfer-encoding", ignoreCase = true) }, body)
    }

    /** A --map answer: a raw HTTP response file as written, else the file as a fixture (its .meta honoured); `:status` wins. */
    fun replayMap(entry: MapEntry, method: String, url: HttpUrl): FixtureServer.Reply {
        val r = parseHttpFile(entry.file.readBytes()) ?: replay(entry.file, method, url, legacyRedirect = false)
        return if (entry.code == null) r else FixtureServer.Reply(entry.code, "", r.headers, r.body)
    }

    /** A recorded Cloudflare challenge (refetched when recording, see FixtureServer). */
    fun isChallenge(file: File): Boolean {
        val meta = metaJson(file) ?: return false
        val code = meta["code"]?.jsonPrimitive?.int ?: return false
        if (code != 403 && code != 503) return false
        return meta["headers"]?.jsonArray.orEmpty().any {
            val kv = it.jsonArray
            kv[0].jsonPrimitive.content.equals("server", ignoreCase = true) &&
                kv[1].jsonPrimitive.content.lowercase() in setOf("cloudflare", "cloudflare-nginx")
        }
    }

    /**
     * Records an exchange as the fixture server saw it: the request as the app sent it and the response as
     * the site sent it. The fixture file holds the decoded body, as always (readable, and what WebView
     * replays and older tools read); an encoded body also goes to `<fixture>.raw` byte for byte.
     */
    fun saveWire(fixture: File, request: Request, r: FixtureServer.Reply) {
        val encodings = r.headers.filter { it.first.equals("Content-Encoding", ignoreCase = true) }
            .flatMap { it.second.split(',') }.map { it.trim().lowercase() }.filter { it.isNotEmpty() && it != "identity" }
        val headers = Headers.Builder().apply { r.headers.forEach { (k, v) -> addUnsafeNonAscii(k, v) } }.build()
        // The secrets this exchange carries, so a login body that echoes its own session cookie is scrubbed now.
        request.headers.forEach { (k, v) -> Secrets.learnHeader(k, v) }
        headers.forEach { (k, v) -> Secrets.learnHeader(k, v) }
        var decoded = if (encodings.isEmpty()) r.body else decode(r.body, encodings)
        // A body holding a secret is kept decoded and scrubbed, without the encoded copy.
        val scrubbed = decoded?.let(Secrets::scrubBody)
        if (scrubbed != null) decoded = scrubbed
        val keepRaw = encodings.isNotEmpty() && scrubbed == null
        fixture.parentFile.mkdirs()
        fixture.writeBytes(decoded ?: r.body)
        val raw = File(fixture.path + ".raw")
        if (keepRaw) raw.writeBytes(r.body) else raw.delete()
        Secrets.recorded(fixture)
        metaFile(fixture).writeText(
            buildJsonObject {
                put("code", r.code)
                if (r.message.isNotEmpty()) put("message", r.message)
                put("url", Secrets.scrub(request.url.toString()))
                headers["Content-Type"]?.let { put("contentType", it) }
                put("headers", headersJson(headers))
                put("wire", true)
                if (keepRaw) put("raw", true)
                if (encodings.isNotEmpty() && decoded == null) put("undecoded", encodings.joinToString(","))
                put(
                    "request",
                    buildJsonObject {
                        put("method", request.method)
                        put("headers", headersJson(request.headers))
                    },
                )
            }.toString(),
        )
    }

    /** [body] with its Content-Encodings undone (last applied first), or null for one this can't decode. */
    private fun decode(body: ByteArray, encodings: List<String>): ByteArray? = try {
        encodings.asReversed().fold(body) { b, enc ->
            val algorithm: okhttp3.CompressionInterceptor.DecompressionAlgorithm? = when (enc) {
                "gzip", "x-gzip" -> okhttp3.Gzip
                "br" -> okhttp3.brotli.Brotli
                "zstd" -> okhttp3.zstd.Zstd
                else -> null
            }
            when {
                algorithm != null -> algorithm.decompress(Buffer().write(b)).buffer().use { it.readByteArray() }
                enc == "deflate" -> inflate(b)
                else -> return null
            }
        }
    } catch (e: Exception) {
        null
    }

    /** HTTP "deflate" is zlib-wrapped; some servers send raw deflate. */
    private fun inflate(b: ByteArray): ByteArray = try {
        java.util.zip.InflaterInputStream(b.inputStream()).readBytes()
    } catch (_: java.util.zip.ZipException) {
        java.util.zip.InflaterInputStream(b.inputStream(), java.util.zip.Inflater(true)).readBytes()
    }

    // ---- WebView traffic (the sidecar browser fetches itself; these replay, record and count it) ----

    val webviewCount = AtomicInteger()
    val webviewKeys = java.util.concurrent.ConcurrentLinkedQueue<String>()

    private fun webviewRequest(url: String, method: String, headers: Map<String, String>, postData: ByteArray?): Request =
        Request.Builder().url(url).apply {
            // Only what fixture names depend on (Content-Type: a multipart body's boundary).
            headers.entries.firstOrNull { it.key.equals("RSC", ignoreCase = true) }?.let { header("RSC", it.value) }
            headers.entries.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }?.let { header("Content-Type", it.value) }
            method(method, if (method == "GET" || method == "HEAD") null else (postData ?: ByteArray(0)).toRequestBody())
        }.build()

    /** A --map file or fixture for a WebView request, as the sidecar's fulfil JSON, or null to go live. */
    fun serveWebView(url: String, method: String, headers: Map<String, String>, postData: ByteArray?): JsonObject? {
        val request = try {
            webviewRequest(url, method, headers, postData)
        } catch (_: IllegalArgumentException) {
            return null
        }
        val mapped = Harness.urlMap[mapKey(request.url)]
        val fixture = Harness.fixturesDir?.let { findFixture(it, request) }
        // The browser gets a decoded body: a raw HTTP --map file is decoded here.
        mapped?.let { entry -> parseHttpFile(entry.file.readBytes()) }?.let { r ->
            val enc = r.headers.filter { it.first.equals("Content-Encoding", ignoreCase = true) }.flatMap { it.second.split(',') }
                .map { it.trim().lowercase() }.filter { it.isNotEmpty() && it != "identity" }
            val body = if (enc.isEmpty()) r.body else decode(r.body, enc) ?: r.body
            return buildJsonObject {
                put("status", mapped.code ?: r.code)
                put("contentType", r.headers.lastOrNull { it.first.equals("Content-Type", ignoreCase = true) }?.second ?: sniff(body))
                put("body", java.util.Base64.getEncoder().encodeToString(body))
                put("via", "map")
            }
        }
        val (file, via) = when {
            mapped != null -> mapped.file to "map"
            fixture != null && fixture.exists() && !Harness.refresh -> fixture to "fixture"
            else -> return null
        }
        val s = readStored(file)
        return buildJsonObject {
            put("status", mapped?.code ?: s.code)
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

    /**
     * A WebView recording: the decoded body, and a .meta with status, final URL and content type (the
     * sidecar reports nothing else). OkHttp traffic is recorded by [saveWire].
     */
    private fun store(
        fixture: File,
        bytes: ByteArray,
        code: Int,
        url: String,
        contentType: String?,
        request: Request? = null,
        responseHeaders: Headers? = null,
    ) {
        fixture.parentFile.mkdirs()
        fixture.writeBytes(Secrets.scrubBody(bytes) ?: bytes)
        File(fixture.path + ".raw").delete() // a WebView recording replaces a wire one
        Secrets.recorded(fixture)
        metaFile(fixture).writeText(
            buildJsonObject {
                put("code", code)
                put("url", Secrets.scrub(url))
                contentType?.let { put("contentType", it) }
                responseHeaders?.let { put("headers", headersJson(it)) }
                request?.let {
                    put(
                        "request",
                        buildJsonObject {
                            put("method", it.method)
                            put("headers", headersJson(it.headers))
                        },
                    )
                }
            }.toString(),
        )
    }
}
