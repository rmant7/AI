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
