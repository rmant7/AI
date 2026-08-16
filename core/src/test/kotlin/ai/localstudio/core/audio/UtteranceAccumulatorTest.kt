package ai.localstudio.core.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UtteranceAccumulatorTest {

    private val config = UtteranceConfig()

    /** One block of constant-amplitude samples — energy is amplitude². */
    private fun block(amplitude: Float, samples: Int = 4096) = FloatArray(samples) { amplitude }

    private fun quiet(samples: Int = 4096) = block(0.001f, samples)
    private fun loud(samples: Int = 4096) = block(0.2f, samples)

    @Test
    fun `leading silence is not buffered but does calibrate the noise floor`() {
        val accumulator = UtteranceAccumulator(config)

        repeat(10) { accumulator.append(quiet()) }

        assertEquals(0, accumulator.state.bufferedSamples)
        assertEquals(10, accumulator.state.blocksReceived)
        assertTrue(accumulator.state.noiseFloor < AudioAnalysis.INITIAL_NOISE_FLOOR)
    }

    @Test
    fun `speech is detected relative to a quiet room, where a fixed threshold would fail`() {
        val accumulator = UtteranceAccumulator(config)
        // Ambient level far below any plausible hard-coded constant.
        repeat(30) { accumulator.append(block(1e-4f)) }

        // Speech that is loud *for this room* but tiny in absolute terms.
        val buffered = accumulator.append(block(1e-2f))

        assertTrue(buffered)
        assertTrue(accumulator.state.voicedSamples > 0)
    }

    @Test
    fun `an utterance is finalized after a pause and returns exactly what was buffered`() {
        val accumulator = UtteranceAccumulator(config)
        repeat(5) { accumulator.append(loud()) }
        assertFalse(accumulator.shouldFinalize)

        // 800 ms of silence at 16 kHz = 12800 samples; blocks of 4096 need four.
        repeat(4) { accumulator.append(quiet()) }

        assertTrue(accumulator.shouldFinalize)
        val utterance = accumulator.takeUtterance()
        assertEquals(9 * 4096, utterance.size)
        assertEquals(0, accumulator.state.bufferedSamples)
        assertFalse(accumulator.shouldFinalize)
    }

    @Test
    fun `silence alone never finalizes an utterance`() {
        val accumulator = UtteranceAccumulator(config)

        repeat(50) { accumulator.append(quiet()) }

        assertFalse(accumulator.shouldFinalize)
        assertEquals(0, accumulator.state.bufferedSamples)
    }

    @Test
    fun `the window is capped even when the speaker never pauses`() {
        val accumulator = UtteranceAccumulator(config)

        while (accumulator.state.bufferedSamples < config.maxSamples) {
            accumulator.append(loud())
        }

        assertTrue(accumulator.shouldFinalize)
        assertTrue(accumulator.takeUtterance().size >= config.maxSamples)
    }

    @Test
    fun `a pause mid-utterance does not drag the noise floor upward`() {
        val accumulator = UtteranceAccumulator(config)
        repeat(10) { accumulator.append(quiet()) }
        val calibrated = accumulator.state.noiseFloor

        accumulator.append(loud())
        repeat(3) { accumulator.append(quiet()) }

        assertEquals(calibrated, accumulator.state.noiseFloor)
    }

    @Test
    fun `short audio is not sent to inference`() {
        val accumulator = UtteranceAccumulator(config)

        accumulator.append(loud(samples = 1000)) // 62 ms
        assertFalse(accumulator.hasEnoughToTranscribe)

        accumulator.append(loud(samples = 8000)) // now well past 400 ms
        assertTrue(accumulator.hasEnoughToTranscribe)
    }

    @Test
    fun `the noise floor survives finalization because the room did not change`() {
        val accumulator = UtteranceAccumulator(config)
        repeat(10) { accumulator.append(quiet()) }
        val calibrated = accumulator.state.noiseFloor

        repeat(5) { accumulator.append(loud()) }
        repeat(4) { accumulator.append(quiet()) }
        accumulator.takeUtterance()

        assertEquals(calibrated, accumulator.state.noiseFloor)
    }

    @Test
    fun `the buffer grows correctly across many blocks`() {
        val accumulator = UtteranceAccumulator(config)
        val amplitudes = (1..30).map { 0.1f + it / 100f }

        amplitudes.forEach { accumulator.append(block(it, samples = 1000)) }
        val utterance = accumulator.snapshot()

        assertEquals(30_000, utterance.size)
        assertEquals(amplitudes.first(), utterance.first())
        assertEquals(amplitudes.last(), utterance.last())
    }

    @Test
    fun `voice detection has a floor under the floor`() {
        // In a near-silent room the estimate approaches zero; without a minimum,
        // sensor noise multiplied up would read as speech.
        assertFalse(AudioAnalysis.isVoiced(energy = 1e-8f, noiseFloor = 0f))
        assertTrue(AudioAnalysis.isVoiced(energy = 1e-3f, noiseFloor = 0f))
    }

    @Test
    fun `mean square is zero for an empty block`() {
        assertEquals(0f, AudioAnalysis.meanSquare(FloatArray(0)))
        assertEquals(0.25f, AudioAnalysis.meanSquare(FloatArray(10) { 0.5f }))
    }
}
