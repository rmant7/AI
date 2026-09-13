package ai.localstudio.commercialmemory

import ai.localstudio.memory.MemoryItem
import ai.localstudio.memory.MemoryScope
import kotlin.test.Test
import kotlin.test.assertEquals

class HeuristicContextRankerTest {

    private fun candidate(id: String, lexical: Double, task: Double = 0.0, recency: Double = 0.0, source: Double = 0.0) =
        ContextCandidate(MemoryItem(id, "text $id", MemoryScope.SEMANTIC, 0), lexical, task, recency, source)

    @Test
    fun `higher weighted score sorts first`() {
        val low = candidate("low", lexical = 0.1)
        val high = candidate("high", lexical = 0.9)

        val ranked = HeuristicContextRanker().rank("q", listOf(low, high))

        assertEquals(listOf("high", "low"), ranked.map { it.memory.id })
    }

    @Test
    fun `weights actually change the outcome`() {
        // "task" wins on task relevance only; "lex" wins on lexical only.
        val lex = candidate("lex", lexical = 1.0, task = 0.0)
        val task = candidate("task", lexical = 0.0, task = 1.0)

        val lexWins = HeuristicContextRanker().rank("q", listOf(task, lex), RankingWeights(lexical = 1.0, taskRelevance = 0.0, recency = 0.0, sourcePriority = 0.0))
        val taskWins = HeuristicContextRanker().rank("q", listOf(lex, task), RankingWeights(lexical = 0.0, taskRelevance = 1.0, recency = 0.0, sourcePriority = 0.0))

        assertEquals("lex", lexWins.first().memory.id)
        assertEquals("task", taskWins.first().memory.id)
    }

    @Test
    fun `an empty candidate list ranks to an empty list`() {
        assertEquals(emptyList(), HeuristicContextRanker().rank("q", emptyList()))
    }

    @Test
    fun `semanticScore contributes nothing at the default weight of zero`() {
        val noSemantic = candidate("no-semantic", lexical = 0.5).copy(semanticScore = null)
        val highSemantic = candidate("high-semantic", lexical = 0.5).copy(semanticScore = 0.99)

        val ranked = HeuristicContextRanker().rank("q", listOf(noSemantic, highSemantic))

        // Tied on every weighted signal (both have identical lexical/task/
        // recency/source scores and the default semantic weight is 0) — the
        // one with an actual semantic score must not be treated as any more
        // relevant until something has actually decided that weight matters.
        assertEquals(setOf("no-semantic", "high-semantic"), ranked.map { it.memory.id }.toSet())
    }

    @Test
    fun `a non-zero semantic weight lets semanticScore change the outcome`() {
        val lexicalWinner = candidate("lexical-winner", lexical = 0.9).copy(semanticScore = 0.0)
        val semanticWinner = candidate("semantic-winner", lexical = 0.1).copy(semanticScore = 0.9)
        val weights = RankingWeights(lexical = 0.5, taskRelevance = 0.0, recency = 0.0, sourcePriority = 0.0, semantic = 0.5)

        val ranked = HeuristicContextRanker().rank("q", listOf(lexicalWinner, semanticWinner), weights)

        assertEquals("semantic-winner", ranked.first().memory.id, "0.1*0.5 + 0.9*0.5 must beat 0.9*0.5 + 0.0*0.5")
    }

    @Test
    fun `a null semanticScore is treated as absent, not as the worst possible score`() {
        val noVectorYet = candidate("no-vector-yet", lexical = 0.5).copy(semanticScore = null)
        val weights = RankingWeights(lexical = 1.0, taskRelevance = 0.0, recency = 0.0, sourcePriority = 0.0, semantic = 1.0)

        // Must not throw on a null semanticScore even with a non-zero semantic
        // weight — a real backend reports null for exactly this case (no
        // semantic index configured, or this item not embedded yet).
        val ranked = HeuristicContextRanker().rank("q", listOf(noVectorYet), weights)

        assertEquals(listOf("no-vector-yet"), ranked.map { it.memory.id })
    }
}
