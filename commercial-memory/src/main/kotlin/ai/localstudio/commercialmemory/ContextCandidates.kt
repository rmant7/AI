package ai.localstudio.commercialmemory

import ai.localstudio.memory.MemoryItem
import ai.localstudio.memory.MemoryScope

/**
 * Turns raw retrieval results ([AppMemory.candidates]) into scored
 * [ContextCandidate]s for [ContextRanker] to rank. Computed fresh from each
 * item's own fields rather than reused from Mobile_mem0's own internal
 * relevance score: that score already bakes in Mobile_mem0's own generic
 * scope/recency weighting for its own retrieval-ordering purposes, and the
 * whole point of this private layer is to score independently, with its own
 * (eventually swappable) weights — see the module README.
 */
internal fun buildCandidates(query: String, items: List<MemoryItem>): List<ContextCandidate> {
    val newest = items.maxOfOrNull { it.createdAt }
    val oldest = items.minOfOrNull { it.createdAt } ?: newest
    val span = if (newest != null && oldest != null) (newest - oldest).coerceAtLeast(1) else 1L

    return items.map { item ->
        ContextCandidate(
            memory = item,
            lexicalScore = lexicalOverlap(query, item.text),
            taskRelevance = taskRelevance(query, item.text),
            recencyScore = if (newest == null || oldest == null || item.scope == MemoryScope.SEMANTIC) {
                0.0
            } else {
                (item.createdAt - oldest).toDouble() / span
            },
            sourcePriority = when (item.scope) {
                MemoryScope.WORKING -> 1.0
                MemoryScope.SEMANTIC -> 0.6
                MemoryScope.EPISODIC -> 0.4
            },
        )
    }
}

private fun lexicalOverlap(query: String, text: String): Double {
    val q = tokens(query)
    if (q.isEmpty()) return 0.0
    val t = tokens(text)
    return q.count { it in t }.toDouble() / q.size
}

/** Looser than [lexicalOverlap]: also credits a query term that is a substring of (or contains) a memory term. */
private fun taskRelevance(query: String, text: String): Double {
    val q = tokens(query)
    if (q.isEmpty()) return 0.0
    val t = tokens(text)
    return q.count { queryTerm -> t.any { it == queryTerm || it.contains(queryTerm) || queryTerm.contains(it) } }
        .toDouble() / q.size
}

private fun tokens(value: String): Set<String> =
    value.lowercase()
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.length >= 2 }
        .toSet()
