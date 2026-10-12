package keiyoushi.extrun

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/**
 * The one piece of harness-only logic around the ported CloudflareInterceptor (the solver's single
 * intended deviation from Mihon). Mihon's WebView solves with the request's User-Agent, so the
 * cf_clearance it earns already matches the UA the retry sends. ext-run's WebView keeps desktop
 * Chrome's own UA (desktop Chrome claiming Android fails the managed check), so the clearance is
 * bound to that UA, and every request to the cookie's domain has to send it: [withUserAgent].
 *
 * Clearances and their UA are also kept in $EXT_RUN_CF_DIR/clearances.json across runs and loaded
 * into the cookie jar at start, as the app keeps its cookies (only these are kept, though).
 */
object CfClearances {
    private const val COOKIE = "cf_clearance"
    private val dir = System.getenv("EXT_RUN_CF_DIR")?.takeIf { it.isNotEmpty() }?.let(::File)

    private class Earned(val value: String, val userAgent: String)

    /** cookie domain -> the clearance our WebView earned there and the UA it was earned with. */
    private val earned = ConcurrentHashMap<String, Earned>()

    @Volatile private var loaded = false

    /** [request] with the solving browser's UA, if it will carry a cf_clearance our WebView earned. */
    fun withUserAgent(request: Request): Request {
        val ua = userAgentFor(request.url) ?: return request
        return if (request.header("User-Agent") == ua) request else request.newBuilder().header("User-Agent", ua).build()
    }

    fun userAgentFor(url: HttpUrl): String? {
        ensureLoaded()
        return HarnessCookieJar.loadForRequest(url).asSequence()
            .filter { it.name == COOKIE }
            .mapNotNull { c -> earned[c.domain]?.takeIf { it.value == c.value }?.userAgent }
            .firstOrNull()
    }

    /** Called right after the WebView earned a new cf_clearance for [url]. */
    fun solved(url: HttpUrl) {
        ensureLoaded()
        val ua = WebViewBridge.defaultUserAgent() // the WebView's UA: it was never overridden
        HarnessCookieJar.loadForRequest(url).filter { it.name == COOKIE }.forEach { c ->
            earned[c.domain] = Earned(c.value, ua)
            persist(c, ua)
        }
    }

    // ---- persistence: {"<cookie domain>": {"ua", "value", "hostOnly", "path", "expiresAt", "secure", "httpOnly", "solvedAt"}} ----

    /** Loads stored clearances into the cookie jar (live and --direct runs only). */
    fun load() = ensureLoaded()

    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        if (!Harness.live && !Harness.direct) return
        val now = System.currentTimeMillis()
        read().forEach { (domain, e) ->
            val o = e.jsonObject
            val expires = o["expiresAt"]?.jsonPrimitive?.long ?: return@forEach
            if (expires <= now) return@forEach
            val cookie = try {
                Cookie.Builder().name(COOKIE).value(o["value"]!!.jsonPrimitive.content).path(o["path"]?.jsonPrimitive?.content ?: "/")
                    .apply {
                        if (o["hostOnly"]?.jsonPrimitive?.boolean == true) hostOnlyDomain(domain) else domain(domain)
                        expiresAt(expires)
                        if (o["secure"]?.jsonPrimitive?.boolean == true) secure()
                        if (o["httpOnly"]?.jsonPrimitive?.boolean == true) httpOnly()
                    }.build()
            } catch (_: Exception) {
                return@forEach
            }
            HarnessCookieJar.put(cookie)
            earned[domain] = Earned(cookie.value, o["ua"]!!.jsonPrimitive.content)
        }
    }

    private fun persist(c: Cookie, ua: String) = update {
        it[c.domain] = buildJsonObject {
            put("ua", ua)
            put("value", c.value)
            put("hostOnly", c.hostOnly)
            put("path", c.path)
            put("expiresAt", c.expiresAt)
            put("secure", c.secure)
            put("httpOnly", c.httpOnly)
            put("solvedAt", System.currentTimeMillis())
        }
    }

    private fun file() = dir?.resolve("clearances.json")

    private fun read(): Map<String, JsonElement> = try {
        file()?.takeIf { it.exists() }?.let { Json.parseToJsonElement(it.readText()).jsonObject.toMap() }.orEmpty()
    } catch (_: Exception) {
        emptyMap()
    }

    /** Read-modify-write under a file lock (concurrent runs share the file). */
    private fun update(change: (MutableMap<String, JsonElement>) -> Unit) {
        val f = file() ?: return
        try {
            f.parentFile.mkdirs()
            RandomAccessFile(f.resolveSibling(".clearances.lock"), "rw").use { raf ->
                raf.channel.lock().use {
                    val m = read().toMutableMap()
                    change(m)
                    val tmp = f.resolveSibling(".clearances.json.tmp")
                    tmp.writeText(JsonObject(m).toString())
                    Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                }
            }
        } catch (e: Exception) {
            System.err.println("cloudflare: couldn't save clearances to $f: $e")
        }
    }
}
