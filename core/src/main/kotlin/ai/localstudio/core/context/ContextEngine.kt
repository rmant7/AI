package ai.localstudio.core.context

/** Where a piece of context came from. Determines default priority and how it is rendered. */
enum class FragmentSource {
    SYSTEM,
    USER_MESSAGE,
    TRANSCRIPT,
    VISION,
    CONVERSATION,
    SEMANTIC_MEMORY,
    EPISODIC_MEMORY,
    KNOWLEDGE,
    TOOL_RESULT,
}

data class ContextFragment(
    val source: FragmentSource,
    val text: String,
    val label: String? = null,
    /** Higher wins when the budget is tight; defaults to the source's rank. */
    val priority: Int = source.defaultPriority(),
    /** Relevance from retrieval, used to order fragments of equal priority. */
    val relevance: Double = 0.0,
)

data class DroppedFragment(val fragment: ContextFragment, val tokens: Int)

data class AssembledContext(
    val fragments: List<ContextFragment>,
    val dropped: List<DroppedFragment>,
    val usedTokens: Int,
    val budgetTokens: Int,
) {
    /** The prompt as the model sees it: labelled sections in priority order. */
    fun render(): String = fragments.joinToString("\n\n") { fragment ->
        val label = fragment.label ?: fragment.source.name
        "[$label]\n${fragment.text}"
    }
}

fun interface TokenCounter {
    fun count(text: String): Int
}

/**
 * Approximation used until a real tokenizer for the selected model is wired in.
 * Deliberately pessimistic: overestimating tokens truncates context, whereas
 * underestimating overflows it mid-generation.
 *
 * A flat 4-chars-per-token rule is an English-text average; most tokenizers
 * spend closer to 1-2 characters per token on Cyrillic (and most other
 * non-Latin scripts), so counting Russian text at the same rate as English
 * underestimated it by roughly half. That gap is exactly what let an
 * assembled context look like it fit the budget while the model's real
 * tokenizer still overflowed the context window — a silent generation
 * failure a Russian-speaking user would just see as "the model doesn't work".
 */
object HeuristicTokenCounter : TokenCounter {
    override fun count(text: String): Int {
        var asciiChars = 0
        var wideChars = 0
        for (ch in text) {
            if (ch.code < 128) asciiChars++ else wideChars++
        }
        return (asciiChars + 3) / 4 + (wideChars + 1) / 2
    }
}

/**
 * Builds the prompt from everything the system knows.
 *
 * This is the part that makes "continue what we were doing yesterday" work:
 * the model is never asked to remember, it is handed the memory. Fragments are
 * packed by priority under a token budget, and whatever does not fit is
 * reported in [AssembledContext.dropped] instead of being silently lost.
 */
class ContextEngine(
    private val tokenCounter: TokenCounter = HeuristicTokenCounter,
    /** Share of the budget reserved for the model's answer. */
    private val responseReserveRatio: Double = 0.25,
) {
    init {
        require(responseReserveRatio in 0.0..0.9) { "responseReserveRatio out of range" }
    }

    fun assemble(fragments: List<ContextFragment>, contextWindowTokens: Int): AssembledContext {
        val budget = ((1.0 - responseReserveRatio) * contextWindowTokens).toInt()

        val ordered = fragments.sortedWith(
            compareByDescending<ContextFragment> { it.priority }
                .thenByDescending { it.relevance },
        )

        val kept = mutableListOf<ContextFragment>()
        val dropped = mutableListOf<DroppedFragment>()
        var used = 0

        for (fragment in ordered) {
            val tokens = tokenCounter.count(fragment.text)
            if (used + tokens <= budget) {
                kept += fragment
                used += tokens
            } else {
                dropped += DroppedFragment(fragment, tokens)
            }
        }

        return AssembledContext(
            fragments = kept,
            dropped = dropped,
            usedTokens = used,
            budgetTokens = budget,
        )
    }
}

private fun FragmentSource.defaultPriority(): Int = when (this) {
    FragmentSource.SYSTEM -> 100
    FragmentSource.USER_MESSAGE -> 90
    FragmentSource.TRANSCRIPT -> 85
    FragmentSource.VISION -> 80
    FragmentSource.TOOL_RESULT -> 70
    FragmentSource.CONVERSATION -> 60
    FragmentSource.SEMANTIC_MEMORY -> 50
    FragmentSource.KNOWLEDGE -> 40
    FragmentSource.EPISODIC_MEMORY -> 30
}
