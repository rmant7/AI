package ai.localstudio.core.runtime

import ai.localstudio.core.binding
import ai.localstudio.core.model
import ai.localstudio.core.registry.ModelDescriptor
import ai.localstudio.core.registry.RuntimeBinding
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private class FakeTextRuntime(
    private val behavior: () -> Flow<String>,
) : ModelRuntime {
    override val kind = ai.localstudio.core.registry.RuntimeKind.STUB
    override fun canRun(model: ModelDescriptor, binding: RuntimeBinding) = true
    override suspend fun load(model: ModelDescriptor, binding: RuntimeBinding): LoadedModel = FakeHandle(behavior)
}

private class FakeHandle(private val behavior: () -> Flow<String>) : TextModelHandle {
    override val modelId = "fake"
    override val ramBytes = 0L
    var closed = false
    override fun generate(request: GenerationRequest): Flow<String> = behavior()
    override fun requestCancel() = Unit
    override fun close() {
        closed = true
    }
}

private fun candidate(label: String, behavior: () -> Flow<String>) = FallbackCandidate(
    label = label,
    runtime = FakeTextRuntime(behavior),
    model = model(label),
    binding = binding(),
)

private fun failing(message: String): Flow<String> = flow { throw IllegalStateException(message) }
private fun empty(): Flow<String> = flow { }
private fun succeeding(vararg tokens: String): Flow<String> = flow { tokens.forEach { emit(it) } }

class FallbackTextRuntimeTest {

    @Test
    fun `the first candidate that actually produces tokens wins`() = runBlocking {
        val runtime = FallbackTextRuntime(
            listOf(
                candidate("local") { succeeding("ответ ", "локально") },
                candidate("cloud") { succeeding("should not be reached") },
            ),
        )

        val handle = runtime.load(model("m"), binding()) as TextModelHandle
        val text = handle.generate(GenerationRequest(prompt = "hi")).toList().joinToString("")

        assertEquals("ответ локально", text)
    }

    @Test
    fun `a throwing candidate falls through to the next one`() = runBlocking {
        val runtime = FallbackTextRuntime(
            listOf(
                candidate("local") { failing("native crash") },
                candidate("cloud") { succeeding("cloud answered") },
            ),
        )

        val handle = runtime.load(model("m"), binding()) as TextModelHandle
        val text = handle.generate(GenerationRequest(prompt = "hi")).toList().joinToString("")

        assertEquals("cloud answered", text)
    }

    @Test
    fun `an empty response also counts as a failure worth falling back from`() = runBlocking {
        val runtime = FallbackTextRuntime(
            listOf(
                candidate("local") { empty() },
                candidate("cloud") { succeeding("cloud answered") },
            ),
        )

        val handle = runtime.load(model("m"), binding()) as TextModelHandle
        val text = handle.generate(GenerationRequest(prompt = "hi")).toList().joinToString("")

        assertEquals("cloud answered", text)
    }

    @Test
    fun `every candidate failing surfaces one clear error, not a crash`() = runBlocking {
        val runtime = FallbackTextRuntime(
            listOf(
                candidate("local") { failing("no local model") },
                candidate("cloud") { failing("no api key") },
            ),
        )

        val handle = runtime.load(model("m"), binding()) as TextModelHandle
        val error = assertFailsWith<ModelLoadException> {
            handle.generate(GenerationRequest(prompt = "hi")).toList()
        }

        assertTrue(error.message!!.contains("no local model"))
        assertTrue(error.message!!.contains("no api key"))
    }

    @Test
    fun `a partial answer before a mid-stream failure is discarded, not shown truncated`() = runBlocking {
        val runtime = FallbackTextRuntime(
            listOf(
                candidate("local") {
                    flow {
                        emit("partial ")
                        emit("words ")
                        throw IllegalStateException("decode error")
                    }
                },
                candidate("cloud") { succeeding("full cloud answer") },
            ),
        )

        val handle = runtime.load(model("m"), binding()) as TextModelHandle
        val text = handle.generate(GenerationRequest(prompt = "hi")).toList().joinToString("")

        assertEquals("full cloud answer", text)
    }
}
