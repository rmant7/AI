package ai.localstudio.app.llama

import ai.localstudio.memory.MemoryEmbedder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
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
 *
 * [queryPrefix]/[passagePrefix] exist for asymmetric dual encoders — e5's own
 * convention is a literal `"query: "` versus `"passage: "` text prefix ahead
 * of the actual content, and [MemoryEmbedder.embedForQuery]/
 * [MemoryEmbedder.embedForStorage] are exactly the seam that distinction
 * needs. Both default to null (no prefix) for a symmetric model that has no
 * such convention.
 */
class LlamaCppMemoryEmbedder private constructor(
    private val bridge: LlamaBridge,
    private val handle: Long,
    override val modelId: String,
    override val dimension: Int,
    private val queryPrefix: String?,
    private val passagePrefix: String?,
) : MemoryEmbedder {

    // Both wrapped in LlamaBridge.nativeOpMutex: this embedder's periodic
    // embedPending() backfill and a live query's embedForQuery() run on
    // independent coroutines with nothing else stopping them from calling
    // nativeEmbed() on this exact same llama_context at the same instant —
    // confirmed as the cause of a real on-device native crash (see the
    // mutex's own doc comment). A single llama_context's KV cache and batch
    // buffers are not safe to touch from two threads at once.
    override suspend fun embedForStorage(texts: List<String>): List<FloatArray> = withContext(Dispatchers.Default) {
        LlamaBridge.nativeOpMutex.withLock {
            texts.map { text -> bridge.nativeEmbed(handle, passagePrefix?.plus(text) ?: text) }
        }
    }

    override suspend fun embedForQuery(query: String): FloatArray = withContext(Dispatchers.Default) {
        LlamaBridge.nativeOpMutex.withLock {
            bridge.nativeEmbed(handle, queryPrefix?.plus(query) ?: query)
        }
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
         *
         * [pooling] has no safe default deliberately: unlike [queryPrefix]/
         * [passagePrefix] (where "no prefix" is a real, valid choice for a
         * symmetric model), guessing a pooling mode wrong doesn't fail
         * loudly — see [LlamaBridge.nativeLoadEmbeddingModel]'s own doc
         * comment — so the caller must look this up for the specific model
         * being loaded, not inherit a value that happened to work for a
         * different one.
         */
        suspend fun load(
            bridge: LlamaBridge,
            modelPath: String,
            modelId: String,
            pooling: EmbeddingPooling,
            queryPrefix: String? = null,
            passagePrefix: String? = null,
            contextTokens: Int = DEFAULT_CONTEXT_TOKENS,
            threads: Int = LlamaBridge.defaultThreads(),
        ): LlamaCppMemoryEmbedder? = LlamaBridge.nativeOpMutex.withLock {
            val handle = bridge.nativeLoadEmbeddingModel(modelPath, contextTokens, threads, pooling)
            if (handle == 0L) return@withLock null
            val dimension = bridge.nativeEmbeddingDimension(handle)
            if (dimension <= 0) {
                bridge.nativeFree(handle)
                return@withLock null
            }
            LlamaCppMemoryEmbedder(bridge, handle, modelId, dimension, queryPrefix, passagePrefix)
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
