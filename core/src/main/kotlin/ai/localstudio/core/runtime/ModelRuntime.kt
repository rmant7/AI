package ai.localstudio.core.runtime

import ai.localstudio.core.model.AudioRef
import ai.localstudio.core.model.ImageRef
import ai.localstudio.core.model.Transcript
import ai.localstudio.core.model.VisionResult
import ai.localstudio.core.registry.ModelDescriptor
import ai.localstudio.core.registry.RuntimeBinding
import ai.localstudio.core.registry.RuntimeKind
import kotlinx.coroutines.flow.Flow

/**
 * A model held in memory. Closing it releases the memory it reported through
 * [ramBytes]; the [RuntimeManager] owns the lifecycle, callers never close it
 * themselves.
 */
interface LoadedModel : AutoCloseable {
    val modelId: String
    val ramBytes: Long
}

/**
 * Something whose in-flight work can be interrupted.
 *
 * Cancelling the coroutine is not enough: a native inference call blocks in C++
 * and will run to completion regardless. The runtime has to raise a flag the
 * engine checks between steps (whisper.cpp's `abort_callback`, llama.cpp's
 * equivalent), so cancellation takes effect at the next checkpoint rather than
 * immediately — and leaving it unwired means a Stop button that does nothing on
 * a long transcription.
 */
interface Interruptible {
    fun requestCancel()
}

interface TextModelHandle : LoadedModel, Interruptible {
    fun generate(request: GenerationRequest): Flow<String>
}

interface SpeechModelHandle : LoadedModel, Interruptible {
    /**
     * Transcribes a whole buffer. Live dictation is built on this same call:
     * the current utterance is re-transcribed as it grows, which is what lets
     * the model revise earlier words once it has heard the end of the sentence.
     * See `ai.localstudio.core.audio.UtteranceAccumulator` and docs/12-audio.md.
     */
    suspend fun transcribe(audio: AudioRef, language: String? = null): Transcript
}

interface VisionModelHandle : LoadedModel {
    suspend fun analyze(image: ImageRef, prompt: String? = null): VisionResult
}

interface EmbeddingModelHandle : LoadedModel {
    val dimensions: Int
    suspend fun embed(texts: List<String>): List<FloatArray>
}

data class GenerationRequest(
    val prompt: String,
    val systemPrompt: String? = null,
    val maxTokens: Int = 1024,
    val temperature: Double = 0.7,
    val stopSequences: List<String> = emptyList(),
)

/**
 * An inference engine: llama.cpp, MediaPipe, MLC, whisper.cpp, ONNX Runtime,
 * or an OpenAI-compatible endpoint during development.
 *
 * Everything above this interface is written once. Adding an engine means
 * adding an implementation here and a [RuntimeBinding] in the registry — no
 * changes to the router, the context engine, the pipelines or the UI.
 */
interface ModelRuntime {
    val kind: RuntimeKind

    /** Cheap check before attempting a load — artifact format, quantisation, ABI. */
    fun canRun(model: ModelDescriptor, binding: RuntimeBinding): Boolean

    suspend fun load(model: ModelDescriptor, binding: RuntimeBinding): LoadedModel
}

class ModelLoadException(message: String, cause: Throwable? = null) : Exception(message, cause)

class InsufficientMemoryException(
    val requestedBytes: Long,
    val budgetBytes: Long,
    val residentBytes: Long,
) : Exception(
    "Cannot load model requiring $requestedBytes bytes: budget $budgetBytes, " +
        "$residentBytes bytes resident and not evictable",
)
