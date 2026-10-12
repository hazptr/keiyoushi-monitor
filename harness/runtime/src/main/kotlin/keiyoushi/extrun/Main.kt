package keiyoushi.extrun

import android.app.Application
import android.util.Log
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.protobuf.ProtoBuf
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import java.io.ByteArrayInputStream
import java.io.File
import java.time.Instant
import javax.imageio.ImageIO
import kotlin.system.exitProcess

private const val USAGE = """usage: ext-run [options] --class <source class> <command> [args]
commands:
  popular [page]              getPopularManga
  latest [page]               getLatestUpdates
  search <query> [page]       getSearchManga with the source's default filters
  url <http-url> [page]       getSearchManga(query = url): KeiSource URL-search / deeplink path
  manga <manga-url>           getMangaUpdate(details + chapters); an http(s) URL is resolved via `url` first
  details <manga-url>         getMangaUpdate(fetchDetails only)
  chapters <manga-url>        getMangaUpdate(fetchChapters only)
  pages <chapter-url>         getPageList; a full URL on baseUrl is reduced to its path
  filters                     print getFilterList()
  related <manga-url>         fetchRelatedMangaList (Komikku related manga)
  sources                     list the sources the class provides
  smoke [manga-url]           every user-facing op, each with its own pass/fail/skip: browse, latest,
                               search, details, chapters, read (page list), pages (2 images through
                               source.client); always prints one JSON object, exit 0 = no op failed (see README)
options:
  --fixtures DIR   serve responses from DIR (with --live: record misses into DIR)
  --direct, --app  app parity: plain OkHttp through the ported Mihon network stack (the app's UA,
                   cookie jar, cache, CloudflareInterceptor). What ext-monitor uses
  --live           fetch misses with curl-impersonate (Firefox fingerprint, like site-fetch); for
                   investigating a site, NOT app behaviour (browser TLS passes where the app is blocked)
  --refresh        with --fixtures --live: re-fetch and overwrite existing fixtures
  --map URL=FILE   serve one URL from a file (e.g. a saved sample)
  --pref K=V       seed SharedPreferences (all files)
  --source NAME    pick a source from a SourceFactory (name, lang or id)
  --json           JSON output (default: readable text)
  --all            text mode: print every item, not just the first/last few
  --limit N        text mode list limit (default 40)
  --keep-ua        --live: send the source's User-Agent instead of curl's Firefox one
  -v               verbose source logs (android.util.Log d/i/v)"""

private class Opts {
    var cls: String? = null
    var sourceSel: String? = null
    var json = false
    var all = false
    var limit = 40
    val rest = mutableListOf<String>()
}

fun main(args: Array<String>) {
    val o = Opts()
    var i = 0
    fun next(): String = args.getOrNull(++i) ?: die("missing value for ${args[i - 1]}")
    while (i < args.size) {
        when (val a = args[i]) {
            "--class" -> o.cls = next()
            "--fixtures" -> Harness.fixturesDir = File(next())
            "--live" -> Harness.live = true
            "--refresh" -> Harness.refresh = true
            "--direct", "--app" -> Harness.direct = true
            "--map" -> next().let { Harness.urlMap[it.substringBeforeLast('=')] = File(it.substringAfterLast('=')) }
            "--pref" -> next().let { Harness.prefOverrides[it.substringBefore('=')] = it.substringAfter('=') }
            "--source" -> o.sourceSel = next()
            "--json" -> o.json = true
            "--all" -> o.all = true
            "--limit" -> o.limit = next().toInt()
            "--keep-ua" -> Harness.keepUserAgent = true
            "--browser" -> Harness.browser = next()
            "-v" -> Harness.verbose = true
            "-h", "--help" -> die(USAGE, 0)
            else -> o.rest += a
        }
        i++
    }
    Log.quiet = !Harness.verbose
    val cls = o.cls ?: die(USAGE)
    if (o.rest.isEmpty()) die(USAGE)
    if (!Harness.live && !Harness.direct && Harness.fixturesDir == null && Harness.urlMap.isEmpty()) Harness.live = true

    // What Android's runtime sets up before any app code runs.
    // http.agent: Dalvik's default ("Dalvik/2.1.0 (Linux; U; Android <release>; <model> Build/<id>)"), from the Build shim.
    System.setProperty(
        "http.agent",
        "Dalvik/2.1.0 (Linux; U; Android ${android.os.Build.VERSION.RELEASE}; ${android.os.Build.MODEL} Build/${android.os.Build.ID})",
    )
    // Android's providers (Conscrypt, BC) know cipher names the JDK doesn't (AES/CBC/PKCS7Padding).
    java.security.Security.addProvider(org.bouncycastle.jce.provider.BouncyCastleProvider())

    // What the app registers in Injekt that core/ and extensions pull out.
    val app = HarnessApplication()
    Injekt.addSingleton<Application>(app)
    Injekt.addSingleton<android.content.Context>(app)
    Injekt.addSingleton(Json { ignoreUnknownKeys = true; explicitNulls = false })
    Injekt.addSingleton<ProtoBuf>(ProtoBuf)
    Injekt.addSingleton(NetworkHelper(app))
    CfClearances.load()

    val t0 = System.nanoTime()
    val code = try {
        val sources = loadSources(cls)
        val source = pickSource(sources, o.sourceSel)
        runBlocking { run(source, sources, o) }
    } catch (e: Throwable) {
        System.err.println("\nERROR: ${e::class.qualifiedName}: ${e.message}")
        e.printStackTrace()
        missingAndroidClass(e)?.let { cls ->
            System.err.println(
                "\next-run: the source uses an Android class the harness doesn't shim ($cls). Add a shim under " +
                    "runtime/src/main/kotlin/${cls.substringBeforeLast('/')}/ or test on the device (kx).",
            )
        }
        1
    }
    val ms = (System.nanoTime() - t0) / 1_000_000
    val byKey = Transport.requestKeys.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
        .joinToString { (k, v) -> "$k x$v" }
    val wvKeys = Transport.webviewKeys.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
        .take(6).joinToString { (k, v) -> "$k x$v" }
    val webview = Transport.webviewCount.get().takeIf { it > 0 }?.let { " + $it webview request(s) [$wvKeys${if (Transport.webviewKeys.toSet().size > 6) ", ..." else ""}]" }.orEmpty()
    System.err.println("-- ${Transport.requestCount.get()} request(s)${if (byKey.isEmpty()) "" else " [$byKey]"}$webview, ${ms}ms in source code + transport")
    WebViewBridge.shutdown()
    System.out.flush()
    exitProcess(code)
}

/** The missing class name (e.g. "android/webkit/WebView") if [e] or a cause is a NoClassDefFoundError for an Android class. */
private fun missingAndroidClass(e: Throwable): String? = generateSequence(e) { it.cause }
    .filterIsInstance<NoClassDefFoundError>()
    .mapNotNull { it.message?.replace('.', '/') }
    .firstOrNull { it.startsWith("android/") || it.startsWith("androidx/") }

private fun die(msg: String, code: Int = 2): Nothing {
    System.err.println(msg)
    exitProcess(code)
}

private fun loadSources(cls: String): List<Source> {
    val c = Class.forName(cls)
    val inst = c.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
    return when (inst) {
        is SourceFactory -> inst.createSources()
        is Source -> listOf(inst)
        else -> die("$cls is neither a Source nor a SourceFactory")
    }
}

private fun pickSource(sources: List<Source>, sel: String?): Source {
    if (sel == null) {
        val pick = sources.firstOrNull { (it as? CatalogueSource)?.lang == "en" } ?: sources.first()
        if (sources.size > 1) System.err.println("(${sources.size} sources; using ${pick.name} (${(pick as? CatalogueSource)?.lang}). Pick with --source <name|lang|id>, list with `sources`)")
        return pick
    }
    return sources.firstOrNull { it.name.equals(sel, true) || it.id.toString() == sel || (it as? CatalogueSource)?.lang == sel }
        ?: die("no source matches '$sel': " + sources.joinToString { "${it.name}/${(it as? CatalogueSource)?.lang}/${it.id}" })
}

private fun Source.base() = (this as? HttpSource)?.baseUrl

private suspend fun run(source: Source, all: List<Source>, o: Opts): Int {
    val args = o.rest
    val cmd = args[0]
    fun arg(n: Int) = args.getOrNull(n) ?: die("$cmd: missing argument #$n\n$USAGE")
    fun page(n: Int) = args.getOrNull(n)?.toInt() ?: 1
    System.err.println("source: ${source.name} (${(source as? CatalogueSource)?.lang}) id=${source.id} ${source.base().orEmpty()}")

    when (cmd) {
        "sources" -> emit(o, buildJsonArray {
            all.forEach { add(buildJsonObject { put("name", it.name); put("lang", (it as? CatalogueSource)?.lang); put("id", it.id); put("baseUrl", it.base()) }) }
        }) { all.joinToString("\n") { "${it.name}\t${(it as? CatalogueSource)?.lang}\t${it.id}\t${it.base()}" } }
        "popular" -> printPage(o, source.getPopularManga(page(1)))
        "latest" -> printPage(o, source.getLatestUpdates(page(1)))
        "search" -> printPage(o, source.getSearchManga(page(2), arg(1), source.getFilterList()))
        "url" -> printPage(o, source.getSearchManga(page(2), arg(1), source.getFilterList()))
        "manga", "details", "chapters" -> {
            val manga = SManga.create().apply { url = resolveMangaUrl(source, arg(1)) }
            val update = source.getMangaUpdate(manga, emptyList(), cmd != "chapters", cmd != "details")
            printManga(o, update.manga, update.chapters.takeIf { cmd != "details" })
        }
        "pages" -> {
            val chapter = SChapter.create().apply {
                url = stripBase(source, arg(1))
                name = "ext-run"
            }
            printPages(o, source, source.getPageList(chapter))
        }
        "filters" -> printFilters(o, source.getFilterList())
        "related" -> {
            val manga = SManga.create().apply { url = resolveMangaUrl(source, arg(1)) }
            printPage(o, MangasPage((source as CatalogueSource).fetchRelatedMangaList(manga), false))
        }
        "smoke" -> return runSmoke(source, args.getOrNull(1))
        else -> die("unknown command '$cmd'\n$USAGE")
    }
    return 0
}

private fun stripBase(source: Source, u: String): String {
    val base = source.base() ?: return u
    return if (u.startsWith(base)) u.removePrefix(base).ifEmpty { "/" } else u
}

private suspend fun resolveMangaUrl(source: Source, arg: String): String {
    if (!arg.startsWith("http://") && !arg.startsWith("https://")) return arg
    val found = source.getSearchManga(1, arg, source.getFilterList()).mangas.firstOrNull()
    if (found != null) {
        System.err.println("resolved $arg -> manga.url=${found.url}")
        return found.url
    }
    return stripBase(source, arg).also { System.err.println("URL search found nothing; using path $it") }
}

// ---------- output ----------

private inline fun <T> safe(block: () -> T): T? = try {
    block()
} catch (_: UninitializedPropertyAccessException) {
    null
}

private val statusNames = mapOf(0 to "UNKNOWN", 1 to "ONGOING", 2 to "COMPLETED", 3 to "LICENSED", 4 to "PUBLISHING_FINISHED", 5 to "CANCELLED", 6 to "ON_HIATUS")

private fun SManga.toJson() = buildJsonObject {
    put("url", safe { url })
    put("title", safe { title })
    put("thumbnail_url", thumbnail_url)
    put("author", author)
    put("artist", artist)
    put("status", statusNames[status] ?: status.toString())
    put("genre", genre)
    put("description", description)
    put("update_strategy", update_strategy.name)
    put("initialized", initialized)
    if (memo.isNotEmpty()) put("memo", memo)
}

private fun SChapter.toJson() = buildJsonObject {
    put("url", safe { url })
    put("name", safe { name })
    put("chapter_number", chapter_number)
    put("scanlator", scanlator)
    put("date_upload", date_upload)
    if (date_upload > 0) put("date", Instant.ofEpochMilli(date_upload).toString())
    if (memo.isNotEmpty()) put("memo", memo)
}

private val pretty = Json { prettyPrint = true }

private fun emit(o: Opts, json: JsonElement, text: () -> String) {
    println(if (o.json) pretty.encodeToString(JsonElement.serializer(), json) else text())
}

private fun <T> window(list: List<T>, o: Opts, head: Int = o.limit, tail: Int = 0): List<Pair<Int, T>?> {
    val indexed = list.mapIndexed { i, t -> i to t }
    if (o.all || list.size <= head + tail) return indexed
    return indexed.take(head) + listOf(null) + indexed.takeLast(tail)
}

private fun printPage(o: Opts, page: MangasPage) {
    val warn = mutableListOf<String>()
    page.mangas.count { safe { it.title }.isNullOrBlank() }.takeIf { it > 0 }?.let { warn += "$it without title" }
    page.mangas.count { safe { it.url }.isNullOrBlank() }.takeIf { it > 0 }?.let { warn += "$it without url" }
    page.mangas.count { it.thumbnail_url.isNullOrBlank() }.takeIf { it > 0 }?.let { warn += "$it without thumbnail" }
    page.mangas.groupingBy { safe { it.url } }.eachCount().filter { it.value > 1 }.size.takeIf { it > 0 }?.let { warn += "$it duplicate urls" }
    emit(
        o,
        buildJsonObject {
            put("count", page.mangas.size)
            put("hasNextPage", page.hasNextPage)
            put("warnings", JsonArray(warn.map(::JsonPrimitive)))
            put("mangas", JsonArray(page.mangas.map { it.toJson() }))
        },
    ) {
        buildString {
            appendLine("${page.mangas.size} manga, hasNextPage=${page.hasNextPage}")
            if (warn.isNotEmpty()) appendLine("WARN: ${warn.joinToString()}")
            window(page.mangas, o).forEach { e ->
                if (e == null) appendLine("  ... (${page.mangas.size - o.limit} more, --all)") else {
                    val (i, m) = e
                    appendLine("%3d. %s\n     url=%s\n     thumb=%s".format(i + 1, safe { m.title }, safe { m.url }, m.thumbnail_url))
                }
            }
        }.trimEnd()
    }
}

private fun printManga(o: Opts, m: SManga, chapters: List<SChapter>?) {
    val warn = mutableListOf<String>()
    if (chapters != null) {
        chapters.count { it.chapter_number < 0 }.takeIf { it > 0 }?.let { warn += "$it chapters with chapter_number=-1" }
        chapters.count { it.date_upload == 0L }.takeIf { it > 0 }?.let { warn += "$it chapters with date_upload=0" }
        chapters.count { safe { it.name }.isNullOrBlank() }.takeIf { it > 0 }?.let { warn += "$it chapters without name" }
        chapters.groupingBy { safe { it.url } }.eachCount().filter { it.value > 1 }.size.takeIf { it > 0 }?.let { warn += "$it duplicate chapter urls" }
    }
    val scanlators = chapters?.groupingBy { it.scanlator ?: "<none>" }?.eachCount()
    emit(
        o,
        buildJsonObject {
            put("manga", m.toJson())
            if (chapters != null) {
                put("chapterCount", chapters.size)
                put("scanlators", buildJsonObject { scanlators!!.forEach { (k, v) -> put(k, v) } })
                put("warnings", JsonArray(warn.map(::JsonPrimitive)))
                put("chapters", JsonArray(chapters.map { it.toJson() }))
            }
        },
    ) {
        buildString {
            appendLine("title:       ${safe { m.title }}")
            appendLine("url:         ${safe { m.url }}")
            appendLine("thumbnail:   ${m.thumbnail_url}")
            appendLine("author:      ${m.author}")
            appendLine("artist:      ${m.artist}")
            appendLine("status:      ${statusNames[m.status] ?: m.status}")
            appendLine("genre:       ${m.genre}")
            appendLine("initialized: ${m.initialized}")
            appendLine("description:\n" + (m.description ?: "null").prependIndent("  "))
            if (chapters != null) {
                appendLine("chapters:    ${chapters.size}")
                appendLine("scanlators:  " + scanlators!!.entries.joinToString { "${it.key}=${it.value}" })
                if (warn.isNotEmpty()) appendLine("WARN: ${warn.joinToString()}")
                window(chapters, o, head = minOf(o.limit, 8), tail = 5).forEach { e ->
                    if (e == null) appendLine("  ...") else {
                        val (i, c) = e
                        val date = if (c.date_upload > 0) Instant.ofEpochMilli(c.date_upload).toString().take(10) else "-"
                        appendLine("%4d. [%s] #%s %s | %s | %s".format(i + 1, date, c.chapter_number, safe { c.name }, c.scanlator, safe { c.url }))
                    }
                }
            }
        }.trimEnd()
    }
}

private fun printPages(o: Opts, source: Source, pages: List<Page>) {
    val noUrl = pages.count { it.imageUrl.isNullOrBlank() && it.url.isBlank() }
    val hosts = pages.mapNotNull { (it.imageUrl ?: it.url).takeIf(String::isNotBlank)?.let { u -> runCatching { java.net.URI(u).host }.getOrNull() } }
        .groupingBy { it }.eachCount()
    val headers = (source as? HttpSource)?.let { s ->
        pages.firstOrNull { !it.imageUrl.isNullOrBlank() }?.let { p -> runCatching { s.harnessImageRequest(p).headers }.getOrNull() }
    }
    emit(
        o,
        buildJsonObject {
            put("count", pages.size)
            put("hosts", buildJsonObject { hosts.forEach { (k, v) -> put(k, v) } })
            headers?.let { h -> put("imageRequestHeaders", buildJsonObject { h.forEach { (k, v) -> put(k, v) } }) }
            put(
                "pages",
                buildJsonArray {
                    pages.forEach { p -> add(buildJsonObject { put("index", p.index); put("url", p.url); put("imageUrl", p.imageUrl) }) }
                },
            )
        },
    ) {
        buildString {
            appendLine("${pages.size} pages; hosts: " + hosts.entries.joinToString { "${it.key}=${it.value}" })
            if (noUrl > 0) appendLine("WARN: $noUrl pages with neither imageUrl nor url")
            if (pages.any { it.imageUrl == null && it.url.isNotBlank() }) appendLine("note: pages with url but no imageUrl need getImageUrl() (not called here)")
            headers?.let { appendLine("imageRequest headers: " + it.joinToString { (k, v) -> "$k: $v" }) }
            window(pages, o, head = minOf(o.limit, 5), tail = 3).forEach { e ->
                if (e == null) appendLine("  ...") else appendLine("%4d. %s".format(e.second.index, e.second.imageUrl ?: "url=" + e.second.url))
            }
        }.trimEnd()
    }
}

private fun printFilters(o: Opts, filters: List<Filter<*>>) {
    fun desc(f: Filter<*>): JsonObject = buildJsonObject {
        put("type", generateSequence<Class<*>>(f::class.java) { it.superclass }.firstOrNull { it.enclosingClass == Filter::class.java }?.simpleName ?: "?")
        put("class", f::class.java.simpleName)
        put("name", f.name)
        when (f) {
            is Filter.Select<*> -> put("values", JsonArray(f.values.map { JsonPrimitive(it.toString()) }))
            is Filter.Sort -> put("values", JsonArray(f.values.map(::JsonPrimitive)))
            is Filter.Group<*> -> put("items", JsonArray(f.state.filterIsInstance<Filter<*>>().map { desc(it) }))
            else -> {}
        }
        if (f !is Filter.Group<*>) put("state", f.state.toString())
    }
    val arr = JsonArray(filters.map { desc(it) })
    emit(o, arr) {
        filters.joinToString("\n") { f ->
            val d = desc(f)
            "- ${d["type"]!!.toString().trim('"')} ${f::class.java.simpleName} \"${f.name}\"" +
                ((d["values"] as? JsonArray)?.let { " values=${it.size}" } ?: "") +
                ((d["items"] as? JsonArray)?.let { " items=${it.size}" } ?: "")
        }
    }
}

// ---------- smoke: the whole reader path once, for an unattended monitor ----------

/** One user-facing operation of a smoke run. `ok` mirrors [status] for older readers: pass = true, fail = false, skip = null. */
private class SmokeStep(val name: String) {
    var status = "fail"
    var ms = 0L
    var detail: JsonObject = JsonObject(emptyMap())
    var reason: String? = null
    var error: String? = null
    var errorFrames: List<String> = emptyList()

    fun toJson() = buildJsonObject {
        put("step", name)
        put("status", status)
        put("ok", when (status) { "pass" -> true; "fail" -> false; else -> null })
        put("ms", ms)
        reason?.let { put("reason", it) }
        if (detail.isNotEmpty()) put("detail", detail)
        error?.let { put("error", it) }
        if (errorFrames.isNotEmpty()) put("errorFrames", JsonArray(errorFrames.map(::JsonPrimitive)))
    }
}

/** Thrown inside an op to record it as skipped (e.g. the source has no latest list), not failed. */
private class SkipOp(val reason: String) : Exception(reason)

/** Runs [block] as one op: pass with its result, fail on an exception (null), skip on [SkipOp] (null). */
private suspend fun <T> smokeStep(source: Source, name: String, steps: MutableList<SmokeStep>, block: suspend (SmokeStep) -> T): T? {
    val s = SmokeStep(name)
    val t0 = System.nanoTime()
    val result = try {
        block(s).also { s.status = "pass" }
    } catch (e: SkipOp) {
        s.status = "skip"
        s.reason = e.reason
        null
    } catch (e: Throwable) {
        s.error = "${e::class.qualifiedName}: ${e.message}"
        s.errorFrames = extensionFrames(source, e)
        null
    }
    s.ms = (System.nanoTime() - t0) / 1_000_000
    steps += s
    return result
}

/** Records [name] as skipped without running anything: a dependency it needs isn't available. */
private fun skipStep(name: String, steps: MutableList<SmokeStep>, reason: String) {
    steps += SmokeStep(name).apply { status = "skip"; this.reason = reason }
}

/** Up to 3 stack frames in extension code (the source's package, lib-multisrc, lib modules, core), from [e] and its causes. */
private fun extensionFrames(source: Source, e: Throwable): List<String> {
    val prefixes = listOf(source::class.java.packageName + ".", "eu.kanade.tachiyomi.multisrc.", "eu.kanade.tachiyomi.lib.", "keiyoushi.")
    return generateSequence(e) { it.cause }.take(5)
        .flatMap { it.stackTrace.asSequence() }
        .filter { f -> prefixes.any { f.className.startsWith(it) } && !f.className.startsWith("keiyoushi.extrun.") }
        .map { it.toString() }
        .distinct()
        .take(3)
        .toList()
}

private fun collectMangaWarnings(mangas: List<SManga>, warnings: MutableList<String>) {
    mangas.groupingBy { safe { it.url } }.eachCount().filter { it.value > 1 }.size.takeIf { it > 0 }?.let { warnings += "$it duplicate manga urls" }
}

private fun collectChapterWarnings(chapters: List<SChapter>, warnings: MutableList<String>) {
    chapters.count { it.chapter_number < 0 }.takeIf { it > 0 }?.let { warnings += "$it chapters with chapter_number=-1" }
    chapters.count { it.date_upload == 0L }.takeIf { it > 0 }?.let { warnings += "$it chapters with date_upload=0" }
    chapters.groupingBy { safe { it.url } }.eachCount().filter { it.value > 1 }.size.takeIf { it > 0 }?.let { warnings += "$it duplicate chapter urls" }
}

/** sha256 of image responses known to be a host's placeholder instead of the requested image. */
private val KNOWN_PLACEHOLDERS = mapOf<String, String>(
    // images*.imgbox.com, 8091-byte JPEG "Thumbnail Temporarily Unavailable" (2026-10-10, ahottie/doujinshell)
    "c0ff95f9ec7fea007b8236e8efddfcc6c0dfdd56f8e4c38c8ffed8fde655d8a7" to "imgbox's \"Thumbnail Temporarily Unavailable\" placeholder",
    // From Vetle's ext-stats (vetleledaal/keiyoushi-status, wip/ext), used with permission.
    "7b62dcb9f9de701c6721fffbf0f9836f9b76730442e6a4e376b4871b17d69b17" to "imgbox's \"Thumbnail Temporarily Unavailable\" placeholder",
    "dda713ac5393d52bfe89cc0b417c7a195a7603a8a8954ead1ec45f370124ce38" to "Lunar's placeholder for scrapers",
)

/** Smallest width or height a real page has; anything under is an icon or a tracking pixel. */
private const val MIN_PAGE_SIDE = 32

/** Spread of every RGB channel over a grid of samples; 8 or less is one flat colour (a blank or placeholder image). */
private fun isSingleColour(img: java.awt.image.BufferedImage): Boolean {
    val lo = intArrayOf(255, 255, 255)
    val hi = intArrayOf(0, 0, 0)
    val steps = 48
    for (yi in 0 until steps) for (xi in 0 until steps) {
        val rgb = img.getRGB(xi * (img.width - 1) / (steps - 1), yi * (img.height - 1) / (steps - 1))
        val c = intArrayOf((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)
        for (i in 0..2) { lo[i] = minOf(lo[i], c[i]); hi[i] = maxOf(hi[i], c[i]) }
    }
    return (0..2).all { hi[it] - lo[it] <= 8 }
}

private fun sha256(b: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

/** Width and height from a RIFF/WEBP header (VP8, VP8L or VP8X chunk), or null if it isn't a well-formed one. */
private fun webpSize(b: ByteArray): Pair<Int, Int>? {
    if (b.size < 30) return null
    fun str(at: Int, n: Int) = String(b, at, n, Charsets.ISO_8859_1)
    fun u8(at: Int) = b[at].toInt() and 0xFF
    fun u16(at: Int) = u8(at) or (u8(at + 1) shl 8)
    fun u24(at: Int) = u16(at) or (u8(at + 2) shl 16)
    if (str(0, 4) != "RIFF" || str(8, 4) != "WEBP") return null
    return when (str(12, 4)) {
        "VP8 " -> if (u8(23) == 0x9d && u8(24) == 0x01 && u8(25) == 0x2a) (u16(26) and 0x3fff) to (u16(28) and 0x3fff) else null
        "VP8L" -> if (u8(20) == 0x2f) {
            val bits = u8(21) or (u8(22) shl 8) or (u8(23) shl 16) or (u8(24) shl 24)
            ((bits and 0x3fff) + 1) to (((bits shr 14) and 0x3fff) + 1)
        } else null
        "VP8X" -> (u24(24) + 1) to (u24(27) + 1)
        else -> null
    }
}

private val KNOWN_COMICS = listOf(
    "One Piece", "Naruto", "Berserk", "Solo Leveling", "Tower of God", "Chainsaw Man", "Jujutsu Kaisen",
    "Spy x Family", "Attack on Titan", "Vagabond", "Omniscient Reader", "The Beginning After the End",
    "Vinland Saga", "Dragon Ball", "Bleach", "Hunter x Hunter", "One Punch Man", "Demon Slayer", "Tokyo Ghoul",
    "Death Note", "Lookism", "Blue Lock", "Kingdom", "Haikyuu", "My Hero Academia", "Frieren", "Dandadan",
    "Oshi no Ko", "Nano Machine", "The Greatest Estate Developer",
)

/** First two to three words of a title, enough to search with and loose enough to survive punctuation. */
private fun searchWords(title: String): String =
    title.split(Regex("[^\\p{L}\\p{N}']+")).filter { it.isNotBlank() }.take(3).joinToString(" ").ifBlank { title }

/**
 * Scrolling past the first page, as the app does when page 1 says there's more: null if page 2 is fine,
 * else what's wrong with it. Like Vetle's ext-stats pagination check.
 */
private suspend fun page2Problem(page1: MangasPage, load: suspend () -> MangasPage): String? {
    if (!page1.hasNextPage) return null
    val page2 = try { load() } catch (e: Exception) { return "Page 2 doesn't load: ${e::class.simpleName}: ${e.message}" }
    if (page2.mangas.isEmpty()) return "Page 2 is empty, though page 1 says there's more"
    val urls1 = page1.mangas.mapNotNull { safe { it.url } }.toSet()
    val urls2 = page2.mangas.mapNotNull { safe { it.url } }.toSet()
    return if (urls2.isNotEmpty() && urls1 == urls2) "Page 2 repeats page 1" else null
}

/** A listing whose page 1 works but page 2 doesn't still gives a title to open, so the step fails without losing it. */
private fun markPage2(steps: MutableList<SmokeStep>, loaded: List<SManga>?, problem: String?) {
    if (loaded == null || problem == null) return
    steps.last().apply { status = "fail"; error = problem }
}

/** What a listing op loaded: the entries, for picking a title to open. */
private fun checkListing(page: MangasPage, op: String, warnings: MutableList<String>, s: SmokeStep): List<SManga> {
    check(page.mangas.isNotEmpty()) { "0 manga returned" }
    // The app can't store an entry whose url or title was never set (lateinit), so that fails. A
    // blank one it accepts (single-entry sources often use url ""), so that's a warning, unless
    // every title is blank (an empty-looking list).
    val unset = page.mangas.count { safe { it.title } == null || safe { it.url } == null }
    check(unset == 0) { "$unset of ${page.mangas.size} entries with url or title never set" }
    val blankTitle = page.mangas.count { safe { it.title }!!.isBlank() }
    check(blankTitle < page.mangas.size) { "all ${page.mangas.size} titles are blank" }
    if (blankTitle > 0) warnings += "$blankTitle of ${page.mangas.size} $op entries with a blank title"
    page.mangas.count { safe { it.url }!!.isBlank() }.takeIf { it > 0 }?.let { warnings += "$it of ${page.mangas.size} $op entries with a blank url" }
    collectMangaWarnings(page.mangas, warnings)
    s.detail = buildJsonObject { put("count", page.mangas.size); put("hasNextPage", page.hasNextPage) }
    return page.mangas
}

/**
 * Every user-facing operation once, each with its own status: browse, latest, search, details,
 * chapters, read (page list), pages (2 images through source.client). An op that fails doesn't stop
 * the others; one whose input is missing (no title, no chapter) is skipped. One JSON object, always;
 * exit 0 if no op failed, else 1.
 */
private suspend fun runSmoke(source: Source, mangaArg: String?): Int {
    val steps = mutableListOf<SmokeStep>()
    val warnings = mutableListOf<String>()
    var mangaUrl: String? = null
    var chapterUrl: String? = null
    var untestable: String? = null

    fun finish(): Int {
        if (WebViewBridge.consumeCfClicked()) warnings += "cloudflare checkbox clicked"
        val ok = steps.isNotEmpty() && steps.none { it.status == "fail" }
        val json = buildJsonObject {
            put("ok", ok)
            put("source", source.name)
            put("lang", (source as? CatalogueSource)?.lang)
            put("manga", mangaUrl)
            put("chapter", chapterUrl)
            untestable?.let { put("untestable", it) }
            put("steps", JsonArray(steps.map { it.toJson() }))
            if (warnings.isNotEmpty()) put("warnings", JsonArray(warnings.map(::JsonPrimitive)))
        }
        println(pretty.encodeToString(JsonElement.serializer(), json))
        return if (ok) 0 else 1
    }

    // 1. browse: the Browse tab (getPopularManga).
    var popularUnsupported = false
    var browsePage2: String? = null
    var latestPage2: String? = null
    val browseMangas = smokeStep(source, "browse", steps) { s ->
        val page = try {
            source.getPopularManga(1)
        } catch (e: UnsupportedOperationException) {
            popularUnsupported = true
            throw e
        }
        checkListing(page, "browse", warnings, s).also { browsePage2 = page2Problem(page) { source.getPopularManga(2) } }
    }
    markPage2(steps, browseMangas, browsePage2)

    // 2. latest: hidden in the app when the source doesn't support it, so a skip then.
    var latestUnsupported = false
    val latestMangas = smokeStep(source, "latest", steps) { s ->
        if (!source.supportsLatest) {
            latestUnsupported = true
            throw SkipOp("source has no latest list")
        }
        val page = try {
            source.getLatestUpdates(1)
        } catch (e: UnsupportedOperationException) {
            latestUnsupported = true
            throw SkipOp("source has no latest list (getLatestUpdates throws UnsupportedOperationException)")
        }
        checkListing(page, "latest", warnings, s).also { latestPage2 = page2Problem(page) { source.getLatestUpdates(2) } }
    }
    markPage2(steps, latestMangas, latestPage2)
    if (popularUnsupported) {
        if (latestUnsupported) {
            untestable = "Search-only source, no popular or latest list"
        } else {
            // A latest-only source: nothing on the Browse tab to break, the user uses Latest.
            steps.first { it.name == "browse" }.apply {
                status = "skip"; reason = "source has no popular list (latest is used instead)"; error = null; errorFrames = emptyList()
            }
        }
    }

    // 3. search: a well-known comic must not error (no results is fine, the source may not have it), and a
    // title from the source's own list should find something (a warning, not a failure).
    val searchMangas = mutableListOf<SManga>()
    smokeStep(source, "search", steps) { s ->
        val known = KNOWN_COMICS.random()
        val knownHits = try {
            source.getSearchManga(1, known, source.getFilterList()).mangas
        } catch (e: UnsupportedOperationException) {
            // A source without search (e.g. a single-comic site): nothing for a user to break.
            warnings += "no search (getSearchManga throws UnsupportedOperationException)"
            throw SkipOp("source has no search (getSearchManga throws UnsupportedOperationException)")
        }
        val bad = knownHits.count { safe { it.url }.isNullOrBlank() || safe { it.title }.isNullOrBlank() }
        check(bad == 0) { "$bad of ${knownHits.size} results for '$known' without title or url" }
        searchMangas += knownHits
        val own = (browseMangas ?: latestMangas).orEmpty().mapNotNull { safe { it.title } }.firstOrNull { it.isNotBlank() }
        val ownQuery = own?.let { searchWords(it) }
        val ownHits = ownQuery?.let { source.getSearchManga(1, it, source.getFilterList()).mangas }
        ownHits?.let { searchMangas += it }
        if (ownQuery != null && ownHits!!.isEmpty()) warnings += "search for '$ownQuery' (from its own ${if (browseMangas != null) "browse" else "latest"} list) found nothing"
        s.detail = buildJsonObject {
            put("known", known); put("knownHits", knownHits.size)
            ownQuery?.let { put("own", it); put("ownHits", ownHits!!.size) }
        }
    }

    // The title to open: from whichever list loaded (browse, else latest, else search results), or the argument.
    val listing = browseMangas ?: latestMangas ?: searchMangas.takeIf { it.isNotEmpty() }
    val listingName = when (listing) { null -> null; browseMangas -> "browse"; latestMangas -> "latest"; else -> "search" }
    val candidates: List<SManga> = when {
        mangaArg != null -> {
            // Keep the listing's title when the given series is in it (the app always has it).
            val url = try { resolveMangaUrl(source, mangaArg) } catch (e: Exception) { stripBase(source, mangaArg) }
            listOf(listing?.firstOrNull { safe { it.url } == url } ?: SManga.create().apply { this.url = url })
        }
        // A different series each run, so repeated runs cover more of the source.
        listing != null -> listing.filter { safe { it.url } != null }.distinctBy { it.url }.shuffled().take(3)
        else -> emptyList()
    }
    val noTitle = "no title to open: browse, latest and search all failed or returned nothing"

    // 4. details: getMangaUpdate(details only), passing the listing's SManga as the app does.
    var opened: SManga? = null
    if (candidates.isEmpty()) {
        skipStep("details", steps, noTitle)
    } else {
        smokeStep(source, "details", steps) { s ->
            var tried = 0
            var lastError: Exception? = null
            for (listed in candidates) {
                tried++
                try {
                    val update = source.getMangaUpdate(listed, emptyList(), true, false)
                    val title = safe { update.manga.title }?.takeIf { it.isNotBlank() } ?: safe { listed.title }
                    if (!title.isNullOrBlank()) {
                        opened = listed
                        s.detail = buildJsonObject {
                            put("url", listed.url)
                            put("title", title)
                            put("from", if (mangaArg != null) "argument" else listingName)
                            put("triedCandidates", tried)
                        }
                        return@smokeStep
                    }
                } catch (e: Exception) {
                    lastError = e
                }
            }
            throw lastError ?: IllegalStateException("no title among $tried candidate(s) (details returned a blank title)")
        }
    }

    // 5. chapters: the chapter list for that title (the app fetches it separately, so this runs even
    // when details failed). The title details opened goes first; others are tried if it has none.
    var chapters: List<SChapter> = emptyList()
    if (candidates.isEmpty()) {
        skipStep("chapters", steps, noTitle)
    } else {
        smokeStep(source, "chapters", steps) { s ->
            var tried = 0
            var lastError: Exception? = null
            for (listed in (listOfNotNull(opened) + candidates).distinct()) {
                tried++
                try {
                    val update = source.getMangaUpdate(listed, emptyList(), false, true)
                    if (update.chapters.isNotEmpty()) {
                        mangaUrl = listed.url
                        chapters = update.chapters
                        collectChapterWarnings(chapters, warnings)
                        s.detail = buildJsonObject {
                            put("url", listed.url)
                            safe { listed.title }?.let { put("title", it) }
                            put("chapterCount", update.chapters.size)
                            put("triedCandidates", tried)
                        }
                        return@smokeStep
                    }
                } catch (e: Exception) {
                    lastError = e
                }
            }
            throw lastError ?: IllegalStateException("0 chapters among $tried title(s) tried")
        }
    }

    // 6. read: open a chapter (getPageList). 7. pages: load 2 page images the way the reader does.
    // One title's chapters can be paid, removed or on a dead image host while the rest of the source
    // reads fine, so a failure on the opened title gives the other candidates a turn before it counts.
    val noChapter = if (candidates.isEmpty()) noTitle else "no chapter to open: chapters failed"
    var pages: List<Page> = emptyList()
    if (chapters.isEmpty()) {
        skipStep("read", steps, noChapter)
        skipStep("pages", steps, noChapter)
    } else {
        val firstTitle = mangaUrl
        val others = candidates.filter { safe { it.url } != mangaUrl }.toMutableList()
        var titleChapters = chapters
        var kept: List<SmokeStep>? = null
        val titleErrors = mutableListOf<String>()
        while (true) {
            val tried = mutableListOf<SmokeStep>()
            pages = emptyList()
            smokeStep(source, "read", tried) { s ->
                var n = 0
                var lastError: Exception? = null
                // Newest first, then the oldest: locked/paid chapters are usually the newest ones.
                for (chapter in (titleChapters.take(2) + titleChapters.takeLast(2)).distinct()) {
                    n++
                    try {
                        val p = source.getPageList(chapter)
                        if (p.isNotEmpty()) {
                            pages = p
                            chapterUrl = chapter.url
                            s.detail = buildJsonObject { put("chapterUrl", chapter.url); put("count", p.size); put("triedChapters", n) }
                            return@smokeStep
                        }
                    } catch (e: Exception) {
                        lastError = e
                    }
                }
                throw lastError ?: IllegalStateException("0 pages among $n chapter(s) tried (locked/paid chapters are common)")
            }
            if (pages.isEmpty()) {
                skipStep("pages", tried, "no page list: read failed")
            } else {
                smokeStep(source, "pages", tried) { s -> s.detail = buildJsonObject { put("images", loadPageImages(source, pages, warnings)) } }
            }
            val failed = tried.firstOrNull { it.status == "fail" }
            if (kept == null) kept = tried // the first title's result stands if no other title reads either
            if (failed == null) {
                if (titleErrors.isNotEmpty()) {
                    kept = tried
                    tried.forEach { it.detail = JsonObject(it.detail + ("triedTitles" to JsonPrimitive(titleErrors.size + 1))) }
                    warnings += "${titleErrors.size} of ${titleErrors.size + 1} titles tried didn't read (${titleErrors.first()}), another title did"
                }
                break
            }
            titleErrors += "${failed.name}: ${failed.error}"
            // The next candidate that has chapters.
            var next: List<SChapter>? = null
            while (next == null && others.isNotEmpty()) {
                val listed = others.removeAt(0)
                next = try {
                    source.getMangaUpdate(listed, emptyList(), false, true).chapters.takeIf { it.isNotEmpty() }
                } catch (e: Exception) {
                    null
                }
                if (next != null) mangaUrl = listed.url
            }
            if (next == null) {
                mangaUrl = firstTitle
                if (titleErrors.size > 1) kept!!.first { it.status == "fail" }.detail = buildJsonObject { put("triedTitles", titleErrors.size) }
                break
            }
            titleChapters = next
        }
        steps += kept!!
    }

    return finish()
}

/** The first 2 pages' images through source.client, as the reader (HttpPageLoader) loads them; throws on a bad one. */
private suspend fun loadPageImages(source: Source, pages: List<Page>, warnings: MutableList<String>): JsonArray {
    val http = source as? HttpSource ?: throw IllegalStateException("source is not an HttpSource; can't exercise imageRequest/interceptors")
    val scrambleHeaderNames = listOf("x-scramble-grid", "x-scramble-seed", "x-scramble-algo", "x-enc-seed", "x-enc-algo")
    val digests = mutableListOf<String>()
    var singleColour = 0
    val images = buildJsonArray {
        // As the reader (HttpPageLoader): fresh Page objects from the list, getImageUrl() only when
        // imageUrl is null or empty, then HttpSource.getImage() (cacheless client + awaitSuccess).
        pages.take(2).map { Page(it.index, it.url, it.imageUrl) }.forEach { page ->
            if (page.imageUrl.isNullOrEmpty()) page.imageUrl = http.getImageUrl(page)
            http.getImage(page).use { response ->
                val url = response.request.url
                val bytes = response.body.bytes()
                check(bytes.size >= 1024) { "body only ${bytes.size} bytes for $url" }
                digests += sha256(bytes)
                KNOWN_PLACEHOLDERS[digests.last()]?.let { throw IllegalStateException("$url is $it, not a page") }
                // AVIF/HEIF: no ImageIO reader on the JVM; the app decodes them, so check the ftyp brand instead.
                val brand = bytes.copyOfRange(4, 12).toString(Charsets.ISO_8859_1)
                if (brand.startsWith("ftyp") && brand.substring(4) in setOf("avif", "avis", "heic", "heix", "mif1")) {
                    add(
                        buildJsonObject {
                            put("url", url.toString())
                            put("bytes", bytes.size)
                            put("format", "${brand.substring(4)} (signature only, not decoded)")
                        },
                    )
                    return@use
                }
                val scrambleHeaders = scrambleHeaderNames.filter { response.header(it) != null }
                val iis = ImageIO.createImageInputStream(ByteArrayInputStream(bytes))
                val reader = ImageIO.getImageReaders(iis).asSequence().firstOrNull()
                val decoded = try {
                    reader?.let { it.input = iis; it.formatName to it.read(0) }
                } catch (e: Exception) {
                    // TwelveMonkeys' WebP reader fails on some valid VP8 files that Android decodes
                    // ("Coordinate out of bounds"): accept a well-formed WebP header instead.
                    webpSize(bytes)?.let { (w, h) ->
                        add(
                            buildJsonObject {
                                put("url", url.toString())
                                put("bytes", bytes.size)
                                put("format", "webp (header only; ImageIO: ${e.message})")
                                put("width", w)
                                put("height", h)
                                if (scrambleHeaders.isNotEmpty()) put("scrambleHeaders", JsonArray(scrambleHeaders.map(::JsonPrimitive)))
                            },
                        )
                        return@use
                    }
                    throw e
                } finally {
                    reader?.dispose()
                } ?: throw IllegalStateException("${bytes.size} bytes from $url don't decode as an image")
                val (format, img) = decoded
                check(img.width >= MIN_PAGE_SIDE && img.height >= MIN_PAGE_SIDE) { "$url is ${img.width}x${img.height}, too small to be a page" }
                if (isSingleColour(img)) singleColour++
                add(
                    buildJsonObject {
                        put("url", url.toString())
                        put("bytes", bytes.size)
                        put("format", format)
                        put("width", img.width)
                        put("height", img.height)
                        if (scrambleHeaders.isNotEmpty()) put("scrambleHeaders", JsonArray(scrambleHeaders.map(::JsonPrimitive)))
                    },
                )
            }
        }
    }
    // Two different pages with byte-identical images: a host's placeholder (e.g. imgbox's
    // "Thumbnail Temporarily Unavailable"), not the chapter.
    if (digests.size >= 2 && digests.distinct().size == 1) {
        throw IllegalStateException("pages ${pages[0].index} and ${pages[1].index} returned the same image (sha256 ${digests[0].take(16)}), a placeholder rather than pages")
    }
    check(images.size >= minOf(2, pages.size)) { "only ${images.size}/2 page images downloaded" }
    // One blank page happens (chapter separators); every page we loaded being one flat colour is a placeholder.
    if (singleColour > 0 && singleColour == images.size) throw IllegalStateException("page images are a single flat colour, a placeholder rather than pages")
    if (singleColour > 0) warnings += "$singleColour of ${images.size} page images is a single flat colour"
    return images
}
