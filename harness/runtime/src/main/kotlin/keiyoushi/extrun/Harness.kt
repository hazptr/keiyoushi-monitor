package keiyoushi.extrun

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/** Global harness settings, filled from the command line before the source is created. */
object Harness {
    var fixturesDir: File? = null

    /**
     * The WebView sidecar ticks Turnstile checkboxes as the app's user would (`EXT_RUN_TICK=0`: never, as in a
     * background WebView nobody looks at). [turnstileTicks] counts this run's ticks, so a pass that needed one says so.
     */
    val tick = System.getenv("EXT_RUN_TICK") != "0"
    val turnstileTicks = java.util.concurrent.atomic.AtomicInteger()

    /** No network at all: a request without a fixture (or --map file) fails, and so does a WebView's. */
    var offline = false
    var refresh = false
    var verbose = false
    /** --map: canonical URL ([Transport.mapKey]) -> the file that answers it. */
    val urlMap = mutableMapOf<String, MapEntry>()

    /** --fixture-ignore-param: query parameters fixture names leave out (besides timestamp cache busters). */
    val ignoreParams = mutableSetOf<String>()
    var dataDir = File(System.getenv("EXT_RUN_DATA") ?: "/tmp/ext-run")
    val prefOverrides = mutableMapOf<String, String>()

    /** Is the fixture server under the app's OkHttp stack (NetworkHelper, FixtureServer). */
    val fixtureServer: Boolean get() = fixturesDir != null || offline || urlMap.isNotEmpty()

    /** Engine name passed to the WebView sidecar (nodriver only knows chrome). */
    const val BROWSER = "chrome"

    /** The app's default User-Agent (NetworkPreferences.defaultUserAgent), what OkHttp sends unless a source sets its own. */
    val userAgent: String get() = eu.kanade.tachiyomi.network.NetworkPreferences.DEFAULT_USER_AGENT
}

class HarnessApplication : Application() {
    init {
        attachBaseContext(HarnessContext(this))
    }
}

/** The app's ContextImpl: the base context of the Application and its Activity. */
class HarnessContext(private val app: Application) : Context() {
    private val prefs = ConcurrentHashMap<String, HarnessPreferences>()

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        prefs.getOrPut(name) { HarnessPreferences(Harness.prefOverrides) }

    override fun getCacheDir(): File = Harness.dataDir.resolve("cache").apply { mkdirs() }
    override fun getFilesDir(): File = Harness.dataDir.resolve("files").apply { mkdirs() }
    override fun getPackageName(): String = "app.ext.run"
    override fun getApplicationContext(): Context = app
}

/** In-memory SharedPreferences. `--pref key=value` seeds every preferences file; typed getters parse strings. */
class HarnessPreferences(seed: Map<String, String>) : SharedPreferences {
    private val values = ConcurrentHashMap<String, Any>(seed)
    private val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()

    override fun getAll(): Map<String, *> = HashMap(values)
    override fun getString(key: String, defValue: String?): String? = values[key]?.toString() ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? = when (val v = values[key]) {
        null -> defValues
        is Set<*> -> v as Set<String>
        else -> v.toString().split(',').filter { it.isNotEmpty() }.toSet()
    }

    override fun getInt(key: String, defValue: Int): Int = values[key]?.toString()?.toIntOrNull() ?: defValue
    override fun getLong(key: String, defValue: Long): Long = values[key]?.toString()?.toLongOrNull() ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = values[key]?.toString()?.toFloatOrNull() ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key]?.toString()?.toBooleanStrictOrNull() ?: defValue
    override fun contains(key: String): Boolean = values.containsKey(key)

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        listeners += listener
    }

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        listeners -= listener
    }

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private var clear = false
        override fun putString(key: String, value: String?) = apply { pending[key] = value }
        override fun putStringSet(key: String, values: Set<String>?) = apply { pending[key] = values?.toSet() }
        override fun putInt(key: String, value: Int) = apply { pending[key] = value }
        override fun putLong(key: String, value: Long) = apply { pending[key] = value }
        override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
        override fun remove(key: String) = apply { pending[key] = null }
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean {
            if (clear) values.clear()
            pending.forEach { (k, v) -> if (v == null) values.remove(k) else values[k] = v }
            pending.keys.forEach { k -> listeners.forEach { it.onSharedPreferenceChanged(this@HarnessPreferences, k) } }
            return true
        }
        override fun apply() {
            commit()
        }
    }
}

/**
 * The cookie store behind android.webkit.CookieManager (and so behind the app's AndroidCookieJar).
 * Like Chromium's store, a cookie is keyed by name + domain (with host-only distinct from a domain
 * cookie) + path, and setting an already-expired cookie deletes the one with the same key.
 * Process lifetime by default: ext-run starts empty each run, except Cloudflare clearances (see
 * CfClearances). With EXT_RUN_COOKIES=<file> the jar is kept across runs, as the app keeps its
 * cookies: [load] at start, [save] at exit (see there).
 */
object HarnessCookieJar : CookieJar {
    private val store = ConcurrentHashMap<String, Cookie>()

    /** Keys this run deleted, so [save] doesn't bring them back from the file. */
    private val deleted = ConcurrentHashMap.newKeySet<String>()
    private val file = System.getenv("EXT_RUN_COOKIES")?.takeIf { it.isNotEmpty() }?.let(::File)

    private fun key(c: Cookie) = "${if (c.hostOnly) "" else "."}${c.domain}|${c.path}|${c.name}"

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val now = System.currentTimeMillis()
        cookies.forEach {
            if (it.expiresAt <= now) {
                store.remove(key(it))
                deleted += key(it)
            } else {
                store[key(it)] = it
                deleted -= key(it)
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        return store.values.filter { it.expiresAt > now && it.matches(url) }
    }

    fun all(): List<Cookie> = store.values.toList()

    fun put(cookie: Cookie) {
        store[key(cookie)] = cookie
        deleted -= key(cookie)
    }

    fun remove(predicate: (Cookie) -> Boolean) {
        store.values.filter(predicate).forEach { deleted += key(it) }
        store.values.removeIf(predicate)
    }

    fun clear(sessionOnly: Boolean = false) {
        remove { !sessionOnly || !it.persistent }
    }

    // ---- kept across runs (EXT_RUN_COOKIES) ----

    /** Loads the kept jar, if any. Session cookies are kept too: the app keeps them while it runs, and a
     * run here stands for one use of the app, not a restart. */
    fun load() {
        val f = file ?: return
        if (!f.exists()) return
        val now = System.currentTimeMillis()
        val loaded = read(f).filter { it.expiresAt > now }
        loaded.forEach { store.putIfAbsent(key(it), it) }
        System.err.println("ext-run: ${loaded.size} cookie(s) from ${f.name} (kept across runs)")
    }

    /** Writes the jar back: the file's cookies, minus what this run deleted, plus this run's (newer)
     * ones. Under a lock, so parallel runs on one file merge instead of overwriting each other. */
    fun save() {
        val f = file ?: return
        try {
            f.parentFile?.mkdirs()
            RandomAccessFile(File(f.path + ".lock"), "rw").channel.use { ch ->
                ch.lock().use {
                    val now = System.currentTimeMillis()
                    val merged = LinkedHashMap<String, Cookie>()
                    if (f.exists()) read(f).forEach { merged[key(it)] = it }
                    deleted.forEach { merged.remove(it) }
                    store.values.forEach { merged[key(it)] = it }
                    val out = buildJsonArray { merged.values.filter { it.expiresAt > now }.forEach { add(toJson(it)) } }
                    val tmp = File(f.path + ".tmp")
                    tmp.writeText(out.toString())
                    Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                }
            }
        } catch (e: Exception) {
            System.err.println("ext-run: couldn't keep cookies in $f: $e")
        }
    }

    private fun toJson(c: Cookie) = buildJsonObject {
        put("name", c.name)
        put("value", c.value)
        put("domain", c.domain)
        put("hostOnly", c.hostOnly)
        put("path", c.path)
        if (c.persistent) put("expiresAt", c.expiresAt)
        put("secure", c.secure)
        put("httpOnly", c.httpOnly)
    }

    private fun read(f: File): List<Cookie> = try {
        Json.parseToJsonElement(f.readText()).jsonArray.mapNotNull { e ->
            val o = e.jsonObject
            fun str(k: String) = o[k]?.jsonPrimitive?.contentOrNull
            fun flag(k: String) = o[k]?.jsonPrimitive?.booleanOrNull == true
            runCatching {
                Cookie.Builder().name(str("name")!!).value(str("value").orEmpty()).path(str("path") ?: "/").apply {
                    if (flag("hostOnly")) hostOnlyDomain(str("domain")!!) else domain(str("domain")!!)
                    o["expiresAt"]?.jsonPrimitive?.longOrNull?.let(::expiresAt)
                    if (flag("secure")) secure()
                    if (flag("httpOnly")) httpOnly()
                }.build()
            }.getOrNull()
        }
    } catch (e: Exception) {
        System.err.println("ext-run: ignoring unreadable cookie file $f: $e")
        emptyList()
    }
}

/**
 * One --map answer: [file] is a body (with an optional `<file>.meta` as fixtures have) or a whole raw HTTP
 * response (`HTTP/1.1 403 Forbidden`, headers, blank line, body bytes); [code] overrides its status.
 */
class MapEntry(val file: File, val code: Int?)
