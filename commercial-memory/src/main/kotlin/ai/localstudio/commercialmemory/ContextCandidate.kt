package ai.localstudio.commercialmemory

import ai.localstudio.memory.MemoryItem

/**
 * One [MemoryItem] plus the private-layer signals scored against it for a
 * specific query. Mobile_mem0 knows nothing of these — [ContextRanker]
 * exists specifically to compute and weigh them without Mobile_mem0's
 * generic retrieval needing to change.
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
)
