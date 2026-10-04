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
import java.nio.file.Files

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

/** How one artifact's bytes were proven to be a specific upstream file. */
data class Proof(
    val spec: ArtifactSpec,
    val file: File,
    val sha256: String,
    val integrity: IntegrityBasis,
    val source: SourceRecord,
)

/** A legacy installation that could not be proven — kept as a record, never as an installed variant. */
@Serializable
data class LegacyRecord(
    val schema: Int = 1,
    val status: String = STATUS,
    val modelId: ModelId,
    val variantId: VariantId,
    val origin: String,
    val files: List<LegacyFile>,
    val reason: String,
    val scannedAtEpochMs: Long,
) {
    companion object {
        /** Not a verified install, not "probably fine": unknown bytes the app happens to have. */
        const val STATUS = "legacy_unverified"
    }
}

@Serializable
data class LegacyFile(val role: ArtifactRole, val path: String, val sizeBytes: Long, val isDirectory: Boolean)

sealed interface MigrationOutcome {
    /** Proven and adopted into the store (hard links — the legacy files stay where they are, no extra space). */
    data class Imported(val manifest: InstallManifest, val proofs: List<Proof>) : MigrationOutcome

    /** The store already has this variant intact; nothing was examined. */
    data class AlreadyInStore(val manifest: InstallManifest) : MigrationOutcome

    /** Identity not proven: recorded as legacy/unverified, nothing imported. */
    data class Unproven(val record: LegacyRecord) : MigrationOutcome

    /** Couldn't decide now (network, I/O); nothing written — try again later. */
    data class Deferred(val reason: String) : MigrationOutcome
}

/**
 * Adopts legacy installations into the store only when their identity is
 * proven, never on a file name or a size:
 *
 * 1. Each artifact's catalogue source is asked which upstream files it would
 *    install *now* — for a selection, the file [FileSelection] picks in each
 *    of its repositories at the current commit; for a fixed Hugging Face
 *    path, that path at the current commit — with the sha256 Hugging Face
 *    reports for it (or the catalogue's own sha256 when it has one).
 * 2. Candidates of a different size are dropped without hashing (size is a
 *    filter, not a proof). No candidate with a known sha256 left → unproven,
 *    and the local file is not even hashed.
 * 3. The local file is hashed; its sha256 must equal a candidate's. That
 *    binds the bytes to one upstream file at one commit — the identity.
 *
 * A directory (an archive the legacy code unpacked and then deleted) has no
 * bytes left to prove anything with: always unproven.
 *
 * Every mandatory artifact proven → hard-linked into a migration staging
 * directory, manifest written, moved into place like any install. An
 * optional artifact that isn't proven is left out and recorded as skipped.
 * Any mandatory artifact unproven → a [LegacyRecord] under `.legacy/`,
 * nothing else.
 */
class LegacyMigrator(
    private val layout: InstallLayout,
    private val hf: HuggingFaceMetadata,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sha256: (File) -> String = Sha256::of,
    private val link: (existing: File, newLink: File) -> Unit = { existing, newLink ->
        Files.createLink(newLink.toPath(), existing.toPath())
    },
) {
    private val installed = InstalledVariants(layout)

    fun migrate(catalogId: String, catalogVersion: String, legacy: LegacyInstallation): MigrationOutcome {
        installed.manifest(legacy.variant.id)?.let { manifest ->
            if (installed.health(manifest) == InstallHealth.Intact) return MigrationOutcome.AlreadyInStore(manifest)
        }
        val proofs = mutableListOf<Proof>()
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
                        return MigrationOutcome.Unproven(record(legacy, "${spec.fileName}: ${verdict.reason}"))
                    }
            }
        }
        return try {
            MigrationOutcome.Imported(adopt(catalogId, catalogVersion, legacy, proofs, skipped), proofs)
        } catch (e: IOException) {
            MigrationOutcome.Deferred("import failed: ${e.message}")
        }
    }

    /** Legacy installations recorded as unproven. */
    fun records(): List<LegacyRecord> =
        layout.legacyDir().listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".json") }
            .mapNotNull { runCatching { json.decodeFromString(LegacyRecord.serializer(), it.readText()) }.getOrNull() }
            .sortedBy { it.variantId.id }

    private sealed interface Verdict {
        data class Yes(val proof: Proof) : Verdict

        data class No(val reason: String) : Verdict
    }

    private data class Candidate(val sha256: String, val sizeBytes: Long?, val integrity: IntegrityBasis, val source: SourceRecord)

    private fun prove(spec: ArtifactSpec, file: File, status: CatalogStatus): Verdict {
        if (file.isDirectory) return Verdict.No("unpacked contents only, the downloaded archive is gone: no bytes left to prove")
        if (!file.isFile) return Verdict.No("missing")
        val size = file.length()
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
        return Verdict.Yes(Proof(spec, file, actual, match.integrity, match.source))
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

    private fun adopt(
        catalogId: String,
        catalogVersion: String,
        legacy: LegacyInstallation,
        proofs: List<Proof>,
        skipped: List<SkippedArtifact>,
    ): InstallManifest {
        val staging = layout.migrationStagingDir(legacy.variant.id)
        staging.deleteRecursively()
        staging.mkdirs()
        val artifacts = proofs.map { proof ->
            val target = File(staging, proof.spec.fileName)
            target.parentFile?.mkdirs()
            link(proof.file, target)
            // The link is the same inode; a length check guards against a link
            // that silently produced something else (a copy cut short, say).
            if (target.length() != proof.file.length()) throw IOException("linked ${target.name} has the wrong size")
            InstalledArtifact(
                role = proof.spec.role,
                fileName = proof.spec.fileName,
                sizeBytes = target.length(),
                sha256 = proof.sha256,
                integrity = proof.integrity,
                source = proof.source,
                migratedFrom = proof.file.path,
            )
        }
        val manifest = InstallManifest(
            catalogId = catalogId,
            catalogVersion = catalogVersion,
            modelId = legacy.model.id,
            variantId = legacy.variant.id,
            installedAtEpochMs = clock(),
            artifacts = artifacts,
            skippedOptional = skipped,
        )
        File(staging, InstallManifest.FILE_NAME).writeText(ManifestCodec.encode(manifest))
        layout.promote(staging, layout.variantDir(legacy.variant.id), clock())
        recordFile(legacy.variant.id).delete()
        return manifest
    }

    private fun record(legacy: LegacyInstallation, reason: String): LegacyRecord {
        val record = LegacyRecord(
            modelId = legacy.model.id,
            variantId = legacy.variant.id,
            origin = legacy.origin,
            files = legacy.files.map { (role, file) ->
                LegacyFile(role, file.path, if (file.isDirectory) file.walkTopDown().filter { it.isFile }.sumOf { it.length() } else file.length(), file.isDirectory)
            },
            reason = reason,
            scannedAtEpochMs = clock(),
        )
        val out = recordFile(legacy.variant.id)
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
