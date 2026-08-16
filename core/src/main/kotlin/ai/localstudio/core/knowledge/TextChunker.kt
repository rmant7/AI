package ai.localstudio.core.knowledge

data class Chunk(
    val text: String,
    val ordinal: Int,
    val startChar: Int,
    val endChar: Int,
)

/**
 * Splits a document into overlapping chunks for embedding.
 *
 * Boundaries follow the text's own structure — paragraphs first, sentences
 * next, characters only as a last resort — because a chunk that ends mid-clause
 * embeds badly and reads worse when it is quoted back to the user.
 *
 * Consecutive chunks overlap by [overlapChars]: an answer whose evidence
 * straddles a boundary is otherwise lost by every retrieval strategy, no matter
 * how good the embedder.
 */
class TextChunker(
    private val targetChars: Int = 1_200,
    private val overlapChars: Int = 200,
    private val minChunkChars: Int = 120,
) {
    init {
        require(targetChars > 0) { "targetChars must be positive" }
        require(overlapChars in 0 until targetChars) { "overlapChars must be smaller than targetChars" }
        require(minChunkChars in 0..targetChars) { "minChunkChars must not exceed targetChars" }
    }

    fun chunk(text: String): List<Chunk> {
        val normalized = text.replace("\r\n", "\n").trim()
        if (normalized.isEmpty()) return emptyList()

        val pieces = splitToPieces(normalized)
        val chunks = mutableListOf<Chunk>()
        val current = StringBuilder()
        var currentStart = 0

        fun flush() {
            val body = current.toString().trim()
            if (body.isEmpty()) return
            chunks += Chunk(body, chunks.size, currentStart, currentStart + body.length)
            val carry = body.takeLast(overlapChars)
            current.setLength(0)
            if (overlapChars > 0 && carry.length == overlapChars) {
                current.append(carry).append(' ')
                currentStart += body.length - overlapChars
            } else {
                currentStart += body.length
            }
        }

        for (piece in pieces) {
            if (current.isNotEmpty() && current.length + piece.length > targetChars) flush()
            current.append(piece).append("\n")
        }
        // The tail is merged into the previous chunk when it is too small to
        // stand on its own — a 20-character chunk retrieves noise.
        val tail = current.toString().trim()
        if (tail.isNotEmpty()) {
            if (tail.length < minChunkChars && chunks.isNotEmpty()) {
                val last = chunks.removeAt(chunks.size - 1)
                chunks += last.copy(
                    text = "${last.text}\n$tail",
                    endChar = last.endChar + tail.length + 1,
                )
            } else {
                flush()
            }
        }
        return chunks
    }

    private fun splitToPieces(text: String): List<String> {
        val pieces = mutableListOf<String>()
        for (paragraph in text.split(PARAGRAPH_BREAK)) {
            val trimmed = paragraph.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.length <= targetChars) {
                pieces += trimmed
                continue
            }
            for (sentence in splitSentences(trimmed)) {
                if (sentence.length <= targetChars) {
                    pieces += sentence
                } else {
                    // A single unbroken run longer than the target (minified data,
                    // a table, a language without spaces): hard-split it.
                    sentence.chunked(targetChars).forEach { pieces += it }
                }
            }
        }
        return pieces
    }

    private fun splitSentences(paragraph: String): List<String> {
        val sentences = mutableListOf<String>()
        var start = 0
        SENTENCE_END.findAll(paragraph).forEach { match ->
            val end = match.range.last + 1
            val sentence = paragraph.substring(start, end).trim()
            if (sentence.isNotEmpty()) sentences += sentence
            start = end
        }
        paragraph.substring(start).trim().takeIf { it.isNotEmpty() }?.let { sentences += it }
        return sentences
    }

    private companion object {
        val PARAGRAPH_BREAK = Regex("\n\\s*\n")
        val SENTENCE_END = Regex("[.!?…]+[\\s\"»)]+")
    }
}
