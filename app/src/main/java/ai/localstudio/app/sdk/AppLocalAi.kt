package ai.localstudio.app.sdk

import ai.localstudio.app.AppContainer
import ai.localstudio.app.CloudProviders
import ai.localstudio.app.IsoScriptCodes
import ai.localstudio.app.localai.SdkMapping
import ai.localstudio.app.localai.TranslationPrompts
import ai.localstudio.app.modelinstall.finalAnswer
import ai.localstudio.app.models.ModelPurpose
import ai.localstudio.core.engine.SelectedModel
import ai.localstudio.core.runtime.GenerationRequest
import ai.localstudio.core.runtime.ImageNotSeenException
import ai.localstudio.core.runtime.InsufficientMemoryException
import ai.localstudio.core.runtime.OperationTimeoutException
import ai.localstudio.core.runtime.TextModelHandle
import ai.localstudio.core.runtime.withOperationTimeout
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
import ai.localstudio.sdk.ModelSource
import ai.localstudio.sdk.TranslationRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.toList
import java.io.File

/**
 * [LocalAi] backed by this app: the models the user installed, the one
 * shared llama.cpp runtime and RAM manager the Chat and Translation screens
 * use, and candidate discovery. Every choice it makes is the screens' own
 * (see [AppContainer.localModelFor]) -- a caller of the SDK gets exactly what
 * the app would do, never a second path that could drift from it.
 */
class AppLocalAi(private val container: AppContainer) : LocalAi {

    override suspend fun models(): List<LocalModel> =
        (container.installedLocalModels(ModelPurpose.CHAT) + container.installedLocalModels(ModelPurpose.TRANSLATION))
            .distinctBy { it.model.id }
            .map { selected ->
                val id = selected.model.id
                val seed = container.localSeed(id)
                val artifact = container.installedArtifact(id)
                // Checked as the files that load -- pinned or not; only a pinned id is offered as an artifact ref.
                val checked = seed?.let(container::installedCheck)
                LocalModel(
                    id = id,
                    displayName = seed?.title ?: id,
                    capabilities = capabilitiesOf(selected),
                    verified = checked?.let { (record, now) -> SdkMapping.checks(record, now) }.orEmpty(),
                    sizeBytes = selected.binding.fileSizeBytes + (selected.binding.mmprojArtifact?.let { File(it).length() } ?: 0L),
                    artifact = artifact?.let(SdkMapping::artifactRef),
                    source = sourceOf(id, artifact),
                )
            } + listOfNotNull(nano())

    /**
     * Gemini Nano, while AICore says it is ready here: part of the phone,
     * not a file of ours. Checked like any model, against AICore's version.
     */
    private suspend fun nano(): LocalModel? {
        if (!container.aicoreReady()) return null
        val checked = container.nanoCheck()
        return LocalModel(
            id = NANO,
            displayName = "Gemini Nano (AICore)",
            capabilities = setOf(LocalCapability.TEXT, LocalCapability.TRANSLATION, LocalCapability.VISION),
            verified = checked?.let { (record, now) -> SdkMapping.checks(record, now) }.orEmpty(),
            sizeBytes = 0,
            source = ModelSource.SYSTEM,
        )
    }

    /**
     * Whether a request for [purpose] goes to Gemini Nano: asked for by id;
     * or no id, and the user chose it for translation; or no id and no
     * installed model for [purpose] at all, while AICore is ready.
     */
    private suspend fun usesNano(purpose: ModelPurpose, modelId: String?): Boolean = when {
        modelId != null -> modelId == NANO
        purpose == ModelPurpose.TRANSLATION && container.settings.translationModel == CloudProviders.AICORE.id -> container.aicoreReady()
        else -> container.localModelFor(purpose, null) == null && container.aicoreReady()
    }

    private suspend fun requireNano() {
        if (!container.aicoreReady()) throw LocalAiException.Failed("Gemini Nano (AICore) is not ready on this device (status ${container.aicoreStatus.value})")
    }

    override fun generate(input: LocalAiInput, options: GenerationOptions, modelId: String?): Flow<String> = flow {
        if (usesNano(ModelPurpose.CHAT, modelId)) {
            requireNano()
            // One image per request is AICore's limit; more is refused by its runtime as ImageNotSeen.
            emitAll(answer(container::loadNano, SdkMapping.request(input, options), options))
            return@flow
        }
        val selected = container.localModelFor(ModelPurpose.CHAT, modelId)
            ?: throw if (modelId != null) LocalAiException.UnknownModel(modelId) else LocalAiException.NoModel(LocalCapability.TEXT)
        if (LocalCapability.TEXT !in capabilitiesOf(selected)) {
            throw LocalAiException.Failed("${selected.model.id} only translates; ask it through translate()")
        }
        if (!SdkMapping.canTake(selected.binding, input)) {
            throw LocalAiException.ImageNotSeen("${selected.model.id} has no vision projector installed")
        }
        emitAll(answer(selected, SdkMapping.request(input, options), options))
    }

    override suspend fun translate(request: TranslationRequest, modelId: String?): String {
        if (usesNano(ModelPurpose.TRANSLATION, modelId)) {
            requireNano()
            val prompt = TranslationPrompts.forLocalModel(
                TranslationPrompts.Format.CHAT_INSTRUCTION, request.source.name, request.target.name, request.target.code, request.text, IsoScriptCodes::of,
            )
            val reply = answer(container::loadNano, GenerationRequest(prompt = prompt, temperature = 0.0), GenerationOptions(temperature = 0.0)).toList().joinToString("")
            return reply.trim().takeIf { it.isNotEmpty() } ?: throw LocalAiException.Failed("$NANO gave no translation")
        }
        val selected = container.localModelFor(ModelPurpose.TRANSLATION, modelId)
            ?: throw if (modelId != null) LocalAiException.UnknownModel(modelId) else LocalAiException.NoModel(LocalCapability.TRANSLATION)
        val seed = container.localSeed(selected.model.id)
        val format = TranslationPrompts.Format.of(
            isT5EncoderDecoder = seed?.isT5EncoderDecoder == true,
            modelName = listOfNotNull(selected.model.id, seed?.title).joinToString(" "),
        )
        val prompt = TranslationPrompts.forLocalModel(
            format, request.source.name, request.target.name, request.target.code, request.text, IsoScriptCodes::of,
        )
        val reply = answer(selected, GenerationRequest(prompt = prompt, temperature = 0.0), GenerationOptions(temperature = 0.0)).toList().joinToString("")
        return finalAnswer(reply)?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw LocalAiException.Failed("${selected.model.id} gave no translation" + if (finalAnswer(reply) == null) " (still reasoning when the reply ended)" else "")
    }

    override suspend fun verify(modelId: String): Map<LocalCapability, CheckResult> {
        if (modelId == NANO) {
            requireNano()
            container.checkNano()
            container.candidateWork.first { AppContainer.NANO_CHECK_KEY !in it.queued && it.trial?.key != AppContainer.NANO_CHECK_KEY }
            val (record, now) = container.nanoCheck() ?: throw LocalAiException.Failed("AICore is not installed any more")
            return SdkMapping.checks(record, now)
        }
        val seed = container.localSeed(modelId) ?: throw LocalAiException.UnknownModel(modelId)
        val key = container.checkKey(seed) ?: throw LocalAiException.UnknownModel(modelId)
        container.checkInstalledModel(modelId)
        container.candidateWork.first { key !in it.queued && it.trial?.key != key }
        val (record, now) = container.installedCheck(seed) ?: throw LocalAiException.Failed("$modelId is not installed any more")
        return SdkMapping.checks(record, now)
    }

    override val discovery: LocalModelDiscovery = object : LocalModelDiscovery {
        override suspend fun candidates(): List<ModelCandidate> =
            container.discoveredCandidates().map { (_, c) ->
                SdkMapping.candidate(c, installed = container.candidateTestable(c), now = container.verificationContext(c))
            }

        override fun install(candidateId: String): Flow<InstallProgress> = flow {
            val (label, candidate) = container.discoveredCandidate(candidateId) ?: run {
                emit(InstallProgress.Failed("no candidate \"$candidateId\" in the last search"))
                return@flow
            }
            if (!container.candidateTestable(candidate)) {
                // Downloading straight into the device check is what the app itself does (see AppContainer.downloadCandidate).
                container.downloadCandidate(label, candidate)
                container.candidateWork
                    .map { it.downloads[candidate.identity] }
                    .takeWhile { it != null }
                    .collect { emit(InstallProgress.Downloading(it!!.bytesDone, it.bytesTotal)) }
                if (!container.candidateTestable(candidate)) {
                    emit(InstallProgress.Failed(container.candidateWork.value.failures[candidate.identity] ?: "not installed (paused or cancelled)"))
                    return@flow
                }
            } else {
                container.testCandidate(label, candidate)
            }
            emit(InstallProgress.Checking)
            emit(InstallProgress.Done(awaitCheck(candidateId)))
        }

        override suspend fun verify(candidateId: String): Map<LocalCapability, CheckResult> {
            val (label, candidate) = container.discoveredCandidate(candidateId) ?: throw LocalAiException.UnknownModel(candidateId)
            if (!container.candidateTestable(candidate)) throw LocalAiException.Failed("$candidateId is not installed")
            container.testCandidate(label, candidate)
            return awaitCheck(candidateId)
        }
    }

    /** Waits for [candidateId]'s queued or running check to finish, then reads what it says now. */
    private suspend fun awaitCheck(candidateId: String): Map<LocalCapability, CheckResult> {
        container.candidateWork.first { candidateId !in it.queued && it.trial?.key != candidateId }
        val candidate = container.discoveredCandidate(candidateId)?.second ?: return emptyMap()
        return SdkMapping.checks(candidate.verification, container.verificationContext(candidate))
    }

    private companion object {
        const val NANO = AppContainer.NANO_MODEL_ID
    }

    /** Shipped in the catalog; else taken in from discovery when its bytes are a candidate's; else added by the user. */
    private fun sourceOf(modelId: String, artifact: ai.localstudio.model.install.ArtifactId?): ModelSource = when {
        (ai.localstudio.app.models.LocalModels.SEEDS + ai.localstudio.app.models.TranslationModels.SEEDS).any { it.id == modelId } -> ModelSource.CATALOG
        artifact != null && container.discoveredCandidates().any { it.second.identity == artifact.key } -> ModelSource.DISCOVERED
        else -> ModelSource.CUSTOM
    }

    /** A T5 translation model translates and nothing else; every other local model takes text, and images with its projector. */
    private fun capabilitiesOf(selected: SelectedModel): Set<LocalCapability> =
        if (container.localSeed(selected.model.id)?.isT5EncoderDecoder == true) setOf(LocalCapability.TRANSLATION) else SdkMapping.capabilities(selected.binding)

    /**
     * One request through the shared runtime, bounded by [options]' timeout
     * the way the Chat screen bounds its own (waiting for another model's
     * turn does not count), with its failures in the SDK's terms. A
     * channelFlow: the watchdog runs the work in a child coroutine, and a
     * plain flow may only emit from its own.
     */
    private fun answer(selected: SelectedModel, request: GenerationRequest, options: GenerationOptions): Flow<String> =
        answer({
            container.localTextRuntime().load(selected.model, selected.binding) as? TextModelHandle
                ?: throw LocalAiException.Failed("${selected.model.id} did not load as a text model")
        }, request, options)

    private fun answer(
        load: suspend () -> TextModelHandle,
        request: GenerationRequest,
        options: GenerationOptions,
    ): Flow<String> = channelFlow {
        val handle = load()
        try {
            container.heavyOperations.track {
                withOperationTimeout(options.timeoutMs, options.deadlineMs) {
                    handle.generate(request).collect { send(it) }
                }
            }
        } catch (e: OperationTimeoutException) {
            handle.requestCancel()
            throw LocalAiException.Timeout(e.limitMs)
        } finally {
            handle.close()
        }
    }.catch { e ->
        throw when (e) {
            is CancellationException, is LocalAiException -> e
            is ImageNotSeenException -> LocalAiException.ImageNotSeen(e.reason)
            is InsufficientMemoryException -> LocalAiException.NotEnoughMemory(e.requestedBytes, e.budgetBytes)
            else -> LocalAiException.Failed(e.message ?: e.javaClass.simpleName, e)
        }
    }
}
