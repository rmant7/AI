package ai.localstudio.core.memory

/**
 * Decides what from a finished exchange is worth keeping.
 *
 * Extraction is a model call in any serious implementation, which is why it is
 * an interface: on a phone that call is background work, not something that
 * runs synchronously after every answer.
 */
fun interface MemoryExtractor {
    suspend fun extract(conversationId: String, workingMemory: List<MemoryItem>): List<MemoryItem>
}

/**
 * A memory store with no external dependencies.
 *
 * Retrieval is lexical: shared-term overlap, weighted by scope and recency. It
 * is not as good as embeddings, and it is not meant to be — it exists so the
 * rest of the system can be built, tested and demonstrated before any memory
 * backend is chosen, and so that swapping one in later is an implementation
 * change behind [MemoryProvider] rather than a rewrite.
 */
class InMemoryMemoryProvider(
    private val extractor: MemoryExtractor = PromoteWorkingMemory,
    private val clock: () -> Long = System::currentTimeMillis,
) : MemoryProvider {

    private val items = LinkedHashMap<String, MemoryItem>()
    private var counter = 0L

    override suspend fun search(query: MemoryQuery): List<MemoryItem> {
        val terms = tokenize(query.text)
        if (terms.isEmpty()) return emptyList()

        val newest = items.values.maxOfOrNull { it.createdAt } ?: return emptyList()
        val oldest = items.values.minOfOrNull { it.createdAt } ?: newest
        val span = (newest - oldest).coerceAtLeast(1)

        return items.values
            .filter { it.scope in query.scopes }
            .filter { item -> query.metadataFilter.all { (k, v) -> item.metadata[k] == v } }
            .mapNotNull { item ->
                val overlap = overlap(terms, tokenize(item.text))
                if (overlap == 0.0) return@mapNotNull null
                // Recency applies only to time-bound memories: a preference
                // stated a month ago is exactly as true as one stated today.
                val recency = if (item.scope == MemoryScope.SEMANTIC) {
                    0.0
                } else {
                    (item.createdAt - oldest).toDouble() / span
                }
                item.copy(relevance = (overlap + recency * RECENCY_WEIGHT) * scopeWeight(item.scope))
            }
            .sortedWith(compareByDescending<MemoryItem> { it.relevance ?: 0.0 }.thenBy { it.id })
            .take(query.limit)
    }

    override suspend fun remember(text: String, scope: MemoryScope, metadata: Map<String, String>): String {
        val id = "mem-${++counter}"
        items[id] = MemoryItem(id, text.trim(), scope, clock(), metadata = metadata)
        return id
    }

    override suspend fun forget(id: String) {
        items.remove(id)
    }

    /**
     * Turns this conversation's working memory into durable memories and clears
     * the working set — working memory that outlives its conversation is just a
     * leak with a nicer name.
     */
    override suspend fun consolidate(conversationId: String): List<MemoryItem> {
        val working = items.values.filter {
            it.scope == MemoryScope.WORKING && it.metadata[CONVERSATION_KEY] == conversationId
        }
        if (working.isEmpty()) return emptyList()

        val extracted = extractor.extract(conversationId, working)
        working.forEach { items.remove(it.id) }
        return extracted.map { item ->
            val id = "mem-${++counter}"
            val stored = item.copy(id = id, createdAt = clock())
            items[id] = stored
            stored
        }
    }

    fun all(): List<MemoryItem> = items.values.toList()

    private fun tokenize(text: String): Set<String> =
        text.lowercase()
            .split(NON_WORD)
            .filter { it.length >= MIN_TERM_LENGTH }
            .toSet()

    /** Jaccard-style overlap, normalised by the query so long memories are not favoured. */
    private fun overlap(queryTerms: Set<String>, itemTerms: Set<String>): Double {
        if (itemTerms.isEmpty()) return 0.0
        val shared = queryTerms.count { it in itemTerms }
        return shared.toDouble() / queryTerms.size
    }

    /**
     * A stable fact beats an episode that matches equally well: semantic memory
     * is the distilled form, and the episode it was distilled from is
     * redundant. Between episodes, recency still decides.
     */
    private fun scopeWeight(scope: MemoryScope): Double = when (scope) {
        MemoryScope.SEMANTIC -> 1.4
        MemoryScope.EPISODIC -> 1.0
        MemoryScope.WORKING -> 1.0
    }

    companion object {
        const val CONVERSATION_KEY = "conversationId"
        private const val RECENCY_WEIGHT = 0.25
        private const val MIN_TERM_LENGTH = 3
        private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")

        /**
         * Default extraction: keep working-memory entries verbatim as episodic
         * memories. Crude but honest — it never invents a fact the user did not
         * say, which a weak extraction model absolutely will.
         */
        val PromoteWorkingMemory = MemoryExtractor { _, working ->
            working.map { it.copy(scope = MemoryScope.EPISODIC) }
        }
    }
}
