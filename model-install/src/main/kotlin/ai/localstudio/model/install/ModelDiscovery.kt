package ai.localstudio.model.install

import ai.localstudio.model.FileSelector

/** A repository search on the Hub. Implemented over HTTP by the app, faked in tests. */
interface HuggingFaceSearch {
    fun searchModels(query: ModelSearchQuery): List<RepoSummary>
}

/**
 * What to look for: [tags] all required (`gguf` among them for llama.cpp),
 * [pipelineTag] the Hub's task tag (`text-generation`, `translation`, ...),
 * [search] free text; most downloaded first.
 */
data class ModelSearchQuery(
    val tags: List<String> = listOf("gguf"),
    val pipelineTag: String? = null,
    val search: String? = null,
    val limit: Int = 20,
)

data class RepoSummary(
    val id: String,
    val downloads: Long = 0,
    val likes: Long = 0,
    val tags: List<String> = emptyList(),
    val pipelineTag: String? = null,
    /** Gated repositories need the user's accepted licence and a token; not offered blind. */
    val gated: Boolean = false,
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
        ) : Outcome

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
     */
    fun discover(
        query: ModelSearchQuery,
        quantPriority: List<String>,
        maxModelBytes: Long,
        skip: Set<String> = emptySet(),
        isCancelled: () -> Boolean = { false },
    ): Report {
        val repos = search.searchModels(query)
        val outcomes = mutableListOf<Outcome>()
        for (repo in repos) {
            if (isCancelled()) break
            outcomes += examine(repo, quantPriority, maxModelBytes, skip)
        }
        return Report(query, outcomes.sortedWith(compareBy<Outcome> { it !is Outcome.Candidate }.thenByDescending { it.repo.downloads }))
    }

    private fun examine(repo: RepoSummary, quantPriority: List<String>, maxModelBytes: Long, skip: Set<String>): Outcome {
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
        // A vision projector is a GGUF too, and the smallest one in its repository.
        val weights = files.filterNot { it.name.contains("mmproj", ignoreCase = true) }
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
                is GgufCompatibility.Loadable -> Outcome.Candidate(repo, commit, file, verdict.architecture, verdict.contextLength, verdict.notes)
            }
        }
    }

    private companion object {
        const val MB = 1024L * 1024
    }
}
