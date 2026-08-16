package ai.localstudio.core.knowledge

import kotlin.math.sqrt

object Vectors {

    /**
     * Cosine similarity in -1..1. Zero vectors yield 0 rather than NaN: an
     * embedder that returns zeros is a bug to surface, not a crash to trigger
     * in the middle of a search.
     */
    fun cosine(a: FloatArray, b: FloatArray): Double {
        require(a.size == b.size) { "Dimension mismatch: ${a.size} vs ${b.size}" }
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            dot += a[i].toDouble() * b[i]
            normA += a[i].toDouble() * a[i]
            normB += b[i].toDouble() * b[i]
        }
        if (normA == 0.0 || normB == 0.0) return 0.0
        return dot / (sqrt(normA) * sqrt(normB))
    }
}

/**
 * Raised when a vector of the wrong size reaches the index.
 *
 * This is the failure mode of changing the embedding model without
 * re-indexing, which is why the message says so: without it, the symptom is
 * "search returns nothing useful" and the cause is invisible.
 */
class IndexDimensionMismatchException(val expected: Int, val actual: Int) : Exception(
    "Index holds $expected-dimensional vectors but got $actual — " +
        "the embedding model changed and the knowledge base needs re-indexing",
)

data class IndexedChunk(
    val id: String,
    val documentId: String,
    val text: String,
    val ordinal: Int,
    val embedding: FloatArray,
    val metadata: Map<String, String> = emptyMap(),
) {
    // Generated equals/hashCode would compare the FloatArray by identity, which
    // makes two logically identical chunks unequal. Identity here is the id.
    override fun equals(other: Any?): Boolean = other is IndexedChunk && other.id == id
    override fun hashCode(): Int = id.hashCode()
}

/**
 * A vector store small enough to hold the whole index in memory.
 *
 * Deliberately the simplest thing that works: brute-force cosine over a few
 * thousand chunks costs microseconds, and an embedded vector database can
 * replace this behind [KnowledgeProvider] once a corpus outgrows it. Starting
 * with the database instead would buy nothing and cost a dependency.
 */
class InMemoryVectorIndex {

    private val chunks = LinkedHashMap<String, IndexedChunk>()
    var dimensions: Int? = null
        private set

    val size: Int get() = chunks.size

    fun upsert(chunk: IndexedChunk) {
        val expected = dimensions
        if (expected == null) {
            dimensions = chunk.embedding.size
        } else if (expected != chunk.embedding.size) {
            throw IndexDimensionMismatchException(expected, chunk.embedding.size)
        }
        chunks[chunk.id] = chunk
    }

    fun search(
        queryEmbedding: FloatArray,
        limit: Int,
        documentIds: Set<String> = emptySet(),
    ): List<Pair<IndexedChunk, Double>> {
        val expected = dimensions ?: return emptyList()
        if (queryEmbedding.size != expected) {
            throw IndexDimensionMismatchException(expected, queryEmbedding.size)
        }
        return chunks.values
            .filter { documentIds.isEmpty() || it.documentId in documentIds }
            .map { it to Vectors.cosine(queryEmbedding, it.embedding) }
            .sortedWith(compareByDescending<Pair<IndexedChunk, Double>> { it.second }.thenBy { it.first.id })
            .take(limit)
    }

    fun deleteDocument(documentId: String): Int {
        val victims = chunks.values.filter { it.documentId == documentId }.map { it.id }
        victims.forEach { chunks.remove(it) }
        if (chunks.isEmpty()) dimensions = null
        return victims.size
    }

    fun documentIds(): Set<String> = chunks.values.map { it.documentId }.toSet()
}
