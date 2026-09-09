package ai.localstudio.commercialmemory

import ai.localstudio.memory.FileMemoryStore
import ai.localstudio.memory.MemoryScope
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Exercises [AppMemory] against a real [FileMemoryStore] — the same
 * MemoryProvider shape the app actually wires up — rather than a fake, so
 * this proves the facade forwards correctly, not just that its own logic
 * is internally consistent.
 */
class AppMemoryTest {

    private fun appMemory(): AppMemory {
        val file = File.createTempFile("app-memory-test", ".json").apply { deleteOnExit() }
        return AppMemory(FileMemoryStore(file))
    }

    @Test
    fun `remember then candidates finds it by shared terms`() = runBlocking {
        val memory = appMemory()
        memory.remember("Пользователь строит локальный AI runtime", MemoryScope.SEMANTIC)

        val hits = memory.candidates("локальный runtime")

        assertEquals(1, hits.size)
    }

    @Test
    fun `forget removes it from future candidates`() = runBlocking {
        val memory = appMemory()
        val id = memory.remember("временный факт", MemoryScope.SEMANTIC)

        memory.forget(id)

        assertTrue(memory.candidates("временный факт").isEmpty())
    }

    @Test
    fun `consolidate promotes working memory for the given conversation`() = runBlocking {
        val memory = appMemory()
        memory.remember("обсуждали capability router", MemoryScope.WORKING, mapOf("conversationId" to "c1"))

        val promoted = memory.consolidate("c1")

        assertEquals(1, promoted.size)
        assertEquals(MemoryScope.EPISODIC, promoted.single().scope)
    }
}
