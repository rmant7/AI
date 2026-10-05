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
    /** Every model installed on this device, checked or not: each with what it can be asked and what a check here says now. */
    suspend fun models(): List<LocalModel>

    /** [models] narrowed by [query] -- e.g. ModelQuery.proven(VISION) for what a feature can rely on. */
    suspend fun models(query: ModelQuery): List<LocalModel> = models().filter(query::matches)

    /**
     * Streams the answer to [input] from [modelId], or from the model the
     * user chose for chat when null. Images need a model with
     * [LocalCapability.VISION]: one without it fails with
     * [LocalAiException.ImageNotSeen], never with an answer that pretends it
     * saw them. Ends with [LocalAiException.Timeout] past
     * [GenerationOptions.timeoutMs].
     */
    fun generate(input: LocalAiInput, options: GenerationOptions = GenerationOptions(), modelId: String? = null): Flow<String>

    /** Translates with [modelId], or the model the user chose for translation when null; the answer only, no reasoning. */
    suspend fun translate(request: TranslationRequest, modelId: String? = null): String

    /**
     * Checks the installed [modelId] on this device now -- the same questions
     * a discovered model is asked -- and returns what was observed; also
     * what refreshes a STALE result. Afterwards [models] reports the same.
     * One check runs at a time: this waits its turn.
     */
    suspend fun verify(modelId: String): Map<LocalCapability, CheckResult>

    /** New models on the Hub and what this device made of them. */
    val discovery: LocalModelDiscovery
}

/**
 * Finding, installing and checking models this app does not ship with.
 * Discovery answers where a model's bytes come from; a check answers what
 * those bytes do on this device. Installing and checking are separate
 * steps: [install] is the convenience that runs both.
 */
interface LocalModelDiscovery {
    /** What the last search found, with what a check on this device says about each now. */
    suspend fun candidates(): List<ModelCandidate>

    /** [candidates] narrowed by [query], with the same meaning as for installed models. */
    suspend fun candidates(query: ModelQuery): List<ModelCandidate> = candidates().filter(query::matches)

    /** Downloads [candidateId] (all of its files) unless already installed, then checks it on this device; progress until done. */
    fun install(candidateId: String): Flow<InstallProgress>

    /**
     * Checks an installed [candidateId] on this device now and returns what
     * was observed -- also what refreshes a STALE result.
     */
    suspend fun verify(candidateId: String): Map<LocalCapability, CheckResult>
}
