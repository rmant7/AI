package ai.localstudio.app.avatar

import kotlin.math.min
import kotlin.math.sqrt

/**
 * How loud one chunk of TTS-synthesized audio is, as a `0f..1f` fraction —
 * fed straight from `AvatarTtsEngine.Event.Audio`'s raw bytes, no decoding
 * step: Android's `TextToSpeech`/`UtteranceProgressListener.onAudioAvailable`
 * always hands over signed 16-bit PCM, the same sample format this app's
 * mic/file audio pipeline (`core/audio`'s `PcmBuffer`, `MicrophoneAudioSource`)
 * already works with — this is a standalone, byte-array-in analyzer for
 * that same format rather than a reuse of those classes' own `ShortArray`
 * APIs, since a single TTS-callback chunk has none of the streaming/
 * buffering concerns those exist for.
 *
 * Root-mean-square, not peak: peak reacts to a single loud sample (fricative
 * noise, a click), RMS tracks the chunk's actual perceived loudness — which
 * is what a mouth-open amount should follow. Android's own platform
 * contract for `AudioFormat.ENCODING_PCM_16BIT` (what `TextToSpeech`
 * reports via `onBeginSynthesis`) is always little-endian signed 16-bit,
 * regardless of device — that byte order is not an assumption specific to
 * this app.
 */
object PcmEnvelopeAnalyzer {

    /** `0f` for an empty or too-short chunk (fewer than one 16-bit sample). */
    fun rms(pcm16: ByteArray): Float {
        if (pcm16.size < 2) return 0f
        var sumSquares = 0.0
        var sampleCount = 0
        var i = 0
        while (i + 1 < pcm16.size) {
            // Little-endian signed 16-bit, matching every PCM16 buffer
            // elsewhere in this app (see PcmBuffer's own doc comment).
            val sample = ((pcm16[i + 1].toInt() shl 8) or (pcm16[i].toInt() and 0xFF)).toShort()
            sumSquares += (sample.toDouble() / Short.MAX_VALUE).let { it * it }
            sampleCount++
            i += 2
        }
        if (sampleCount == 0) return 0f
        val rms = sqrt(sumSquares / sampleCount)
        // Ordinary speech rarely drives RMS much past ~0.3 on this 0..1
        // scale; a fixed gain rather than per-chunk auto-normalizing is what
        // keeps mouth-open amount comparable from one utterance to the next
        // instead of quiet and loud passages both maxing out the same way.
        return min(1f, (rms * GAIN).toFloat())
    }

    private const val GAIN = 3.2
}
