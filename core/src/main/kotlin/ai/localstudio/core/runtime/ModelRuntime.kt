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

interface TextModelHandle : LoadedModel {
    fun generate(request: GenerationRequest): Flow<String>
}

interface SpeechModelHandle : LoadedModel {
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
