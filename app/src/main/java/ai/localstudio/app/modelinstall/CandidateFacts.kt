package ai.localstudio.app.modelinstall

/** What a candidate's own Hugging Face tags say it is for -- nothing inferred beyond them. */
enum class CandidatePurpose(internal val tags: Set<String>) {
    CHAT(setOf("conversational", "chat", "instruct", "assistant")),
    CODE(setOf("code", "coding", "coder")),
    MATH(setOf("math", "mathematics")),
    REASONING(setOf("reasoning", "thinking", "chain-of-thought", "cot")),
    TRANSLATION(setOf("translation", "machine-translation")),
    TOOLS(setOf("tool-use", "function-calling", "tool-calling", "agent", "agentic")),
    ROLEPLAY(setOf("roleplay", "rp", "creative-writing", "storytelling")),
    UNCENSORED(setOf("uncensored", "abliterated", "heretic")),
    VISION(setOf("image-text-to-text", "vision", "multimodal")),
    MEDICAL(setOf("medical", "biology", "clinical")),
}

/**
 * The parts of a repository's tag list worth showing a person deciding
 * whether to test a candidate: what it is for, what it was built from,
 * which languages and license it declares. Hugging Face tags are free-form
 * and author-written; this only sorts what is there.
 */
data class CandidateFacts(
    val purposes: List<CandidatePurpose>,
    val baseModels: List<String>,
    val languages: List<String>,
    val license: String?,
) {
    companion object {
        private val LANGUAGE = Regex("^[a-z]{2}$")

        fun of(tags: List<String>): CandidateFacts {
            val lower = tags.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            val plain = lower.toSet()
            return CandidateFacts(
                purposes = CandidatePurpose.entries.filter { purpose -> purpose.tags.any { it in plain } },
                // "base_model:X", "base_model:quantized:X", "base_model:finetune:X" -- repo ids never contain ':'.
                baseModels = tags.filter { it.startsWith("base_model:") }
                    .map { it.substringAfterLast(':') }
                    .filter { '/' in it }
                    .distinct(),
                languages = lower.filter { tag -> LANGUAGE.matches(tag) && CandidatePurpose.entries.none { tag in it.tags } }.distinct(),
                license = tags.firstOrNull { it.startsWith("license:") }?.substringAfter(':'),
            )
        }
    }
}
