package eu.kanade.tachiyomi.source.online

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.newCachelessCallWithProgress
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import uy.kohesive.injekt.injectLazy
import java.net.URI
import java.net.URISyntaxException
import java.security.MessageDigest

// Behaviour follows Mihon's HttpSource (source-api/src/main/kotlin/eu/kanade/tachiyomi/source/online/HttpSource.kt
// at df6507256a, v0.20.4, Apache-2.0); signatures follow extensions-lib 1.6 (getMangaUrl/getChapterUrl abstract,
// no deprecated Rx/parse helpers).
abstract class HttpSource : CatalogueSource {

    protected val network: NetworkHelper by injectLazy()

    abstract val baseUrl: String

    open fun getHomeUrl(): String = baseUrl

    open val versionId: Int = 1

    override val id: Long by lazy { generateId(name, lang, versionId) }

    // KeiSource replaces this field's delegate reflectively, so it must stay `by lazy` and named `headers`.
    val headers: Headers by lazy { headersBuilder().build() }

    open val client: OkHttpClient
        get() = network.client

    private fun generateId(name: String, lang: String, versionId: Int): Long {
        val key = "${name.lowercase()}/$lang/$versionId"
        val bytes = MessageDigest.getInstance("MD5").digest(key.toByteArray())
        return (0..7).map { bytes[it].toLong() and 0xff shl 8 * (7 - it) }.reduce(Long::or) and Long.MAX_VALUE
    }

    protected open fun headersBuilder(): Headers.Builder = Headers.Builder().apply {
        add("User-Agent", network.defaultUserAgentProvider())
    }

    override fun toString(): String = "$name (${lang.uppercase()})"

    // Mihon: fetchImageUrl(page).awaitSingle() = GET(page.url, headers) through client, then imageUrlParse(),
    // which throws UnsupportedOperationException unless overridden (1.6 sources override getImageUrl instead).
    open suspend fun getImageUrl(page: Page): String {
        client.newCall(GET(page.url, headers)).awaitSuccess().close()
        throw UnsupportedOperationException()
    }

    /** Mihon's HttpSource.getImage: what the reader and the downloader call to fetch a page image. */
    suspend fun getImage(page: Page, existingSize: Long = 0L): okhttp3.Response {
        return client.newCachelessCallWithProgress(imageRequest(page), existingSize)
            .awaitSuccess()
    }

    protected open fun imageRequest(page: Page): Request = GET(page.imageUrl!!, headers)

    fun SChapter.setUrlWithoutDomain(url: String) {
        this.url = getUrlWithoutDomain(url)
    }

    fun SManga.setUrlWithoutDomain(url: String) {
        this.url = getUrlWithoutDomain(url)
    }

    private fun getUrlWithoutDomain(orig: String): String = try {
        val uri = URI(orig.replace(" ", "%20"))
        var out = uri.path
        if (uri.query != null) out += "?" + uri.query
        if (uri.fragment != null) out += "#" + uri.fragment
        out
    } catch (_: URISyntaxException) {
        orig
    }

    abstract fun getMangaUrl(manga: SManga): String

    abstract fun getChapterUrl(chapter: SChapter): String

    // Harness hook: the protected imageRequest, so `pages --head` can show what the app would request.
    fun harnessImageRequest(page: Page): Request = imageRequest(page)
}
