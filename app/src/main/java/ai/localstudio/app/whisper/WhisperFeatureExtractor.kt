package ai.localstudio.app.whisper

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Turns 16kHz PCM audio into the `[1, 80, 3000]` log-mel-spectrogram tensor
 * Whisper's encoder expects.
 *
 * Ported from VirtualClone's `Whisper_Sizes` branch (never merged there) —
 * the STFT/mel-filter math is a self-contained, verifiable signal-processing
 * pipeline with no external dependency, unlike the tokenizer it shipped with.
 */
class WhisperFeatureExtractor {

    private val sampleRate = 16000
    private val nFft = 512
    private val frameLen = 400
    private val hopLength = 160
    private val nMels = 80
    private val nSamples = 30 * sampleRate
    private val melLen = 3000

    private val melFilters: Array<FloatArray> by lazy { computeMelFilters() }
    private val window: FloatArray by lazy { hanningWindow(frameLen) }

    fun extractFeatures(audio: FloatArray): Array<Array<FloatArray>> {
        val paddedAudio = FloatArray(nSamples)
        audio.copyInto(paddedAudio, 0, 0, min(audio.size, nSamples))

        val features = Array(1) { Array(nMels) { FloatArray(melLen) } }
        val fftReal = DoubleArray(nFft)
        val fftImag = DoubleArray(nFft)

        for (i in 0 until melLen) {
            val start = i * hopLength
            if (start + frameLen > nSamples) break

            for (j in 0 until nFft) {
                fftReal[j] = if (j < frameLen) (paddedAudio[start + j] * window[j]).toDouble() else 0.0
                fftImag[j] = 0.0
            }

            fft(fftReal, fftImag)

            val powerSpectrum = FloatArray(nFft / 2 + 1)
            for (j in powerSpectrum.indices) {
                powerSpectrum[j] = (fftReal[j] * fftReal[j] + fftImag[j] * fftImag[j]).toFloat()
            }

            for (m in 0 until nMels) {
                var melEnergy = 0f
                val filter = melFilters[m]
                val numBins = min(powerSpectrum.size, filter.size)
                for (j in 0 until numBins) melEnergy += powerSpectrum[j] * filter[j]
                features[0][m][i] = log10(max(melEnergy, 1e-10f))
            }
        }

        normalize(features[0])
        return features
    }

    private fun normalize(melSpectrogram: Array<FloatArray>) {
        var maxVal = -100f
        for (row in melSpectrogram) for (v in row) if (v > maxVal) maxVal = v
        val floor = maxVal - 8.0f
        for (row in melSpectrogram) {
            for (i in row.indices) row[i] = (max(row[i], floor) + 4.0f) / 4.0f
        }
    }

    /** Iterative radix-2 Cooley-Tukey FFT, in place. [nFft] is a power of two. */
    private fun fft(real: DoubleArray, imag: DoubleArray) {
        val n = real.size
        var j = 0
        for (i in 0 until n) {
            if (i < j) {
                real[i] = real[j].also { real[j] = real[i] }
                imag[i] = imag[j].also { imag[j] = imag[i] }
            }
            var m = n shr 1
            while (m >= 1 && j >= m) {
                j -= m
                m = m shr 1
            }
            j += m
        }

        var len = 2
        while (len <= n) {
            val ang = 2.0 * PI / len
            val wlenReal = cos(ang)
            val wlenImag = -sin(ang)
            for (i in 0 until n step len) {
                var wReal = 1.0
                var wImag = 0.0
                for (k in 0 until len / 2) {
                    val uReal = real[i + k]
                    val uImag = imag[i + k]
                    val vReal = real[i + k + len / 2] * wReal - imag[i + k + len / 2] * wImag
                    val vImag = real[i + k + len / 2] * wImag + imag[i + k + len / 2] * wReal
                    real[i + k] = uReal + vReal
                    imag[i + k] = uImag + vImag
                    real[i + k + len / 2] = uReal - vReal
                    imag[i + k + len / 2] = uImag - vImag
                    val nextWReal = wReal * wlenReal - wImag * wlenImag
                    wImag = wReal * wlenImag + wImag * wlenReal
                    wReal = nextWReal
                }
            }
            len = len shl 1
        }
    }

    private fun hanningWindow(size: Int): FloatArray =
        FloatArray(size) { (0.5 * (1.0 - cos(2.0 * PI * it / (size - 1)))).toFloat() }

    private fun computeMelFilters(): Array<FloatArray> {
        val numBins = nFft / 2 + 1
        val filters = Array(nMels) { FloatArray(numBins) }

        fun hzToMel(hz: Float): Float = 2595f * log10(1f + hz / 700f)
        fun melToHz(mel: Float): Float = 700f * (10f.pow(mel / 2595f) - 1f)

        val minMel = hzToMel(0f)
        val maxMel = hzToMel(sampleRate / 2f)
        val melPoints = FloatArray(nMels + 2) { minMel + it * (maxMel - minMel) / (nMels + 1) }
        val hzPoints = FloatArray(nMels + 2) { melToHz(melPoints[it]) }
        val binPoints = IntArray(nMels + 2) {
            ((nFft + 1) * hzPoints[it] / sampleRate).toInt().coerceIn(0, numBins - 1)
        }

        for (m in 0 until nMels) {
            val startBin = binPoints[m]
            val centerBin = binPoints[m + 1]
            val endBin = binPoints[m + 2]
            for (k in startBin until centerBin) {
                filters[m][k] = (k - startBin).toFloat() / (centerBin - startBin).coerceAtLeast(1)
            }
            for (k in centerBin until endBin) {
                filters[m][k] = (endBin - k).toFloat() / (endBin - centerBin).coerceAtLeast(1)
            }
        }
        return filters
    }
}
