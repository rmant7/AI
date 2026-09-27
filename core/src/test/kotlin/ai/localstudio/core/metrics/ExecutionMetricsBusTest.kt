package ai.localstudio.core.metrics

import ai.localstudio.core.errors.AIError
import ai.localstudio.core.errors.AIErrorCode
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun sampleMetrics(provider: String = "groq") = ExecutionMetrics(
    provider = provider,
    model = "openai/gpt-oss-120b",
    startEpochMs = 1_000L,
    durationMs = 500L,
)

class ExecutionMetricsTest {

    @Test
    fun `succeeded is true when there is no error`() {
        assertTrue(sampleMetrics().succeeded)
    }

    @Test
    fun `succeeded is false once an error is attached`() {
        val metrics = sampleMetrics().copy(error = AIError(AIErrorCode.UNAVAILABLE, "overloaded"))
        assertEquals(false, metrics.succeeded)
    }
}

class ExecutionMetricsBusTest {

    @AfterTest
    fun clearListeners() {
        // The bus is a singleton object shared across every test in this
        // process — a listener a previous test added and never removed
        // would otherwise leak into every test that runs after it.
        knownListeners.forEach(ExecutionMetricsBus::removeListener)
        knownListeners.clear()
    }

    private val knownListeners = mutableListOf<ExecutionMetricsListener>()

    private fun listen(onEvent: (ExecutionMetrics) -> Unit): ExecutionMetricsListener =
        ExecutionMetricsListener(onEvent).also {
            knownListeners += it
            ExecutionMetricsBus.addListener(it)
        }

    @Test
    fun `every registered listener sees a posted metrics instance`() {
        val seenByA = mutableListOf<ExecutionMetrics>()
        val seenByB = mutableListOf<ExecutionMetrics>()
        listen { seenByA += it }
        listen { seenByB += it }

        ExecutionMetricsBus.post(sampleMetrics())

        assertEquals(1, seenByA.size)
        assertEquals(1, seenByB.size)
    }

    @Test
    fun `a removed listener no longer receives posts`() {
        val seen = mutableListOf<ExecutionMetrics>()
        val listener = listen { seen += it }
        ExecutionMetricsBus.removeListener(listener)

        ExecutionMetricsBus.post(sampleMetrics())

        assertEquals(0, seen.size)
    }

    @Test
    fun `a listener that throws does not stop other listeners from being called`() {
        val seen = mutableListOf<ExecutionMetrics>()
        listen { throw IllegalStateException("listener bug") }
        listen { seen += it }

        ExecutionMetricsBus.post(sampleMetrics())

        assertEquals(1, seen.size)
    }
}
