package ai.localstudio.core.memory

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InMemoryMemoryProviderTest {

    private var now = 1_000L
    private fun provider(extractor: MemoryExtractor = InMemoryMemoryProvider.PromoteWorkingMemory) =
        InMemoryMemoryProvider(extractor) { now += 10; now }

    @Test
    fun `search finds a memory by shared terms`() = runBlocking {
        val memory = provider()
        memory.remember("Пользователь строит локальный AI runtime на Kotlin", MemoryScope.SEMANTIC)
        memory.remember("Обсуждали рецепт борща", MemoryScope.EPISODIC)

        val hits = memory.search(MemoryQuery("что мы решили про локальный runtime"))

        assertEquals(1, hits.size)
        assertTrue(hits.single().text.contains("runtime"))
    }

    @Test
    fun `scopes are respected`() = runBlocking {
        val memory = provider()
        memory.remember("Проект использует Kotlin", MemoryScope.SEMANTIC)
        memory.remember("Вчера чинили пайплайн", MemoryScope.EPISODIC)

        val semantic = memory.search(MemoryQuery("Kotlin пайплайн", scopes = setOf(MemoryScope.SEMANTIC)))
        val episodic = memory.search(MemoryQuery("Kotlin пайплайн", scopes = setOf(MemoryScope.EPISODIC)))

        assertEquals(listOf("Проект использует Kotlin"), semantic.map { it.text })
        assertEquals(listOf("Вчера чинили пайплайн"), episodic.map { it.text })
    }

    @Test
    fun `a stable fact outranks an episode at equal overlap`() = runBlocking {
        val memory = provider()
        memory.remember("Проект использует Qdrant", MemoryScope.SEMANTIC)
        memory.remember("Проект использует Qdrant", MemoryScope.EPISODIC)

        val hits = memory.search(MemoryQuery("проект использует qdrant"))

        assertEquals(MemoryScope.SEMANTIC, hits.first().scope)
    }

    @Test
    fun `between episodes the more recent one wins`() = runBlocking {
        val memory = provider()
        memory.remember("Решили использовать llama.cpp", MemoryScope.EPISODIC)
        memory.remember("Решили использовать llama.cpp и MediaPipe", MemoryScope.EPISODIC)

        val hits = memory.search(MemoryQuery("что решили использовать llama"))

        assertEquals("Решили использовать llama.cpp и MediaPipe", hits.first().text)
    }

    @Test
    fun `metadata filters narrow the search`() = runBlocking {
        val memory = provider()
        memory.remember("Решили делать registry", MemoryScope.EPISODIC, mapOf("conversationId" to "c1"))
        memory.remember("Решили делать registry", MemoryScope.EPISODIC, mapOf("conversationId" to "c2"))

        val hits = memory.search(
            MemoryQuery("решили делать registry", metadataFilter = mapOf("conversationId" to "c2")),
        )

        assertEquals(1, hits.size)
        assertEquals("c2", hits.single().metadata["conversationId"])
    }

    @Test
    fun `unrelated memories are not returned at all`() = runBlocking {
        val memory = provider()
        memory.remember("Пользователь предпочитает тёмную тему", MemoryScope.SEMANTIC)

        assertTrue(memory.search(MemoryQuery("квантование моделей")).isEmpty())
    }

    @Test
    fun `forget removes a memory`() = runBlocking {
        val memory = provider()
        val id = memory.remember("Временный факт про runtime", MemoryScope.SEMANTIC)

        memory.forget(id)

        assertTrue(memory.search(MemoryQuery("runtime")).isEmpty())
        assertTrue(memory.all().isEmpty())
    }

    @Test
    fun `consolidate promotes working memory and clears it`() = runBlocking {
        val memory = provider()
        memory.remember("Обсуждали capability router", MemoryScope.WORKING, mapOf("conversationId" to "c1"))
        memory.remember("Чужой разговор", MemoryScope.WORKING, mapOf("conversationId" to "c2"))

        val promoted = memory.consolidate("c1")

        assertEquals(1, promoted.size)
        assertEquals(MemoryScope.EPISODIC, promoted.single().scope)
        assertEquals(
            listOf(MemoryScope.WORKING),
            memory.all().filter { it.metadata["conversationId"] == "c2" }.map { it.scope },
        )
        assertTrue(memory.all().none { it.scope == MemoryScope.WORKING && it.metadata["conversationId"] == "c1" })
    }

    @Test
    fun `consolidation is pluggable because extraction is a model call`() = runBlocking {
        val memory = provider { _, working ->
            listOf(MemoryItem("", "Итог: " + working.size + " реплик", MemoryScope.SEMANTIC, 0))
        }
        memory.remember("реплика один", MemoryScope.WORKING, mapOf("conversationId" to "c1"))
        memory.remember("реплика два", MemoryScope.WORKING, mapOf("conversationId" to "c1"))

        val promoted = memory.consolidate("c1")

        assertEquals(listOf("Итог: 2 реплик"), promoted.map { it.text })
        assertEquals(MemoryScope.SEMANTIC, promoted.single().scope)
    }

    @Test
    fun `nothing to consolidate is not an error`() = runBlocking {
        assertTrue(provider().consolidate("unknown").isEmpty())
    }
}
