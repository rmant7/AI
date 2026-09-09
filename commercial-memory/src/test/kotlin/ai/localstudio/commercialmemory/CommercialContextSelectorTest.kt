package ai.localstudio.commercialmemory

import ai.localstudio.memory.MemoryItem
import ai.localstudio.memory.MemoryScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommercialContextSelectorTest {

    private fun item(id: String, text: String, scope: MemoryScope = MemoryScope.SEMANTIC, createdAt: Long = 0) =
        MemoryItem(id, text, scope, createdAt)

    @Test
    fun `ranks the more relevant candidate first`() {
        val items = listOf(
            item("a", "The user prefers local Android AI models"),
            item("b", "The weather is cold today"),
        )
        val candidates = buildCandidates("Android AI models", items)

        val result = CommercialContextSelector().select("Android AI models", candidates)

        assertTrue(result.items.isNotEmpty())
        assertEquals("a", result.items.first().id)
    }

    @Test
    fun `respects the character budget`() {
        val items = listOf(
            item("a", "alpha beta gamma delta epsilon"),
            item("b", "alpha beta another memory"),
        )
        val candidates = buildCandidates("alpha beta", items)

        val result = CommercialContextSelector().select("alpha beta", candidates, ContextBudget(maxCharacters = 12))

        assertTrue(result.estimatedCharacters <= 12)
    }

    @Test
    fun `a single oversized item is still selected, truncated, rather than selecting nothing`() {
        val items = listOf(item("a", "x".repeat(100)))
        val candidates = buildCandidates("x", items)

        val result = CommercialContextSelector().select("x", candidates, ContextBudget(maxCharacters = 10))

        assertEquals(1, result.items.size)
        assertEquals(10, result.items.single().text.length)
        assertEquals(10, result.estimatedCharacters)
    }

    @Test
    fun `an oversized item is only allowed through when it is first, not later`() {
        val items = listOf(
            item("small", "fits"),
            item("huge", "x".repeat(100)),
        )
        val candidates = buildCandidates("fits huge", items)

        val result = CommercialContextSelector().select("fits huge", candidates, ContextBudget(maxCharacters = 10))

        assertEquals(listOf("small"), result.items.map { it.id })
    }

    @Test
    fun `maxItems caps the selection even when characters would still fit`() {
        val items = (1..5).map { item("id$it", "short text $it") }
        val candidates = buildCandidates("short text", items)

        val result = CommercialContextSelector().select("short text", candidates, ContextBudget(maxCharacters = 10_000, maxItems = 2))

        assertEquals(2, result.items.size)
    }

    @Test
    fun `NoRanking preserves retrieval order instead of scoring`() {
        val items = listOf(
            item("first", "irrelevant text about gardening"),
            item("second", "highly relevant to the query terms"),
        )
        val candidates = buildCandidates("highly relevant query", items)

        val result = CommercialContextSelector(ranker = NoRanking).select("highly relevant query", candidates)

        // Despite "second" scoring higher on lexical overlap, NoRanking keeps input order.
        assertEquals(listOf("first", "second"), result.items.map { it.id })
    }
}
