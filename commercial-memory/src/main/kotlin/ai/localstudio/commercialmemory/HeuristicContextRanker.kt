package ai.localstudio.commercialmemory

/**
 * Deterministic, dependency-free baseline ranker: a weighted sum of each
 * candidate's already-computed signals, sorted descending. Deliberately
 * simple and observable — this exists to make the retrieve→rank→budget→
 * select pipeline testable and measurable *today*, not to be a clinically
 * validated ranking algorithm. See the module README: replacing this with a
 * stronger private ranker later must not require changing [ContextRanker],
 * [CommercialContextSelector], or anything in Mobile_mem0.
 */
class HeuristicContextRanker : ContextRanker {
    override fun rank(
        query: String,
        candidates: List<ContextCandidate>,
        weights: RankingWeights,
    ): List<ContextCandidate> = candidates
        .map { candidate ->
            val score = candidate.lexicalScore * weights.lexical +
                candidate.taskRelevance * weights.taskRelevance +
                candidate.recencyScore * weights.recency +
                candidate.sourcePriority * weights.sourcePriority
            candidate to score
        }
        .sortedByDescending { it.second }
        .map { it.first }
}
