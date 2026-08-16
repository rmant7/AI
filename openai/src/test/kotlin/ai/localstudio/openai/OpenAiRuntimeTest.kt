package ai.localstudio.openai

import ai.localstudio.core.capability.Capability
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
