package ai.localstudio.model.install

/**
 * A model family worth watching on the Hub. [searches] are matched against
 * repository ids; [officialAuthors] are the family's own organisations,
 * whose uploads rank first.
 */
data class Lineage(
    val id: String,
    val displayName: String,
    val purpose: Purpose,
    val searches: List<String>,
    val officialAuthors: Set<String> = emptySet(),
    /** Null for a family search (its own GGUFs carry various task tags); set for the catch-all searches. */
    val pipelineTag: String? = null,
) {
    enum class Purpose { CHAT, TRANSLATION }
}

object Lineages {
    /**
     * Translation families first: a repository goes to the first lineage
     * that finds it, so TranslateGemma lands under translation, not Gemma.
     * Each purpose ends with a catch-all of its most downloaded and newest
     * GGUFs, so a family not listed here (yet) is still found.
     */
    val ALL: List<Lineage> = listOf(
        Lineage("hunyuan-mt", "Hunyuan-MT", Lineage.Purpose.TRANSLATION, listOf("Hunyuan-MT", "HY-MT"), setOf("tencent")),
        Lineage("translategemma", "TranslateGemma", Lineage.Purpose.TRANSLATION, listOf("translategemma"), setOf("google")),
        Lineage("translation", "Other translation", Lineage.Purpose.TRANSLATION, emptyList(), pipelineTag = "translation"),
        Lineage("qwen", "Qwen", Lineage.Purpose.CHAT, listOf("Qwen"), setOf("Qwen")),
        Lineage("gemma", "Gemma", Lineage.Purpose.CHAT, listOf("gemma"), setOf("google")),
        Lineage("llama", "Llama", Lineage.Purpose.CHAT, listOf("Llama"), setOf("meta-llama")),
        Lineage("phi", "Phi", Lineage.Purpose.CHAT, listOf("Phi-"), setOf("microsoft")),
        Lineage("mistral", "Mistral", Lineage.Purpose.CHAT, listOf("Mistral", "Ministral"), setOf("mistralai")),
        Lineage("smollm", "SmolLM", Lineage.Purpose.CHAT, listOf("SmolLM"), setOf("HuggingFaceTB")),
        Lineage("lfm", "LFM (Liquid)", Lineage.Purpose.CHAT, listOf("LFM"), setOf("LiquidAI")),
        Lineage("granite", "Granite", Lineage.Purpose.CHAT, listOf("granite"), setOf("ibm-granite")),
        Lineage("popular", "Other popular", Lineage.Purpose.CHAT, emptyList(), pipelineTag = "text-generation"),
    )

    /** Well-known GGUF publishers: ranked after a family's own uploads, before anyone else's. */
    val TRUSTED_QUANTIZERS: Set<String> = setOf("ggml-org", "unsloth", "bartowski", "lmstudio-community", "mradermacher")

    fun byId(id: String): Lineage? = ALL.firstOrNull { it.id == id }
}

/**
 * Discovery by family: each [Lineage] searched by its newest and its most
 * downloaded GGUFs, the results narrowed to [perLineage] worth examining
 * ([shortlist]), each of those through [ModelDiscovery.examine] -- the same
 * size and header checks as before. One family's 400B models cannot crowd
 * another family's 2B out of the list any more, and a release from last
 * week is found before it has piled up downloads.
 */
class LineageDiscovery(
    private val search: HuggingFaceSearch,
    private val discovery: ModelDiscovery,
    private val perLineage: Int = 6,
    private val resultsPerQuery: Int = 20,
) {
    data class Result(
        val lineage: Lineage,
        /** Every repository the searches returned for this family, examined or not. */
        val found: Int,
        val outcomes: List<ModelDiscovery.Outcome>,
        /** Set when every search for this family failed; then [found] is 0. */
        val failure: String? = null,
    ) {
        val candidates: List<ModelDiscovery.Outcome.Candidate> get() = outcomes.filterIsInstance<ModelDiscovery.Outcome.Candidate>()
    }

    fun discover(
        lineage: Lineage,
        quantPriority: List<String>,
        maxModelBytes: Long,
        skip: Set<String>,
        isCancelled: () -> Boolean = { false },
        onOutcome: (ModelDiscovery.Outcome) -> Unit = {},
    ): Result {
        val terms: List<String?> = lineage.searches.ifEmpty { listOf(null) }
        val queries = terms.flatMap { term ->
            ModelSearchQuery.Sort.entries.map { sort ->
                ModelSearchQuery(tags = listOf("gguf"), pipelineTag = lineage.pipelineTag, search = term, limit = resultsPerQuery, sort = sort)
            }
        }
        val repos = mutableListOf<RepoSummary>()
        val failures = mutableListOf<String>()
        for (query in queries) {
            if (isCancelled()) break
            try {
                repos += search.searchModels(query)
            } catch (e: SourceException) {
                failures += "${query.search ?: query.pipelineTag} (${query.sort.apiValue}): ${e.message}"
            }
        }
        if (repos.isEmpty() && failures.isNotEmpty()) return Result(lineage, 0, emptyList(), failures.joinToString("; "))

        val (chosen, dropped) = shortlist(lineage, repos, skip, maxModelBytes, perLineage)
        val outcomes = mutableListOf<ModelDiscovery.Outcome>()
        dropped.forEach { outcomes += it; onOutcome(it) }
        for (repo in chosen) {
            if (isCancelled()) break
            val outcome = discovery.examine(repo, quantPriority, maxModelBytes, skip)
            outcomes += outcome
            onOutcome(outcome)
        }
        return Result(lineage, repos.distinctBy { it.id }.size, outcomes)
    }

    companion object {
        /** Hub task tags of models that do not generate text: never chat or translation candidates. */
        private val NOT_TEXT_GENERATION = setOf(
            "feature-extraction", "sentence-similarity", "text-ranking", "text-classification", "token-classification",
            "automatic-speech-recognition", "text-to-speech", "text-to-audio", "text-to-image", "image-classification",
        )

        // "1.5B", "30B" in "Qwen3-30B-A3B" (the larger number wins), not the "3.2" of "Llama-3.2-1B".
        private val PARAMS = Regex("(?i)(?<![\\d.])(\\d+(?:\\.\\d+)?)b(?![a-z])")

        /**
         * Bytes per parameter for the name check -- deliberately below a
         * real Q4_K_M (~0.6), so only a model clearly too large is dropped
         * by its name; anything closer is examined and judged by its file.
         */
        private const val NAME_CHECK_BYTES_PER_PARAM = 0.5

        /** The largest "<n>B" in [repoId]'s name, in parameters; null when the name says none. */
        fun parametersFromName(repoId: String): Double? =
            PARAMS.findAll(repoId.substringAfter('/')).mapNotNull { it.groupValues[1].toDoubleOrNull() }.maxOrNull()?.times(1e9)

        /** 0 = the family's own upload, 1 = a well-known GGUF publisher, 2 = anyone else. */
        fun sourceTier(lineage: Lineage, repoId: String): Int {
            val author = repoId.substringBefore('/')
            return when {
                lineage.officialAuthors.any { it.equals(author, ignoreCase = true) } -> 0
                Lineages.TRUSTED_QUANTIZERS.any { it.equals(author, ignoreCase = true) } -> 1
                else -> 2
            }
        }

        /**
         * What [repo] is a GGUF of: its `base_model:X` / `base_model:quantized:X`
         * tag when it has one, else itself. `base_model:finetune:X` (and
         * merge/adapter) name what it was *built from* -- a different model,
         * never grouped with X.
         */
        fun baseModelOf(repo: RepoSummary): String {
            val base = repo.tags.firstNotNullOfOrNull { tag ->
                val rest = tag.removePrefix("base_model:").takeIf { tag.startsWith("base_model:") } ?: return@firstNotNullOfOrNull null
                when {
                    ':' !in rest -> rest
                    rest.startsWith("quantized:") -> rest.removePrefix("quantized:")
                    else -> null
                }
            }
            return (base ?: repo.id).lowercase()
        }

        /**
         * Which of [repos] to examine, at most [limit]: not already known
         * ([skip]), a text model, not clearly too large by its name, one per
         * base model (the family's own upload over a known publisher's over
         * anyone else's, then the most downloaded), the family's own and
         * the newest first. The rest that were left out for a reason come
         * back as [ModelDiscovery.Outcome.Dropped], so the log says why.
         */
        fun shortlist(
            lineage: Lineage,
            repos: List<RepoSummary>,
            skip: Set<String>,
            maxModelBytes: Long,
            limit: Int,
        ): Pair<List<RepoSummary>, List<ModelDiscovery.Outcome.Dropped>> {
            val dropped = mutableListOf<ModelDiscovery.Outcome.Dropped>()
            val eligible = repos.distinctBy { it.id }.filter { repo ->
                when {
                    repo.id in skip -> false
                    repo.pipelineTag in NOT_TEXT_GENERATION -> {
                        dropped += ModelDiscovery.Outcome.Dropped(repo, "not a text-generation model (${repo.pipelineTag})")
                        false
                    }
                    (parametersFromName(repo.id) ?: 0.0) * NAME_CHECK_BYTES_PER_PARAM > maxModelBytes -> {
                        val billions = parametersFromName(repo.id)!! / 1e9
                        dropped += ModelDiscovery.Outcome.Dropped(
                            repo,
                            "~${billions.toBigDecimal().stripTrailingZeros().toPlainString()}B parameters by its name: too large for this device, not examined",
                        )
                        false
                    }
                    else -> true
                }
            }
            val best = eligible.groupBy(::baseModelOf).values.map { same ->
                val keep = same.minWith(compareBy<RepoSummary> { sourceTier(lineage, it.id) }.thenByDescending { it.downloads })
                same.filter { it !== keep }.forEach { dropped += ModelDiscovery.Outcome.Dropped(it, "same model as ${keep.id}") }
                keep
            }
            val ordered = best.sortedWith(
                compareBy<RepoSummary> { sourceTier(lineage, it.id) }.thenByDescending { it.createdAt ?: "" },
            )
            return ordered.take(limit) to dropped
        }
    }
}
