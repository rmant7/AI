package ai.localstudio.commercialmemory

/**
 * Fixed weights [HeuristicContextRanker] combines a [ContextCandidate]'s
 * signals with. "Weights" — not "learned" or "adaptive": these are engineer-
 * chosen constants, not fit on any user's data. Deliberately not named for
 * a learning mechanism this Stage-3 baseline does not have — see the
 * module README's "What this deliberately is not yet" section.
 */
data class RankingWeights(
    val lexical: Double = 0.45,
    val taskRelevance: Double = 0.30,
    val recency: Double = 0.15,
    val sourcePriority: Double = 0.10,
) {
    init {
        require(lexical >= 0 && taskRelevance >= 0 && recency >= 0 && sourcePriority >= 0) {
            "ranking weights must not be negative"
        }
    }
}
