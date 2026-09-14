package ai.localstudio.whisper

import kotlinx.coroutines.sync.Mutex

/**
 * The JNI surface of whisper.cpp. One instance owns one loaded model.
 *
 * Kept as thin as possible, same reasoning as llama.LlamaBridge: everything
 * decidable in Kotlin is decided in Kotlin, because native code cannot be
 * tested without a device.
 */
class WhisperBridge {

    /**
     * Delivered once per finalized segment, during [nativeTranscribe] rather
     * than only after it returns — the native side calls this as soon as
     * whisper.cpp itself finalizes a segment (`new_segment_callback`), which is
     * what lets a caller show text before a long chunk's inference finishes,
     * not just before the whole file decodes. [startMs]/[endMs] are relative
     * to the start of the sample array passed to that call, not to the file —
     * the caller (see `ai.localstudio.app.whisper.WhisperCppSpeechModel`) adds
     * its own chunk offset.
     */
    fun interface SegmentSink {
        fun onSegment(text: String, startMs: Long, endMs: Long)
    }

    /** Returns a handle, or 0 when the model could not be loaded. */
    external fun nativeLoad(modelPath: String): Long

    external fun nativeFree(handle: Long)

    /**
     * Flips [handle]'s abort flag. whisper.cpp checks it between decode steps
     * (see whisper_jni.cpp's `abort_callback`), so a running [nativeTranscribe]
     * notices at its next checkpoint rather than immediately — the same
     * contract [ai.localstudio.core.runtime.Interruptible] documents for every
     * engine. The flag is reset at the start of the next [nativeTranscribe]
     * call, so a cancelled call never causes the next one to abort instantly.
     */
    external fun nativeCancel(handle: Long)

    /**
     * [samples] is mono 16kHz PCM as float32 in [-1, 1]. [language] is an
     * ISO-639-1 code ("ru", "en", ...) or "auto" — see the native side's own
     * doc comment for why passing the actual language beats "auto" whenever
     * it's known. [sink], when non-null, receives each segment as whisper.cpp
     * finalizes it. Returns the full transcribed text (all segments
     * concatenated), or "" on failure or cancellation.
     */
    // No default value on `sink`: a Kotlin default requires a generated
    // bridge method the compiler cannot synthesize for an `external`
    // (bodyless) declaration, so every caller passes it explicitly — null
    // when only the returned concatenated string is wanted.
    external fun nativeTranscribe(
        handle: Long,
        samples: FloatArray,
        threads: Int,
        language: String,
        sink: SegmentSink?,
    ): String

    companion object {
        /**
         * Whether the native library is present and loadable on this device.
         *
         * False rather than a crash: an ABI this build does not cover must
         * degrade to "voice input unavailable" instead of killing the app.
         */
        val isAvailable: Boolean by lazy {
            runCatching { System.loadLibrary("whisper_jni") }.isSuccess
        }

        /** Same reasoning as LlamaBridge.defaultThreads(): favour the performance cluster, not the efficiency cores. */
        fun defaultThreads(): Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

        /**
         * Serializes every [nativeLoad]/[nativeTranscribe] call against a
         * given handle's `whisper_context`, same reasoning and same pattern as
         * LlamaBridge.nativeOpMutex: the context is not safe for concurrent
         * native calls, and a [WhisperCppSpeechModel][ai.localstudio.app.whisper.WhisperCppSpeechModel]
         * can have both a batch [nativeTranscribe] window and a streaming
         * session's periodic re-transcribe in flight against the same
         * handle. [nativeCancel] deliberately does *not* go through this —
         * it has to reach a call that may currently be holding the lock.
         */
        val nativeOpMutex = Mutex()
    }
}
