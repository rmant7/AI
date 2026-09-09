package ai.localstudio.commercialmemory

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
    ): ContextSelection {
        val start = clock()

        val (candidateCount, selection) = when (mode) {
            ExperimentMode.MEMORY_OFF ->
                0 to ContextSelection(emptyList(), emptyMap(), 0)

            ExperimentMode.BASIC_MEMORY -> {
                val items = appMemory.candidates(query, limit = retrievalLimit)
                val candidates = buildCandidates(query, items)
                // Budget-packed, same as COMMERCIAL_MEMORY, but with ranking
                // switched off (NoRanking) — see that object's own comment
                // for why this still goes through CommercialContextSelector
                // rather than a separate, unbudgeted code path.
                items.size to CommercialContextSelector(ranker = NoRanking).select(query, candidates, budget)
            }

            ExperimentMode.COMMERCIAL_MEMORY -> {
                val items = appMemory.candidates(query, limit = retrievalLimit)
                val candidates = buildCandidates(query, items)
                items.size to selector.select(query, candidates, budget)
            }
        }

        logger.log(
            ExperimentRecord(
                experimentId = idGenerator(),
                timestampEpochMs = start,
                mode = mode,
                queryLength = query.length,
                candidateCount = candidateCount,
                selectedCount = selection.items.size,
                selectedCharacters = selection.estimatedCharacters,
                latencyMs = clock() - start,
            ),
        )

        return selection
    }

    private companion object {
        const val DEFAULT_RETRIEVAL_LIMIT = 40
    }
}
