package ai.localstudio.core.speech

import ai.localstudio.core.runtime.SpeechModelHandle

/**
 * One entry the registry routes to: routing metadata ([info]) plus a lazy
 * way to obtain the actual [SpeechModelHandle] that does the transcribing.
 *
 * Deliberately a separate type from [SpeechModelHandle] rather than fields
 * bolted onto it — folding [SpeechModelInfo] into that interface would
 * force every existing implementor (`WhisperCppSpeechModel`, `StubRuntime`,
 * `OpenAiRuntime`'s speech path) to carry ASR-routing-specific fields
 * (languages, code-switching) they have no reason to know about, and that
 * predate this router entirely. This is the actual seam: adding Nemotron,
 * Parakeet, Canary, a new ONNX/GGUF/remote engine tomorrow means
 * implementing this one interface and registering it —
 * [SpeechModelHandle], [SpeechModelRegistry] and the router itself never
 * change.
 */
interface RegisteredSpeechModel {
    val info: SpeechModelInfo

    /**
     * Obtains the loaded handle, loading it first on the first real call.
     * [SpeechModelRegistry] never loads a model itself — every entry owns
     * its own lazy load/reuse, the same way
     * [ai.localstudio.app.whisper.WhisperCppMicSession] and
     * [ai.localstudio.app.vosk.VoskSpeechRecognizer] already load their
     * model on first use rather than at construction. This is what keeps
     * "register three specialists and a fallback" from meaning "four
     * models resident in RAM at once" — see docs/13-asr-pipeline-
     * migration.md and docs/14-vosk-spike.md for why that already mattered
     * before a router existed at all.
     */
    suspend fun handle(): SpeechModelHandle
}

/**
 * What the router is asking for beyond "supports this language" — kept
 * separate from [Language] itself so a query can express "any RU-capable
 * streaming model" without conflating capability requirements with the
 * language match.
 */
data class SpeechModelRequirements(
    val requireStreaming: Boolean = true,
    val requireFileTranscription: Boolean = false,
    val requireCodeSwitching: Boolean = false,
)

interface SpeechModelRegistry {
    fun register(model: RegisteredSpeechModel)
    fun unregister(modelId: String)
    fun get(modelId: String): RegisteredSpeechModel?
    fun all(): List<RegisteredSpeechModel>

    /**
     * Every registered model that could serve [language] under
     * [requirements], specialists (a non-empty, matching
     * [SpeechModelCapabilities.languages]) ranked ahead of generalists (an
     * empty set — "any language", a multilingual fallback's own shape)
     * regardless of priority number, then by [SpeechModelInfo.priority]
     * descending within each tier. The caller (the router) tries
     * candidates in order and falls through on failure — see
     * [StreamingSpeechRouter]'s own fallback handling for why this returns
     * a ranked list rather than one "best" answer.
     */
    fun findCandidates(language: Language, requirements: SpeechModelRequirements = SpeechModelRequirements()): List<RegisteredSpeechModel>
}

/** In-process registry — everything registered lives only for this app run; nothing here is persisted. A JSON-backed one (see docs/14 configuration note) can implement the same interface later without the router changing. */
class InMemorySpeechModelRegistry : SpeechModelRegistry {
    private val models = linkedMapOf<String, RegisteredSpeechModel>()

    @Synchronized
    override fun register(model: RegisteredSpeechModel) {
        models[model.info.id] = model
    }

    @Synchronized
    override fun unregister(modelId: String) {
        models.remove(modelId)
    }

    @Synchronized
    override fun get(modelId: String): RegisteredSpeechModel? = models[modelId]

    @Synchronized
    override fun all(): List<RegisteredSpeechModel> = models.values.toList()

    @Synchronized
    override fun findCandidates(language: Language, requirements: SpeechModelRequirements): List<RegisteredSpeechModel> =
        models.values
            .filter { entry -> matches(entry.info.capabilities, language, requirements) }
            .sortedWith(
                compareByDescending<RegisteredSpeechModel> { it.info.capabilities.languages.isNotEmpty() }
                    .thenByDescending { it.info.priority },
            )

    private fun matches(caps: SpeechModelCapabilities, language: Language, requirements: SpeechModelRequirements): Boolean {
        val languageOk = caps.languages.isEmpty() || language in caps.languages
        return languageOk &&
            (!requirements.requireStreaming || caps.supportsStreaming) &&
            (!requirements.requireFileTranscription || caps.supportsFileTranscription) &&
            (!requirements.requireCodeSwitching || caps.supportsCodeSwitching)
    }
}
