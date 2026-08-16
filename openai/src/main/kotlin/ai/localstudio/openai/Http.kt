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

    fun postJson(url: String, body: String): Response =
        send(url, "application/json", body.toByteArray(StandardCharsets.UTF_8), streaming = false)

    /**
     * Opens a streaming POST. The caller must close the returned response —
     * the connection stays open while the model emits tokens.
     */
    fun postJsonStreaming(url: String, body: String): Response =
        send(url, "application/json", body.toByteArray(StandardCharsets.UTF_8), streaming = true)

    fun postBytes(url: String, contentType: String, body: ByteArray): Response =
        send(url, contentType, body, streaming = false)

    private fun send(url: String, contentType: String, body: ByteArray, streaming: Boolean): Response {
        val connection = (URI.create(url).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            doOutput = true
            useCaches = false
            instanceFollowRedirects = true
            setRequestProperty("Content-Type", contentType)
            setRequestProperty("Accept", if (streaming) "text/event-stream" else "application/json")
            // A long-lived SSE connection is exactly the case Android's HTTP
            // stack's keep-alive pool gets wrong: it can hand back a socket the
            // server has already started closing, and the read fails partway
            // through with "unexpected end of stream" instead of at connect
            // time. A fresh connection per streaming request sidesteps that
            // whole class of failure; a plain JSON call is short enough that
            // reuse is safe and worth keeping for it.
            if (streaming) setRequestProperty("Connection", "close")
            apiKey?.let { setRequestProperty("Authorization", "Bearer $it") }
            // Without this the whole request body is buffered in memory, which
            // matters for audio uploads.
            setFixedLengthStreamingMode(body.size)
        }

        connection.outputStream.use { it.write(body) }

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
