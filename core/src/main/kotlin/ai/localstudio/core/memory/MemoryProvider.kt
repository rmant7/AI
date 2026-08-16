package ai.localstudio.core.memory

/**
 * Memory is split by lifetime and meaning, not by storage engine.
 *
 * - [WORKING]  — the current conversation, dropped when it ends.
 * - [EPISODIC] — what happened before: decisions, events, sessions.
 * - [SEMANTIC] — stable facts: preferences, project properties, entities.
 */
enum class MemoryScope { WORKING, EPISODIC, SEMANTIC }

data class MemoryItem(
    val id: String,
    val text: String,
    val scope: MemoryScope,
    val createdAt: Long,
    val relevance: Double? = null,
    val metadata: Map<String, String> = emptyMap(),
)

data class MemoryQuery(
    val text: String,
    val scopes: Set<MemoryScope> = setOf(MemoryScope.EPISODIC, MemoryScope.SEMANTIC),
    val limit: Int = 8,
    val metadataFilter: Map<String, String> = emptyMap(),
)

/**
 * Long-term memory, deliberately kept behind an interface.
 *
 * Mem0 is a reasonable first implementation, but memory outlives any single
 * model *and* any single backend: the application must be able to move from
 * Mem0 to a local store without touching the context engine.
 */
interface MemoryProvider {
    suspend fun search(query: MemoryQuery): List<MemoryItem>

    /** Returns the id of the stored memory. */
    suspend fun remember(text: String, scope: MemoryScope, metadata: Map<String, String> = emptyMap()): String

    suspend fun forget(id: String)

    /** Distils a finished exchange into durable memories. Returns what was written. */
    suspend fun consolidate(conversationId: String): List<MemoryItem>
}
