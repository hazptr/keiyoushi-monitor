package android.net

import java.net.URI
import java.net.URLDecoder

/**
 * android.net.Uri: parsing, the getters, Uri.Builder/buildUpon() and encode()/decode() with
 * Android's rules (encode() leaves only A-Z a-z 0-9 _-!.~'()* unescaped; appendPath/
 * appendQueryParameter encode with it, so spaces become %20, never '+').
 */
abstract class Uri {
    abstract val scheme: String?
    abstract val authority: String?
    abstract val host: String?
    abstract val port: Int
    abstract val path: String?
    abstract val encodedPath: String?
    abstract val query: String?
    abstract val encodedQuery: String?
    abstract val fragment: String?
    abstract val pathSegments: List<String>
    val lastPathSegment: String? get() = pathSegments.lastOrNull()

    fun getQueryParameters(key: String): List<String> = encodedQuery.orEmpty().split('&')
        .filter { it.isNotEmpty() && formDecode(it.substringBefore('=')) == key }
        .map { formDecode(it.substringAfter('=', "")) }

    fun getQueryParameter(key: String): String? = getQueryParameters(key).firstOrNull()

    fun getQueryParameterNames(): Set<String> = encodedQuery.orEmpty().split('&')
        .filter { it.isNotEmpty() }.mapTo(LinkedHashSet()) { formDecode(it.substringBefore('=')) }

    // Query values decode '+' as a space, as Android's getQueryParameter does.
    private fun formDecode(s: String): String = URLDecoder.decode(s, "UTF-8")

    open val encodedAuthority: String? get() = authority
    open val encodedFragment: String? get() = fragment

    /** A Builder holding this Uri's (encoded) parts. */
    fun buildUpon(): Builder = Builder()
        .scheme(scheme)
        .encodedAuthority(encodedAuthority)
        .encodedPath(encodedPath)
        .encodedQuery(encodedQuery)
        .encodedFragment(encodedFragment)

    /** Hierarchical builder, as android.net.Uri.Builder (opaque URIs aren't built by any extension). */
    class Builder {
        private var scheme: String? = null
        private var authority: String? = null // encoded
        private var path: String? = null // encoded
        private var query: String? = null // encoded
        private var fragment: String? = null // encoded

        fun scheme(scheme: String?) = apply { this.scheme = scheme }
        fun authority(authority: String?) = apply { this.authority = authority?.let { encode(it, "@:") } }
        fun encodedAuthority(authority: String?) = apply { this.authority = authority }
        fun path(path: String?) = apply { this.path = path?.let { encode(it, "/") } }
        fun encodedPath(path: String?) = apply { this.path = path }

        fun appendPath(newSegment: String) = appendEncodedPath(encode(newSegment)!!)

        fun appendEncodedPath(newSegment: String) = apply {
            val old = path.orEmpty()
            path = when {
                old.isEmpty() -> if (newSegment.startsWith("/")) newSegment else "/$newSegment"
                old.endsWith("/") -> old + newSegment.removePrefix("/")
                newSegment.startsWith("/") -> old + newSegment
                else -> "$old/$newSegment"
            }
        }

        fun query(query: String?) = apply { this.query = query?.let { encode(it, "=&") } }
        fun encodedQuery(query: String?) = apply { this.query = query }

        fun appendQueryParameter(key: String, value: String?) = apply {
            val param = encode(key, null) + "=" + encode(value, null).orEmpty()
            query = if (query.isNullOrEmpty()) param else "$query&$param"
        }

        fun clearQuery() = apply { query = null }
        fun fragment(fragment: String?) = apply { this.fragment = fragment?.let { encode(it, null) } }
        fun encodedFragment(fragment: String?) = apply { this.fragment = fragment }

        fun build(): Uri {
            var p = path.orEmpty()
            // Android makes the path absolute when there is an authority.
            if (authority != null && p.isNotEmpty() && !p.startsWith("/")) p = "/$p"
            val str = buildString {
                scheme?.let { append(it).append(':') }
                authority?.let { append("//").append(it) }
                append(p)
                query?.let { append('?').append(it) }
                fragment?.let { append('#').append(it) }
            }
            return Parsed(str)
        }

        override fun toString(): String = build().toString()
    }

    fun isAbsolute(): Boolean = scheme != null
    fun isRelative(): Boolean = scheme == null

    override fun equals(other: Any?) = other is Uri && other.toString() == toString()
    override fun hashCode() = toString().hashCode()

    private class Parsed(private val raw: String) : Uri() {
        private val u: URI? = try {
            URI(raw)
        } catch (_: Exception) {
            null
        }
        override val scheme get() = u?.scheme ?: raw.substringBefore(':', "").ifEmpty { null }
        override val authority get() = u?.authority
        override val encodedAuthority get() = u?.rawAuthority
        override val encodedFragment get() = u?.rawFragment
        override val host get() = u?.host
        override val port get() = u?.port ?: -1
        override val path get() = u?.path
        override val encodedPath get() = u?.rawPath
        override val query get() = u?.query
        override val encodedQuery get() = u?.rawQuery
        override val fragment get() = u?.fragment
        override val pathSegments get() = path.orEmpty().split('/').filter { it.isNotEmpty() }
        override fun toString() = raw
    }

    companion object {
        @JvmField val EMPTY: Uri = Parsed("")

        @JvmStatic fun parse(uriString: String): Uri = Parsed(uriString)

        private const val UNRESERVED = "_-!.~'()*"

        @JvmStatic fun encode(s: String?): String? = encode(s, null)

        /** Android's Uri.encode: UTF-8 percent-encoding of everything but A-Z a-z 0-9 _-!.~'()* and [allow]. */
        @JvmStatic fun encode(s: String?, allow: String?): String? {
            if (s == null) return null
            val sb = StringBuilder()
            for (b in s.toByteArray(Charsets.UTF_8)) {
                val c = (b.toInt() and 0xFF).toChar()
                if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c in UNRESERVED || (allow != null && c.code < 0x80 && c in allow)) {
                    sb.append(c)
                } else {
                    sb.append('%').append("%02X".format(b.toInt() and 0xFF))
                }
            }
            return sb.toString()
        }

        @JvmStatic fun fromParts(scheme: String, ssp: String, fragment: String?): Uri =
            Parsed("$scheme:" + encode(ssp, "/:@") + (fragment?.let { "#" + encode(it) } ?: ""))

        @JvmStatic fun decode(s: String?): String? = s?.let { URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }
    }
}
