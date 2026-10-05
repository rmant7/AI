package ai.localstudio.app.models

import ai.localstudio.app.modelinstall.ModelInstallation
import ai.localstudio.app.models.catalog.LegacyCatalogMapper
import ai.localstudio.model.ArtifactRole
import ai.localstudio.model.ArtifactRoles
import ai.localstudio.model.VariantId
import ai.localstudio.model.install.InstallHealth
import ai.localstudio.model.install.InstallManifest
import android.content.Context
import java.io.File

/**
 * Where downloaded models live.
 *
 * Files are named after the seed's stable id, never after the repository's file
 * name: a repo that renames its quant files would otherwise leave the app
 * unable to find a model it has already downloaded, and downloading it again.
 *
 * The directory is app-private storage rather than the cache directory —
 * several gigabytes fetched over mobile data must not be something the system
 * can reclaim on a whim.
 *
 * Phase 3c.1: with an [installation], a model installed through the new
 * chain (commit-pinned, sha256-verified, manifest) is found in the model
 * store first; the legacy `models/<id>.gguf` (and `<id>.mmproj.gguf`) stay
 * the fallback, so every model already on a phone keeps working exactly as
 * before. Legacy files are not moved into the store yet: proving a
 * multi-gigabyte file means hashing it, which needs its own idle-time step.
 */
class ModelStore(
    context: Context,
    val installation: ModelInstallation? = null,
    private val baseDir: File = context.filesDir,
) {

    fun directory(): File = File(baseDir, "models").apply { mkdirs() }

    /** The file to load: the store's copy when installed and intact there, else the legacy one. */
    fun fileFor(seed: LocalModelSeed): File = storeFile(seed, ArtifactRoles.WEIGHTS) ?: legacyFileFor(seed)

    /** Where the legacy download code writes, and the fallback. */
    fun legacyFileFor(seed: LocalModelSeed): File = File(directory(), "${seed.id}.gguf")

    fun partFor(seed: LocalModelSeed): File = File(directory(), "${seed.id}.gguf.part")

    /** A truncated download is not an installed model, hence the size floor. */
    fun isInstalled(seed: LocalModelSeed): Boolean =
        isInNewStore(seed) || legacyFileFor(seed).let { it.isFile && it.length() > MIN_PLAUSIBLE_SIZE }

    /** Whether [fileFor] points into the model store (for logs and status lines). */
    fun isInNewStore(seed: LocalModelSeed): Boolean = intactManifest(seed) != null

    /** The variant the new chain installs [seed] as -- the same mapping as model-catalog/local-models.json. */
    fun variantId(seed: LocalModelSeed): VariantId = VariantId(seed.id + LegacyCatalogMapper.LEGACY_VARIANT_SUFFIX)

    /**
     * Total disk footprint of this model — the main GGUF plus its projector
     * when one is installed. Reporting only the main file's size here left
     * the "Установлена · N ГБ" status understating actual usage by however
     * big the mmproj file was, which is not a rounding error: this model's
     * own projector is roughly a third of the main file's size on top.
     */
    fun installedSize(seed: LocalModelSeed): Long =
        (fileFor(seed).takeIf { it.isFile }?.length() ?: 0) +
            (mmprojFileFor(seed).takeIf { it.isFile }?.length() ?: 0)

    /** Bytes already on disk toward a resumable download: the legacy part, or the new chain's staging. */
    fun partialSize(seed: LocalModelSeed): Long =
        (partFor(seed).takeIf { it.isFile }?.length() ?: 0) + stagingBytes(seed)

    fun delete(seed: LocalModelSeed) {
        installation?.let { it.installed.uninstall(variantId(seed)); it.layout.stagingDir(variantId(seed)).deleteRecursively() }
        legacyFileFor(seed).delete()
        partFor(seed).delete()
        legacyMmprojFileFor(seed).delete()
        mmprojPartFor(seed).delete()
    }

    /**
     * A vision-capable model's projector, downloaded and named separately
     * from its main GGUF — llama.cpp keeps the two apart, and this app
     * mirrors that rather than trying to merge them into one file.
     */
    fun mmprojFileFor(seed: LocalModelSeed): File = storeFile(seed, ArtifactRoles.PROJECTOR) ?: legacyMmprojFileFor(seed)

    /**
     * Also where a projector is backfilled for a store-installed model whose
     * projector was skipped (optional): re-running the install would fetch
     * the multi-gigabyte weights again.
     */
    fun legacyMmprojFileFor(seed: LocalModelSeed): File = File(directory(), "${seed.id}.mmproj.gguf")

    fun mmprojPartFor(seed: LocalModelSeed): File = File(directory(), "${seed.id}.mmproj.gguf.part")

    /**
     * True once a projector is actually on disk for this model, whether the
     * seed declared one or not -- a model whose main GGUF finished but whose
     * (much smaller, best-effort) projector download failed is still usable,
     * just text-only; and a user's own model installed with its projector
     * (a discovered candidate moved in by "Use") can see, though no
     * built-in seed ever said so. What is installed decides, never the id.
     */
    fun hasMmproj(seed: LocalModelSeed): Boolean =
        mmprojFileFor(seed).let { it.isFile && it.length() > 0 }

    /**
     * Files sitting in the models directory that don't belong to any seed
     * this catalog currently knows about — a real, observed case: a model
     * downloaded from a different branch under active development (a
     * `.litertlm` file from a LiteRT-LM/Tensor SDK runtime this build
     * doesn't even compile in) stays on disk exactly as-is across a plain
     * branch/version switch, since nothing about switching branches touches
     * app-private storage. Nothing in this app ever revisits this directory
     * looking for files it doesn't recognize, so without this they would sit
     * there, invisible and undeletable through the app, for as long as the
     * app is installed.
     */
    fun orphanedFiles(knownSeeds: List<LocalModelSeed>): List<File> {
        val known = knownSeeds.flatMap {
            listOf(legacyFileFor(it).name, partFor(it).name, legacyMmprojFileFor(it).name, mmprojPartFor(it).name)
        }.toSet()
        return directory().listFiles()?.filter { it.isFile && it.name !in known }.orEmpty()
    }

    fun freeSpaceBytes(): Long = directory().freeSpace

    /** Which bytes [seed]'s install is (see [InstallManifest.artifactId]); null for a legacy install or one with no recorded source. */
    fun installedArtifact(seed: LocalModelSeed): ai.localstudio.model.install.ArtifactId? = intactManifest(seed)?.artifactId()

    private fun intactManifest(seed: LocalModelSeed): InstallManifest? {
        val installed = installation?.installed ?: return null
        return installed.manifest(variantId(seed))?.takeIf { installed.health(it) == InstallHealth.Intact }
    }

    private fun storeFile(seed: LocalModelSeed, role: ArtifactRole): File? {
        val manifest = intactManifest(seed) ?: return null
        val artifact = manifest.artifacts.firstOrNull { it.role == role } ?: return null
        return installation!!.installed.pathOf(manifest, artifact).takeIf { it.isFile }
    }

    private fun stagingBytes(seed: LocalModelSeed): Long {
        val staging = installation?.layout?.stagingDir(variantId(seed)) ?: return 0
        return staging.listFiles().orEmpty().filter { it.isFile && !it.name.endsWith(".json") }.sumOf { it.length() }
    }

    private companion object {
        const val MIN_PLAUSIBLE_SIZE = 50L * 1024 * 1024
    }
}
