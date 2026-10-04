package ai.localstudio.app.models

import ai.localstudio.app.modelinstall.ModelInstallation
import ai.localstudio.app.models.catalog.LegacyCatalogMapper
import ai.localstudio.core.registry.ArtifactResolver
import ai.localstudio.model.ArtifactRoles
import ai.localstudio.model.install.ArtifactResolution
import ai.localstudio.model.install.GgufCompatibility
import ai.localstudio.model.install.GgufProbe
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

sealed interface DownloadState {
    data object Idle : DownloadState

    /**
     * Not currently downloading, but a `.part` file with [partialBytes]
     * already in it sits on disk — [ModelDownloads.start] resumes it via
     * HTTP Range rather than starting over. Real device report: after a
     * process kill (or a deliberate pause) mid-download, the Models screen
     * showed a plain "Download" button with no sign that ~5GB was already
     * on disk, indistinguishable from a model never touched at all.
     */
    data class Paused(val partialBytes: Long) : DownloadState
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
    /**
     * Real device report: a custom model's download failed (a malformed repo
     * id — see [ai.localstudio.app.ModelsActivity.normalizeRepoInput]'s own
     * doc comment) with nothing about it anywhere in the app's own log — this
     * class published the failure only as a [DownloadState] the Models
     * screen's own row happened to be showing at the time, the same as every
     * other subsystem's `appLog.record(tag, message)` calls, and every real
     * download's start/failure/success now goes through it too, not just a
     * projector's best-effort one (see [downloadMmproj]'s own use of it).
     */
    private val log: (tag: String, message: String) -> Unit = { _, _ -> },
) {

    private val states = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val state: StateFlow<Map<String, DownloadState>> = states

    private val jobs = mutableMapOf<String, Job>()
    private val downloaders = mutableMapOf<String, ModelDownloader>()

    /** Cancellation for the new chain's blocking install (see [installViaStore]). */
    private val cancelled = mutableMapOf<String, AtomicBoolean>()

    /**
     * When a backfill attempt (see [start]'s own comment) may next retry for
     * a given seed, keyed by seed id — set only after a *failed* attempt.
     * Every message sent rebuilds the local registry, which calls [start]
     * again for any seed still missing its projector; with no cooldown, a
     * network that simply can't reach huggingface.co right now (observed on
     * a real device: DNS resolution failing for that host specifically,
     * while the rest of the internet worked) would retry — with its own
     * internal multi-attempt retry inside [downloadMmproj] — on every single
     * turn for as long as that lasted, for a host with no realistic chance
     * of answering differently a few seconds later.
     */
    private val mmprojBackfillCooldownUntil = mutableMapOf<String, Long>()

    fun stateOf(seed: LocalModelSeed): DownloadState = states.value[seed.id] ?: when {
        store.isInstalled(seed) -> DownloadState.Installed
        store.partialSize(seed) > 0 -> DownloadState.Paused(store.partialSize(seed))
        else -> DownloadState.Idle
    }

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
            if (System.currentTimeMillis() < (mmprojBackfillCooldownUntil[seed.id] ?: 0L)) return
            onDownloadStarted()
            val backfillDownloader = ModelDownloader()
            downloaders[seed.id] = backfillDownloader
            jobs[seed.id] = scope.launch {
                downloadMmproj(seed, backfillDownloader)
                if (!store.hasMmproj(seed)) {
                    mmprojBackfillCooldownUntil[seed.id] = System.currentTimeMillis() + MMPROJ_BACKFILL_COOLDOWN_MS
                }
                publish(seed, DownloadState.Installed)
                downloaders.remove(seed.id)
            }
            return
        }

        onDownloadStarted()
        val downloader = ModelDownloader()
        downloaders[seed.id] = downloader
        val cancel = AtomicBoolean(false).also { cancelled[seed.id] = it }
        jobs[seed.id] = scope.launch {
            log("MODEL_DOWNLOAD", "${seed.id}: resolving from ${seed.repoIds}")
            publish(seed, DownloadState.Resolving(seed.repoIds.first()))
            // Phase 3c.1: the new chain first (commit-pinned, sha256-verified,
            // manifest) -- unless a legacy .part is already on disk: resuming
            // it beats fetching those gigabytes again into the store.
            val installation = store.installation
            if (installation != null && !store.partFor(seed).isFile) {
                val outcome = try {
                    installViaStore(installation, seed, cancel)
                } catch (e: TransferCancelledException) {
                    // cancel() published Paused already, but a progress
                    // callback can land after it (device report, build #445:
                    // the button kept saying Pause) -- published once more.
                    publishStopped(seed)
                    downloaders.remove(seed.id)
                    return@launch
                } catch (e: Exception) {
                    NewChainOutcome.Failed("${e.javaClass.simpleName}: ${e.message}")
                }
                when (outcome) {
                    NewChainOutcome.Installed -> {
                        publish(seed, DownloadState.Installed)
                        downloaders.remove(seed.id)
                        return@launch
                    }
                    is NewChainOutcome.Final -> {
                        log("MODEL_DOWNLOAD", "${seed.id}: FAILED: ${outcome.message}")
                        publish(seed, DownloadState.Failed(outcome.message))
                        downloaders.remove(seed.id)
                        return@launch
                    }
                    is NewChainOutcome.Failed ->
                        log("MODEL_DOWNLOAD", "${seed.id}: model store install failed (${outcome.message}); falling back to the legacy download")
                }
            }
            try {
                val (source, resolved) = HuggingFaceResolver.resolveAny(
                    seed.repoIds,
                    tokenProvider(),
                    seed.quantPriority ?: ArtifactResolver.DEFAULT_QUANT_PRIORITY,
                )
                log("MODEL_DOWNLOAD", "${seed.id}: resolved via $source -> ${resolved.fileName} (${gb(resolved.sizeBytes)})")
                val free = store.freeSpaceBytes()
                if (resolved.sizeBytes > 0 && resolved.sizeBytes + SLACK_BYTES > free) {
                    val message = "Not enough space: need ${gb(resolved.sizeBytes)}, ${gb(free)} free"
                    log("MODEL_DOWNLOAD", "${seed.id}: FAILED: $message")
                    publish(seed, DownloadState.Failed(message))
                    return@launch
                }

                publish(
                    seed,
                    DownloadState.Running(DownloadProgress(store.partialSize(seed), resolved.sizeBytes), source),
                )
                downloader.download(
                    url = resolved.downloadUrl,
                    destination = store.legacyFileFor(seed),
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
                val installedFile = store.legacyFileFor(seed)
                val installedSize = installedFile.length()
                if (resolved.sizeBytes > 0 && installedSize != resolved.sizeBytes) {
                    installedFile.delete()
                    val message = "File corrupted: got $installedSize bytes, expected ${resolved.sizeBytes}"
                    log("MODEL_DOWNLOAD", "${seed.id}: FAILED: $message")
                    publish(seed, DownloadState.Failed(message))
                    return@launch
                }

                if (seed.mmprojFileName != null) downloadMmproj(seed, downloader)

                log("MODEL_DOWNLOAD", "${seed.id}: installed")
                publish(seed, DownloadState.Installed)
            } catch (e: Exception) {
                log("MODEL_DOWNLOAD", "${seed.id}: FAILED: ${e.javaClass.simpleName}: ${e.message}")
                publish(seed, DownloadState.Failed(e.message ?: e.toString()))
            } finally {
                downloaders.remove(seed.id)
            }
        }
    }

    private sealed interface NewChainOutcome {
        data object Installed : NewChainOutcome

        /** Not worth a legacy retry (no space, refused): shown as is. */
        data class Final(val message: String) : NewChainOutcome

        data class Failed(val message: String) : NewChainOutcome
    }

    private fun installViaStore(installation: ModelInstallation, seed: LocalModelSeed, cancel: AtomicBoolean): NewChainOutcome {
        val model = LegacyCatalogMapper.customModel(seed)
        val source = seed.repoIds.first()
        if (seed.isCustom) incompatibility(installation, seed, model)?.let { return NewChainOutcome.Final(it) }
        val result = installation.installer.install(
            LegacyCatalogMapper.CATALOG_ID,
            LegacyCatalogMapper.CATALOG_VERSION,
            model,
            model.variants.single(),
            installation::freeBytes,
            cancel = { cancel.get() },
        ) { progress ->
            if (!cancel.get()) publish(seed, DownloadState.Running(DownloadProgress(progress.transfer.bytesDone, progress.transfer.bytesTotal ?: 0), source))
        }
        return when (result) {
            is InstallResult.Installed -> {
                result.manifest.artifacts.forEach { artifact ->
                    log(
                        "MODEL_DOWNLOAD",
                        "${seed.id}: ${artifact.role.id} installed in the model store from " +
                            "${artifact.source.repo}/${artifact.source.path}@${artifact.source.commit?.take(8)} (${gb(artifact.sizeBytes)}, ${artifact.integrity})",
                    )
                }
                result.manifest.skippedOptional.forEach {
                    log("MMPROJ_DOWNLOAD", "${seed.id}: optional ${it.role.id} skipped (${it.reason}); text-only until it is backfilled")
                }
                NewChainOutcome.Installed
            }
            is InstallResult.AlreadyInstalled -> NewChainOutcome.Installed
            is InstallResult.InsufficientStorage ->
                NewChainOutcome.Final("Not enough space: need ${gb(result.neededBytes)}, ${gb(result.freeBytes)} free")
            is InstallResult.Refused -> NewChainOutcome.Final("${seed.title} is ${result.status.name.lowercase()} in the catalogue")
            is InstallResult.Failed -> NewChainOutcome.Failed("${result.fileName}: ${result.failures.joinToString("; ")}")
        }
    }

    /**
     * A user-added repository's GGUF judged by its own header before the
     * download (bundled seeds are curated, so not probed): an architecture
     * the bundled llama.cpp cannot load used to cost the whole multi-GB
     * download and then fail at load. The reason when it cannot load; null
     * when it can, or when the header could not be read -- that is not a
     * verdict, so the install goes ahead as before.
     */
    private fun incompatibility(installation: ModelInstallation, seed: LocalModelSeed, model: ai.localstudio.model.ModelDefinition): String? {
        val weights = model.variants.single().artifacts.firstOrNull { it.role == ArtifactRoles.WEIGHTS } ?: return null
        val resolved = (installation.resolver.resolve(weights, model.status) as? ArtifactResolution.Resolved)?.artifact ?: return null
        return when (val probe = installation.ggufProbe.probe(resolved)) {
            is GgufProbe.Result.Probed -> when (val verdict = probe.compatibility) {
                is GgufCompatibility.NotLoadable -> {
                    log("MODEL_DOWNLOAD", "${seed.id}: not downloaded -- ${verdict.reason} (${resolved.candidates.first().origin.path}, header read in ${probe.bytesRead} bytes)")
                    "This model cannot run in this app: ${verdict.reason}"
                }
                is GgufCompatibility.Loadable -> {
                    log("MODEL_DOWNLOAD", "${seed.id}: header OK -- ${verdict.architecture}, context ${verdict.contextLength ?: "?"}${verdict.notes.joinToString("") { "; $it" }}")
                    null
                }
            }
            is GgufProbe.Result.Unreadable -> {
                log("MODEL_DOWNLOAD", "${seed.id}: header not checked (${probe.reason}); downloading anyway")
                null
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
            log("MMPROJ_DOWNLOAD", "$fileName not found in any of ${seed.repoIds}")
            return
        }
        val (source, file) = resolved
        publish(seed, DownloadState.Running(DownloadProgress(store.legacyMmprojFileFor(seed).length(), file.sizeBytes), source))
        runCatching {
            downloader.download(
                url = file.downloadUrl,
                destination = store.legacyMmprojFileFor(seed),
                tempFile = store.mmprojPartFor(seed),
            ) { progress -> publish(seed, DownloadState.Running(progress, source)) }
        }.onFailure {
            // A corrupt or half-downloaded projector must not look installed —
            // ModelStore.hasMmproj checks file presence, not validity beyond size.
            store.legacyMmprojFileFor(seed).delete()
            log("MMPROJ_DOWNLOAD", "mmproj download failed for ${seed.id}: ${it.message}")
        }
    }

    fun cancel(seed: LocalModelSeed) {
        cancelled[seed.id]?.set(true)
        downloaders[seed.id]?.cancel()
        jobs[seed.id]?.cancel()
        // The partial file is kept on purpose: the next attempt resumes from
        // it — published as DownloadState.Paused, not a blanket Idle, so the
        // Models screen can say so immediately rather than only after a
        // restart (see stateOf's own fallback for the same file).
        publishStopped(seed)
    }

    private fun publishStopped(seed: LocalModelSeed) {
        val partial = store.partialSize(seed)
        publish(seed, if (partial > 0) DownloadState.Paused(partial) else DownloadState.Idle)
    }

    fun delete(seed: LocalModelSeed) {
        cancel(seed)
        store.delete(seed)
        publish(seed, DownloadState.Idle)
    }

    private fun publish(seed: LocalModelSeed, state: DownloadState) {
        states.value = states.value + (seed.id to state)
    }

    private fun gb(bytes: Long): String = "%.1f GB".format(bytes / 1_000_000_000.0)

    private companion object {
        /** Never fill the disk to the last byte for a model. */
        const val SLACK_BYTES = 500L * 1024 * 1024

        /** How long a failed mmproj backfill attempt sits out before retrying — see [mmprojBackfillCooldownUntil]. */
        const val MMPROJ_BACKFILL_COOLDOWN_MS = 30 * 60 * 1000L
    }
}
