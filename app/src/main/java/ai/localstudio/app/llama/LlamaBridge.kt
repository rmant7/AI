package ai.localstudio.app.llama

import kotlinx.coroutines.sync.Mutex

/**
 * Which token(s) of an embedding model's output become the sentence vector —
 * `llama_pooling_type`'s ordinal values, as defined in llama.h at this app's
 * pinned commit (`MEAN=1, CLS=2, LAST=3`). Not every embedding checkpoint
 * agrees on this: e5-family models are trained for [MEAN]; others expect the
 * [CLS] token instead. Using the wrong one for a given model does not
 * error — it produces vectors that still look valid (right dimension, right
 * rough magnitude) while retrieval quality quietly degrades, which is why
 * this is a real, per-model choice rather than a hardcoded default.
 */
enum class EmbeddingPooling(internal val nativeValue: Int) {
    MEAN(1),
    CLS(2),
    LAST(3),
}

/**
 * The JNI surface of llama.cpp. One instance owns one loaded model.
 *
 * Kept as thin as possible: everything that can be decided in Kotlin is decided
 * in Kotlin, because native code cannot be tested without a device.
 */
class LlamaBridge {

    /** Receives generated text as it is produced. Called on the generating thread. */
    interface TokenSink {
        fun onToken(text: String)
    }

    external fun nativeSystemInfo(): String

    /**
     * Whether this model's own chat template was found and applied, or the
     * generic fallback scaffold had to stand in for it. Worth a log line per
     * load: without the model's turn markers an instruction-tuned model
     * stops answering and starts continuing the prompt as prose, and that
     * failure is otherwise indistinguishable from the model just being bad.
     */
    external fun nativeChatTemplateInfo(handle: Long): String

    /**
     * Where the last turn's time went: prompt tokens, how many of them the
     * KV cache reused from the previous turn, and the rate of each phase.
     * "Slow" on its own has never been enough to act on — a large prompt at
     * a normal rate and a small one at a collapsed rate look identical from
     * Kotlin, and want opposite fixes.
     */
    external fun nativeLastTurnStats(handle: Long): String

    /** Returns a handle, or 0 when the model could not be loaded. */
    external fun nativeLoad(modelPath: String, contextTokens: Int, threads: Int): Long

    /**
     * Loads a GGUF for [nativeEmbed] rather than [nativeGenerate] — a
     * separate context configuration (embeddings enabled, [pooling]), not
     * interchangeable with a handle from [nativeLoad]. Returns a handle, or 0
     * when the model could not be loaded, same contract as [nativeLoad].
     */
    fun nativeLoadEmbeddingModel(modelPath: String, contextTokens: Int, threads: Int, pooling: EmbeddingPooling): Long =
        nativeLoadEmbeddingModel(modelPath, contextTokens, threads, pooling.nativeValue)

    private external fun nativeLoadEmbeddingModel(modelPath: String, contextTokens: Int, threads: Int, pooling: Int): Long

    /**
     * The reason the *last* [nativeLoadEmbeddingModel] call on this bridge
     * failed — empty if it didn't fail, or if it hasn't been called yet. The
     * only way that reason reaches a caller with no adb/logcat access: every
     * failure path already logs the same text natively, but only to logcat.
     */
    external fun nativeLastLoadError(): String

    /**
     * The pooled, L2-normalized embedding of [text] — a plain dot product
     * between two results is then equivalent to cosine similarity. [handle]
     * must come from [nativeLoadEmbeddingModel]. An empty array on any
     * failure (blank text, decode failure) rather than an exception: this
     * feeds a background retrieval-quality signal, not something a caller
     * should have to guard a whole turn against.
     */
    external fun nativeEmbed(handle: Long, text: String): FloatArray

    /** The fixed length of every [nativeEmbed] vector for this [handle] — read from the model, never assumed by a caller. */
    external fun nativeEmbeddingDimension(handle: Long): Int

    external fun nativeFree(handle: Long)

    /** Asks generation to stop; takes effect at the next token, not instantly. */
    external fun nativeCancel(handle: Long)

    /** Returns the number of tokens produced, or a negative code on failure. */
    external fun nativeGenerate(
        handle: Long,
        systemPrompt: String?,
        userPrompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        topK: Int,
        repeatPenalty: Float,
        callback: TokenSink,
    ): Int

    /**
     * Loads the vision encoder a multimodal model ships as a separate file
     * (mmproj) alongside its main GGUF — llama.cpp keeps the two apart, so
     * this is a second call after [nativeLoad], not part of it. Returns
     * false for a model with no real projector at that path, or one whose
     * projector doesn't actually report vision support; either way
     * [nativeGenerateWithImage] then has nothing to work with.
     */
    external fun nativeLoadMmproj(handle: Long, mmprojPath: String, threads: Int): Boolean

    /**
     * Same contract as [nativeGenerate], for a turn with exactly one
     * attached image — [imageBytes] is the raw, already-decoded file
     * content (whatever format stb_image handles: jpg, png, bmp, gif, ...),
     * not a path or URI. Requires [nativeLoadMmproj] to have already
     * succeeded for this handle; the prompt-cache reuse [nativeGenerate]
     * does across turns does not apply here (see the native side's own doc
     * comment) — every image turn starts the KV cache clean.
     */
    external fun nativeGenerateWithImage(
        handle: Long,
        systemPrompt: String?,
        userPrompt: String,
        imageBytes: ByteArray,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        topK: Int,
        repeatPenalty: Float,
        callback: TokenSink,
    ): Int

    companion object {
        /**
         * Whether the native library is present and loadable on this device.
         *
         * False rather than a crash: an ABI this build does not cover, or a CPU
         * older than the one the kernels were compiled for, must degrade to
         * "local models unavailable" instead of killing the app on startup.
         */
        val isAvailable: Boolean by lazy {
            runCatching { System.loadLibrary("llama_jni") }.isSuccess
        }

        // Matches NodeExecutors' default context-assembly budget (4096) —
        // deliberately, on both sides: raising this without raising the RAM
        // budget alongside it is what let a model that barely fit start
        // allocating a KV cache twice the size it used to, on devices
        // already running at 90-95% of total RAM. A mismatch here rejects a
        // turn cleanly (a "context exceeded" error); an oversized KV cache
        // on a memory-starved device gets the process killed outright, which
        // is the worse failure to risk by default.
        const val DEFAULT_CONTEXT_TOKENS = 4096

        /**
         * Phone SoCs are big.LITTLE and ggml splits each matmul evenly across
         * its threads, so handing work to the efficiency cores makes every
         * other thread wait on them. Four is a good proxy for the performance
         * cluster, and it keeps sustained load — and thermal throttling — lower.
         */
        fun defaultThreads(): Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

        /**
         * Serializes every blocking native llama.cpp call across the whole
         * app — chat generation, model loads, and memory embedding alike,
         * any model or context, any [LlamaBridge] instance.
         *
         * Root-caused from a real on-device native crash (segfault) reported
         * during ordinary use: [LlamaCppMemoryEmbedder]'s periodic
         * `embedPending()` backfill loop and a live query's
         * `embedForQuery()` both call [nativeEmbed] on the very same loaded
         * embedding context from independent coroutines, with nothing in
         * Kotlin stopping them from doing so at the same instant. A single
         * `llama_context`'s KV cache and batch buffers are not safe to touch
         * from two threads at once — this is not a hypothetical race, it is
         * exactly what a live device hit. Sharing this one mutex between
         * [LlamaCppRuntime]'s chat calls and [LlamaCppMemoryEmbedder]'s
         * embedding calls (different contexts, but the same native process
         * and thread pool) is the same "queued after, not in parallel with"
         * guarantee [LlamaCppRuntime] already relied on for chat generation
         * and model loads — this just closes the gap that the embedding
         * path never went through it.
         */
        val nativeOpMutex = Mutex()
    }
}
