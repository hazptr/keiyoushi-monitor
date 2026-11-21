package keiyoushi.extrun

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FixturesTest {
    private val dir: File = Files.createTempDirectory("ext-run-test").toFile()

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
        Harness.offline = false
        Harness.ignoreParams.clear()
    }

    private fun get(url: String) = Request.Builder().url(url).build()

    // ---- names ----

    @Test
    fun equivalentEncodingsNameOneFixture() {
        val a = Transport.fixtureFile(dir, get("https://atsu.moe/api/infinite/trending?types=Manga%2CManwha%2cManhua&page=0"))
        val b = Transport.fixtureFile(dir, get("https://atsu.moe/api/infinite/trending?types=Manga,Manwha,Manhua&page=0"))
        assertEquals(a, b)
        assertEquals("api__infinite__trending?types=Manga,Manwha,Manhua&page=0", a.name)
        // Reserved characters keep their meaning.
        assertNotEquals(
            Transport.fixtureFile(dir, get("https://x.test/a?q=a%26b")),
            Transport.fixtureFile(dir, get("https://x.test/a?q=a&b")),
        )
        assertEquals(
            Transport.fixtureFile(dir, get("https://x.test/a?q=a:b")),
            Transport.fixtureFile(dir, get("https://x.test/a?q=a%3ab")),
        )
    }

    @Test
    fun cacheBustersLeaveTheName() {
        val plain = Transport.fixtureFile(dir, get("https://onf.test/reader_image.php?id=5"))
        assertEquals(plain, Transport.fixtureFile(dir, get("https://onf.test/reader_image.php?id=5&retry=1763340000123")))
        assertEquals(plain, Transport.fixtureFile(dir, get("https://onf.test/reader_image.php?_=1763340000&id=5")))
        assertEquals(plain, Transport.fixtureFile(dir, get("https://onf.test/reader_image.php?id=5&rnd=0.8273645123")))
        // Not a timestamp: a real parameter.
        assertNotEquals(plain, Transport.fixtureFile(dir, get("https://onf.test/reader_image.php?id=5&t=manga")))
        assertEquals("index", Transport.fixtureFile(dir, get("https://onf.test/?_=1763340000123")).name)
        Harness.ignoreParams += "sig"
        assertEquals(plain, Transport.fixtureFile(dir, get("https://onf.test/reader_image.php?id=5&sig=abc")))
    }

    @Test
    fun olderLiteralNamesStillReplay() {
        val req = get("https://atsu.moe/api/x?types=Manga%2CManwha")
        val literal = Transport.fixtureFile(dir, req, canonical = false)
        assertEquals("api__x?types=Manga%2CManwha", literal.name)
        literal.parentFile.mkdirs()
        literal.writeText("{}")
        assertEquals(literal, Transport.findFixture(dir, req))
        val canonical = Transport.fixtureFile(dir, req)
        canonical.writeText("{}")
        assertEquals(canonical, Transport.findFixture(dir, req))
    }

    @Test
    fun mapKeysAreCanonical() {
        assertEquals(Transport.mapKey("https://a.test/p?x=1%2C2&t=1763340000"), Transport.mapKey("https://a.test:443/p?x=1,2".toHttpUrl()))
    }

    // ---- --map files ----

    @Test
    fun rawHttpMapFileKeepsBinaryBody() {
        val png = ByteArray(300) { (it * 7 + 0x89).toByte() }.also { it[0] = 0x89.toByte(); it[1] = 'P'.code.toByte() }
        val f = dir.resolve("img.raw")
        f.writeBytes("HTTP/1.1 200 OK\r\nContent-Type: image/png\r\n\r\n".toByteArray() + png)
        val r = Transport.replayMap(MapEntry(f, null), "GET", "https://a.test/c.png".toHttpUrl())
        assertEquals(200, r.code)
        assertContentEquals(png, r.body)
        assertEquals("image/png", r.headers.single { it.first == "Content-Type" }.second)
    }

    @Test
    fun mapStatusAndMetaStatus() {
        val f = dir.resolve("challenge.html")
        f.writeText("<html>blocked</html>")
        assertEquals(403, Transport.replayMap(MapEntry(f, 403), "GET", "https://a.test/".toHttpUrl()).code)
        assertEquals(200, Transport.replayMap(MapEntry(f, null), "GET", "https://a.test/".toHttpUrl()).code)
        File(f.path + ".meta").writeText("""{"code":503,"contentType":"text/html"}""")
        assertEquals(503, Transport.replayMap(MapEntry(f, null), "GET", "https://a.test/".toHttpUrl()).code)
        val raw = dir.resolve("r.http")
        raw.writeText("HTTP/1.1 429 Too Many Requests\nRetry-After: 5\n\nslow down")
        val r = Transport.replayMap(MapEntry(raw, null), "GET", "https://a.test/".toHttpUrl())
        assertEquals(429, r.code)
        assertEquals("slow down", String(r.body))
    }

    // ---- secrets ----

    @Test
    fun sessionHeadersAndEchoedCookiesAreRedactedConsistently() {
        val session = "s3cr3tSessionValue0123456789abcdef"
        val token = "tok_9f8e7d6c5b4a39281706f5e4d3c2b1a0"
        val req = Request.Builder().url("https://kuro.test/api/mangas/1")
            .header("Cookie", "kuro_session=$session; _kn=$token; lang=pt")
            .header("X-Request-Ctx", token)
            .header("Authorization", "Bearer $token")
            .build()
        val resp = FixtureServer.Reply(
            200, "OK",
            listOf(
                "Set-Cookie" to "kuro_session=$session; Max-Age=604800; Path=/; HttpOnly",
                "Content-Type" to "application/json",
            ),
            """{"user":{"session":"$session"},"ok":true}""".toByteArray(),
        )
        val fixture = dir.resolve("kuro.test/api__mangas__1")
        Transport.saveWire(fixture, req, resp)
        Secrets.scrubRecorded()
        val meta = File(fixture.path + ".meta").readText()
        assertFalse(session in meta, "session in meta")
        assertFalse(token in meta, "token in meta")
        assertFalse(session in fixture.readText(), "session in body")
        assertTrue("lang=pt" in meta, "short non-secret cookie kept")
        val ph = Secrets.placeholder(token)
        assertTrue("\"X-Request-Ctx\",\"$ph\"" in meta, meta)
        assertTrue("Bearer $ph" in meta, meta)
        // Replay offline: the source sends the placeholders back, which matches the recording.
        Harness.offline = true
        val replayed = Transport.replay(fixture, "GET", req.url, legacyRedirect = true)
        val cookie = replayed.headers.single { it.first == "Set-Cookie" }.second
        assertTrue(cookie.startsWith("kuro_session=${Secrets.placeholder(session)};"), cookie)
        val again = Request.Builder().url(req.url)
            .header("Cookie", "kuro_session=${Secrets.placeholder(session)}; _kn=$ph; lang=pt")
            .header("X-Request-Ctx", ph)
            .header("Authorization", "Bearer $ph")
            .build()
        assertNull(Transport.requestMismatch(fixture, again))
        // A different session is a different request.
        val other = again.newBuilder().header("X-Request-Ctx", Secrets.placeholder("another-token-value-123456")).build()
        assertEquals("x-request-ctx (value differs)", Transport.requestMismatch(fixture, other))
        // With the network, a placeholder never goes back to the site.
        Harness.offline = false
        assertTrue(Transport.replay(fixture, "GET", req.url, legacyRedirect = true).headers.none { it.first == "Set-Cookie" })
    }

    @Test
    fun ordinaryValuesStayReadable() {
        val req = Request.Builder().url("https://w.test/a").header("Cookie", "auth_state=undefined; logged=true; lang=pt-BR").build()
        val fixture = dir.resolve("w.test/a")
        Transport.saveWire(fixture, req, FixtureServer.Reply(200, "", listOf("Set-Cookie" to "auth_state=undefined; Path=/", "Access-Control-Allow-Credentials" to "true"), "var x = undefined; true".toByteArray()))
        Secrets.scrubRecorded()
        val meta = File(fixture.path + ".meta").readText()
        assertTrue("auth_state=undefined; logged=true; lang=pt-BR" in meta, meta)
        assertTrue("\"wire\":true" in meta, meta)
        assertEquals("var x = undefined; true", fixture.readText())
    }

    @Test
    fun legacyRedactedFixturesStillMatch() {
        val fixture = dir.resolve("old.test/api")
        fixture.parentFile.mkdirs()
        fixture.writeText("{}")
        File(fixture.path + ".meta").writeText(
            """{"code":200,"url":"https://old.test/api","headers":[["Set-Cookie","sid=<redacted>; Path=/"]],"wire":true,""" +
                """"request":{"method":"GET","headers":[["Cookie","<redacted>"],["Authorization","<redacted>"]]}}""",
        )
        val req = Request.Builder().url("https://old.test/api").header("Cookie", "sid=whatever-session-1234567890").header("Authorization", "Bearer abcdefghijklmnop").build()
        assertNull(Transport.requestMismatch(fixture, req))
        Harness.offline = true
        assertEquals("sid=<redacted>; Path=/", Transport.replay(fixture, "GET", req.url, true).headers.single().second)
    }

    @Test
    fun expiredRecordedCookieGetsItsLifetime() {
        val fixture = dir.resolve("exp.test/login#POST-x")
        fixture.parentFile.mkdirs()
        fixture.writeText("{}")
        File(fixture.path + ".meta").writeText(
            """{"code":200,"url":"https://exp.test/login","wire":true,"headers":[["Date","Mon, 02 Nov 2026 10:00:00 GMT"],""" +
                """["Set-Cookie","a=1; Path=/; Expires=Mon, 09 Nov 2026 10:00:00 GMT"],["Set-Cookie","b=1; Path=/; Expires=Thu, 01 Jan 1970 00:00:00 GMT"]]}""",
        )
        val headers = Transport.replay(fixture, "POST", "https://exp.test/login".toHttpUrl(), false).headers.filter { it.first == "Set-Cookie" }
        assertTrue(headers[0].second.endsWith("; Max-Age=604800"), headers[0].second)
        assertFalse("Max-Age" in headers[1].second)
    }

    @Test
    fun multipartBodyNameUnchanged() {
        // Non-GET names keep their body hash (unchanged by canonical naming).
        val r = Request.Builder().url("https://a.test/api?x=%2C").post("q".toRequestBody()).build()
        assertTrue(Transport.fixtureFile(dir, r).name.startsWith("api?x=,#POST-"))
    }
}
