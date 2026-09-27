package ai.localstudio.openai

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.Closeable
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

/**
 * A real HTTP server speaking Anthropic's Messages API shape — its own SSE
 * event types, `x-api-key`/`anthropic-version` headers instead of a bearer
 * token — so [AnthropicRuntime] is exercised over an actual socket, the
 * same way [FakeOpenAiServer] exercises [OpenAiRuntime].
 */
class FakeAnthropicServer : Closeable {

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    val requests = mutableListOf<RecordedRequest>()

    data class RecordedRequest(
        val path: String,
        val apiKey: String?,
        val apiVersion: String?,
        val body: ByteArray,
    ) {
        val text: String get() = String(body, StandardCharsets.UTF_8)
    }

    var messageChunks: List<String> = listOf("Привет", ", ", "мир")
    var stopReason: String = "end_turn"
    var messagesStatus: Int = 200
    var messagesStatusForKey: Map<String, Int> = emptyMap()
    var errorBody: String = """{"type":"error","error":{"type":"invalid_request_error","message":"model not found"}}"""
    var rateLimitBody: String = """{"type":"error","error":{"type":"rate_limit_error","message":"Number of request tokens has exceeded your rate limit."}}"""

    /** When set, the stream sends this as an "error" SSE event after the
     * first chunk instead of finishing normally — a mid-stream failure that
     * only shows up once the HTTP 200 body has already started, distinct
     * from [messagesStatus] which fails before any streaming begins. */
    var midStreamErrorType: String? = null
    var midStreamErrorMessage: String = "Overloaded"

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}/v1"

    init {
        server.createContext("/v1/messages") { exchange ->
            record(exchange)
            val status = messagesStatusForKey[apiKeyOf(exchange)] ?: messagesStatus
            if (status !in 200..299) {
                respond(exchange, status, if (status == 429) rateLimitBody else errorBody)
                return@createContext
            }
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { out ->
                fun send(event: String, data: String) {
                    out.write("event: $event\n".toByteArray(StandardCharsets.UTF_8))
                    out.write("data: $data\n\n".toByteArray(StandardCharsets.UTF_8))
                    out.flush()
                }
                send("message_start", """{"type":"message_start","message":{"id":"msg_1","role":"assistant","content":[],"usage":{"input_tokens":10,"output_tokens":0}}}""")
                send("content_block_start", """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""")
                val errorType = midStreamErrorType
                if (errorType != null) {
                    send(
                        "error",
                        """{"type":"error","error":{"type":${quote(errorType)},"message":${quote(midStreamErrorMessage)}}}""",
                    )
                    return@createContext
                }
                messageChunks.forEach { chunk ->
                    send("content_block_delta", """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":${quote(chunk)}}}""")
                }
                send("content_block_stop", """{"type":"content_block_stop","index":0}""")
                send("message_delta", """{"type":"message_delta","delta":{"stop_reason":${quote(stopReason)}},"usage":{"output_tokens":${messageChunks.size}}}""")
                send("message_stop", """{"type":"message_stop"}""")
            }
        }

        server.createContext("/v1/models") { exchange ->
            record(exchange)
            val status = messagesStatusForKey[apiKeyOf(exchange)] ?: messagesStatus
            respond(
                exchange,
                status,
                if (status in 200..299) {
                    """{"data":[{"id":"claude-opus-5"}]}"""
                } else {
                    """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}"""
                },
            )
        }

        server.start()
    }

    private fun apiKeyOf(exchange: HttpExchange): String? = exchange.requestHeaders.getFirst("x-api-key")

    private fun record(exchange: HttpExchange) {
        requests += RecordedRequest(
            path = exchange.requestURI.path,
            apiKey = exchange.requestHeaders.getFirst("x-api-key"),
            apiVersion = exchange.requestHeaders.getFirst("anthropic-version"),
            body = exchange.requestBody.readBytes(),
        )
    }

    private fun respond(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun quote(text: String): String = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    override fun close() = server.stop(0)
}
