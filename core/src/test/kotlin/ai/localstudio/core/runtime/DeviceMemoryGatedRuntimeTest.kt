package ai.localstudio.core.runtime

import ai.localstudio.core.binding
import ai.localstudio.core.model
import ai.localstudio.core.registry.ModelDescriptor
import ai.localstudio.core.registry.RuntimeBinding
import ai.localstudio.core.registry.RuntimeKind
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Reports whether it ever overlapped with another instance sharing the same [inFlight]/[overlapDetected]. */
private class SlowTextRuntime(
    private val answer: String,
    private val inFlight: AtomicInteger,
    private val overlapDetected: AtomicBoolean,
) : ModelRuntime {
    override val kind = RuntimeKind.LLAMA_CPP
    override fun canRun(model: ModelDescriptor, binding: RuntimeBinding) = true
    override suspend fun load(model: ModelDescriptor, binding: RuntimeBinding): LoadedModel =
        object : TextModelHandle {
            override val modelId = model.id
            override val ramBytes = 0L
            override fun generate(request: GenerationRequest): Flow<String> = flow {
                if (inFlight.incrementAndGet() > 1) overlapDetected.set(true)
                delay(30)
                inFlight.decrementAndGet()
                emit(answer)
            }
            override fun requestCancel() = Unit
            override fun close() = Unit
        }
}

class DeviceMemoryGatedRuntimeTest {

    @Test
    fun `two runtimes sharing a gate never generate at the same time`() = runBlocking {
        val gate = Mutex()
        val inFlight = AtomicInteger(0)
        val overlapDetected = AtomicBoolean(false)
        val local = DeviceMemoryGatedRuntime(SlowTextRuntime("local", inFlight, overlapDetected), gate)
        val aicore = DeviceMemoryGatedRuntime(SlowTextRuntime("aicore", inFlight, overlapDetected), gate)
        val llm = model("llm")

        val results = listOf(
            async {
                (local.load(llm, llm.bindings.first()) as TextModelHandle)
                    .generate(GenerationRequest(prompt = "a")).toList()
            },
            async {
                (aicore.load(llm, llm.bindings.first()) as TextModelHandle)
                    .generate(GenerationRequest(prompt = "b")).toList()
            },
        ).awaitAll()

        assertFalse(overlapDetected.get())
        assertEquals(setOf(listOf("local"), listOf("aicore")), results.toSet())
    }

    @Test
    fun `an ungated candidate is unaffected by another one's gate`() = runBlocking {
        val gate = Mutex()
        val inFlight = AtomicInteger(0)
        val overlapDetected = AtomicBoolean(false)
        val gated = DeviceMemoryGatedRuntime(SlowTextRuntime("gated", inFlight, overlapDetected), gate)
        val ungated = SlowTextRuntime("ungated", inFlight, overlapDetected)
        val llm = model("llm")

        // Held for the whole test so the gated candidate below has to wait —
        // an ungated one must run anyway, exactly as a cloud candidate (no
        // device memory footprint to protect) keeps working while a local
        // load and AICore's own generate() take turns behind the gate.
        gate.lock()
        val gatedResult = async {
            (gated.load(llm, llm.bindings.first()) as TextModelHandle)
                .generate(GenerationRequest(prompt = "a")).toList()
        }
        val ungatedResult = (ungated.load(llm, llm.bindings.first()) as TextModelHandle)
            .generate(GenerationRequest(prompt = "b")).toList()

        assertEquals(listOf("ungated"), ungatedResult)
        assertTrue(gatedResult.isActive)
        gate.unlock()
        assertEquals(listOf("gated"), gatedResult.await())
    }

    @Test
    fun `requestCancel and close delegate to the underlying handle`() = runBlocking {
        var cancelled = false
        var closed = false
        val inner = object : ModelRuntime {
            override val kind = RuntimeKind.AICORE
            override fun canRun(model: ModelDescriptor, binding: RuntimeBinding) = true
            override suspend fun load(model: ModelDescriptor, binding: RuntimeBinding): LoadedModel =
                object : TextModelHandle {
                    override val modelId = model.id
                    override val ramBytes = 0L
                    override fun generate(request: GenerationRequest): Flow<String> = flowOf("hi")
                    override fun requestCancel() {
                        cancelled = true
                    }
                    override fun close() {
                        closed = true
                    }
                }
        }
        val llm = model("llm", bindings = listOf(binding(runtime = RuntimeKind.AICORE)))
        val handle = DeviceMemoryGatedRuntime(inner, Mutex()).load(llm, llm.bindings.first()) as TextModelHandle

        handle.requestCancel()
        handle.close()

        assertTrue(cancelled)
        assertTrue(closed)
    }
}
