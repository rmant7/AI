package ai.localstudio.app.whisper

import ai.localstudio.app.modelinstall.ModelInstallation
import ai.localstudio.app.models.DownloadProgress
import ai.localstudio.app.models.ModelDownloader
import ai.localstudio.app.models.catalog.LegacyCatalogMapper
import ai.localstudio.model.ArtifactRoles
import ai.localstudio.model.VariantId
import ai.localstudio.model.install.InstallHealth
import ai.localstudio.model.install.InstallResult
import ai.localstudio.model.install.TransferCancelledException
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Where a downloaded Whisper model lives — a single self-contained ggml
 * `.bin` file (weights, tokenizer and mel filters all in one), unlike the
 * old TFLite path this replaced, which needed a separate shared vocab.json.
 *
 * Phase 3c.2: with an [installation], a model installed through the new
 * chain is found in the model store first; the legacy `whisper/<id>.bin`
 * stays the fallback, so every engine (mic, preview, file transcription,
 * the router) switches together through [modelFile]/[isInstalled].
 */
class WhisperStore(
    context: Context,
    val installation: ModelInstallation? = null,
    private val baseDir: File = context.filesDir,
) {

    fun directory(): File = File(baseDir, "whisper").apply { mkdirs() }

    /** The file to load: the store's copy when installed and intact there, else the legacy one. */
    fun modelFile(seed: WhisperModelSeed): File = storeFile(seed) ?: legacyModelFile(seed)

    fun legacyModelFile(seed: WhisperModelSeed): File = File(directory(), "${seed.id}.bin")
    fun modelPartFile(seed: WhisperModelSeed): File = File(directory(), "${seed.id}.bin.part")

    fun isInstalled(seed: WhisperModelSeed): Boolean =
        isInNewStore(seed) || legacyModelFile(seed).let { it.isFile && it.length() > MIN_PLAUSIBLE_MODEL_SIZE }

    fun isInNewStore(seed: WhisperModelSeed): Boolean = storeFile(seed) != null

    fun variantId(seed: WhisperModelSeed): VariantId = VariantId(seed.id + LegacyCatalogMapper.LEGACY_VARIANT_SUFFIX)

    /**
     * [preferredId] wins if that size is actually installed; otherwise the
     * largest installed size — not just "whichever happens to be first in
     * [WhisperModels.SEEDS]", which is Tiny. That fallback meant installing
     * Tiny (for the live preview) silently downgraded the final, accurate
     * transcription away from whatever larger model someone had actually
     * been using, the moment nothing had been explicitly selected.
     */
    fun installedSeed(preferredId: String? = null, candidates: List<WhisperModelSeed> = WhisperModels.SEEDS): WhisperModelSeed? {
        val preferred = preferredId?.let { id -> candidates.firstOrNull { it.id == id } ?: WhisperModels.byId(id) }
            ?.takeIf { isInstalled(it) }
        return preferred ?: candidates.filter { isInstalled(it) }.maxByOrNull { it.approxSizeBytes }
    }

    fun delete(seed: WhisperModelSeed) {
        installation?.let { it.installed.uninstall(variantId(seed)); it.layout.stagingDir(variantId(seed)).deleteRecursively() }
        legacyModelFile(seed).delete()
        modelPartFile(seed).delete()
    }

    private fun storeFile(seed: WhisperModelSeed): File? {
        val installed = installation?.installed ?: return null
        val manifest = installed.manifest(variantId(seed))?.takeIf { installed.health(it) == InstallHealth.Intact } ?: return null
        val weights = manifest.artifacts.firstOrNull { it.role == ArtifactRoles.WEIGHTS } ?: return null
        return installed.pathOf(manifest, weights).takeIf { it.isFile }
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
    private val log: (String) -> Unit = {},
) {

    private val states = MutableStateFlow<Map<String, WhisperDownloadState>>(emptyMap())
    val state: StateFlow<Map<String, WhisperDownloadState>> = states

    private val jobs = mutableMapOf<String, Job>()

    /** Per seed, like [ai.localstudio.app.models.ModelDownloads.downloaders] — a single shared field here previously meant [cancel] could cancel a *different* seed's transfer, and a second Download tap was blocked outright rather than starting alongside the first. */
    private val downloaders = mutableMapOf<String, ModelDownloader>()

    /** Cancellation for the new chain's blocking install. */
    private val cancelled = mutableMapOf<String, AtomicBoolean>()

    fun stateOf(seed: WhisperModelSeed): WhisperDownloadState =
        states.value[seed.id] ?: if (store.isInstalled(seed)) WhisperDownloadState.Installed else WhisperDownloadState.Idle

    fun start(seed: WhisperModelSeed) {
        if (jobs[seed.id]?.isActive == true) return

        onDownloadStarted()
        val downloader = ModelDownloader()
        downloaders[seed.id] = downloader
        val cancel = AtomicBoolean(false).also { cancelled[seed.id] = it }
        jobs[seed.id] = scope.launch {
            // Phase 3c.2: the new chain first, unless a legacy .part is
            // already on disk (resuming it beats fetching it again).
            val installation = store.installation
            if (installation != null && !store.modelPartFile(seed).isFile) {
                val outcome = try {
                    installViaStore(installation, seed, cancel)
                } catch (e: TransferCancelledException) {
                    // A progress callback can land after cancel() published
                    // Idle (device report, build #445: Whisper Tiny's button
                    // kept saying Pause) -- published once more.
                    publish(seed, WhisperDownloadState.Idle)
                    downloaders.remove(seed.id)
                    return@launch
                } catch (e: Exception) {
                    StoreOutcome.Failed("${e.javaClass.simpleName}: ${e.message}")
                }
                when (outcome) {
                    StoreOutcome.Installed -> {
                        publish(seed, WhisperDownloadState.Installed)
                        downloaders.remove(seed.id)
                        return@launch
                    }
                    is StoreOutcome.Final -> {
                        publish(seed, WhisperDownloadState.Failed(outcome.message))
                        downloaders.remove(seed.id)
                        return@launch
                    }
                    is StoreOutcome.Failed ->
                        log("${seed.id}: model store install failed (${outcome.message}); falling back to the legacy download")
                }
            }
            try {
                publish(seed, WhisperDownloadState.Running(DownloadProgress(0, seed.approxSizeBytes), "model"))
                downloader.download(
                    url = seed.modelUrl,
                    destination = store.legacyModelFile(seed),
                    tempFile = store.modelPartFile(seed),
                ) { progress -> publish(seed, WhisperDownloadState.Running(progress, "model")) }

                publish(seed, WhisperDownloadState.Installed)
            } catch (e: Exception) {
                publish(seed, WhisperDownloadState.Failed(e.message ?: e.toString()))
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

    private fun installViaStore(installation: ModelInstallation, seed: WhisperModelSeed, cancel: AtomicBoolean): StoreOutcome {
        val model = LegacyCatalogMapper.customWhisperModel(seed)
        val result = installation.installer.install(
            LegacyCatalogMapper.CATALOG_ID,
            LegacyCatalogMapper.CATALOG_VERSION,
            model,
            model.variants.single(),
            installation::freeBytes,
            cancel = { cancel.get() },
        ) { progress ->
            if (!cancel.get()) {
                publish(seed, WhisperDownloadState.Running(DownloadProgress(progress.transfer.bytesDone, progress.transfer.bytesTotal ?: seed.approxSizeBytes), "model"))
            }
        }
        return when (result) {
            is InstallResult.Installed -> {
                val weights = result.manifest.artifacts.single()
                log("${seed.id}: installed in the model store from ${weights.source.repo ?: weights.source.url}@${weights.source.commit?.take(8)} (${weights.integrity})")
                StoreOutcome.Installed
            }
            is InstallResult.AlreadyInstalled -> StoreOutcome.Installed
            is InstallResult.InsufficientStorage ->
                StoreOutcome.Final("Not enough space: need ${result.neededBytes / 1_000_000} MB, ${result.freeBytes / 1_000_000} MB free")
            is InstallResult.Refused -> StoreOutcome.Final("${seed.title} is ${result.status.name.lowercase()} in the catalogue")
            is InstallResult.Failed -> StoreOutcome.Failed("${result.fileName}: ${result.failures.joinToString("; ")}")
        }
    }

    fun cancel(seed: WhisperModelSeed) {
        cancelled[seed.id]?.set(true)
        downloaders[seed.id]?.cancel()
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
