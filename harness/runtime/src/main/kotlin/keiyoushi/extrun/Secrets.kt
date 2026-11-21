package keiyoushi.extrun

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * What fixtures never hold in clear: session cookies, auth headers, tokens a source echoes into its own
 * headers (KuroMangas' X-Request-Ctx carries a cookie's value), and those same values wherever else they
 * show up (a login response body, a URL in the .meta).
 *
 * A secret is replaced by a placeholder derived from it (`extrunRedacted` + 16 hex of its SHA-256), the
 * same one everywhere and on every run. So a recording stays consistent with itself: an --offline replay
 * of a login sets the placeholder as the cookie's value, the source sends that placeholder back in its
 * Cookie and token headers, and the recorded request (redacted the same way) still matches.
 *
 * Secrets are learnt as a run sees them (run-wide), and every file recorded in the run is scrubbed again
 * at exit with all of them, so a token that a login body returned and a later header used is gone from
 * the login fixture too. Placeholders are never sent to a site: a run with the network drops replayed
 * headers that carry one (see [Transport.replay]).
 *
 * Fixtures from before 2026-11-17 hold the old fixed "<redacted>" instead; they still replay as before.
 */
object Secrets {
    const val LEGACY = "<redacted>"
    private const val PREFIX = "extrunRedacted"
    private val placeholderRe = Regex("$PREFIX[0-9a-f]{16}")

    /**
     * Shortest value treated as a secret; below [LONG] it must also hold a digit (a session id does, "undefined"
     * or "loggedin" don't), so replacing it wherever it shows up never hits ordinary words.
     */
    private const val MIN = 8
    private const val LONG = 16

    /** Headers whose whole value is a credential (Authorization keeps its scheme). */
    private val secretHeaders = setOf("authorization", "proxy-authorization", "x-auth-token", "x-api-key", "apikey", "x-access-token")

    /** Header names that carry a token: X-Request-Ctx, X-XSRF-Token, X-Session-Id, X-Integrity-Token ... */
    private val secretHeaderName = Regex("""(^|[-_])(token|auth|session|sess|sid|jwt|secret|ctx|csrf|xsrf|signature)([-_]|$)|token$""", RegexOption.IGNORE_CASE)

    /** Cookie names that are session/auth state even when the value is short. */
    private val secretCookieName = Regex("""sess|sid|token|auth|login|jwt|secret|remember|clearance|csrf|xsrf|__cf_bm|_vx|_kn""", RegexOption.IGNORE_CASE)

    /** session/token-like `name=value` pairs inside any other value (site-snapshot's rule). */
    private val secretPair = Regex("""(cf_clearance|__cf_bm|ory_kratos_session|PHPSESSID|session|token)=([^;\s"&]{8,})""", RegexOption.IGNORE_CASE)

    /** secret -> placeholder, longest first when scrubbing. */
    private val known = ConcurrentHashMap<String, String>()

    fun placeholder(secret: String): String =
        PREFIX + MessageDigest.getInstance("SHA-256").digest(secret.toByteArray()).take(8).joinToString("") { "%02x".format(it) }

    fun isPlaceholder(v: String) = placeholderRe.matches(v)

    /** Does [v] carry a redaction (a placeholder or the legacy marker), so it must not go to a site. */
    fun carriesRedaction(v: String) = LEGACY in v || placeholderRe.containsMatchIn(v)

    private fun learn(value: String) {
        val v = value.trim().removeSurrounding("\"")
        if (isPlaceholder(v) || v == LEGACY || '"' in v || '\\' in v) return
        if (v.length >= LONG || (v.length >= MIN && v.any(Char::isDigit))) known.computeIfAbsent(v, ::placeholder)
    }

    // Long cookie values are secrets whatever their name; shorter ones under a session-like name.
    private fun cookieSecret(name: String, value: String): Boolean = value.length >= LONG || secretCookieName.containsMatchIn(name)

    /** Learns the secrets a header holds (before [redact] or a body scrub uses them). */
    fun learnHeader(name: String, value: String) {
        val n = name.lowercase()
        when {
            n == "cookie" -> value.split(';').forEach { pair ->
                val i = pair.indexOf('=')
                if (i > 0) {
                    val v = pair.substring(i + 1).trim()
                    if (cookieSecret(pair.substring(0, i).trim(), v)) learn(v)
                }
            }
            n == "set-cookie" -> {
                val pair = value.substringBefore(';')
                val i = pair.indexOf('=')
                if (i > 0) {
                    val v = pair.substring(i + 1).trim()
                    if (cookieSecret(pair.substring(0, i).trim(), v)) learn(v)
                }
            }
            n in secretHeaders || secretHeaderName.containsMatchIn(n) -> learn(authValue(value))
        }
        secretPair.findAll(value).forEach { learn(it.groupValues[2]) }
    }

    /** "Bearer xyz" -> "xyz"; a value without a scheme as it is. */
    private fun authValue(v: String): String {
        val parts = v.trim().split(' ', limit = 2)
        return if (parts.size == 2 && parts[0].matches(Regex("[A-Za-z][A-Za-z0-9-]*"))) parts[1].trim() else v.trim()
    }

    /** A header value as fixtures keep it. */
    fun redact(name: String, value: String): String {
        learnHeader(name, value)
        return scrub(value)
    }

    /** [text] with every known secret replaced by its placeholder. */
    fun scrub(text: String): String {
        if (known.isEmpty()) return text
        var out = text
        for ((secret, ph) in known.entries.sortedByDescending { it.key.length }) out = out.replace(secret, ph)
        return out
    }

    /** A body with known secrets replaced, or null when it holds none (bytes as ISO-8859-1, so binary passes unchanged). */
    fun scrubBody(body: ByteArray): ByteArray? {
        if (known.isEmpty() || body.isEmpty()) return null
        val s = String(body, Charsets.ISO_8859_1)
        var out = s
        for ((secret, ph) in known.entries.sortedByDescending { it.key.length }) {
            out = out.replace(String(secret.toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1), ph)
        }
        return if (out == s) null else out.toByteArray(Charsets.ISO_8859_1)
    }

    /** A .meta's JSON with its string values scrubbed (the structure untouched). */
    private fun scrubJson(e: kotlinx.serialization.json.JsonElement): kotlinx.serialization.json.JsonElement = when (e) {
        is kotlinx.serialization.json.JsonObject -> kotlinx.serialization.json.JsonObject(e.mapValues { scrubJson(it.value) })
        is kotlinx.serialization.json.JsonArray -> kotlinx.serialization.json.JsonArray(e.map(::scrubJson))
        is kotlinx.serialization.json.JsonPrimitive -> if (e.isString) kotlinx.serialization.json.JsonPrimitive(scrub(e.content)) else e
        else -> e
    }

    // ---- files recorded in this run, scrubbed again with every secret at exit ----

    private val recorded = ConcurrentHashMap.newKeySet<File>()
    private val hook by lazy { Runtime.getRuntime().addShutdownHook(Thread(::scrubRecorded, "ext-run-scrub")) }

    fun recorded(fixture: File) {
        hook
        recorded += fixture
    }

    /** Scrubs the run's recordings with everything learnt since they were written. */
    fun scrubRecorded() {
        recorded.forEach { f ->
            try {
                val meta = File(f.path + ".meta")
                if (meta.exists()) {
                    val t = kotlinx.serialization.json.Json.parseToJsonElement(meta.readText())
                    val s = scrubJson(t)
                    if (s != t) meta.writeText(s.toString())
                }
                if (f.exists()) scrubBody(f.readBytes())?.let { b -> Transport.bodyScrubbed(f, b) }
            } catch (e: Exception) {
                System.err.println("ext-run: couldn't scrub $f: ${e::class.simpleName}")
            }
        }
    }
}
