package ai.localstudio.core.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ClassifyCompareFailureTest {

    @Test
    fun `an ordinary exception with hideOnFailure false becomes Failed, preserving the error`() {
        val error = IllegalStateException("boom")
        val outcome = classifyCompareFailure(error, hideOnFailure = false)
        val failed = assertIs<CompareOutcome.Failed>(outcome)
        assertEquals(error, failed.error)
    }

    @Test
    fun `an ordinary exception with hideOnFailure true becomes Hidden instead`() {
        assertEquals(CompareOutcome.Hidden, classifyCompareFailure(IllegalStateException("boom"), hideOnFailure = true))
    }

    @Test
    fun `TimeoutCancellationException is treated as an ordinary failure, not propagated`() {
        val error = runCatching { runBlocking { withTimeout(1) { delay(1_000) } } }
            .exceptionOrNull() as TimeoutCancellationException

        assertIs<CompareOutcome.Failed>(classifyCompareFailure(error, hideOnFailure = false))
        assertEquals(CompareOutcome.Hidden, classifyCompareFailure(error, hideOnFailure = true))
    }

    @Test
    fun `a plain CancellationException must propagate regardless of hideOnFailure`() {
        val error = CancellationException("stop")
        assertEquals(null, classifyCompareFailure(error, hideOnFailure = false))
        assertEquals(null, classifyCompareFailure(error, hideOnFailure = true))
    }
}

class RunCompareTest {

    @Test
    fun `every candidate's outcome is reported, success and failure alike`() = runBlocking {
        val candidates = listOf(
            CompareCandidate(label = "a", run = { "answer-a" }),
            CompareCandidate(label = "b", run = { throw IllegalStateException("b failed") }),
            CompareCandidate(label = "c", hideOnFailure = true, run = { throw IllegalStateException("c failed") }),
        )
        val outcomes = mutableMapOf<Int, CompareOutcome<String>>()

        runCompare(candidates) { index, outcome -> outcomes[index] = outcome }

        assertEquals(CompareOutcome.Success("answer-a"), outcomes[0])
        assertIs<CompareOutcome.Failed>(outcomes[1])
        assertEquals(CompareOutcome.Hidden, outcomes[2])
    }

    @Test
    fun `a faster candidate is reported before a slower one finishes, not held back`() = runBlocking {
        val order = ConcurrentLinkedQueue<String>()
        val candidates = listOf(
            CompareCandidate(label = "slow", run = { delay(200); "slow-done" }),
            CompareCandidate(label = "fast", run = { "fast-done" }),
        )

        runCompare(candidates) { index, _ -> order += candidates[index].label }

        assertEquals(listOf("fast", "slow"), order.toList())
    }

    @Test
    fun `an empty candidate list completes immediately, calling onOutcome zero times`() = runBlocking {
        var calls = 0
        runCompare(emptyList<CompareCandidate<String>>()) { _, _ -> calls++ }
        assertEquals(0, calls)
    }

    @Test
    fun `cancelling the caller stops every still-running candidate`() = runBlocking {
        var fastReported = false
        var slowReported = false
        val job = launch {
            val candidates = listOf(
                CompareCandidate<String>(label = "fast", run = { "done" }),
                CompareCandidate<String>(label = "slow", run = { delay(10_000); "unreachable" }),
            )
            runCompare(candidates) { index, _ -> if (index == 0) fastReported = true else slowReported = true }
        }
        // Let the fast candidate finish and report, then cancel before the slow one ever could.
        delay(50)
        job.cancelAndJoin()

        assertTrue(fastReported)
        assertTrue(!slowReported)
    }

}
