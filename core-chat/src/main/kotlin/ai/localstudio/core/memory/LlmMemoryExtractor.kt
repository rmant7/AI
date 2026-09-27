package ai.localstudio.core.memory

import ai.localstudio.core.runtime.ANSWERED_BY_LABEL
import ai.localstudio.memory.FileMemoryStore
import ai.localstudio.memory.MemoryExtractor
import ai.localstudio.memory.MemoryItem
import ai.localstudio.memory.MemoryScope

/**
 * The model-backed half of consolidation the `:memory` module deliberately
 * leaves out (see that module's own top-level doc comment): asks a text
 * model to pick out what from a finished exchange is actually worth
 * remembering, rather than [ai.localstudio.memory.FileMemoryStore.PromoteWorkingMemory]'s
 * "keep everything verbatim" default.
 *
 * Takes a plain `suspend (String) -> String` rather than a [ai.localstudio.core.engine.ModelSelector]/
 * [ai.localstudio.core.runtime.RuntimeManager] pair directly: the caller
 * already has a fully-configured [ai.localstudio.core.engine.Orchestrator]
 * (local-vs-cloud selection, fallback chains, cooldowns, RAM-aware
 * eviction — all of it), and routing this exact same way is simpler and
 * more correct than re-deriving "which model should answer this" from
 * scratch a second time just for extraction.
 *
 * Deliberately simple output contract — one remembered fact per line, or a
 * single "НЕТ" line for nothing — rather than JSON: this app's smaller local
 * models (see the whole vision-mmproj-llama branch's history with this
 * exact kind of model's chat template) are not reliable enough at
 * structured output to build a real feature on it failing to parse. A
 * line-based format degrades gracefully — a model that ignores the
 * instruction and rambles just produces a few over-long "facts" instead of
 * a hard parse failure.
 *
 * Failure of any kind (no model available, the call throws, nothing came
 * back) returns an empty list rather than propagating: consolidation is a
 * background nicety, not something that should ever fail a turn.
 */
class LlmMemoryExtractor(
    private val log: (String) -> Unit = {},
    private val generate: suspend (prompt: String) -> String,
) : MemoryExtractor {

    override suspend fun extract(conversationId: String, workingMemory: List<MemoryItem>): List<MemoryItem> {
        if (workingMemory.isEmpty()) return emptyList()

        val transcript = workingMemory.joinToString("\n") { it.text }
        val response = runCatching { generate(PROMPT_PREFIX + transcript) }
            .onFailure { log("conversation=$conversationId extraction call failed: ${it.message}") }
            .getOrDefault("")

        val facts = response.lineSequence()
            .map { it.trim() }
            // Checked against the raw, still-un-stripped line: a bare "-"
            // or "*" bullet-marker strip below has no space requirement of
            // its own reason to stop at, so it was eating one character off
            // a plain "---" attribution separator too, turning "---" into
            // "--" and slipping it past this exact check one line down.
            .filter { it.isNotBlank() && !ATTRIBUTION_LINE.matches(it) }
            .map { it.replaceFirst(BULLET_MARKER, "").replace(LIST_MARKER, "").trim() }
            .filter { it.isNotBlank() && !it.equals(NOTHING_MARKER, ignoreCase = true) }
            .map { fact ->
                MemoryItem(
                    id = "",
                    text = fact,
                    scope = MemoryScope.EPISODIC,
                    createdAt = 0L,
                    // Lets a deleted conversation's own consolidated memory be
                    // found and forgotten too (see AppContainer.forgetConversationMemory) —
                    // without this, deleting a chat from history left whatever
                    // it had already been distilled into permanently behind.
                    metadata = mapOf(FileMemoryStore.CONVERSATION_KEY to conversationId),
                )
            }
            .toList()

        // The raw response, not just its length: a length alone was already
        // ambiguous once (a legitimate "НЕТ" + attribution footer looks the
        // same length-wise as plenty of other short-but-wrong outputs), and
        // guessing the model's actual words after the fact wastes a whole
        // round trip that logging them directly avoids.
        val preview = response.replace("\n", "\\n").take(300)
        log("conversation=$conversationId working=${workingMemory.size} facts=${facts.size} response=\"$preview\"")
        return facts
    }

    private companion object {
        const val NOTHING_MARKER = "нет"
        val LIST_MARKER = Regex("^\\d+[.)]\\s*")
        // Requires a space after the marker, unlike a bare removePrefix("-")
        // — otherwise this eats one character off a plain "---" separator
        // line too, which is exactly what let one slip past ATTRIBUTION_LINE.
        val BULLET_MARKER = Regex("^[-*]\\s+")

        // Every answer through the fallback chain carries a trailing
        // "Answer from: <label>" line (FallbackTextModel.attributionFooter) —
        // real, useful context for a chat bubble, and not itself a fact
        // worth remembering.
        val ATTRIBUTION_LINE = Regex("^(-{3,}|⚠.*|${Regex.escape(ANSWERED_BY_LABEL)}.*)$")

        val PROMPT_PREFIX = """
            Ниже — реплики одного разговора. Выпиши, по одной на строке, то,
            что стоит запомнить для будущих разговоров: факты, решения,
            предпочтения пользователя.

            Дополнительно, обязательно, даже если пользователь просто о
            чём-то спросил или что-то попросил (например, рецепты,
            инструкцию, список, объяснение) и получил обычный ответ по
            существу — выпиши отдельной строкой краткую суть: что именно
            просили и что было предложено (например: "Пользователь просил
            рецепты низкокалорийных десертов; было предложено: желе,
            фруктовый салат, запечённые яблоки"). Это нужно, чтобы в новом
            разговоре не переспрашивать то же самое и не повторяться — не
            пропускай эту строку просто потому, что запрос выглядит
            рутинным или не содержит новых "фактов".

            Не придумывай ничего, чего не было сказано, и не пересказывай
            длинные ответы дословно — только суть в одной строке на пункт.
            Выведи ровно одну строку "НЕТ" только если разговор был чистым
            приветствием/благодарностью/светской репликой без единого
            реального вопроса или просьбы.

            Реплики:

        """.trimIndent() + "\n"
    }
}
