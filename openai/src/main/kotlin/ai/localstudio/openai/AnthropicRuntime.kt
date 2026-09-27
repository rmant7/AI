package ai.localstudio.openai

import ai.localstudio.core.keys.ApiKeyRotator
import ai.localstudio.core.model.ImageRef
import ai.localstudio.core.registry.ModelDescriptor
import ai.localstudio.core.registry.RuntimeBinding
import ai.localstudio.core.registry.RuntimeKind
import ai.localstudio.core.runtime.GenerationRequest
import ai.localstudio.core.runtime.LoadedModel
import ai.localstudio.core.runtime.ModelLoadException
import ai.localstudio.core.runtime.ModelRuntime
import ai.localstudio.core.runtime.TextModelHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

data class AnthropicConfig(
    val baseUrl: String = "https://api.anthropic.com/v1",
    val apiKey: String? = null,
    /** Same reasoning as [OpenAiConfig.keyRotator] — rotates on a 429 rather than failing the turn outright. */
    val keyRotator: ApiKeyRotator? = null,
    val connectTimeoutMs: Int = 15_000,
    val readTimeoutMs: Int = 300_000,
    /**
     * Anthropic versions its wire protocol independently of any model —
     * required on every request. Bumping this is a deliberate, tested
     * change, not something a caller overrides per request.
     */
    val apiVersion: String = "2023-06-01",
)

/**
 * Anthropic's own error shape: `{"type":"error","error":{"type":"...","message":"..."}}`.
 * Implements [ai.localstudio.core.errors.HttpStatusError] the same way
 * [OpenAiException] does, so [ai.localstudio.core.errors.AIErrorClassifier]
 * classifies an Anthropic failure exactly like any other provider's —
 * without `core` (or this classifier) knowing anything Anthropic-specific.
 */
class AnthropicException(override val status: Int, override val body: String) : Exception(
    "Anthropic API returned HTTP $status: ${describe(body)}",
), ai.localstudio.core.errors.HttpStatusError {
    private companion object {
        fun describe(body: String): String = runCatching {
            Json.parseToJsonElement(body).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
        }.getOrNull() ?: body.take(300).ifBlank { "no response body" }
    }
}

/**
 * Runs models on Anthropic's own Messages API (`/v1/messages`) — a genuinely
 * different wire protocol from the OpenAI-compatible one [OpenAiRuntime]
 * speaks (`x-api-key` instead of a bearer token, `system` as its own
 * top-level field rather than a system-role message, content as typed
 * blocks, its own SSE event shapes), not a config variant of it — unlike
 * GigaChat, which only needed a different token exchange in front of the
 * same request/response shape.
 *
 * Deliberately does not reuse [HttpTransport]: that class hardcodes a
 * bearer-token `Authorization` header and throws [OpenAiException]
 * specifically, neither of which fits here, and modifying a class every
 * other provider in this module already depends on to accommodate one
 * more provider's different auth scheme is a worse trade than a second,
 * small, self-contained HTTP client with the exact same connection-handling
 * shape (fresh connection per streaming request, fixed-length streaming
 * mode, read the error body before throwing).
 */
class AnthropicRuntime(private val config: AnthropicConfig) : ModelRuntime {

    override val kind: RuntimeKind = RuntimeKind.REMOTE_ANTHROPIC

    override fun canRun(model: ModelDescriptor, binding: RuntimeBinding): Boolean =
        binding.runtime == RuntimeKind.REMOTE_ANTHROPIC

    override suspend fun load(model: ModelDescriptor, binding: RuntimeBinding): LoadedModel {
        if (!canRun(model, binding)) {
            throw ModelLoadException("${binding.runtime.id} binding cannot run on the Anthropic runtime")
        }
        val remoteName = binding.artifact.ifBlank { model.id }
        return RemoteTextModel(model.id, remoteName)
    }

    private fun url(path: String) = "${config.baseUrl.trimEnd('/')}$path"

    private inner class RemoteTextModel(
        override val modelId: String,
        private val remoteName: String,
    ) : TextModelHandle {

        private val cancelled = AtomicBoolean(false)
        override val ramBytes: Long = 0

        override fun generate(request: GenerationRequest): Flow<String> = flow {
            cancelled.set(false)
            val body = buildJsonObject {
                put("model", remoteName)
                // GenerationRequest.maxTokens is deliberately NOT forwarded —
                // same reasoning as OpenAiRuntime's own generate(): it's tuned
                // for a local quantized model's own settings screen, not for
                // whatever this cloud provider's own sane default should be.
                // max_tokens is REQUIRED on every Anthropic request, unlike
                // OpenAI's optional one, so a fixed, generous ceiling stands
                // in for "the provider's own default."
                put("max_tokens", MAX_TOKENS_CEILING)
                request.systemPrompt?.let { put("system", it) }
                put("stream", true)
                if (request.stopSequences.isNotEmpty()) {
                    put("stop_sequences", buildJsonArray { request.stopSequences.forEach { add(JsonPrimitive(it)) } })
                }
                put(
                    "messages",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("role", "user")
                                put("content", userContent(request.prompt, request.images))
                            },
                        )
                    },
                )
            }.toString()

            var emittedAny = false
            var ioRetries = 0
            val rotator = config.keyRotator?.takeIf { it.hasAnyKey() }
            while (true) {
                val keyEntry = rotator?.activeKey()
                if (rotator != null && keyEntry == null) {
                    throw ModelLoadException(
                        rotator.exhaustionMessage() ?: "No available API keys for this provider",
                    )
                }
                try {
                    val key = keyEntry?.key ?: config.apiKey
                    var truncatedByLength = false
                    postStreaming(url("/messages"), body, key).use { response ->
                        val reader = response.reader()
                        while (true) {
                            if (cancelled.get()) return@use
                            val line = reader.readLine() ?: return@use
                            if (!line.startsWith("data:")) continue
                            val payload = line.removePrefix("data:").trim()
                            if (payload.isEmpty()) continue
                            val event = runCatching { Json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: continue
                            when (event["type"]?.jsonPrimitive?.content) {
                                "content_block_delta" -> {
                                    val delta = event["delta"]?.jsonObject
                                    if (delta?.get("type")?.jsonPrimitive?.content == "text_delta") {
                                        delta["text"]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() }?.let {
                                            emittedAny = true
                                            emit(it)
                                        }
                                    }
                                }
                                // The final stop_reason for the whole message —
                                // "max_tokens" is Anthropic's own output-length
                                // cap, the same failure mode Groq's free tier
                                // hits (see OpenAiRuntime's own truncatedByLength):
                                // the stream still ends cleanly, with nothing
                                // else to distinguish a genuinely complete
                                // answer from one cut off mid-thought.
                                "message_delta" -> {
                                    if (event["delta"]?.jsonObject?.get("stop_reason")?.jsonPrimitive?.content == "max_tokens") {
                                        truncatedByLength = true
                                    }
                                }
                                "error" -> {
                                    // A mid-stream error event (HTTP 200, the
                                    // failure only shows up once the SSE body
                                    // starts) — e.g. "overloaded_error" once
                                    // generation has already begun. Surfaced
                                    // the same way a pre-stream HTTP failure
                                    // is, through the same AnthropicException/
                                    // AIErrorClassifier path (which reads its
                                    // own message back out of [payload], the
                                    // same shape a real HTTP error body has),
                                    // rather than a silently truncated answer.
                                    val errorType = event["error"]?.jsonObject?.get("type")?.jsonPrimitive?.content
                                    throw AnthropicException(streamErrorStatus(errorType), payload)
                                }
                            }
                        }
                    }
                    if (truncatedByLength) {
                        throw IOException("the model reached its own output-length limit and the response was cut off")
                    }
                    break
                } catch (e: AnthropicException) {
                    val classified = ai.localstudio.core.errors.AIErrorClassifier.classify(e)
                    if (rotator != null && keyEntry != null &&
                        ai.localstudio.core.errors.FallbackPolicy.shouldRotateKey(classified.code) && !emittedAny
                    ) {
                        val cooldownMs = classified.retryAfterMs ?: ApiKeyRotator.DEFAULT_COOLDOWN_MS
                        rotator.markExhausted(keyEntry.id, cooldownMs)
                        continue
                    }
                    throw e
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Same reasoning as OpenAiRuntime's own catch-all: a
                    // dropped/reset connection is common enough on mobile
                    // networks that one retry beats failing the whole turn,
                    // as long as nothing has reached the caller yet.
                    if (emittedAny || cancelled.get() || ioRetries >= STREAM_RETRY_LIMIT) throw e
                    ioRetries++
                }
            }
        }.flowOn(Dispatchers.IO)

        /**
         * Plain text stays a bare string; an attached image switches to
         * Anthropic's own content-blocks array — `{type:"image",
         * source:{type:"base64", media_type, data}}`, not OpenAI's
         * `image_url` shape. [ImageRef.uri] is always a
         * `"data:<mime>;base64,<payload>"` string (see
         * [ai.localstudio.app.ChatActivity.attachImage]'s own doc comment),
         * so this only ever re-splits a string this app already built —
         * never re-encodes image bytes.
         */
        private fun userContent(text: String, images: List<ImageRef>): JsonArray = buildJsonArray {
            images.forEach { image ->
                val mime = image.uri.substringAfter("data:").substringBefore(";base64,")
                val payload = image.uri.substringAfter(",", "")
                if (payload.isNotEmpty()) {
                    add(
                        buildJsonObject {
                            put("type", "image")
                            put(
                                "source",
                                buildJsonObject {
                                    put("type", "base64")
                                    put("media_type", mime.ifBlank { "image/jpeg" })
                                    put("data", payload)
                                },
                            )
                        },
                    )
                }
            }
            add(buildJsonObject { put("type", "text"); put("text", text) })
        }

        override fun requestCancel() {
            cancelled.set(true)
        }

        override fun close() = Unit
    }

    /**
     * A stand-in for a mid-stream error's own HTTP-status equivalent, read
     * from Anthropic's own `error.type` field rather than a real status code
     * (the connection already returned 200 by the time an "error" SSE event
     * arrives) — mapped only as precisely as [ai.localstudio.core.errors.AIErrorClassifier]
     * actually branches on (401/403/404/400/413/422/429/5xx), so the right
     * [ai.localstudio.core.errors.AIErrorCode] still comes out the other end.
     */
    private fun streamErrorStatus(errorType: String?): Int = when (errorType) {
        "authentication_error", "permission_error" -> 401
        "not_found_error" -> 404
        "invalid_request_error" -> 400
        "request_too_large" -> 413
        "rate_limit_error" -> 429
        "overloaded_error", "api_error" -> 529
        else -> 500
    }

    /**
     * A small, self-contained HTTP client — see this class's own doc
     * comment on why it doesn't reuse [HttpTransport]. Mirrors its
     * connection-handling shape (fresh connection per streaming request,
     * `Connection: close` to sidestep Android's keep-alive pool returning
     * an already-closing socket, fixed-length streaming mode for the
     * request body) with Anthropic's own headers and [AnthropicException].
     */
    private fun postStreaming(url: String, body: String, apiKey: String?): AnthropicResponse {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val connection = (URI.create(url).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = config.connectTimeoutMs
            readTimeout = config.readTimeoutMs
            useCaches = false
            instanceFollowRedirects = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "text/event-stream")
            setRequestProperty("Connection", "close")
            setRequestProperty("anthropic-version", config.apiVersion)
            apiKey?.let { setRequestProperty("x-api-key", it) }
            doOutput = true
            // Deliberately NOT setFixedLengthStreamingMode: this body is a
            // short JSON chat request, not a multi-megabyte upload, so there
            // is no memory-buffering reason to reach for it — and it is
            // actively harmful here. HttpURLConnection special-cases 401/407
            // as authentication challenges; with streaming mode enabled it
            // cannot buffer the request for a possible retry, so for those
            // two statuses specifically it silently discards the response
            // body and getErrorStream() returns null (verified against this
            // exact JDK). A bad API key is exactly the 401 case a caller
            // most needs the real message for, so a plain buffered write —
            // which keeps the error body intact for every status — is worth
            // the (here, negligible) extra buffering.
        }
        connection.outputStream.use { it.write(bytes) }

        val status = connection.responseCode
        return if (status in 200..299) {
            AnthropicResponse(connection, connection.inputStream)
        } else {
            val error = connection.errorStream?.readAllText().orEmpty()
            connection.disconnect()
            throw AnthropicException(status, error)
        }
    }

    private class AnthropicResponse(
        private val connection: HttpURLConnection,
        private val stream: InputStream,
    ) : AutoCloseable {
        fun reader(): BufferedReader = stream.bufferedReader(StandardCharsets.UTF_8)
        override fun close() {
            runCatching { stream.close() }
            connection.disconnect()
        }
    }

    private fun InputStream.readAllText(): String = bufferedReader(StandardCharsets.UTF_8).use { it.readText() }

    private companion object {
        /** One retry: enough for a transient reset, not enough to mask a real outage. */
        const val STREAM_RETRY_LIMIT = 1

        /**
         * Anthropic requires max_tokens on every request, unlike OpenAI's
         * optional one — this stands in for "the provider's own sane
         * default" the same way omitting the field does for OpenAiRuntime.
         * Comfortably above what any of this app's own generation settings
         * (maxResponseTokens) would ask a local model for.
         */
        const val MAX_TOKENS_CEILING = 8192
    }
}
