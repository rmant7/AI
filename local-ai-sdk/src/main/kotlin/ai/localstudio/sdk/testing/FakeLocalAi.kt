package ai.localstudio.sdk.testing

import ai.localstudio.sdk.CheckResult
import ai.localstudio.sdk.GenerationOptions
import ai.localstudio.sdk.InstallProgress
import ai.localstudio.sdk.LocalAi
import ai.localstudio.sdk.LocalAiException
import ai.localstudio.sdk.LocalAiInput
import ai.localstudio.sdk.LocalCapability
import ai.localstudio.sdk.LocalModel
import ai.localstudio.sdk.LocalModelDiscovery
import ai.localstudio.sdk.ModelCandidate
import ai.localstudio.sdk.TranslationRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf

/**
 * A [LocalAi] with no models behind it, for a caller's own tests: answers
 * come from [answer], and it keeps the contract a real implementation
 * keeps -- an unknown model id, a capability no model has, and images to a
 * model that cannot see all fail the same way they would on a device.
 */
class FakeLocalAi(
    private val installed: List<LocalModel>,
    private val chatModelId: String? = installed.firstOrNull { LocalCapability.TEXT in it.capabilities }?.id,
    private val translationModelId: String? = installed.firstOrNull { LocalCapability.TRANSLATION in it.capabilities }?.id,
    private val candidates: List<ModelCandidate> = emptyList(),
    private val answer: (model: LocalModel, input: LocalAiInput) -> String = { _, input -> "echo: ${input.text}" },
) : LocalAi {

    /** Every input [generate] accepted, in order. */
    val received = mutableListOf<Pair<String, LocalAiInput>>()

    override suspend fun models(): List<LocalModel> = installed

    override fun generate(input: LocalAiInput, options: GenerationOptions, modelId: String?): Flow<String> = flow {
        val model = resolve(modelId ?: chatModelId, LocalCapability.TEXT)
        if (input.images.isNotEmpty() && LocalCapability.VISION !in model.capabilities) {
            throw LocalAiException.ImageNotSeen("${model.id} has no vision")
        }
        received += model.id to input
        emit(answer(model, input))
    }

    override suspend fun translate(request: TranslationRequest, modelId: String?): String {
        val model = resolve(modelId ?: translationModelId, LocalCapability.TRANSLATION)
        val input = LocalAiInput("${request.source.code}->${request.target.code}: ${request.text}")
        received += model.id to input
        return answer(model, input)
    }

    override suspend fun verify(modelId: String): Map<LocalCapability, CheckResult> = resolveAny(modelId).verified

    override val discovery: LocalModelDiscovery = object : LocalModelDiscovery {
        override suspend fun candidates(): List<ModelCandidate> = candidates

        override fun install(candidateId: String): Flow<InstallProgress> {
            val candidate = candidates.firstOrNull { it.id == candidateId }
                ?: return flowOf(InstallProgress.Failed("no candidate \"$candidateId\""))
            return flowOf(
                InstallProgress.Downloading(candidate.sizeBytes, candidate.sizeBytes),
                InstallProgress.Checking,
                InstallProgress.Done(candidate.verified),
            )
        }

        override suspend fun verify(candidateId: String): Map<LocalCapability, CheckResult> =
            candidates.firstOrNull { it.id == candidateId }?.verified ?: throw LocalAiException.UnknownModel(candidateId)
    }

    private fun resolve(id: String?, capability: LocalCapability): LocalModel {
        if (id == null) throw LocalAiException.NoModel(capability)
        return resolveAny(id)
    }

    private fun resolveAny(id: String): LocalModel = installed.firstOrNull { it.id == id } ?: throw LocalAiException.UnknownModel(id)
}
