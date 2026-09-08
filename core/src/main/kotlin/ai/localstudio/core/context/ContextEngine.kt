package ai.localstudio.core.context

import ai.localstudio.core.model.ImageRef

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
    /**
     * Attached images, carried alongside the text fragments rather than as
     * one of them: an image has no token cost under this engine's budgeting
     * (it never goes through [ContextEngine]'s truncation) and nothing to
     * render as prompt text — it is handed to
     * [ai.localstudio.core.runtime.GenerationRequest.images] as-is for a
     * runtime that understands vision to embed directly.
     */
    val images: List<ImageRef> = emptyList(),
) {
    /**
     * The prompt as the model sees it.
     *
     * Two rules, both learned from a model answering something other than
     * what it was asked. Ordered so the user's question is last, right
     * before the model's own turn begins (see [FragmentSource.renderOrder]);
     * and the question itself carries no heading at all, so the turn ends on
     * a plain question rather than on another labelled section.
     *
     * That second rule is why the headings are prose rather than the
     * `[SYSTEM]`-style brackets this used to emit. With brackets, a model
     * whose own chat template was applied correctly — verified present, 18k
     * characters of it — still treated the turn as a structured document to
     * continue: it reproduced `[SYSTEM]` and `[CONVERSATION]` sections of
     * its own and invented an `[ASSISTANT_MESSAGE]` label to write its
     * answer under. Section markers that look like a template invite the
     * model to keep filling the template in.
     */
    fun render(): String = fragments.joinToString("\n\n") { fragment ->
        val heading = fragment.label ?: fragment.source.heading()
        if (heading == null) fragment.text else "## $heading\n${fragment.text}"
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

    fun assemble(fragments: List<ContextFragment>, contextWindowTokens: Int, images: List<ImageRef> = emptyList()): AssembledContext {
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
            // Selected by priority above — which is the right question for
            // "what survives a tight budget" and the wrong one for "what
            // order does the model read this in". Sorting stably by
            // renderOrder here separates the two: the same fragments are
            // kept, but the user's question ends up last instead of second,
            // and equal-source fragments keep the relevance order they were
            // selected in.
            fragments = kept.sortedBy { it.source.renderOrder() },
            dropped = dropped,
            usedTokens = used,
            budgetTokens = budget,
            images = images,
        )
    }
}

/** How much a fragment deserves to survive a tight budget. Nothing to do with what order it is read in — see [renderOrder]. */
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

/**
 * The heading this section is introduced by, or null for a section that is
 * shown bare.
 *
 * The user's own question — typed or spoken — is the null case, and that is
 * the point: it is rendered last (see [renderOrder]) with nothing wrapped
 * around it, so the user's turn ends on the question itself. A heading there
 * would put one more piece of document structure between the question and
 * the model's turn, which is exactly what a model was observed continuing
 * instead of answering.
 */
private fun FragmentSource.heading(): String? = when (this) {
    FragmentSource.USER_MESSAGE, FragmentSource.TRANSCRIPT -> null
    FragmentSource.SYSTEM -> "Инструкции"
    FragmentSource.KNOWLEDGE -> "Прикреплённые материалы"
    FragmentSource.SEMANTIC_MEMORY -> "Из прошлых разговоров"
    FragmentSource.EPISODIC_MEMORY -> "Из прошлых разговоров"
    FragmentSource.CONVERSATION -> "Ранее в этом разговоре"
    FragmentSource.TOOL_RESULT -> "Результат инструмента"
    FragmentSource.VISION -> "На изображении"
}

/**
 * Where a section sits in the finished prompt, lowest first — deliberately
 * *not* [defaultPriority], which answers a completely different question.
 *
 * A language model continues from wherever the prompt stops, so whatever
 * ends up last is what it treats as the thing to respond to. Ordering the
 * prompt by priority put the user's question second from the top and left
 * recalled memory and attached documents at the bottom, which is exactly
 * what a model then answered: a real chat about desserts, asked to
 * elaborate, came back mid-sentence with "о черной дыре." followed by an
 * invented `[MODEL_RESPONSE]` label — the model had continued the recalled
 * memory fragment the prompt happened to end on, and mimicked the bracket
 * labels it saw around it, rather than answering the question buried above.
 *
 * So: instructions first, then background material, then the dialogue so
 * far, and the user's actual question last — right where the model's own
 * turn begins.
 */
private fun FragmentSource.renderOrder(): Int = when (this) {
    FragmentSource.SYSTEM -> 0
    FragmentSource.KNOWLEDGE -> 10
    FragmentSource.SEMANTIC_MEMORY -> 20
    FragmentSource.EPISODIC_MEMORY -> 30
    FragmentSource.CONVERSATION -> 40
    FragmentSource.TOOL_RESULT -> 50
    // Material for *this* turn, so it sits with the question rather than
    // with the background above: what the attached image shows, then what
    // was said out loud, then what was typed. With voice input the
    // transcript is the question, which is why it lands this close to the end.
    FragmentSource.VISION -> 60
    FragmentSource.TRANSCRIPT -> 70
    FragmentSource.USER_MESSAGE -> 80
}
