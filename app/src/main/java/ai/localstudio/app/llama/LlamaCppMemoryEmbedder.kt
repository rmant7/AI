package ai.localstudio.app.llama

import ai.localstudio.memory.MemoryEmbedder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [MemoryEmbedder] backed by a dedicated llama.cpp context loaded for
 * embedding (see [LlamaBridge.nativeLoadEmbeddingModel]) — a second, smaller
 * model resident alongside whichever chat model is currently loaded, not a
 * mode switch on the same context: an embedding context (pooled, non-causal)
 * and a chat-generation context (causal) are configured incompatibly at
 * creation time, so the two can never share one [LlamaBridge] handle.
 *
 * [dimension] is read from the loaded model itself via
 * [LlamaBridge.nativeEmbeddingDimension] — never a caller-supplied guess,
 * which could silently mismatch the actual model and only surface later as a
 * confusing dimension-mismatch failure somewhere downstream in
 * [ai.localstudio.memory.MemorySemanticIndex].
 */
class LlamaCppMemoryEmbedder private constructor(
    private val bridge: LlamaBridge,
    private val handle: Long,
    override val modelId: String,
    override val dimension: Int,
) : MemoryEmbedder {

    override suspend fun embed(texts: List<String>): List<FloatArray> = withContext(Dispatchers.Default) {
        texts.map { text -> bridge.nativeEmbed(handle, text) }
    }

    /** Releases the native context. Not part of [MemoryEmbedder] — that interface has no lifecycle of its own. */
    fun close() {
        bridge.nativeFree(handle)
    }

    companion object {
        /**
         * Loads [modelPath] as an embedding model and wraps it, or returns
         * null if the file could not be loaded as one — the same
         * "unavailable, degrade gracefully" contract every other local-model
         * load in this app follows, rather than throwing.
         *
         * [modelId] must uniquely identify these exact weights (see
         * [MemoryEmbedder.modelId]'s own contract) — pass something derived
         * from the model catalog entry (its id and version), not a constant
         * a future model swap would silently keep reporting.
         */
        fun load(
            bridge: LlamaBridge,
            modelPath: String,
            modelId: String,
            contextTokens: Int = DEFAULT_CONTEXT_TOKENS,
            threads: Int = LlamaBridge.defaultThreads(),
        ): LlamaCppMemoryEmbedder? {
            val handle = bridge.nativeLoadEmbeddingModel(modelPath, contextTokens, threads)
            if (handle == 0L) return null
            val dimension = bridge.nativeEmbeddingDimension(handle)
            if (dimension <= 0) {
                bridge.nativeFree(handle)
                return null
            }
            return LlamaCppMemoryEmbedder(bridge, handle, modelId, dimension)
        }

        // Memory facts and queries are short (one sentence to a short
        // paragraph, never a resent multi-turn conversation) — generous
        // enough to never truncate a real one, small enough to keep the
        // embedding context's own memory footprint well below the chat
        // model's, per SEMANTIC_RETRIEVAL_DESIGN.md's "a second resident
        // model" cost this app accepts explicitly.
        private const val DEFAULT_CONTEXT_TOKENS = 512
    }
}
