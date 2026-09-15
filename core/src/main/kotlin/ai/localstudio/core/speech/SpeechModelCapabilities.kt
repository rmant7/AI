package ai.localstudio.core.speech

/**
 * Which runtime/packaging a model uses — informational and diagnostic only
 * (shown in logs/benchmarks, e.g. `[RU][vosk-ru]`). The router never
 * switches on this: two [AsrEngineType.VOSK] entries and an
 * [AsrEngineType.ONNX] one are indistinguishable to routing, which only
 * ever reads [SpeechModelCapabilities]'s other fields. Adding
 * [AsrEngineType.ONNX]/[AsrEngineType.GGUF]/[AsrEngineType.REMOTE] entries
 * up front, unused today, is what lets a future Nemotron/Parakeet/Canary
 * backend register without extending this enum's *meaning* — only its
 * membership, and even that only for diagnostics.
 */
enum class AsrEngineType {
    WHISPER,
    VOSK,
    ONNX,
    GGUF,
    REMOTE,
    OTHER,
}

/**
 * What a registered ASR model can actually do — the entire vocabulary the
 * router is allowed to make decisions from. [languages] empty means
 * "no fixed language" (a genuinely multilingual/auto-detecting model, e.g.
 * a Whisper fallback) rather than "no languages at all" — see
 * [SpeechModelRegistry.findCandidates]'s own matching rule.
 *
 * [supportsCodeSwitching] must only be true once actually verified for that
 * model — it is a specific, testable claim ("this model stays coherent
 * when the speaker switches languages mid-utterance"), not a guess from a
 * spec sheet or a marketing page. Claiming it for a model that hasn't been
 * checked would silently break the one case ([RoutingPolicy]'s mixed-
 * language fallback) this whole field exists to route correctly.
 */
data class SpeechModelCapabilities(
    val languages: Set<Language>,
    val supportsStreaming: Boolean,
    val supportsFileTranscription: Boolean,
    val supportsLanguageAutoDetection: Boolean,
    val supportsCodeSwitching: Boolean,
    val engine: AsrEngineType,
)

/**
 * Static routing metadata for one registered model. Not the model itself —
 * see [RegisteredSpeechModel] for how this pairs with the actual, lazily-
 * loaded [ai.localstudio.core.runtime.SpeechModelHandle]. [priority] breaks
 * ties between several candidates that all satisfy a
 * [SpeechModelRequirements] query (higher wins) — e.g. preferring a
 * specialist over a multilingual fallback that also happens to list the
 * same language among its (empty-set, "any") capabilities is handled by
 * [SpeechModelRegistry.findCandidates] itself, not by priority; priority is
 * for breaking ties *within* a tier, such as two RU specialists of
 * differing quality.
 */
data class SpeechModelInfo(
    val id: String,
    val displayName: String,
    val capabilities: SpeechModelCapabilities,
    val priority: Int = 0,
) {
    init {
        require(id.isNotBlank()) { "SpeechModelInfo.id must not be blank" }
    }
}
