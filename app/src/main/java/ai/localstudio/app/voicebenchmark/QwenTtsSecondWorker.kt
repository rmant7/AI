package ai.localstudio.app.voicebenchmark

import ai.localstudio.qwen3tts.Qwen3TtsNative
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * A second, independent Qwen3-TTS context, for the avatar only: on a phone with RAM and cores to spare,
 * two sentences are synthesized at the same time (measured: the model does not scale with threads, so
 * two contexts of ~3 threads each roughly double the throughput). It does not prepare the voice — it
 * uses the prompt file [QwenTtsRuntimeManager] already made — and has its own lock, so it never waits
 * for the main context.
 */
internal class QwenTtsSecondWorker {

    private val lock = Mutex()
    @Volatile
    private var handle = 0L
    private var loadedFile: String? = null

    suspend fun synthesize(
        context: Context,
        provider: QwenTtsModelProvider,
        prompt: File,
        threads: Int,
        vocoderThreads: Int,
        text: String,
        languageId: Int,
        output: File,
    ) = lock.withLock {
        // Same talker quantization as the main context.
        val talkerFile = (QwenTtsRuntimeManager.currentVariant() ?: QwenTtsRuntimeManager.talkerVariant).file.name
        if (handle != 0L && loadedFile != talkerFile) {
            val old = handle
            handle = 0L
            QwenTtsRuntimeManager.runNative(null) { Qwen3TtsNative.destroy(old) }
        }
        if (handle == 0L) {
            if (!Qwen3TtsNative.isLoaded) throw QwenTtsException("Native Qwen3-TTS library could not be loaded")
            val created = QwenTtsRuntimeManager.runNative(null) { Qwen3TtsNative.create(threads) }
            if (created == 0L) throw QwenTtsException("Could not create the second Qwen3-TTS context")
            val ok = QwenTtsRuntimeManager.runNative(null) {
                Qwen3TtsNative.loadModels(created, provider.modelDir.absolutePath, talkerFile)
            }
            if (!ok) {
                val error = Qwen3TtsNative.lastError(created)
                Qwen3TtsNative.destroy(created)
                throw QwenTtsException("Second model load failed: $error")
            }
            handle = created
            loadedFile = talkerFile
        }
        val h = handle
        val status = QwenTtsRuntimeManager.runNative(h) {
            Qwen3TtsNative.setStreaming(QwenTtsRuntimeManager.streamingChunkMs, QwenTtsRuntimeManager.streamingLeftMs)
            Qwen3TtsNative.setVocoderThreads(vocoderThreads)
            Qwen3TtsNative.synthesize(h, prompt.absolutePath, text, languageId, QwenTtsRuntimeManager.maxFramesFor(text), output.absolutePath)
        }
        Qwen3TtsNative.takeLog(h)
        when (status) {
            Qwen3TtsNative.STATUS_OK -> Unit
            Qwen3TtsNative.STATUS_CANCELLED -> throw CancellationException("Qwen3-TTS generation cancelled")
            else -> throw QwenTtsException("Generation failed: ${Qwen3TtsNative.lastError(h)}")
        }
    }

    fun requestCancel() {
        val h = handle
        if (h != 0L) Qwen3TtsNative.cancel(h)
    }

    suspend fun release() = lock.withLock {
        val h = handle
        handle = 0L
        if (h != 0L) QwenTtsRuntimeManager.runNative(null) { Qwen3TtsNative.destroy(h) }
    }
}
