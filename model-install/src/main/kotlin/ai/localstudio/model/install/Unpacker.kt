package ai.localstudio.model.install

import ai.localstudio.model.UnpackSpec
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * Unpacks an archive artifact into a directory. Only zip for now — the one
 * format any catalogued model uses (Vosk); the tar family is declared by the
 * domain and fails here with [UnsupportedArchiveException] until a model
 * needs it.
 *
 * Every entry must land inside the destination after
 * [UnpackSpec.stripComponents] are dropped ("zip slip" is rejected, not
 * skipped); entries with nothing left after stripping (the wrapper folder
 * itself) are skipped, like the legacy Vosk extractor does.
 */
object Unpacker {

    class UnsupportedArchiveException(format: String) : IOException("unsupported archive format: $format")

    /** Archive extensions stripped to name the unpack directory: "model.zip" → "model". */
    fun directoryNameFor(fileName: String): String {
        val name = fileName.substringAfterLast('/')
        val ext = UnpackSpec.KNOWN_FORMATS.sortedByDescending { it.length }.firstOrNull { name.endsWith(".$it") }
        val base = if (ext != null) name.removeSuffix(".$ext") else "$name.d"
        return base.ifEmpty { "unpacked" }
    }

    /** Returns the total number of bytes written. [destination] is replaced. */
    fun unpack(archive: File, spec: UnpackSpec, destination: File, cancel: CancellationSignal = CancellationSignal.NONE): Long {
        if (spec.format != "zip") throw UnsupportedArchiveException(spec.format)
        if (destination.exists()) destination.deleteRecursively()
        destination.mkdirs()
        val root = destination.canonicalFile
        var written = 0L
        ZipInputStream(archive.inputStream().buffered(1 shl 16)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (cancel.isCancelled()) throw TransferCancelledException()
                val segments = entry.name.split('/').filter { it.isNotEmpty() }
                val kept = segments.drop(spec.stripComponents)
                if (!entry.isDirectory && kept.isNotEmpty()) {
                    val out = File(destination, kept.joinToString("/"))
                    val canonical = out.canonicalFile
                    if (!canonical.path.startsWith(root.path + File.separator)) {
                        throw IOException("archive entry escapes the install directory: ${entry.name}")
                    }
                    canonical.parentFile?.mkdirs()
                    canonical.outputStream().use { written += zip.copyTo(it) }
                }
                zip.closeEntry()
            }
        }
        return written
    }
}
