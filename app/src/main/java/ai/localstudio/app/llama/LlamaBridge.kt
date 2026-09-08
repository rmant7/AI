package ai.localstudio.app.llama

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
    }
}
