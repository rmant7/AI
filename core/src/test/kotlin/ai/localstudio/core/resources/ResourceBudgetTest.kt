package ai.localstudio.core.resources

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResourceBudgetTest {

    @Test
    fun `with nothing reserved, canRun is a plain fits-in-free-RAM check`() {
        val budget = ResourceBudget()
        assertTrue(budget.canRun(ResourceKind.WHISPER, requiredBytes = 500, freeBytesNow = 1_000))
        assertFalse(budget.canRun(ResourceKind.WHISPER, requiredBytes = 1_001, freeBytesNow = 1_000))
    }

    @Test
    fun `another kind's reservation is subtracted from free RAM`() {
        val budget = ResourceBudget()
        budget.reserve(ResourceKind.AICORE_RESERVE, bytes = 4_096)
        assertTrue(budget.canRun(ResourceKind.WHISPER, requiredBytes = 900, freeBytesNow = 5_000))
        assertFalse(budget.canRun(ResourceKind.WHISPER, requiredBytes = 905, freeBytesNow = 5_000))
    }

    @Test
    fun `a kind's own existing reservation is not subtracted from itself`() {
        // Re-checking the same engine that already reserved must not count
        // its own cost twice — that isn't a second, additional cost.
        val budget = ResourceBudget()
        budget.reserve(ResourceKind.LOCAL_LLM, bytes = 2_000)
        assertTrue(budget.canRun(ResourceKind.LOCAL_LLM, requiredBytes = 5_000, freeBytesNow = 5_000))
    }

    @Test
    fun `multiple reservations from different kinds all count against a third kind`() {
        val budget = ResourceBudget()
        budget.reserve(ResourceKind.LOCAL_LLM, bytes = 3_000)
        budget.reserve(ResourceKind.AICORE_RESERVE, bytes = 4_096)
        assertEquals(7_096, budget.totalReservedBytes)
        assertFalse(budget.canRun(ResourceKind.EMBEDDING, requiredBytes = 1, freeBytesNow = 7_096))
        assertTrue(budget.canRun(ResourceKind.EMBEDDING, requiredBytes = 1, freeBytesNow = 7_097))
    }

    @Test
    fun `release clears a reservation so it no longer counts against others`() {
        val budget = ResourceBudget()
        budget.reserve(ResourceKind.VOSK, bytes = 1_000)
        budget.release(ResourceKind.VOSK)
        assertEquals(0, budget.reservedBytes(ResourceKind.VOSK))
        assertTrue(budget.canRun(ResourceKind.WHISPER, requiredBytes = 1_000, freeBytesNow = 1_000))
    }

    @Test
    fun `reserve replaces a kind's previous amount rather than adding to it`() {
        val budget = ResourceBudget()
        budget.reserve(ResourceKind.LOCAL_LLM, bytes = 1_000)
        budget.reserve(ResourceKind.LOCAL_LLM, bytes = 2_000)
        assertEquals(2_000, budget.reservedBytes(ResourceKind.LOCAL_LLM))
    }
}

class AicoreRoomDecisionTest {

    @Test
    fun `no resident model means AICore has the field regardless of free RAM`() {
        assertEquals(AicoreRoomDecision.NO_LOCAL_MODEL, decideAicoreRoom(freeBytesNow = 0, residentBytes = 0, reserveBytes = 4_096))
    }

    @Test
    fun `free RAM at or above the reserve keeps the local model resident`() {
        assertEquals(
            AicoreRoomDecision.SUFFICIENT_HEADROOM,
            decideAicoreRoom(freeBytesNow = 4_096, residentBytes = 2_000, reserveBytes = 4_096),
        )
        assertEquals(
            AicoreRoomDecision.SUFFICIENT_HEADROOM,
            decideAicoreRoom(freeBytesNow = 5_000, residentBytes = 2_000, reserveBytes = 4_096),
        )
    }

    @Test
    fun `free RAM below the reserve, with something resident, evicts idle models`() {
        assertEquals(
            AicoreRoomDecision.EVICT_IDLE,
            decideAicoreRoom(freeBytesNow = 4_095, residentBytes = 2_000, reserveBytes = 4_096),
        )
    }
}
