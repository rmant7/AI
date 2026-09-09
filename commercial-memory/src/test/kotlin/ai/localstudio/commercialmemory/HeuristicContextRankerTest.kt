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
}
