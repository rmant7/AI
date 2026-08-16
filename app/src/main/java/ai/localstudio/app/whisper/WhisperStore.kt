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

/** Where a downloaded Whisper model — and the tokenizer it shares with every other size — live. */
class WhisperStore(private val context: Context) {

    fun directory(): File = File(context.filesDir, "whisper").apply { mkdirs() }

    fun modelFile(seed: WhisperModelSeed): File = File(directory(), "${seed.id}.tflite")
    fun modelPartFile(seed: WhisperModelSeed): File = File(directory(), "${seed.id}.tflite.part")
    fun vocabFile(): File = File(directory(), "vocab.json")
    fun vocabPartFile(): File = File(directory(), "vocab.json.part")

    fun isInstalled(seed: WhisperModelSeed): Boolean =
        modelFile(seed).let { it.isFile && it.length() > MIN_PLAUSIBLE_MODEL_SIZE } && hasVocab()

    fun hasVocab(): Boolean = vocabFile().let { it.isFile && it.length() > MIN_PLAUSIBLE_VOCAB_SIZE }

    fun installedSeed(): WhisperModelSeed? = WhisperModels.SEEDS.firstOrNull { isInstalled(it) }

    fun delete(seed: WhisperModelSeed) {
        modelFile(seed).delete()
        modelPartFile(seed).delete()
    }

    private companion object {
        const val MIN_PLAUSIBLE_MODEL_SIZE = 10L * 1024 * 1024
        const val MIN_PLAUSIBLE_VOCAB_SIZE = 10L * 1024
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
) {

    private val states = MutableStateFlow<Map<String, WhisperDownloadState>>(emptyMap())
    val state: StateFlow<Map<String, WhisperDownloadState>> = states

    private val jobs = mutableMapOf<String, Job>()
    private var activeDownloader: ModelDownloader? = null

    fun stateOf(seed: WhisperModelSeed): WhisperDownloadState =
        states.value[seed.id] ?: if (store.isInstalled(seed)) WhisperDownloadState.Installed else WhisperDownloadState.Idle

    fun start(seed: WhisperModelSeed) {
        // Only one at a time: every size shares one vocab.json destination
        // file, and two concurrent downloads would race writing it.
        if (jobs.values.any { it.isActive }) return

        val downloader = ModelDownloader()
        activeDownloader = downloader
        jobs[seed.id] = scope.launch {
            try {
                publish(seed, WhisperDownloadState.Running(DownloadProgress(0, seed.approxSizeBytes), "модель"))
                downloader.download(
                    url = seed.modelUrl,
                    destination = store.modelFile(seed),
                    tempFile = store.modelPartFile(seed),
                ) { progress -> publish(seed, WhisperDownloadState.Running(progress, "модель")) }

                if (!store.hasVocab()) {
                    publish(seed, WhisperDownloadState.Running(DownloadProgress(0, 0), "словарь"))
                    downloader.download(
                        url = WhisperModels.VOCAB_URL,
                        destination = store.vocabFile(),
                        tempFile = store.vocabPartFile(),
                    ) { progress -> publish(seed, WhisperDownloadState.Running(progress, "словарь")) }
                }

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
