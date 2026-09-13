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
    /**
     * Defaults to 0 — not a considered value, an explicit "not decided yet."
     * No embedder is wired up in the app as of this weight's addition, and
     * SEMANTIC_RETRIEVAL_DESIGN.md's own order of work is explicit that
     * which fusion and what weights to use is a measurement against
     * [ExperimentLogger]'s real data, not a guess to ship ahead of it — see
     * that document's step 9. A caller with an embedder actually configured
     * should override this deliberately, not inherit a made-up default.
     */
    val semantic: Double = 0.0,
) {
    init {
        require(lexical >= 0 && taskRelevance >= 0 && recency >= 0 && sourcePriority >= 0 && semantic >= 0) {
            "ranking weights must not be negative"
        }
    }
}
