package keiyoushi.extrun

import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.SequenceInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory
import javax.net.ssl.ExtendedSSLSession
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The socket under the app's OkHttp stack when a run has --fixtures, --offline or --map. NetworkHelper's
 * base client (which every source client is built from) connects every route to this loopback server and
 * trusts its certificates, so a request goes through the whole app chain (application interceptors,
 * BridgeInterceptor with cookies and gzip, the cache, network interceptors) and the server sees exactly
 * what the app puts on the wire. It answers, in order: --map, a fixture, or (unless --offline) the site
 * itself through a plain OkHttp client, recording the exchange as a fixture. The answer goes back as the
 * site sent it (still compressed, its own headers), so a replay feeds the app chain the same bytes.
 *
 * HTTP/1.1 only (no ALPN on the TLS side, so OkHttp falls back from h2). Per-host leaf certificates
 * from a per-run CA, picked by SNI, so OkHttp's own hostname check passes unchanged.
 */
object FixtureServer {
    /** What the server did for a request, read back by [Transport.proceed] for the log line. */
    class Exchange(val via: String, val mismatch: String? = null, val missPath: String? = null)

    private val exchanges = ConcurrentHashMap<String, Exchange>()

    fun exchange(method: String, url: HttpUrl): Exchange? = exchanges[key(method, url.toString())]

    /** Upstream failures by request, so [Transport.proceed] can throw what the app would have got. */
    private val failures = ConcurrentHashMap<String, IOException>()

    fun takeFailure(method: String, url: HttpUrl): IOException? = failures.remove(key(method, url.toString()))

    private fun key(method: String, url: String) = "$method $url"

    val replayed = AtomicInteger()
    val recorded = AtomicInteger()
    val mapped = AtomicInteger()
    val upstream = AtomicInteger()
    val misses = AtomicInteger()

    /** Old fixtures (curl followed redirects): final URL -> the fixture that holds its body. */
    private val aliases = ConcurrentHashMap<String, File>()

    @Volatile var started = false
        private set

    fun summary(): String =
        "-- fixture server: ${replayed.get()} replayed, ${recorded.get()} recorded, ${mapped.get()} from --map, " +
            "${upstream.get()} sent upstream, ${misses.get()} offline miss(es)"

    // ---- TLS: a CA for this run, one leaf per SNI host ----

    private val ca: HeldCertificate by lazy {
        HeldCertificate.Builder().certificateAuthority(0).commonName("ext-run fixture server").build()
    }
    private val leaves = ConcurrentHashMap<String, HeldCertificate>()

    private fun leaf(host: String): HeldCertificate = leaves.getOrPut(host) {
        HeldCertificate.Builder().commonName(host).addSubjectAlternativeName(host).signedBy(ca).build()
    }

    private val keyManager = object : X509ExtendedKeyManager() {
        private fun sni(session: javax.net.ssl.SSLSession?): String =
            (session as? ExtendedSSLSession)?.requestedServerNames?.filterIsInstance<SNIHostName>()?.firstOrNull()?.asciiName
                ?: "localhost"

        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? =
            if (keyType == "EC") sni((socket as? SSLSocket)?.handshakeSession) else null

        override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?): String? =
            if (keyType == "EC") sni(engine?.handshakeSession) else null

        override fun getCertificateChain(alias: String): Array<X509Certificate> = arrayOf(leaf(alias).certificate, ca.certificate)
        override fun getPrivateKey(alias: String): PrivateKey = leaf(alias).keyPair.private
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? = null
    }

    private val serverTls: SSLContext by lazy { SSLContext.getInstance("TLS").apply { init(arrayOf(keyManager), null, null) } }
    private val clientTls: HandshakeCertificates by lazy { HandshakeCertificates.Builder().addTrustedCertificate(ca.certificate).build() }

    // ---- the server ----

    private val pool = Executors.newCachedThreadPool { Thread(it, "ext-run-fixture-server").apply { isDaemon = true } }

    private val server: ServerSocket by lazy {
        ServerSocket(0, 128, InetAddress.getLoopbackAddress()).also { s ->
            started = true
            thread(isDaemon = true, name = "ext-run-fixture-accept") {
                while (true) {
                    val c = try {
                        s.accept()
                    } catch (_: IOException) {
                        break
                    }
                    pool.execute { handle(c) }
                }
            }
        }
    }

    /** Puts the server under [builder]: every connection goes to it, whatever host or address OkHttp resolved. */
    fun install(builder: OkHttpClient.Builder) {
        val target = InetSocketAddress(InetAddress.getLoopbackAddress(), server.localPort)
        builder
            .proxy(Proxy.NO_PROXY)
            // No lookup: the socket factory ignores the address anyway (TEST-NET-1 placeholder).
            .dns(Dns { host -> listOf(InetAddress.getByAddress(host, byteArrayOf(192.toByte(), 0, 2, 1))) })
            .socketFactory(LoopbackSocketFactory(target))
            .sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
    }

    private class LoopbackSocketFactory(private val target: SocketAddress) : SocketFactory() {
        override fun createSocket(): Socket = object : Socket() {
            override fun connect(endpoint: SocketAddress?, timeout: Int) = super.connect(target, timeout)
        }
        override fun createSocket(host: String?, port: Int): Socket = createSocket().apply { connect(target) }
        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket = createSocket(host, port)
        override fun createSocket(host: InetAddress?, port: Int): Socket = createSocket().apply { connect(target) }
        override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket = createSocket(address, port)
    }

    private class Wire(
        val method: String,
        val url: HttpUrl,
        val headers: List<Pair<String, String>>,
        val body: ByteArray?,
        val close: Boolean,
    ) {
        /** As an OkHttp request, for fixture names and the recorded request headers. */
        fun toRequest(): Request = Request.Builder().url(url)
            .headers(Headers.Builder().apply { headers.forEach { (k, v) -> addUnsafeNonAscii(k, v) } }.build())
            .method(method, requestBody(method, body))
            .build()
    }

    /** A WebSocket (or other protocol) upgrade: the connection becomes the site's own after the 101. */
    private fun Wire.isUpgrade() = headers.any { it.first.equals("Upgrade", ignoreCase = true) } &&
        headers.any { it.first.equals("Connection", ignoreCase = true) && it.second.contains("upgrade", ignoreCase = true) }

    /**
     * An upgrade request goes to the site as sent, and from then on bytes pass both ways untouched: neither replayed
     * nor recorded (a frame stream isn't a fixture), so the source's WebSocket works in a recording run as in the
     * app. Offline it fails like a miss (a 504 the app's WebSocket reports as a failed handshake).
     */
    private fun tunnel(w: Wire, ins: InputStream, out: OutputStream) {
        val label = "${w.method} ${w.url}"
        if (Harness.offline) {
            misses.incrementAndGet()
            note(w, Exchange("miss", missPath = "(a WebSocket upgrade: --offline has no network, and frames aren't recorded)"))
            write(out, w, Reply(504, "", listOf("Content-Type" to "text/plain"), "ext-run --offline: no network for a WebSocket upgrade\n".toByteArray()))
            out.flush()
            return
        }
        if (refusePlaceholder(w)) return
        upstream.incrementAndGet()
        note(w, Exchange("ws"))
        val port = w.url.port
        val up: Socket = try {
            if (w.url.isHttps) {
                (javax.net.ssl.SSLSocketFactory.getDefault().createSocket(w.url.host, port) as SSLSocket).apply {
                    sslParameters = sslParameters.apply { applicationProtocols = arrayOf("http/1.1") }
                    startHandshake()
                }
            } else {
                Socket(w.url.host, port)
            }
        } catch (e: IOException) {
            System.err.println("  ! upstream $label failed: ${e::class.simpleName}")
            failures[key(w.method, w.url.toString())] = e
            return
        }
        up.use {
            val head = StringBuilder("${w.method} ${w.url.encodedPath}${w.url.encodedQuery?.let { "?$it" } ?: ""} HTTP/1.1\r\n")
            w.headers.forEach { (k, v) -> head.append(k).append(": ").append(v).append("\r\n") }
            head.append("\r\n")
            val upOut = up.getOutputStream()
            upOut.write(head.toString().toByteArray(Charsets.UTF_8))
            w.body?.let { upOut.write(it) }
            upOut.flush()
            val down = thread(isDaemon = true, name = "ext-run-fixture-ws") {
                try {
                    up.getInputStream().copyTo(out.let { o -> FlushingStream(o) })
                } catch (_: IOException) {
                }
                runCatching { out.flush() }
            }
            try {
                ins.copyTo(FlushingStream(upOut))
            } catch (_: IOException) {
            }
            runCatching { up.shutdownOutput() }
            down.join(5_000)
        }
    }

    /** Flushes after every write, so frames go through at once. */
    private class FlushingStream(private val o: OutputStream) : OutputStream() {
        override fun write(b: Int) { o.write(b); o.flush() }
        override fun write(b: ByteArray, off: Int, len: Int) { o.write(b, off, len); o.flush() }
    }

    /** A response as it goes on the wire: status, headers in order, body bytes as sent (still encoded). */
    class Reply(val code: Int, val message: String, val headers: List<Pair<String, String>>, val body: ByteArray)

    private val bodyMethods = setOf("POST", "PUT", "PATCH", "PROPPATCH", "REPORT")

    /** OkHttp's rules: GET/HEAD never have a body (even an empty one), POST and friends always do. */
    private fun requestBody(method: String, body: ByteArray?) = when {
        method == "GET" || method == "HEAD" -> null
        body != null -> body.toRequestBody()
        method in bodyMethods -> ByteArray(0).toRequestBody()
        else -> null
    }

    private fun handle(raw: Socket) {
        try {
            raw.use {
                raw.soTimeout = 180_000
                val rin = raw.getInputStream()
                val first = rin.read()
                if (first < 0) return
                val tls = first == 0x16 // TLS handshake record
                val sock: Socket
                val ins: InputStream
                var sni: String? = null
                if (tls) {
                    val ssl = serverTls.socketFactory.createSocket(raw, ByteArrayInputStream(byteArrayOf(first.toByte())), true) as SSLSocket
                    ssl.startHandshake()
                    sni = (ssl.session as? ExtendedSSLSession)?.requestedServerNames?.filterIsInstance<SNIHostName>()?.firstOrNull()?.asciiName
                    sock = ssl
                    ins = BufferedInputStream(ssl.inputStream)
                } else {
                    sock = raw
                    ins = BufferedInputStream(SequenceInputStream(ByteArrayInputStream(byteArrayOf(first.toByte())), rin))
                }
                sock.use {
                    val out = BufferedOutputStream(sock.getOutputStream())
                    while (true) {
                        val w = readRequest(ins, out, tls, sni) ?: break
                        if (w.isUpgrade()) {
                            raw.soTimeout = 0 // a WebSocket may sit idle for long
                            tunnel(w, ins, out)
                            break
                        }
                        val reply = respond(w) ?: break // null: upstream failed; closing makes the app's call fail
                        write(out, w, reply)
                        out.flush()
                        if (w.close) break
                    }
                }
            }
        } catch (e: IOException) {
            if (Harness.verbose) System.err.println("fixture server: connection ended: $e")
        }
    }

    private fun readLine(ins: InputStream): String? {
        val b = ByteArrayOutputStream()
        while (true) {
            val c = ins.read()
            if (c < 0) return if (b.size() == 0) null else b.toString(Charsets.UTF_8)
            if (c == '\n'.code) break
            b.write(c)
        }
        return b.toString(Charsets.UTF_8).removeSuffix("\r")
    }

    private fun readRequest(ins: InputStream, out: OutputStream, tls: Boolean, sni: String?): Wire? {
        var line = readLine(ins) ?: return null
        while (line.isEmpty()) line = readLine(ins) ?: return null
        val parts = line.split(' ')
        if (parts.size < 3) throw IOException("bad request line")
        val (method, target) = parts
        val headers = mutableListOf<Pair<String, String>>()
        while (true) {
            val h = readLine(ins) ?: throw IOException("eof in headers")
            if (h.isEmpty()) break
            val i = h.indexOf(':')
            if (i > 0) headers += h.substring(0, i).trim() to h.substring(i + 1).trim()
        }
        fun header(name: String) = headers.lastOrNull { it.first.equals(name, ignoreCase = true) }?.second
        if (header("Expect")?.equals("100-continue", ignoreCase = true) == true) {
            out.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray())
            out.flush()
        }
        val body: ByteArray? = when {
            header("Transfer-Encoding")?.contains("chunked", ignoreCase = true) == true -> readChunked(ins)
            header("Content-Length") != null -> readN(ins, header("Content-Length")!!.toInt())
            else -> null
        }
        val url = if (target.startsWith("http://") || target.startsWith("https://")) {
            target.toHttpUrl()
        } else {
            val host = header("Host") ?: sni ?: throw IOException("no Host")
            "${if (tls) "https" else "http"}://$host$target".toHttpUrl()
        }
        val close = header("Connection")?.equals("close", ignoreCase = true) == true
        return Wire(method, url, headers, body, close)
    }

    private fun readN(ins: InputStream, n: Int): ByteArray {
        val b = ins.readNBytes(n)
        if (b.size < n) throw IOException("eof in body")
        return b
    }

    private fun readChunked(ins: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val size = (readLine(ins) ?: throw IOException("eof in chunk")).substringBefore(';').trim().toInt(16)
            if (size == 0) {
                while ((readLine(ins) ?: "").isNotEmpty()) Unit // trailers
                return out.toByteArray()
            }
            out.write(readN(ins, size))
            readLine(ins)
        }
    }

    private val framingHeaders = setOf("content-length", "transfer-encoding", "connection", "keep-alive")

    private fun write(out: OutputStream, w: Wire, r: Reply) {
        val sb = StringBuilder("HTTP/1.1 ${r.code}")
        if (r.message.isNotEmpty()) sb.append(' ').append(r.message)
        sb.append("\r\n")
        val noBody = w.method == "HEAD" || r.code in 100..199 || r.code == 204 || r.code == 304
        r.headers.forEach { (k, v) ->
            // A bodiless response keeps its own Content-Length (HEAD's is the GET's length).
            if (k.lowercase() !in framingHeaders || (noBody && k.equals("content-length", ignoreCase = true))) sb.append(k).append(": ").append(v).append("\r\n")
        }
        if (!noBody) sb.append("Content-Length: ").append(r.body.size).append("\r\n")
        if (w.close) sb.append("Connection: close\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.UTF_8))
        if (!noBody) out.write(r.body)
    }

    // ---- what to answer ----

    private fun note(w: Wire, e: Exchange) {
        exchanges[key(w.method, w.url.toString())] = e
    }

    private fun respond(w: Wire): Reply? {
        val request = w.toRequest()
        val urlString = w.url.toString()
        if (Harness.verbose) {
            System.err.println("fixture server: ${w.method} $urlString <- " + w.headers.joinToString(" | ") { (k, v) -> "$k: ${Transport.redact(k, v)}" })
        }
        Harness.urlMap[Transport.mapKey(w.url)]?.let { entry ->
            mapped.incrementAndGet()
            note(w, Exchange("map:${entry.file.name}"))
            return Transport.replayMap(entry, w.method, w.url)
        }
        val fixture = Harness.fixturesDir?.let { occurrence(Transport.findFixture(it, request)) }
        // The hop after a legacy redirect gets the body of the fixture that redirected (once), as the old
        // replay served it, even when the final URL has a fixture of its own.
        val existing = aliases.remove(urlString)
            ?: fixture?.takeIf { it.exists() && !Harness.refresh }
                // Recording: a fixture holding a Cloudflare challenge is fetched again, so the retry after the
                // WebView solved it records the page instead of replaying the challenge.
                ?.takeUnless { !Harness.offline && Transport.isChallenge(it) }
                // With the network, a body that carries a placeholder (a scrubbed token or cookie value the source
                // reads and sends back) is fetched again: replayed, the placeholder would go to the site.
                ?.takeUnless { !Harness.offline && Secrets.carriesRedaction(String(it.readBytes(), Charsets.ISO_8859_1)) }
            // Offline, a repeat beyond what was recorded gets the last recording (fixtures from before
            // repeats were recorded hold one response per request).
            ?: fixture?.takeIf { Harness.offline }?.let(::lastRecorded)
        if (existing != null) {
            replayed.incrementAndGet()
            val reply = Transport.replay(existing, w.method, w.url, legacyRedirect = true)
            reply.headers.firstOrNull { it.first == Transport.ALIAS_HEADER }?.let { (_, finalUrl) -> aliases[finalUrl] = existing }
            note(w, Exchange("fixture", Transport.requestMismatch(existing, request)))
            return Reply(reply.code, reply.message, reply.headers.filter { it.first != Transport.ALIAS_HEADER }, reply.body)
        }
        if (Harness.offline) {
            misses.incrementAndGet()
            val path = fixture?.path ?: "(no --fixtures dir)"
            note(w, Exchange("miss", missPath = path))
            return Reply(504, "", listOf("Content-Type" to "text/plain"), "ext-run --offline: no fixture $path\n".toByteArray())
        }
        val reply = forward(w) ?: return null
        if (fixture != null) {
            Transport.saveWire(fixture, request, reply)
            recorded.incrementAndGet()
            note(w, Exchange("rec"))
        } else {
            note(w, Exchange("app"))
        }
        return reply
    }

    // ---- repeats: the n-th identical request in a run is the n-th recording of its fixture ----

    private val occurrences = ConcurrentHashMap<String, AtomicInteger>()

    private fun nth(base: File, n: Int) = if (n <= 1) base else File("${base.path}#$n")

    /**
     * The fixture for this request's occurrence in the run: the first request for a fixture name uses
     * `<name>`, a repeat `<name>#2`, `<name>#3` ... So when an interceptor sends the same request again
     * (a retry after a bot check set a cookie), a recording run sends the repeat upstream and stores it as
     * the next response, and a replay serves the responses in the order they were recorded. Recording, a
     * repeat after a stored Cloudflare challenge reuses the challenge's slot, so the retry after the
     * WebView solved it replaces the challenge (as it always has).
     */
    private fun occurrence(base: File): File {
        val counter = occurrences.computeIfAbsent(base.path) { AtomicInteger() }
        val n = synchronized(counter) {
            val current = counter.get()
            if (current > 0 && !Harness.offline && Transport.isChallenge(nth(base, current))) current else counter.incrementAndGet()
        }
        return nth(base, n)
    }

    /** The highest recorded occurrence below [fixture] (`<name>#n`), or null when there's none. */
    private fun lastRecorded(fixture: File): File? {
        val base = File(fixture.path.replace(Regex("#\\d+$"), ""))
        val n = fixture.path.substringAfterLast('#', "").toIntOrNull() ?: return null
        return (n - 1 downTo 1).map { nth(base, it) }.firstOrNull { it.exists() }
    }

    // ---- upstream: a plain client that sends what it was given and returns what it got ----

    private val hopByHop = setOf(
        "connection", "keep-alive", "proxy-connection", "te", "trailer", "transfer-encoding", "upgrade", "content-length",
    )

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .cookieJar(CookieJar.NO_COOKIES)
            .connectTimeout(30.seconds)
            .readTimeout(60.seconds)
            .callTimeout(3.minutes)
            .build()
    }

    /** Where a request about to go to the site carries a redacted placeholder, or null. */
    private fun placeholderIn(w: Wire): String? = when {
        Secrets.carriesRedaction(w.url.toString()) -> "its URL"
        else -> w.headers.firstOrNull { (_, v) -> Secrets.carriesRedaction(v) }?.let { "its ${it.first} header" }
            ?: w.body?.takeIf { Secrets.carriesRedaction(String(it, Charsets.ISO_8859_1)) }?.let { "its body" }
    }

    /** Fails the request instead of sending a placeholder to the site (it came from a replayed fixture). */
    private fun refusePlaceholder(w: Wire): Boolean {
        val where = placeholderIn(w) ?: return false
        System.err.println("  ! ext-run: ${w.method} ${w.url} not sent: a redacted fixture placeholder would go to the site in $where")
        failures[key(w.method, w.url.toString())] =
            IOException("ext-run: a redacted fixture placeholder would go to the site in $where (a value the source took from a replayed response; use a fresh --fixtures dir)")
        return true
    }

    private fun forward(w: Wire): Reply? {
        if (refusePlaceholder(w)) return null
        // The request carries the app's Accept-Encoding, so this client's BridgeInterceptor neither adds its own
        // nor decodes: the body comes back as the site sent it. Cookie, User-Agent and Host pass through as given.
        val headers = Headers.Builder().apply {
            w.headers.forEach { (k, v) -> if (k.lowercase() !in hopByHop) addUnsafeNonAscii(k, v) }
        }.build()
        upstream.incrementAndGet()
        return try {
            val req = Request.Builder().url(w.url).headers(headers).method(w.method, requestBody(w.method, w.body)).build()
            client.newCall(req).execute().use { r -> Reply(r.code, r.message, r.headers.toList(), r.body.bytes()) }
        } catch (e: IOException) {
            System.err.println("  ! upstream ${w.method} ${w.url} failed: ${e::class.simpleName}")
            failures[key(w.method, w.url.toString())] = e
            null
        }
    }
}
