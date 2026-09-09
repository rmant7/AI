package ai.localstudio.commercialmemory

/**
 * Turns ranked candidates into the final, budget-respecting selection. An
 * interface, separate from [ContextRanker], because ranking ("what order
 * matters most") and selection ("how much of that fits, and what happens to
 * the first item that alone exceeds the budget") are different questions —
 * see [CommercialContextSelector] for the one implementation this baseline
 * ships with.
 */
interface ContextSelector {
    fun select(
        query: String,
        candidates: List<ContextCandidate>,
        budget: ContextBudget = ContextBudget(),
    ): ContextSelection
}
