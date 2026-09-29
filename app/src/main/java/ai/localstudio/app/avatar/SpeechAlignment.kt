package ai.localstudio.app.avatar

/**
 * Which letter of an utterance is being spoken at a given moment, worked out
 * from the synthesized audio itself rather than from whatever timing (if any)
 * the TTS engine chose to report — the engine's own callbacks were the reason
 * the mouth barely correlated with the speech (word-level ranges at best,
 * often nothing at all).
 *
 * The letters are laid over the audio's energy: each letter gets a share of
 * the total loudness proportional to a weight (vowels count more than
 * consonants, since a vowel is where the mouth is actually open and the sound
 * actually loud), so words land where the sound is and pauses between words
 * are skipped. It is an estimate, not forced alignment — good to within a
 * letter or two, which is what a mouth shape needs.
 */
internal class SpeechTimeline(private val textIndex: IntArray, private val endSec: FloatArray) {

    /** Index into the utterance text of the letter being spoken at [sec], or null if the text has no letters. */
    fun letterAt(sec: Float): Int? {
        if (textIndex.isEmpty()) return null
        var lo = 0
        var hi = endSec.size - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (endSec[mid] > sec) hi = mid else lo = mid + 1
        }
        return textIndex[lo]
    }
}

internal object SpeechAlignment {

    /** [envelope] is one loudness value per [frameSec] seconds of audio. */
    fun build(text: String, envelope: FloatArray, frameSec: Float): SpeechTimeline {
        val letters = text.indices.filter { text[it].isLetter() }
        if (letters.isEmpty() || envelope.isEmpty()) return SpeechTimeline(IntArray(0), FloatArray(0))

        val weights = FloatArray(letters.size) { if (text[letters[it]].lowercaseChar() in VOWELS) VOWEL_WEIGHT else 1f }
        val totalWeight = weights.sum()

        // Frames quieter than this count as silence (pauses, breaths), so no
        // letter gets stretched across them.
        val floor = (envelope.max() * SILENCE_FRACTION)
        val energy = FloatArray(envelope.size) { if (envelope[it] >= floor) envelope[it] else 0f }
        val totalEnergy = energy.sum()
        val durationSec = envelope.size * frameSec

        val ends = FloatArray(letters.size)
        if (totalEnergy <= 0f) {
            var acc = 0f
            for (i in letters.indices) {
                acc += weights[i]
                ends[i] = durationSec * acc / totalWeight
            }
        } else {
            var frame = 0
            var cumulative = 0f
            var weightSoFar = 0f
            for (i in letters.indices) {
                weightSoFar += weights[i]
                val target = totalEnergy * weightSoFar / totalWeight
                while (frame < energy.size - 1 && cumulative + energy[frame] < target) {
                    cumulative += energy[frame]
                    frame++
                }
                val within = if (energy[frame] > 0f) ((target - cumulative) / energy[frame]).coerceIn(0f, 1f) else 1f
                ends[i] = (frame + within) * frameSec
            }
        }
        return SpeechTimeline(letters.toIntArray(), ends)
    }

    private const val VOWEL_WEIGHT = 3f
    private const val SILENCE_FRACTION = 0.06f
    private const val VOWELS = "аеёиоуыэюяaeiouy"
}
