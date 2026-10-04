package ai.localstudio.app.llama

import ai.localstudio.app.modelinstall.ModelInstallation
import ai.localstudio.app.models.catalog.LegacyCatalogMapper
import ai.localstudio.model.ArtifactRoles
import ai.localstudio.model.ModelDefinition
import ai.localstudio.model.install.AdoptionOutcome
import ai.localstudio.model.install.InstallHealth
import ai.localstudio.model.install.InstallManifest
import ai.localstudio.model.install.LegacyInstallation
import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Where an embedding model's GGUF lives -- private app storage, same
 * reasoning as [ai.localstudio.app.models.ModelStore]: several hundred
 * megabytes fetched over mobile data must not be something the system
 * reclaims on a whim.
 *
 * Phase 3b.3: the first consumer on the new install chain. With an
 * [installation], a model installed through it (or adopted from the legacy
 * directory by [adoptLegacy]) is found in the new store first; the legacy
 * `experimental_embeddings/<id>.gguf` stays the fallback, so whenever the new
 * chain can't help (no network to prove a legacy file, a bug) the model
 * keeps working exactly as before. Every caller -- the memory loader, the
 * Models and Memory screens, the Experimental screen -- goes through
 * [fileFor]/[isInstalled]/[delete], so they all switch together.
 */
class ExperimentalEmbeddingStore(
    private val context: Context,
    val installation: ModelInstallation? = null,
    private val log: (String) -> Unit = {},
    private val baseDir: File = context.filesDir,
) {

    fun directory(): File = File(baseDir, "experimental_embeddings").apply { mkdirs() }

    /** The legacy location -- where the old download code puts the file, and the fallback. */
    fun legacyFileFor(spec: EmbeddingModelSpec): File = File(directory(), "${spec.id}.gguf")

    /** The file to load: the new store's copy when installed and intact there, else the legacy one. */
    fun fileFor(spec: EmbeddingModelSpec): File = storeFile(spec) ?: legacyFileFor(spec)

    fun partFor(spec: EmbeddingModelSpec): File = File(directory(), "${spec.id}.gguf.part")

    fun isInstalled(spec: EmbeddingModelSpec): Boolean =
        storeFile(spec) != null || legacyFileFor(spec).let { it.isFile && it.length() > MIN_PLAUSIBLE_SIZE }

    /** Whether [fileFor] currently points into the new store (for logs and status lines). */
    fun isInNewStore(spec: EmbeddingModelSpec): Boolean = storeFile(spec) != null

    fun installedSize(spec: EmbeddingModelSpec): Long = fileFor(spec).takeIf { it.isFile }?.length() ?: 0

    fun partialSize(spec: EmbeddingModelSpec): Long = partFor(spec).takeIf { it.isFile }?.length() ?: 0

    fun delete(spec: EmbeddingModelSpec) {
        installation?.installed?.uninstall(model(spec).variants.single().id)
        legacyFileFor(spec).delete()
        partFor(spec).delete()
    }

    fun freeSpaceBytes(): Long = directory().freeSpace

    /** The catalogue entry for [spec] -- the same mapping that produced model-catalog/local-models.json. */
    fun model(spec: EmbeddingModelSpec): ModelDefinition = LegacyCatalogMapper.embeddingModel(spec)

    /**
     * Moves a legacy download into the new store -- only once its bytes are
     * proven to be the file the catalogue would install now (sha256 against
     * Hugging Face; see [ai.localstudio.model.install.LegacyMigrator]). Blocking
     * (hashes the file); call off the main thread. Anything short of proof
     * leaves the legacy file exactly where it is, still used via [fileFor].
     *
     * Settled once per process: a file found not to be the catalogue's is not
     * re-hashed on every reload of the model. Only [AdoptionOutcome.Deferred]
     * (no network to prove it) is tried again.
     */
    fun adoptLegacy(spec: EmbeddingModelSpec): AdoptionOutcome? {
        val installation = installation ?: return null
        if (spec.id in adoptionSettled) return null
        val legacy = legacyFileFor(spec)
        if (!legacy.isFile || legacy.length() <= MIN_PLAUSIBLE_SIZE) return null
        val model = model(spec)
        val outcome = installation.migrator.adopt(
            LegacyCatalogMapper.CATALOG_ID,
            LegacyCatalogMapper.CATALOG_VERSION,
            LegacyInstallation(model, model.variants.single(), mapOf(ArtifactRoles.WEIGHTS to legacy), "experimental_embeddings/${legacy.name}"),
        )
        log(
            when (outcome) {
                is AdoptionOutcome.Adopted -> "${spec.title}: legacy file proven (${outcome.manifest.artifacts.single().source.path}@${outcome.manifest.artifacts.single().source.commit?.take(8)}) and moved into the model store"
                is AdoptionOutcome.AlreadyInStore -> "${spec.title}: already in the model store"
                is AdoptionOutcome.NotProven -> "${spec.title}: legacy file not proven, kept and used where it is ($outcome)"
                is AdoptionOutcome.Deferred -> "${spec.title}: adoption deferred, legacy file used for now (${outcome.reason})"
            },
        )
        if (outcome !is AdoptionOutcome.Deferred) adoptionSettled += spec.id
        return outcome
    }

    private val adoptionSettled = ConcurrentHashMap.newKeySet<String>()

    /**
     * Removes legacy downloads of models no longer in [ExperimentalEmbeddingModels.ALL]
     * (Multilingual E5 Small: removed, it never loaded) -- dead weight on disk.
     */
    fun deleteRemovedModels() {
        val known = ExperimentalEmbeddingModels.ALL.map { it.id }.toSet()
        directory().listFiles().orEmpty()
            .filter { it.isFile && (it.name.endsWith(".gguf") || it.name.endsWith(".gguf.part")) }
            .filter { it.name.removeSuffix(".part").removeSuffix(".gguf") !in known }
            .forEach { file ->
                val size = file.length()
                if (file.delete()) log("removed ${file.name} (${size / 1_000_000} MB): its model is no longer offered")
            }
    }

    private fun manifest(spec: EmbeddingModelSpec): InstallManifest? {
        val installed = installation?.installed ?: return null
        return installed.manifest(model(spec).variants.single().id)?.takeIf { installed.health(it) == InstallHealth.Intact }
    }

    private fun storeFile(spec: EmbeddingModelSpec): File? {
        val manifest = manifest(spec) ?: return null
        val weights = manifest.artifacts.firstOrNull { it.role == ArtifactRoles.WEIGHTS } ?: return null
        return installation!!.installed.pathOf(manifest, weights).takeIf { it.isFile }
    }

    private companion object {
        // A truncated download is not a usable model — same floor ModelStore
        // uses, small enough not to reject even the smallest real candidate.
        const val MIN_PLAUSIBLE_SIZE = 1L * 1024 * 1024
    }
}
