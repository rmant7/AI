package ai.localstudio.core.errors

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FallbackPolicyTest {

    @Test
    fun `UNAVAILABLE means cool this model down`() {
        assertEquals(FallbackAction.COOLDOWN_MODEL, FallbackPolicy.actionFor(AIErrorCode.UNAVAILABLE))
    }

    @Test
    fun `INVALID_REQUEST means skip the rest of this provider's models this turn`() {
        assertEquals(FallbackAction.SKIP_PROVIDER_THIS_TURN, FallbackPolicy.actionFor(AIErrorCode.INVALID_REQUEST))
    }

    @Test
    fun `every other code takes no special action beyond the ordinary next-candidate fallback`() {
        val handled = setOf(AIErrorCode.UNAVAILABLE, AIErrorCode.INVALID_REQUEST)
        for (code in AIErrorCode.entries.filterNot { it in handled }) {
            assertEquals(FallbackAction.NONE, FallbackPolicy.actionFor(code), "unexpected action for $code")
        }
    }

    @Test
    fun `RATE_LIMIT and QUOTA both rotate the key`() {
        assertTrue(FallbackPolicy.shouldRotateKey(AIErrorCode.RATE_LIMIT))
        assertTrue(FallbackPolicy.shouldRotateKey(AIErrorCode.QUOTA))
    }

    @Test
    fun `authentication and other codes do not rotate the key`() {
        assertFalse(FallbackPolicy.shouldRotateKey(AIErrorCode.AUTHENTICATION))
        assertFalse(FallbackPolicy.shouldRotateKey(AIErrorCode.UNAVAILABLE))
        assertFalse(FallbackPolicy.shouldRotateKey(AIErrorCode.NETWORK))
    }
}
