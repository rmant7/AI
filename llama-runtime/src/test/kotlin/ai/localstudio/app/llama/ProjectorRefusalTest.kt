package ai.localstudio.app.llama

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The vision projector's live check, on the figures IntelliVerse #163 logged on a Pixel 10 Pro. */
class ProjectorRefusalTest {
    private val mb = 1_000_000L

    @Test
    fun `mapped weights in the free reading are not free for the projector`() {
        // Gemma 4 E4B mapped: 5327 MB "free" right after its load, 4980 MB of it its own weights. Ran on swap for 190 s.
        val refusal = projectorRefusal(freeBytes = 5327 * mb, ownMappedWeightsBytes = 4980 * mb, needBytes = 1386 * mb)
        assertNotNull(refusal)
        assertTrue(refusal!!, "mapped weights" in refusal)
    }

    @Test
    fun `too little left after the projector is refused even with the weights in memory`() {
        // E4B read into memory: 1886 MB free, projector 1386 MB -- 229 s to the first token.
        assertNotNull(projectorRefusal(freeBytes = 1886 * mb, ownMappedWeightsBytes = 0, needBytes = 1386 * mb))
    }

    @Test
    fun `room enough is admitted`() {
        // Gemma 4 E2B mapped: 5837 MB free, ~3350 MB its weights, projector 1381 MB -- answered in 14 s.
        assertNull(projectorRefusal(freeBytes = 5837 * mb, ownMappedWeightsBytes = 3350 * mb, needBytes = 1381 * mb))
    }
}
