package ai.localstudio.qwen3tts

/**
 * The JNI surface over qwen3-tts.cpp (see qwen3_tts_bridge.cpp) — nothing
 * else. One [create]d handle is one loaded model; callers must not run two
 * native calls on the same handle at once, with the single exception of
 * [cancel], which is safe from any thread while [synthesize] runs.
 *
 * Prepare-then-synthesize is split on purpose: [prepareVoice] is the costly
 * part (speaker encoder plus reference speech codes) and writes a reusable
 * prompt file, so every following [synthesize] pays only for generation.
 */
object Qwen3TtsNative {

    const val STATUS_OK = 0
    const val STATUS_CANCELLED = 1
    const val STATUS_ERROR = 2

    /** Whether libqwen3_tts_bridge.so could be loaded on this device. */
    val isLoaded: Boolean by lazy { runCatching { System.loadLibrary("qwen3_tts_bridge") }.isSuccess }

    /** A new context using [threads] CPU threads, or 0 if it could not be created. */
    external fun create(threads: Int): Long

    external fun destroy(handle: Long)

    /** Loads the talker and tokenizer GGUFs found in [modelDir]; [talkerFile] selects the talker among them. */
    external fun loadModels(handle: Long, modelDir: String, talkerFile: String): Boolean

    /** Encodes [referenceWav] + its transcript into a reusable voice prompt file at [promptPath]. */
    external fun prepareVoice(handle: Long, referenceWav: String, referenceText: String, promptPath: String): Boolean

    /** [STATUS_OK] writes a 24 kHz mono 16-bit WAV to [outputWav]. */
    external fun synthesize(
        handle: Long,
        promptPath: String,
        text: String,
        languageId: Int,
        maxAudioTokens: Int,
        outputWav: String,
    ): Int

    /** Streaming: audio chunk length and how much earlier audio the vocoder re-decodes as context. Applies to the next [synthesize]. */
    external fun setStreaming(chunkMs: Int, leftContextMs: Int)

    /** Threads for the streaming vocoder (0 = same as the model); takes effect for a model loaded after this call. */
    external fun setVocoderThreads(threads: Int)

    /** Diagnostics: ggml matvec/matmul micro-benchmark on 1..6 threads (blocks for about half a minute); returns the report. */
    external fun benchmarkMatvec(): String

    external fun cancelBenchmark()

    external fun cancel(handle: Long)

    /** Milliseconds from the start of the last [synthesize] to its first audio chunk, or -1. */
    external fun firstChunkMs(handle: Long): Long

    external fun lastError(handle: Long): String

    /** `[chunks, audioMs, elapsedMs]` of the current or last [synthesize]; safe to call while it runs. */
    external fun progress(handle: Long): LongArray

    /**
     * What the native runtime reported (its own per-phase timings, memory) plus
     * the bridge's chunk timeline since the last call; clears it.
     */
    external fun takeLog(handle: Long): String

    /** What the running (or last) native call has printed so far; safe to call while it runs. */
    external fun peekLive(handle: Long): String
}
