package ai.localstudio.core.knowledge

import ai.localstudio.core.model.DocumentRef

/** Turns a document reference into plain text. Parsing PDFs, HTML and code is a platform concern. */
fun interface DocumentReader {
    suspend fun read(document: DocumentRef): String
}

/** Produces one embedding per input text, in the same order. */
fun interface EmbeddingFunction {
    suspend fun embed(texts: List<String>): List<FloatArray>
}

/** Optional second-stage ordering. Cross-encoders are far better than cosine, and far slower. */
fun interface Reranker {
    suspend fun rerank(query: String, chunks: List<KnowledgeChunk>): List<KnowledgeChunk>
}

/**
 * The thin RAG layer: parse → chunk → embed → search → rerank.
 *
 * Everything specific lives behind an injected interface, so the same provider
 * runs against an on-device embedder or an OpenAI-compatible endpoint during
 * development, over an in-memory index today and an embedded vector store
 * later.
 */
class LocalKnowledgeProvider(
    private val reader: DocumentReader,
    private val embedder: EmbeddingFunction,
    private val index: InMemoryVectorIndex = InMemoryVectorIndex(),
    private val chunker: TextChunker = TextChunker(),
    private val reranker: Reranker? = null,
) : KnowledgeProvider {

    override suspend fun ingest(document: DocumentRef): IngestReport {
        val documentId = document.uri
        val text = try {
            reader.read(document)
        } catch (e: Exception) {
            return IngestReport(documentId, chunkCount = 0, skipped = true, error = e.message ?: e.toString())
        }

        val chunks = chunker.chunk(text)
        if (chunks.isEmpty()) {
            return IngestReport(documentId, chunkCount = 0, skipped = true, error = "document has no text")
        }

        // Re-ingesting a document replaces it wholesale: leaving the old chunks
        // behind would return stale passages that no longer exist in the file.
        index.deleteDocument(documentId)

        val embeddings = embedder.embed(chunks.map { it.text })
        require(embeddings.size == chunks.size) {
            "Embedder returned ${embeddings.size} vectors for ${chunks.size} chunks"
        }

        chunks.forEachIndexed { i, chunk ->
            index.upsert(
                IndexedChunk(
                    id = "$documentId#${chunk.ordinal}",
                    documentId = documentId,
                    text = chunk.text,
                    ordinal = chunk.ordinal,
                    embedding = embeddings[i],
                    metadata = buildMap {
                        document.title?.let { put("title", it) }
                        document.mimeType?.let { put("mimeType", it) }
                        put("startChar", chunk.startChar.toString())
                    },
                ),
            )
        }
        return IngestReport(documentId, chunkCount = chunks.size)
    }

    override suspend fun search(query: KnowledgeQuery): List<KnowledgeChunk> {
        if (query.text.isBlank() || index.size == 0) return emptyList()

        val queryEmbedding = embedder.embed(listOf(query.text)).firstOrNull() ?: return emptyList()
        val hits = index.search(queryEmbedding, query.limit, query.documentIds).map { (chunk, score) ->
            KnowledgeChunk(
                id = chunk.id,
                documentId = chunk.documentId,
                text = chunk.text,
                score = score,
                ordinal = chunk.ordinal,
                metadata = chunk.metadata,
            )
        }

        val ranker = reranker
        return if (query.rerank && ranker != null) ranker.rerank(query.text, hits) else hits
    }

    override suspend fun delete(documentId: String) {
        index.deleteDocument(documentId)
    }

    fun indexedDocuments(): Set<String> = index.documentIds()

    /** Dimensionality of the current index — null when empty. Shown next to the re-index button. */
    fun embeddingDimensions(): Int? = index.dimensions
}
