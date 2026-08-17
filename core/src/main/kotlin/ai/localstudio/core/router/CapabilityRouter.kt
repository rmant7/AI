package ai.localstudio.core.router

import ai.localstudio.core.capability.Capability
import ai.localstudio.core.pipeline.NodeType

data class RequestSignals(
    val text: String? = null,
    val hasAudio: Boolean = false,
    val hasImage: Boolean = false,
    val hasVideo: Boolean = false,
    val hasDocumentContext: Boolean = false,
    val memoryEnabled: Boolean = true,
    val knowledgeEnabled: Boolean = true,
)

data class RoutePlan(
    val capabilities: List<Capability>,
    val stages: List<NodeType>,
    val explanation: List<String>,
)

/**
 * Turns a request into the capabilities needed to answer it.
 *
 * Rule-based on purpose: it is deterministic, costs nothing, and is auditable
 * through [RoutePlan.explanation]. A small local classifier can replace the
 * keyword rules later without changing this interface — the rest of the system
 * only ever sees a [RoutePlan].
 */
class CapabilityRouter(
    private val codingKeywords: Set<String> = DEFAULT_CODING_KEYWORDS,
    private val translationKeywords: Set<String> = DEFAULT_TRANSLATION_KEYWORDS,
    private val reasoningKeywords: Set<String> = DEFAULT_REASONING_KEYWORDS,
    private val recallKeywords: Set<String> = DEFAULT_RECALL_KEYWORDS,
) {
    fun route(signals: RequestSignals): RoutePlan {
        val capabilities = LinkedHashSet<Capability>()
        val stages = mutableListOf<NodeType>()
        val why = mutableListOf<String>()
        val text = signals.text?.lowercase().orEmpty()

        if (signals.hasVideo) {
            capabilities += listOf(
                Capability.SPEECH_TO_TEXT,
                Capability.VIDEO_UNDERSTANDING,
                Capability.OCR,
            )
            stages += listOf(NodeType.FRAME_EXTRACT, NodeType.SPEECH_TO_TEXT, NodeType.VISION_ANALYZE)
            why += "video input: frames and audio are analysed separately, then fused"
        }

        if (signals.hasAudio) {
            capabilities += Capability.SPEECH_TO_TEXT
            if (NodeType.SPEECH_TO_TEXT !in stages) stages += NodeType.SPEECH_TO_TEXT
            why += "audio input: transcription first"
        }

        if (signals.hasImage) {
            capabilities += listOf(Capability.VISION, Capability.IMAGE_UNDERSTANDING)
            if (NodeType.VISION_ANALYZE !in stages) stages += NodeType.VISION_ANALYZE
            why += "image input: vision analysis before generation"
        }

        if (signals.memoryEnabled) {
            // Unconditional, not gated on a recall keyword: attached documents
            // live in semantic memory (see AppContainer.rememberDocument), and
            // gating this on "напомни"/"вчера" meant a plain "what's in the
            // files I attached?" never searched memory at all — the model
            // denied having any files. The search itself is still lexical
            // (InMemoryMemoryProvider.search), so an unrelated query still
            // returns nothing; this only removes the keyword as a second gate
            // on top of that.
            stages += NodeType.MEMORY_SEARCH
            why += if (recallKeywords.any { it in text }) {
                "request refers to earlier work: memory retrieval"
            } else {
                "memory retrieval: surface anything relevant already known"
            }
        }

        if (signals.knowledgeEnabled && signals.hasDocumentContext) {
            capabilities += listOf(Capability.EMBEDDING, Capability.RERANKING)
            stages += NodeType.KNOWLEDGE_SEARCH
            why += "documents in scope: knowledge retrieval"
        }

        when {
            codingKeywords.any { it in text } -> {
                capabilities += Capability.CODING
                why += "coding request"
            }

            translationKeywords.any { it in text } -> {
                capabilities += Capability.TRANSLATION
                why += "translation request"
            }

            reasoningKeywords.any { it in text } -> {
                capabilities += Capability.REASONING
                why += "analytical request"
            }
        }

        capabilities += Capability.TEXT_GENERATION
        stages += listOf(NodeType.CONTEXT_BUILD, NodeType.TEXT_GENERATION, NodeType.RESPONSE)

        return RoutePlan(capabilities.toList(), stages, why)
    }

    companion object {
        val DEFAULT_CODING_KEYWORDS = setOf(
            "код", "функци", "багу", "баг", "скрипт", "приложение", "рефактор", "стектрейс",
            "code", "function", "bug", "refactor", "stacktrace", "compile",
        )
        val DEFAULT_TRANSLATION_KEYWORDS = setOf(
            "переведи", "перевод", "translate", "translation",
        )
        val DEFAULT_REASONING_KEYWORDS = setOf(
            "проанализируй", "сравни", "почему", "объясни", "спланируй",
            "analyse", "analyze", "compare", "why", "explain", "plan",
        )
        val DEFAULT_RECALL_KEYWORDS = setOf(
            "вчера", "ранее", "продолжи", "мы решили", "мы обсуждали", "напомни",
            "yesterday", "earlier", "continue", "we decided", "we discussed", "remind",
        )
    }
}
