package ai.localstudio.core.engine

import ai.localstudio.commercialmemory.AppMemory
import ai.localstudio.commercialmemory.CommercialContextSelector
import ai.localstudio.commercialmemory.ExperimentMode
import ai.localstudio.commercialmemory.InMemoryExperimentLogger
import ai.localstudio.commercialmemory.MemoryExperimentRunner
import ai.localstudio.core.GB
import ai.localstudio.core.binding
import ai.localstudio.core.capability.Capability
import ai.localstudio.core.context.ContextEngine
import ai.localstudio.core.context.FragmentSource
import ai.localstudio.core.device
import ai.localstudio.core.model
import ai.localstudio.core.registry.Benchmarks
import ai.localstudio.core.registry.InstallState
import ai.localstudio.core.registry.ModelRegistry
import ai.localstudio.core.registry.RegistryEntry
import ai.localstudio.core.router.CapabilityRouter
import ai.localstudio.core.runtime.RuntimeManager
import ai.localstudio.memory.FileMemoryStore
import ai.localstudio.memory.MemoryScope
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves the three [ExperimentMode]s actually take different code paths
 * through the real pipeline — not just inside `:commercial-memory`'s own
 * unit tests, which never touch [NodeExecutors] or [ContextEngine] at all —
 * and that leaving `memoryExperiment` unset (every existing caller, as of
 * this branch) is a true no-op: see `OrchestratorTest`'s full suite passing
 * unchanged, which this class does not repeat.
 */
class MemoryExperimentModeTest {

    private val runtime = FakeRuntime()
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
        ),
    )

    private fun orchestrator(mode: ExperimentMode, memory: FileMemoryStore, logger: InMemoryExperimentLogger): Orchestrator {
        val manager = RuntimeManager(budgetBytes = 6 * GB, runtimes = mapOf(ai.localstudio.core.registry.RuntimeKind.LLAMA_CPP to runtime))
        val executors = NodeExecutors(
            selector = ModelSelector(registry, device()),
            runtimeManager = manager,
            contextEngine = ContextEngine(),
            memory = memory,
            memoryExperiment = MemoryExperimentRunner(AppMemory(memory), selector = CommercialContextSelector(), logger = logger),
            memoryExperimentMode = mode,
            systemPrompt = "Ты локальный ассистент.",
        )
        return Orchestrator(CapabilityRouter(), executors)
    }

    private fun memoryWithFact(): FileMemoryStore {
        val store = FileMemoryStore(java.io.File.createTempFile("mode-test", ".json").apply { deleteOnExit() })
        runBlocking { store.remember("Решили делать capability registry", MemoryScope.EPISODIC) }
        return store
    }

    @Test
    fun `MEMORY_OFF never surfaces a memory fragment even though a matching memory exists`() = runBlocking {
        val logger = InMemoryExperimentLogger()
        val answer = orchestrator(ExperimentMode.MEMORY_OFF, memoryWithFact(), logger).handle(
            UserRequest(conversationId = "c1", text = "Что мы решили про registry вчера?"),
        )

        assertTrue(answer.context!!.fragments.none { it.source == FragmentSource.EPISODIC_MEMORY })
        assertEquals(ExperimentMode.MEMORY_OFF, logger.all().single().mode)
        assertEquals(0, logger.all().single().selectedCount)
    }

    @Test
    fun `BASIC_MEMORY and COMMERCIAL_MEMORY both surface the matching memory`() = runBlocking {
        val basicLogger = InMemoryExperimentLogger()
        val basicAnswer = orchestrator(ExperimentMode.BASIC_MEMORY, memoryWithFact(), basicLogger).handle(
            UserRequest(conversationId = "c1", text = "Что мы решили про registry вчера?"),
        )
        val commercialLogger = InMemoryExperimentLogger()
        val commercialAnswer = orchestrator(ExperimentMode.COMMERCIAL_MEMORY, memoryWithFact(), commercialLogger).handle(
            UserRequest(conversationId = "c1", text = "Что мы решили про registry вчера?"),
        )

        assertTrue(basicAnswer.context!!.fragments.any { it.source == FragmentSource.EPISODIC_MEMORY })
        assertTrue(commercialAnswer.context!!.fragments.any { it.source == FragmentSource.EPISODIC_MEMORY })
        assertEquals(ExperimentMode.BASIC_MEMORY, basicLogger.all().single().mode)
        assertEquals(ExperimentMode.COMMERCIAL_MEMORY, commercialLogger.all().single().mode)
    }
}
