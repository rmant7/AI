package ai.localstudio.core.runtime

import ai.localstudio.core.model
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OperationTimeoutTest {

    private fun slowRuntime(workMs: Long) = object : ModelRuntime {
        override val kind = ai.localstudio.core.registry.RuntimeKind.LLAMA_CPP
        override fun canRun(model: ai.localstudio.core.registry.ModelDescriptor, binding: ai.localstudio.core.registry.RuntimeBinding) = true
        override suspend fun load(model: ai.localstudio.core.registry.ModelDescriptor, binding: ai.localstudio.core.registry.RuntimeBinding): LoadedModel =
            object : TextModelHandle {
                override val modelId = model.id
                override val ramBytes = 0L
                override fun generate(request: GenerationRequest): Flow<String> = flow {
                    delay(workMs)
                    emit("done")
                }
                override fun requestCancel() = Unit
                override fun close() = Unit
            }
    }

    private suspend fun generateThroughGate(gate: Mutex, workMs: Long): List<String> {
        val llm = model("llm")
        val runtime = DeviceMemoryGatedRuntime(slowRuntime(workMs), gate)
        val handle = runtime.load(llm, llm.bindings.first()) as TextModelHandle
        return handle.generate(GenerationRequest(prompt = "a")).toList()
    }

    @Test
    fun `time queued on the gate does not count against the operation's own timeout`() = runBlocking {
        val gate = Mutex()
        gate.lock()
        launch { delay(400); gate.unlock() }

        // 400 ms queued + 150 ms of work, against a 300 ms budget: passes only
        // because the queueing is excluded.
        val result = withOperationTimeout(timeoutMs = 300, deadlineMs = 5_000, tickMs = 20) {
            generateThroughGate(gate, workMs = 150)
        }
        assertEquals(listOf("done"), result)
    }

    @Test
    fun `the operation's own work still times out`() = runBlocking {
        val failure = assertFailsWith<OperationTimeoutException> {
            withOperationTimeout(timeoutMs = 100, deadlineMs = 5_000, tickMs = 20) {
                generateThroughGate(Mutex(), workMs = 1_000)
            }
        }
        assertEquals(false, failure.deadlineHit)
    }

    @Test
    fun `the deadline bounds everything, queueing included`() = runBlocking {
        val gate = Mutex()
        gate.lock()
        val failure = assertFailsWith<OperationTimeoutException> {
            withOperationTimeout(timeoutMs = 10_000, deadlineMs = 200, tickMs = 20) {
                generateThroughGate(gate, workMs = 10)
            }
        }
        assertTrue(failure.deadlineHit)
        gate.unlock()
    }

    @Test
    fun `a caller's own cancellation propagates as cancellation, not a timeout`() = runBlocking {
        val job = async {
            withOperationTimeout(timeoutMs = 10_000, deadlineMs = 10_000, tickMs = 20) {
                delay(5_000)
            }
        }
        delay(50)
        job.cancel()
        val thrown = runCatching { job.await() }.exceptionOrNull()
        assertTrue(thrown is CancellationException && thrown !is OperationTimeoutException)
    }

    @Test
    fun `an ordinary failure inside the block propagates unchanged`() = runBlocking {
        assertFailsWith<IllegalStateException> {
            withOperationTimeout(timeoutMs = 10_000, deadlineMs = 10_000, tickMs = 20) {
                error("boom")
            }
        }
    }
}
