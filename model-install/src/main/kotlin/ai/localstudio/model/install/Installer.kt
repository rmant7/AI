package ai.localstudio.model.install

import ai.localstudio.model.ArtifactRole
import ai.localstudio.model.CatalogStatus
import ai.localstudio.model.ModelDefinition
import ai.localstudio.model.ModelId
import ai.localstudio.model.ModelVariant
import ai.localstudio.model.VariantId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/**
 * What is installed in one variant directory and where it came from —
 * written last, so a directory without one is not an installed variant.
 */
@Serializable
data class InstallManifest(
    val schema: Int = SCHEMA,
    val catalogId: String,
    val catalogVersion: String,
    val modelId: ModelId,
    val variantId: VariantId,
    val installedAtEpochMs: Long,
    val artifacts: List<InstalledArtifact>,
    val skippedOptional: List<SkippedArtifact> = emptyList(),
) {
    val roles: Set<ArtifactRole> get() = artifacts.mapTo(linkedSetOf()) { it.role }

    val sizesByRole: Map<ArtifactRole, Long> get() = artifacts.associate { it.role to (it.unpackedBytes ?: it.sizeBytes) }

    companion object {
        const val SCHEMA = 1
        const val FILE_NAME = "install.json"
    }
}

@Serializable
data class InstalledArtifact(
    val role: ArtifactRole,
    val fileName: String,
    /** Size and sha256 of the file as downloaded (the archive, for one that was unpacked). */
    val sizeBytes: Long,
    val sha256: String,
    val integrity: IntegrityBasis,
    val source: SourceRecord,
    /** For an archive: the directory it was unpacked into (the archive itself is not kept) and the bytes unpacked. */
    val unpackedDir: String? = null,
    val unpackedBytes: Long? = null,
    /** Set when this file was adopted from a legacy installation instead of downloaded: the legacy path it was linked from. */
    val migratedFrom: String? = null,
)

/** An optional artifact (a vision projector, say) that couldn't be installed; the variant works without it. */
@Serializable
data class SkippedArtifact(val role: ArtifactRole, val fileName: String, val reason: String)

/**
 * The on-disk layout under one root:
 *
 *     <root>/<variant id>/            an installed variant: its files + install.json
 *     <root>/.staging/<variant id>/   an install in progress — kept across restarts so it resumes
 *     <root>/.trash/                  a replaced variant on its way out
 *     <root>/.legacy/                 legacy installations found but not proven (records only)
 *
 * A variant directory only ever appears complete: it is renamed into place
 * from staging after everything in it was verified.
 */
class InstallLayout(val root: File) {

    fun variantDir(variant: VariantId): File = File(root, segment(variant.id))

    fun stagingDir(variant: VariantId): File = File(File(root, STAGING), segment(variant.id))

    fun trashDir(): File = File(root, TRASH)

    /**
     * Moves a finished [staging] directory into place as [destination]. An
     * existing destination is renamed aside first and restored if the move
     * fails, so a variant directory is always either the old one or the new
     * one, never half of each.
     */
    fun promote(staging: File, destination: File, nowMs: Long) {
        if (destination.exists()) {
            val trash = File(trashDir(), destination.name + "-" + nowMs)
            trash.parentFile.mkdirs()
            if (!destination.renameTo(trash)) throw IOException("cannot move the old ${destination.name} aside")
            if (!staging.renameTo(destination)) {
                trash.renameTo(destination)
                throw IOException("cannot move ${staging.name} into place")
            }
            trash.deleteRecursively()
        } else {
            destination.parentFile?.mkdirs()
            if (!staging.renameTo(destination)) throw IOException("cannot move ${staging.name} into place")
        }
    }

    /** Where migration assembles a variant — apart from [stagingDir], whose download parts it must never touch. */
    fun migrationStagingDir(variant: VariantId): File = File(File(root, STAGING), segment(variant.id) + ".migrate")

    /** Records of legacy installations that could not be proven (see [LegacyMigrator]). */
    fun legacyDir(): File = File(root, LEGACY)

    private fun segment(id: String): String {
        require(id.isNotBlank() && '/' in id == false && '\\' in id == false && id != "." && id != ".." && !id.startsWith(".")) {
            "variant id '$id' is not a safe directory name"
        }
        return id
    }

    companion object {
        const val STAGING = ".staging"
        const val TRASH = ".trash"
        const val LEGACY = ".legacy"
    }
}

data class InstallProgress(val artifact: ArtifactRole, val fileName: String, val transfer: TransferProgress)

sealed interface InstallResult {
    data class Installed(val manifest: InstallManifest, val log: List<SourceFailure>) : InstallResult

    /** Already installed and intact: nothing was resolved or downloaded. Pass `force` to reinstall anyway. */
    data class AlreadyInstalled(val manifest: InstallManifest) : InstallResult

    /** Not attempted: the catalog says this model must not be newly installed. */
    data class Refused(val status: CatalogStatus) : InstallResult

    data class InsufficientStorage(val neededBytes: Long, val freeBytes: Long) : InstallResult

    /** A required artifact couldn't be resolved or downloaded; staging is kept so a retry resumes. */
    data class Failed(val fileName: String, val failures: List<SourceFailure>) : InstallResult
}

/**
 * Installs one variant: resolve → admit storage → download and verify each
 * artifact into staging → unpack archives → write the manifest → move the
 * finished directory into place. Optional artifacts are best-effort, like
 * the legacy projector download: their failure is recorded in the manifest,
 * the variant is installed without them.
 *
 * Synchronous and blocking — the caller decides the thread and the Android
 * job type (a user-initiated data transfer job on API 34+).
 */
class ModelInstaller(
    private val layout: InstallLayout,
    private val resolver: ArtifactResolver,
    private val transfer: TransferEngine,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    fun install(
        catalogId: String,
        catalogVersion: String,
        model: ModelDefinition,
        variant: ModelVariant,
        freeBytes: () -> Long,
        cancel: CancellationSignal = CancellationSignal.NONE,
        force: Boolean = false,
        progress: (InstallProgress) -> Unit = {},
    ): InstallResult {
        require(variant in model.variants) { "${variant.id} is not a variant of ${model.id}" }
        // Decided from disk alone — the manifest and the files — never from memory,
        // so a run after a crash or a restart sees exactly what is there.
        if (!force) {
            val existing = InstalledVariants(layout)
            existing.manifest(variant.id)?.let { manifest ->
                if (existing.health(manifest) == InstallHealth.Intact) return InstallResult.AlreadyInstalled(manifest)
            }
        }
        if (model.status == CatalogStatus.DEPRECATED || model.status == CatalogStatus.WITHDRAWN) {
            return InstallResult.Refused(model.status)
        }
        val staging = layout.stagingDir(variant.id).apply { mkdirs() }
        val log = mutableListOf<SourceFailure>()
        val skipped = mutableListOf<SkippedArtifact>()

        // Mandatory artifacts first: a missing optional one must never cost the variant itself.
        val ordered = variant.artifacts.sortedBy { it.optional }
        val resolved = mutableListOf<ResolvedArtifact>()
        for (resolution in ordered.map { resolver.resolve(it, model.status) }) {
            log += resolution.failures
            when (resolution) {
                is ArtifactResolution.Resolved -> resolved += resolution.artifact
                is ArtifactResolution.Unresolved ->
                    if (resolution.spec.optional) {
                        skipped += SkippedArtifact(resolution.spec.role, resolution.spec.fileName, resolution.failures.joinToString("; "))
                    } else {
                        return InstallResult.Failed(resolution.spec.fileName, resolution.failures)
                    }
            }
        }

        val admission = ResourceAdmission.storage(stillNeeded(resolved, staging), freeBytes())
        if (admission is Admission.Deny) return InstallResult.InsufficientStorage(admission.neededBytes, admission.availableBytes)

        val installed = mutableListOf<InstalledArtifact>()
        for (artifact in resolved) {
            val spec = artifact.spec
            val target = File(staging, spec.fileName)
            val unpackDir = spec.unpack?.let { File(staging, Unpacker.directoryNameFor(spec.fileName)) }
            val outcome = transfer.download(artifact, target, cancel) { progress(InstallProgress(spec.role, spec.fileName, it)) }
            when (outcome) {
                is TransferOutcome.Failed -> {
                    log += outcome.failures
                    if (spec.optional) {
                        skipped += SkippedArtifact(spec.role, spec.fileName, outcome.failures.joinToString("; "))
                        continue
                    }
                    return InstallResult.Failed(spec.fileName, outcome.failures)
                }
                is TransferOutcome.Done -> {
                    log += outcome.failures
                    val file = outcome.file
                    var unpacked: Long? = null
                    if (spec.unpack != null && unpackDir != null) {
                        unpacked = Unpacker.unpack(target, spec.unpack!!, unpackDir, cancel)
                        target.delete()
                    }
                    installed += InstalledArtifact(
                        role = spec.role,
                        fileName = spec.fileName,
                        sizeBytes = file.sizeBytes,
                        sha256 = file.sha256,
                        integrity = file.integrity,
                        source = file.candidate.origin,
                        unpackedDir = unpackDir?.name,
                        unpackedBytes = unpacked,
                    )
                }
            }
        }

        val manifest = InstallManifest(
            catalogId = catalogId,
            catalogVersion = catalogVersion,
            modelId = model.id,
            variantId = variant.id,
            installedAtEpochMs = clock(),
            artifacts = installed,
            skippedOptional = skipped,
        )
        File(staging, InstallManifest.FILE_NAME).writeText(ManifestCodec.encode(manifest))
        layout.promote(staging, layout.variantDir(variant.id), clock())
        return InstallResult.Installed(manifest, log)
    }

    /** Bytes still to be written: downloads not yet in staging plus every unpacked size; null if any size is unknown. */
    private fun stillNeeded(resolved: List<ResolvedArtifact>, staging: File): Long? {
        var total = 0L
        for (artifact in resolved) {
            val planned = artifact.plannedSizeBytes
            if (planned <= 0) {
                if (artifact.spec.optional) continue
                return null
            }
            val target = File(staging, artifact.spec.fileName)
            val have = when {
                target.isFile -> target.length()
                else -> File(target.path + ".part").takeIf { it.isFile }?.length() ?: 0L
            }
            total += (planned - have).coerceAtLeast(0)
            total += artifact.spec.unpack?.unpackedSizeBytes ?: 0L
        }
        return total
    }

}

object ManifestCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    fun encode(manifest: InstallManifest): String = json.encodeToString(InstallManifest.serializer(), manifest)

    fun decode(text: String): InstallManifest? = runCatching { json.decodeFromString(InstallManifest.serializer(), text) }
        .getOrNull()
        ?.takeIf { it.schema <= InstallManifest.SCHEMA }
}

/** The state of one installed variant directory, checked cheaply (presence and sizes, not hashes). */
sealed interface InstallHealth {
    data object Intact : InstallHealth

    data class Damaged(val problems: List<String>) : InstallHealth

    /**
     * Only from [InstalledVariants.verifyHashes]: every kept file re-hashed
     * fine and the structure is intact, but [unpackedDirs] hold unpacked
     * archive contents that have no hashes to check against.
     */
    data class UnverifiableContents(val unpackedDirs: List<String>) : InstallHealth
}

/** Reads what [layout] holds: installed variants, their health, uninstalling. */
class InstalledVariants(private val layout: InstallLayout) {

    fun all(): List<InstallManifest> =
        layout.root.listFiles().orEmpty()
            .filter { it.isDirectory && !it.name.startsWith(".") }
            .mapNotNull { dir -> File(dir, InstallManifest.FILE_NAME).takeIf { it.isFile }?.let { ManifestCodec.decode(it.readText()) } }
            .sortedBy { it.variantId.id }

    fun manifest(variant: VariantId): InstallManifest? =
        File(layout.variantDir(variant), InstallManifest.FILE_NAME).takeIf { it.isFile }?.let { ManifestCodec.decode(it.readText()) }

    /** Directory of an installed artifact: the file itself, or its unpacked directory. */
    fun pathOf(manifest: InstallManifest, artifact: InstalledArtifact): File =
        File(layout.variantDir(manifest.variantId), artifact.unpackedDir ?: artifact.fileName)

    /**
     * Cheap check — presence and sizes, no hashing:
     * - a kept file must exist with exactly the size it was downloaded at;
     * - an unpacked archive's directory must exist and hold exactly the bytes
     *   unpacking wrote ([InstalledArtifact.unpackedBytes]) — a deleted or
     *   truncated file inside it shows up, a same-size edit does not.
     */
    fun health(manifest: InstallManifest): InstallHealth {
        val problems = buildList {
            for (artifact in manifest.artifacts) {
                val path = pathOf(manifest, artifact)
                if (artifact.unpackedDir != null) {
                    if (!path.isDirectory) {
                        add("${artifact.unpackedDir}: missing")
                    } else {
                        val bytes = path.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                        if (artifact.unpackedBytes != null && bytes != artifact.unpackedBytes) {
                            add("${artifact.unpackedDir}: $bytes bytes, unpacked ${artifact.unpackedBytes}")
                        }
                    }
                } else {
                    when {
                        !path.isFile -> add("${artifact.fileName}: missing")
                        path.length() != artifact.sizeBytes -> add("${artifact.fileName}: ${path.length()} bytes, expected ${artifact.sizeBytes}")
                    }
                }
            }
        }
        return if (problems.isEmpty()) InstallHealth.Intact else InstallHealth.Damaged(problems)
    }

    /**
     * Full re-hash of every kept file against the sha256 recorded at
     * download — slow, for an explicit "verify" action.
     *
     * An unpacked archive is not re-hashed: its recorded sha256 is that of
     * the archive as downloaded (verified then, deleted after unpacking), and
     * no per-file hashes of the unpacked contents exist. For those this
     * proves nothing beyond [health]; it reports them as unverifiable rather
     * than silently as intact.
     */
    fun verifyHashes(manifest: InstallManifest): InstallHealth {
        val structural = health(manifest)
        if (structural is InstallHealth.Damaged) return structural
        val problems = manifest.artifacts.filter { it.unpackedDir == null }.mapNotNull { artifact ->
            val file = pathOf(manifest, artifact)
            when {
                !file.isFile -> "${artifact.fileName}: missing"
                Sha256.of(file) != artifact.sha256 -> "${artifact.fileName}: sha256 changed"
                else -> null
            }
        }
        if (problems.isNotEmpty()) return InstallHealth.Damaged(problems)
        val unverifiable = manifest.artifacts.mapNotNull { it.unpackedDir }
        return if (unverifiable.isEmpty()) InstallHealth.Intact else InstallHealth.UnverifiableContents(unverifiable)
    }

    fun uninstall(variant: VariantId): Boolean {
        val dir = layout.variantDir(variant)
        layout.stagingDir(variant).deleteRecursively()
        if (!dir.exists()) return false
        val trash = File(layout.trashDir(), dir.name + "-uninstall-" + System.nanoTime())
        trash.parentFile.mkdirs()
        // Rename first: the directory stops being an installed variant atomically, the slow delete follows.
        return if (dir.renameTo(trash)) trash.deleteRecursively() else dir.deleteRecursively()
    }
}
