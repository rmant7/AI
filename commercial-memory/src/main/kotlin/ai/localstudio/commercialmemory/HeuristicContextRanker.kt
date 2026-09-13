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
                candidate.sourcePriority * weights.sourcePriority +
                // null (no semantic index configured, or this item has no
                // vector yet) contributes nothing rather than being treated
                // as "definitely irrelevant" (0.0 would be a real score for
                // cosine similarity, not an absence of one) — but at
                // weights.semantic's own default of 0, this line changes
                // nothing regardless, by design.
                (candidate.semanticScore ?: 0.0) * weights.semantic
            candidate to score
        }
        .sortedByDescending { it.second }
        .map { it.first }
}
