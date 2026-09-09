package ai.localstudio.app.whisper

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Runs a Whisper TFLite model end to end: PCM in, text out.
 *
 * Ported from VirtualClone's `Whisper_Sizes` branch (the interpreter wiring
 * is sound), with [WhisperTokenizer] in place of the vocabulary that branch
 * never actually connected. Without a real vocabulary, [transcribe] fails
 * loudly instead of handing back raw token numbers as if they were words.
 */
class WhisperTranscriber(private val modelFile: File, private val vocabFile: File) {

    private val featureExtractor = WhisperFeatureExtractor()
    private var interpreter: Interpreter? = null
    private var tokenizer: WhisperTokenizer? = null

    suspend fun initialize() = withContext(Dispatchers.IO) {
        if (!modelFile.isFile) throw IllegalStateException("Model not found: ${modelFile.absolutePath}")
        if (!vocabFile.isFile) throw IllegalStateException("Vocabulary not found: ${vocabFile.absolutePath}")

        tokenizer = WhisperTokenizer.fromVocabJson(vocabFile)
        interpreter = Interpreter(modelFile, Interpreter.Options().apply { setNumThreads(4) })
    }

    /** [audioData] is 16-bit PCM, mono, 16kHz, little-endian. */
    suspend fun transcribe(audioData: ByteArray): String = withContext(Dispatchers.Default) {
        val currentInterpreter = interpreter ?: throw IllegalStateException("Model not initialized")
        val currentTokenizer = tokenizer ?: throw IllegalStateException("Vocabulary not loaded")

        val audioFloats = decodePcmToFloat(audioData)
        if (rmsEnergy(audioFloats) < SILENCE_THRESHOLD) return@withContext ""

        val inputFeatures = featureExtractor.extractFeatures(audioFloats)
        val outputShape = currentInterpreter.getOutputTensor(0).shape()
        val outputBuffer = Array(outputShape[0]) { IntArray(outputShape[1]) }
        currentInterpreter.run(inputFeatures, outputBuffer)

        currentTokenizer.decode(outputBuffer[0].filter { it > 0 })
    }

    fun release() {
        interpreter?.close()
        interpreter = null
        tokenizer = null
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
