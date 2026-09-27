package ai.localstudio.core.memory

import ai.localstudio.memory.MemoryItem
import ai.localstudio.memory.MemoryScope
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LlmMemoryExtractorTest {

    private fun working(vararg texts: String) =
        texts.mapIndexed { i, text -> MemoryItem("w$i", text, MemoryScope.WORKING, 0L) }

    @Test
    fun `each line becomes one episodic memory`() = runBlocking {
        val extractor = LlmMemoryExtractor { "Пользователь пишет локальный AI\nПредпочитает Kotlin" }

        val result = extractor.extract("c1", working("реплика"))

        assertEquals(listOf("Пользователь пишет локальный AI", "Предпочитает Kotlin"), result.map { it.text })
        assertTrue(result.all { it.scope == MemoryScope.EPISODIC })
    }

    @Test
    fun `a plain negative answer produces nothing`() = runBlocking {
        val extractor = LlmMemoryExtractor { "НЕТ" }

        assertTrue(extractor.extract("c1", working("реплика")).isEmpty())
    }

    @Test
    fun `list markers and numbering are stripped`() = runBlocking {
        val extractor = LlmMemoryExtractor { "- пункт один\n2) пункт два\n* пункт три" }

        val result = extractor.extract("c1", working("реплика"))

        assertEquals(listOf("пункт один", "пункт два", "пункт три"), result.map { it.text })
    }

    @Test
    fun `an attribution footer from the fallback chain is not treated as a fact`() = runBlocking {
        val extractor = LlmMemoryExtractor { "Настоящий факт\n\n---\nAnswer from: Google Gemini" }

        val result = extractor.extract("c1", working("реплика"))

        assertEquals(listOf("Настоящий факт"), result.map { it.text })
    }

    @Test
    fun `nothing to extract from means no model call at all`() = runBlocking {
        var called = false
        val extractor = LlmMemoryExtractor { called = true; "НЕТ" }

        assertTrue(extractor.extract("c1", emptyList()).isEmpty())
        assertTrue(!called)
    }

    @Test
    fun `a failing generator degrades to no memories, not an exception`() = runBlocking {
        val extractor = LlmMemoryExtractor { throw IllegalStateException("no model available") }

        assertTrue(extractor.extract("c1", working("реплика")).isEmpty())
    }
}
