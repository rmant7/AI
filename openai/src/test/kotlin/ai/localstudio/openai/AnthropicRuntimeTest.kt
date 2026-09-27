package ai.localstudio.openai

import ai.localstudio.core.capability.Capability
import ai.localstudio.core.keys.ApiKeyRotator
import ai.localstudio.core.keys.InMemoryApiKeyStore
import ai.localstudio.core.registry.ModelDescriptor
import ai.localstudio.core.registry.RuntimeBinding
import ai.localstudio.core.registry.RuntimeKind
import ai.localstudio.core.runtime.ModelLoadException
import ai.localstudio.core.runtime.GenerationRequest
import ai.localstudio.core.runtime.TextModelHandle
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AnthropicRuntimeTest {

    private val server = FakeAnthropicServer()
    private val runtime = AnthropicRuntime(AnthropicConfig(baseUrl = server.baseUrl, apiKey = "test-key"))

    @AfterTest
    fun stop() = server.close()

    private fun descriptor(id: String, capabilities: Set<Capability>) = ModelDescriptor(
        id = id,
        family = id,
        version = "1",
        parameterCount = 1,
        capabilities = capabilities,
        bindings = listOf(
            RuntimeBinding(
                runtime = RuntimeKind.REMOTE_ANTHROPIC,
                artifact = id,
                fileSizeBytes = 1,
                requiredRamBytes = 1,
            ),
        ),
    )

    private suspend fun textModel(id: String = "claude-opus-5") =
        runtime.load(descriptor(id, setOf(Capability.TEXT_GENERATION)), descriptor(id, setOf(Capability.TEXT_GENERATION)).bindings.first())

    @Test
    fun `generation streams the deltas of a message`() = runBlocking {
        val handle = assertIs<TextModelHandle>(textModel())

        val chunks = handle.generate(GenerationRequest(prompt = "привет", systemPrompt = "ты ассистент")).toList()

        assertEquals(listOf("Привет", ", ", "мир"), chunks)
        val request = server.requests.single()
        assertEquals("/v1/messages", request.path)
        assertEquals("test-key", request.apiKey)
        assertEquals("2023-06-01", request.apiVersion)
        assertContains(request.text, "\"model\":\"claude-opus-5\"")
        assertContains(request.text, "\"stream\":true")
        assertContains(request.text, "\"system\":\"ты ассистент\"")
    }

    @Test
    fun `a stop_reason of max_tokens fails instead of reporting a complete answer`() = runBlocking {
        server.stopReason = "max_tokens"
        val handle = assertIs<TextModelHandle>(textModel())

        val failure = assertFailsWith<java.io.IOException> {
            handle.generate(GenerationRequest(prompt = "long answer")).toList()
        }

        assertContains(failure.message!!, "output-length limit")
    }

    @Test
    fun `an ordinary end_turn is unaffected by the length-limit check`() = runBlocking {
        server.stopReason = "end_turn"
        val handle = assertIs<TextModelHandle>(textModel())

        val chunks = handle.generate(GenerationRequest(prompt = "hi")).toList()

        assertEquals(listOf("Привет", ", ", "мир"), chunks)
    }

    @Test
    fun `an error response carries the server's message`() = runBlocking {
        server.messagesStatus = 404
        val handle = assertIs<TextModelHandle>(textModel())

        val failure = assertFailsWith<AnthropicException> {
            handle.generate(GenerationRequest(prompt = "привет")).toList()
        }

        assertEquals(404, failure.status)
        assertContains(failure.message!!, "model not found")
    }

    @Test
    fun `a rate-limited key rotates to the next one in the pool automatically`() = runBlocking {
        val store = InMemoryApiKeyStore()
        val rotator = ApiKeyRotator(store, "anthropic")
        val bad = rotator.add("bad-key")
        rotator.add("good-key")
        server.messagesStatusForKey = mapOf(bad.key to 429)

        val pooledRuntime = AnthropicRuntime(AnthropicConfig(baseUrl = server.baseUrl, keyRotator = rotator))
        val model = descriptor("claude-opus-5", setOf(Capability.TEXT_GENERATION))
        val handle = assertIs<TextModelHandle>(pooledRuntime.load(model, model.bindings.first()))

        val chunks = handle.generate(GenerationRequest(prompt = "hi")).toList()

        assertEquals(listOf("Привет", ", ", "мир"), chunks)
        assertEquals(2, server.requests.size, "expected one failed attempt with the bad key, then one with the good key")
        assertTrue(rotator.pool().first { it.id == bad.id }.cooldownUntilEpochMs > 0)
    }

    @Test
    fun `every key exhausted surfaces one clear error instead of the raw 429`() = runBlocking {
        val store = InMemoryApiKeyStore()
        val rotator = ApiKeyRotator(store, "anthropic")
        rotator.add("only-key")
        server.messagesStatus = 429

        val pooledRuntime = AnthropicRuntime(AnthropicConfig(baseUrl = server.baseUrl, keyRotator = rotator))
        val model = descriptor("claude-opus-5", setOf(Capability.TEXT_GENERATION))
        val handle = assertIs<TextModelHandle>(pooledRuntime.load(model, model.bindings.first()))

        val error = assertFailsWith<ModelLoadException> {
            handle.generate(GenerationRequest(prompt = "hi")).toList()
        }
        assertContains(error.message!!, "1")
    }

    @Test
    fun `a provider with no pool configured falls back to the plain apiKey untouched`() = runBlocking {
        val handle = assertIs<TextModelHandle>(textModel())

        handle.generate(GenerationRequest(prompt = "hi")).toList()

        assertEquals("test-key", server.requests.single().apiKey)
    }

    @Test
    fun `a mid-stream error event fails the turn with the server's message`() = runBlocking {
        // Anthropic can return HTTP 200 and only fail once the SSE body has
        // started (e.g. "overloaded_error" mid-generation) — a genuinely
        // different code path from a pre-stream HTTP error status.
        server.midStreamErrorType = "overloaded_error"
        server.midStreamErrorMessage = "Overloaded"
        val handle = assertIs<TextModelHandle>(textModel())

        val failure = assertFailsWith<AnthropicException> {
            handle.generate(GenerationRequest(prompt = "hi")).toList()
        }

        assertEquals(529, failure.status)
        assertContains(failure.message!!, "Overloaded")
    }

    @Test
    fun `a pre-stream error status carries the server's message via HttpStatusError`() = runBlocking {
        server.messagesStatus = 401
        server.errorBody = """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}"""
        val handle = assertIs<TextModelHandle>(textModel())

        val failure = assertFailsWith<AnthropicException> {
            handle.generate(GenerationRequest(prompt = "hi")).toList()
        }

        assertEquals(401, failure.status)
        assertContains(failure.message!!, "invalid x-api-key")
    }

    @Test
    fun `a remote model reports no local footprint`() = runBlocking {
        assertEquals(0, textModel().ramBytes)
    }

    @Test
    fun `bindings for other runtimes are rejected`() = runBlocking {
        val model = ModelDescriptor(
            id = "local",
            family = "local",
            version = "1",
            parameterCount = 1,
            capabilities = setOf(Capability.TEXT_GENERATION),
            bindings = listOf(
                RuntimeBinding(RuntimeKind.LLAMA_CPP, "model.gguf", fileSizeBytes = 1, requiredRamBytes = 1),
            ),
        )

        assertTrue(!runtime.canRun(model, model.bindings.first()))
        assertFailsWith<ModelLoadException> {
            runtime.load(model, model.bindings.first())
        }
        Unit
    }
}
