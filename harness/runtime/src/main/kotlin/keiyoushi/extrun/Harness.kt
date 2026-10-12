package keiyoushi.extrun

import android.app.Application
import android.content.SharedPreferences
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Global harness settings, filled from the command line before the source is created. */
object Harness {
    var fixturesDir: File? = null
    var live = false
    var refresh = false
    var direct = false
    var keepUserAgent = false
    var verbose = false
    val urlMap = mutableMapOf<String, File>()
    var curlRoot = File(System.getenv("EXT_RUN_CURL_ROOT") ?: "/ci/root")
    var dataDir = File(System.getenv("EXT_RUN_DATA") ?: "/tmp/ext-run")
    val prefOverrides = mutableMapOf<String, String>()

    /** Engine name passed to the WebView sidecar: firefox or chromium (playwright backend), chrome (nodriver). */
    var browser = "firefox"

    /** The app's default User-Agent (NetworkPreferences.defaultUserAgent), what OkHttp sends unless a source sets its own. */
    val userAgent: String get() = eu.kanade.tachiyomi.network.NetworkPreferences.DEFAULT_USER_AGENT
}

class HarnessApplication : Application() {
    private val prefs = ConcurrentHashMap<String, HarnessPreferences>()

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        prefs.getOrPut(name) { HarnessPreferences(Harness.prefOverrides) }

    override fun getCacheDir(): File = Harness.dataDir.resolve("cache").apply { mkdirs() }
    override fun getFilesDir(): File = Harness.dataDir.resolve("files").apply { mkdirs() }
    override fun getPackageName(): String = "app.ext.run"
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
 * Process lifetime (the app persists cookies; ext-run starts empty each run, except Cloudflare
 * clearances, see CfClearances).
 */
object HarnessCookieJar : CookieJar {
    private val store = ConcurrentHashMap<String, Cookie>()

    private fun key(c: Cookie) = "${if (c.hostOnly) "" else "."}${c.domain}|${c.path}|${c.name}"

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val now = System.currentTimeMillis()
        cookies.forEach { if (it.expiresAt <= now) store.remove(key(it)) else store[key(it)] = it }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        return store.values.filter { it.expiresAt > now && it.matches(url) }
    }

    fun all(): List<Cookie> = store.values.toList()

    fun put(cookie: Cookie) {
        store[key(cookie)] = cookie
    }

    fun remove(predicate: (Cookie) -> Boolean) {
        store.values.removeIf(predicate)
    }

    fun clear(sessionOnly: Boolean = false) {
        if (sessionOnly) store.values.removeIf { !it.persistent } else store.clear()
    }
}
