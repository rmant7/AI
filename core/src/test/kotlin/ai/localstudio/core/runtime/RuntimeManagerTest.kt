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
    fun `an explicit runtime is used instead of the registered one`() = runBlocking {
        val manager = RuntimeManager(budgetBytes = 6 * GB, runtimes = emptyMap(), clock = { ++now })
        val llm = model("llm", bindings = listOf(binding(ramBytes = 1 * GB)))

        manager.withModel(llm, llm.bindings.first(), runtime) { }

        assertEquals(listOf("llm"), runtime.loads)
    }

    @Test
    fun `the budget is read on every acquisition`() = runBlocking {
        var budget = 2 * GB
        val manager = RuntimeManager(budgetBytes = { budget }, runtimes = mapOf(RuntimeKind.LLAMA_CPP to runtime), clock = { ++now })
        val llm = model("llm", bindings = listOf(binding(ramBytes = 3 * GB)))

        assertFailsWith<InsufficientMemoryException> { manager.withModel(llm, llm.bindings.first()) { } }
        budget = 4 * GB
        manager.withModel(llm, llm.bindings.first()) { }

        assertEquals(listOf("llm"), runtime.loads)
    }

    @Test
    fun `a lenient manager evicts everything idle and attempts an over-budget model`() = runBlocking {
        val manager = RuntimeManager(
            budgetBytes = { 4 * GB },
            runtimes = mapOf(RuntimeKind.LLAMA_CPP to runtime),
            clock = { ++now },
            strictBudget = false,
        )
        val small = model("small", bindings = listOf(binding(ramBytes = 1 * GB)))
        val huge = model("huge", bindings = listOf(binding(ramBytes = 6 * GB)))

        manager.withModel(small, small.bindings.first()) { }
        manager.withModel(huge, huge.bindings.first()) { }

        assertEquals(listOf("small"), runtime.unloads)
        assertEquals(listOf("huge"), manager.residentModels().map { it.modelId })
    }

    @Test
    fun `a lenient manager still refuses while another model is in use`() = runBlocking {
        val manager = RuntimeManager(
            budgetBytes = { 4 * GB },
            runtimes = mapOf(RuntimeKind.LLAMA_CPP to runtime),
            clock = { ++now },
            strictBudget = false,
        )
        val busy = model("busy", bindings = listOf(binding(ramBytes = 1 * GB)))
        val huge = model("huge", bindings = listOf(binding(ramBytes = 6 * GB)))

        assertFailsWith<InsufficientMemoryException> {
            manager.withModel(busy, busy.bindings.first()) {
                manager.withModel(huge, huge.bindings.first()) { }
            }
        }
        assertEquals(listOf("busy"), runtime.loads)
    }

    @Test
    fun `an idle copy of another variant is reloaded, not reused`() = runBlocking {
        val manager = manager(6 * GB)
        val llm = model("llm", bindings = listOf(binding(ramBytes = 1 * GB)))

        manager.withModel(llm, llm.bindings.first(), variant = 2048) { }
        manager.withModel(llm, llm.bindings.first(), variant = 2048) { }
        manager.withModel(llm, llm.bindings.first(), variant = 4096) { }

        assertEquals(listOf("llm", "llm"), runtime.loads)
        assertEquals(listOf("llm"), runtime.unloads)
    }

    @Test
    fun `an exclusive manager keeps at most one idle model resident`() = runBlocking {
        val manager = RuntimeManager(
            budgetBytes = { 16 * GB },
            runtimes = mapOf(RuntimeKind.LLAMA_CPP to runtime),
            clock = { ++now },
            exclusive = true,
        )
        val gemma = model("gemma", bindings = listOf(binding(ramBytes = 2 * GB)))
        val qwen = model("qwen", bindings = listOf(binding(ramBytes = 2 * GB)))

        manager.withModel(gemma, gemma.bindings.first()) { }
        manager.withModel(qwen, qwen.bindings.first()) { }

        assertEquals(listOf("gemma"), runtime.unloads)
        assertEquals(listOf("qwen"), manager.residentModels().map { it.modelId })
    }
}
