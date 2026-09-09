package ai.localstudio.openai

import ai.localstudio.core.capability.Capability
import ai.localstudio.core.context.ContextEngine
import ai.localstudio.core.context.FragmentSource
import ai.localstudio.core.engine.ModelSelector
import ai.localstudio.core.engine.NodeExecutors
import ai.localstudio.core.engine.Orchestrator
import ai.localstudio.core.engine.UserRequest
import ai.localstudio.core.engine.audioInput
import ai.localstudio.memory.FileMemoryStore
import ai.localstudio.core.pipeline.NodeType
import ai.localstudio.core.registry.DeviceProfile
import ai.localstudio.core.registry.InstallState
import ai.localstudio.core.registry.ModelDescriptor
import ai.localstudio.core.registry.ModelRegistry
import ai.localstudio.core.registry.RegistryEntry
import ai.localstudio.core.registry.RuntimeBinding
import ai.localstudio.core.registry.RuntimeKind
import ai.localstudio.core.router.CapabilityRouter
import ai.localstudio.core.runtime.RuntimeManager
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Stage 1 of the roadmap: the whole stack — router, pipeline builder,
 * validator, engine, model selection, runtime manager, context assembly and
 * memory — running against a real OpenAI-compatible endpoint over HTTP.
 *
 * The only thing that changes on the phone is which [ai.localstudio.core.runtime.ModelRuntime]
 * is registered, which is the entire point of the abstraction.
 */
class EndToEndTest {

    private val server = FakeOpenAiServer()
    private val memory = FileMemoryStore(java.io.File.createTempFile("end-to-end-test", ".json").apply { deleteOnExit() })

    @AfterTest
    fun stop() = server.close()

    private fun remoteModel(id: String, vararg capabilities: Capability) = ModelDescriptor(
        id = id,
        family = id.substringBefore(':'),
        version = "1",
        parameterCount = 1,
        contextLength = 8_192,
        capabilities = capabilities.toSet(),
        bindings = listOf(
            RuntimeBinding(
                runtime = RuntimeKind.REMOTE_OPENAI,
                artifact = id,
                fileSizeBytes = 1,
                requiredRamBytes = 1,
                referenceTokensPerSecond = 60.0,
            ),
        ),
    )

    private val registry = ModelRegistry(
        listOf(
            RegistryEntry(
                remoteModel("qwen3:8b", Capability.TEXT_GENERATION, Capability.REASONING),
                InstallState.INSTALLED,
            ),
            RegistryEntry(
                remoteModel("whisper-1", Capability.SPEECH_TO_TEXT),
                InstallState.INSTALLED,
            ),
        ),
    )

    private val device = DeviceProfile(
        totalRamBytes = 32_000_000_000,
        availableRamBytes = 16_000_000_000,
        availableStorageBytes = 100_000_000_000,
        cpuCores = 16,
        androidApiLevel = 0,
        supportedRuntimes = setOf(RuntimeKind.REMOTE_OPENAI),
        performanceIndex = 1.0,
    )

    private fun orchestrator(): Orchestrator {
        val runtime = OpenAiRuntime(OpenAiConfig(baseUrl = server.baseUrl))
        val manager = RuntimeManager(
            budgetBytes = 1_000_000,
            runtimes = mapOf(RuntimeKind.REMOTE_OPENAI to runtime),
        )
        return Orchestrator(
            router = CapabilityRouter(),
            executors = NodeExecutors(
                selector = ModelSelector(registry, device),
                runtimeManager = manager,
                contextEngine = ContextEngine(),
                memory = memory,
                systemPrompt = "Ты локальный ассистент. Отвечай кратко.",
            ),
        )
    }

    @Test
    fun `a typed question is answered by the served model`() = runBlocking {
        val answer = orchestrator().handle(
            UserRequest(conversationId = "dev", text = "Чем capability отличается от модели?"),
        )

        assertEquals("Привет, мир", answer.text)
        val sent = server.requests.single { it.path == "/v1/chat/completions" }.text
        assertContains(sent, "Чем capability отличается от модели?")
        assertContains(sent, "Ты локальный ассистент")
        // Exactly once, as a system-role message — it used to also be
        // repeated inside the user turn as a "## Инструкции" section.
        assertContains(sent, "\"role\":\"system\"")
        assertEquals(1, Regex("Ты локальный ассистент").findAll(sent).count())
    }

    @Test
    fun `voice goes through transcription and then generation, in that order`() = runBlocking {
        val audio = File.createTempFile("note", ".wav").apply {
            writeBytes(ByteArray(64) { it.toByte() })
            deleteOnExit()
        }

        val answer = orchestrator().handle(
            UserRequest(conversationId = "dev", attachment = audioInput("file://${audio.absolutePath}")),
        )

        assertEquals(
            listOf("/v1/audio/transcriptions", "/v1/chat/completions"),
            server.requests.map { it.path },
        )
        assertEquals("Привет, мир", answer.text)

        val transcript = answer.context!!.fragments.single { it.source == FragmentSource.TRANSCRIPT }
        assertEquals("найди мне лучшие локальные модели", transcript.text)
        assertContains(
            server.requests.last { it.path == "/v1/chat/completions" }.text,
            "найди мне лучшие локальные модели",
        )
    }

    @Test
    fun `what was said is remembered and retrieved on the next turn`() = runBlocking {
        val orchestrator = orchestrator()

        orchestrator.handle(
            UserRequest(conversationId = "dev", text = "Решили использовать capability registry для выбора моделей"),
        )
        memory.consolidate("dev")
        val second = orchestrator.handle(
            UserRequest(conversationId = "dev", text = "Напомни, что мы решили про registry"),
        )

        assertTrue(NodeType.MEMORY_SEARCH in second.pipeline.nodes.map { it.type })
        val prompt = server.requests.last { it.path == "/v1/chat/completions" }.text
        assertContains(prompt, "capability registry")
        assertContains(prompt, "## Из прошлых разговоров")
    }

    @Test
    fun `the remote runtime holds no memory budget of its own`() = runBlocking {
        // The manager's budget is a megabyte: nothing local could load, yet the
        // pipeline runs, because a served model occupies no local RAM.
        val answer = orchestrator().handle(UserRequest(conversationId = "dev", text = "привет"))

        assertEquals("Привет, мир", answer.text)
    }
}
