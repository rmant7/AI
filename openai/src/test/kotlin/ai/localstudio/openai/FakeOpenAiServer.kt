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

    data class RecordedRequest(
        val path: String,
        val contentType: String?,
        val authorization: String?,
        val body: ByteArray,
    ) {
        val text: String get() = String(body, StandardCharsets.UTF_8)
    }

    var chatChunks: List<String> = listOf("Привет", ", ", "мир")

    /** Real models emit tokens over seconds; a server that dumps them instantly hides timing behaviour. */
    var chatChunkDelayMs: Long = 0
    var chatStatus: Int = 200
    var chatErrorBody: String = """{"error":{"message":"model not found","type":"invalid_request_error"}}"""

    /**
     * Overrides [chatStatus] for a specific `Authorization: Bearer <key>` value
     * — for testing key-pool rotation, where the same endpoint must behave
     * differently depending on which key in the pool made the request.
     */
    var chatStatusForKey: Map<String, Int> = emptyMap()
    var quotaErrorBody: String = """{"error":{"message":"Resource has been exhausted (e.g. check quota).","type":"rate_limit_exceeded"}}"""

    var modelsStatus: Int = 200
    var modelsStatusForKey: Map<String, Int> = emptyMap()

    var embeddings: List<List<Float>> = listOf(listOf(0.1f, 0.2f, 0.3f))
    var transcription: String = """
        {"text":" найди мне лучшие локальные модели","language":"ru",
         "segments":[{"start":0.0,"end":2.5,"text":" найди мне лучшие локальные модели"}]}
    """.trimIndent()

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}/v1"

    init {
        server.createContext("/v1/chat/completions") { exchange ->
            record(exchange)
            val status = chatStatusForKey[bearerKeyOf(exchange)] ?: chatStatus
            if (status !in 200..299) {
                respond(exchange, status, if (status == 429) quotaErrorBody else chatErrorBody)
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

        server.createContext("/v1/models") { exchange ->
            record(exchange)
            val status = modelsStatusForKey[bearerKeyOf(exchange)] ?: modelsStatus
            respond(
                exchange,
                status,
                if (status in 200..299) {
                    """{"data":[{"id":"stub-model"}]}"""
                } else {
                    """{"error":{"message":"invalid api key","type":"invalid_request_error"}}"""
                },
            )
        }

        server.start()
    }

    private fun bearerKeyOf(exchange: HttpExchange): String? =
        exchange.requestHeaders.getFirst("Authorization")?.removePrefix("Bearer ")

    private fun record(exchange: HttpExchange) {
        requests += RecordedRequest(
            path = exchange.requestURI.path,
            contentType = exchange.requestHeaders.getFirst("Content-Type"),
            authorization = exchange.requestHeaders.getFirst("Authorization"),
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
