package dev.jinseop.agentkit.http

import org.junit.jupiter.api.AfterEach
import java.net.ServerSocket
import java.net.URI
import java.nio.charset.Charset
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HttpFetcherTest {
    private val server = TestHttpServer()

    @AfterEach
    fun close() = server.close()

    private fun fetcher(maxBytes: Int = 1_048_576, maxChars: Int = 6000, timeout: Duration = Duration.ofSeconds(5)) =
        HttpFetcher(timeout, maxBytes, maxChars)

    /** 테스트의 허용목록: 127.0.0.1 만. */
    private fun allowLocal(uri: URI): HttpTarget? = if (uri.host == "127.0.0.1") HttpTarget(uri) else null

    private fun get(path: String, f: HttpFetcher = fetcher()) = f.fetch(HttpTarget(URI(server.url(path))), ::allowLocal)

    // ---- 콘텐츠 변환

    @Test
    fun `json is re-serialized compactly keeping strings and numbers as written`() {
        server.respond(
            "/json", 200, "application/json; charset=utf-8",
            "{\n  \"base\": \"USD\",\n  \"rates\": { \"KRW\": 1393.50 },\n  \"note\": \"a \\\"b\\\"  c\",\n  \"list\": [1, 2.0e3]\n}\n",
        )
        assertEquals("""{"base":"USD","rates":{"KRW":1393.50},"note":"a \"b\"  c","list":[1,2.0e3]}""", get("/json"))

        server.respond("/problem", 200, "application/problem+json", "{oops  x}")
        assertEquals("{oops  x}", get("/problem")) // 파싱 실패 → 원문
    }

    @Test
    fun `html is reduced to text with title first and scripts styles comments entities handled`() {
        val html = """
            <!DOCTYPE html><html><head><title> Example &amp; Domain </title>
            <style>body { color: red }</style><script>var x = "<p>no</p>";</script></head>
            <body><!-- hidden comment --><noscript>enable js</noscript>
            <h1>Example Domain</h1><p>Tom &amp; Jerry &lt;3 &quot;hi&quot; &#39;q&#39; &#54620;&#xAE00;&nbsp;end &amp;lt;</p>
            <div>line<br>two <span>in</span>line</div><table><tr><td>A</td><td>B</td></tr></table></body></html>
        """.trimIndent()
        server.respond("/page", 200, "text/html; charset=UTF-8", html)
        assertEquals(
            "제목: Example & Domain\nExample Domain\nTom & Jerry <3 \"hi\" 'q' 한글 end &lt;\nline\ntwo inline\nA B",
            get("/page"),
        )
    }

    @Test
    fun `euc-kr charset from content-type header is decoded`() {
        val text = "안녕하세요, 세계! 똠방각하" // '똠' 은 EUC-KR 밖(MS949 확장) — euc-kr 라벨도 MS949 로 읽는다
        server.respond("/kr", 200, "text/plain; charset=EUC-KR", text.toByteArray(Charset.forName("x-windows-949")))
        assertEquals(text, get("/kr"))
    }

    @Test
    fun `html meta charset is sniffed when content-type has none`() {
        val cs = Charset.forName("EUC-KR")
        server.respond("/meta1", 200, "text/html", "<html><head><meta charset=\"euc-kr\"><title>한글 제목</title></head><body><p>본문 내용</p></body></html>".toByteArray(cs))
        assertEquals("제목: 한글 제목\n본문 내용", get("/meta1"))

        server.respond(
            "/meta2", 200, "text/html",
            "<html><head><meta http-equiv=\"Content-Type\" content=\"text/html; charset=EUC-KR\"></head><body>공지사항</body></html>".toByteArray(cs),
        )
        assertEquals("공지사항", get("/meta2"))

        server.respond("/meta3", 200, "text/html", "<p>기본은 UTF-8</p>")
        assertEquals("기본은 UTF-8", get("/meta3"))
    }

    @Test
    fun `text and xml keep content with whitespace tidied`() {
        server.respond("/xml", 200, "application/xml", "<response>\n    <header>\n        <resultCode>00</resultCode>\n    </header>\n\n\n\n<body/>\n</response>\n")
        assertEquals("<response>\n<header>\n<resultCode>00</resultCode>\n</header>\n\n<body/>\n</response>", get("/xml"))
    }

    @Test
    fun `binary content types are reported as normal output`() {
        server.respond("/img", 200, "image/png", byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0, 1, 2))
        assertEquals("지원하지 않는 콘텐츠 타입이에요: image/png", get("/img"))
        server.respond("/bin", 200, "application/octet-stream", byteArrayOf(1, 2, 3))
        assertEquals("지원하지 않는 콘텐츠 타입이에요: application/octet-stream", get("/bin"))
    }

    // ---- 길이 제한

    @Test
    fun `output over max-chars is cut with a note`() {
        server.respond("/long", 200, "text/plain; charset=utf-8", "가".repeat(120))
        assertEquals("가".repeat(50) + "\n…(총 120자 중 50자까지 표시)", get("/long", fetcher(maxChars = 50)))
        assertEquals("가".repeat(120), get("/long", fetcher(maxChars = 120)))
    }

    @Test
    fun `body is read only up to max-bytes`() {
        server.handle("/huge") { ex -> // chunked 로 3MB 를 흘려보낸다
            ex.responseHeaders.set("Content-Type", "text/plain")
            ex.sendResponseHeaders(200, 0)
            ex.responseBody.use { out -> val chunk = ByteArray(64 * 1024) { 'a'.code.toByte() }; repeat(48) { out.write(chunk) } }
        }
        assertEquals("a".repeat(1000) + "\n…(응답이 1000바이트를 넘어 앞부분만 읽었어요)", get("/huge", fetcher(maxBytes = 1000)))

        server.respond("/exact", 200, "text/plain", "b".repeat(1000))
        assertEquals("b".repeat(1000), get("/exact", fetcher(maxBytes = 1000))) // 딱 맞으면 안내 없음
    }

    // ---- 상태 코드

    @Test
    fun `non-2xx status is returned as normal output with the head of the body`() {
        server.respond("/missing", 404, "application/json", "{ \"error\": \"not found\" }")
        assertEquals("HTTP 404 Not Found\n{\"error\":\"not found\"}", get("/missing"))

        server.respond("/boom", 500, "text/plain", "x".repeat(600))
        assertEquals("HTTP 500 Internal Server Error\n" + "x".repeat(500) + "…", get("/boom"))

        server.respond("/empty403", 403, null, ByteArray(0))
        assertEquals("HTTP 403 Forbidden", get("/empty403"))

        server.respond("/noloc", 302, "text/plain", "moved") // Location 없는 3xx 는 따라가지 않음
        assertEquals("HTTP 302 Found\nmoved", get("/noloc"))
    }

    // ---- 리다이렉트

    @Test
    fun `redirects to allowed hosts are followed up to three times`() {
        server.redirect("/r1", "/r2?step=2", 301) // 상대 경로
        server.redirect("/r2", server.url("/r3"), 302) // 절대 URL
        server.redirect("/r3", "/final", 307)
        server.respond("/final", 200, "application/json", "{ \"ok\": true }")
        assertEquals("""{"ok":true}""", get("/r1"))
        assertEquals(listOf("/r1", "/r2", "/r3", "/final"), server.requests.map { it.path })
        assertEquals("step=2", server.requests[1].rawQuery)
    }

    @Test
    fun `more than three redirects fail`() {
        server.redirect("/loop", "/loop", 302)
        val e = assertFailsWith<IllegalStateException> { get("/loop") }
        assertTrue(e.message!!.startsWith("리다이렉트가 3회를 넘었어요: http://127.0.0.1:"), e.message)
        assertEquals(4, server.requests.size)
    }

    @Test
    fun `redirect to a host outside the allow list is refused before connecting`() {
        server.redirect("/go", "https://evil.example.com/steal?x=1", 302)
        val e = assertFailsWith<IllegalArgumentException> { get("/go") }
        assertEquals("리다이렉트 대상이 허용되지 않은 호스트예요: evil.example.com", e.message)
        assertEquals(listOf("/go"), server.requests.map { it.path })
    }

    // ---- 네트워크 오류

    @Test
    fun `slow server times out with a korean message`() {
        server.handle("/slow") { ex ->
            Thread.sleep(3000)
            ex.sendResponseHeaders(200, -1)
        }
        val started = System.currentTimeMillis()
        val e = assertFailsWith<IllegalStateException> { get("/slow?serviceKey=s3cret", fetcher(timeout = Duration.ofMillis(300))) }
        assertTrue(System.currentTimeMillis() - started < 2000, "took too long")
        assertTrue(e.message!!.startsWith("요청 시간이 초과됐어요(0.3초): http://127.0.0.1:${server.port}/slow"), e.message)
    }

    @Test
    fun `connection failure names the host and cause`() {
        val port = ServerSocket(0).use { it.localPort } // 닫힌 포트
        val e = assertFailsWith<IllegalStateException> { fetcher().fetch(HttpTarget(URI("http://127.0.0.1:$port/x")), ::allowLocal) }
        assertEquals("연결에 실패했어요: 127.0.0.1 (ConnectException)", e.message)
    }

    // ---- URL 유틸

    @Test
    fun `parseUrl percent-encodes spaces korean and brackets in query`() {
        assertEquals(
            "https://a.com/%EA%B2%80%EC%83%89?q=%EC%84%9C%EC%9A%B8%20%EB%82%A0%EC%94%A8&f%5B0%5D=1&k=%2B",
            HttpFetcher.parseUrl(" https://a.com/검색?q=서울 날씨&f[0]=1&k=%2B ").toString(),
        )
        assertFailsWith<IllegalArgumentException> { HttpFetcher.parseUrl("https://a.com/100%zz") }
    }

    @Test
    fun `mergeQuery overrides same keys case-insensitively and keeps pre-encoded keys`() {
        val uri = URI("https://a.com/x?page=2&ServiceKey=WRONG&type=xml#frag")
        assertEquals(
            "https://a.com/x?page=2&serviceKey=a%2Bb%2Fc%3D&type=json#frag",
            HttpFetcher.mergeQuery(uri, linkedMapOf("serviceKey" to "a+b/c=", "type" to "json")).toString(),
        )
        assertEquals("https://a.com?serviceKey=abc%2Bdef%3D%3D", HttpFetcher.mergeQuery(URI("https://a.com"), mapOf("serviceKey" to "abc%2Bdef%3D%3D")).toString())
        assertEquals("100%25%20off", HttpFetcher.encodeValue("100% off"))
        assertEquals(uri, HttpFetcher.mergeQuery(uri, emptyMap()))
    }
}
