package ai.localstudio.whisper

/**
 * The JNI surface of whisper.cpp. One instance owns one loaded model.
 *
 * Kept as thin as possible, same reasoning as llama.LlamaBridge: everything
 * decidable in Kotlin is decided in Kotlin, because native code cannot be
 * tested without a device.
 */
class WhisperBridge {

    /** Returns a handle, or 0 when the model could not be loaded. */
    external fun nativeLoad(modelPath: String): Long

    external fun nativeFree(handle: Long)

    /** [samples] is mono 16kHz PCM as float32 in [-1, 1]. Returns the transcribed text, or "" on failure. */
    external fun nativeTranscribe(handle: Long, samples: FloatArray, threads: Int): String

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
    }
}
