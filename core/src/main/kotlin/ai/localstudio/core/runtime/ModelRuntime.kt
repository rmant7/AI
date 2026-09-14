package ai.localstudio.core.runtime

import ai.localstudio.core.model.AudioRef
import ai.localstudio.core.model.ImageRef
import ai.localstudio.core.model.Transcript
import ai.localstudio.core.model.TranscriptSegment
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
     * Transcribes a whole [audio] reference in one call and returns the full
     * [Transcript]. What "whole" costs depends on the implementation: a file
     * handle is expected to decode and infer incrementally under the hood
     * (bounded memory, first segments available before the last one is
     * decoded — see docs/13-asr-pipeline-migration.md) even though this
     * suspend function itself only resolves once, at the end.
     */
    suspend fun transcribe(audio: AudioRef, language: String? = null): Transcript

    /**
     * Starts a genuinely incremental session: audio pushed in as it arrives via
     * [StreamingSpeechSession.acceptAudio], text delivered as it is produced via
     * [StreamingSpeechSession.segments] — a distinct mode from [transcribe], not
     * a relabeling of it (the old approach this replaces called [transcribe] on
     * a growing buffer every couple of seconds, which makes the cost of a turn
     * grow with the turn's own length). See docs/13-asr-pipeline-migration.md.
     */
    fun startStreaming(language: String? = null): StreamingSpeechSession
}

/**
 * One in-progress speech session, fed audio incrementally by whoever owns the
 * capture loop — a microphone thread or a file-decode pipeline. Not modeled as
 * a coroutine `Flow` *in*, deliberately: audio delivery is push-driven by that
 * external loop, not pull-driven by a suspend collector.
 */
interface StreamingSpeechSession {
    /** Mono 16kHz PCM16. Must return quickly — buffer, don't infer, on this call. */
    fun acceptAudio(pcm: ShortArray)

    /** No more audio is coming: flushes whatever is buffered and completes [segments]. */
    fun finish()

    /** Stops immediately, discarding any unflushed buffered audio, and completes [segments]. */
    fun cancel()

    /** Segments as they become available. The flow completes after [finish] or [cancel]. */
    val segments: Flow<TranscriptSegment>
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
    val topP: Double = 0.95,
    val topK: Int = 40,
    /** >1.0 discourages repeats; 1.0 disables the penalty entirely. */
    val repeatPenalty: Double = 1.2,
    val stopSequences: List<String> = emptyList(),
    /**
     * Attached images, in the order they should be shown to the model — see
     * [ai.localstudio.core.context.AssembledContext.images]. Empty for every
     * runtime that doesn't understand vision, including every current local
     * one: a runtime that ignores this field simply behaves as it always did.
     */
    val images: List<ImageRef> = emptyList(),
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
