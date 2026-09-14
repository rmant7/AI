package ai.localstudio.commercialmemory

import ai.localstudio.memory.MemoryCandidate
import java.util.UUID

/**
 * The single entry point the rest of the application calls for memory-backed
 * context, in any of the three modes this Stage-3 baseline exists to compare
 * — see [ExperimentMode]. This is the "existing pipeline" integration point:
 * a caller (see `:core`'s `NodeExecutors`) gets a [ContextSelection] back and
 * turns its items into whatever fragment type its own prompt builder uses;
 * this class owns retrieval, ranking, budgeting, and measurement, and
 * nothing about how the result is rendered into a prompt.
 */
class MemoryExperimentRunner(
    private val appMemory: AppMemory,
    private val selector: ContextSelector = CommercialContextSelector(),
    private val budget: ContextBudget = ContextBudget(),
    private val logger: ExperimentLogger = InMemoryExperimentLogger(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) {

    suspend fun run(
        query: String,
        mode: ExperimentMode,
        retrievalLimit: Int = DEFAULT_RETRIEVAL_LIMIT,
        /** See [AppMemory.candidates]'s own `matchAll` parameter. */
        matchAll: Boolean = false,
    ): ContextSelection {
        val start = clock()

        val (candidateCount, semanticOnlyCandidateCount, selection) = when (mode) {
            ExperimentMode.MEMORY_OFF ->
                Triple(0, 0, ContextSelection(emptyList(), emptyMap(), 0))

            ExperimentMode.BASIC_MEMORY -> {
                val items = appMemory.candidates(query, limit = retrievalLimit, matchAll = matchAll)
                val candidates = buildCandidates(query, items)
                // Budget-packed, same as COMMERCIAL_MEMORY, but with ranking
                // switched off (NoRanking) — see that object's own comment
                // for why this still goes through CommercialContextSelector
                // rather than a separate, unbudgeted code path.
                Triple(
                    items.size,
                    semanticOnlyCount(items),
                    CommercialContextSelector(ranker = NoRanking).select(query, candidates, budget),
                )
            }

            ExperimentMode.COMMERCIAL_MEMORY -> {
                val items = appMemory.candidates(query, limit = retrievalLimit, matchAll = matchAll)
                val candidates = buildCandidates(query, items)
                Triple(items.size, semanticOnlyCount(items), selector.select(query, candidates, budget))
            }
        }

        logger.log(
            ExperimentRecord(
                experimentId = idGenerator(),
                timestampEpochMs = start,
                mode = mode,
                queryLength = query.length,
                candidateCount = candidateCount,
                semanticOnlyCandidateCount = semanticOnlyCandidateCount,
                selectedCount = selection.items.size,
                selectedCharacters = selection.estimatedCharacters,
                latencyMs = clock() - start,
            ),
        )

        return selection
    }

    /**
     * How many of [items] a lexical-only search (what this app had before
     * semantic retrieval existed) would never have found at all — a vocabulary
     * mismatch between query and stored text, closed only by cosine similarity.
     * The number that actually answers "is the embedder pulling its weight,"
     * as opposed to candidateCount alone, which a lexical match can inflate on
     * its own and says nothing about which retriever actually found what.
     */
    private fun semanticOnlyCount(items: List<MemoryCandidate>): Int =
        items.count { it.semanticRank != null && it.lexicalRank == null }

    private companion object {
        const val DEFAULT_RETRIEVAL_LIMIT = 40
    }
}
