package ai.localstudio.openai

import java.io.BufferedReader
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets

/**
 * The HTTP layer, deliberately built on [HttpURLConnection].
 *
 * `java.net.http.HttpClient` would be nicer, and it is what this module used
 * first — but it does not exist on Android at any API level, so a module built
 * on it can only ever run on the desktop. This module has to run in both
 * places: the same runtime serves development against Ollama and a phone
 * pointed at a server on the same network.
 */
internal class HttpTransport(
    private val connectTimeoutMs: Int,
    private val readTimeoutMs: Int,
    private val apiKey: String?,
) {

    fun postJson(url: String, body: String, apiKeyOverride: String? = null): Response =
        send(url, "POST", "application/json", body.toByteArray(StandardCharsets.UTF_8), streaming = false, apiKeyOverride)

    /**
     * Opens a streaming POST. The caller must close the returned response —
     * the connection stays open while the model emits tokens.
     *
     * [apiKeyOverride], when given, is sent instead of the key this transport
     * was built with — for key-pool rotation, where the caller decides which
     * key to try on each attempt rather than fixing one for the transport's
     * whole lifetime.
     */
    fun postJsonStreaming(url: String, body: String, apiKeyOverride: String? = null): Response =
        send(url, "POST", "application/json", body.toByteArray(StandardCharsets.UTF_8), streaming = true, apiKeyOverride)

    fun postBytes(url: String, contentType: String, body: ByteArray): Response =
        send(url, "POST", contentType, body, streaming = false)

    /** A plain authenticated GET — used for key validation (`/models`), never streamed. */
    fun get(url: String): Response = send(url, "GET", contentType = null, body = null, streaming = false)

    private fun send(
        url: String,
        method: String,
        contentType: String?,
        body: ByteArray?,
        streaming: Boolean,
        apiKeyOverride: String? = null,
    ): Response {
        val connection = (URI.create(url).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            useCaches = false
            instanceFollowRedirects = true
            contentType?.let { setRequestProperty("Content-Type", it) }
            setRequestProperty("Accept", if (streaming) "text/event-stream" else "application/json")
            // A long-lived SSE connection is exactly the case Android's HTTP
            // stack's keep-alive pool gets wrong: it can hand back a socket the
            // server has already started closing, and the read fails partway
            // through with "unexpected end of stream" instead of at connect
            // time. A fresh connection per streaming request sidesteps that
            // whole class of failure; a plain JSON call is short enough that
            // reuse is safe and worth keeping for it.
            if (streaming) setRequestProperty("Connection", "close")
            (apiKeyOverride ?: apiKey)?.let { setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                doOutput = true
                // Without this the whole request body is buffered in memory,
                // which matters for audio uploads.
                setFixedLengthStreamingMode(body.size)
            }
        }

        if (body != null) connection.outputStream.use { it.write(body) }

        val status = connection.responseCode
        return if (status in 200..299) {
            Response(status, connection, connection.inputStream)
        } else {
            val error = connection.errorStream?.readAllText().orEmpty()
            connection.disconnect()
            throw OpenAiException(status, error)
        }
    }

    class Response(
        val status: Int,
        private val connection: HttpURLConnection,
        private val stream: InputStream,
    ) : AutoCloseable {

        fun text(): String = stream.readAllText().also { close() }

        /** Lines as they arrive. Consumed lazily so a token stream is not buffered whole. */
        fun reader(): BufferedReader = stream.bufferedReader(StandardCharsets.UTF_8)

        override fun close() {
            runCatching { stream.close() }
            connection.disconnect()
        }
    }
}

private fun InputStream.readAllText(): String =
    bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
