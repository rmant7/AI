package ai.localstudio.core.context

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContextEngineTest {

    private val engine = ContextEngine()

    private fun fragment(source: FragmentSource, text: String, relevance: Double = 0.0) =
        ContextFragment(source = source, text = text, relevance = relevance)

    @Test
    fun `instructions come first and the question comes last, whatever order they arrive in`() {
        val assembled = engine.assemble(
            listOf(
                fragment(FragmentSource.KNOWLEDGE, "doc"),
                fragment(FragmentSource.USER_MESSAGE, "question"),
                fragment(FragmentSource.SYSTEM, "rules"),
                fragment(FragmentSource.EPISODIC_MEMORY, "last week"),
            ),
            contextWindowTokens = 4096,
        )

        assertEquals(
            listOf(
                FragmentSource.SYSTEM,
                FragmentSource.KNOWLEDGE,
                FragmentSource.EPISODIC_MEMORY,
                FragmentSource.USER_MESSAGE,
            ),
            assembled.fragments.map { it.source },
        )
    }

    @Test
    fun `recalled memory never gets to be the last thing the model reads`() {
        // The real failure this ordering exists to prevent: a chat about
        // desserts, asked to elaborate, answered "о черной дыре." — the
        // prompt had ended on a recalled memory fragment, so that is what
        // the model continued. The question must be what the prompt ends on.
        val rendered = engine.assemble(
            listOf(
                fragment(FragmentSource.SEMANTIC_MEMORY, "чёрные дыры искривляют пространство"),
                fragment(FragmentSource.CONVERSATION, "Вы: расскажи про десерты"),
                fragment(FragmentSource.USER_MESSAGE, "Подробнее"),
                fragment(FragmentSource.SYSTEM, "Ты локальный ассистент."),
            ),
            contextWindowTokens = 4096,
        ).render()

        assertTrue(
            rendered.endsWith("\n\nПодробнее"),
            "the prompt must end on the question, not on background material — got:\n$rendered",
        )
        assertTrue(rendered.startsWith("## Инструкции\n"), "instructions belong at the top — got:\n$rendered")
        assertTrue(
            !rendered.contains("[USER_MESSAGE]") && !rendered.contains("[SYSTEM]"),
            "bracketed section markers are what the model was mimicking — got:\n$rendered",
        )
    }

    @Test
    fun `equal priority is broken by retrieval relevance`() {
        val assembled = engine.assemble(
            listOf(
                fragment(FragmentSource.KNOWLEDGE, "less relevant", relevance = 0.2),
                fragment(FragmentSource.KNOWLEDGE, "most relevant", relevance = 0.9),
            ),
            contextWindowTokens = 4096,
        )

        assertEquals(
            listOf("most relevant", "less relevant"),
            assembled.fragments.map { it.text },
        )
    }

    @Test
    fun `the budget reserves room for the answer`() {
        val assembled = engine.assemble(listOf(fragment(FragmentSource.SYSTEM, "x")), contextWindowTokens = 1000)

        assertEquals(750, assembled.budgetTokens)
    }

    @Test
    fun `cyrillic text is not undercounted the way a flat 4-chars-per-token rule would`() {
        // Reproduces the failure this heuristic used to invite: a Russian
        // conversation that "fit" the estimated budget while the model's
        // real tokenizer still overflowed the actual context window, which
        // surfaced on-device as generation silently refusing to run.
        val cyrillic = "а".repeat(400)
        val ascii = "a".repeat(400)

        assertTrue(
            HeuristicTokenCounter.count(cyrillic) > HeuristicTokenCounter.count(ascii),
            "Cyrillic text of the same length must not be estimated as cheaper than ASCII",
        )
    }

    @Test
    fun `low-priority fragments are dropped, not silently lost`() {
        val big = "a".repeat(4 * 600) // ~600 tokens with the heuristic counter
        val assembled = engine.assemble(
            listOf(
                fragment(FragmentSource.SYSTEM, big),
                fragment(FragmentSource.USER_MESSAGE, big),
                fragment(FragmentSource.KNOWLEDGE, big),
            ),
            contextWindowTokens = 2000, // 1500 usable
        )

        assertEquals(
            listOf(FragmentSource.SYSTEM, FragmentSource.USER_MESSAGE),
            assembled.fragments.map { it.source },
        )
        assertEquals(listOf(FragmentSource.KNOWLEDGE), assembled.dropped.map { it.fragment.source })
        assertTrue(assembled.usedTokens <= assembled.budgetTokens)
    }

    @Test
    fun `a smaller fragment still fits after a larger one is dropped`() {
        val huge = "a".repeat(4 * 900)
        val small = "a".repeat(4 * 100)
        val assembled = engine.assemble(
            listOf(
                fragment(FragmentSource.SYSTEM, small),
                fragment(FragmentSource.KNOWLEDGE, huge),
                fragment(FragmentSource.EPISODIC_MEMORY, small),
            ),
            contextWindowTokens = 1200, // 900 usable
        )

        assertEquals(
            listOf(FragmentSource.SYSTEM, FragmentSource.EPISODIC_MEMORY),
            assembled.fragments.map { it.source },
        )
    }

    @Test
    fun `render labels every section`() {
        val rendered = engine.assemble(
            listOf(
                ContextFragment(FragmentSource.TRANSCRIPT, "найди модели", label = "VOICE"),
                fragment(FragmentSource.SYSTEM, "rules"),
            ),
            contextWindowTokens = 4096,
        ).render()

        // An explicit label still wins over the source's own heading; the
        // system section keeps a heading, and both stay bracket-free.
        assertEquals("## Инструкции\nrules\n\n## VOICE\nнайди модели", rendered)
    }

    @Test
    fun `an explicit priority overrides the source default`() {
        // Priority decides what survives a tight budget, not what order the
        // survivors are read in — so this asserts on what was kept, not on
        // position. Budget is 900 tokens and each fragment is ~600, so only
        // the higher-priority one fits, default ranking notwithstanding.
        val big = "a".repeat(4 * 600)
        val assembled = engine.assemble(
            listOf(
                fragment(FragmentSource.SYSTEM, big),
                ContextFragment(FragmentSource.KNOWLEDGE, big, priority = 200),
            ),
            contextWindowTokens = 1200,
        )

        assertEquals(listOf(FragmentSource.KNOWLEDGE), assembled.fragments.map { it.source })
        assertEquals(listOf(FragmentSource.SYSTEM), assembled.dropped.map { it.fragment.source })
    }
}
