package dev.jinseop.agentkit.http

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** 127.0.0.1 임의 포트의 JDK HttpServer. 받은 요청(경로·raw 쿼리·소문자 헤더)을 requests 에 기록한다. */
internal class TestHttpServer : AutoCloseable {
    data class Recorded(val path: String, val rawQuery: String?, val headers: Map<String, String>)

    private val pool = Executors.newCachedThreadPool()
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also {
        it.executor = pool
        it.start()
    }
    val requests = CopyOnWriteArrayList<Recorded>()
    val port: Int get() = server.address.port

    fun url(pathAndQuery: String) = "http://127.0.0.1:$port$pathAndQuery"

    /** path 는 접두 매칭이므로 테스트마다 서로 접두어가 겹치지 않는 경로를 쓴다. */
    fun handle(path: String, handler: (HttpExchange) -> Unit) {
        server.createContext(path) { ex ->
            requests += Recorded(
                ex.requestURI.rawPath,
                ex.requestURI.rawQuery,
                ex.requestHeaders.entries.associate { it.key.lowercase() to it.value.joinToString(",") },
            )
            try {
                handler(ex)
            } catch (e: Exception) {
                // 클라이언트가 먼저 끊는 경우(바이트 상한·타임아웃 테스트) 무시
            } finally {
                ex.close()
            }
        }
    }

    fun respond(path: String, status: Int, contentType: String?, body: ByteArray, headers: Map<String, String> = emptyMap()) =
        handle(path) { ex ->
            contentType?.let { ex.responseHeaders.set("Content-Type", it) }
            headers.forEach { (k, v) -> ex.responseHeaders.set(k, v) }
            ex.sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
            if (body.isNotEmpty()) ex.responseBody.use { it.write(body) }
        }

    fun respond(path: String, status: Int, contentType: String?, text: String, headers: Map<String, String> = emptyMap()) =
        respond(path, status, contentType, text.toByteArray(Charsets.UTF_8), headers)

    fun redirect(path: String, location: String, status: Int = 301) = respond(path, status, null, ByteArray(0), mapOf("Location" to location))

    override fun close() {
        server.stop(0)
        pool.shutdownNow()
    }
}
