package ai.localstudio.app.vosk

import ai.localstudio.app.models.DownloadProgress
import ai.localstudio.app.models.ModelDownloader
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

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
) {

    private val states = MutableStateFlow<Map<String, VoskDownloadState>>(emptyMap())
    val state: StateFlow<Map<String, VoskDownloadState>> = states

    private val jobs = mutableMapOf<String, Job>()
    private val downloaders = mutableMapOf<String, ModelDownloader>()

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
        jobs[seed.id] = scope.launch {
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
                        VoskModelStore.extract(zip, VoskModelStore.modelDir(context, seed))
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

    fun cancel(seed: VoskModelSeed) {
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
