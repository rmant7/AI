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

private fun candidate(
    label: String,
    onFailure: ((Throwable) -> Unit)? = null,
    behavior: () -> Flow<String>,
) = FallbackCandidate(
    label = label,
    runtime = FakeTextRuntime(behavior),
    model = model(label),
    binding = binding(),
    onFailure = onFailure,
)

private fun failing(message: String): Flow<String> = flow { throw IllegalStateException(message) }
private fun empty(): Flow<String> = flow { }
private fun succeeding(vararg tokens: String): Flow<String> = flow { tokens.forEach { emit(it) } }

class FallbackTextRuntimeTest {

    @Test
    fun `the first candidate that actually produces tokens wins, and is named in the answer`() = runBlocking {
        val runtime = FallbackTextRuntime(
            listOf(
                candidate("local") { succeeding("ответ ", "локально") },
                candidate("cloud") { succeeding("should not be reached") },
            ),
        )

        val handle = runtime.load(model("m"), binding()) as TextModelHandle
        val text = handle.generate(GenerationRequest(prompt = "hi")).toList().joinToString("")

        assertTrue(text.startsWith("ответ локально"))
        assertTrue(text.contains("Ответ от: local"), "the answering candidate should be named even with no fallback: $text")
        // No fallback happened, so there is nothing to warn about — only the
        // plain attribution line, no "⚠" failure summary.
        assertTrue(!text.contains("⚠"))
    }

    @Test
    fun `a throwing candidate falls through to the next one, and the failure is not silently discarded`() = runBlocking {
        val runtime = FallbackTextRuntime(
            listOf(
                candidate("local") { failing("native crash") },
                candidate("cloud") { succeeding("cloud answered") },
            ),
        )

        val handle = runtime.load(model("m"), binding()) as TextModelHandle
        val text = handle.generate(GenerationRequest(prompt = "hi")).toList().joinToString("")

        assertTrue(text.startsWith("cloud answered"))
        assertTrue(text.contains("native crash"), "the local failure reason should reach the answer: $text")
        assertTrue(text.contains("cloud"), "which candidate actually answered should be named: $text")
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

        assertTrue(text.startsWith("cloud answered"))
        assertTrue(text.contains("пустой ответ"))
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

        assertTrue(text.startsWith("full cloud answer"))
        assertTrue(text.contains("decode error"))
        // The discarded partial tokens must not appear anywhere, including in
        // the failure note — a truncated answer with a caption is still a
        // truncated answer.
        assertTrue(!text.contains("partial words"))
    }

    @Test
    fun `even a single candidate gets attributed, not just a fallback`() = runBlocking {
        val runtime = FallbackTextRuntime(listOf(candidate("local") { succeeding("just an answer") }))

        val handle = runtime.load(model("m"), binding()) as TextModelHandle
        val text = handle.generate(GenerationRequest(prompt = "hi")).toList().joinToString("")

        assertTrue(text.startsWith("just an answer"))
        assertTrue(text.contains("Ответ от: local"))
    }

    @Test
    fun `onFailure is invoked with the raw exception whenever a candidate fails`() = runBlocking {
        val seen = mutableListOf<Throwable>()
        val runtime = FallbackTextRuntime(
            listOf(
                candidate("local", onFailure = { seen += it }) { failing("HTTP 503: overloaded") },
                candidate("cloud") { succeeding("cloud answered") },
            ),
        )

        val handle = runtime.load(model("m"), binding()) as TextModelHandle
        handle.generate(GenerationRequest(prompt = "hi")).toList()

        assertEquals(1, seen.size)
        assertTrue(seen.single().message!!.contains("HTTP 503"))
    }
}
