package ai.localstudio.openai

import ai.localstudio.core.capability.Capability
import ai.localstudio.core.keys.ApiKeyRotator
import ai.localstudio.core.keys.InMemoryApiKeyStore
import ai.localstudio.core.model.AudioRef
import ai.localstudio.core.registry.ModelDescriptor
import ai.localstudio.core.registry.RuntimeBinding
import ai.localstudio.core.registry.RuntimeKind
import ai.localstudio.core.runtime.EmbeddingModelHandle
import ai.localstudio.core.runtime.GenerationRequest
import ai.localstudio.core.runtime.SpeechModelHandle
import ai.localstudio.core.runtime.TextModelHandle
import kotlinx.coroutines.flow.collectIndexed
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OpenAiRuntimeTest {

    private val server = FakeOpenAiServer()
    private val runtime = OpenAiRuntime(OpenAiConfig(baseUrl = server.baseUrl, apiKey = "test-key"))

    @AfterTest
    fun stop() = server.close()

    private fun descriptor(
        id: String,
        capabilities: Set<Capability>,
        artifact: String = "",
    ) = ModelDescriptor(
        id = id,
        family = id,
        version = "1",
        parameterCount = 1,
        capabilities = capabilities,
        bindings = listOf(
            RuntimeBinding(
                runtime = RuntimeKind.REMOTE_OPENAI,
                artifact = artifact.ifEmpty { id },
                fileSizeBytes = 1,
                requiredRamBytes = 1,
            ),
        ),
    )

    private suspend fun textModel(id: String = "qwen3:8b") =
        runtime.load(descriptor(id, setOf(Capability.TEXT_GENERATION)), descriptor(id, setOf(Capability.TEXT_GENERATION)).bindings.first())

    @Test
    fun `generation streams the deltas of a chat completion`() = runBlocking {
        val handle = assertIs<TextModelHandle>(textModel())

        val chunks = handle.generate(GenerationRequest(prompt = "привет", systemPrompt = "ты ассистент")).toList()

        assertEquals(listOf("Привет", ", ", "мир"), chunks)
        val request = server.requests.single()
        assertEquals("/v1/chat/completions", request.path)
        assertContains(request.text, "\"model\":\"qwen3:8b\"")
        assertContains(request.text, "\"stream\":true")
        assertContains(request.text, "ты ассистент")
    }

    @Test
    fun `the binding's artifact names the served model`() = runBlocking {
        val model = descriptor("local-alias", setOf(Capability.TEXT_GENERATION), artifact = "gemma3:4b")
        val handle = assertIs<TextModelHandle>(runtime.load(model, model.bindings.first()))

        handle.generate(GenerationRequest(prompt = "x")).toList()

        assertContains(server.requests.single().text, "\"model\":\"gemma3:4b\"")
    }

    @Test
    fun `cancellation stops the stream at the next chunk`() = runBlocking {
        server.chatChunks = List(20) { "часть$it " }
        server.chatChunkDelayMs = 25
        val handle = assertIs<TextModelHandle>(textModel())

        val received = mutableListOf<String>()
        handle.generate(GenerationRequest(prompt = "длинный ответ")).collectIndexed { index, value ->
            received += value
            if (index == 1) handle.requestCancel()
        }

        // Prompt, not instantaneous: the flow is buffered, so a few chunks that
        // were already in flight still arrive. What matters is that the stream
        // stops long before the model would have finished.
        assertTrue(received.size in 2..8, "received ${received.size} of 20 chunks")
    }

    @Test
    fun `an error response carries the server's message`() = runBlocking {
        server.chatStatus = 404
        val handle = assertIs<TextModelHandle>(textModel())

        val failure = assertFailsWith<OpenAiException> {
            handle.generate(GenerationRequest(prompt = "привет")).toList()
        }

        assertEquals(404, failure.status)
        assertContains(failure.message!!, "model not found")
    }

    @Test
    fun `a Gemini error body, which arrives as an array, still yields just the sentence`() {
        // Verbatim from a real Gemini 503. The spec's shape is an object;
        // this is an array of them, and only the object form used to parse —
        // so the whole blob was quoted into the chat as the note explaining
        // which candidate had been skipped, inside an otherwise fine answer.
        val body = """
            [{
              "error": {
                "code": 503,
                "message": "This model is currently experiencing high demand. Spikes in demand are usually temporary. Please try again later.",
                "status": "UNAVAILABLE"
              }
            }
            ]
        """.trimIndent()

        val message = OpenAiException(503, body).message!!

        assertContains(message, "This model is currently experiencing high demand")
        assertTrue("\"error\"" !in message, "the raw JSON must not survive into the message: $message")
        assertTrue("UNAVAILABLE" !in message, "the raw JSON must not survive into the message: $message")
    }

    @Test
    fun `a rate-limited key rotates to the next one in the pool automatically`() = runBlocking {
        val store = InMemoryApiKeyStore()
        val rotator = ApiKeyRotator(store, "gemini")
        val bad = rotator.add("bad-key")
        rotator.add("good-key")
        server.chatStatusForKey = mapOf(bad.key to 429)

        val pooledRuntime = OpenAiRuntime(OpenAiConfig(baseUrl = server.baseUrl, keyRotator = rotator))
        val model = descriptor("qwen3:8b", setOf(Capability.TEXT_GENERATION))
        val handle = assertIs<TextModelHandle>(pooledRuntime.load(model, model.bindings.first()))

        val chunks = handle.generate(GenerationRequest(prompt = "hi")).toList()

        assertEquals(listOf("Привет", ", ", "мир"), chunks)
        assertEquals(2, server.requests.size, "expected one failed attempt with the bad key, then one with the good key")
        // The bad key is now on a 24h cooldown, not just skipped this once.
        assertTrue(rotator.pool().first { it.id == bad.id }.cooldownUntilEpochMs > 0)
    }

    @Test
    fun `every key exhausted surfaces one clear error instead of the raw 429`() = runBlocking {
        val store = InMemoryApiKeyStore()
        val rotator = ApiKeyRotator(store, "gemini")
        rotator.add("only-key")
        server.chatStatus = 429

        val pooledRuntime = OpenAiRuntime(OpenAiConfig(baseUrl = server.baseUrl, keyRotator = rotator))
        val model = descriptor("qwen3:8b", setOf(Capability.TEXT_GENERATION))
        val handle = assertIs<TextModelHandle>(pooledRuntime.load(model, model.bindings.first()))

        val error = assertFailsWith<ai.localstudio.core.runtime.ModelLoadException> {
            handle.generate(GenerationRequest(prompt = "hi")).toList()
        }
        assertContains(error.message!!, "1")
    }

    @Test
    fun `a rate limit that names its own wait gets a short cooldown, not the 24h default`() = runBlocking {
        val store = InMemoryApiKeyStore()
        val rotator = ApiKeyRotator(store, "groq")
        val onlyKey = rotator.add("only-key")
        server.chatStatus = 429
        // Groq's actual free-tier wording for a per-minute burst, as opposed
        // to a real daily-quota exhaustion — the whole point of parsing this
        // is telling those two apart instead of treating every 429 the same.
        server.quotaErrorBody = """
            {"error":{"message":"Rate limit reached for model `x` in organization `y` on : Limit 6000, Used 6000, Requested 33. Please try again in 0.05s.","type":"tokens","code":"rate_limit_exceeded"}}
        """.trimIndent()

        val pooledRuntime = OpenAiRuntime(OpenAiConfig(baseUrl = server.baseUrl, keyRotator = rotator))
        val model = descriptor("qwen3:8b", setOf(Capability.TEXT_GENERATION))
        val handle = assertIs<TextModelHandle>(pooledRuntime.load(model, model.bindings.first()))

        assertFailsWith<ai.localstudio.core.runtime.ModelLoadException> {
            handle.generate(GenerationRequest(prompt = "hi")).toList()
        }

        val cooldownUntil = rotator.pool().first { it.id == onlyKey.id }.cooldownUntilEpochMs
        assertTrue(cooldownUntil > 0)
        assertTrue(
            cooldownUntil - System.currentTimeMillis() < 60_000,
            "a wait the provider itself named (0.05s) must not fall back to the 24h default",
        )
    }

    @Test
    fun `a provider with no pool configured falls back to the plain apiKey untouched`() = runBlocking {
        // Regression guard: adding keyRotator to OpenAiConfig must not change
        // behavior for every provider that has not opted into a pool yet.
        val handle = assertIs<TextModelHandle>(textModel())

        handle.generate(GenerationRequest(prompt = "hi")).toList()

        assertEquals("Bearer test-key", server.requests.single().authorization)
    }

    @Test
    fun `embeddings come back in the order they were requested`() = runBlocking {
        server.embeddings = listOf(listOf(1f, 0f, 0f), listOf(0f, 1f, 0f), listOf(0f, 0f, 1f))
        val model = descriptor("nomic-embed", setOf(Capability.EMBEDDING))
        val handle = assertIs<EmbeddingModelHandle>(runtime.load(model, model.bindings.first()))

        val vectors = handle.embed(listOf("первый", "второй", "третий"))

        assertEquals(3, vectors.size)
        assertEquals(listOf(1f, 0f, 0f), vectors[0].toList())
        assertEquals(listOf(0f, 0f, 1f), vectors[2].toList())
        assertEquals(3, handle.dimensions)
    }

    @Test
    fun `an empty embedding request never reaches the network`() = runBlocking {
        val model = descriptor("nomic-embed", setOf(Capability.EMBEDDING))
        val handle = assertIs<EmbeddingModelHandle>(runtime.load(model, model.bindings.first()))

        assertTrue(handle.embed(emptyList()).isEmpty())
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun `transcription uploads the file as multipart and parses segments`() = runBlocking {
        val audio = File.createTempFile("note", ".wav").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4, 5))
            deleteOnExit()
        }
        val model = descriptor("whisper-1", setOf(Capability.SPEECH_TO_TEXT))
        val handle = assertIs<SpeechModelHandle>(runtime.load(model, model.bindings.first()))

        val transcript = handle.transcribe(AudioRef("file://${audio.absolutePath}"), language = "ru")

        assertEquals("найди мне лучшие локальные модели", transcript.text)
        assertEquals("ru", transcript.language)
        assertEquals(1, transcript.segments.size)
        assertEquals(2500, transcript.segments.single().endMs)

        val request = server.requests.single()
        assertContains(request.contentType!!, "multipart/form-data; boundary=")
        assertContains(request.text, "name=\"model\"")
        assertContains(request.text, "whisper-1")
        assertContains(request.text, "filename=\"${audio.name}\"")
    }

    @Test
    fun `a missing audio file fails before any request is made`() = runBlocking {
        val model = descriptor("whisper-1", setOf(Capability.SPEECH_TO_TEXT))
        val handle = assertIs<SpeechModelHandle>(runtime.load(model, model.bindings.first()))

        assertFailsWith<OpenAiException> { handle.transcribe(AudioRef("file:///nope/missing.wav")) }
        assertTrue(server.requests.isEmpty())
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
        assertFailsWith<ai.localstudio.core.runtime.ModelLoadException> {
            runtime.load(model, model.bindings.first())
        }
        Unit
    }
}

class SseParserTest {

    @Test
    fun `data lines are unwrapped`() {
        assertEquals("""{"a":1}""", SseParser.dataOf("""data: {"a":1}"""))
        assertEquals("""{"a":1}""", SseParser.dataOf("""data:{"a":1}"""))
    }

    @Test
    fun `comments, event names and blank lines carry no data`() {
        assertEquals(null, SseParser.dataOf(": keep-alive"))
        assertEquals(null, SseParser.dataOf("event: message"))
        assertEquals(null, SseParser.dataOf(""))
        assertEquals(null, SseParser.dataOf("data:"))
    }

    @Test
    fun `the terminator is recognised`() {
        assertTrue(SseParser.isTerminator("[DONE]"))
        assertTrue(!SseParser.isTerminator("""{"choices":[]}"""))
    }
}
