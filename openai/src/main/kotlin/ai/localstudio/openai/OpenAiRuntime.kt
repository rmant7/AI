package ai.localstudio.openai

import ai.localstudio.core.capability.Capability
import ai.localstudio.core.keys.ApiKeyRotator
import ai.localstudio.core.model.AudioRef
import ai.localstudio.core.model.ImageRef
import ai.localstudio.core.model.Transcript
import ai.localstudio.core.model.TranscriptSegment
import ai.localstudio.core.registry.ModelDescriptor
import ai.localstudio.core.registry.RuntimeBinding
import ai.localstudio.core.registry.RuntimeKind
import ai.localstudio.core.runtime.EmbeddingModelHandle
import ai.localstudio.core.runtime.GenerationRequest
import ai.localstudio.core.runtime.LoadedModel
import ai.localstudio.core.runtime.ModelLoadException
import ai.localstudio.core.runtime.ModelRuntime
import ai.localstudio.core.runtime.SpeechModelHandle
import ai.localstudio.core.runtime.TextModelHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

data class OpenAiConfig(
    /** e.g. `http://localhost:11434/v1` for Ollama, `http://localhost:8080/v1` for llama-server. */
    val baseUrl: String,
    val apiKey: String? = null,
    /**
     * When set and non-empty, text generation draws its key from here instead
     * of [apiKey] on every attempt, and rotates to the pool's next key on an
     * HTTP 429 (rate limit / daily quota exceeded) rather than failing the
     * turn outright. [apiKey] stays the fallback for providers with no pool
     * configured, and for embeddings/transcription, which this does not cover.
     */
    val keyRotator: ApiKeyRotator? = null,
    val connectTimeoutMs: Int = 15_000,
    /**
     * Generous on purpose: a large model on modest hardware can take minutes to
     * emit its first token, and a timeout here looks exactly like a broken
     * server to the user.
     */
    val readTimeoutMs: Int = 300_000,
) {
    val normalizedBaseUrl: String get() = baseUrl.trimEnd('/')
}

class OpenAiException(val status: Int, val body: String) : Exception(
    "OpenAI-compatible endpoint returned HTTP $status: ${describe(body)}",
) {
    private companion object {
        /**
         * The provider's own sentence, not its JSON.
         *
         * Two shapes, because Gemini answers with the second: `{"error":…}`
         * as the spec describes it, and `[{"error":…}]` — an array — which
         * is what a real 503 from Gemini looks like. Only the object form
         * was handled, so every Gemini failure fell through to the raw-body
         * branch and the whole JSON blob ended up quoted in the chat, inside
         * an otherwise successful answer, as the note explaining which
         * candidate had been skipped.
         */
        fun describe(body: String): String = (asObject(body) ?: asArray(body))
            ?: body.take(300).ifBlank { "no response body" }

        private fun asObject(body: String): String? = runCatching {
            openAiJson.decodeFromString(ApiErrorBody.serializer(), body).error?.message
        }.getOrNull()

        private fun asArray(body: String): String? = runCatching {
            openAiJson.decodeFromString(ListSerializer(ApiErrorBody.serializer()), body)
                .firstNotNullOfOrNull { it.error?.message }
        }.getOrNull()
    }
}

/**
 * Runs models on an OpenAI-compatible endpoint — Ollama, llama-server, or any
 * other server speaking the same API.
 *
 * This is the development-mode runtime from docs/10, and also what a phone uses
 * to reach a server on the same network. It implements the same [ModelRuntime]
 * interface as an on-device engine will, which is the whole point: nothing
 * above the runtime layer knows which one it is talking to.
 *
 * [ModelDescriptor.id] names the remote model unless the binding's `artifact`
 * overrides it, so one descriptor can name a local file on device and a served
 * model name in development.
 */
class OpenAiRuntime(private val config: OpenAiConfig) : ModelRuntime {

    private companion object {
        /** One retry: enough for a transient reset, not enough to mask a real outage. */
        const val STREAM_RETRY_LIMIT = 1
    }

    override val kind: RuntimeKind = RuntimeKind.REMOTE_OPENAI

    private val http = HttpTransport(
        connectTimeoutMs = config.connectTimeoutMs,
        readTimeoutMs = config.readTimeoutMs,
        apiKey = config.apiKey,
    )

    override fun canRun(model: ModelDescriptor, binding: RuntimeBinding): Boolean =
        binding.runtime == RuntimeKind.REMOTE_OPENAI

    override suspend fun load(model: ModelDescriptor, binding: RuntimeBinding): LoadedModel {
        if (!canRun(model, binding)) {
            throw ModelLoadException("${binding.runtime.id} binding cannot run on the OpenAI-compatible runtime")
        }
        val remoteName = binding.artifact.ifBlank { model.id }
        // Nothing is loaded into this process: the server owns the weights. The
        // reported footprint is therefore zero, which keeps the runtime manager
        // from evicting local models to make room for a remote one.
        return when {
            Capability.SPEECH_TO_TEXT in model.capabilities -> RemoteSpeechModel(model.id, remoteName)
            Capability.EMBEDDING in model.capabilities -> RemoteEmbeddingModel(model.id, remoteName)
            Capability.TEXT_GENERATION in model.capabilities -> RemoteTextModel(model.id, remoteName)
            else -> throw ModelLoadException("No remote endpoint for the capabilities of ${model.id}")
        }
    }

    private fun url(path: String) = "${config.normalizedBaseUrl}$path"

    /**
     * Plain text stays a bare string (what every server already expects);
     * an attached image switches to the OpenAI vision content-parts array
     * (`[{type:"text",...},{type:"image_url",...}]`) — a provider that
     * doesn't support vision on the selected model rejects this the same
     * way it would reject any other unsupported request, with its own
     * error surfacing through [OpenAiException] rather than this runtime
     * guessing per-model support itself.
     */
    private fun userContent(text: String, images: List<ImageRef>) =
        if (images.isEmpty()) {
            JsonPrimitive(text)
        } else {
            buildJsonArray {
                add(buildJsonObject { put("type", "text"); put("text", text) })
                images.forEach { image ->
                    add(
                        buildJsonObject {
                            put("type", "image_url")
                            put("image_url", buildJsonObject { put("url", image.uri) })
                        },
                    )
                }
            }
        }

    private inner class RemoteTextModel(
        override val modelId: String,
        private val remoteName: String,
    ) : TextModelHandle {

        private val cancelled = AtomicBoolean(false)
        override val ramBytes: Long = 0

        override fun generate(request: GenerationRequest): Flow<String> = flow {
            cancelled.set(false)
            val body = openAiJson.encodeToString(
                ChatRequest.serializer(),
                ChatRequest(
                    model = remoteName,
                    messages = buildList {
                        request.systemPrompt?.let { add(ChatMessage("system", JsonPrimitive(it))) }
                        add(ChatMessage("user", userContent(request.prompt, request.images)))
                    },
                    stream = true,
                    // maxTokens/temperature/topP are deliberately NOT forwarded
                    // here: GenerationRequest carries whatever the local
                    // generation settings screen has configured regardless of
                    // which runtime ends up serving the turn, and those are
                    // tuned for a local quantized model, not for whatever a
                    // cloud provider's own defaults are. A user who set max
                    // response length to 1512 for a small local Gemma got
                    // every Gemini reply truncated to 1512 tokens too — a
                    // cloud provider is trusted to pick its own sane defaults.
                    stop = request.stopSequences.takeIf { it.isNotEmpty() },
                ),
            )

            // A dropped/reset connection on a mobile network is common enough
            // that failing the whole turn on the first hiccup is the wrong
            // default. Retrying is only safe before any token has reached the
            // caller — once a partial answer has been shown, re-sending the
            // same prompt would duplicate it — so the flag below gates the
            // retry to exactly that window. The same flag also gates key
            // rotation below: a 429 from a provider is always the very first
            // thing that comes back (the whole response is buffered before
            // streaming starts), so in practice it is always false there too.
            var emittedAny = false
            var ioRetries = 0
            val rotator = config.keyRotator?.takeIf { it.poolSize() > 0 }
            while (true) {
                // Re-read every attempt, not just once before the loop: a 429
                // below moves the pool's active key on, and the next attempt
                // must pick that up rather than retry the same exhausted key.
                val keyEntry = rotator?.activeKey()
                if (rotator != null && keyEntry == null) {
                    throw ModelLoadException(
                        rotator.exhaustionMessage() ?: "Нет доступных ключей API для этого провайдера",
                    )
                }
                try {
                    http.postJsonStreaming(url("/chat/completions"), body, keyEntry?.key ?: config.apiKey).use { response ->
                        val reader = response.reader()
                        while (true) {
                            if (cancelled.get()) return@use
                            val line = reader.readLine() ?: return@use
                            val payload = SseParser.dataOf(line) ?: continue
                            if (SseParser.isTerminator(payload)) return@use
                            val chunk = runCatching {
                                openAiJson.decodeFromString(ChatStreamChunk.serializer(), payload)
                            }.getOrNull() ?: continue
                            chunk.choices.firstOrNull()?.delta?.content?.takeIf { it.isNotEmpty() }?.let {
                                emittedAny = true
                                emit(it)
                            }
                        }
                    }
                    break
                } catch (e: OpenAiException) {
                    // 429 is what both a per-minute rate limit and a daily
                    // quota show up as on every provider this app targets —
                    // rotate to the pool's next key and try again rather than
                    // failing a turn that a second key would have answered.
                    if (rotator != null && keyEntry != null && e.status == 429 && !emittedAny) {
                        rotator.markExhausted(keyEntry.id)
                        continue
                    }
                    throw e
                } catch (e: java.io.IOException) {
                    if (emittedAny || cancelled.get() || ioRetries >= STREAM_RETRY_LIMIT) throw e
                    ioRetries++
                }
            }
        }.flowOn(Dispatchers.IO)

        /**
         * Takes effect at the next chunk boundary. Chunks already buffered
         * downstream still arrive — the flow is buffered so the UI renders
         * smoothly — so cancellation is prompt, not instantaneous.
         */
        override fun requestCancel() {
            cancelled.set(true)
        }

        override fun close() = Unit
    }

    private inner class RemoteEmbeddingModel(
        override val modelId: String,
        private val remoteName: String,
    ) : EmbeddingModelHandle {

        override val ramBytes: Long = 0
        override var dimensions: Int = 0
            private set

        override suspend fun embed(texts: List<String>): List<FloatArray> = withContext(Dispatchers.IO) {
            if (texts.isEmpty()) return@withContext emptyList()
            val body = openAiJson.encodeToString(
                EmbeddingRequest.serializer(),
                EmbeddingRequest(remoteName, texts),
            )
            val decoded = openAiJson.decodeFromString(
                EmbeddingResponse.serializer(),
                http.postJson(url("/embeddings"), body).text(),
            )
            decoded.data
                .sortedBy { it.index }
                .map { it.embedding.toFloatArray() }
                .also { vectors -> dimensions = vectors.firstOrNull()?.size ?: 0 }
        }

        override fun close() = Unit
    }

    private inner class RemoteSpeechModel(
        override val modelId: String,
        private val remoteName: String,
    ) : SpeechModelHandle {

        override val ramBytes: Long = 0

        override suspend fun transcribe(audio: AudioRef, language: String?): Transcript =
            withContext(Dispatchers.IO) {
                val file = audioFile(audio)
                if (!file.isFile) throw OpenAiException(0, "Audio file not found: ${audio.uri}")

                val boundary = "----localaistudio${System.nanoTime()}"
                val body = Multipart(boundary).apply {
                    field("model", remoteName)
                    language?.let { field("language", it) }
                    field("response_format", "verbose_json")
                    file("file", file)
                }.build()

                val decoded = openAiJson.decodeFromString(
                    TranscriptionResponse.serializer(),
                    http.postBytes(
                        url = url("/audio/transcriptions"),
                        contentType = "multipart/form-data; boundary=$boundary",
                        body = body,
                    ).text(),
                )
                Transcript(
                    text = decoded.text.trim(),
                    language = decoded.language ?: language,
                    segments = decoded.segments.map {
                        TranscriptSegment(
                            text = it.text.trim(),
                            startMs = (it.start * 1000).toLong(),
                            endMs = (it.end * 1000).toLong(),
                        )
                    },
                )
            }

        private fun audioFile(audio: AudioRef): File {
            val path = runCatching { URI.create(audio.uri) }
                .getOrNull()
                ?.takeIf { it.scheme == "file" }
                ?.path
            return File(path ?: audio.uri)
        }

        override fun requestCancel() = Unit

        override fun close() = Unit
    }
}
