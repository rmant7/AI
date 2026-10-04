package ai.localstudio.app.llama

import ai.localstudio.app.modelinstall.ModelInstallation
import ai.localstudio.app.models.DownloadProgress
import ai.localstudio.app.models.HuggingFaceResolver
import ai.localstudio.app.models.ModelDownloader
import ai.localstudio.app.models.catalog.LegacyCatalogMapper
import ai.localstudio.model.install.InstallResult
import ai.localstudio.model.install.TransferCancelledException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

sealed interface ExperimentalDownloadState {
    data object Idle : ExperimentalDownloadState
    data object Resolving : ExperimentalDownloadState
    data class Running(val progress: DownloadProgress) : ExperimentalDownloadState
    data class Failed(val message: String) : ExperimentalDownloadState
    data object Installed : ExperimentalDownloadState
}

/**
 * Downloads a candidate [EmbeddingModelSpec]'s GGUF onto the device from
 * inside the app — the only option on a phone with no adb, which is exactly
 * how this app's own manual on-device verification harness
 * ([ExperimentalEmbeddingModelTest]) previously expected the file to arrive.
 *
 * Deliberately its own small class rather than a reuse of
 * [ai.localstudio.app.models.ModelDownloads]: that class's state machine
 * carries mmproj-projector and RAM-fit concerns specific to
 * [ai.localstudio.app.models.LocalModelSeed] chat models, none of which
 * apply here. What *is* reused is the actual transfer engine
 * ([ModelDownloader]) and repo resolution ([HuggingFaceResolver]) — the
 * parts that are genuinely the same problem (resumable HTTP download of a
 * GGUF off Hugging Face) regardless of what the file is for.
 *
 * Not wired to [ai.localstudio.app.models.ModelDownloadService]'s foreground
 * notification: these are modest-sized (tens to a few hundred MB) manual,
 * one-at-a-time verification downloads, not the multi-gigabyte chat-model
 * transfers that motivated keeping the process alive in the background.
 */
class ExperimentalEmbeddingDownloads(
    private val store: ExperimentalEmbeddingStore,
    private val tokenProvider: () -> String? = { null },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val log: (String) -> Unit = {},
) {

    private val states = MutableStateFlow<Map<String, ExperimentalDownloadState>>(emptyMap())
    val state: StateFlow<Map<String, ExperimentalDownloadState>> = states

    private val jobs = mutableMapOf<String, Job>()
    private val downloaders = mutableMapOf<String, ModelDownloader>()

    fun stateOf(spec: EmbeddingModelSpec): ExperimentalDownloadState =
        states.value[spec.id] ?: if (store.isInstalled(spec)) ExperimentalDownloadState.Installed else ExperimentalDownloadState.Idle

    private val cancelled = mutableMapOf<String, AtomicBoolean>()

    /**
     * Phase 3b.3: installs through the new chain (resolve to a commit, Range
     * download, sha256 check, manifest -- [ai.localstudio.app.modelinstall.ModelInstallation])
     * when the store has one; the legacy download below is the fallback when
     * that fails for any reason other than cancellation or lack of space.
     */
    fun start(spec: EmbeddingModelSpec) {
        if (jobs[spec.id]?.isActive == true) return

        val downloader = ModelDownloader()
        downloaders[spec.id] = downloader
        val cancel = AtomicBoolean(false).also { cancelled[spec.id] = it }
        jobs[spec.id] = scope.launch {
            publish(spec, ExperimentalDownloadState.Resolving)
            store.installation?.let { installation ->
                val outcome = runCatching { installViaStore(installation, spec, cancel) }
                    .getOrElse { e -> if (e is TransferCancelledException) return@launch else NewChainOutcome.Failed(e.message ?: e.toString()) }
                when (outcome) {
                    NewChainOutcome.Installed -> {
                        store.partFor(spec).delete()
                        publish(spec, ExperimentalDownloadState.Installed)
                        downloaders.remove(spec.id)
                        return@launch
                    }
                    is NewChainOutcome.Final -> {
                        publish(spec, ExperimentalDownloadState.Failed(outcome.message))
                        downloaders.remove(spec.id)
                        return@launch
                    }
                    is NewChainOutcome.Failed -> log("${spec.title}: model store install failed (${outcome.message}); falling back to the legacy download")
                }
            }
            try {
                val (_, resolved) = HuggingFaceResolver.resolveAny(
                    listOf(spec.repoId),
                    tokenProvider(),
                    quantPriority = listOf(spec.quantLabel),
                )
                val free = store.freeSpaceBytes()
                if (resolved.sizeBytes > 0 && resolved.sizeBytes + SLACK_BYTES > free) {
                    publish(spec, ExperimentalDownloadState.Failed("Not enough space: need ${mb(resolved.sizeBytes)}, ${mb(free)} free"))
                    return@launch
                }

                publish(spec, ExperimentalDownloadState.Running(DownloadProgress(store.partialSize(spec), resolved.sizeBytes)))
                downloader.download(
                    url = resolved.downloadUrl,
                    destination = store.legacyFileFor(spec),
                    tempFile = store.partFor(spec),
                ) { progress -> publish(spec, ExperimentalDownloadState.Running(progress)) }

                val installedSize = store.legacyFileFor(spec).length()
                if (resolved.sizeBytes > 0 && installedSize != resolved.sizeBytes) {
                    store.legacyFileFor(spec).delete()
                    publish(spec, ExperimentalDownloadState.Failed("File corrupted: got $installedSize bytes, expected ${resolved.sizeBytes}"))
                    return@launch
                }

                publish(spec, ExperimentalDownloadState.Installed)
            } catch (e: Exception) {
                publish(spec, ExperimentalDownloadState.Failed(e.message ?: e.toString()))
            } finally {
                downloaders.remove(spec.id)
            }
        }
    }

    private sealed interface NewChainOutcome {
        data object Installed : NewChainOutcome

        /** Not worth a legacy retry (no space, refused): shown as is. */
        data class Final(val message: String) : NewChainOutcome

        data class Failed(val message: String) : NewChainOutcome
    }

    private fun installViaStore(installation: ModelInstallation, spec: EmbeddingModelSpec, cancel: AtomicBoolean): NewChainOutcome {
        val model = store.model(spec)
        val result = installation.installer.install(
            LegacyCatalogMapper.CATALOG_ID,
            LegacyCatalogMapper.CATALOG_VERSION,
            model,
            model.variants.single(),
            installation::freeBytes,
            cancel = { cancel.get() },
        ) { progress ->
            publish(spec, ExperimentalDownloadState.Running(DownloadProgress(progress.transfer.bytesDone, progress.transfer.bytesTotal ?: 0)))
        }
        return when (result) {
            is InstallResult.Installed -> {
                val weights = result.manifest.artifacts.single()
                log("${spec.title}: installed in the model store from ${weights.source.repo}/${weights.source.path}@${weights.source.commit?.take(8)} (${weights.integrity})")
                NewChainOutcome.Installed
            }
            is InstallResult.AlreadyInstalled -> NewChainOutcome.Installed
            is InstallResult.InsufficientStorage ->
                NewChainOutcome.Final("Not enough space: need ${mb(result.neededBytes)}, ${mb(result.freeBytes)} free")
            is InstallResult.Refused -> NewChainOutcome.Final("${spec.title} is ${result.status.name.lowercase()} in the catalogue")
            is InstallResult.Failed -> NewChainOutcome.Failed("${result.fileName}: ${result.failures.joinToString("; ")}")
        }
    }

    fun cancel(spec: EmbeddingModelSpec) {
        cancelled[spec.id]?.set(true)
        downloaders[spec.id]?.cancel()
        jobs[spec.id]?.cancel()
        publish(spec, ExperimentalDownloadState.Idle)
    }

    fun delete(spec: EmbeddingModelSpec) {
        cancel(spec)
        store.delete(spec)
        publish(spec, ExperimentalDownloadState.Idle)
    }

    private fun publish(spec: EmbeddingModelSpec, state: ExperimentalDownloadState) {
        states.value = states.value + (spec.id to state)
    }

    private fun mb(bytes: Long): String = "%.0f MB".format(bytes / 1_000_000.0)

    private companion object {
        const val SLACK_BYTES = 100L * 1024 * 1024
    }
}
