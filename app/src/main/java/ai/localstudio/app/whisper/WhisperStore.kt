package ai.localstudio.app.whisper

import ai.localstudio.app.models.DownloadProgress
import ai.localstudio.app.models.ModelDownloader
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Where a downloaded Whisper model lives — a single self-contained ggml
 * `.bin` file (weights, tokenizer and mel filters all in one), unlike the
 * old TFLite path this replaced, which needed a separate shared vocab.json.
 */
class WhisperStore(private val context: Context) {

    fun directory(): File = File(context.filesDir, "whisper").apply { mkdirs() }

    fun modelFile(seed: WhisperModelSeed): File = File(directory(), "${seed.id}.bin")
    fun modelPartFile(seed: WhisperModelSeed): File = File(directory(), "${seed.id}.bin.part")

    fun isInstalled(seed: WhisperModelSeed): Boolean =
        modelFile(seed).let { it.isFile && it.length() > MIN_PLAUSIBLE_MODEL_SIZE }

    /**
     * [preferredId] wins if that size is actually installed; otherwise the
     * largest installed size — not just "whichever happens to be first in
     * [WhisperModels.SEEDS]", which is Tiny. That fallback meant installing
     * Tiny (for the live preview) silently downgraded the final, accurate
     * transcription away from whatever larger model someone had actually
     * been using, the moment nothing had been explicitly selected.
     */
    fun installedSeed(preferredId: String? = null): WhisperModelSeed? {
        val preferred = preferredId?.let { id -> WhisperModels.byId(id) }?.takeIf { isInstalled(it) }
        return preferred ?: WhisperModels.SEEDS.filter { isInstalled(it) }.maxByOrNull { it.approxSizeBytes }
    }

    fun delete(seed: WhisperModelSeed) {
        modelFile(seed).delete()
        modelPartFile(seed).delete()
    }

    private companion object {
        const val MIN_PLAUSIBLE_MODEL_SIZE = 10L * 1024 * 1024
    }
}

sealed interface WhisperDownloadState {
    data object Idle : WhisperDownloadState
    data class Running(val progress: DownloadProgress, val stage: String) : WhisperDownloadState
    data class Failed(val message: String) : WhisperDownloadState
    data object Installed : WhisperDownloadState
}

/** Mirrors [ai.localstudio.app.models.ModelDownloads]'s shape, scaled down to one active model at a time. */
class WhisperDownloads(
    private val store: WhisperStore,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /** Same reasoning as ModelDownloads: without a foreground service, the process — and this download — dies the moment the screen locks. */
    private val onDownloadStarted: () -> Unit = {},
) {

    private val states = MutableStateFlow<Map<String, WhisperDownloadState>>(emptyMap())
    val state: StateFlow<Map<String, WhisperDownloadState>> = states

    private val jobs = mutableMapOf<String, Job>()
    private var activeDownloader: ModelDownloader? = null

    fun stateOf(seed: WhisperModelSeed): WhisperDownloadState =
        states.value[seed.id] ?: if (store.isInstalled(seed)) WhisperDownloadState.Installed else WhisperDownloadState.Idle

    fun start(seed: WhisperModelSeed) {
        if (jobs.values.any { it.isActive }) return

        onDownloadStarted()
        val downloader = ModelDownloader()
        activeDownloader = downloader
        jobs[seed.id] = scope.launch {
            try {
                publish(seed, WhisperDownloadState.Running(DownloadProgress(0, seed.approxSizeBytes), "model"))
                downloader.download(
                    url = seed.modelUrl,
                    destination = store.modelFile(seed),
                    tempFile = store.modelPartFile(seed),
                ) { progress -> publish(seed, WhisperDownloadState.Running(progress, "model")) }

                publish(seed, WhisperDownloadState.Installed)
            } catch (e: Exception) {
                publish(seed, WhisperDownloadState.Failed(e.message ?: e.toString()))
            }
        }
    }

    fun cancel(seed: WhisperModelSeed) {
        activeDownloader?.cancel()
        jobs[seed.id]?.cancel()
        publish(seed, WhisperDownloadState.Idle)
    }

    fun delete(seed: WhisperModelSeed) {
        cancel(seed)
        store.delete(seed)
        publish(seed, WhisperDownloadState.Idle)
    }

    private fun publish(seed: WhisperModelSeed, s: WhisperDownloadState) {
        states.value = states.value + (seed.id to s)
    }
}
