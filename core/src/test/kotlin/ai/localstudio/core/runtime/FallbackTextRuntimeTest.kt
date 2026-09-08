package ai.localstudio.core.runtime

import ai.localstudio.core.binding
import ai.localstudio.core.model
import ai.localstudio.core.registry.ModelDescriptor
import ai.localstudio.core.registry.RuntimeBinding
import kotlinx.coroutines.CancellationException
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
    fun `a failure after the first token is reported, not papered over by the next candidate`() = runBlocking {
        // Tokens reach the caller as they are produced, so by the time a
        // candidate fails mid-stream part of its answer is already on screen
        // and cannot be taken back. Falling through here would append a
        // second, unrelated answer to the first one's remains; the failure is
        // surfaced instead. Falling back is still possible up to the moment
        // the first token is emitted — see the test below.
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
        val emitted = mutableListOf<String>()
        val failure = assertFailsWith<IllegalStateException> {
            handle.generate(GenerationRequest(prompt = "hi")).collect { emitted += it }
        }

        assertEquals("decode error", failure.message)
        assertEquals(listOf("partial ", "words "), emitted)
    }

    @Test
    fun `a candidate that fails before its first token still falls through`() = runBlocking {
        val runtime = FallbackTextRuntime(
            listOf(
                candidate("local") { failing("decode error") },
                candidate("cloud") { succeeding("full cloud answer") },
            ),
        )

        val handle = runtime.load(model("m"), binding()) as TextModelHandle
        val text = handle.generate(GenerationRequest(prompt = "hi")).toList().joinToString("")

        assertTrue(text.startsWith("full cloud answer"))
        assertTrue(text.contains("decode error"))
    }

    @Test
    fun `a candidate is loaded once and reused across turns`() = runBlocking {
        // The chain used to load and close every candidate inside each
        // generate() call, so enabling any second provider meant reading a
        // local model off disk again for every single message.
        var loads = 0
        val counting = object : ModelRuntime {
            override val kind = ai.localstudio.core.registry.RuntimeKind.STUB
            override fun canRun(model: ModelDescriptor, binding: RuntimeBinding) = true
            override suspend fun load(model: ModelDescriptor, binding: RuntimeBinding): LoadedModel {
                loads++
                return FakeHandle { succeeding("ответ") }
            }
        }
        val runtime = FallbackTextRuntime(
            listOf(FallbackCandidate(label = "local", runtime = counting, model = model("m"), binding = binding())),
        )

        val handle = runtime.load(model("m"), binding()) as TextModelHandle
        repeat(3) { handle.generate(GenerationRequest(prompt = "hi")).toList() }

        assertEquals(1, loads)
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

    @Test
    fun `a cancelled candidate stops the chain instead of falling through to the next one`() = runBlocking {
        var cloudWasTried = false
        val runtime = FallbackTextRuntime(
            listOf(
                candidate("local") { flow { throw CancellationException("stopped by user") } },
                candidate("cloud") {
                    cloudWasTried = true
                    succeeding("should never be reached")
                },
            ),
        )

        val handle = runtime.load(model("m"), binding()) as TextModelHandle
        assertFailsWith<CancellationException> {
            handle.generate(GenerationRequest(prompt = "hi")).toList()
        }

        assertTrue(
            !cloudWasTried,
            "cancelling generation must stop the chain, not silently move on to the next candidate",
        )
    }
}
