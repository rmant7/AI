package ai.localstudio.commercialmemory

import ai.localstudio.memory.MemoryItem

/**
 * The one [ContextSelector] this baseline ships with: rank by [ranker],
 * then pack candidates into [ContextBudget] in ranked order.
 *
 * Packing rule: the first selected item is always taken, even if it alone
 * exceeds [ContextBudget.maxCharacters] — truncated to fit rather than
 * selecting nothing at all, since an empty selection is a worse outcome
 * than a single truncated one. Every item after that is skipped, not
 * truncated, once it would push the running total over budget: skipping
 * (not stopping outright) lets a smaller, lower-ranked item that still
 * fits get selected instead of an early miss wasting the rest of the
 * budget.
 */
class CommercialContextSelector(
    private val ranker: ContextRanker = HeuristicContextRanker(),
    private val weights: RankingWeights = RankingWeights(),
) : ContextSelector {

    override fun select(
        query: String,
        candidates: List<ContextCandidate>,
        budget: ContextBudget,
    ): ContextSelection {
        val ranked = ranker.rank(query, candidates, weights)

        val selected = ArrayList<MemoryItem>()
        val scores = LinkedHashMap<String, Double>()
        var used = 0

        for (candidate in ranked) {
            if (selected.size >= budget.maxItems) break
            val memory = candidate.memory
            val size = memory.text.length

            if (selected.isEmpty() && size > budget.maxCharacters) {
                selected += memory.copy(text = memory.text.take(budget.maxCharacters))
                scores[memory.id] = candidate.lexicalScore
                used = budget.maxCharacters
                break
            }
            if (selected.isNotEmpty() && used + size > budget.maxCharacters) continue

            selected += memory
            scores[memory.id] = candidate.lexicalScore
            used += size
            if (used >= budget.maxCharacters) break
        }

        return ContextSelection(selected, scores, used)
    }
}
