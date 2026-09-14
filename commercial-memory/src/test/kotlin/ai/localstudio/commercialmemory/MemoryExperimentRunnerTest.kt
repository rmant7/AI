package ai.localstudio.commercialmemory

import ai.localstudio.memory.FileMemoryStore
import ai.localstudio.memory.InMemorySemanticIndex
import ai.localstudio.memory.MemoryEmbedder
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
    fun `matchAll surfaces candidates for a query sharing no vocabulary with what is stored`() = runBlocking {
        val runner = MemoryExperimentRunner(memoryWithData())

        val withoutMatchAll = runner.run("что тебе известно обо мне?", ExperimentMode.COMMERCIAL_MEMORY)
        val withMatchAll = runner.run("что тебе известно обо мне?", ExperimentMode.COMMERCIAL_MEMORY, matchAll = true)

        assertTrue(withoutMatchAll.items.isEmpty(), "sanity check: this meta-question really finds nothing by default")
        assertTrue(withMatchAll.items.isNotEmpty())
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
    fun `semanticOnlyCandidateCount reports items a lexical-only search would never find`() = runBlocking {
        // A trivial embedder: every text maps to the same vector, so cosine
        // similarity is always 1.0 — this test only cares that an item with
        // zero shared vocabulary with the query is still found (semanticRank
        // set, lexicalRank null), not about ranking real embedding quality.
        val fakeEmbedder = object : MemoryEmbedder {
            override val modelId = "fake-test-embedder"
            override val dimension = 2
            override suspend fun embedForQuery(query: String) = floatArrayOf(1f, 0f)
            override suspend fun embedForStorage(texts: List<String>) = texts.map { floatArrayOf(1f, 0f) }
        }
        val index = InMemorySemanticIndex(modelId = fakeEmbedder.modelId, dimension = fakeEmbedder.dimension)
        val file = File.createTempFile("experiment-runner-semantic-test", ".json").apply { deleteOnExit() }
        val store = FileMemoryStore(file, semanticIndex = index, embedder = fakeEmbedder)
        store.remember("совершенно не связанный по словам текст", MemoryScope.SEMANTIC)
        store.embedPending()

        val logger = InMemoryExperimentLogger()
        val runner = MemoryExperimentRunner(AppMemory(store), logger = logger)

        val result = runner.run("абсолютно другой запрос без общих слов", ExperimentMode.COMMERCIAL_MEMORY)

        assertTrue(result.items.isNotEmpty(), "sanity check: the semantic-only item was actually selected")
        assertEquals(1, logger.all().single().semanticOnlyCandidateCount)
    }

    @Test
    fun `selection never exceeds the configured character budget`() = runBlocking {
        val runner = MemoryExperimentRunner(memoryWithData(), budget = ContextBudget(maxCharacters = 20))

        val result = runner.run("локальные модели облачные квантование", ExperimentMode.COMMERCIAL_MEMORY)

        assertTrue(result.estimatedCharacters <= 20)
    }
}
