package dev.jinseop.agentkit.http

import com.fasterxml.jackson.databind.json.JsonMapper
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.TreeMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * 실제로 보낼 요청 하나. uri 는 엔드포인트 query 병합이 끝난 것, headers 는 엔드포인트 헤더(기본 헤더를 덮어씀),
 * secretParams 는 로그·에러 메시지에서 값을 *** 로 가릴 쿼리 파라미터 이름(대소문자 무시).
 */
data class HttpTarget(
    val uri: URI,
    val headers: Map<String, String> = emptyMap(),
    val secretParams: Set<String> = emptySet(),
)

/**
 * http_get 의 요청·응답 처리(프레임워크 비의존 순수 로직).
 * - JDK HttpClient, 리다이렉트는 수동(Redirect.NEVER): 3xx + Location 이면 resolveRedirect 로 허용 여부를 묻고 최대 MAX_REDIRECTS 회 따라간다.
 * - 바디는 maxBytes 까지만 받는다(스트림 제한). 연결·요청 타임아웃은 timeout(홉마다).
 * - 문자셋(Content-Type → HTML meta → UTF-8), JSON compact / HTML 텍스트 추출 / 텍스트 공백 정리, maxChars 잘림.
 * - 비 2xx 는 예외가 아니라 일반 출력("HTTP 404 Not Found\n본문 앞 500자")으로 돌려 모델이 URL 을 고칠 수 있게 한다.
 */
class HttpFetcher(
    private val timeout: Duration = Duration.ofSeconds(5),
    private val maxBytes: Int = 1_048_576,
    private val maxChars: Int = 6000,
    private val client: HttpClient = defaultClient(timeout),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** first 를 요청하고, 리다이렉트는 resolveRedirect(절대 URL) 가 돌려준 대상으로만 따라간다(null = 허용 안 됨 → 예외). */
    fun fetch(first: HttpTarget, resolveRedirect: (URI) -> HttpTarget?): String {
        var target = first
        var redirects = 0
        while (true) {
            val res = send(target)
            if (res.status in REDIRECT_STATUSES && res.location != null) {
                if (redirects >= MAX_REDIRECTS) {
                    throw IllegalStateException("리다이렉트가 ${MAX_REDIRECTS}회를 넘었어요: ${maskQuery(target.uri, target.secretParams)}")
                }
                val next = try {
                    target.uri.resolve(parseUrl(res.location))
                } catch (e: IllegalArgumentException) {
                    throw IllegalStateException("리다이렉트 주소를 해석할 수 없어요: ${res.location.take(200)}")
                }
                target = resolveRedirect(next)
                    ?: throw IllegalArgumentException("리다이렉트 대상이 허용되지 않은 호스트예요: ${next.host ?: maskQuery(next, target.secretParams)}")
                redirects++
                continue
            }
            return render(res.status, res.contentType, res.body, res.truncated)
        }
    }

    private class Received(val status: Int, val contentType: String?, val location: String?, val body: ByteArray, val truncated: Boolean)

    private fun send(target: HttpTarget): Received {
        val masked = maskQuery(target.uri, target.secretParams)
        val request = buildRequest(target)
        val started = System.nanoTime()
        val future = client.sendAsync(request) { LimitedBodySubscriber(maxBytes) }
        val res = try {
            future.get(timeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            throw IllegalStateException(timeoutMessage(masked))
        } catch (e: ExecutionException) {
            val cause = e.cause ?: e
            if (cause is HttpTimeoutException) throw IllegalStateException(timeoutMessage(masked), cause)
            throw IllegalStateException("연결에 실패했어요: ${target.uri.host} (${causeName(cause)})", cause)
        } catch (e: InterruptedException) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            throw IllegalStateException("요청이 중단됐어요: $masked", e)
        }
        val body = res.body()
        log.debug("http_get url={} status={} bytes={}{} took={}ms", masked, res.statusCode(), body.bytes.size,
            if (body.truncated) "(cut)" else "", (System.nanoTime() - started) / 1_000_000)
        return Received(
            status = res.statusCode(),
            contentType = res.headers().firstValue("content-type").orElse(null),
            location = res.headers().firstValue("location").orElse(null),
            body = body.bytes,
            truncated = body.truncated,
        )
    }

    private fun buildRequest(t: HttpTarget): HttpRequest {
        val builder = HttpRequest.newBuilder(requestUri(t.uri)).timeout(timeout).GET()
        val headers = TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER)
        headers.putAll(DEFAULT_HEADERS)
        headers.putAll(t.headers)
        headers.forEach { (k, v) ->
            try {
                builder.setHeader(k, v)
            } catch (e: IllegalArgumentException) {
                log.warn("http_get header skipped: {} ({})", k, e.message)
            }
        }
        return builder.build()
    }

    private fun timeoutMessage(maskedUrl: String) = "요청 시간이 초과됐어요(${seconds(timeout)}초): $maskedUrl"

    /** 응답 하나를 툴 출력 텍스트로. 2xx 는 maxChars 잘림(+바이트 상한 안내), 그 외는 "HTTP 상태 사유\n본문 앞 500자". */
    internal fun render(status: Int, contentType: String?, body: ByteArray, bytesTruncated: Boolean): String {
        val text = convert(contentType, body).trim()
        if (status !in 200..299) {
            val head = "HTTP $status" + (REASONS[status]?.let { " $it" } ?: "")
            if (text.isEmpty()) return head
            return "$head\n" + safeTake(text, ERROR_BODY_CHARS) + if (text.length > ERROR_BODY_CHARS) "…" else ""
        }
        if (text.isEmpty()) return "(빈 응답, HTTP $status)"
        var out = if (text.length > maxChars) safeTake(text, maxChars) + "\n…(총 ${text.length}자 중 ${maxChars}자까지 표시)" else text
        if (bytesTruncated) out += "\n…(응답이 ${maxBytes}바이트를 넘어 앞부분만 읽었어요)"
        return out
    }

    /** LimitedBodySubscriber 결과. truncated = maxBytes 를 넘는 바이트가 더 있었음(읽지 않고 연결을 끊음). */
    private class Body(val bytes: ByteArray, val truncated: Boolean)

    /** maxBytes 까지만 모으고 넘치면 구독을 취소한다(큰 응답을 메모리에 다 올리지 않음). */
    private class LimitedBodySubscriber(private val max: Int) : HttpResponse.BodySubscriber<Body> {
        private val out = ByteArrayOutputStream()
        private val result = CompletableFuture<Body>()
        @Volatile private var subscription: Flow.Subscription? = null

        override fun getBody(): CompletionStage<Body> = result

        override fun onSubscribe(s: Flow.Subscription) {
            subscription = s
            s.request(Long.MAX_VALUE)
        }

        override fun onNext(items: List<ByteBuffer>) {
            if (result.isDone) return
            for (buf in items) {
                val n = minOf(max - out.size(), buf.remaining())
                if (n > 0) {
                    val chunk = ByteArray(n)
                    buf.get(chunk)
                    out.write(chunk, 0, n)
                }
                if (buf.hasRemaining()) {
                    result.complete(Body(out.toByteArray(), truncated = true))
                    subscription?.cancel()
                    return
                }
            }
        }

        override fun onError(t: Throwable) {
            result.completeExceptionally(t)
        }

        override fun onComplete() {
            result.complete(Body(out.toByteArray(), truncated = false))
        }
    }

    companion object {
        const val MAX_REDIRECTS = 3
        const val ERROR_BODY_CHARS = 500
        const val USER_AGENT = "agentkit/1.0"
        val DEFAULT_HEADERS = mapOf("User-Agent" to USER_AGENT, "Accept" to "application/json, text/html;q=0.9, text/*;q=0.8")
        private val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)

        private val JSON = JsonMapper.builder().build()

        fun defaultClient(timeout: Duration): HttpClient = HttpClient.newBuilder()
            .connectTimeout(timeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .version(HttpClient.Version.HTTP_1_1) // 평문 http 에서 h2c 업그레이드 시도로 서버가 오동작하는 일을 피한다
            .build()

        // ---- URL

        /**
         * 모델이 준 URL 을 관대하게 파싱한다: 공백·한글 등 URL 에 쓸 수 없는 문자는 UTF-8 퍼센트 인코딩하고(기존 %XX 는 유지),
         * 쿼리의 [ ] 도 인코딩한다. 그래도 안 되면 IllegalArgumentException.
         */
        fun parseUrl(raw: String): URI {
            val s = raw.trim()
            val q = s.indexOf('?')
            val fixed = if (q < 0) encodeIllegal(s, "") else encodeIllegal(s.substring(0, q), "") + "?" + encodeIllegal(s.substring(q + 1), "[]")
            return try {
                URI(fixed)
            } catch (e: URISyntaxException) {
                throw IllegalArgumentException("올바른 URL 이 아니에요: ${s.take(200)}")
            }
        }

        private const val URL_ILLEGAL = "\"<>\\^`{|}"

        private fun encodeIllegal(s: String, extra: String): String {
            val sb = StringBuilder()
            var i = 0
            while (i < s.length) {
                val cp = s.codePointAt(i)
                val n = Character.charCount(cp)
                // 제어문자·공백·비ASCII 는 항상, 그 외 URL 에 못 쓰는 ASCII 와 extra 는 해당 부분에서만 인코딩
                val bad = cp <= 0x20 || cp >= 0x7F || URL_ILLEGAL.indexOf(cp.toChar()) >= 0 || extra.indexOf(cp.toChar()) >= 0
                if (bad) {
                    String(Character.toChars(cp)).toByteArray(StandardCharsets.UTF_8).forEach { b -> sb.append('%').append("%02X".format(b.toInt() and 0xFF)) }
                } else {
                    sb.appendCodePoint(cp)
                }
                i += n
            }
            return sb.toString()
        }

        /** params 를 쿼리에 병합한다. 같은 이름(대소문자 무시)의 기존 파라미터는 지우고 서버 값으로 덮어쓴다. */
        fun mergeQuery(uri: URI, params: Map<String, String>): URI {
            if (params.isEmpty()) return uri
            val keys = params.keys.map { it.lowercase() }.toSet()
            val kept = uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() && decode(it.substringBefore('=')).lowercase() !in keys }
            val added = params.map { (k, v) -> encodeComponent(k) + "=" + encodeValue(v) }
            val fragment = uri.rawFragment?.let { "#$it" } ?: ""
            return URI(base(uri) + "?" + (kept + added).joinToString("&") + fragment)
        }

        /** 로그·에러 메시지용 URL: secretParams(대소문자 무시) 값은 ***, fragment 는 뺀다. */
        fun maskQuery(uri: URI, secretParams: Set<String>): String {
            val query = uri.rawQuery ?: return base(uri)
            if (secretParams.isEmpty()) return base(uri) + "?" + query
            val keys = secretParams.map { it.lowercase() }.toSet()
            val masked = query.split('&').joinToString("&") { p ->
                val k = p.substringBefore('=')
                if (p.contains('=') && decode(k).lowercase() in keys) "$k=***" else p
            }
            return base(uri) + "?" + masked
        }

        private fun base(uri: URI) = "${uri.scheme}://${uri.rawAuthority}${uri.rawPath.orEmpty()}"

        /** 실제 요청 URI: fragment 제거, 빈 경로는 "/". */
        private fun requestUri(uri: URI): URI =
            URI(base(uri).let { if (uri.rawPath.isNullOrEmpty()) "$it/" else it } + (uri.rawQuery?.let { "?$it" } ?: ""))

        private fun decode(s: String): String = try {
            URLDecoder.decode(s, StandardCharsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            s
        }

        private fun encodeComponent(s: String): String = URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20")

        private val PRE_ENCODED = Regex("(?:[A-Za-z0-9._~-]|%[0-9A-Fa-f]{2})+")

        /**
         * 이미 퍼센트 인코딩된 값(% 가 들어 있고 그 외 안전 문자만 있는 값)은 그대로 두고,
         * 아니면 인코딩한다(+ / = 는 %2B %2F %3D 로). 두 번 인코딩돼 인증이 깨지는 흔한 실수를 막는다.
         */
        internal fun encodeValue(v: String): String = if (v.contains('%') && PRE_ENCODED.matches(v)) v else encodeComponent(v)

        // ---- 콘텐츠 변환

        private enum class Kind { JSON, HTML, TEXT, BINARY }

        private fun kindOf(mime: String): Kind? = when {
            mime.isEmpty() -> null
            mime == "application/json" || mime == "text/json" || mime.endsWith("+json") -> Kind.JSON
            mime == "text/html" || mime == "application/xhtml+xml" -> Kind.HTML
            mime.startsWith("text/") || mime == "application/xml" || mime.endsWith("+xml") -> Kind.TEXT
            else -> Kind.BINARY
        }

        /** Content-Type 이 없을 때: NUL 바이트면 바이너리, {/[ 로 시작하면 JSON, <html 이 있으면 HTML, 아니면 텍스트. */
        private fun sniff(body: ByteArray): Kind {
            val n = minOf(body.size, 1024)
            if ((0 until n).any { body[it].toInt() == 0 }) return Kind.BINARY
            val head = String(body, 0, n, StandardCharsets.UTF_8).trimStart('﻿', ' ', '\t', '\r', '\n')
            return when {
                head.startsWith("{") || head.startsWith("[") -> Kind.JSON
                head.contains("<html", ignoreCase = true) || head.startsWith("<!doctype html", ignoreCase = true) -> Kind.HTML
                else -> Kind.TEXT
            }
        }

        internal fun convert(contentType: String?, body: ByteArray): String {
            val mime = contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
            return when (kindOf(mime) ?: sniff(body)) {
                Kind.JSON -> compactJson(decodeBody(body, contentType, html = false))
                Kind.HTML -> htmlToText(decodeBody(body, contentType, html = true))
                Kind.TEXT -> tidy(decodeBody(body, contentType, html = false), dropBlankLines = false)
                Kind.BINARY -> "지원하지 않는 콘텐츠 타입이에요: ${mime.ifEmpty { "(알 수 없음)" }}"
            }
        }

        private val CT_CHARSET = Regex("""(?i)charset\s*=\s*["']?\s*([A-Za-z0-9._:\-]+)""")
        private val META_CHARSET = Regex("""(?i)<meta[^>]+?charset\s*=\s*["']?\s*([A-Za-z0-9._:\-]+)""")
        private val KOREAN_CHARSETS = setOf("euc-kr", "euckr", "ks_c_5601-1987", "ks_c_5601", "ksc5601", "ksc_5601", "cp949", "ms949", "windows-949", "x-windows-949")

        /** 문자셋: Content-Type 의 charset → (HTML 이면) 앞 2KB 의 <meta charset> / <meta http-equiv ... charset=> → UTF-8. */
        internal fun decodeBody(body: ByteArray, contentType: String?, html: Boolean): String {
            val cs = contentType?.let { ct -> CT_CHARSET.find(ct)?.groupValues?.get(1)?.let(::charsetOf) }
                ?: if (html) META_CHARSET.find(String(body, 0, minOf(body.size, 2048), StandardCharsets.ISO_8859_1))?.groupValues?.get(1)?.let(::charsetOf) else null
            return String(body, cs ?: StandardCharsets.UTF_8).removePrefix("﻿")
        }

        /** EUC-KR 계열은 상위 호환인 MS949(x-windows-949)로 읽는다(EUC-KR 로 선언하고 확장 한글을 쓰는 사이트 대응). */
        private fun charsetOf(name: String): Charset? {
            val n = name.trim().lowercase()
            val candidates = if (n in KOREAN_CHARSETS) listOf("x-windows-949", "EUC-KR") else listOf(n)
            for (c in candidates) {
                try {
                    return Charset.forName(c)
                } catch (e: Exception) {
                    // 다음 후보
                }
            }
            return null
        }

        /** 파싱 가능한 JSON 이면 문자열 밖 공백을 모두 제거(숫자 표기 등 원문은 그대로), 아니면 원문. */
        internal fun compactJson(text: String): String {
            val t = text.trim()
            return try {
                JSON.readTree(t)
                minifyJson(t)
            } catch (e: Exception) {
                t
            }
        }

        private fun minifyJson(t: String): String {
            val sb = StringBuilder(t.length)
            var inString = false
            var escaped = false
            for (c in t) {
                if (inString) {
                    sb.append(c)
                    when {
                        escaped -> escaped = false
                        c == '\\' -> escaped = true
                        c == '"' -> inString = false
                    }
                } else when (c) {
                    ' ', '\t', '\n', '\r' -> {}
                    '"' -> { inString = true; sb.append(c) }
                    else -> sb.append(c)
                }
            }
            return sb.toString()
        }

        private val TITLE = Regex("""(?is)<title\b[^>]*>(.*?)</title\s*>""")
        private val COMMENT = Regex("""(?s)<!--.*?-->""")
        private val INVISIBLE = Regex("""(?is)<(script|style|noscript|template|title)\b[^>]*>.*?</\1\s*>""")
        private val BLOCK = Regex(
            """(?i)<(?:br|hr|/?(?:p|div|li|ul|ol|tr|table|thead|tbody|tfoot|caption|h[1-6]|section|article|header|footer|nav|aside|main|""" +
                """blockquote|pre|dl|dt|dd|form|fieldset|legend|figure|figcaption|address|details|summary|option|body))\b[^>]*>"""
        )
        private val CELL = Regex("""(?i)</?t[dh]\b[^>]*>""")
        private val TAG = Regex("""<[A-Za-z/!?][^>]*>""")
        private val ENTITY = Regex("""&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[A-Za-z][A-Za-z0-9]{1,7});""")
        private val NAMED = mapOf(
            "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
            "middot" to "·", "hellip" to "…", "ndash" to "–", "mdash" to "—", "copy" to "©", "reg" to "®",
            "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”", "laquo" to "«", "raquo" to "»",
        )
        private val HSPACE = Regex("""[ \t\u000B\f   　]+""")

        /** 정규식만으로 HTML → 텍스트: title 추출 → 주석·script·style·noscript 제거 → 블록 태그는 줄바꿈 → 태그 제거 → 엔티티 → 공백 정리. */
        internal fun htmlToText(html: String): String {
            val title = TITLE.find(html)?.groupValues?.get(1)
                ?.let { HSPACE.replace(decodeEntities(TAG.replace(it, "")).replace('\n', ' '), " ").trim() }
                ?.ifEmpty { null }
            var s = COMMENT.replace(html, " ")
            s = INVISIBLE.replace(s, " ")
            s = BLOCK.replace(s, "\n")
            s = CELL.replace(s, " ")
            s = TAG.replace(s, "")
            s = tidy(decodeEntities(s), dropBlankLines = true)
            return if (title != null) "제목: $title\n$s".trimEnd() else s
        }

        /** &amp; &lt; &gt; &quot; &#39; &nbsp; + 숫자 엔티티(10진·16진) + 자주 쓰는 몇 개. 모르는 이름은 그대로. 한 번만 치환(&amp;lt; → &lt;). */
        internal fun decodeEntities(s: String): String = ENTITY.replace(s) { m ->
            val v = m.groupValues[1]
            when {
                v.startsWith("#x") || v.startsWith("#X") -> codePoint(v.substring(2).toIntOrNull(16)) ?: m.value
                v.startsWith("#") -> codePoint(v.substring(1).toIntOrNull()) ?: m.value
                else -> NAMED[v] ?: m.value
            }
        }

        private fun codePoint(cp: Int?): String? =
            if (cp == null || cp == 0 || !Character.isValidCodePoint(cp)) null
            else if (cp == 0xA0) " " else String(Character.toChars(cp))

        /** 줄마다 가로 공백을 하나로·양끝 trim. dropBlankLines 면 빈 줄 제거, 아니면 연속 빈 줄을 하나로. */
        internal fun tidy(text: String, dropBlankLines: Boolean): String {
            val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n').map { HSPACE.replace(it, " ").trim() }
            val out = mutableListOf<String>()
            for (l in lines) {
                if (l.isEmpty()) {
                    if (!dropBlankLines && out.isNotEmpty() && out.last().isNotEmpty()) out += l
                } else out += l
            }
            while (out.isNotEmpty() && out.last().isEmpty()) out.removeAt(out.size - 1)
            return out.joinToString("\n")
        }

        // ---- 기타

        private fun safeTake(s: String, max: Int): String {
            if (s.length <= max) return s
            var end = max
            if (end > 0 && Character.isHighSurrogate(s[end - 1])) end--
            return s.substring(0, end)
        }

        private fun seconds(d: Duration): String = BigDecimal.valueOf(d.toMillis()).movePointLeft(3).stripTrailingZeros().toPlainString()

        /**
         * 원인 클래스 이름: 기본은 HttpClient 가 던진 직접 원인(ConnectException, SSLHandshakeException 등).
         * 체인 안에 DNS 실패가 있으면 그것(UnresolvedAddressException 등)을 보여 준다 — 직접 원인만으론 거부/DNS 를 구분할 수 없다.
         */
        private fun causeName(t: Throwable): String {
            var c: Throwable? = t
            var depth = 0
            while (c != null && depth++ < 10) {
                if (c is java.nio.channels.UnresolvedAddressException || c is java.net.UnknownHostException) return c.javaClass.simpleName
                c = c.cause?.takeIf { it !== c }
            }
            return t.javaClass.simpleName
        }

        private val REASONS = mapOf(
            300 to "Multiple Choices", 301 to "Moved Permanently", 302 to "Found", 303 to "See Other", 304 to "Not Modified",
            307 to "Temporary Redirect", 308 to "Permanent Redirect",
            400 to "Bad Request", 401 to "Unauthorized", 402 to "Payment Required", 403 to "Forbidden", 404 to "Not Found",
            405 to "Method Not Allowed", 406 to "Not Acceptable", 408 to "Request Timeout", 409 to "Conflict", 410 to "Gone",
            413 to "Content Too Large", 414 to "URI Too Long", 415 to "Unsupported Media Type", 418 to "I'm a teapot",
            422 to "Unprocessable Content", 429 to "Too Many Requests",
            500 to "Internal Server Error", 501 to "Not Implemented", 502 to "Bad Gateway", 503 to "Service Unavailable",
            504 to "Gateway Timeout",
        )
    }
}
