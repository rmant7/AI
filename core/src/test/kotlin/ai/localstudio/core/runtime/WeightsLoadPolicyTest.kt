package ai.localstudio.core.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WeightsLoadPolicyTest {

    private val mb = 1_000_000L

    @Test
    fun `auto maps an unmeasured model so that the load measures it`() {
        val d = WeightsLoadPolicy.decide(WeightsLoading.AUTO, null, 4683 * mb)
        assertTrue(d.mapped)
        assertTrue("not measured" in d.reason)
    }

    @Test
    fun `auto reads a model into memory when mapping made llama_cpp copy its weights`() {
        // Pixel 10 Pro, #485: Qwen2.5-VL-7B mapped, +3931 MB anonymous for a 4683 MB file.
        assertFalse(WeightsLoadPolicy.decide(WeightsLoading.AUTO, 3931 * mb, 4683 * mb).mapped)
        // Index-Translate-2B: +1384 MB anonymous for 1312 MB.
        assertFalse(WeightsLoadPolicy.decide(WeightsLoading.AUTO, 1384 * mb, 1312 * mb).mapped)
    }

    @Test
    fun `auto keeps a model mapped when its file pages carry the weights`() {
        // Gemma 4 E2B: ~1.4 GB of a 3349 MB file.
        val d = WeightsLoadPolicy.decide(WeightsLoading.AUTO, 1400 * mb, 3349 * mb)
        assertTrue(d.mapped)
        assertTrue("0.42" in d.reason, d.reason)
    }

    @Test
    fun `a forced mode is taken as set, measured or not`() {
        assertTrue(WeightsLoadPolicy.decide(WeightsLoading.MAPPED, 3931 * mb, 4683 * mb).mapped)
        assertFalse(WeightsLoadPolicy.decide(WeightsLoading.IN_MEMORY, null, 4683 * mb).mapped)
        assertEquals(true, WeightsLoadPolicy.decide(WeightsLoading.AUTO, 3931 * mb, 0).mapped, "no file size: nothing to compare")
    }
}
