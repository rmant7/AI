package ai.localstudio.core.knowledge

import ai.localstudio.core.model.DocumentRef

data class KnowledgeChunk(
    val id: String,
    val documentId: String,
    val text: String,
    val score: Double,
    val ordinal: Int = 0,
    val metadata: Map<String, String> = emptyMap(),
)

data class IngestReport(
    val documentId: String,
    val chunkCount: Int,
    val skipped: Boolean = false,
    val error: String? = null,
)

data class KnowledgeQuery(
    val text: String,
    val limit: Int = 6,
    val documentIds: Set<String> = emptySet(),
    val rerank: Boolean = true,
)

/**
 * The user's documents: parse, chunk, embed, search, rerank.
 *
 * Kept separate from [ai.localstudio.memory.MemoryProvider] on purpose —
 * knowledge is what the user gave the system, memory is what the system
 * concluded. They have different lifecycles, different deletion semantics and
 * different storage.
 */
interface KnowledgeProvider {
    suspend fun ingest(document: DocumentRef): IngestReport

    suspend fun search(query: KnowledgeQuery): List<KnowledgeChunk>

    suspend fun delete(documentId: String)
}
