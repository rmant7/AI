package ai.localstudio.sdk

import kotlinx.coroutines.flow.Flow

/**
 * On-device AI for an app: the one entry point. Everything a caller needs is
 * expressed in capabilities, inputs and results -- never in runtimes, files
 * or model formats.
 *
 * A capability is offered only when the installed model has the parts for
 * it ([LocalModel.capabilities]); whether it actually works on this device
 * is a separate, recorded fact ([LocalModel.verified]). A caller that wants
 * only proven behaviour filters on the latter.
 */
interface LocalAi {
    /** The models installed on this device, each with what it can do and what this device proved. */
    suspend fun models(): List<LocalModel>

    /**
     * Streams the answer to [input] from [modelId], or from the model the
     * user chose for chat when null. Images need a model with
     * [LocalCapability.VISION]: one without it fails with
     * [LocalAiException.ImageNotSeen], never with an answer that pretends it
     * saw them.
     */
    fun generate(input: LocalAiInput, options: GenerationOptions = GenerationOptions(), modelId: String? = null): Flow<String>

    /** Translates with [modelId], or the model the user chose for translation when null; the answer only, no reasoning. */
    suspend fun translate(request: TranslationRequest, modelId: String? = null): String

    /** New models on the Hub and what this device made of them. */
    val discovery: LocalModelDiscovery
}

/**
 * Finding, installing and checking models this app does not ship with.
 * Installing never makes a model available for use by itself: a candidate
 * becomes one of [LocalAi.models] only after a check on this device passed.
 */
interface LocalModelDiscovery {
    /** What the last search found, with what this device already proved about each. */
    suspend fun candidates(): List<ModelCandidate>

    /** Downloads [candidateId] (all of its files) and then checks it on this device; progress until done. */
    fun install(candidateId: String): Flow<InstallProgress>

    /** Checks an installed [candidateId] on this device and returns what was observed. */
    suspend fun verify(candidateId: String): Map<LocalCapability, CheckResult>
}
