package ai.localstudio.core.engine

import ai.localstudio.core.GB
import ai.localstudio.core.binding
import ai.localstudio.core.capability.Capability
import ai.localstudio.core.context.ContextEngine
import ai.localstudio.core.context.FragmentSource
import ai.localstudio.core.device
import ai.localstudio.core.memory.InMemoryMemoryProvider
import ai.localstudio.core.memory.MemoryQuery
import ai.localstudio.core.memory.MemoryScope
import ai.localstudio.core.model
import ai.localstudio.core.pipeline.ConversationTurn
import ai.localstudio.core.pipeline.NodeType
import ai.localstudio.core.pipeline.NodeValue
import ai.localstudio.core.registry.Benchmarks
import ai.localstudio.core.registry.InstallState
import ai.localstudio.core.registry.ModelRegistry
import ai.localstudio.core.registry.RegistryEntry
import ai.localstudio.core.registry.RuntimeKind
import ai.localstudio.core.router.CapabilityRouter
import ai.localstudio.core.runtime.RuntimeManager
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * End-to-end through the real router, registry, scorer, runtime manager,
 * context engine and pipeline engine — only the inference itself is faked.
 */
class OrchestratorTest {

    private val runtime = FakeRuntime()
    private val memory = InMemoryMemoryProvider()

    private val registry = ModelRegistry(
        listOf(
            RegistryEntry(
                model(
                    "llm-general",
                    capabilities = setOf(Capability.TEXT_GENERATION, Capability.REASONING),
                    bindings = listOf(binding(ramBytes = 2 * GB)),
                    benchmarks = Benchmarks(general = 70.0),
                ),
                InstallState.INSTALLED,
            ),
            RegistryEntry(
                model(
                    "asr",
                    capabilities = setOf(Capability.SPEECH_TO_TEXT),
                    bindings = listOf(binding(runtime = RuntimeKind.WHISPER_CPP, ramBytes = 1 * GB)),
                ),
                InstallState.INSTALLED,
            ),
            RegistryEntry(
                model(
                    "vlm",
                    capabilities = setOf(Capability.IMAGE_UNDERSTANDING, Capability.OCR),
                    bindings = listOf(binding(ramBytes = 2 * GB)),
                ),
                InstallState.INSTALLED,
            ),
        ),
    )

    private fun orchestrator(
        budgetBytes: Long = 6 * GB,
        memoryEnabled: Boolean = true,
    ): Orchestrator {
        val manager = RuntimeManager(
            budgetBytes = budgetBytes,
            runtimes = mapOf(RuntimeKind.LLAMA_CPP to runtime, RuntimeKind.WHISPER_CPP to runtime),
        )
        val executors = NodeExecutors(
            selector = ModelSelector(registry, device()),
            runtimeManager = manager,
            contextEngine = ContextEngine(),
            memory = if (memoryEnabled) memory else null,
            systemPrompt = "Ты локальный ассистент.",
        )
        return Orchestrator(CapabilityRouter(), executors)
    }

    @Test
    fun `a typed question runs end to end`() = runBlocking {
        val answer = orchestrator().handle(
            UserRequest(conversationId = "c1", text = "Объясни, как работает runtime manager"),
        )

        assertTrue(answer.text.startsWith("ответ по"))
        assertTrue(Capability.TEXT_GENERATION in answer.plan.capabilities)
        assertEquals(NodeType.RESPONSE, answer.pipeline.nodes.last().type)
        assertTrue(answer.trace.isNotEmpty())
    }

    @Test
    fun `voice input is transcribed and the transcript reaches the context`() = runBlocking {
        val answer = orchestrator().handle(
            UserRequest(conversationId = "c1", attachment = audioInput("file://note.wav")),
        )

        assertTrue("asr" in runtime.loaded)
        val transcript = answer.context!!.fragments.single { it.source == FragmentSource.TRANSCRIPT }
        assertEquals("найди мне лучшие локальные модели", transcript.text)
        assertTrue(runtime.prompts.single().contains("найди мне лучшие локальные модели"))
    }

    @Test
    fun `an image goes through the vision model before generation`() = runBlocking {
        val answer = orchestrator().handle(
            UserRequest(conversationId = "c1", text = "что здесь написано?", attachment = imageInput("file://a.png")),
        )

        assertTrue("vlm" in runtime.loaded)
        val vision = answer.context!!.fragments.first { it.source == FragmentSource.VISION }
        assertTrue(vision.text.contains("схема архитектуры"))
        assertTrue(vision.text.contains("CONTEXT ENGINE"), "OCR text should reach the context too")
    }

    @Test
    fun `an image reaches generation directly when no vision model is installed`() = runBlocking {
        // No "vlm" entry here, unlike the shared `registry` — this is the
        // common case (a multimodal chat model like Gemini answers about
        // the image itself; no separate dedicated vision model exists).
        val registryWithoutVision = ModelRegistry(
            listOf(
                RegistryEntry(
                    model(
                        "llm-general",
                        capabilities = setOf(Capability.TEXT_GENERATION, Capability.REASONING),
                        bindings = listOf(binding(ramBytes = 2 * GB)),
                        benchmarks = Benchmarks(general = 70.0),
                    ),
                    InstallState.INSTALLED,
                ),
            ),
        )
        val executors = NodeExecutors(
            selector = ModelSelector(registryWithoutVision, device()),
            runtimeManager = RuntimeManager(budgetBytes = 6 * GB, runtimes = mapOf(RuntimeKind.LLAMA_CPP to runtime)),
            contextEngine = ContextEngine(),
            systemPrompt = "Ты локальный ассистент.",
        )

        val answer = Orchestrator(CapabilityRouter(), executors).handle(
            UserRequest(conversationId = "c1", text = "что на фото?", attachment = imageInput("file://a.png")),
        )

        assertTrue(answer.text.isNotEmpty())
        assertEquals(listOf("file://a.png"), answer.context!!.images.map { it.uri })
    }

    @Test
    fun `the answer and the question are written to memory and found on the next turn`() = runBlocking {
        val orchestrator = orchestrator()

        orchestrator.handle(UserRequest(conversationId = "c1", text = "Решили делать capability registry"))
        memory.consolidate("c1")
        val second = orchestrator.handle(
            UserRequest(conversationId = "c1", text = "Продолжи, что мы решили вчера про registry"),
        )

        assertTrue(NodeType.MEMORY_SEARCH in second.pipeline.nodes.map { it.type })
        val recalled = second.context!!.fragments.filter { it.source == FragmentSource.EPISODIC_MEMORY }
        assertTrue(recalled.any { it.text.contains("capability registry") }, "recalled: $recalled")
    }

    @Test
    fun `earlier turns of this conversation reach the prompt without any recall keyword`() = runBlocking {
        val answer = orchestrator().handle(
            UserRequest(
                conversationId = "c1",
                text = "и второе?",
                history = listOf(
                    ConversationTurn("Вы", "Назови три языка программирования"),
                    ConversationTurn("Модель", "Kotlin, Rust, Python"),
                ),
            ),
        )

        val conversation = answer.context!!.fragments.single { it.source == FragmentSource.CONVERSATION }
        assertTrue(conversation.text.contains("Kotlin, Rust, Python"))
        assertTrue(runtime.prompts.single().contains("Kotlin, Rust, Python"))
    }

    @Test
    fun `memory search runs on an ordinary question, not just an explicit recall`() = runBlocking {
        val answer = orchestrator().handle(
            UserRequest(conversationId = "c1", text = "Объясни, как работает runtime manager"),
        )

        assertTrue(NodeType.MEMORY_SEARCH in answer.pipeline.nodes.map { it.type })
    }

    @Test
    fun `attached documents are named in the prompt even when the question shares no words with their content`() =
        runBlocking {
            val answer = orchestrator().handle(
                UserRequest(
                    conversationId = "c1",
                    text = "Что там у меня загружено?",
                    attachedDocuments = listOf("рецепты.pdf", "заметки.pdf"),
                ),
            )

            val knowledge = answer.context!!.fragments.single { it.source == FragmentSource.KNOWLEDGE }
            assertTrue(knowledge.text.contains("рецепты.pdf"))
            assertTrue(knowledge.text.contains("заметки.pdf"))
            assertTrue(runtime.prompts.single().contains("рецепты.pdf"))
        }

    @Test
    fun `memory is not touched when the user turns it off`() = runBlocking {
        val orchestrator = orchestrator(memoryEnabled = false)

        val answer = orchestrator.handle(
            UserRequest(conversationId = "c2", text = "Продолжи вчерашнее", memoryEnabled = false),
        )

        assertTrue(NodeType.MEMORY_UPDATE !in answer.pipeline.nodes.map { it.type })
        assertTrue(memory.search(MemoryQuery("продолжи", scopes = MemoryScope.entries.toSet())).isEmpty())
    }

    @Test
    fun `models are acquired one at a time and fit a budget that could not hold them together`() = runBlocking {
        // ASR (1 GB) + LLM (2 GB) both fit; a 2.5 GB budget cannot hold both at once.
        val answer = orchestrator(budgetBytes = 2_500_000_000).handle(
            UserRequest(conversationId = "c1", attachment = audioInput("file://note.wav")),
        )

        assertTrue(answer.text.isNotEmpty())
        assertEquals(listOf("asr", "llm-general"), runtime.loaded)
    }

    @Test
    fun `a capability nothing installed provides is reported as such`() = runBlocking {
        val emptyRegistry = ModelRegistry()
        val executors = NodeExecutors(
            selector = ModelSelector(emptyRegistry, device()),
            runtimeManager = RuntimeManager(6 * GB, mapOf(RuntimeKind.LLAMA_CPP to runtime)),
            contextEngine = ContextEngine(),
        )

        val failure = assertFailsWith<NoModelForCapabilityException> {
            Orchestrator(CapabilityRouter(), executors)
                .handle(UserRequest(conversationId = "c1", text = "привет"))
        }

        assertEquals(Capability.TEXT_GENERATION, failure.capability)
    }

    @Test
    fun `a saved pipeline runs through the same path`() = runBlocking {
        val spec = ai.localstudio.core.pipeline.PipelineCodec.decodePipeline(
            java.io.File("../pipelines/photo_analyze.json").readText(),
        )

        val answer = orchestrator().run(
            spec = spec,
            input = imageInput("file://a.png"),
            context = ai.localstudio.core.pipeline.RunContext("c1", userMessage = "что это?"),
        )

        assertTrue(answer.text.isNotEmpty())
        assertTrue("vlm" in runtime.loaded)
    }

    @Test
    fun `missing capabilities of a pipeline can be checked before running it`() {
        val selector = ModelSelector(registry, device())
        val spec = ai.localstudio.core.pipeline.PipelineCodec.decodePipeline(
            java.io.File("../pipelines/voice_rag.json").readText(),
        )

        assertEquals(setOf(Capability.EMBEDDING), spec.missingCapabilities(selector))
    }
}
