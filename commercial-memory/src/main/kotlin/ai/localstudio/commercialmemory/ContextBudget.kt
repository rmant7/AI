package ai.localstudio.commercialmemory

/**
 * How much recalled memory is allowed into one turn's prompt. Memory must
 * not be free to grow the prompt without bound — a character budget, not a
 * token-aware one: this is a stable, deterministic baseline meant to be
 * measured, not the final accounting. Swapping in a token-aware budget
 * later (once the actual model's tokenizer and context window are known at
 * this layer) is meant to be a change to how this class computes fit, not
 * to [ContextSelector]'s contract.
 *
 * The defaults are conservative placeholders, not measured limits for any
 * specific model — see this module's README for why the real number
 * depends on the context window of whichever model actually answers.
 */
data class ContextBudget(
    val maxCharacters: Int = 12_000,
    val maxItems: Int = 20,
) {
    init {
        require(maxCharacters >= 0) { "maxCharacters must not be negative: $maxCharacters" }
        require(maxItems >= 0) { "maxItems must not be negative: $maxItems" }
    }
}
