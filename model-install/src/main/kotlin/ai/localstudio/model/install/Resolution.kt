package ai.localstudio.model.install

import ai.localstudio.model.ArtifactSource
import ai.localstudio.model.ArtifactSpec
import ai.localstudio.model.CatalogStatus
import ai.localstudio.model.ModelDefinition
import ai.localstudio.model.ModelVariant
import kotlinx.serialization.Serializable
import java.net.URLEncoder

/** How much a downloaded file's bytes can be checked, strongest first. */
@Serializable
enum class IntegrityBasis {
    /** sha256 from the catalog: the bytes are the ones the catalog vouches for. */
    CATALOG_SHA256,

    /**
     * sha256 Hugging Face reports for the file at the resolved commit: proves
     * the transfer delivered what the repository holds, says nothing about
     * whether those bytes are good — the catalog vouched for nothing.
     */
    UPSTREAM_SHA256,

    /** Only the exact size is known. */
    SIZE_ONLY,

    /** Nothing to check against; the computed sha256 is still recorded. */
    NONE,
}

/** Where an installed file came from, recorded in the install manifest. */
@Serializable
data class SourceRecord(
    val url: String,
    val repo: String? = null,
    val requestedRevision: String? = null,
    /** The commit actually downloaded from — a branch like "main" is resolved to one before downloading. */
    val commit: String? = null,
    val path: String? = null,
)

/**
 * One concrete URL to try for an artifact, with what its bytes must match.
 * [expectedSizeBytes] is exact or null — a catalog's size estimate for an
 * UNVERIFIED entry is never used here.
 */
data class DownloadCandidate(
    val url: String,
    val expectedSizeBytes: Long?,
    val expectedSha256: String?,
    val origin: SourceRecord,
) {
    fun integrity(fromCatalog: Boolean): IntegrityBasis = when {
        expectedSha256 != null && fromCatalog -> IntegrityBasis.CATALOG_SHA256
        expectedSha256 != null -> IntegrityBasis.UPSTREAM_SHA256
        expectedSizeBytes != null -> IntegrityBasis.SIZE_ONLY
        else -> IntegrityBasis.NONE
    }
}

/** [candidates]: never empty, in the order to try them. */
data class ResolvedArtifact(val spec: ArtifactSpec, val candidates: List<DownloadCandidate>) {
    val integrity: IntegrityBasis get() = candidates.first().integrity(fromCatalog = spec.sha256 != null)

    /** Best known size for planning: exact when resolved, else the catalog's estimate (0 = unknown). */
    val plannedSizeBytes: Long get() = candidates.first().expectedSizeBytes ?: spec.sizeBytes
}

data class SourceFailure(val source: String, val kind: SourceException.Kind, val message: String) {
    override fun toString(): String = "$source: $message"
}

sealed interface ArtifactResolution {
    val spec: ArtifactSpec
    val failures: List<SourceFailure>

    /** [failures]: sources that were tried and skipped on the way — kept for the log. */
    data class Resolved(val artifact: ResolvedArtifact, override val failures: List<SourceFailure>) : ArtifactResolution {
        override val spec: ArtifactSpec get() = artifact.spec
    }

    data class Unresolved(override val spec: ArtifactSpec, override val failures: List<SourceFailure>) : ArtifactResolution
}

/**
 * Turns each artifact of a variant into concrete download candidates.
 *
 * - A Hugging Face source on a branch is resolved to the commit the branch
 *   points at now, and downloaded from that commit: every retry and resume
 *   of one install then reads the same bytes even if the branch moves.
 * - When the catalog has no sha256, the one Hugging Face reports for the
 *   file at that commit (LFS) becomes the transfer check — weaker than a
 *   catalog hash ([IntegrityBasis.UPSTREAM_SHA256]), much better than none.
 * - When the catalog *has* a sha256 and Hugging Face reports a different one
 *   for that commit, the source is rejected before a byte is downloaded.
 * - A [ArtifactSource.HuggingFaceSelection] tries its repositories in order
 *   and picks a file with [FileSelection]; a network failure stops the
 *   search (every repository is on the same host), any other failure moves
 *   on to the next repository — the legacy behaviour.
 * - [ArtifactSpec.mirrors] and [ArtifactSource.Alternatives] each become
 *   further candidates in order; the transfer decides which one answers.
 *
 * A catalog's size for an UNVERIFIED entry is an estimate and is never used
 * as an expected size; for VERIFIED/EXPERIMENTAL it is exact.
 */
class ArtifactResolver(private val hf: HuggingFaceMetadata) {

    fun resolve(model: ModelDefinition, variant: ModelVariant): List<ArtifactResolution> =
        variant.artifacts.map { resolve(it, model.status) }

    fun resolve(spec: ArtifactSpec, status: CatalogStatus): ArtifactResolution {
        val exactSize = spec.sizeBytes.takeIf { it > 0 && status in EXACT_SIZE_STATUSES }
        val failures = mutableListOf<SourceFailure>()
        val candidates = mutableListOf<DownloadCandidate>()

        val primary = spec.source
        val sources: List<ArtifactSource> = when (primary) {
            is ArtifactSource.Alternatives -> primary.sources
            else -> listOf(primary)
        } + spec.mirrors

        for (source in sources) {
            val candidate = try {
                when (source) {
                    is ArtifactSource.HuggingFace -> fixedHuggingFace(source, spec.sha256, exactSize)
                    is ArtifactSource.DirectUrl -> DownloadCandidate(source.url, exactSize, spec.sha256, SourceRecord(source.url))
                    is ArtifactSource.HuggingFaceSelection -> selection(source, failures)
                    is ArtifactSource.Alternatives -> throw SourceException(SourceException.Kind.OTHER, "nested alternatives")
                }
            } catch (e: SourceException) {
                failures += SourceFailure(describe(source), e.kind, e.message ?: e.kind.name)
                null
            }
            if (candidate != null) candidates += candidate
        }
        return if (candidates.isEmpty()) {
            ArtifactResolution.Unresolved(spec, failures)
        } else {
            ArtifactResolution.Resolved(ResolvedArtifact(spec, candidates), failures)
        }
    }

    private fun fixedHuggingFace(source: ArtifactSource.HuggingFace, catalogSha: String?, exactSize: Long?): DownloadCandidate {
        val commit = if (source.isPinned) source.revision else hf.resolveCommit(source.repo, source.revision)
        var expectedSha = catalogSha
        var expectedSize = exactSize
        // A pinned commit with a catalog hash needs no lookup: verification after
        // the download catches any difference. Otherwise ask what the commit holds.
        if (!(source.isPinned && catalogSha != null)) {
            val directory = source.path.substringBeforeLast('/', missingDelimiterValue = "")
            val file = hf.listFiles(source.repo, commit, directory).firstOrNull { it.path == source.path }
                ?: throw SourceException(SourceException.Kind.NOT_FOUND, "${source.path} not found at $commit")
            if (catalogSha != null && file.lfsSha256 != null && file.lfsSha256 != catalogSha) {
                throw SourceException(SourceException.Kind.OTHER, "upstream sha256 ${file.lfsSha256} differs from the catalog's $catalogSha")
            }
            if (expectedSha == null) expectedSha = file.lfsSha256
            if (expectedSize == null && file.sizeBytes > 0) expectedSize = file.sizeBytes
        }
        return DownloadCandidate(
            url = resolveUrl(source.repo, commit, source.path),
            expectedSizeBytes = expectedSize,
            expectedSha256 = expectedSha,
            origin = SourceRecord(resolveUrl(source.repo, commit, source.path), source.repo, source.revision, commit, source.path),
        )
    }

    private fun selection(source: ArtifactSource.HuggingFaceSelection, failures: MutableList<SourceFailure>): DownloadCandidate {
        for (repo in source.repoIds) {
            try {
                val commit = hf.resolveCommit(repo, source.revision)
                val file = FileSelection.select(source.file, hf.listFiles(repo, commit))
                    ?: throw SourceException(SourceException.Kind.NOT_FOUND, "no file matches ${source.file}")
                val url = resolveUrl(repo, commit, file.path)
                return DownloadCandidate(
                    url = url,
                    expectedSizeBytes = file.sizeBytes.takeIf { it > 0 },
                    expectedSha256 = file.lfsSha256,
                    origin = SourceRecord(url, repo, source.revision, commit, file.path),
                )
            } catch (e: SourceException) {
                if (e.kind == SourceException.Kind.NETWORK) throw e
                failures += SourceFailure("hf:$repo@${source.revision}", e.kind, e.message ?: e.kind.name)
            }
        }
        throw SourceException(SourceException.Kind.NOT_FOUND, "none of ${source.repoIds} offered a file")
    }

    private fun describe(source: ArtifactSource): String = when (source) {
        is ArtifactSource.HuggingFace -> "hf:${source.repo}@${source.revision}/${source.path}"
        is ArtifactSource.HuggingFaceSelection -> "hf-selection:${source.repoIds}@${source.revision}"
        is ArtifactSource.DirectUrl -> source.url
        is ArtifactSource.Alternatives -> "alternatives"
    }

    companion object {
        private val EXACT_SIZE_STATUSES = setOf(CatalogStatus.VERIFIED, CatalogStatus.EXPERIMENTAL)

        fun resolveUrl(repo: String, commit: String, path: String): String =
            "https://huggingface.co/$repo/resolve/$commit/" + path.split('/').joinToString("/") {
                URLEncoder.encode(it, "UTF-8").replace("+", "%20")
            }
    }
}
