package ai.localstudio.openai

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.Closeable
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

/**
 * A real HTTP server speaking the OpenAI-compatible API, so the runtime is
 * exercised over an actual socket — status handling, streaming, multipart —
 * without depending on a running Ollama.
 */
class FakeOpenAiServer : Closeable {

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    val requests = mutableListOf<RecordedRequest>()

    data class RecordedRequest(val path: String, val contentType: String?, val body: ByteArray) {
        val text: String get() = String(body, StandardCharsets.UTF_8)
    }

    var chatChunks: List<String> = listOf("Привет", ", ", "мир")

    /** Real models emit tokens over seconds; a server that dumps them instantly hides timing behaviour. */
    var chatChunkDelayMs: Long = 0
    var chatStatus: Int = 200
    var chatErrorBody: String = """{"error":{"message":"model not found","type":"invalid_request_error"}}"""
    var embeddings: List<List<Float>> = listOf(listOf(0.1f, 0.2f, 0.3f))
    var transcription: String = """
        {"text":" найди мне лучшие локальные модели","language":"ru",
         "segments":[{"start":0.0,"end":2.5,"text":" найди мне лучшие локальные модели"}]}
    """.trimIndent()

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}/v1"

    init {
        server.createContext("/v1/chat/completions") { exchange ->
            record(exchange)
            if (chatStatus !in 200..299) {
                respond(exchange, chatStatus, chatErrorBody)
                return@createContext
            }
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { out ->
                out.write(": keep-alive\n\n".toByteArray())
                for (chunk in chatChunks) {
                    if (chatChunkDelayMs > 0) Thread.sleep(chatChunkDelayMs)
                    val payload = """{"choices":[{"index":0,"delta":{"content":${quote(chunk)}}}]}"""
                    out.write("data: $payload\n\n".toByteArray(StandardCharsets.UTF_8))
                    out.flush()
                }
                out.write("data: [DONE]\n\n".toByteArray())
                out.flush()
            }
        }

        server.createContext("/v1/embeddings") { exchange ->
            record(exchange)
            // Returned out of order on purpose: the client must restore it.
            val body = embeddings
                .mapIndexed { i, vector -> """{"index":$i,"embedding":[${vector.joinToString(",")}]}""" }
                .reversed()
                .joinToString(",")
            respond(exchange, 200, """{"data":[$body]}""")
        }

        server.createContext("/v1/audio/transcriptions") { exchange ->
            record(exchange)
            respond(exchange, 200, transcription)
        }

        server.start()
    }

    private fun record(exchange: HttpExchange) {
        requests += RecordedRequest(
            path = exchange.requestURI.path,
            contentType = exchange.requestHeaders.getFirst("Content-Type"),
            body = exchange.requestBody.readBytes(),
        )
    }

    private fun respond(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun quote(text: String): String = "\"" + text.replace("\"", "\\\"") + "\""

    override fun close() = server.stop(0)
}
