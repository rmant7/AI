package ai.localstudio.core.runtime

import ai.localstudio.core.GB
import ai.localstudio.core.binding
import ai.localstudio.core.model
import ai.localstudio.core.registry.ModelDescriptor
import ai.localstudio.core.registry.RuntimeBinding
import ai.localstudio.core.registry.RuntimeKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class CountingTextRuntime(private val answer: String) : ModelRuntime {
    val loads = mutableListOf<String>()
    val closes = mutableListOf<String>()
    override val kind = RuntimeKind.LLAMA_CPP
    override fun canRun(model: ModelDescriptor, binding: RuntimeBinding) = true
    override suspend fun load(model: ModelDescriptor, binding: RuntimeBinding): LoadedModel {
        loads += model.id
        return object : TextModelHandle {
            override val modelId = model.id
            override val ramBytes = binding.effectiveRequiredRamBytes
            override fun generate(request: GenerationRequest): Flow<String> = flowOf(answer)
            override fun requestCancel() = Unit
            override fun close() {
                closes += model.id
            }
        }
    }
}

class SharedRuntimeTest {

    private var now = 0L
    private val shared = RuntimeManager(budgetBytes = 6 * GB, runtimes = emptyMap(), clock = { ++now })

    @Test
    fun `two chains holding the same local model load it once`() = runBlocking {
        val inner = CountingTextRuntime("hi")
        val llm = model("llm", bindings = listOf(binding(ramBytes = 4 * GB)))
        fun chain() = FallbackTextRuntime(
            listOf(FallbackCandidate("local", SharedRuntime(inner, shared), llm, llm.bindings.first())),
        )

        val first = chain().load(llm, llm.bindings.first()) as TextModelHandle
        val second = chain().load(llm, llm.bindings.first()) as TextModelHandle
        first.generate(GenerationRequest(prompt = "a")).toList()
        second.generate(GenerationRequest(prompt = "b")).toList()

        assertEquals(listOf("llm"), inner.loads)
        assertEquals(4 * GB, shared.residentBytes)
    }

    @Test
    fun `switching models through separate chains evicts the previous one`() = runBlocking {
        val inner = CountingTextRuntime("hi")
        val gemma = model("gemma", bindings = listOf(binding(ramBytes = 4 * GB)))
        val qwen = model("qwen", bindings = listOf(binding(ramBytes = 4 * GB)))

        (SharedRuntime(inner, shared).load(gemma, gemma.bindings.first()) as TextModelHandle)
            .generate(GenerationRequest(prompt = "a")).toList()
        (SharedRuntime(inner, shared).load(qwen, qwen.bindings.first()) as TextModelHandle)
            .generate(GenerationRequest(prompt = "b")).toList()

        assertEquals(listOf("gemma"), inner.closes)
        assertEquals(listOf("qwen"), shared.residentModels().map { it.modelId })
    }

    @Test
    fun `the weightless handle does not count against the holder's own manager`() = runBlocking {
        val inner = CountingTextRuntime("hi")
        val llm = model("llm", bindings = listOf(binding(ramBytes = 4 * GB)))

        val handle = SharedRuntime(inner, shared).load(llm, llm.bindings.first())

        assertEquals(0L, handle.ramBytes)
        assertTrue(inner.loads.isEmpty())
    }
}
