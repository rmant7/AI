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
    data class Running(val progress: DownloadProgress, val source: String) : DownloadState
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
    private val tokenProvider: () -> String? = { null },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /**
     * Lets the caller keep the process alive for the duration of the transfer
     * (a foreground service) without this class knowing anything about
     * Android services or notifications.
     */
    private val onDownloadStarted: () -> Unit = {},
    /** Best-effort mmproj download failures go here rather than surfacing as the model's own DownloadState.Failed — see [downloadMmproj]. */
    private val appLogForMmproj: ((String) -> Unit)? = null,
) {

    private val states = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val state: StateFlow<Map<String, DownloadState>> = states

    private val jobs = mutableMapOf<String, Job>()
    private val downloaders = mutableMapOf<String, ModelDownloader>()

    fun stateOf(seed: LocalModelSeed): DownloadState =
        states.value[seed.id] ?: if (store.isInstalled(seed)) DownloadState.Installed else DownloadState.Idle

    fun start(seed: LocalModelSeed) {
        if (jobs[seed.id]?.isActive == true) return

        // A model already installed before this seed declared a projector
        // (or one downloaded while the projector's own fetch failed) has its
        // main GGUF sitting right there — every seed's own download flow
        // below assumes it's starting from nothing and re-resolves and
        // re-fetches that multi-gigabyte file unconditionally. Backfilling
        // just the missing projector, the same best-effort way a fresh
        // install does (see downloadMmproj's own comment), is what actually
        // turns vision on for a model someone already has instead of silent
        // permanent text-only — nothing else ever revisits an installed
        // model to check whether it's missing a file a later app update
        // started expecting.
        if (store.isInstalled(seed) && seed.mmprojFileName != null && !store.hasMmproj(seed)) {
            onDownloadStarted()
            val backfillDownloader = ModelDownloader()
            downloaders[seed.id] = backfillDownloader
            jobs[seed.id] = scope.launch {
                downloadMmproj(seed, backfillDownloader)
                publish(seed, DownloadState.Installed)
                downloaders.remove(seed.id)
            }
            return
        }

        onDownloadStarted()
        val downloader = ModelDownloader()
        downloaders[seed.id] = downloader
        jobs[seed.id] = scope.launch {
            publish(seed, DownloadState.Resolving(seed.repoIds.first()))
            try {
                val (source, resolved) = HuggingFaceResolver.resolveAny(seed.repoIds, tokenProvider())
                val free = store.freeSpaceBytes()
                if (resolved.sizeBytes > 0 && resolved.sizeBytes + SLACK_BYTES > free) {
                    publish(seed, DownloadState.Failed("Не хватает места: нужно ${gb(resolved.sizeBytes)}, свободно ${gb(free)}"))
                    return@launch
                }

                publish(
                    seed,
                    DownloadState.Running(DownloadProgress(store.partialSize(seed), resolved.sizeBytes), source),
                )
                downloader.download(
                    url = resolved.downloadUrl,
                    destination = store.fileFor(seed),
                    tempFile = store.partFor(seed),
                ) { progress -> publish(seed, DownloadState.Running(progress, source)) }

                // The HTTP layer already rejects a transfer that ends short of
                // the Content-Length it was told to expect, but that guards
                // only a single connection's own honesty. Comparing against the
                // size Hugging Face's own metadata reported for this file is an
                // independent check — the "install" step this is standing in
                // for — and it is exactly the kind of corruption that produces
                // a model which loads, then misbehaves or crashes mid-generation
                // instead of failing cleanly up front.
                val installedFile = store.fileFor(seed)
                val installedSize = installedFile.length()
                if (resolved.sizeBytes > 0 && installedSize != resolved.sizeBytes) {
                    installedFile.delete()
                    publish(
                        seed,
                        DownloadState.Failed(
                            "Файл повреждён: получено $installedSize байт, ожидалось ${resolved.sizeBytes}",
                        ),
                    )
                    return@launch
                }

                if (seed.mmprojFileName != null) downloadMmproj(seed, downloader)

                publish(seed, DownloadState.Installed)
            } catch (e: Exception) {
                publish(seed, DownloadState.Failed(e.message ?: e.toString()))
            } finally {
                downloaders.remove(seed.id)
            }
        }
    }

    /**
     * Best-effort: failure here does not fail [start] as a whole. The main
     * GGUF is a model this app cannot run at all without; the projector is a
     * bonus capability on top of an already-usable model, so a bad network
     * blip, a renamed file, or a gated companion repo should leave the user
     * with a working text-only model rather than no model — exactly the
     * failure mode a hard [resolveAny]-style throw here would produce.
     */
    private suspend fun downloadMmproj(seed: LocalModelSeed, downloader: ModelDownloader) {
        val fileName = seed.mmprojFileName ?: return
        val resolved = HuggingFaceResolver.resolveExact(seed.repoIds, fileName, tokenProvider())
        if (resolved == null) {
            appLogForMmproj?.invoke("$fileName not found in any of ${seed.repoIds}")
            return
        }
        val (source, file) = resolved
        publish(seed, DownloadState.Running(DownloadProgress(store.mmprojFileFor(seed).length(), file.sizeBytes), source))
        runCatching {
            downloader.download(
                url = file.downloadUrl,
                destination = store.mmprojFileFor(seed),
                tempFile = store.mmprojPartFor(seed),
            ) { progress -> publish(seed, DownloadState.Running(progress, source)) }
        }.onFailure {
            // A corrupt or half-downloaded projector must not look installed —
            // ModelStore.hasMmproj checks file presence, not validity beyond size.
            store.mmprojFileFor(seed).delete()
            appLogForMmproj?.invoke("mmproj download failed for ${seed.id}: ${it.message}")
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
