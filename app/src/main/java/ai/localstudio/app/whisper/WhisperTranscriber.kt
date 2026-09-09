package ai.localstudio.app.whisper

import ai.localstudio.whisper.WhisperBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Runs a Whisper ggml model end to end via whisper.cpp: PCM in, text out.
 *
 * Replaces a hand-rolled TFLite path (mel spectrogram computed in Kotlin,
 * decoding baked into a single opaque graph op, models pulled from a
 * third-party single-file conversion) with the reference C++ implementation
 * — the same engine, same feature extraction, same decode loop every other
 * whisper.cpp-based transcriber uses, and models from whisper.cpp's own
 * canonical release rather than an unverified conversion. [WhisperBridge]
 * owns the actual native call; this class only owns the audio-format
 * conversion and the pre-transcription silence gate that already existed.
 */
class WhisperTranscriber(private val modelFile: File) {

    private val bridge = WhisperBridge()
    private var handle: Long = 0

    suspend fun initialize() = withContext(Dispatchers.IO) {
        if (!modelFile.isFile) throw IllegalStateException("Model not found: ${modelFile.absolutePath}")
        if (!WhisperBridge.isAvailable) throw IllegalStateException("Native Whisper runtime unavailable on this device")

        handle = bridge.nativeLoad(modelFile.absolutePath)
        if (handle == 0L) throw IllegalStateException("Failed to load model: ${modelFile.name}")
    }

    /** [audioData] is 16-bit PCM, mono, 16kHz, little-endian. */
    suspend fun transcribe(audioData: ByteArray): String = withContext(Dispatchers.Default) {
        if (handle == 0L) throw IllegalStateException("Model not initialized")

        val audioFloats = decodePcmToFloat(audioData)
        if (rmsEnergy(audioFloats) < SILENCE_THRESHOLD) return@withContext ""

        bridge.nativeTranscribe(handle, audioFloats, WhisperBridge.defaultThreads())
    }

    fun release() {
        if (handle != 0L) {
            bridge.nativeFree(handle)
            handle = 0
        }
    }

    private fun rmsEnergy(audio: FloatArray): Float {
        if (audio.isEmpty()) return 0f
        var sum = 0f
        for (sample in audio) sum += sample * sample
        return kotlin.math.sqrt(sum / audio.size)
    }

    private fun decodePcmToFloat(audioData: ByteArray): FloatArray {
        val shortBuffer = ByteBuffer.wrap(audioData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return FloatArray(shortBuffer.limit()) { shortBuffer.get(it) / 32768.0f }
    }

    private companion object {
        const val SILENCE_THRESHOLD = 0.01f
    }
}
