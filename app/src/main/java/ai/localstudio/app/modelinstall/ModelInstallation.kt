package ai.localstudio.app.modelinstall

import ai.localstudio.model.install.ArtifactResolver
import ai.localstudio.model.install.InstallLayout
import ai.localstudio.model.install.InstalledVariants
import ai.localstudio.model.install.LegacyMigrator
import ai.localstudio.model.install.ModelInstaller
import ai.localstudio.model.install.TransferEngine
import android.content.Context
import java.io.File

/**
 * The :model-install chain wired to Android: Hugging Face over HTTP, Range
 * downloads, StatFs/ActivityManager. Everything it knows lives on disk under
 * [root] (manifests, staging parts and their identity sidecars), so a fresh
 * instance after a process death picks up exactly where the last one stopped.
 *
 * Phase 3b.3: semantic memory's embedding model (see
 * [ai.localstudio.app.llama.ExperimentalEmbeddingStore]) is the first consumer;
 * every other model still installs through the legacy download classes, into
 * their own directories, untouched by this.
 */
class ModelInstallation(
    context: Context,
    val root: File = File(context.filesDir, ROOT_DIR),
    token: () -> String? = { null },
    hub: ai.localstudio.model.install.HuggingFaceMetadata = HuggingFaceApiClient(token = token),
    transport: ai.localstudio.model.install.HttpTransport = HttpRangeTransport(token),
    attemptsPerSource: Int = 3,
    retryDelayMs: Long = 2_000,
) {
    val probe = AndroidDeviceProbe(context)
    val layout = InstallLayout(root)
    val installed = InstalledVariants(layout)
    val installer = ModelInstaller(layout, ArtifactResolver(hub), TransferEngine(transport, attemptsPerSource, retryDelayMs))

    /** Adopts proven legacy installations (see [LegacyInstallationScanner]); run for the embedding model only so far. */
    val migrator = LegacyMigrator(layout, hub)

    /** Free space for an install under [root], for [ModelInstaller.install]'s `freeBytes`. */
    fun freeBytes(): Long = probe.freeBytes(root)

    companion object {
        /** Separate from every legacy directory (models/, vosk-models/, experimental_embeddings/, ...). */
        const val ROOT_DIR = "model-store"
    }
}
