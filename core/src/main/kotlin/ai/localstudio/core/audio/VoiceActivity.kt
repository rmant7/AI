package ai.localstudio.core.audio

/**
 * Voice-activity detection and utterance accumulation for live dictation.
 *
 * The constants and the policy here are not guesses: they come from a working
 * on-device whisper.cpp dictation implementation (see docs/12-audio.md). The
 * one finding worth stating up front is that a *fixed* energy threshold does
 * not work — real speech level depends on mic gain, distance and room, so any
 * constant is either never tripped (all audio silently discarded) or tripped
 * constantly. Voice is detected relative to a *measured* noise floor instead.
 */
object AudioAnalysis {

    /** Mean-square energy of a block — a crude but free voice-activity signal. */
    fun meanSquare(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0f
        var sum = 0.0
        for (sample in samples) sum += sample.toDouble() * sample
        return (sum / samples.size).toFloat()
    }

    /**
     * Whether [energy] counts as speech relative to a measured [noiseFloor].
     *
     * [MIN_NOISE_FLOOR] is the floor under the floor: in a near-silent room the
     * estimate approaches zero, and anything multiplied by almost nothing is
     * still almost nothing — without it, sensor noise reads as speech.
     */
    fun isVoiced(energy: Float, noiseFloor: Float, multiplier: Float = VOICE_ENERGY_MULTIPLIER): Boolean {
        val floor = noiseFloor.coerceAtLeast(MIN_NOISE_FLOOR)
        return energy > floor * multiplier
    }

    /** Low enough to catch soft speech, high enough to ignore mic self-noise and room hum. */
    const val VOICE_ENERGY_MULTIPLIER = 4f
    const val MIN_NOISE_FLOOR = 1e-7f
    const val NOISE_FLOOR_EMA_ALPHA = 0.2f

    /**
     * Starts above zero: before any silence has been observed there is nothing
     * to calibrate against, and an initial estimate of zero makes the first
     * blocks read as loud whatever they contain.
     */
    const val INITIAL_NOISE_FLOOR = 0.001f
}

data class UtteranceConfig(
    val sampleRate: Int = 16_000,
    /** Whisper's own analysis window; past this an utterance is cut whether or not the speaker paused. */
    val maxUtteranceSeconds: Int = 25,
    /** Long enough not to cut on a mid-sentence breath. */
    val silenceToFinalizeMs: Int = 800,
    /** Below this whisper has too little context to produce anything useful. */
    val minTranscribeMs: Int = 400,
    /** How often the in-progress utterance is re-transcribed for partial text. */
    val refreshMs: Long = 2_000,
) {
    val maxSamples: Int get() = maxUtteranceSeconds * sampleRate
    val silenceSamplesToFinalize: Int get() = silenceToFinalizeMs * sampleRate / 1000
    val minSamplesToTranscribe: Int get() = minTranscribeMs * sampleRate / 1000
}

/** Live counters, published to the UI so a "nothing happens" report comes with numbers. */
data class UtteranceState(
    val blocksReceived: Int = 0,
    val bufferedSamples: Int = 0,
    val voicedSamples: Int = 0,
    val silentSamples: Int = 0,
    val noiseFloor: Float = AudioAnalysis.INITIAL_NOISE_FLOOR,
    val lastEnergy: Float = 0f,
)

/**
 * Accumulates microphone blocks into one utterance and decides when it ends.
 *
 * Deliberately pure: no Android types, no coroutines, no clock. The capture
 * loop and the inference loop live above this class, which makes the policy
 * — the part that is genuinely hard to get right — directly testable.
 *
 * Two rules that matter:
 *
 * 1. **Leading silence is dropped, not buffered.** Buffering it pushes real
 *    speech out of the capped window and makes the model transcribe mostly
 *    empty audio.
 * 2. **The noise floor is calibrated only before speech starts.** Updating it
 *    during a pause mid-sentence lets it drift toward whatever is happening
 *    now, and the threshold follows the speaker instead of the room.
 */
class UtteranceAccumulator(private val config: UtteranceConfig = UtteranceConfig()) {

    private var buffer = FloatArray(INITIAL_CAPACITY)
    private var size = 0
    private var voicedSamples = 0
    private var silentSamples = 0
    private var blocksReceived = 0
    private var noiseFloor = AudioAnalysis.INITIAL_NOISE_FLOOR

    var state: UtteranceState = UtteranceState()
        private set

    /** True once the utterance is long enough to be worth an inference pass. */
    val hasEnoughToTranscribe: Boolean
        get() = size >= config.minSamplesToTranscribe

    /** True when the speaker has paused, or the window is full. */
    val shouldFinalize: Boolean
        get() = size >= config.maxSamples ||
            (voicedSamples > 0 && silentSamples >= config.silenceSamplesToFinalize)

    /**
     * Adds one captured block. Returns true if it was buffered, false if it was
     * leading silence used only to calibrate the noise floor.
     */
    fun append(block: FloatArray): Boolean {
        blocksReceived++
        val energy = AudioAnalysis.meanSquare(block)
        val voiced = AudioAnalysis.isVoiced(energy, noiseFloor)
        val leadingSilence = !voiced && voicedSamples == 0

        if (leadingSilence) {
            noiseFloor += (energy - noiseFloor) * AudioAnalysis.NOISE_FLOOR_EMA_ALPHA
        } else {
            ensureCapacity(size + block.size)
            block.copyInto(buffer, size)
            size += block.size
            if (voiced) {
                silentSamples = 0
                voicedSamples += block.size
            } else {
                silentSamples += block.size
            }
        }

        state = UtteranceState(
            blocksReceived = blocksReceived,
            bufferedSamples = size,
            voicedSamples = voicedSamples,
            silentSamples = silentSamples,
            noiseFloor = noiseFloor,
            lastEnergy = energy,
        )
        return !leadingSilence
    }

    /** A copy of what is buffered, for a partial (non-destructive) transcription pass. */
    fun snapshot(): FloatArray = buffer.copyOf(size)

    /** Takes the finished utterance and starts a new one. The noise floor is kept — the room did not change. */
    fun takeUtterance(): FloatArray {
        val samples = snapshot()
        size = 0
        voicedSamples = 0
        silentSamples = 0
        state = state.copy(bufferedSamples = 0, voicedSamples = 0, silentSamples = 0)
        return samples
    }

    private fun ensureCapacity(required: Int) {
        if (required <= buffer.size) return
        var capacity = buffer.size
        while (capacity < required) capacity *= 2
        buffer = buffer.copyOf(capacity)
    }

    private companion object {
        /** Doubling growth: appending a block must not copy the whole utterance every time. */
        const val INITIAL_CAPACITY = 16_384
    }
}
