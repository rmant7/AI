package ai.localstudio.app.attach

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.IOException

/**
 * Turns an attached file into plain-text chunks the memory provider can hold.
 *
 * There is no vector store here — [ai.localstudio.memory.FileMemoryStore]
 * already does lexical retrieval over whatever text it is given, so ingestion's
 * only job is to get clean text out of the file the user picked.
 */
object DocumentIngest {

    private var pdfBoxReady = false

    class UnsupportedFileException(message: String) : IOException(message)

    fun extractText(context: Context, uri: Uri): String {
        val mimeType = context.contentResolver.getType(uri).orEmpty()
        val name = fileName(context, uri)
        return when {
            mimeType == "application/pdf" || name.endsWith(".pdf", ignoreCase = true) -> extractPdf(context, uri)
            mimeType.startsWith("text/") || name.endsWith(".txt", ignoreCase = true) ||
                name.endsWith(".md", ignoreCase = true) -> extractPlainText(context, uri)

            else -> throw UnsupportedFileException("Поддерживаются только .txt и .pdf")
        }
    }

    fun fileName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) return cursor.getString(index).orEmpty()
        }
        return uri.lastPathSegment.orEmpty()
    }

    private fun extractPlainText(context: Context, uri: Uri): String =
        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?: throw IOException("Не удалось открыть файл")

    private fun extractPdf(context: Context, uri: Uri): String {
        if (!pdfBoxReady) {
            PDFBoxResourceLoader.init(context.applicationContext)
            pdfBoxReady = true
        }
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Не удалось открыть файл")
        return input.use { stream ->
            PDDocument.load(stream).use { document -> PDFTextStripper().getText(document) }
        }
    }

    /**
     * Paragraph-first chunking: a paragraph boundary is a better break point
     * than a fixed character count, and only paragraphs that themselves
     * overflow [chunkSize] get split mid-text.
     */
    fun chunk(text: String, chunkSize: Int = 1200): List<String> {
        val paragraphs = text.split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotEmpty() }
        val chunks = mutableListOf<String>()
        val current = StringBuilder()

        fun flush() {
            if (current.isNotEmpty()) {
                chunks += current.toString().trim()
                current.setLength(0)
            }
        }

        for (paragraph in paragraphs) {
            if (paragraph.length > chunkSize) {
                flush()
                paragraph.chunked(chunkSize).forEach { chunks += it }
                continue
            }
            if (current.length + paragraph.length + 2 > chunkSize) flush()
            if (current.isNotEmpty()) current.append("\n\n")
            current.append(paragraph)
        }
        flush()
        return chunks
    }
}
