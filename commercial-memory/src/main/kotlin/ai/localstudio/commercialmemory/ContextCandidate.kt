package ai.localstudio.commercialmemory

import ai.localstudio.memory.MemoryItem

/**
 * One [MemoryItem] plus the private-layer signals scored against it for a
 * specific query. [ContextRanker] exists specifically to compute and weigh
 * these without Mobile_mem0's generic retrieval needing to change.
 *
 * [lexicalScore], [taskRelevance], and [recencyScore] are computed fresh
 * from each item's own fields (see [buildCandidates]) rather than reused
 * from Mobile_mem0— its own `MemoryItem.relevance` already bakes its
 * generic scope/recency weighting in, and reusing it here would double-count
 * exactly what this layer computes independently with its own, swappable
 * weights. [semanticScore] is different: it is Mobile_mem0's own raw cosine
 * similarity ([ai.localstudio.memory.MemoryCandidate.semanticScore]),
 * carried straight through rather than recomputed — it has no scope/recency
 * weighting baked in to begin with (nothing to double-count), and
 * recomputing it here would mean this module embedding text a second time
 * for no reason.
 */
data class ContextCandidate(
    val memory: MemoryItem,
    /** Shared-term overlap between the query and this memory's text, in [0, 1]. */
    val lexicalScore: Double,
    /** How directly this memory's terms bear on the query's terms — looser than [lexicalScore], in [0, 1]. */
    val taskRelevance: Double = 0.0,
    /** How recent this memory is relative to the candidate pool, in [0, 1]. */
    val recencyScore: Double = 0.0,
    /** A source-specific priority independent of the query itself (e.g. WORKING > SEMANTIC), in [0, 1]. */
    val sourcePriority: Double = 0.0,
    /** Mobile_mem0's raw cosine similarity, in [-1, 1]; null when no semantic index was configured or this item has no vector yet. */
    val semanticScore: Double? = null,
)
