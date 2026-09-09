package ai.localstudio.commercialmemory

import ai.localstudio.memory.FileMemoryStore
import ai.localstudio.memory.MemoryScope
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemoryExperimentRunnerTest {

    private fun memoryWithData(): AppMemory {
        val file = File.createTempFile("experiment-runner-test", ".json").apply { deleteOnExit() }
        val store = FileMemoryStore(file)
        runBlocking {
            store.remember("Пользователь предпочитает локальные модели, а не облачные", MemoryScope.SEMANTIC)
            store.remember("Обсуждали квантование весов модели вчера", MemoryScope.EPISODIC)
        }
        return AppMemory(store)
    }

    @Test
    fun `MEMORY_OFF never retrieves anything, regardless of what is stored`() = runBlocking {
        val logger = InMemoryExperimentLogger()
        val runner = MemoryExperimentRunner(memoryWithData(), logger = logger)

        val result = runner.run("локальные модели", ExperimentMode.MEMORY_OFF)

        assertTrue(result.items.isEmpty())
        assertEquals(0, logger.all().single().candidateCount)
    }

    @Test
    fun `BASIC_MEMORY retrieves without commercial ranking`() = runBlocking {
        val runner = MemoryExperimentRunner(memoryWithData())

        val result = runner.run("локальные модели", ExperimentMode.BASIC_MEMORY)

        assertTrue(result.items.isNotEmpty())
    }

    @Test
    fun `COMMERCIAL_MEMORY ranks the more relevant memory first`() = runBlocking {
        val runner = MemoryExperimentRunner(memoryWithData())

        val result = runner.run("локальные модели вместо облачных", ExperimentMode.COMMERCIAL_MEMORY)

        assertTrue(result.items.isNotEmpty())
        assertTrue(result.items.first().text.contains("локальные"))
    }

    @Test
    fun `every mode is logged, and the three modes are distinguishable in the log`() = runBlocking {
        val logger = InMemoryExperimentLogger()
        val runner = MemoryExperimentRunner(memoryWithData(), logger = logger)

        runner.run("локальные модели", ExperimentMode.MEMORY_OFF)
        runner.run("локальные модели", ExperimentMode.BASIC_MEMORY)
        runner.run("локальные модели", ExperimentMode.COMMERCIAL_MEMORY)

        val modes = logger.all().map { it.mode }
        assertEquals(listOf(ExperimentMode.MEMORY_OFF, ExperimentMode.BASIC_MEMORY, ExperimentMode.COMMERCIAL_MEMORY), modes)
    }

    @Test
    fun `selection never exceeds the configured character budget`() = runBlocking {
        val runner = MemoryExperimentRunner(memoryWithData(), budget = ContextBudget(maxCharacters = 20))

        val result = runner.run("локальные модели облачные квантование", ExperimentMode.COMMERCIAL_MEMORY)

        assertTrue(result.estimatedCharacters <= 20)
    }
}
