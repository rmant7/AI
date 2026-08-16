package ai.localstudio.app.models

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface DownloadState {
    data object Idle : DownloadState
    data class Resolving(val repoId: String) : DownloadState
    data class Running(val progress: DownloadProgress) : DownloadState
    data class Failed(val message: String) : DownloadState
    data object Installed : DownloadState
}

/**
 * Owns downloads for the whole app rather than for a screen.
 *
 * A multi-gigabyte transfer must not die because the user backed out of the
 * Models screen, so the work runs in an application-scoped coroutine and the
 * UI merely observes it.
 */
class ModelDownloads(
    private val store: ModelStore,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    private val states = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val state: StateFlow<Map<String, DownloadState>> = states

    private val jobs = mutableMapOf<String, Job>()
    private val downloaders = mutableMapOf<String, ModelDownloader>()

    fun stateOf(seed: LocalModelSeed): DownloadState =
        states.value[seed.id] ?: if (store.isInstalled(seed)) DownloadState.Installed else DownloadState.Idle

    fun start(seed: LocalModelSeed) {
        if (jobs[seed.id]?.isActive == true) return

        val downloader = ModelDownloader()
        downloaders[seed.id] = downloader
        jobs[seed.id] = scope.launch {
            publish(seed, DownloadState.Resolving(seed.repoId))
            try {
                val resolved = HuggingFaceResolver.resolve(seed.repoId)
                val free = store.freeSpaceBytes()
                if (resolved.sizeBytes > 0 && resolved.sizeBytes + SLACK_BYTES > free) {
                    publish(seed, DownloadState.Failed("Не хватает места: нужно ${gb(resolved.sizeBytes)}, свободно ${gb(free)}"))
                    return@launch
                }

                publish(seed, DownloadState.Running(DownloadProgress(store.partialSize(seed), resolved.sizeBytes)))
                downloader.download(
                    url = resolved.downloadUrl,
                    destination = store.fileFor(seed),
                    tempFile = store.partFor(seed),
                ) { progress -> publish(seed, DownloadState.Running(progress)) }

                publish(seed, DownloadState.Installed)
            } catch (e: Exception) {
                publish(seed, DownloadState.Failed(e.message ?: e.toString()))
            } finally {
                downloaders.remove(seed.id)
            }
        }
    }

    fun cancel(seed: LocalModelSeed) {
        downloaders[seed.id]?.cancel()
        jobs[seed.id]?.cancel()
        // The partial file is kept on purpose: the next attempt resumes from it.
        publish(seed, DownloadState.Idle)
    }

    fun delete(seed: LocalModelSeed) {
        cancel(seed)
        store.delete(seed)
        publish(seed, DownloadState.Idle)
    }

    private fun publish(seed: LocalModelSeed, state: DownloadState) {
        states.value = states.value + (seed.id to state)
    }

    private fun gb(bytes: Long): String = "%.1f ГБ".format(bytes / 1_000_000_000.0)

    private companion object {
        /** Never fill the disk to the last byte for a model. */
        const val SLACK_BYTES = 500L * 1024 * 1024
    }
}
