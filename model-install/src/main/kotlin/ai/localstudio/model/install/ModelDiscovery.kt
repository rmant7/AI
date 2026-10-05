package ai.localstudio.model.install

import ai.localstudio.model.FileSelector

/** A repository search on the Hub. Implemented over HTTP by the app, faked in tests. */
interface HuggingFaceSearch {
    fun searchModels(query: ModelSearchQuery): List<RepoSummary>
}

/**
 * What to look for: [tags] all required (`gguf` among them for llama.cpp),
 * [pipelineTag] the Hub's task tag (`text-generation`, `translation`, ...),
 * [search] free text in the repository id; [sort] decides which [limit]
 * come back: the most downloaded, or the newest.
 */
data class ModelSearchQuery(
    val tags: List<String> = listOf("gguf"),
    val pipelineTag: String? = null,
    val search: String? = null,
    val limit: Int = 20,
    val sort: Sort = Sort.DOWNLOADS,
) {
    /** [apiValue] is the Hub's own `sort` parameter value. */
    enum class Sort(val apiValue: String) { DOWNLOADS("downloads"), NEWEST("createdAt") }
}

data class RepoSummary(
    val id: String,
    val downloads: Long = 0,
    val likes: Long = 0,
    val tags: List<String> = emptyList(),
    val pipelineTag: String? = null,
    /** Gated repositories need the user's accepted licence and a token; not offered blind. */
    val gated: Boolean = false,
    /** When the repository was created, as the Hub reports it (ISO 8601); null when the listing leaves it out. */
    val createdAt: String? = null,
)

/**
 * Discovery, phase 1: search -> each repository's files at its current
 * commit -> one GGUF by quantization (the same [FileSelection] an install
 * uses) -> size against the device's RAM -> the file's own header
 * ([GgufProbe]). Nothing is installed and nothing is enabled: the result is
 * a list of candidates and why the others were dropped, for a person to
 * look at. Whether a candidate actually loads and answers on this phone is
 * the device step after it.
 */
class ModelDiscovery(
    private val search: HuggingFaceSearch,
    private val hub: HuggingFaceMetadata,
    private val probe: GgufProbe,
) {
    sealed interface Outcome {
        val repo: RepoSummary

        data class Candidate(
            override val repo: RepoSummary,
            val commit: String,
            val file: RepoFile,
            val architecture: String,
            val contextLength: Long?,
            val notes: List<String>,
            /** The vision projector shipped with [file] in the same repository and commit, header-checked; null for a text-only model. */
            val projector: Projector? = null,
        ) : Outcome {
            /** The candidate as one model: main file + its projector, both pinned to [commit]. */
            fun artifact(): ModelArtifact = ModelArtifact(
                main = ModelFile(repo.id, commit, file.path, file.sizeBytes, file.lfsSha256),
                projector = projector?.let { ProjectorFile(ModelFile(repo.id, commit, it.file.path, it.file.sizeBytes, it.file.lfsSha256), it.type) },
            )
        }

        data class Projector(val file: RepoFile, val type: String)

        data class Dropped(override val repo: RepoSummary, val reason: String) : Outcome
    }

    data class Report(val query: ModelSearchQuery, val outcomes: List<Outcome>) {
        val candidates: List<Outcome.Candidate> get() = outcomes.filterIsInstance<Outcome.Candidate>()
    }

    /**
     * [maxModelBytes]: the largest file worth offering (the caller derives it
     * from the device's RAM). [skip]: repositories already known (the bundled
     * catalogue, installed models). [isCancelled] is checked between
     * repositories.
     *
     * [onOutcome] fires the instant each repository is examined -- before
     * this call returns, not after. Examining one repository is up to three
     * sequential HTTP round trips (resolveCommit, listFiles, the header
     * probe), all blocking, with no concurrency: for [ModelSearchQuery.limit]
     * repositories that is a genuinely slow, synchronous call, easily a
     * minute or more on a real connection. A caller that only reads the
     * final [Report] has nothing to show for that whole time and no way to
     * tell "still working" from "stuck" -- this is the seam for live
     * progress (a log line, a counter) instead.
     */
    fun discover(
        query: ModelSearchQuery,
        quantPriority: List<String>,
        maxModelBytes: Long,
        skip: Set<String> = emptySet(),
        isCancelled: () -> Boolean = { false },
        onOutcome: (Outcome) -> Unit = {},
    ): Report {
        val repos = search.searchModels(query)
        val outcomes = mutableListOf<Outcome>()
        for (repo in repos) {
            if (isCancelled()) break
            val outcome = examine(repo, quantPriority, maxModelBytes, skip)
            outcomes += outcome
            onOutcome(outcome)
        }
        return Report(query, outcomes.sortedWith(compareBy<Outcome> { it !is Outcome.Candidate }.thenByDescending { it.repo.downloads }))
    }

    /** One repository through the same steps [discover] takes each search result through. */
    fun examine(repo: RepoSummary, quantPriority: List<String>, maxModelBytes: Long, skip: Set<String> = emptySet()): Outcome {
        if (repo.id in skip) return Outcome.Dropped(repo, "already in the app")
        if (repo.gated) return Outcome.Dropped(repo, "gated (licence must be accepted on Hugging Face)")
        val commit = try {
            hub.resolveCommit(repo.id, "main")
        } catch (e: SourceException) {
            return Outcome.Dropped(repo, "no main branch: ${e.message}")
        }
        val files = try {
            hub.listFiles(repo.id, commit)
        } catch (e: SourceException) {
            return Outcome.Dropped(repo, "file list unavailable: ${e.message}")
        }
        // A vision projector is a GGUF too, and the smallest one in its repository: never the model itself.
        val weights = files.filterNot(::isProjector)
        val file = FileSelection.select(FileSelector.ByQuantization(quantPriority, ".gguf"), weights)
            ?: return Outcome.Dropped(repo, "no single-file GGUF")
        if (file.sizeBytes > maxModelBytes) {
            return Outcome.Dropped(repo, "${file.name}: ${file.sizeBytes / MB} MB, more than this device can hold (${maxModelBytes / MB} MB)")
        }
        val url = ArtifactResolver.resolveUrl(repo.id, commit, file.path)
        return when (val result = probe.probe(url)) {
            is GgufProbe.Result.Unreadable -> Outcome.Dropped(repo, "${file.name}: header unreadable (${result.reason})")
            is GgufProbe.Result.Probed -> when (val verdict = result.compatibility) {
                is GgufCompatibility.NotLoadable -> Outcome.Dropped(repo, "${file.name}: ${verdict.reason}")
                is GgufCompatibility.Loadable -> {
                    val (projector, projectorNote) = projectorFor(repo, commit, files, file, maxModelBytes)
                    Outcome.Candidate(repo, commit, file, verdict.architecture, verdict.contextLength, verdict.notes + listOfNotNull(projectorNote), projector)
                }
            }
        }
    }

    /**
     * The projector that ships with [main]: an mmproj GGUF from the same
     * listing -- the same repository at the same commit -- preferring F16,
     * then BF16, Q8_0, F32 (the precisions publishers ship them in), judged
     * by its own header ([ProjectorCompatibility]) and kept only when model
     * and projector fit this device together. A projector that is not
     * usable never costs the candidate: it stays a text model, with the
     * reason as a note. Several mmproj files for different models in one
     * repository are not told apart here; the device check of the pair is
     * what proves a projector fits its model.
     */
    private fun projectorFor(repo: RepoSummary, commit: String, files: List<RepoFile>, main: RepoFile, maxModelBytes: Long): Pair<Outcome.Projector?, String?> {
        val projectors = files.filter { isProjector(it) && it.name.endsWith(".gguf", ignoreCase = true) }
        if (projectors.isEmpty()) return null to null
        val chosen = projectors.minWith(
            compareBy<RepoFile> { file -> PROJECTOR_PRECISIONS.indexOfFirst { it.containsMatchIn(file.name) }.let { if (it < 0) Int.MAX_VALUE else it } }
                .thenBy { it.sizeBytes },
        )
        if (main.sizeBytes + chosen.sizeBytes > maxModelBytes) {
            return null to "projector ${chosen.name} left out: with it the model needs ${(main.sizeBytes + chosen.sizeBytes) / MB} MB, more than this device can hold (${maxModelBytes / MB} MB)"
        }
        return when (val result = probe.probeProjector(ArtifactResolver.resolveUrl(repo.id, commit, chosen.path))) {
            is GgufProbe.ProjectorResult.Unreadable -> null to "projector ${chosen.name} left out: header unreadable (${result.reason})"
            is GgufProbe.ProjectorResult.Probed -> when (val verdict = result.compatibility) {
                is ProjectorCompatibility.NotUsable -> null to "projector ${chosen.name} left out: ${verdict.reason}"
                is ProjectorCompatibility.Vision -> Outcome.Projector(chosen, verdict.projectorType) to null
            }
        }
    }

    private companion object {
        const val MB = 1024L * 1024

        /** Order of preference among a repository's projector files. */
        val PROJECTOR_PRECISIONS = listOf("f16", "bf16", "q8_0", "f32").map {
            // A whole token: "f16" must not match inside "bf16".
            Regex("(?<![a-z0-9])" + Regex.escape(it) + "(?![a-z0-9])", RegexOption.IGNORE_CASE)
        }

        fun isProjector(file: RepoFile) = file.name.contains("mmproj", ignoreCase = true)
    }
}
