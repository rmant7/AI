package ai.localstudio.openai

import ai.localstudio.core.capability.Capability
import ai.localstudio.core.model.AudioRef
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
import java.io.File
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

data class OpenAiConfig(
    /** e.g. `http://localhost:11434/v1` for Ollama, `http://localhost:8080/v1` for llama-server. */
    val baseUrl: String,
    val apiKey: String? = null,
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
        fun describe(body: String): String = runCatching {
            openAiJson.decodeFromString(ApiErrorBody.serializer(), body).error?.message
        }.getOrNull() ?: body.take(300).ifBlank { "no response body" }
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
                        request.systemPrompt?.let { add(ChatMessage("system", it)) }
                        add(ChatMessage("user", request.prompt))
                    },
                    stream = true,
                    maxTokens = request.maxTokens,
                    temperature = request.temperature,
                    stop = request.stopSequences.takeIf { it.isNotEmpty() },
                ),
            )

            // A dropped/reset connection on a mobile network is common enough
            // that failing the whole turn on the first hiccup is the wrong
            // default. Retrying is only safe before any token has reached the
            // caller — once a partial answer has been shown, re-sending the
            // same prompt would duplicate it — so the flag below gates the
            // retry to exactly that window.
            var emittedAny = false
            var attempt = 0
            while (true) {
                attempt++
                try {
                    http.postJsonStreaming(url("/chat/completions"), body).use { response ->
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
                } catch (e: java.io.IOException) {
                    if (emittedAny || cancelled.get() || attempt > STREAM_RETRY_LIMIT) throw e
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
