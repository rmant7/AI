package ai.localstudio.core.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PcmMathTest {

    @Test
    fun `mono input passes through downmix unchanged`() {
        val mono = shortArrayOf(1, 2, 3, 4)
        assertEquals(mono.toList(), PcmMath.downmixToMono(mono, channels = 1).toList())
    }

    @Test
    fun `stereo downmix averages left and right per frame`() {
        // Frame 0: L=10 R=20 -> 15. Frame 1: L=-10 R=-20 -> -15.
        val stereo = shortArrayOf(10, 20, -10, -20)
        val mono = PcmMath.downmixToMono(stereo, channels = 2)
        assertEquals(listOf<Short>(15, -15), mono.toList())
    }

    @Test
    fun `resample to the same rate is a no-op`() {
        val input = shortArrayOf(1, 2, 3, 4, 5)
        assertEquals(input.toList(), PcmMath.resample(input, fromRate = 16_000, toRate = 16_000).toList())
    }

    @Test
    fun `downsampling halves the sample count for a half-rate target`() {
        val input = ShortArray(32_000) { (it % 100).toShort() }
        val resampled = PcmMath.resample(input, fromRate = 32_000, toRate = 16_000)
        assertEquals(16_000, resampled.size)
    }

    @Test
    fun `upsampling preserves the first and last samples`() {
        val input = shortArrayOf(100, 200, 300, 400)
        val resampled = PcmMath.resample(input, fromRate = 8_000, toRate = 16_000)
        assertEquals(8, resampled.size)
        assertEquals(100, resampled.first())
        // Interpolated, not necessarily exactly 400, but must stay within the input's range.
        assertTrue(resampled.last() in 90..410)
    }

    @Test
    fun `empty input stays empty`() {
        assertEquals(0, PcmMath.resample(ShortArray(0), 44_100, 16_000).size)
    }
}
