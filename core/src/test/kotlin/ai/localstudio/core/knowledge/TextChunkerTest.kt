package ai.localstudio.core.knowledge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextChunkerTest {

    private val chunker = TextChunker(targetChars = 200, overlapChars = 40, minChunkChars = 50)

    @Test
    fun `short text stays one chunk`() {
        val chunks = chunker.chunk("Короткий документ про архитектуру.")

        assertEquals(1, chunks.size)
        assertEquals(0, chunks.single().ordinal)
    }

    @Test
    fun `empty input produces nothing`() {
        assertTrue(chunker.chunk("").isEmpty())
        assertTrue(chunker.chunk("   \n\n  ").isEmpty())
    }

    @Test
    fun `paragraphs are packed up to the target size`() {
        val paragraph = "П".repeat(80)
        val chunks = chunker.chunk(List(6) { paragraph }.joinToString("\n\n"))

        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.text.length <= 300 }, "chunks: ${chunks.map { it.text.length }}")
        assertEquals(chunks.indices.toList(), chunks.map { it.ordinal })
    }

    @Test
    fun `consecutive chunks overlap so evidence on a boundary is not lost`() {
        val chunks = chunker.chunk(List(8) { "Абзац номер $it. " + "х".repeat(70) }.joinToString("\n\n"))

        assertTrue(chunks.size >= 2)
        val tailOfFirst = chunks[0].text.takeLast(40)
        assertTrue(chunks[1].text.startsWith(tailOfFirst), "second chunk should carry the first chunk's tail")
    }

    @Test
    fun `a paragraph longer than the target is split on sentence boundaries`() {
        val sentences = (1..12).joinToString(" ") { "Это предложение номер $it с некоторым текстом." }
        val chunks = chunker.chunk(sentences)

        assertTrue(chunks.size > 1)
        assertTrue(chunks.first().text.trimEnd().endsWith("."), "chunk should end at a sentence: ${chunks.first().text}")
    }

    @Test
    fun `an unbroken run is hard-split rather than dropped`() {
        val blob = "a".repeat(1000)
        val chunks = chunker.chunk(blob)

        assertTrue(chunks.isNotEmpty())
        assertTrue(chunks.sumOf { it.text.count { c -> c == 'a' } } >= 1000)
    }

    @Test
    fun `a tiny tail is merged into the previous chunk instead of standing alone`() {
        val chunks = chunker.chunk(List(4) { "П".repeat(90) }.joinToString("\n\n") + "\n\nКонец.")

        assertTrue(chunks.last().text.trimEnd().endsWith("Конец."))
        assertTrue(chunks.none { it.text.length < 50 }, "sizes: ${chunks.map { it.text.length }}")
    }

    @Test
    fun `overlap must be smaller than the target`() {
        val failure = runCatching { TextChunker(targetChars = 100, overlapChars = 100) }
        assertTrue(failure.isFailure)
    }
}
