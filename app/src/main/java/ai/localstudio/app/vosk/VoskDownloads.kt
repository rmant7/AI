package ai.localstudio.app.vosk

import ai.localstudio.app.modelinstall.ModelInstallation
import ai.localstudio.app.models.DownloadProgress
import ai.localstudio.app.models.ModelDownloader
import ai.localstudio.app.models.catalog.LegacyCatalogMapper
import ai.localstudio.model.install.InstallResult
import ai.localstudio.model.install.TransferCancelledException
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

sealed interface VoskDownloadState {
    data object Idle : VoskDownloadState
    data class Running(val progress: DownloadProgress, val stage: String) : VoskDownloadState
    data class Failed(val message: String) : VoskDownloadState
    data object Installed : VoskDownloadState
}

/**
 * Mirrors [ai.localstudio.app.whisper.WhisperDownloads]'s shape, plus one
 * extra step: a Vosk model is a zip of a directory, not a single file, so
 * "download" here means download-then-[VoskModelStore.extract]. Per-seed
 * downloaders/jobs, same as Whisper's — several models can download
 * concurrently.
 */
class VoskDownloads(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /** Same reasoning as ModelDownloads/WhisperDownloads: without a foreground service, the process — and this download — dies the moment the screen locks. */
    private val onDownloadStarted: () -> Unit = {},
    private val log: (String) -> Unit = {},
) {

    private val states = MutableStateFlow<Map<String, VoskDownloadState>>(emptyMap())
    val state: StateFlow<Map<String, VoskDownloadState>> = states

    private val jobs = mutableMapOf<String, Job>()
    private val downloaders = mutableMapOf<String, ModelDownloader>()

    /** Cancellation for the new chain's blocking install. */
    private val cancelled = mutableMapOf<String, AtomicBoolean>()

    fun stateOf(seed: VoskModelSeed): VoskDownloadState =
        states.value[seed.id] ?: if (VoskModelStore.isInstalled(context, seed)) VoskDownloadState.Installed else VoskDownloadState.Idle

    /**
     * Tries every URL in [VoskModelSeed.downloadUrls] in order, moving to
     * the next only once the current one actually fails — not a real load
     * balancer, just enough to survive one host being unreachable (a real,
     * reported failure: `alphacephei.com` not resolving on a real device,
     * VPN or not, after [ModelDownloader]'s own 6-attempt retry already
     * gave up). Reports [VoskDownloadState.Failed] only once every mirror
     * has failed, carrying the last mirror's own error.
     */
    fun start(seed: VoskModelSeed) {
        if (jobs[seed.id]?.isActive == true) return

        onDownloadStarted()
        val cancel = AtomicBoolean(false).also { cancelled[seed.id] = it }
        jobs[seed.id] = scope.launch {
            // Phase 3c.3: the new chain first (it tries the same URLs in the
            // same order and unpacks the same way), unless a legacy .part is
            // already on disk.
            val installation = VoskModelStore.installation
            if (installation != null && !VoskModelStore.zipPartFile(context, seed).isFile) {
                val outcome = try {
                    installViaStore(installation, seed, cancel)
                } catch (e: TransferCancelledException) {
                    return@launch
                } catch (e: Exception) {
                    StoreOutcome.Failed("${e.javaClass.simpleName}: ${e.message}")
                }
                when (outcome) {
                    StoreOutcome.Installed -> {
                        publish(seed, VoskDownloadState.Installed)
                        return@launch
                    }
                    is StoreOutcome.Final -> {
                        publish(seed, VoskDownloadState.Failed(outcome.message))
                        return@launch
                    }
                    is StoreOutcome.Failed ->
                        log("${seed.id}: model store install failed (${outcome.message}); falling back to the legacy download")
                }
            }
            var lastError: Exception? = null
            try {
                for (url in seed.downloadUrls) {
                    val downloader = ModelDownloader()
                    downloaders[seed.id] = downloader
                    try {
                        publish(seed, VoskDownloadState.Running(DownloadProgress(0, seed.approxSizeBytes), "model"))
                        val zip = VoskModelStore.zipFile(context, seed)
                        downloader.download(
                            url = url,
                            destination = zip,
                            tempFile = VoskModelStore.zipPartFile(context, seed),
                        ) { progress -> publish(seed, VoskDownloadState.Running(progress, "model")) }

                        publish(seed, VoskDownloadState.Running(DownloadProgress(seed.approxSizeBytes, seed.approxSizeBytes), "unzipping"))
                        VoskModelStore.extract(zip, VoskModelStore.legacyModelDir(context, seed))
                        zip.delete()

                        publish(seed, VoskDownloadState.Installed)
                        return@launch
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        lastError = e
                        // A .part left over from this mirror must not be
                        // resumed against a *different* mirror on the next
                        // loop iteration — Range-based resume assumes the
                        // same file at the other end of the connection.
                        VoskModelStore.zipPartFile(context, seed).delete()
                    }
                }
                publish(seed, VoskDownloadState.Failed(lastError?.message ?: lastError?.toString() ?: "All sources failed"))
            } finally {
                downloaders.remove(seed.id)
            }
        }
    }

    private sealed interface StoreOutcome {
        data object Installed : StoreOutcome

        /** Not worth a legacy retry (no space, refused): shown as is. */
        data class Final(val message: String) : StoreOutcome

        data class Failed(val message: String) : StoreOutcome
    }

    private fun installViaStore(installation: ModelInstallation, seed: VoskModelSeed, cancel: AtomicBoolean): StoreOutcome {
        val model = LegacyCatalogMapper.voskModel(seed)
        val result = installation.installer.install(
            LegacyCatalogMapper.CATALOG_ID,
            LegacyCatalogMapper.CATALOG_VERSION,
            model,
            model.variants.single(),
            installation::freeBytes,
            cancel = { cancel.get() },
        ) { progress ->
            publish(seed, VoskDownloadState.Running(DownloadProgress(progress.transfer.bytesDone, progress.transfer.bytesTotal ?: seed.approxSizeBytes), "model"))
        }
        return when (result) {
            is InstallResult.Installed -> {
                val archive = result.manifest.artifacts.single()
                log("${seed.id}: installed in the model store from ${archive.source.url} (${archive.integrity}, ${(archive.unpackedBytes ?: 0) / 1_000_000} MB unpacked)")
                StoreOutcome.Installed
            }
            is InstallResult.AlreadyInstalled -> StoreOutcome.Installed
            is InstallResult.InsufficientStorage ->
                StoreOutcome.Final("Not enough space: need ${result.neededBytes / 1_000_000} MB, ${result.freeBytes / 1_000_000} MB free")
            is InstallResult.Refused -> StoreOutcome.Final("${seed.title} is ${result.status.name.lowercase()} in the catalogue")
            is InstallResult.Failed -> StoreOutcome.Failed("${result.fileName}: ${result.failures.joinToString("; ")}")
        }
    }

    fun cancel(seed: VoskModelSeed) {
        cancelled[seed.id]?.set(true)
        downloaders[seed.id]?.cancel()
        jobs[seed.id]?.cancel()
        publish(seed, VoskDownloadState.Idle)
    }

    fun delete(seed: VoskModelSeed) {
        cancel(seed)
        VoskModelStore.delete(context, seed)
        publish(seed, VoskDownloadState.Idle)
    }

    private fun publish(seed: VoskModelSeed, s: VoskDownloadState) {
        states.value = states.value + (seed.id to s)
    }
}
