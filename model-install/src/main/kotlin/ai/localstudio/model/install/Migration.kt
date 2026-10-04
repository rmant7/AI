package ai.localstudio.model.install

import ai.localstudio.model.ArtifactRole
import ai.localstudio.model.ArtifactSource
import ai.localstudio.model.ArtifactSpec
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
 * A legacy installation as the app's scanner found it: which catalogue
 * model and variant the legacy layout says it is (the legacy stores name
 * files after the seed id), and its files by artifact role — a file, or a
 * directory for an archive the legacy code unpacked. [origin] describes
 * where it was found, for records and logs.
 *
 * That mapping is only a claim. [LegacyMigrator] proves or rejects it.
 */
data class LegacyInstallation(
    val model: ModelDefinition,
    val variant: ModelVariant,
    val files: Map<ArtifactRole, File>,
    val origin: String,
)

/**
 * What [LegacyMigrator.migrate] concluded about one legacy installation,
 * kept under `.legacy/`. Never an install manifest: a [PROVEN] record
 * prepares an adoption, an [UNVERIFIED] one just says the app has bytes it
 * cannot vouch for.
 */
@Serializable
data class LegacyRecord(
    val schema: Int = 1,
    val status: String,
    val modelId: ModelId,
    val variantId: VariantId,
    val origin: String,
    val files: List<LegacyFile>,
    /** Why it is not proven; null for [PROVEN]. */
    val reason: String? = null,
    /** One per proven artifact; empty unless [PROVEN]. */
    val proofs: List<ProofRecord> = emptyList(),
    /** Optional artifacts present on disk but not proven — left behind on adoption. */
    val skippedOptional: List<SkippedArtifact> = emptyList(),
    val scannedAtEpochMs: Long,
) {
    companion object {
        /** Identity proven; adoptable as long as the files stay exactly as recorded. */
        const val PROVEN = "proven"

        /** Not a verified install, not "probably fine": unknown bytes the app happens to have. */
        const val UNVERIFIED = "legacy_unverified"
    }
}

@Serializable
data class LegacyFile(
    val role: ArtifactRole,
    val path: String,
    val sizeBytes: Long,
    val isDirectory: Boolean,
    val lastModifiedMs: Long,
)

/** The proof for one artifact: these exact bytes (path, size, mtime, sha256) are this upstream file. */
@Serializable
data class ProofRecord(
    val role: ArtifactRole,
    /** The artifact's file name in the store ([ArtifactSpec.fileName]). */
    val fileName: String,
    val legacyPath: String,
    val sizeBytes: Long,
    val lastModifiedMs: Long,
    val sha256: String,
    val integrity: IntegrityBasis,
    val source: SourceRecord,
)

sealed interface MigrationOutcome {
    /** Identity proven and recorded; nothing on disk was moved — see [LegacyMigrator.adopt]. */
    data class Proven(val record: LegacyRecord) : MigrationOutcome

    /** The store already has this variant intact; nothing was examined. */
    data class AlreadyInStore(val manifest: InstallManifest) : MigrationOutcome

    /** Identity not proven: recorded as legacy/unverified. */
    data class Unproven(val record: LegacyRecord) : MigrationOutcome

    /** Couldn't decide now (network, I/O); nothing written — try again later. */
    data class Deferred(val reason: String) : MigrationOutcome
}

sealed interface AdoptionOutcome {
    /** Moved into the store; the legacy paths no longer exist. */
    data class Adopted(val manifest: InstallManifest) : AdoptionOutcome

    data class AlreadyInStore(val manifest: InstallManifest) : AdoptionOutcome

    /** No valid proof (none recorded, or the files changed and no longer prove out): nothing moved. */
    data class NotProven(val outcome: MigrationOutcome) : AdoptionOutcome

    /** Something failed on the way; every legacy file is back where it was. */
    data class Deferred(val reason: String) : AdoptionOutcome
}

/**
 * Two steps, deliberately apart:
 *
 * **[migrate] proves, and touches nothing.** A legacy file's name is only a
 * claim; identity is proven by bytes:
 * 1. Each artifact's catalogue source is asked which upstream files it would
 *    install *now* — for a selection, the file [FileSelection] picks in each
 *    of its repositories at the current commit; for a fixed Hugging Face
 *    path, that path at the current commit — with the sha256 Hugging Face
 *    reports for it (or the catalogue's own sha256 when it has one).
 * 2. Candidates of a different size are dropped without hashing (size is a
 *    filter, not a proof). No candidate with a known sha256 left → unproven,
 *    and the local file is not even hashed.
 * 3. The local file is hashed; its sha256 must equal a candidate's.
 * A directory (an archive the legacy code unpacked and then deleted) has no
 * bytes left to prove anything with: always unproven. The result is a
 * [LegacyRecord]: [LegacyRecord.PROVEN] with path, size, mtime and sha256 of
 * every proven file, or [LegacyRecord.UNVERIFIED] with the reason.
 *
 * **[adopt] moves, at the moment a consumer switches to the store** — when
 * the legacy code for it no longer runs, so the bytes have one owner at any
 * time. It re-checks size and mtime against the proof (re-proving if either
 * changed), then renames each file into a migration staging directory, writes
 * the manifest and moves the directory into place like any install. If a
 * rename isn't possible (another filesystem) the file is copied and the copy's
 * sha256 checked instead, and the legacy original removed only once the
 * install is in place. Any failure before that puts every renamed file back.
 *
 * Hard links would have avoided the move, but Android denies apps hard links
 * in their own storage (API 29+: AccessDeniedException, seen on API 30).
 */
class LegacyMigrator(
    private val layout: InstallLayout,
    private val hf: HuggingFaceMetadata,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sha256: (File) -> String = Sha256::of,
    /** Atomic rename; false when it can't (e.g. another filesystem) — then copy + verify. */
    private val move: (from: File, to: File) -> Boolean = { from, to -> from.renameTo(to) },
    private val copy: (from: File, to: File) -> Unit = { from, to -> from.copyTo(to, overwrite = true) },
) {
    private val installed = InstalledVariants(layout)

    fun migrate(legacy: LegacyInstallation): MigrationOutcome {
        installed.manifest(legacy.variant.id)?.let { manifest ->
            if (installed.health(manifest) == InstallHealth.Intact) return MigrationOutcome.AlreadyInStore(manifest)
        }
        val proofs = mutableListOf<ProofRecord>()
        val skipped = mutableListOf<SkippedArtifact>()
        for (spec in legacy.variant.artifacts.sortedBy { it.optional }) {
            val file = legacy.files[spec.role]
            val verdict = when {
                file == null -> Verdict.No("no legacy file")
                else -> try {
                    prove(spec, file, legacy.model.status)
                } catch (e: IOException) {
                    return MigrationOutcome.Deferred("${spec.fileName}: ${e.message}")
                }
            }
            when (verdict) {
                is Verdict.Yes -> proofs += verdict.proof
                is Verdict.No ->
                    if (spec.optional) {
                        if (file != null) skipped += SkippedArtifact(spec.role, spec.fileName, "legacy file not proven: ${verdict.reason}")
                    } else {
                        return MigrationOutcome.Unproven(write(record(legacy, LegacyRecord.UNVERIFIED, "${spec.fileName}: ${verdict.reason}")))
                    }
            }
        }
        return MigrationOutcome.Proven(write(record(legacy, LegacyRecord.PROVEN, null, proofs, skipped)))
    }

    fun adopt(catalogId: String, catalogVersion: String, legacy: LegacyInstallation): AdoptionOutcome {
        installed.manifest(legacy.variant.id)?.let { manifest ->
            if (installed.health(manifest) == InstallHealth.Intact) return AdoptionOutcome.AlreadyInStore(manifest)
        }
        var record = record(legacy.variant.id)
        if (record == null || record.status != LegacyRecord.PROVEN || record.proofs.any { changed(it) }) {
            when (val outcome = migrate(legacy)) {
                is MigrationOutcome.Proven -> record = outcome.record
                is MigrationOutcome.AlreadyInStore -> return AdoptionOutcome.AlreadyInStore(outcome.manifest)
                else -> return AdoptionOutcome.NotProven(outcome)
            }
        }
        return try {
            AdoptionOutcome.Adopted(moveIntoStore(catalogId, catalogVersion, legacy, record))
        } catch (e: IOException) {
            AdoptionOutcome.Deferred("adoption failed, legacy files restored: ${e.message}")
        }
    }

    /** Every legacy record — proven and unverified. */
    fun records(): List<LegacyRecord> =
        layout.legacyDir().listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".json") }
            .mapNotNull { runCatching { json.decodeFromString(LegacyRecord.serializer(), it.readText()) }.getOrNull() }
            .sortedBy { it.variantId.id }

    fun record(variant: VariantId): LegacyRecord? =
        recordFile(variant).takeIf { it.isFile }
            ?.let { runCatching { json.decodeFromString(LegacyRecord.serializer(), it.readText()) }.getOrNull() }

    private fun changed(proof: ProofRecord): Boolean {
        val file = File(proof.legacyPath)
        return !file.isFile || file.length() != proof.sizeBytes || file.lastModified() != proof.lastModifiedMs
    }

    private sealed interface Verdict {
        data class Yes(val proof: ProofRecord) : Verdict

        data class No(val reason: String) : Verdict
    }

    private data class Candidate(val sha256: String, val sizeBytes: Long?, val integrity: IntegrityBasis, val source: SourceRecord)

    private fun prove(spec: ArtifactSpec, file: File, status: CatalogStatus): Verdict {
        if (file.isDirectory) return Verdict.No("unpacked contents only, the downloaded archive is gone: no bytes left to prove")
        if (!file.isFile) return Verdict.No("missing")
        val size = file.length()
        val modified = file.lastModified()
        val all = candidates(spec, status)
        val sameSize = all.filter { it.sizeBytes == null || it.sizeBytes == size }
        if (sameSize.isEmpty()) {
            return Verdict.No(
                if (all.isEmpty()) "no upstream file with a known sha256 to compare against"
                else "size $size matches none of ${all.map { "${it.source.path}=${it.sizeBytes}" }}",
            )
        }
        val actual = sha256(file)
        val match = sameSize.firstOrNull { it.sha256 == actual }
            ?: return Verdict.No("sha256 $actual matches none of ${sameSize.map { "${it.source.repo ?: it.source.url}/${it.source.path}@${it.source.commit}" }}")
        return Verdict.Yes(ProofRecord(spec.role, spec.fileName, file.path, size, modified, actual, match.integrity, match.source))
    }

    /** What the catalogue would install for [spec] right now, with a hash to compare against. */
    private fun candidates(spec: ArtifactSpec, status: CatalogStatus): List<Candidate> {
        val exactSize = spec.sizeBytes.takeIf { it > 0 && status in setOf(CatalogStatus.VERIFIED, CatalogStatus.EXPERIMENTAL) }
        val sources = when (val source = spec.source) {
            is ArtifactSource.Alternatives -> source.sources
            else -> listOf(source)
        } + spec.mirrors
        return sources.flatMap { source ->
            when (source) {
                is ArtifactSource.HuggingFaceSelection -> source.repoIds.mapNotNull { repo ->
                    val commit = upstream { hf.resolveCommit(repo, source.revision) } ?: return@mapNotNull null
                    val listing = upstream { hf.listFiles(repo, commit) } ?: return@mapNotNull null
                    val picked = FileSelection.select(source.file, listing) ?: return@mapNotNull null
                    val sha = spec.sha256 ?: picked.lfsSha256 ?: return@mapNotNull null
                    val url = ArtifactResolver.resolveUrl(repo, commit, picked.path)
                    Candidate(sha, picked.sizeBytes.takeIf { it > 0 }, basis(spec), SourceRecord(url, repo, source.revision, commit, picked.path))
                }
                is ArtifactSource.HuggingFace -> {
                    val commit = (if (source.isPinned) source.revision else upstream { hf.resolveCommit(source.repo, source.revision) })
                        ?: return@flatMap emptyList()
                    val directory = source.path.substringBeforeLast('/', "")
                    val listed = upstream { hf.listFiles(source.repo, commit, directory) }?.firstOrNull { it.path == source.path }
                    val sha = spec.sha256 ?: listed?.lfsSha256 ?: return@flatMap emptyList()
                    val url = ArtifactResolver.resolveUrl(source.repo, commit, source.path)
                    listOf(Candidate(sha, exactSize ?: listed?.sizeBytes?.takeIf { it > 0 }, basis(spec), SourceRecord(url, source.repo, source.revision, commit, source.path)))
                }
                is ArtifactSource.DirectUrl -> {
                    val sha = spec.sha256 ?: return@flatMap emptyList()
                    listOf(Candidate(sha, exactSize, IntegrityBasis.CATALOG_SHA256, SourceRecord(source.url)))
                }
                is ArtifactSource.Alternatives -> emptyList()
            }
        }
    }

    /**
     * A repository that is gone, gated or renamed just offers no candidate;
     * a network failure means "can't tell now" and aborts the whole
     * migration of this installation as [MigrationOutcome.Deferred].
     */
    private fun <T> upstream(call: () -> T): T? = try {
        call()
    } catch (e: SourceException) {
        if (e.kind == SourceException.Kind.NETWORK) throw e
        null
    }

    private fun basis(spec: ArtifactSpec) = if (spec.sha256 != null) IntegrityBasis.CATALOG_SHA256 else IntegrityBasis.UPSTREAM_SHA256

    private fun moveIntoStore(catalogId: String, catalogVersion: String, legacy: LegacyInstallation, record: LegacyRecord): InstallManifest {
        val staging = layout.migrationStagingDir(legacy.variant.id)
        staging.deleteRecursively()
        staging.mkdirs()
        val renamed = mutableListOf<Pair<File, File>>() // legacy original -> its place in staging
        val copied = mutableListOf<File>() // legacy originals to delete once the install is in place
        try {
            val artifacts = record.proofs.map { proof ->
                val original = File(proof.legacyPath)
                val target = File(staging, proof.fileName).apply { parentFile?.mkdirs() }
                if (move(original, target)) {
                    renamed += original to target
                    if (target.length() != proof.sizeBytes) throw IOException("${target.name}: size changed in the move")
                } else {
                    copy(original, target)
                    if (sha256(target) != proof.sha256) throw IOException("${target.name}: copy does not match the proven sha256")
                    copied += original
                }
                InstalledArtifact(
                    role = proof.role,
                    fileName = proof.fileName,
                    sizeBytes = proof.sizeBytes,
                    sha256 = proof.sha256,
                    integrity = proof.integrity,
                    source = proof.source,
                    migratedFrom = proof.legacyPath,
                )
            }
            val manifest = InstallManifest(
                catalogId = catalogId,
                catalogVersion = catalogVersion,
                modelId = legacy.model.id,
                variantId = legacy.variant.id,
                installedAtEpochMs = clock(),
                artifacts = artifacts,
                skippedOptional = record.skippedOptional,
            )
            File(staging, InstallManifest.FILE_NAME).writeText(ManifestCodec.encode(manifest))
            layout.promote(staging, layout.variantDir(legacy.variant.id), clock())
            // In place: only now may the legacy side lose anything.
            copied.forEach { it.delete() }
            recordFile(legacy.variant.id).delete()
            return manifest
        } catch (e: IOException) {
            for ((original, target) in renamed.asReversed()) {
                original.parentFile?.mkdirs()
                if (!target.renameTo(original)) target.copyTo(original, overwrite = true)
            }
            staging.deleteRecursively()
            throw e
        }
    }

    private fun record(
        legacy: LegacyInstallation,
        status: String,
        reason: String?,
        proofs: List<ProofRecord> = emptyList(),
        skipped: List<SkippedArtifact> = emptyList(),
    ) = LegacyRecord(
        status = status,
        modelId = legacy.model.id,
        variantId = legacy.variant.id,
        origin = legacy.origin,
        files = legacy.files.map { (role, file) ->
            LegacyFile(
                role,
                file.path,
                if (file.isDirectory) file.walkTopDown().filter { it.isFile }.sumOf { it.length() } else file.length(),
                file.isDirectory,
                file.lastModified(),
            )
        },
        reason = reason,
        proofs = proofs,
        skippedOptional = skipped,
        scannedAtEpochMs = clock(),
    )

    private fun write(record: LegacyRecord): LegacyRecord {
        val out = recordFile(record.variantId)
        out.parentFile.mkdirs()
        out.writeText(json.encodeToString(LegacyRecord.serializer(), record))
        return record
    }

    private fun recordFile(variant: VariantId) = File(layout.legacyDir(), "${layout.variantDir(variant).name}.json")

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }
}
