package ai.localstudio.core.knowledge

import ai.localstudio.core.model.DocumentRef
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Deterministic bag-of-words embedder: shared vocabulary means high cosine, no model needed. */
class HashingEmbedder(private val dimensions: Int = 64) : EmbeddingFunction {
    override suspend fun embed(texts: List<String>): List<FloatArray> = texts.map { text ->
        val vector = FloatArray(dimensions)
        text.lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 3 }
            .forEach { token ->
                val slot = ((token.hashCode() % dimensions) + dimensions) % dimensions
                vector[slot] += 1f
            }
        vector
    }
}

class LocalKnowledgeProviderTest {

    private val documents = mutableMapOf(
        "doc://runtime" to """
            Runtime Manager решает, что держать в памяти.
            Модель, которая используется прямо сейчас, никогда не вытесняется.

            При нехватке бюджета вытесняется наименее давно использованная свободная модель.
        """.trimIndent(),
        "doc://memory" to """
            Память разделена на working, episodic и semantic.
            Knowledge base хранит документы пользователя отдельно от памяти.
        """.trimIndent(),
    )

    private fun provider(
        embedder: EmbeddingFunction = HashingEmbedder(),
        index: InMemoryVectorIndex = InMemoryVectorIndex(),
    ) = LocalKnowledgeProvider(
        reader = { doc -> documents[doc.uri] ?: throw IllegalArgumentException("no such document: ${doc.uri}") },
        embedder = embedder,
        index = index,
        chunker = TextChunker(targetChars = 120, overlapChars = 20, minChunkChars = 30),
    )

    @Test
    fun `ingest chunks and indexes a document`() = runBlocking {
        val knowledge = provider()

        val report = knowledge.ingest(DocumentRef("doc://runtime", title = "Runtime"))

        assertTrue(report.chunkCount > 1)
        assertTrue(!report.skipped)
        assertEquals(setOf("doc://runtime"), knowledge.indexedDocuments())
    }

    @Test
    fun `search returns the passage that shares the query's terms`() = runBlocking {
        val knowledge = provider()
        knowledge.ingest(DocumentRef("doc://runtime"))
        knowledge.ingest(DocumentRef("doc://memory"))

        val hits = knowledge.search(KnowledgeQuery("что вытесняется при нехватке бюджета", limit = 3))

        assertTrue(hits.isNotEmpty())
        assertTrue(hits.first().text.contains("вытесняется"), "got: ${hits.first().text}")
        assertEquals("doc://runtime", hits.first().documentId)
    }

    @Test
    fun `results carry the document and are ordered by score`() = runBlocking {
        val knowledge = provider()
        knowledge.ingest(DocumentRef("doc://runtime", title = "Runtime"))

        val hits = knowledge.search(KnowledgeQuery("модель память", limit = 5))

        assertEquals(hits.map { it.score }.sortedDescending(), hits.map { it.score })
        assertTrue(hits.all { it.metadata["title"] == "Runtime" })
    }

    @Test
    fun `re-ingesting replaces the old chunks instead of duplicating them`() = runBlocking {
        val index = InMemoryVectorIndex()
        val knowledge = provider(index = index)
        knowledge.ingest(DocumentRef("doc://runtime"))
        val firstCount = index.size

        documents["doc://runtime"] = "Совсем другой текст про раннер и бюджет памяти."
        val report = knowledge.ingest(DocumentRef("doc://runtime"))

        assertEquals(report.chunkCount, index.size)
        assertTrue(index.size < firstCount)
    }

    @Test
    fun `deleting a document removes its chunks`() = runBlocking {
        val knowledge = provider()
        knowledge.ingest(DocumentRef("doc://runtime"))
        knowledge.ingest(DocumentRef("doc://memory"))

        knowledge.delete("doc://runtime")

        assertEquals(setOf("doc://memory"), knowledge.indexedDocuments())
        assertTrue(knowledge.search(KnowledgeQuery("вытесняется")).none { it.documentId == "doc://runtime" })
    }

    @Test
    fun `an unreadable document is reported, not thrown`() = runBlocking {
        val report = provider().ingest(DocumentRef("doc://missing"))

        assertTrue(report.skipped)
        assertEquals(0, report.chunkCount)
        assertTrue(report.error != null)
    }

    @Test
    fun `changing the embedding model is reported as needing a re-index`() = runBlocking {
        val index = InMemoryVectorIndex()
        provider(index = index).ingest(DocumentRef("doc://runtime"))

        val failure = assertFailsWith<IndexDimensionMismatchException> {
            provider(embedder = HashingEmbedder(dimensions = 128), index = index)
                .ingest(DocumentRef("doc://memory"))
        }

        assertEquals(64, failure.expected)
        assertEquals(128, failure.actual)
        assertTrue(failure.message!!.contains("re-indexing"))
    }

    @Test
    fun `an empty index and a blank query return nothing rather than failing`() = runBlocking {
        val knowledge = provider()

        assertTrue(knowledge.search(KnowledgeQuery("что угодно")).isEmpty())
        knowledge.ingest(DocumentRef("doc://runtime"))
        assertTrue(knowledge.search(KnowledgeQuery("   ")).isEmpty())
    }

    @Test
    fun `a reranker may reorder the retrieved chunks`() = runBlocking {
        val knowledge = LocalKnowledgeProvider(
            reader = { doc -> documents.getValue(doc.uri) },
            embedder = HashingEmbedder(),
            chunker = TextChunker(targetChars = 120, overlapChars = 20, minChunkChars = 30),
            reranker = { _, chunks -> chunks.sortedBy { it.ordinal } },
        )
        knowledge.ingest(DocumentRef("doc://runtime"))

        val reranked = knowledge.search(KnowledgeQuery("память модель", limit = 5, rerank = true))
        val raw = knowledge.search(KnowledgeQuery("память модель", limit = 5, rerank = false))

        assertEquals(reranked.map { it.ordinal }.sorted(), reranked.map { it.ordinal })
        assertEquals(raw.map { it.score }.sortedDescending(), raw.map { it.score })
    }
}
