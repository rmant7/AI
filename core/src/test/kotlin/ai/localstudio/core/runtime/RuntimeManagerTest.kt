package ai.localstudio.core.runtime

import ai.localstudio.core.GB
import ai.localstudio.core.binding
import ai.localstudio.core.model
import ai.localstudio.core.registry.ModelDescriptor
import ai.localstudio.core.registry.RuntimeBinding
import ai.localstudio.core.registry.RuntimeKind
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private class FakeLoadedModel(
    override val modelId: String,
    override val ramBytes: Long,
    private val onClose: (String) -> Unit,
) : LoadedModel {
    override fun close() = onClose(modelId)
}

private class FakeRuntime(override val kind: RuntimeKind = RuntimeKind.LLAMA_CPP) : ModelRuntime {
    val loads = mutableListOf<String>()
    val unloads = mutableListOf<String>()

    override fun canRun(model: ModelDescriptor, binding: RuntimeBinding) = true

    override suspend fun load(model: ModelDescriptor, binding: RuntimeBinding): LoadedModel {
        loads += model.id
        return FakeLoadedModel(model.id, binding.effectiveRequiredRamBytes) { unloads += it }
    }
}

class RuntimeManagerTest {

    private var now = 0L
    private val runtime = FakeRuntime()
    private fun manager(budgetBytes: Long) = RuntimeManager(
        budgetBytes = budgetBytes,
        runtimes = mapOf(RuntimeKind.LLAMA_CPP to runtime),
        clock = { ++now },
    )

    @Test
    fun `a released model stays resident and is reused without reloading`() = runBlocking {
        val manager = manager(6 * GB)
        val llm = model("llm", bindings = listOf(binding(ramBytes = 2 * GB)))

        manager.withModel(llm, llm.bindings.first()) { }
        manager.withModel(llm, llm.bindings.first()) { }

        assertEquals(listOf("llm"), runtime.loads)
        assertTrue(runtime.unloads.isEmpty())
        assertEquals(2 * GB, manager.residentBytes)
    }

    @Test
    fun `models coexist while the budget allows it`() = runBlocking {
        val manager = manager(6 * GB)
        val asr = model("asr", bindings = listOf(binding(ramBytes = 1 * GB)))
        val llm = model("llm", bindings = listOf(binding(ramBytes = 3 * GB)))

        manager.withModel(asr, asr.bindings.first()) { }
        manager.withModel(llm, llm.bindings.first()) { }

        assertEquals(setOf("asr", "llm"), manager.residentModels().map { it.modelId }.toSet())
        assertTrue(runtime.unloads.isEmpty())
    }

    @Test
    fun `the least recently used idle model is evicted under pressure`() = runBlocking {
        val manager = manager(4 * GB)
        val asr = model("asr", bindings = listOf(binding(ramBytes = 1 * GB)))
        val vision = model("vision", bindings = listOf(binding(ramBytes = 1 * GB)))
        val llm = model("llm", bindings = listOf(binding(ramBytes = 3 * GB)))

        manager.withModel(asr, asr.bindings.first()) { }
        manager.withModel(vision, vision.bindings.first()) { }
        manager.withModel(llm, llm.bindings.first()) { }

        assertEquals(listOf("asr"), runtime.unloads)
        assertEquals(setOf("vision", "llm"), manager.residentModels().map { it.modelId }.toSet())
    }

    @Test
    fun `a model in use is never evicted`() = runBlocking {
        val manager = manager(4 * GB)
        val asr = model("asr", bindings = listOf(binding(ramBytes = 2 * GB)))
        val llm = model("llm", bindings = listOf(binding(ramBytes = 3 * GB)))

        val failure = assertFailsWith<InsufficientMemoryException> {
            manager.withModel(asr, asr.bindings.first()) {
                manager.withModel(llm, llm.bindings.first()) { }
            }
        }

        assertEquals(3 * GB, failure.requestedBytes)
        assertTrue(runtime.unloads.isEmpty())
        assertEquals(listOf("asr"), manager.residentModels().map { it.modelId })
    }

    @Test
    fun `a model larger than the whole budget fails before any eviction`() = runBlocking {
        val manager = manager(2 * GB)
        val asr = model("asr", bindings = listOf(binding(ramBytes = 1 * GB)))
        val huge = model("huge", bindings = listOf(binding(ramBytes = 8 * GB)))

        manager.withModel(asr, asr.bindings.first()) { }
        assertFailsWith<InsufficientMemoryException> {
            manager.withModel(huge, huge.bindings.first()) { }
        }

        assertTrue(runtime.unloads.isEmpty())
        assertEquals(listOf("asr"), manager.residentModels().map { it.modelId })
    }

    @Test
    fun `evictIdle frees everything that is not in use`() = runBlocking {
        val manager = manager(6 * GB)
        val asr = model("asr", bindings = listOf(binding(ramBytes = 1 * GB)))

        manager.withModel(asr, asr.bindings.first()) { }
        manager.evictIdle()

        assertEquals(listOf("asr"), runtime.unloads)
        assertEquals(0, manager.residentBytes)
    }

    @Test
    fun `loading without a registered runtime fails loudly`() = runBlocking {
        val manager = RuntimeManager(budgetBytes = 6 * GB, runtimes = emptyMap(), clock = { ++now })
        val llm = model("llm")

        assertFailsWith<ModelLoadException> {
            manager.withModel(llm, llm.bindings.first()) { }
        }
        Unit
    }

    @Test
    fun `rewire merges runtime kinds instead of replacing them`() = runBlocking {
        // Two callers sharing one manager (e.g. chat's own path and a
        // Compare-mode source), each rewiring only the kind it just
        // rebuilt — as AppContainer.buildOrchestrator now does for every
        // orchestrator it builds against one shared manager.
        val manager = RuntimeManager(budgetBytes = 6 * GB, runtimes = emptyMap(), clock = { ++now })
        val aicore = FakeRuntime(RuntimeKind.AICORE)

        manager.rewire(6 * GB, mapOf(RuntimeKind.LLAMA_CPP to runtime))
        manager.rewire(6 * GB, mapOf(RuntimeKind.AICORE to aicore))

        // Both kinds still resolve — the second rewire must not have
        // dropped the first's entry.
        val llm = model("llm", bindings = listOf(binding(ramBytes = 1 * GB)))
        manager.withModel(llm, llm.bindings.first()) { }
        assertEquals(listOf("llm"), runtime.loads)
    }

    @Test
    fun `a model stays resident across a rewire with a freshly-built runtime wrapper`() = runBlocking {
        // Simulates a settings change rebuilding the ModelRuntime wrapper
        // (new contextTokens/temperature baked in) for the same
        // RuntimeKind — the already-loaded model must not reload just
        // because the wrapper instance registered for its kind changed.
        val manager = RuntimeManager(budgetBytes = 6 * GB, runtimes = emptyMap(), clock = { ++now })
        val llm = model("llm", bindings = listOf(binding(ramBytes = 1 * GB)))

        manager.rewire(6 * GB, mapOf(RuntimeKind.LLAMA_CPP to runtime))
        manager.withModel(llm, llm.bindings.first()) { }

        val rebuiltRuntime = FakeRuntime()
        manager.rewire(6 * GB, mapOf(RuntimeKind.LLAMA_CPP to rebuiltRuntime))
        manager.withModel(llm, llm.bindings.first()) { }

        assertEquals(listOf("llm"), runtime.loads)
        assertTrue(rebuiltRuntime.loads.isEmpty())
    }
}
