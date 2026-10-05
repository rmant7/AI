package ai.localstudio.app.localai

import ai.localstudio.app.modelinstall.DiscoveredCandidate
import ai.localstudio.core.model.ImageRef
import ai.localstudio.core.registry.RuntimeBinding
import ai.localstudio.core.runtime.GenerationRequest
import ai.localstudio.model.install.CheckStatus
import ai.localstudio.model.install.DeviceVerification
import ai.localstudio.model.install.VerifiedCapability
import ai.localstudio.sdk.CheckResult
import ai.localstudio.sdk.GenerationOptions
import ai.localstudio.sdk.LocalAiInput
import ai.localstudio.sdk.LocalCapability
import ai.localstudio.sdk.LocalImage
import ai.localstudio.sdk.ModelCandidate

/**
 * Between the SDK's types ([ai.localstudio.sdk]) and this app's own: the
 * only place that knows both, so neither side leaks into the other.
 */
object SdkMapping {

    /** The runtime's form of an image: a `data:` URI (see [ImageRef]). */
    fun imageRef(image: LocalImage): ImageRef =
        ImageRef("data:${image.mimeType};base64," + java.util.Base64.getEncoder().encodeToString(image.bytes))

    fun request(input: LocalAiInput, options: GenerationOptions, repeatPenalty: Double = GenerationRequest(prompt = "").repeatPenalty): GenerationRequest =
        GenerationRequest(
            prompt = input.text,
            systemPrompt = input.systemPrompt,
            maxTokens = options.maxTokens,
            temperature = options.temperature,
            repeatPenalty = repeatPenalty,
            images = input.images.map(::imageRef),
        )

    /** Whether the installed model behind [binding] can take [input]: images need its projector on disk, whatever the model is called. */
    fun canTake(binding: RuntimeBinding, input: LocalAiInput): Boolean = input.images.isEmpty() || binding.mmprojArtifact != null

    /** What an installed model's files allow: any local LLM takes text and can be asked to translate; seeing needs its projector. */
    fun capabilities(binding: RuntimeBinding): Set<LocalCapability> =
        setOf(LocalCapability.TEXT, LocalCapability.TRANSLATION) + if (binding.mmprojArtifact != null) setOf(LocalCapability.VISION) else emptySet()

    fun capability(verified: String): LocalCapability? = when (verified) {
        VerifiedCapability.TEXT -> LocalCapability.TEXT
        VerifiedCapability.TRANSLATION -> LocalCapability.TRANSLATION
        VerifiedCapability.VISION -> LocalCapability.VISION
        else -> null
    }

    /** What a device check observed, as the SDK reports it; nothing from a check older than the current one counts. */
    fun checks(verification: DeviceVerification?): Map<LocalCapability, CheckResult> {
        if (verification == null || verification.checkVersion < DeviceVerification.CURRENT_CHECK) return emptyMap()
        return VerifiedCapability.ALL.mapNotNull { cap ->
            val result = when (verification.status(cap)) {
                CheckStatus.PASS -> CheckResult.PASS
                CheckStatus.FAIL -> CheckResult.FAIL
                CheckStatus.NOT_TESTED -> CheckResult.NOT_TESTED
            }
            capability(cap)?.let { it to result }
        }.toMap()
    }

    fun candidate(candidate: DiscoveredCandidate, installed: Boolean): ModelCandidate = ModelCandidate(
        id = candidate.identity,
        repository = candidate.repoId,
        sizeBytes = candidate.totalBytes,
        capabilities = setOf(LocalCapability.TEXT, LocalCapability.TRANSLATION) +
            if (candidate.artifact().canCheck(VerifiedCapability.VISION)) setOf(LocalCapability.VISION) else emptySet(),
        installed = installed,
        verified = checks(candidate.verification),
        notes = candidate.notes,
    )
}
