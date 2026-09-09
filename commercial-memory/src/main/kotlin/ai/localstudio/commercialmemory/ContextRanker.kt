package ai.localstudio.commercialmemory

/**
 * Private ranking logic — the part of "context ranking" the boundary rule
 * (see module README) keeps out of Mobile_mem0. An interface, not a class,
 * so [HeuristicContextRanker] can be replaced by a stronger private
 * implementation later without [CommercialContextSelector] or anything
 * above it changing.
 */
interface ContextRanker {
    fun rank(
        query: String,
        candidates: List<ContextCandidate>,
        weights: RankingWeights = RankingWeights(),
    ): List<ContextCandidate>
}

/**
 * Preserves whatever order retrieval already returned candidates in —
 * Mobile_mem0's own lexical-plus-recency ordering, unchanged. This is what
 * [ExperimentMode.BASIC_MEMORY] uses in place of [HeuristicContextRanker]:
 * "no commercial ranking," not "no budget at all" — see
 * [MemoryExperimentRunner], which still runs this through
 * [CommercialContextSelector] for the budget-packing behavior every mode
 * that injects memory should share.
 */
object NoRanking : ContextRanker {
    override fun rank(query: String, candidates: List<ContextCandidate>, weights: RankingWeights): List<ContextCandidate> =
        candidates
}
