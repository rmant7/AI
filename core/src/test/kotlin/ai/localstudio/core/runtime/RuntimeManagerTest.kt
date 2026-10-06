package ai.localstudio.core.runtime

import ai.localstudio.core.GB
import ai.localstudio.core.binding
import ai.localstudio.core.model
import ai.localstudio.core.registry.ModelDescriptor
import ai.localstudio.core.registry.RuntimeBinding
import ai.localstudio.core.registry.RuntimeKind
import kotlinx.coroutines.launch
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
    fun `a copy in use is never handed out for another variant -- the request waits, then reloads`() = runBlocking {
        val manager = manager(6 * GB)
        val llm = model("llm", bindings = listOf(binding(ramBytes = 1 * GB)))
        val holding = kotlinx.coroutines.CompletableDeferred<Unit>()
        val loadedShort = kotlinx.coroutines.CompletableDeferred<Unit>()

        val short = launch {
            manager.withModel(llm, llm.bindings.first(), variant = 2048) {
                loadedShort.complete(Unit)
                holding.await()
            }
        }
        loadedShort.await()
        var longGot = false
        val long = launch {
            manager.withModel(llm, llm.bindings.first(), variant = 4096) { longGot = true }
        }
        repeat(10) { kotlinx.coroutines.yield() }
        assertEquals(false, longGot, "the 2048-token copy must not serve a 4096-token request")
        assertEquals(listOf("llm"), runtime.loads)

        holding.complete(Unit)
        short.join()
        long.join()
        assertTrue(longGot)
        assertEquals(listOf("llm", "llm"), runtime.loads, "reloaded with the context asked for")
        assertEquals(listOf("llm"), runtime.unloads)
    }

    @Test
    fun `the same variant in use is shared, not waited for`() = runBlocking {
        val manager = manager(6 * GB)
        val llm = model("llm", bindings = listOf(binding(ramBytes = 1 * GB)))
        manager.withModel(llm, llm.bindings.first(), variant = 2048) {
            manager.withModel(llm, llm.bindings.first(), variant = 2048) { }
        }
        assertEquals(listOf("llm"), runtime.loads)
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

    @Test
    fun `beforeAdmission runs before the budget is read and the load starts, and not on reuse`() = runBlocking {
        val events = mutableListOf<String>()
        val recordingRuntime = object : ModelRuntime {
            override val kind = RuntimeKind.LLAMA_CPP
            override fun canRun(model: ModelDescriptor, binding: RuntimeBinding) = true
            override suspend fun load(model: ModelDescriptor, binding: RuntimeBinding): LoadedModel {
                events += "load"
                return FakeLoadedModel(model.id, binding.effectiveRequiredRamBytes) {}
            }
        }
        val manager = RuntimeManager(
            budgetBytes = { events += "budget"; 6 * GB },
            runtimes = mapOf(RuntimeKind.LLAMA_CPP to recordingRuntime),
            clock = { ++now },
            beforeAdmission = { requiredBytes -> events += "beforeAdmission:${requiredBytes / GB}" },
        )
        val llm = model("llm", bindings = listOf(binding(ramBytes = 2 * GB)))

        manager.withModel(llm, llm.bindings.first()) { }
        manager.withModel(llm, llm.bindings.first()) { }

        assertEquals(listOf("beforeAdmission:2", "budget", "load"), events)
    }

    @Test
    fun `admission uses requiredBytesFor, read fresh on every acquisition`() = runBlocking {
        var measured: Long? = null
        val manager = RuntimeManager(
            budgetBytes = { 7 * GB },
            runtimes = mapOf(RuntimeKind.LLAMA_CPP to runtime),
            clock = { ++now },
            exclusive = true,
            requiredBytesFor = { binding, _ -> measured ?: binding.effectiveRequiredRamBytes },
        )
        // No explicit requirement: the binding falls back to file size × 1.3 = 7.8 GB.
        val madlad = model("madlad", bindings = listOf(RuntimeBinding(RuntimeKind.LLAMA_CPP, "madlad.gguf", fileSizeBytes = 6 * GB)))

        assertFailsWith<InsufficientMemoryException> { manager.withModel(madlad, madlad.bindings.first()) { } }

        measured = 6 * GB + GB / 3
        manager.withModel(madlad, madlad.bindings.first()) { }
        assertEquals(listOf("madlad"), runtime.loads)
    }

    @Test
    fun `hasModelInUse tracks acquisitions and releases`() = runBlocking {
        val manager = manager(6 * GB)
        val llm = model("llm", bindings = listOf(binding(ramBytes = 2 * GB)))

        assertEquals(false, manager.hasModelInUse)
        manager.withModel(llm, llm.bindings.first()) {
            assertEquals(true, manager.hasModelInUse)
        }
        assertEquals(false, manager.hasModelInUse)

        manager.acquire(llm, llm.bindings.first())
        assertEquals(true, manager.hasModelInUse)
        manager.unloadAll()
        assertEquals(false, manager.hasModelInUse)
    }

    @Test
    fun `a projector reserved after the load counts as resident until the model unloads`() = runBlocking {
        val manager = manager(6 * GB)
        val vlm = model("vlm", bindings = listOf(binding(ramBytes = 3 * GB)))

        manager.withModel(vlm, vlm.bindings.first()) { manager.reserve("vlm", 1 * GB, "vision projector") }
        assertEquals(4 * GB, manager.residentBytes)
        assertEquals(4 * GB, manager.residentModels().single().ramBytes)

        manager.unloadAll()
        manager.withModel(vlm, vlm.bindings.first()) { }
        assertEquals(3 * GB, manager.residentBytes, "a reload starts from the load alone; the projector comes back only when reserved again")
    }

    @Test
    fun `a projector that does not fit evicts idle models, never the one that asked`() = runBlocking {
        val manager = manager(5 * GB)
        val asr = model("asr", bindings = listOf(binding(ramBytes = 1 * GB)))
        val vlm = model("vlm", bindings = listOf(binding(ramBytes = 3 * GB)))
        manager.withModel(asr, asr.bindings.first()) { }

        manager.withModel(vlm, vlm.bindings.first()) {
            manager.reserve("vlm", 2 * GB, "vision projector")
        }

        assertEquals(listOf("asr"), runtime.unloads)
        assertEquals(listOf("vlm"), manager.residentModels().map { it.modelId })
        assertEquals(5 * GB, manager.residentBytes)
    }

    @Test
    fun `a projector that cannot fit is refused and changes nothing`() = runBlocking {
        val manager = manager(4 * GB)
        val vlm = model("vlm", bindings = listOf(binding(ramBytes = 3 * GB)))

        manager.withModel(vlm, vlm.bindings.first()) {
            assertFailsWith<InsufficientMemoryException> { manager.reserve("vlm", 2 * GB, "vision projector") }
        }

        assertEquals(3 * GB, manager.residentBytes)
        assertTrue(runtime.unloads.isEmpty(), "the model that asked is never evicted for its own part")
    }

    @Test
    fun `an unused reservation is given back`() = runBlocking {
        val manager = manager(6 * GB)
        val vlm = model("vlm", bindings = listOf(binding(ramBytes = 3 * GB)))
        manager.withModel(vlm, vlm.bindings.first()) {
            manager.reserve("vlm", 1 * GB, "vision projector")
            manager.unreserve("vlm", 1 * GB)
        }
        assertEquals(3 * GB, manager.residentBytes)
    }

    @Test
    fun `text then vision then text then vision on one resident model loads it once and reserves the projector once`() = runBlocking {
        val manager = manager(6 * GB)
        val vlm = model("vlm", bindings = listOf(binding(ramBytes = 3 * GB)))
        var projectorLoaded = false
        suspend fun turn(image: Boolean) = manager.withModel(vlm, vlm.bindings.first()) {
            if (image && !projectorLoaded) {
                manager.reserve("vlm", 1 * GB, "vision projector")
                projectorLoaded = true
            }
        }

        turn(image = false)
        assertEquals(3 * GB, manager.residentBytes)
        turn(image = true)
        turn(image = false)
        turn(image = true)

        assertEquals(listOf("vlm"), runtime.loads)
        assertEquals(4 * GB, manager.residentBytes)
    }
}
